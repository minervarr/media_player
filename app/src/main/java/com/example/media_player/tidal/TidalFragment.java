package com.example.media_player.tidal;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.media_player.ArtworkCache;
import com.example.media_player.CategoryAdapter;
import com.example.media_player.CategoryItem;
import com.example.media_player.PlaybackObserver;
import com.example.media_player.R;
import com.example.media_player.Track;
import com.example.media_player.TrackAdapter;
import com.example.media_player.TrackDataProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TidalFragment extends Fragment
        implements PlaybackObserver, TrackAdapter.OnTrackClickListener,
        CategoryAdapter.OnCategoryClickListener {

    private static final String TAG = "TidalFragment";

    // View states
    private static final int STATE_LOGIN = 0;
    private static final int STATE_HOME = 1;
    private static final int STATE_GRID = 2;
    private static final int STATE_DETAIL = 3;
    private static final int STATE_SEARCH = 4;

    private TrackDataProvider dataProvider;
    private TidalAuth auth;
    private TidalApi api;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Views
    private View loginContainer;
    private View libraryContainer;
    private View detailContainer;
    private TextView tvUserCode;
    private TextView tvVerificationUri;
    private TextView tvLoginStatus;
    private TextView btnLogin;
    private RecyclerView recyclerHome;
    private RecyclerView recyclerDetailTracks;
    private TextView tvDetailTitle;
    private TextView tvLoading;
    private View searchBar;
    private EditText etSearch;
    private TextView btnSearchClose;
    private View gridBackHeader;
    private TextView tvGridTitle;

    // Data
    private final List<CategoryItem> homeCategories = new ArrayList<>();
    private CategoryAdapter homeAdapter;
    private final List<CategoryItem> gridItems = new ArrayList<>();
    private CategoryAdapter gridAdapter;
    private final List<Track> detailTracks = new ArrayList<>();
    private TrackAdapter detailTrackAdapter;

    private int currentState = STATE_LOGIN;
    private String currentGridType;

    // Cached data
    private List<TidalModels.TidalAlbum> cachedAlbums;
    private List<TidalModels.TidalPlaylist> cachedPlaylists;

    private OnBackPressedCallback backCallback;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
    }

    public void setTidalAuth(TidalAuth auth) {
        boolean wasLoggedIn = this.auth != null && this.auth.isLoggedIn();
        this.auth = auth;
        if (auth != null) {
            this.api = new TidalApi(auth);
        }
        if (getView() != null && !wasLoggedIn) {
            updateView();
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                navigateBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tidal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        loginContainer = view.findViewById(R.id.login_container);
        libraryContainer = view.findViewById(R.id.library_container);
        detailContainer = view.findViewById(R.id.detail_container);
        tvUserCode = view.findViewById(R.id.tv_user_code);
        tvVerificationUri = view.findViewById(R.id.tv_verification_uri);
        tvLoginStatus = view.findViewById(R.id.tv_login_status);
        btnLogin = view.findViewById(R.id.btn_tidal_login);
        recyclerHome = view.findViewById(R.id.recycler_home);
        recyclerDetailTracks = view.findViewById(R.id.recycler_detail_tracks);
        tvDetailTitle = view.findViewById(R.id.tv_detail_title);
        tvLoading = view.findViewById(R.id.tv_loading);
        searchBar = view.findViewById(R.id.search_bar);
        etSearch = view.findViewById(R.id.et_search);
        btnSearchClose = view.findViewById(R.id.btn_search_close);
        gridBackHeader = view.findViewById(R.id.grid_back_header);
        tvGridTitle = view.findViewById(R.id.tv_grid_title);
        gridBackHeader.setOnClickListener(v -> navigateBack());

        // Home recycler - grid of categories
        homeAdapter = new CategoryAdapter(homeCategories, this, CategoryAdapter.VIEW_TYPE_GRID);
        recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        recyclerHome.setAdapter(homeAdapter);

        // Detail tracks
        detailTrackAdapter = new TrackAdapter(detailTracks, this);
        recyclerDetailTracks.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerDetailTracks.setAdapter(detailTrackAdapter);

        setItemAnimatorDurations(recyclerHome);
        setItemAnimatorDurations(recyclerDetailTracks);

        view.findViewById(R.id.back_header).setOnClickListener(v -> navigateBack());

        btnLogin.setOnClickListener(v -> startLogin());

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch(etSearch.getText().toString().trim());
                return true;
            }
            return false;
        });

        btnSearchClose.setOnClickListener(v -> {
            if (etSearch.getText().length() > 0) {
                etSearch.setText("");
                gridItems.clear();
                if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
                etSearch.requestFocus();
            } else {
                hideSearch();
                showState(STATE_HOME);
            }
        });

        updateView();
    }

    private void updateView() {
        if (auth == null && getContext() != null) {
            TidalAuth fallback = new TidalAuth(getContext());
            if (fallback.isLoggedIn()) {
                auth = fallback;
                api = new TidalApi(auth);
            }
        }
        if (auth != null && auth.isLoggedIn()) {
            showState(STATE_HOME);
            buildHomeCategories();
            validateSession();
        } else {
            showState(STATE_LOGIN);
            // Show login button, hide code/status until user taps login
            tvUserCode.setVisibility(View.GONE);
            tvVerificationUri.setVisibility(View.GONE);
            tvLoginStatus.setVisibility(View.GONE);
            btnLogin.setVisibility(View.VISIBLE);
        }
    }

    private void showState(int state) {
        currentState = state;
        Fade fade = new Fade();
        fade.setDuration(150);
        fade.setInterpolator(new FastOutSlowInInterpolator());
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), fade);

        loginContainer.setVisibility(state == STATE_LOGIN ? View.VISIBLE : View.GONE);
        libraryContainer.setVisibility(
                (state == STATE_HOME || state == STATE_GRID || state == STATE_SEARCH)
                        ? View.VISIBLE : View.GONE);
        detailContainer.setVisibility(state == STATE_DETAIL ? View.VISIBLE : View.GONE);
        tvLoading.setVisibility(View.GONE);

        backCallback.setEnabled(state == STATE_DETAIL || state == STATE_GRID || state == STATE_SEARCH);

        if (state == STATE_HOME) {
            searchBar.setVisibility(View.GONE);
            gridBackHeader.setVisibility(View.GONE);
            recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
            recyclerHome.setAdapter(homeAdapter);
        } else if (state == STATE_GRID) {
            searchBar.setVisibility(View.GONE);
            gridBackHeader.setVisibility(View.VISIBLE);
        } else if (state == STATE_SEARCH) {
            searchBar.setVisibility(View.VISIBLE);
            gridBackHeader.setVisibility(View.GONE);
        }
    }

    private void navigateBack() {
        switch (currentState) {
            case STATE_DETAIL:
                if ("search".equals(currentGridType)) {
                    // Return to search results with search bar visible
                    showState(STATE_SEARCH);
                    recyclerHome.setAdapter(gridAdapter);
                } else if (currentGridType != null) {
                    showState(STATE_GRID);
                    recyclerHome.setAdapter(gridAdapter);
                } else {
                    showState(STATE_HOME);
                }
                break;
            case STATE_GRID:
                showState(STATE_HOME);
                break;
            case STATE_SEARCH:
                etSearch.setText("");
                hideSearch();
                showState(STATE_HOME);
                break;
        }
    }

    private void hideSearch() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
    }

    private void validateSession() {
        if (auth == null || !auth.isLoggedIn()) return;
        executor.execute(() -> {
            try {
                auth.getAccessToken();
            } catch (Exception e) {
                Log.w(TAG, "Session validation failed, showing login", e);
                mainHandler.post(() -> {
                    if (auth != null) auth.logout();
                    showState(STATE_LOGIN);
                    tvUserCode.setVisibility(View.GONE);
                    tvVerificationUri.setVisibility(View.GONE);
                    tvLoginStatus.setVisibility(View.GONE);
                    btnLogin.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // Login flow

    private void startLogin() {
        if (auth == null) return;
        btnLogin.setVisibility(View.GONE);
        tvLoginStatus.setVisibility(View.VISIBLE);
        tvLoginStatus.setText(R.string.tidal_connecting);

        executor.execute(() -> {
            try {
                TidalModels.DeviceAuth deviceAuth = auth.startDeviceAuth();
                mainHandler.post(() -> {
                    tvUserCode.setVisibility(View.VISIBLE);
                    tvUserCode.setText(deviceAuth.userCode);
                    tvVerificationUri.setVisibility(View.VISIBLE);
                    tvVerificationUri.setText(deviceAuth.verificationUri);
                    tvLoginStatus.setText(R.string.tidal_waiting_auth);
                });

                boolean success = auth.pollForToken(deviceAuth);
                mainHandler.post(() -> {
                    if (success) {
                        api = new TidalApi(auth);
                        updateView();
                    } else {
                        tvLoginStatus.setText(R.string.tidal_login_failed);
                        btnLogin.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Login failed", e);
                mainHandler.post(() -> {
                    tvLoginStatus.setText(R.string.tidal_login_failed);
                    btnLogin.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // Home categories

    private void buildHomeCategories() {
        homeCategories.clear();
        homeCategories.add(new CategoryItem("albums", getString(R.string.tidal_albums), "", 0, null));
        homeCategories.add(new CategoryItem("playlists", getString(R.string.tidal_playlists), "", 0, null));
        homeCategories.add(new CategoryItem("favorites", getString(R.string.tidal_favorites), "", 0, null));
        homeCategories.add(new CategoryItem("search", getString(R.string.tidal_search), "", 0, null));
        homeCategories.add(new CategoryItem("logout", getString(R.string.tidal_logout), "", 0, null));
        homeAdapter.notifyDataSetChanged();
    }

    @Override
    public void onCategoryClick(CategoryItem item) {
        switch (item.key) {
            case "albums":
                loadAlbums();
                break;
            case "playlists":
                loadPlaylists();
                break;
            case "favorites":
                loadFavoriteTracks();
                break;
            case "search":
                showSearchMode();
                break;
            case "logout":
                if (auth != null) {
                    auth.logout();
                    updateView();
                }
                break;
            default:
                // Grid item clicked -- could be album or playlist
                handleGridItemClick(item);
                break;
        }
    }

    private void showSearchMode() {
        tvGridTitle.setText(getString(R.string.tidal_search));
        showState(STATE_SEARCH);
        etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }
        // Show empty grid initially
        gridItems.clear();
        gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
        recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        recyclerHome.setAdapter(gridAdapter);
    }

    private void performSearch(String query) {
        if (query.isEmpty() || api == null) return;
        hideSearch();
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalAlbum> albums = api.searchAlbums(query, 30);
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (TidalModels.TidalAlbum a : albums) {
                        String artPath = a.artworkId != null && !a.artworkId.isEmpty()
                                ? a.artworkId.replace('-', '/') : null;
                        String artKey = artPath != null ? "tidal:" + artPath : null;
                        gridItems.add(new CategoryItem(
                                "album:" + a.id, a.title, a.artist, a.numberOfTracks, artKey));
                    }
                    currentGridType = "search";
                    // Stay in STATE_SEARCH -- search bar remains visible above results
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Search failed", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadAlbums() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalAlbum> albums = api.getFavoriteAlbums();
                cachedAlbums = albums;
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (TidalModels.TidalAlbum a : albums) {
                        String artPath = a.artworkId != null && !a.artworkId.isEmpty()
                                ? a.artworkId.replace('-', '/') : null;
                        String artKey = artPath != null ? "tidal:" + artPath : null;
                        gridItems.add(new CategoryItem(
                                "album:" + a.id, a.title, a.artist, a.numberOfTracks, artKey));
                    }
                    currentGridType = "albums";
                    tvGridTitle.setText(getString(R.string.tidal_albums));
                    showState(STATE_GRID);
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load albums", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadPlaylists() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalPlaylist> playlists = api.getPlaylists();
                cachedPlaylists = playlists;
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (TidalModels.TidalPlaylist p : playlists) {
                        String artPath = p.artworkId != null && !p.artworkId.isEmpty()
                                ? p.artworkId.replace('-', '/') : null;
                        String artKey = artPath != null ? "tidal:" + artPath : null;
                        gridItems.add(new CategoryItem(
                                "playlist:" + p.uuid, p.title, p.creator,
                                p.numberOfTracks, artKey));
                    }
                    currentGridType = "playlists";
                    tvGridTitle.setText(getString(R.string.tidal_playlists));
                    showState(STATE_GRID);
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load playlists", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadFavoriteTracks() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalTrack> tidalTracks = api.getFavoriteTracks();
                List<Track> tracks = convertToTracks(tidalTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(getString(R.string.tidal_favorites), tracks);
                    currentGridType = null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load favorites", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void handleGridItemClick(CategoryItem item) {
        if (item.key.startsWith("album:")) {
            long albumId = Long.parseLong(item.key.substring(6));
            loadAlbumTracks(albumId, item.title);
        } else if (item.key.startsWith("playlist:")) {
            String uuid = item.key.substring(9);
            loadPlaylistTracks(uuid, item.title);
        }
    }

    private void loadAlbumTracks(long albumId, String title) {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalTrack> tidalTracks = api.getAlbumTracks(albumId);
                List<Track> tracks = convertToTracks(tidalTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(title, tracks);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load album tracks", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadPlaylistTracks(String uuid, String title) {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<TidalModels.TidalTrack> tidalTracks = api.getPlaylistTracks(uuid);
                List<Track> tracks = convertToTracks(tidalTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(title, tracks);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load playlist tracks", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.tidal_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showDetailView(String title, List<Track> tracks) {
        tvDetailTitle.setText(title);
        detailTracks.clear();
        detailTracks.addAll(tracks);
        detailTrackAdapter.notifyDataSetChanged();
        detailTrackAdapter.setPlayingTrackId(dataProvider.getPlayingTrackId());
        showState(STATE_DETAIL);
    }

    private List<Track> convertToTracks(List<TidalModels.TidalTrack> tidalTracks) {
        List<Track> tracks = new ArrayList<>();
        for (TidalModels.TidalTrack tt : tidalTracks) {
            String artPath = tt.artworkId != null && !tt.artworkId.isEmpty()
                    ? tt.artworkId.replace('-', '/') : null;
            tracks.add(Track.tidalTrack(
                    tt.id, tt.title, tt.artist, tt.durationMs,
                    tt.albumTitle, tt.albumId, tt.trackNumber, artPath));
        }
        return tracks;
    }

    @Override
    public void onTrackClick(Track track) {
        dataProvider.playTrack(track, detailTracks);
    }

    @Override
    public void onPlayingTrackChanged(long trackId) {
        if (detailTrackAdapter != null) {
            detailTrackAdapter.setPlayingTrackId(trackId);
        }
    }

    private void showLoading() {
        Fade fade = new Fade();
        fade.setDuration(150);
        fade.setInterpolator(new FastOutSlowInInterpolator());
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), fade);
        tvLoading.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        Fade fade = new Fade();
        fade.setDuration(150);
        fade.setInterpolator(new FastOutSlowInInterpolator());
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), fade);
        tvLoading.setVisibility(View.GONE);
    }

    private static void setItemAnimatorDurations(RecyclerView rv) {
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(150);
        animator.setRemoveDuration(150);
        animator.setMoveDuration(150);
        animator.setChangeDuration(150);
        rv.setItemAnimator(animator);
    }
}
