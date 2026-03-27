package com.example.media_player;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchFragment extends Fragment
        implements PlaybackObserver, TrackAdapter.OnTrackClickListener,
        SearchFilterSheet.OnFiltersAppliedListener {

    private static final String TAG = "SearchFragment";
    private static final long DEBOUNCE_MS = 300;

    private TrackDataProvider dataProvider;
    private SearchDao searchDao;
    private StatsDao statsDao;
    private PlaylistDao playlistDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText etSearch;
    private TextView btnClear;
    private LinearLayout filterRow;
    private TextView btnFilters;
    private LinearLayout chipContainer;
    private TextView btnSave;
    private RecyclerView recyclerResults;
    private RecyclerView recyclerLanding;
    private TextView tvNoResults;

    private final List<Track> resultTracks = new ArrayList<>();
    private TrackAdapter resultAdapter;

    private final List<Object> landingItems = new ArrayList<>();
    private LandingAdapter landingAdapter;

    private SearchCriteria currentCriteria;
    private Runnable pendingSearch;

    // State: 0=landing, 1=results, 2=no-results
    private int contentState = 0;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
        MatrixPlayerDatabase db = MatrixPlayerDatabase.getInstance(context);
        searchDao = new SearchDao(db);
        statsDao = new StatsDao(db);
        playlistDao = new PlaylistDao(db);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etSearch = view.findViewById(R.id.et_search);
        btnClear = view.findViewById(R.id.btn_clear);
        filterRow = view.findViewById(R.id.filter_row);
        btnFilters = view.findViewById(R.id.btn_filters);
        chipContainer = view.findViewById(R.id.chip_container);
        btnSave = view.findViewById(R.id.btn_save);
        recyclerResults = view.findViewById(R.id.recycler_results);
        recyclerLanding = view.findViewById(R.id.recycler_landing);
        tvNoResults = view.findViewById(R.id.tv_no_results);

        // Results adapter
        resultAdapter = new TrackAdapter(resultTracks, this);
        recyclerResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerResults.setAdapter(resultAdapter);
        setItemAnimatorDurations(recyclerResults);

        // Landing adapter
        landingAdapter = new LandingAdapter(landingItems, this);
        recyclerLanding.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLanding.setAdapter(landingAdapter);
        setItemAnimatorDurations(recyclerLanding);

        // Filter row always visible (contains the Filters button)
        filterRow.setVisibility(View.VISIBLE);

        // Search debounce
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
                String query = s.toString().trim();
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (query.isEmpty() && currentCriteria == null) {
                    showLanding();
                    return;
                }
                pendingSearch = () -> performSearch(query);
                mainHandler.postDelayed(pendingSearch, DEBOUNCE_MS);
            }
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
                performSearch(etSearch.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });

        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            if (currentCriteria != null) {
                // Clear text but keep filters active, re-search
                performSearch("");
            }
        });

        btnFilters.setOnClickListener(v -> {
            SearchFilterSheet sheet = new SearchFilterSheet();
            if (currentCriteria != null) {
                Bundle args = new Bundle();
                args.putString("criteria_json", currentCriteria.toJson());
                sheet.setArguments(args);
            }
            sheet.setListener(this);
            sheet.show(getChildFragmentManager(), "filters");
        });

        btnSave.setOnClickListener(v -> promptSaveSmartPlaylist());

        loadLandingData();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            loadLandingData();
            etSearch.requestFocus();
            showKeyboard();
        } else {
            hideKeyboard();
        }
    }

    private void showKeyboard() {
        mainHandler.postDelayed(() -> {
            if (etSearch == null || !isAdded()) return;
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }

    private void hideKeyboard() {
        if (etSearch == null || !isAdded()) return;
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
    }

    /** Called by MainActivity on back press. Returns true if consumed. */
    public boolean handleBack() {
        if (contentState == 1 || contentState == 2) {
            // From results -> show landing
            etSearch.setText("");
            currentCriteria = null;
            updateChips();
            showLanding();
            return true;
        }
        // From landing -> hide fragment (caller handles)
        return false;
    }

    // -- Search --

    private void performSearch(String query) {
        String sanitized = sanitizeFtsQuery(query);
        executor.execute(() -> {
            List<Track> results;
            if (currentCriteria != null) {
                SearchCriteria c = cloneCriteria(currentCriteria);
                if (!sanitized.isEmpty()) c.freeText = sanitized;
                results = searchDao.advancedSearch(c);
            } else if (!sanitized.isEmpty()) {
                results = searchDao.search(sanitized);
            } else {
                results = new ArrayList<>();
            }
            mainHandler.post(() -> {
                resultTracks.clear();
                resultTracks.addAll(results);
                resultAdapter.notifyDataSetChanged();
                resultAdapter.setPlayingTrackId(dataProvider.getPlayingTrackId());
                if (results.isEmpty() && (!sanitized.isEmpty() || currentCriteria != null)) {
                    showNoResults();
                } else if (!results.isEmpty()) {
                    showResults();
                } else {
                    showLanding();
                }
            });
        });
    }

    private String sanitizeFtsQuery(String query) {
        if (query == null) return "";
        // Strip FTS special characters
        return query.replaceAll("[\"*(){}\\[\\]^~]", "").trim();
    }

    private SearchCriteria cloneCriteria(SearchCriteria src) {
        return SearchCriteria.fromJson(src.toJson());
    }

    // -- Content state --

    private void showLanding() {
        contentState = 0;
        recyclerResults.setVisibility(View.GONE);
        tvNoResults.setVisibility(View.GONE);
        recyclerLanding.setVisibility(View.VISIBLE);
    }

    private void showResults() {
        contentState = 1;
        recyclerLanding.setVisibility(View.GONE);
        tvNoResults.setVisibility(View.GONE);
        recyclerResults.setVisibility(View.VISIBLE);
    }

    private void showNoResults() {
        contentState = 2;
        recyclerLanding.setVisibility(View.GONE);
        recyclerResults.setVisibility(View.GONE);
        tvNoResults.setVisibility(View.VISIBLE);
    }

    // -- Landing --

    private void loadLandingData() {
        executor.execute(() -> {
            List<Track> recent = statsDao.getRecentlyPlayed(20);
            List<StatsDao.ArtistPlayCount> topArtists = statsDao.getMostPlayedArtists(10);
            mainHandler.post(() -> {
                landingItems.clear();
                if (!recent.isEmpty()) {
                    landingItems.add(new LandingHeader(getString(R.string.search_recently_played)));
                    landingItems.addAll(recent);
                }
                if (!topArtists.isEmpty()) {
                    landingItems.add(new LandingHeader(getString(R.string.search_top_artists)));
                    landingItems.addAll(topArtists);
                }
                landingAdapter.notifyDataSetChanged();
            });
        });
    }

    // -- Filters --

    @Override
    public void onFiltersApplied(SearchCriteria criteria) {
        currentCriteria = criteria;
        updateChips();
        performSearch(etSearch.getText().toString().trim());
    }

    @Override
    public void onFiltersClear() {
        currentCriteria = null;
        updateChips();
        String query = etSearch.getText().toString().trim();
        if (query.isEmpty()) {
            showLanding();
        } else {
            performSearch(query);
        }
    }

    private void updateChips() {
        chipContainer.removeAllViews();
        boolean hasFilters = currentCriteria != null && !currentCriteria.isEmpty();
        btnSave.setVisibility(hasFilters ? View.VISIBLE : View.GONE);

        if (!hasFilters) return;

        SearchCriteria c = currentCriteria;
        if (c.artist != null) addChip("Artist: " + c.artist);
        if (c.albumArtist != null) addChip("Album Artist: " + c.albumArtist);
        if (c.composer != null) addChip("Composer: " + c.composer);
        if (c.genre != null) addChip("Genre: " + c.genre);
        if (c.yearFrom != null && c.yearTo != null) {
            addChip("Year: " + c.yearFrom + "-" + c.yearTo);
        } else if (c.yearFrom != null) {
            addChip("Year >= " + c.yearFrom);
        } else if (c.yearTo != null) {
            addChip("Year <= " + c.yearTo);
        }
        if (c.source != null) {
            addChip("Source: " + (c.source == 0 ? "Local" : "TIDAL"));
        }
        if (c.format != null) addChip("Format: " + c.format);
        if (c.minBitrate != null) addChip("Bitrate >= " + c.minBitrate + "k");
        if (c.minSampleRate != null) addChip("Sample >= " + c.minSampleRate + "Hz");
    }

    private void addChip(String text) {
        TextView chip = new TextView(requireContext());
        chip.setText(text);
        chip.setTextColor(requireContext().getColor(R.color.green_bright));
        chip.setTextSize(11);
        chip.setBackgroundColor(requireContext().getColor(R.color.bg_item));
        chip.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(8);
        chip.setLayoutParams(lp);
        chipContainer.addView(chip);
    }

    // -- Save smart playlist --

    private void promptSaveSmartPlaylist() {
        if (currentCriteria == null) return;
        EditText input = new EditText(requireContext());
        input.setHint(R.string.search_save_prompt_hint);
        input.setTextColor(requireContext().getColor(R.color.text_primary));
        input.setHintTextColor(requireContext().getColor(R.color.text_secondary));

        new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(R.string.search_save_prompt_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        SearchCriteria c = cloneCriteria(currentCriteria);
                        String freeText = etSearch.getText().toString().trim();
                        if (!freeText.isEmpty()) c.freeText = freeText;
                        executor.execute(() -> {
                            playlistDao.createSmartPlaylist(name, c);
                            mainHandler.post(() -> Toast.makeText(requireContext(),
                                    R.string.search_save_success, Toast.LENGTH_SHORT).show());
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // -- TrackAdapter.OnTrackClickListener --

    @Override
    public void onTrackClick(Track track) {
        dataProvider.playTrack(track, resultTracks);
    }

    // -- PlaybackObserver --

    @Override
    public void onPlayingTrackChanged(long trackId) {
        if (resultAdapter != null) {
            resultAdapter.setPlayingTrackId(trackId);
        }
    }

    private static void setItemAnimatorDurations(RecyclerView rv) {
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(150);
        animator.setRemoveDuration(150);
        animator.setMoveDuration(150);
        animator.setChangeDuration(150);
        rv.setItemAnimator(animator);
    }

    // -- Landing data types --

    static class LandingHeader {
        final String title;
        LandingHeader(String title) { this.title = title; }
    }

    // -- Landing adapter (multi-type: headers, tracks, artists) --

    static class LandingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_TRACK = 1;
        private static final int TYPE_ARTIST = 2;

        private final List<Object> items;
        private final TrackAdapter.OnTrackClickListener trackListener;
        private long playingTrackId = -1;

        LandingAdapter(List<Object> items, TrackAdapter.OnTrackClickListener trackListener) {
            this.items = items;
            this.trackListener = trackListener;
        }

        @Override
        public int getItemViewType(int position) {
            Object item = items.get(position);
            if (item instanceof LandingHeader) return TYPE_HEADER;
            if (item instanceof Track) return TYPE_TRACK;
            if (item instanceof StatsDao.ArtistPlayCount) return TYPE_ARTIST;
            return TYPE_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case TYPE_TRACK:
                    return new TrackAdapter.ViewHolder(
                            inflater.inflate(R.layout.item_track, parent, false));
                case TYPE_ARTIST:
                    return new ArtistVH(
                            inflater.inflate(R.layout.item_category, parent, false));
                default:
                    return new HeaderVH(
                            inflater.inflate(R.layout.item_stat_header, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind((LandingHeader) item);
            } else if (holder instanceof TrackAdapter.ViewHolder) {
                bindTrack((TrackAdapter.ViewHolder) holder, (Track) item, position);
            } else if (holder instanceof ArtistVH) {
                ((ArtistVH) holder).bind((StatsDao.ArtistPlayCount) item);
            }
        }

        private void bindTrack(TrackAdapter.ViewHolder holder, Track track, int position) {
            boolean isPlaying = track.id == playingTrackId;
            holder.itemView.setBackgroundColor(holder.itemView.getContext().getColor(
                    isPlaying ? R.color.bg_item_playing : R.color.bg_item));
            int titleColor = holder.itemView.getContext().getColor(
                    isPlaying ? R.color.text_playing : R.color.text_primary);
            holder.tvTrackNumber.setText("");
            holder.tvTitle.setText(track.title);
            holder.tvTitle.setTextColor(titleColor);
            String subtitle = track.artist;
            if (track.album != null && !track.album.isEmpty()) {
                subtitle = track.artist + " -- " + track.album;
            }
            holder.tvArtist.setText(subtitle);
            holder.tvDuration.setText(track.getFormattedDuration());

            String artworkKey;
            if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
                artworkKey = "tidal:" + track.artworkUrl;
            } else {
                artworkKey = "album:" + track.albumId;
            }
            ArtworkCache.getInstance(holder.ivArtwork.getContext())
                    .loadArtwork(artworkKey, holder.ivArtwork, 120);

            holder.itemView.setOnClickListener(v -> {
                // Build queue from all tracks in landing
                List<Track> queue = new ArrayList<>();
                for (Object o : items) {
                    if (o instanceof Track) queue.add((Track) o);
                }
                trackListener.onTrackClick(track);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void setPlayingTrackId(long trackId) {
            long old = playingTrackId;
            playingTrackId = trackId;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof Track) {
                    Track t = (Track) items.get(i);
                    if (t.id == old || t.id == trackId) notifyItemChanged(i);
                }
            }
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            HeaderVH(View view) {
                super(view);
                tvTitle = view.findViewById(R.id.tv_header_title);
                // Hide summary for landing headers
                View summary = view.findViewById(R.id.tv_header_summary);
                if (summary != null) summary.setVisibility(View.GONE);
            }
            void bind(LandingHeader header) {
                tvTitle.setText(header.title);
            }
        }

        static class ArtistVH extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            final TextView tvSubtitle;
            final TextView tvCount;
            ArtistVH(View view) {
                super(view);
                tvTitle = view.findViewById(R.id.tv_category_title);
                tvSubtitle = view.findViewById(R.id.tv_category_subtitle);
                tvCount = view.findViewById(R.id.tv_category_count);
            }
            void bind(StatsDao.ArtistPlayCount apc) {
                tvTitle.setText(apc.artist);
                tvSubtitle.setVisibility(View.GONE);
                tvCount.setText(String.valueOf(apc.totalPlayCount));
            }
        }
    }
}
