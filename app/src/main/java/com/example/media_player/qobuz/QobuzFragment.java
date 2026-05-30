package com.example.media_player.qobuz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.media_player.CategoryAdapter;
import com.example.media_player.CategoryItem;
import com.example.media_player.PlaybackObserver;
import com.example.media_player.R;
import com.example.media_player.Track;
import com.example.media_player.TrackAdapter;
import com.example.media_player.TrackDataProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QobuzFragment extends Fragment
        implements PlaybackObserver, TrackAdapter.OnTrackClickListener,
        CategoryAdapter.OnCategoryClickListener {

    private static final String TAG = "QobuzFragment";

    private static final int STATE_LOGIN = 0;
    private static final int STATE_HOME = 1;
    private static final int STATE_GRID = 2;
    private static final int STATE_DETAIL = 3;
    private static final int STATE_SEARCH = 4;

    private TrackDataProvider dataProvider;
    private QobuzAuth auth;
    private QobuzApi api;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Views
    private View loginContainer;
    private View libraryContainer;
    private View detailContainer;
    private TextView tvLoginStatus;
    private TextView btnLogin;
    private ActivityResultLauncher<Intent> oauthLauncher;
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

    private List<QobuzModels.QobuzAlbum> cachedAlbums;
    private List<QobuzModels.QobuzPlaylist> cachedPlaylists;

    private OnBackPressedCallback backCallback;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
    }

    public void setQobuzAuth(QobuzAuth auth) {
        boolean wasLoggedIn = this.auth != null && this.auth.isLoggedIn();
        this.auth = auth;
        if (auth != null) {
            this.api = new QobuzApi(auth);
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

        oauthLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        String code = result.getData().getStringExtra(QobuzOAuthActivity.EXTRA_CODE);
                        if (code != null && !code.isEmpty()) {
                            finishOAuthLogin(code);
                            return;
                        }
                    }
                    // Cancelled or no code returned
                    tvLoginStatus.setVisibility(View.VISIBLE);
                    tvLoginStatus.setText(R.string.qobuz_login_cancelled);
                    btnLogin.setVisibility(View.VISIBLE);
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_qobuz, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        loginContainer = view.findViewById(R.id.login_container);
        libraryContainer = view.findViewById(R.id.library_container);
        detailContainer = view.findViewById(R.id.detail_container);
        tvLoginStatus = view.findViewById(R.id.tv_login_status);
        btnLogin = view.findViewById(R.id.btn_qobuz_login);
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

        homeAdapter = new CategoryAdapter(homeCategories, this, CategoryAdapter.VIEW_TYPE_GRID);
        recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        recyclerHome.setAdapter(homeAdapter);

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
                hideKeyboard();
                showState(STATE_HOME);
            }
        });

        updateView();
    }

    private void updateView() {
        if (auth == null && getContext() != null) {
            QobuzAuth fallback = new QobuzAuth(getContext());
            if (fallback.isLoggedIn()) {
                auth = fallback;
                api = new QobuzApi(auth);
                // If we have persisted app_id/secret, try restoring session
                if (!auth.isInitialized()) {
                    initAndRestore();
                    return;
                }
            }
        }
        if (auth != null && auth.isLoggedIn() && auth.isInitialized()) {
            showState(STATE_HOME);
            buildHomeCategories();
            validateSession();
        } else if (auth != null && auth.isLoggedIn() && !auth.isInitialized()) {
            initAndRestore();
        } else {
            showState(STATE_LOGIN);
            tvLoginStatus.setVisibility(View.GONE);
            btnLogin.setVisibility(View.VISIBLE);
        }
    }

    private void initAndRestore() {
        showState(STATE_LOGIN);
        tvLoginStatus.setVisibility(View.VISIBLE);
        tvLoginStatus.setText(R.string.qobuz_connecting);
        btnLogin.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                if (!auth.isInitialized()) {
                    auth.init();
                }
                boolean restored = auth.restoreSession();
                mainHandler.post(() -> {
                    if (restored && auth.isLoggedIn()) {
                        api = new QobuzApi(auth);
                        showState(STATE_HOME);
                        buildHomeCategories();
                    } else {
                        auth.logout();
                        tvLoginStatus.setText(R.string.qobuz_session_expired);
                        btnLogin.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Init/restore failed", e);
                mainHandler.post(() -> {
                    tvLoginStatus.setText(R.string.qobuz_login_failed);
                    btnLogin.setVisibility(View.VISIBLE);
                });
            }
        });
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
                hideKeyboard();
                showState(STATE_HOME);
                break;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
    }

    private void validateSession() {
        if (auth == null || !auth.isLoggedIn() || !auth.isInitialized()) return;
        executor.execute(() -> {
            try {
                auth.restoreSession();
            } catch (Exception e) {
                Log.w(TAG, "Session validation failed", e);
                mainHandler.post(() -> {
                    if (auth != null) auth.logout();
                    showState(STATE_LOGIN);
                    tvLoginStatus.setVisibility(View.GONE);
                    btnLogin.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // ---- Login (OAuth flow) ----

    private void startLogin() {
        if (auth == null && getContext() != null) {
            auth = new QobuzAuth(getContext());
        }
        if (auth == null) return;

        btnLogin.setVisibility(View.GONE);
        tvLoginStatus.setVisibility(View.VISIBLE);
        tvLoginStatus.setText(R.string.qobuz_connecting);

        executor.execute(() -> {
            // Ensure bundle tokens (app_id + private_key) are loaded
            if (!auth.isInitialized() || auth.getPrivateKey() == null) {
                try {
                    auth.init();
                } catch (Exception initErr) {
                    Log.e(TAG, "Bundle init failed", initErr);
                    mainHandler.post(() -> {
                        tvLoginStatus.setText("Could not connect to Qobuz");
                        btnLogin.setVisibility(View.VISIBLE);
                    });
                    return;
                }
            }

            String appId = auth.getAppId();
            mainHandler.post(() -> launchOAuthWebView(appId));
        });
    }

    private void launchOAuthWebView(String appId) {
        if (appId == null) {
            tvLoginStatus.setText(R.string.qobuz_login_failed);
            btnLogin.setVisibility(View.VISIBLE);
            return;
        }
        Intent intent = new Intent(requireContext(), QobuzOAuthActivity.class);
        intent.putExtra(QobuzOAuthActivity.EXTRA_APP_ID, appId);
        oauthLauncher.launch(intent);
    }

    /** Called from the ActivityResult callback once the WebView captured a code. */
    private void finishOAuthLogin(String code) {
        tvLoginStatus.setVisibility(View.VISIBLE);
        tvLoginStatus.setText(R.string.qobuz_connecting);
        btnLogin.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                String token = auth.exchangeOAuthCode(code);
                boolean success = auth.loginWithOAuthToken(token);
                mainHandler.post(() -> {
                    if (success) {
                        api = new QobuzApi(auth);
                        showState(STATE_HOME);
                        buildHomeCategories();
                    } else {
                        tvLoginStatus.setText(R.string.qobuz_login_failed);
                        btnLogin.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "OAuth login failed", e);
                mainHandler.post(() -> {
                    tvLoginStatus.setText(R.string.qobuz_login_failed);
                    btnLogin.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // ---- Home ----

    private void buildHomeCategories() {
        homeCategories.clear();
        homeCategories.add(new CategoryItem("albums", getString(R.string.qobuz_albums), "", 0, null));
        homeCategories.add(new CategoryItem("playlists", getString(R.string.qobuz_playlists), "", 0, null));
        homeCategories.add(new CategoryItem("favorites", getString(R.string.qobuz_favorites), "", 0, null));
        homeCategories.add(new CategoryItem("search", getString(R.string.qobuz_search), "", 0, null));
        homeCategories.add(new CategoryItem("logout", getString(R.string.qobuz_logout), "", 0, null));
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
                handleGridItemClick(item);
                break;
        }
    }

    private void showSearchMode() {
        tvGridTitle.setText(getString(R.string.qobuz_search));
        showState(STATE_SEARCH);
        etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }
        gridItems.clear();
        gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
        recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        recyclerHome.setAdapter(gridAdapter);
    }

    private void performSearch(String query) {
        if (query.isEmpty() || api == null) return;
        hideKeyboard();
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzAlbum> albums = api.searchAlbums(query, 30);
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (QobuzModels.QobuzAlbum a : albums) {
                        String artKey = artworkKeyForUrl(a.getArtworkUrl());
                        gridItems.add(new CategoryItem(
                                "album:" + a.id, a.title, a.artist, a.tracksCount, artKey));
                    }
                    currentGridType = "search";
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Search failed", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadAlbums() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzAlbum> albums = api.getFavoriteAlbums();
                cachedAlbums = albums;
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (QobuzModels.QobuzAlbum a : albums) {
                        String artKey = artworkKeyForUrl(a.getArtworkUrl());
                        gridItems.add(new CategoryItem(
                                "album:" + a.id, a.title, a.artist, a.tracksCount, artKey));
                    }
                    currentGridType = "albums";
                    tvGridTitle.setText(getString(R.string.qobuz_albums));
                    showState(STATE_GRID);
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load albums", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadPlaylists() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzPlaylist> playlists = api.getUserPlaylists();
                cachedPlaylists = playlists;
                mainHandler.post(() -> {
                    hideLoading();
                    gridItems.clear();
                    for (QobuzModels.QobuzPlaylist p : playlists) {
                        String artKey = artworkKeyForUrl(p.artworkUrl);
                        gridItems.add(new CategoryItem(
                                "playlist:" + p.id, p.name, p.ownerName,
                                p.tracksCount, artKey));
                    }
                    currentGridType = "playlists";
                    tvGridTitle.setText(getString(R.string.qobuz_playlists));
                    showState(STATE_GRID);
                    gridAdapter = new CategoryAdapter(gridItems, this, CategoryAdapter.VIEW_TYPE_GRID);
                    recyclerHome.setLayoutManager(new GridLayoutManager(requireContext(), 3));
                    recyclerHome.setAdapter(gridAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load playlists", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadFavoriteTracks() {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzTrack> qobuzTracks = api.getFavoriteTracks();
                List<Track> tracks = convertToTracks(qobuzTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(getString(R.string.qobuz_favorites), tracks);
                    currentGridType = null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load favorites", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void handleGridItemClick(CategoryItem item) {
        if (item.key.startsWith("album:")) {
            String albumId = item.key.substring(6);
            loadAlbumTracks(albumId, item.title);
        } else if (item.key.startsWith("playlist:")) {
            long playlistId = Long.parseLong(item.key.substring(9));
            loadPlaylistTracks(playlistId, item.title);
        }
    }

    private void loadAlbumTracks(String albumId, String title) {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzTrack> qobuzTracks = api.getAlbumTracks(albumId);
                List<Track> tracks = convertToTracks(qobuzTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(title, tracks);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load album tracks", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadPlaylistTracks(long playlistId, String title) {
        if (api == null) return;
        showLoading();

        executor.execute(() -> {
            try {
                List<QobuzModels.QobuzTrack> qobuzTracks = api.getPlaylistTracks(playlistId);
                List<Track> tracks = convertToTracks(qobuzTracks);
                mainHandler.post(() -> {
                    hideLoading();
                    showDetailView(title, tracks);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load playlist tracks", e);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(requireContext(), R.string.qobuz_error, Toast.LENGTH_SHORT).show();
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

    private List<Track> convertToTracks(List<QobuzModels.QobuzTrack> qobuzTracks) {
        List<Track> tracks = new ArrayList<>();
        for (QobuzModels.QobuzTrack qt : qobuzTracks) {
            String artKey = artworkKeyForUrl(qt.artworkUrl);
            tracks.add(Track.qobuzTrack(
                    qt.id, qt.title, qt.artist, qt.durationMs(),
                    qt.albumTitle, qt.albumId, qt.trackNumber, artKey));
        }
        return tracks;
    }

    /** Convert a full URL to an artwork cache key with "qobuz:" prefix. */
    private static String artworkKeyForUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        return "qobuz:" + url;
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
