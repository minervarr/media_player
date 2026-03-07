package com.example.media_player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;

import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class ArtworkActivity extends AppCompatActivity {

    public interface OnAlbumChangedListener {
        void onAlbumChanged(long albumId);
    }

    private static OnAlbumChangedListener albumChangedListener;

    public static void setAlbumChangedListener(OnAlbumChangedListener listener) {
        albumChangedListener = listener;
    }

    public static void notifyAlbumChanged(long albumId) {
        if (albumChangedListener != null) {
            albumChangedListener.onAlbumChanged(albumId);
        }
    }

    private static final String EXTRA_ALBUM_ID = "album_id";
    private static final long BUTTON_TIMEOUT_MS = 3000;

    private ImageView ivArtwork;
    private int screenSize;
    private ImageButton btnRotate;
    private ImageButton btnScreenToggle;
    private AppSettings settings;
    private OrientationEventListener orientationListener;
    private int currentOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    private int pendingOrientation = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRotateButton = () -> {
        btnRotate.setVisibility(View.GONE);
        pendingOrientation = -1;
    };
    private final Runnable hideScreenToggle = () ->
            btnScreenToggle.setVisibility(View.GONE);

    public static Intent newIntent(Context context, long albumId) {
        Intent intent = new Intent(context, ArtworkActivity.class);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_artwork);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

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

        ivArtwork = findViewById(R.id.iv_artwork);
        btnRotate = findViewById(R.id.btn_rotate);
        btnScreenToggle = findViewById(R.id.btn_screen_toggle);
        btnScreenToggle.setImageResource(keepScreenOn
                ? R.drawable.ic_screen_awake : R.drawable.ic_screen_timeout);
        btnScreenToggle.setVisibility(View.VISIBLE);
        handler.postDelayed(hideScreenToggle, BUTTON_TIMEOUT_MS);

        long albumId = getIntent().getLongExtra(EXTRA_ALBUM_ID, -1);
        screenSize = Math.max(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        if (albumId != -1) {
            ArtworkCache.getInstance(this).loadFullSize(
                    "album:" + albumId, ivArtwork, screenSize);
        }

        setAlbumChangedListener(newAlbumId ->
                ArtworkCache.getInstance(this).loadFullSize(
                        "album:" + newAlbumId, ivArtwork, screenSize));

        findViewById(R.id.root_artwork).setOnClickListener(v -> finish());
        ivArtwork.setOnClickListener(v -> finish());

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
    }

    private void setupOrientationListener() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;

                int mapped = mapDegreesToOrientation(degrees);
                if (mapped == currentOrientation) {
                    if (pendingOrientation != -1) {
                        handler.removeCallbacks(hideRotateButton);
                        btnRotate.setVisibility(View.GONE);
                        pendingOrientation = -1;
                    }
                    return;
                }
                if (mapped != pendingOrientation) {
                    pendingOrientation = mapped;
                    handler.removeCallbacks(hideRotateButton);
                    btnRotate.setVisibility(View.VISIBLE);
                    handler.postDelayed(hideRotateButton, BUTTON_TIMEOUT_MS);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setAlbumChangedListener(null);
        if (orientationListener != null) {
            orientationListener.disable();
        }
        handler.removeCallbacksAndMessages(null);
    }
}
