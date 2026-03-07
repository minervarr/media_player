package com.example.media_player;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GroupedFragment extends Fragment implements PlaybackObserver, TrackAdapter.OnTrackClickListener, CategoryAdapter.OnCategoryClickListener {

    public static final int MODE_ALBUM = 0;
    public static final int MODE_ARTIST = 1;
    public static final int MODE_FOLDER = 2;
    public static final int MODE_EP = 3;
    public static final int MODE_SINGLE = 4;
    public static final int MODE_REMIX = 5;

    private static final int RELEASE_ALBUM = 0;
    private static final int RELEASE_EP = 1;
    private static final int RELEASE_SINGLE = 2;
    private static final int RELEASE_REMIX = 3;

    private static final String ARG_MODE = "mode";

    private TrackDataProvider dataProvider;
    private int mode;
    private int viewType;

    private RecyclerView recyclerCategories;
    private View detailContainer;
    private RecyclerView recyclerDetailTracks;
    private TextView tvDetailTitle;
    private TextView tvEmpty;

    private final List<CategoryItem> categories = new ArrayList<>();
    private CategoryAdapter categoryAdapter;

    private final List<Track> detailTracks = new ArrayList<>();
    private TrackAdapter detailTrackAdapter;

    private Map<String, List<Track>> groupedTracks;

    private OnBackPressedCallback backCallback;

    public static GroupedFragment newInstance(int mode) {
        GroupedFragment f = new GroupedFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getArguments() != null ? getArguments().getInt(ARG_MODE, MODE_ALBUM) : MODE_ALBUM;

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showCategoryList();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_grouped, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recyclerCategories = view.findViewById(R.id.recycler_categories);
        detailContainer = view.findViewById(R.id.detail_container);
        recyclerDetailTracks = view.findViewById(R.id.recycler_detail_tracks);
        tvDetailTitle = view.findViewById(R.id.tv_detail_title);
        tvEmpty = view.findViewById(R.id.tv_empty);
        View backHeader = view.findViewById(R.id.back_header);

        if (isAlbumLikeMode()) {
            viewType = CategoryAdapter.VIEW_TYPE_GRID;
            recyclerCategories.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        } else {
            viewType = CategoryAdapter.VIEW_TYPE_LIST;
            recyclerCategories.setLayoutManager(new LinearLayoutManager(requireContext()));
        }

        categoryAdapter = new CategoryAdapter(categories, this, viewType);
        recyclerCategories.setAdapter(categoryAdapter);

        detailTrackAdapter = new TrackAdapter(detailTracks, this);
        recyclerDetailTracks.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerDetailTracks.setAdapter(detailTrackAdapter);

        backHeader.setOnClickListener(v -> showCategoryList());

        loadData();
    }

    public void loadData() {
        if (dataProvider == null || categoryAdapter == null) return;

        List<Track> allTracks = dataProvider.getAllTracks();
        groupedTracks = groupTracks(allTracks);
        buildCategoryList();

        categoryAdapter.notifyDataSetChanged();

        boolean empty = categories.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerCategories.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (detailContainer.getVisibility() == View.VISIBLE) {
            detailTrackAdapter.setPlayingTrackId(dataProvider.getPlayingTrackId());
        }
    }

    private Map<String, List<Track>> groupTracks(List<Track> allTracks) {
        Map<String, List<Track>> map = new LinkedHashMap<>();
        for (Track t : allTracks) {
            String key = getGroupKey(t);
            List<Track> list = map.get(key);
            if (list == null) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            list.add(t);
        }
        for (List<Track> list : map.values()) {
            sortTracksInGroup(list);
        }
        return map;
    }

    private boolean isAlbumLikeMode() {
        return mode == MODE_ALBUM || mode == MODE_EP
            || mode == MODE_SINGLE || mode == MODE_REMIX;
    }

    private String getGroupKey(Track t) {
        if (isAlbumLikeMode()) {
            return String.valueOf(t.albumId);
        }
        switch (mode) {
            case MODE_ARTIST:
                return t.artist != null && !t.artist.isEmpty() ? t.artist : "Unknown";
            case MODE_FOLDER:
                return t.folderPath != null && !t.folderPath.isEmpty() ? t.folderPath : "";
            default:
                return "";
        }
    }

    private void buildCategoryList() {
        categories.clear();
        List<CategoryItem> items = new ArrayList<>();
        for (Map.Entry<String, List<Track>> entry : groupedTracks.entrySet()) {
            String key = entry.getKey();
            List<Track> tracks = entry.getValue();
            Track first = tracks.get(0);
            String title;
            String subtitle;
            String artworkKey;
            switch (mode) {
                case MODE_ALBUM:
                case MODE_EP:
                case MODE_SINGLE:
                case MODE_REMIX:
                    String albumName = first.album != null && !first.album.isEmpty() ? first.album : "Unknown";
                    int releaseType = classifyRelease(albumName, tracks);
                    int targetMode = releaseTypeToMode(releaseType);
                    if (targetMode != mode) continue;
                    title = albumName;
                    String artist = first.artist != null && !first.artist.isEmpty() ? first.artist : "Unknown";
                    subtitle = artist;
                    artworkKey = "album:" + key;
                    break;
                case MODE_ARTIST:
                    title = key;
                    long albumCount = tracks.stream().map(t -> t.albumId).distinct().count();
                    subtitle = albumCount + (albumCount == 1 ? " album" : " albums");
                    artworkKey = null;
                    break;
                case MODE_FOLDER:
                    title = first.folderName != null && !first.folderName.isEmpty() ? first.folderName : "Unknown";
                    subtitle = first.folderPath != null ? first.folderPath : "";
                    artworkKey = "folder:" + (first.folderPath != null ? first.folderPath : "");
                    break;
                default:
                    title = key;
                    subtitle = "";
                    artworkKey = null;
            }
            items.add(new CategoryItem(key, title, subtitle, tracks.size(), artworkKey));
        }
        Collections.sort(items, (a, b) -> a.title.compareToIgnoreCase(b.title));
        categories.addAll(items);
    }

    private int classifyRelease(String albumName, List<Track> tracks) {
        if (isRemixRelease(albumName, tracks)) return RELEASE_REMIX;
        if (tracks.size() == 1) return RELEASE_SINGLE;
        if (tracks.size() <= 4) return RELEASE_EP;
        return RELEASE_ALBUM;
    }

    private int releaseTypeToMode(int releaseType) {
        switch (releaseType) {
            case RELEASE_EP: return MODE_EP;
            case RELEASE_SINGLE: return MODE_SINGLE;
            case RELEASE_REMIX: return MODE_REMIX;
            default: return MODE_ALBUM;
        }
    }

    private static boolean isRemixTrack(String title) {
        if (title == null || title.isEmpty()) return false;
        String lower = title.trim().toLowerCase();
        if (lower.equals("remix") || lower.equals("mix")
                || lower.equals("the remix") || lower.equals("the mix")) {
            return false;
        }
        if (lower.contains("remix") || lower.contains("rmx")) {
            return true;
        }
        if (Pattern.compile("\\b\\w+\\s+mix\\b", Pattern.CASE_INSENSITIVE).matcher(title).find()) {
            return true;
        }
        if (Pattern.compile("\\(.*mix.*\\)", Pattern.CASE_INSENSITIVE).matcher(title).find()) {
            return true;
        }
        if (Pattern.compile("\\[.*mix.*\\]", Pattern.CASE_INSENSITIVE).matcher(title).find()) {
            return true;
        }
        return false;
    }

    private static boolean isRemixRelease(String albumName, List<Track> tracks) {
        if (albumName != null) {
            if (Pattern.compile("\\b(remix|remixes|remixed|rmx)\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(albumName).find()) {
                return true;
            }
        }
        int remixCount = 0;
        for (Track t : tracks) {
            if (isRemixTrack(t.title)) remixCount++;
        }
        return remixCount == tracks.size() || (remixCount >= 2 && remixCount * 2 > tracks.size());
    }

    private void sortTracksInGroup(List<Track> list) {
        if (isAlbumLikeMode()) {
            Collections.sort(list, (a, b) -> {
                int cmp = Integer.compare(a.trackNumber, b.trackNumber);
                return cmp != 0 ? cmp : a.title.compareToIgnoreCase(b.title);
            });
            return;
        }
        switch (mode) {
            case MODE_ARTIST:
                Collections.sort(list, (a, b) -> {
                    int cmp = a.album.compareToIgnoreCase(b.album);
                    if (cmp != 0) return cmp;
                    return Integer.compare(a.trackNumber, b.trackNumber);
                });
                break;
            case MODE_FOLDER:
                Collections.sort(list, (a, b) -> a.title.compareToIgnoreCase(b.title));
                break;
        }
    }

    @Override
    public void onCategoryClick(CategoryItem item) {
        List<Track> tracks = groupedTracks.get(item.key);
        if (tracks == null) return;
        showDetail(item.title, tracks);
    }

    private void showDetail(String title, List<Track> tracks) {
        tvDetailTitle.setText(title);
        detailTracks.clear();
        detailTracks.addAll(tracks);
        detailTrackAdapter.notifyDataSetChanged();
        detailTrackAdapter.setPlayingTrackId(dataProvider.getPlayingTrackId());

        recyclerCategories.setVisibility(View.GONE);
        detailContainer.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
    }

    private void showCategoryList() {
        detailContainer.setVisibility(View.GONE);
        recyclerCategories.setVisibility(View.VISIBLE);
        backCallback.setEnabled(false);
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
}
