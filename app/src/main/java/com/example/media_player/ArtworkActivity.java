package com.example.media_player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;

import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class ArtworkActivity extends AppCompatActivity {

    public interface OnAlbumChangedListener {
        void onAlbumChanged(long albumId, String artworkUrl, Track track);
    }

    private static OnAlbumChangedListener albumChangedListener;

    public static void setAlbumChangedListener(OnAlbumChangedListener listener) {
        albumChangedListener = listener;
    }

    public static void notifyAlbumChanged(long albumId, String artworkUrl, Track track) {
        if (albumChangedListener != null) {
            albumChangedListener.onAlbumChanged(albumId, artworkUrl, track);
        }
    }

    private static final String EXTRA_ALBUM_ID = "album_id";
    private static final String EXTRA_ARTWORK_URL = "artwork_url";
    private static final String EXTRA_TRACK_URI = "track_uri";
    private static final String EXTRA_TRACK_FOLDER = "track_folder";
    private static final String EXTRA_TRACK_SOURCE = "track_source";
    private static final String EXTRA_TRACK_TIDAL_ID = "track_tidal_id";
    private static final String EXTRA_TRACK_TITLE = "track_title";
    private static final long BUTTON_TIMEOUT_MS = 3000;
    private static final long ROTATION_DEBOUNCE_MS = 500;

    private ConstraintLayout rootLayout;
    private ImageView ivArtwork;
    private SyncedLyricsView lyricsView;
    private int screenSize;
    private ImageButton btnRotate;
    private ImageButton btnScreenToggle;
    private AppSettings settings;
    private OrientationEventListener orientationListener;
    private int currentOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int pendingOrientation = -1;
    private int candidateOrientation = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRotateButton = () -> {
        btnRotate.setVisibility(View.GONE);
        pendingOrientation = -1;
    };
    private final Runnable showRotateIfStable = () -> {
        if (candidateOrientation != -1 && candidateOrientation != currentOrientation) {
            pendingOrientation = candidateOrientation;
            btnRotate.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideRotateButton);
            handler.postDelayed(hideRotateButton, BUTTON_TIMEOUT_MS);
        }
    };
    private final Runnable hideScreenToggle = () ->
            btnScreenToggle.setVisibility(View.GONE);

    private boolean lyricsEnabled;
    private LyricsLoader lyricsLoader;
    private Track currentTrack;
    private GestureDetector gestureDetector;

    // Self-contained lyrics position polling via MusicService
    private MusicService.PlaybackController playbackController;
    private boolean serviceBound;
    private long lastPolledTrackId = -1;
    private final Runnable syncUpdater = new Runnable() {
        @Override
        public void run() {
            if (playbackController != null) {
                // Detect track changes (e.g. auto-advance while MainActivity is unbound)
                long serviceTrackId = playbackController.getPlayingTrackId();
                if (serviceTrackId != lastPolledTrackId && serviceTrackId != -1) {
                    lastPolledTrackId = serviceTrackId;
                    Track serviceTrack = playbackController.getCurrentTrack();
                    if (serviceTrack != null && !isSameTrack(serviceTrack, currentTrack)) {
                        currentTrack = serviceTrack;
                        // Update artwork
                        String key = serviceTrack.artworkUrl != null
                                ? "tidal:" + serviceTrack.artworkUrl
                                : "album:" + serviceTrack.albumId;
                        ArtworkCache.getInstance(ArtworkActivity.this)
                                .loadFullSize(key, ivArtwork, screenSize);
                        loadLyricsForTrack(serviceTrack);
                    }
                }
                lyricsView.updatePosition(playbackController.getCurrentPosition());
            }
            handler.postDelayed(this, 200);
        }
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playbackController = (MusicService.PlaybackController) service;
            serviceBound = true;
            lastPolledTrackId = playbackController.getPlayingTrackId();
            handler.post(syncUpdater);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackController = null;
            serviceBound = false;
        }
    };

    public static Intent newIntent(Context context, long albumId) {
        Intent intent = new Intent(context, ArtworkActivity.class);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        return intent;
    }

    public static Intent newIntent(Context context, long albumId, String artworkUrl) {
        Intent intent = new Intent(context, ArtworkActivity.class);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        if (artworkUrl != null) {
            intent.putExtra(EXTRA_ARTWORK_URL, artworkUrl);
        }
        return intent;
    }

    public static Intent newIntent(Context context, long albumId, String artworkUrl, Track track) {
        Intent intent = newIntent(context, albumId, artworkUrl);
        if (track != null) {
            if (track.uri != null) {
                intent.putExtra(EXTRA_TRACK_URI, track.uri.toString());
            }
            intent.putExtra(EXTRA_TRACK_FOLDER, track.folderPath);
            intent.putExtra(EXTRA_TRACK_SOURCE, track.source.name());
            if (track.tidalTrackId != null) {
                intent.putExtra(EXTRA_TRACK_TIDAL_ID, track.tidalTrackId);
            }
            intent.putExtra(EXTRA_TRACK_TITLE, track.title);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_artwork);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        settings = new AppSettings(this);
        boolean keepScreenOn = settings.isArtworkKeepScreenOn();
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        lyricsEnabled = settings.isLyricsEnabled();
        lyricsLoader = new LyricsLoader();

        rootLayout = findViewById(R.id.root_artwork);
        ivArtwork = findViewById(R.id.iv_artwork);
        lyricsView = findViewById(R.id.lyrics_view);
        btnRotate = findViewById(R.id.btn_rotate);
        btnScreenToggle = findViewById(R.id.btn_screen_toggle);
        btnScreenToggle.setImageResource(keepScreenOn
                ? R.drawable.ic_screen_awake : R.drawable.ic_screen_timeout);
        btnScreenToggle.setVisibility(View.VISIBLE);
        handler.postDelayed(hideScreenToggle, BUTTON_TIMEOUT_MS);

        long albumId = getIntent().getLongExtra(EXTRA_ALBUM_ID, -1);
        String artworkUrl = getIntent().getStringExtra(EXTRA_ARTWORK_URL);
        screenSize = Math.max(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        if (artworkUrl != null) {
            ArtworkCache.getInstance(this).loadFullSize(
                    "tidal:" + artworkUrl, ivArtwork, screenSize);
        } else if (albumId != -1) {
            ArtworkCache.getInstance(this).loadFullSize(
                    "album:" + albumId, ivArtwork, screenSize);
        }

        // Build initial track from intent extras
        currentTrack = buildTrackFromIntent(getIntent());

        // Setup gesture detector for single/double tap
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleLyrics();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                finish();
                return true;
            }
        });

        View.OnTouchListener tapListener = (v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        };
        rootLayout.setOnTouchListener(tapListener);
        ivArtwork.setOnTouchListener(tapListener);

        setAlbumChangedListener((newAlbumId, newArtworkUrl, track) -> {
            String key = newArtworkUrl != null ? "tidal:" + newArtworkUrl : "album:" + newAlbumId;
            ArtworkCache.getInstance(this).loadFullSize(key, ivArtwork, screenSize);
            if (track != null && !isSameTrack(track, currentTrack)) {
                currentTrack = track;
                loadLyricsForTrack(track);
            }
        });

        // Bind to MusicService for direct position polling (independent of MainActivity lifecycle)
        bindService(new Intent(this, MusicService.class), serviceConnection, BIND_AUTO_CREATE);

        btnRotate.setOnClickListener(v -> {
            if (pendingOrientation != -1) {
                setRequestedOrientation(pendingOrientation);
                currentOrientation = pendingOrientation;
                pendingOrientation = -1;
            }
            handler.removeCallbacks(hideRotateButton);
            btnRotate.setVisibility(View.GONE);
        });

        btnScreenToggle.setOnClickListener(v -> {
            boolean nowKeepOn = !settings.isArtworkKeepScreenOn();
            settings.setArtworkKeepScreenOn(nowKeepOn);
            btnScreenToggle.setImageResource(nowKeepOn
                    ? R.drawable.ic_screen_awake : R.drawable.ic_screen_timeout);
            if (nowKeepOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            handler.removeCallbacks(hideScreenToggle);
            handler.postDelayed(hideScreenToggle, BUTTON_TIMEOUT_MS);
        });

        setupOrientationListener();
        applyLayout();

        // Load lyrics for initial track
        if (currentTrack != null) {
            loadLyricsForTrack(currentTrack);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayout();
    }

    private void toggleLyrics() {
        lyricsEnabled = !lyricsEnabled;
        settings.setLyricsEnabled(lyricsEnabled);
        applyLayout();
    }

    private void applyLayout() {
        boolean showLyrics = lyricsEnabled && lyricsView.hasLyrics();
        boolean isLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        AutoTransition autoTransition = new AutoTransition();
        autoTransition.setDuration(150);
        autoTransition.setInterpolator(new FastOutSlowInInterpolator());
        TransitionManager.beginDelayedTransition(rootLayout, autoTransition);

        ConstraintSet cs = new ConstraintSet();
        cs.clone(rootLayout);

        if (!showLyrics) {
            // Full-screen artwork
            cs.setVisibility(R.id.lyrics_view, ConstraintSet.GONE);

            cs.connect(R.id.iv_artwork, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            cs.connect(R.id.iv_artwork, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            cs.connect(R.id.iv_artwork, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            cs.connect(R.id.iv_artwork, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            cs.constrainPercentWidth(R.id.iv_artwork, 1f);
            cs.constrainPercentHeight(R.id.iv_artwork, 1f);
        } else if (isLandscape) {
            // Landscape: artwork 35% left, lyrics 65% right
            cs.setVisibility(R.id.lyrics_view, ConstraintSet.VISIBLE);

            cs.constrainPercentWidth(R.id.iv_artwork, 0.35f);
            cs.constrainPercentHeight(R.id.iv_artwork, 1f);
            cs.connect(R.id.iv_artwork, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            cs.connect(R.id.iv_artwork, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            cs.connect(R.id.iv_artwork, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            cs.clear(R.id.iv_artwork, ConstraintSet.END);

            cs.connect(R.id.lyrics_view, ConstraintSet.START, R.id.iv_artwork, ConstraintSet.END);
            cs.connect(R.id.lyrics_view, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            cs.connect(R.id.lyrics_view, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            cs.connect(R.id.lyrics_view, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            cs.constrainPercentHeight(R.id.lyrics_view, 1f);
        } else {
            // Portrait: artwork 35% top, lyrics 65% bottom
            cs.setVisibility(R.id.lyrics_view, ConstraintSet.VISIBLE);

            cs.constrainPercentWidth(R.id.iv_artwork, 1f);
            cs.constrainPercentHeight(R.id.iv_artwork, 0.35f);
            cs.connect(R.id.iv_artwork, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            cs.connect(R.id.iv_artwork, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            cs.connect(R.id.iv_artwork, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            cs.clear(R.id.iv_artwork, ConstraintSet.BOTTOM);

            cs.connect(R.id.lyrics_view, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            cs.connect(R.id.lyrics_view, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            cs.connect(R.id.lyrics_view, ConstraintSet.TOP, R.id.iv_artwork, ConstraintSet.BOTTOM);
            cs.connect(R.id.lyrics_view, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            cs.constrainPercentWidth(R.id.lyrics_view, 1f);
        }

        cs.applyTo(rootLayout);

        if (showLyrics) {
            ConstraintLayout.LayoutParams lp =
                    (ConstraintLayout.LayoutParams) lyricsView.getLayoutParams();
            lp.matchConstraintPercentHeight = 1f;
            lp.matchConstraintPercentWidth = 1f;
            lyricsView.setLayoutParams(lp);
        }
    }

    private void loadLyricsForTrack(Track track) {
        lyricsLoader.loadLyrics(this, track, result -> {
            if (result != null && !result.lines.isEmpty()) {
                lyricsView.setLyrics(result);
            } else {
                lyricsView.setLyrics(null);
            }
            applyLayout();
        });
    }

    private Track buildTrackFromIntent(Intent intent) {
        String uriStr = intent.getStringExtra(EXTRA_TRACK_URI);
        String folder = intent.getStringExtra(EXTRA_TRACK_FOLDER);
        String sourceName = intent.getStringExtra(EXTRA_TRACK_SOURCE);
        String tidalId = intent.getStringExtra(EXTRA_TRACK_TIDAL_ID);
        String title = intent.getStringExtra(EXTRA_TRACK_TITLE);
        long albumId = intent.getLongExtra(EXTRA_ALBUM_ID, -1);

        if (sourceName == null) return null;

        Track.Source source = Track.Source.valueOf(sourceName);
        if (source == Track.Source.TIDAL && tidalId != null) {
            return Track.tidalTrack(Long.parseLong(tidalId),
                    title != null ? title : "", "", 0, "", albumId, 0,
                    intent.getStringExtra(EXTRA_ARTWORK_URL));
        } else if (uriStr != null) {
            return new Track(0, title != null ? title : "", "", 0,
                    Uri.parse(uriStr), "", albumId, 0, 0,
                    folder != null ? folder : "", "");
        }
        return null;
    }

    private void setupOrientationListener() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;

                int mapped = mapDegreesToOrientation(degrees);
                if (mapped == currentOrientation) {
                    candidateOrientation = -1;
                    handler.removeCallbacks(showRotateIfStable);
                    if (pendingOrientation != -1) {
                        handler.removeCallbacks(hideRotateButton);
                        btnRotate.setVisibility(View.GONE);
                        pendingOrientation = -1;
                    }
                    return;
                }
                if (mapped != candidateOrientation) {
                    candidateOrientation = mapped;
                    handler.removeCallbacks(showRotateIfStable);
                    handler.postDelayed(showRotateIfStable, ROTATION_DEBOUNCE_MS);
                }
            }
        };
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
    }

    private int mapDegreesToOrientation(int degrees) {
        if (degrees >= 315 || degrees < 45) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (degrees >= 45 && degrees < 135) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        } else if (degrees >= 135 && degrees < 225) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        } else {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    private boolean isSameTrack(Track a, Track b) {
        if (a == null || b == null) return false;
        if (a.tidalTrackId != null && b.tidalTrackId != null) {
            return a.tidalTrackId.equals(b.tidalTrackId);
        }
        if (a.uri != null && b.uri != null) {
            return a.uri.equals(b.uri);
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setAlbumChangedListener(null);
        handler.removeCallbacksAndMessages(null);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (lyricsLoader != null) {
            lyricsLoader.shutdown();
        }
        if (orientationListener != null) {
            orientationListener.disable();
        }
    }
}
