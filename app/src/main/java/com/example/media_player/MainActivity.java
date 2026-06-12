package com.example.media_player;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.bluetooth.BluetoothDevice;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.nerio.audioengine.AudioOutput;
import com.nerio.audioengine.DffParser;
import com.nerio.audioengine.DsfParser;
import com.nerio.audioengine.EqProfile;
import com.nerio.audioengine.SignalPathInfo;
import com.nerio.audioengine.UsbAudioOutput;

import com.example.media_player.databinding.ActivityMainBinding;
import com.example.media_player.tidal.TidalAuth;
import com.example.media_player.qobuz.QobuzAuth;
import com.example.media_player.qobuz.QobuzFragment;
import com.example.media_player.tidal.TidalFragment;
import com.example.media_player.tidal.TidalModels;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity
        implements TrackDataProvider,
        BluetoothCodecManager.BluetoothCodecListener,
        MusicService.PlaybackCallback {

    private static final String TAG = "MatrixPlayer";

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".flac", ".mp3", ".wav", ".aac", ".ogg", ".m4a",
            ".opus", ".wma", ".dsf", ".dff", ".ape", ".aiff"));

    private ActivityMainBinding binding;
    private final List<Track> tracks = new ArrayList<>();
    private boolean isUserSeeking = false;

    private final Fragment[] fragments = new Fragment[10];
    private int currentTabIndex = 0;
    private SearchFragment searchFragment;
    private boolean searchVisible;

    private AppSettings settings;
    private BluetoothCodecManager bluetoothCodecManager;
    private AudioManager audioManager;

    private int signalPathMode;

    private MusicService.PlaybackController playbackController;
    private boolean serviceBound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            playbackController = (MusicService.PlaybackController) service;
            serviceBound = true;
            playbackController.setCallback(MainActivity.this);
            // Push the current volume-mode preference now that we have a binder.
            // onResume() may have fired before this point (binding is async), so
            // pushing here is the only place we can be sure playbackController
            // exists when the user just returned from SettingsActivity.
            if (settings != null) {
                playbackController.setVolumeMode(
                        com.nerio.audioengine.VolumeMode.fromString(settings.getVolumeMode()));
            }
            // Sync slider with the service's source-of-truth volume.
            float vol = playbackController.getVolume();
            binding.seekbarVolume.setProgress(Math.round(vol * binding.seekbarVolume.getMax()));
            updateVolumeLabel();

            // Cold-start headphone reminder: shown once per app launch
            // (suppressed during the first-launch intro dialog flow).
            if (shouldShowAppStartReminder) {
                shouldShowAppStartReminder = false;
                int pct = Math.round(vol * 100f);
                com.google.android.material.snackbar.Snackbar.make(
                        binding.getRoot(),
                        getString(R.string.volume_app_start_reminder_fmt, pct),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            }
            restoreUiFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            playbackController = null;
            serviceBound = false;
        }
    };

    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Handler volumeOverlayHandler = new Handler(Looper.getMainLooper());

    // True between MainActivity.onCreate (cold launch) and the first
    // onServiceConnected -- so we can fire the headphone reminder Snackbar
    // exactly once per app start. Rotation / recreation does NOT retrigger
    // because we gate on savedInstanceState == null.
    private boolean shouldShowAppStartReminder;
    private final Runnable hideVolumeOverlay = () -> {
        if (binding != null && binding.volumeOverlay.getVisibility() == android.view.View.VISIBLE) {
            binding.volumeOverlay.animate()
                    .alpha(0f)
                    .translationY(-binding.volumeOverlay.getHeight() / 2f)
                    .setDuration(180)
                    .withEndAction(() -> binding.volumeOverlay.setVisibility(android.view.View.GONE))
                    .start();
        }
    };

    private void showVolumeOverlay() {
        if (binding == null) return;
        volumeOverlayHandler.removeCallbacks(hideVolumeOverlay);
        if (binding.volumeOverlay.getVisibility() != android.view.View.VISIBLE) {
            binding.volumeOverlay.setAlpha(0f);
            binding.volumeOverlay.setTranslationY(-binding.volumeOverlay.getHeight() / 2f);
            binding.volumeOverlay.setVisibility(android.view.View.VISIBLE);
            binding.volumeOverlay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start();
        }
        volumeOverlayHandler.postDelayed(hideVolumeOverlay, 3000);
    }
    private final Runnable seekUpdater = new Runnable() {
        @Override
        public void run() {
            if (playbackController != null && playbackController.isPlaying() && !isUserSeeking) {
                int pos = playbackController.getCurrentPosition();
                binding.seekbar.setProgress(pos);
                binding.tvCurrentTime.setText(formatTime(pos));
            }
            seekHandler.postDelayed(this, 500);
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadTracks();
                } else {
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == SettingsActivity.RESULT_FOLDERS_CHANGED) {
                    loadTracks();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Matrix Player starting");
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settings = new AppSettings(this);
        signalPathMode = settings.getSignalPathMode();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Init artwork disk cache (L2 behind in-memory LruCache)
        ArtworkCache.getInstance(this).initDiskCache(MatrixPlayerDatabase.getInstance(this));

        enableFullscreen();
        setSupportActionBar(binding.toolbar);

        setupTabs();
        if (savedInstanceState != null) {
            int restored = savedInstanceState.getInt("tab_index", 0);
            if (restored > 0 && restored < fragments.length) {
                binding.tabLayout.getTabAt(restored).select();
            }
        }
        setupPlayerControls();
        checkPermissionAndLoad();

        if (BluetoothCodecManager.isFeatureAvailable(this)) {
            bluetoothCodecManager = new BluetoothCodecManager(this);
            bluetoothCodecManager.setListener(this);
            bluetoothCodecManager.register();
        }

        // First-launch safety intro. Shown once; flag stored in SharedPreferences.
        if (!settings.isVolumeIntroShown()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.volume_intro_title)
                    .setMessage(R.string.volume_intro_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.volume_intro_ok, (d, w) -> {
                        settings.setVolumeIntroShown(true);
                    })
                    .show();
        } else {
            // Lighter app-start headphone reminder. Only on cold launch
            // (savedInstanceState==null) so a config change doesn't retrigger.
            shouldShowAppStartReminder = savedInstanceState == null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Intercept volume up/down before they reach the system MediaSession
        // routing. onKeyDown fires later in the chain and can be short-circuited
        // by the system slider in some cases; dispatchKeyEvent is more reliable.
        // We only handle ACTION_DOWN -- ACTION_UP should still propagate so
        // long-press auto-repeat works normally.
        final int keyCode = event.getKeyCode();
        final boolean isVolumeKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        if (isVolumeKey && playbackController != null
                && playbackController.isUsbOutputActive()) {
            com.nerio.audioengine.VolumeMode mode = playbackController.getEffectiveVolumeMode();
            if (mode != com.nerio.audioengine.VolumeMode.EXTERNAL) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    double db = playbackController.getVolumeDb();
                    if (Double.isInfinite(db) && db < 0) {
                        db = -60.0;  // floor on the first up-press
                    }
                    db += (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) ? 1.0 : -1.0;
                    if (db > 0.0) db = 0.0;
                    if (db < -60.0) db = -60.0;
                    float linear = playbackController.dbToLinear(db);
                    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                            && linear <= 0.01f) {
                        linear = 0f;  // snap to mute below 1%
                    }
                    playbackController.setVolume(linear);
                    binding.seekbarVolume.setProgress(
                            Math.round(linear * binding.seekbarVolume.getMax()));
                    updateVolumeLabel();
                    showVolumeOverlay();
                }
                return true;  // consume both ACTION_DOWN and ACTION_UP
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: binding to MusicService");
        Intent intent = new Intent(this, MusicService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Push the current volume mode setting to the running engine. Required
        // because SettingsActivity only writes to SharedPreferences; without this
        // call the engine only picks up changes at the next play() configure().
        if (playbackController != null && settings != null) {
            com.nerio.audioengine.VolumeMode mode =
                    com.nerio.audioengine.VolumeMode.fromString(settings.getVolumeMode());
            playbackController.setVolumeMode(mode);
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop: unbinding from MusicService");
        if (serviceBound) {
            if (playbackController != null) {
                playbackController.setCallback(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
            playbackController = null;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab_index", currentTabIndex);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        seekHandler.removeCallbacks(seekUpdater);
        if (bluetoothCodecManager != null) {
            bluetoothCodecManager.unregister();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            toggleSearchFragment();
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            settingsLauncher.launch(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_quit) {
            quitApp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void quitApp() {
        // Drop our service binding before stopping the service, so the system
        // doesn't keep it alive on our behalf. onDestroy() in MusicService will
        // release the audio engine (which stops playback) and dismiss the
        // foreground notification.
        if (serviceBound) {
            if (playbackController != null) playbackController.setCallback(null);
            unbindService(serviceConnection);
            serviceBound = false;
            playbackController = null;
        }
        stopService(new Intent(this, MusicService.class));
        finishAndRemoveTask();
    }

    private void toggleSearchFragment() {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out);
        if (searchVisible) {
            ft.hide(searchFragment);
            ft.show(fragments[currentTabIndex]);
            ft.commit();
            searchVisible = false;
        } else {
            ft.hide(fragments[currentTabIndex]);
            ft.show(searchFragment);
            ft.commit();
            searchVisible = true;
        }
    }

    private void enableFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_tracks));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_albums));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_eps));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_singles));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_remixes));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_artists));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_folders));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_tidal));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_qobuz));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_stats));

        fragments[0] = new TracksFragment();
        fragments[1] = GroupedFragment.newInstance(GroupedFragment.MODE_ALBUM);
        fragments[2] = GroupedFragment.newInstance(GroupedFragment.MODE_EP);
        fragments[3] = GroupedFragment.newInstance(GroupedFragment.MODE_SINGLE);
        fragments[4] = GroupedFragment.newInstance(GroupedFragment.MODE_REMIX);
        fragments[5] = GroupedFragment.newInstance(GroupedFragment.MODE_ARTIST);
        fragments[6] = GroupedFragment.newInstance(GroupedFragment.MODE_FOLDER);

        TidalFragment tidalFragment = new TidalFragment();
        // TidalAuth will be set once service connects
        fragments[7] = tidalFragment;

        QobuzFragment qobuzFragment = new QobuzFragment();
        fragments[8] = qobuzFragment;

        fragments[9] = new StatsFragment();

        searchFragment = new SearchFragment();

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        for (int i = 0; i < fragments.length; i++) {
            ft.add(R.id.fragment_container, fragments[i], "tab_" + i);
            if (i != 0) ft.hide(fragments[i]);
        }
        ft.add(R.id.fragment_container, searchFragment, "search");
        ft.hide(searchFragment);
        ft.commit();

        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int index = tab.getPosition();
                if (index == currentTabIndex && !searchVisible) return;
                Log.d(TAG, "tab switched: " + tab.getText());
                FragmentTransaction ft = fm.beginTransaction();
                ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out);
                if (searchVisible) {
                    ft.hide(searchFragment);
                    searchVisible = false;
                } else {
                    ft.hide(fragments[currentTabIndex]);
                }
                ft.show(fragments[index]);
                ft.commit();
                currentTabIndex = index;
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener(v -> {
            if (playbackController != null) {
                if (!playbackController.isPrepared() && !tracks.isEmpty()) {
                    playTrack(tracks.get(0), tracks);
                } else {
                    playbackController.togglePlayPause();
                }
            }
        });
        binding.btnNext.setOnClickListener(v -> {
            if (playbackController != null) playbackController.playNext();
        });
        binding.btnPrevious.setOnClickListener(v -> {
            if (playbackController != null) playbackController.playPrevious();
        });

        binding.ivNowPlayingArtwork.setOnClickListener(v -> {
            if (playbackController != null) {
                Track track = playbackController.getCurrentTrack();
                if (track != null) {
                    startActivity(ArtworkActivity.newIntent(this, track.albumId, track.artworkUrl, track));
                }
            }
        });

        binding.tvAudioOutputInfo.setOnClickListener(v -> cycleSignalPathMode());
        binding.signalPathView.setOnClickListener(v -> cycleSignalPathMode());

        binding.seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && playbackController != null && playbackController.isPrepared()) {
                    binding.tvCurrentTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                if (playbackController != null && playbackController.isPrepared()) {
                    Log.d(TAG, "seekTo: " + formatTime(seekBar.getProgress()));
                    playbackController.seekTo(seekBar.getProgress());
                }
            }
        });

        binding.seekbarVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (playbackController == null) return;
                float linear = progress / (float) seekBar.getMax();
                playbackController.setVolume(linear);
                updateVolumeLabel();
                // Reset auto-hide while the user is dragging.
                showVolumeOverlay();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                volumeOverlayHandler.removeCallbacks(hideVolumeOverlay);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                showVolumeOverlay();
            }
        });
    }

    private void updateVolumeLabel() {
        if (playbackController == null) {
            binding.tvVolumeLabel.setText(R.string.volume_muted);
            return;
        }
        double db = playbackController.getVolumeDb();
        if (Double.isInfinite(db) && db < 0) {
            binding.tvVolumeLabel.setText(R.string.volume_muted);
        } else {
            binding.tvVolumeLabel.setText(getString(R.string.volume_db_fmt, db));
        }
    }

    // -- Restore UI after rebind --

    private void restoreUiFromService() {
        if (playbackController == null) return;

        // Push scanned tracks to service
        if (!tracks.isEmpty()) {
            playbackController.setAllTracks(tracks);
        }

        // Give TidalAuth to TidalFragment
        TidalAuth tidalAuth = playbackController.getTidalAuth();
        if (tidalAuth != null && fragments[7] instanceof TidalFragment) {
            ((TidalFragment) fragments[7]).setTidalAuth(tidalAuth);
        }

        // Give QobuzAuth to QobuzFragment
        QobuzAuth qobuzAuth = playbackController.getQobuzAuth();
        if (qobuzAuth != null && fragments[8] instanceof QobuzFragment) {
            ((QobuzFragment) fragments[8]).setQobuzAuth(qobuzAuth);
        }

        Track track = playbackController.getCurrentTrack();
        if (track == null) {
            // Nothing playing
            return;
        }

        // Populate player panel
        binding.tvNowPlayingTitle.setText(track.title);
        binding.tvNowPlayingTitle.setSelected(true);
        binding.tvNowPlayingArtist.setText(track.artist);
        binding.tvTotalTime.setText(track.getFormattedDuration());
        binding.seekbar.setMax((int) track.durationMs);
        binding.seekbar.setProgress(playbackController.getCurrentPosition());
        binding.tvCurrentTime.setText(formatTime(playbackController.getCurrentPosition()));

        // Artwork
        ArtworkCache.getInstance(this).loadArtwork(
                resolveArtworkKey(track), binding.ivNowPlayingArtwork, 144);

        // Play/pause icon
        if (playbackController.isPlaying()) {
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
        } else {
            binding.btnPlayPause.setImageResource(R.drawable.ic_play);
        }

        updateOutputInfo();

        // Start seek updater
        seekHandler.removeCallbacks(seekUpdater);
        seekHandler.post(seekUpdater);

        // Notify fragments
        notifyPlaybackObservers();
    }

    // -- PlaybackCallback implementation --

    @Override
    public void onTrackChanged(Track track, long trackId) {
        binding.tvNowPlayingTitle.setText(track.title);
        binding.tvNowPlayingTitle.setSelected(true);
        binding.tvNowPlayingArtist.setText(track.artist);
        binding.tvTotalTime.setText(track.getFormattedDuration());
        binding.tvCurrentTime.setText("0:00");
        binding.seekbar.setMax((int) track.durationMs);
        binding.seekbar.setProgress(0);
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause);

        ArtworkCache.getInstance(this).loadArtwork(
                resolveArtworkKey(track), binding.ivNowPlayingArtwork, 144);

        seekHandler.removeCallbacks(seekUpdater);
        seekHandler.post(seekUpdater);

        notifyPlaybackObservers();
        ArtworkActivity.notifyAlbumChanged(track.albumId, track.artworkUrl, track);
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        binding.btnPlayPause.setImageResource(
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override
    public void onOutputChanged() {
        updateOutputInfo();
        // Output changed (e.g., new track using the existing USB session).
        // Sync the slider so the overlay shows the current persisted volume.
        // No banner here -- the banner belongs to actual hardware attach,
        // handled by onUsbDacFreshlyConnected().
        if (playbackController != null) {
            float vol = playbackController.getVolume();
            binding.seekbarVolume.setProgress(Math.round(vol * binding.seekbarVolume.getMax()));
            updateVolumeLabel();
        }
    }

    @Override
    public void onUsbDacFreshlyConnected() {
        // Real hardware attach: sync slider to the restored per-DAC volume and
        // fire the safety banner with the restored percentage.
        if (binding == null) return;
        float vol = (playbackController != null) ? playbackController.getVolume() : 0f;
        binding.seekbarVolume.setProgress(Math.round(vol * binding.seekbarVolume.getMax()));
        updateVolumeLabel();
        if (settings != null && settings.isShowVolumeWarning()) {
            int pct = Math.round(vol * 100f);
            com.google.android.material.snackbar.Snackbar.make(
                    binding.getRoot(),
                    getString(R.string.volume_connect_banner_fmt, pct),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPrepared() {
        updateOutputInfo();
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        binding.btnPlayPause.setImageResource(R.drawable.ic_play);
        binding.signalPathView.setVisibility(View.GONE);
    }

    @Override
    public void onBluetoothEqPrompt(String deviceName, String mac,
                                     BluetoothEqMatcher.MatchResult matchResult) {
        showBluetoothEqDialog(deviceName, mac, matchResult);
    }

    private void showBluetoothEqDialog(String deviceName, String mac,
                                        BluetoothEqMatcher.MatchResult result) {
        if (isFinishing() || isDestroyed()) return;

        // Contextual ANC dialog
        if (result.hasAncVariants() && !result.hasConnectionVariants()
                && result.variantsByDimension.size() == 1) {
            showAncDialog(deviceName, mac, result);
            return;
        }

        // Contextual connection dialog
        if (result.hasConnectionVariants() && !result.hasAncVariants()
                && result.variantsByDimension.size() == 1) {
            showConnectionDialog(deviceName, mac, result);
            return;
        }

        // General list dialog
        showProfileListDialog(deviceName, mac, result);
    }

    private void showAncDialog(String deviceName, String mac,
                                BluetoothEqMatcher.MatchResult result) {
        List<EqProfile> ancProfiles = result.variantsByDimension.get(BluetoothEqMatcher.DIM_ANC);
        List<EqProfile> baseProfiles = result.baseProfiles;

        // Build button labels and matching profiles
        List<String> labels = new ArrayList<>();
        List<EqProfile> choices = new ArrayList<>();

        // Add base (no ANC qualifier) option if available
        if (!baseProfiles.isEmpty()) {
            labels.add("Default");
            choices.add(BluetoothEqMatcher.pickPreferredSource(baseProfiles));
        }

        if (ancProfiles != null) {
            // Group ANC variants by their variant text
            java.util.LinkedHashMap<String, List<EqProfile>> groups = new java.util.LinkedHashMap<>();
            for (EqProfile p : ancProfiles) {
                String variant = BluetoothEqMatcher.extractVariant(p.name);
                if (variant == null) variant = p.name;
                List<EqProfile> g = groups.get(variant);
                if (g == null) { g = new ArrayList<>(); groups.put(variant, g); }
                g.add(p);
            }
            for (java.util.Map.Entry<String, List<EqProfile>> e : groups.entrySet()) {
                labels.add(e.getKey());
                choices.add(BluetoothEqMatcher.pickPreferredSource(e.getValue()));
            }
        }

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("EQ for " + deviceName)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < choices.size() && playbackController != null) {
                        playbackController.applyBluetoothEqChoice(mac, choices.get(which));
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    if (playbackController != null) playbackController.dismissBluetoothEqPrompt();
                })
                .setCancelable(true)
                .show();
    }

    private void showConnectionDialog(String deviceName, String mac,
                                       BluetoothEqMatcher.MatchResult result) {
        List<EqProfile> connProfiles = result.variantsByDimension.get(BluetoothEqMatcher.DIM_CONNECTION);
        List<EqProfile> baseProfiles = result.baseProfiles;

        List<String> labels = new ArrayList<>();
        List<EqProfile> choices = new ArrayList<>();

        if (!baseProfiles.isEmpty()) {
            labels.add("Default");
            choices.add(BluetoothEqMatcher.pickPreferredSource(baseProfiles));
        }

        if (connProfiles != null) {
            java.util.LinkedHashMap<String, List<EqProfile>> groups = new java.util.LinkedHashMap<>();
            for (EqProfile p : connProfiles) {
                String variant = BluetoothEqMatcher.extractVariant(p.name);
                if (variant == null) variant = p.name;
                List<EqProfile> g = groups.get(variant);
                if (g == null) { g = new ArrayList<>(); groups.put(variant, g); }
                g.add(p);
            }
            for (java.util.Map.Entry<String, List<EqProfile>> e : groups.entrySet()) {
                labels.add(e.getKey());
                choices.add(BluetoothEqMatcher.pickPreferredSource(e.getValue()));
            }
        }

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("EQ for " + deviceName)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < choices.size() && playbackController != null) {
                        playbackController.applyBluetoothEqChoice(mac, choices.get(which));
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    if (playbackController != null) playbackController.dismissBluetoothEqPrompt();
                })
                .setCancelable(true)
                .show();
    }

    private void showProfileListDialog(String deviceName, String mac,
                                        BluetoothEqMatcher.MatchResult result) {
        // Deduplicate by picking preferred source per unique (name) combination
        java.util.LinkedHashMap<String, EqProfile> deduped = new java.util.LinkedHashMap<>();
        for (EqProfile p : result.allCandidates) {
            EqProfile existing = deduped.get(p.name);
            if (existing == null) {
                deduped.put(p.name, p);
            } else {
                // Keep the one with better source ranking
                List<EqProfile> pair = new ArrayList<>();
                pair.add(existing);
                pair.add(p);
                deduped.put(p.name, BluetoothEqMatcher.pickPreferredSource(pair));
            }
        }

        List<String> labels = new ArrayList<>();
        List<EqProfile> choices = new ArrayList<>();
        for (java.util.Map.Entry<String, EqProfile> e : deduped.entrySet()) {
            EqProfile p = e.getValue();
            labels.add(p.name + "  \u2014  " + p.source);
            choices.add(p);
        }

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("EQ profiles for " + deviceName)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < choices.size() && playbackController != null) {
                        playbackController.applyBluetoothEqChoice(mac, choices.get(which));
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    if (playbackController != null) playbackController.dismissBluetoothEqPrompt();
                })
                .setCancelable(true)
                .show();
    }

    // -- Track scanning (stays in Activity) --

    private void checkPermissionAndLoad() {
        String permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadTracks();
        } else {
            permissionLauncher.launch(permission);
        }
    }

    private void loadTracks() {
        long startTime = System.currentTimeMillis();
        Log.d(TAG, "loadTracks: scanning music folders");
        tracks.clear();

        Set<String> folderPaths = settings.getMusicFolders();
        if (folderPaths.isEmpty()) {
            Toast.makeText(this, R.string.no_folders_configured, Toast.LENGTH_LONG).show();
            notifyFragmentsDataLoaded();
            return;
        }

        // DB scan cache
        MatrixPlayerDatabase dbHelper = MatrixPlayerDatabase.getInstance(this);
        TrackDao trackDao = new TrackDao(dbHelper);
        AlbumDao albumDao = new AlbumDao(dbHelper);
        Map<String, long[]> scanCache = trackDao.loadScanCache();

        List<File> audioFiles = new ArrayList<>();
        for (String path : folderPaths) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                scanDirectory(dir, audioFiles);
            }
        }
        Collections.sort(audioFiles, (a, b) ->
                a.getAbsolutePath().compareTo(b.getAbsolutePath()));

        // Separate files into cached vs needs-scan
        List<File> filesToScan = new ArrayList<>();
        List<String> cachedPaths = new ArrayList<>();
        Set<String> allScannedPaths = new HashSet<>();

        for (File file : audioFiles) {
            String path = file.getAbsolutePath();
            allScannedPaths.add(path);
            long[] cached = scanCache.get(path);
            if (cached != null && cached[0] == file.length() && cached[1] == file.lastModified()) {
                cachedPaths.add(path);
            } else {
                filesToScan.add(file);
            }
        }

        Log.d(TAG, "loadTracks: " + cachedPaths.size() + " cached, "
                + filesToScan.size() + " to scan");

        // Load cached tracks from DB (single bulk query instead of N individual queries)
        if (!cachedPaths.isEmpty()) {
            Map<String, Track> cachedTracks = trackDao.loadTracksByFilePaths(
                    new HashSet<>(cachedPaths));
            // Rescan tracks that are missing sample_rate (legacy data before MediaExtractor probe)
            for (Map.Entry<String, Track> entry : cachedTracks.entrySet()) {
                Track t = entry.getValue();
                if (t.sampleRate == 0 && t.source == Track.Source.LOCAL) {
                    File f = new File(entry.getKey());
                    if (f.exists()) {
                        filesToScan.add(f);
                        continue;
                    }
                }
                tracks.add(t);
            }
        }

        // Scan new/changed files with MMR
        if (!filesToScan.isEmpty()) {
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            ExecutorService scanExecutor = Executors.newFixedThreadPool(threads);
            List<Future<TrackDao.TrackScanResult>> futures = new ArrayList<>();

            for (File file : filesToScan) {
                futures.add(scanExecutor.submit(() -> {
                    Track track = extractTrackMetadata(file);
                    if (track != null) {
                        return new TrackDao.TrackScanResult(track,
                                file.getAbsolutePath(), file.length(), file.lastModified());
                    }
                    return null;
                }));
            }

            List<TrackDao.TrackScanResult> scanResults = new ArrayList<>();
            for (Future<TrackDao.TrackScanResult> future : futures) {
                try {
                    TrackDao.TrackScanResult result = future.get();
                    if (result != null) {
                        tracks.add(result.track);
                        scanResults.add(result);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to extract metadata", e);
                }
            }
            scanExecutor.shutdown();

            // Persist scanned tracks to DB
            if (!scanResults.isEmpty()) {
                trackDao.upsertLocalTracks(scanResults);
            }
        }

        // Remove stale tracks (files deleted from disk)
        int removed = trackDao.removeStaleLocalTracks(allScannedPaths);
        if (removed > 0) {
            Log.d(TAG, "loadTracks: removed " + removed + " stale DB entries");
        }

        // Rebuild albums table (updates release_type classification)
        albumDao.rebuildAlbums();

        long elapsed = System.currentTimeMillis() - startTime;
        Log.d(TAG, "loadTracks: found " + tracks.size() + " tracks in " + elapsed + "ms"
                + " (" + cachedPaths.size() + " cached, " + filesToScan.size() + " scanned)");

        Collections.sort(tracks, (a, b) -> a.title.compareToIgnoreCase(b.title));

        registerAlbums();
        notifyFragmentsDataLoaded();

        // Push tracks to service
        if (playbackController != null) {
            playbackController.setAllTracks(tracks);
        }
    }

    private void scanDirectory(File dir, List<File> results) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, results);
            } else {
                String name = file.getName().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && AUDIO_EXTENSIONS.contains(name.substring(dot))) {
                    results.add(file);
                }
            }
        }
    }

    private Track extractTrackMetadata(File file) {
        // DSF/DFF: use our own parsers (MediaMetadataRetriever can't handle DSD)
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".dsf") || lowerName.endsWith(".dff")) {
            return extractDsdMetadata(file);
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());

            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title == null || title.isEmpty()) {
                title = file.getName();
                int dot = title.lastIndexOf('.');
                if (dot > 0) title = title.substring(0, dot);
            }

            String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null || artist.isEmpty()) artist = "Unknown";

            String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            File parent = file.getParentFile();
            if (album == null || album.isEmpty()) {
                album = parent != null ? parent.getName() : "Unknown";
            }

            long duration = 0;
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                try { duration = Long.parseLong(durationStr); } catch (NumberFormatException ignored) {}
            }

            int trackNumber = 0;
            String trackStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            if (trackStr != null) {
                try {
                    int slash = trackStr.indexOf('/');
                    if (slash >= 0) trackStr = trackStr.substring(0, slash);
                    trackNumber = Integer.parseInt(trackStr.trim());
                } catch (NumberFormatException ignored) {}
            }

            int discNumber = 1;
            String discStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER);
            if (discStr != null) {
                try {
                    int slash = discStr.indexOf('/');
                    if (slash >= 0) discStr = discStr.substring(0, slash);
                    discNumber = Integer.parseInt(discStr.trim());
                } catch (NumberFormatException ignored) {}
            }

            int year = 0;
            String yearStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (yearStr != null) {
                try { year = Integer.parseInt(yearStr.trim()); } catch (NumberFormatException ignored) {}
            }

            // Extended metadata
            String albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
            String composer = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);

            int bitrate = 0;
            String bitrateStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrateStr != null) {
                try { bitrate = Integer.parseInt(bitrateStr) / 1000; } catch (NumberFormatException ignored) {}
            }

            // Probe sample rate, bit depth, channels via MediaExtractor
            int sampleRateVal = 0;
            int bitDepthVal = 0;
            int channelsVal = 0;
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat fmt = extractor.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRateVal = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelsVal = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (fmt.containsKey("pcm-encoding")) {
                            int encoding = fmt.getInteger("pcm-encoding");
                            // AudioFormat: ENCODING_PCM_16BIT=2, ENCODING_PCM_24BIT_PACKED=21,
                            // ENCODING_PCM_32BIT=22, ENCODING_PCM_FLOAT=4
                            switch (encoding) {
                                case 2:  bitDepthVal = 16; break;
                                case 21: bitDepthVal = 24; break;
                                case 22: bitDepthVal = 32; break;
                                case 4:  bitDepthVal = 32; break;
                                default: break;
                            }
                        }
                        break; // first audio track is enough
                    }
                }
            } catch (Exception ignored) {
                // MediaExtractor may fail on some formats; leave values at 0
            } finally {
                extractor.release();
            }

            // Derive format from file extension
            String fileName = file.getName().toLowerCase(Locale.ROOT);
            int dot = fileName.lastIndexOf('.');
            String format = dot >= 0 ? fileName.substring(dot + 1).toUpperCase(Locale.ROOT) : null;

            long id = (long) file.getAbsolutePath().hashCode();
            Uri uri = Uri.fromFile(file);

            String folderPath = parent != null ? parent.getAbsolutePath() : "";
            String folderName = parent != null ? parent.getName() : "Unknown";

            String albumGroupFolder = folderPath;
            if (folderName.toLowerCase(Locale.ROOT).matches("^(cd|disc)[\\s-]*\\d+$") && parent != null && parent.getParentFile() != null) {
                albumGroupFolder = parent.getParentFile().getAbsolutePath();
            }

            long albumId = (long) (album + artist + albumGroupFolder).hashCode();

            return new Track(id, title, artist, duration, uri,
                    album, albumId, trackNumber, discNumber, year,
                    folderPath, folderName,
                    albumArtist, genre, composer,
                    bitrate, sampleRateVal, bitDepthVal, channelsVal, format);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read: " + file.getAbsolutePath(), e);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private Track extractDsdMetadata(File file) {
        try {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            boolean isDsf = lower.endsWith(".dsf");

            int sampleRate, channelCount;
            long totalSamples;

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                if (isDsf) {
                    DsfParser parser = new DsfParser();
                    parser.parse(raf);
                    sampleRate = parser.getSampleRate();
                    channelCount = parser.getChannelCount();
                    totalSamples = parser.getTotalSamples();
                } else {
                    DffParser parser = new DffParser();
                    parser.parse(raf);
                    sampleRate = parser.getSampleRate();
                    channelCount = parser.getChannelCount();
                    totalSamples = parser.getTotalSamples();
                }
            } finally {
                raf.close();
            }

            long durationMs = sampleRate > 0 ? (totalSamples * 1000L) / sampleRate : 0;

            String rawName = file.getName();
            int dot = rawName.lastIndexOf('.');
            String baseName = dot > 0 ? rawName.substring(0, dot) : rawName;

            String title = baseName;
            int trackNumber = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,3})[.\\-\\s]+(.+)$").matcher(baseName);
            if (m.matches()) {
                trackNumber = Integer.parseInt(m.group(1));
                title = m.group(2).trim();
            }

            File parent = file.getParentFile();
            String album = parent != null ? parent.getName() : "Unknown";
            String artist = "Unknown";
            String format = isDsf ? "DSF" : "DFF";

            long id = (long) file.getAbsolutePath().hashCode();
            Uri uri = Uri.fromFile(file);
            String folderPath = parent != null ? parent.getAbsolutePath() : "";
            String folderName = parent != null ? parent.getName() : "Unknown";

            String albumGroupFolder = folderPath;
            if (folderName.toLowerCase(Locale.ROOT).matches("^(cd|disc)[\\s-]*\\d+$") && parent != null && parent.getParentFile() != null) {
                albumGroupFolder = parent.getParentFile().getAbsolutePath();
            }

            long albumId = (long) (album + artist + albumGroupFolder).hashCode();

            return new Track(id, title, artist, durationMs, uri,
                    album, albumId, trackNumber, 1, 0,
                    folderPath, folderName,
                    null, null, null,
                    0, sampleRate, 1, channelCount, format);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read DSD: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private void registerAlbums() {
        ArtworkCache artworkCache = ArtworkCache.getInstance(this);
        artworkCache.clearAlbumRegistry();
        Set<Long> seen = new HashSet<>();
        for (Track t : tracks) {
            if (seen.add(t.albumId)) {
                artworkCache.registerAlbum(t.albumId, t.uri, t.folderPath);
            }
        }
    }

    private void notifyFragmentsDataLoaded() {
        for (Fragment f : fragments) {
            if (f instanceof TracksFragment) {
                ((TracksFragment) f).loadData();
            } else if (f instanceof GroupedFragment) {
                ((GroupedFragment) f).loadData();
            }
        }
    }

    // -- TrackDataProvider --

    @Override
    public List<Track> getAllTracks() {
        return tracks;
    }

    @Override
    public void playTrack(Track track, List<Track> queue) {
        if (playbackController != null) {
            playbackController.playTrack(track, queue);
        }
    }

    @Override
    public long getPlayingTrackId() {
        return playbackController != null ? playbackController.getPlayingTrackId() : -1;
    }

    private void notifyPlaybackObservers() {
        long trackId = playbackController != null ? playbackController.getPlayingTrackId() : -1;
        for (Fragment f : fragments) {
            if (f instanceof PlaybackObserver) {
                ((PlaybackObserver) f).onPlayingTrackChanged(trackId);
            }
        }
        if (searchFragment != null) {
            searchFragment.onPlayingTrackChanged(trackId);
        }
    }

    // -- Output info / Signal path --

    private void updateOutputInfo() {
        if (playbackController == null || !playbackController.isPlaying()) {
            binding.tvAudioOutputInfo.setVisibility(View.GONE);
            binding.signalPathView.setVisibility(View.GONE);
            return;
        }

        String formatStr;
        if (playbackController.isDsd()) {
            int dr = playbackController.getDsdRate();
            String dsdLabel;
            if (dr == 2822400) dsdLabel = "DSD64";
            else if (dr == 5644800) dsdLabel = "DSD128";
            else if (dr == 11289600) dsdLabel = "DSD256";
            else dsdLabel = "DSD";
            formatStr = dsdLabel;
        } else {
            int rate = playbackController.getSampleRate();
            int channels = playbackController.getChannelCount();

            String rateStr;
            if (rate % 1000 == 0) {
                rateStr = (rate / 1000) + "kHz";
            } else {
                rateStr = String.format("%.1fkHz", rate / 1000.0);
            }

            int srcBits = playbackController.getSourceBitDepth();

            String bitStr;
            if (playbackController.isUsbOutputActive()) {
                AudioOutput out = playbackController.getOutput();
                if (out instanceof UsbAudioOutput) {
                    int dacBits = ((UsbAudioOutput) out).getConfiguredBitDepth();
                    if (srcBits != dacBits) {
                        bitStr = srcBits + ">" + dacBits + "bit";
                    } else {
                        bitStr = dacBits + "bit";
                    }
                } else {
                    bitStr = srcBits + "bit";
                }
            } else {
                bitStr = srcBits + "bit";
            }
            formatStr = rateStr + "/" + bitStr + "/" + channels + "ch";
        }

        String outputName;
        if (playbackController.isUsbOutputActive()) {
            AudioOutput out = playbackController.getOutput();
            if (out instanceof UsbAudioOutput) {
                int uacVer = ((UsbAudioOutput) out).getUacVersion();
                outputName = "USB DAC (UAC" + uacVer + ")";
            } else {
                outputName = "USB";
            }
        } else {
            String btName = getBluetoothOutputName();
            outputName = btName != null ? btName : "Speaker";
        }
        String info = formatStr + " > " + outputName;

        binding.tvAudioOutputInfo.setText(info);
        binding.tvAudioOutputInfo.setVisibility(View.VISIBLE);
        updateSignalPathDisplay();
    }

    private void cycleSignalPathMode() {
        signalPathMode = (signalPathMode + 1) % 3;
        settings.setSignalPathMode(signalPathMode);
        updateSignalPathDisplay();
    }

    private void updateSignalPathDisplay() {
        if (playbackController == null || !playbackController.isPlaying()) {
            binding.signalPathView.setVisibility(View.GONE);
            return;
        }

        if (signalPathMode == 0) {
            binding.signalPathView.setVisibility(View.GONE);
            binding.tvAudioOutputInfo.setVisibility(View.VISIBLE);
        } else {
            Fade fade = new Fade();
            fade.setDuration(150);
            fade.setInterpolator(new FastOutSlowInInterpolator());
            TransitionManager.beginDelayedTransition((ViewGroup) binding.signalPathView.getParent(), fade);
            binding.tvAudioOutputInfo.setVisibility(View.GONE);
            SignalPathInfo info = buildSignalPathInfo();
            binding.signalPathView.setInfo(info, signalPathMode);
            binding.signalPathView.setVisibility(View.VISIBLE);
        }
    }

    private SignalPathInfo buildSignalPathInfo() {
        SignalPathInfo info = new SignalPathInfo();

        if (playbackController == null) return info;

        Track currentTrack = playbackController.getCurrentTrack();

        info.sourceRate = playbackController.getSampleRate();
        info.sourceBitDepth = playbackController.getSourceBitDepth();
        info.sourceChannels = playbackController.getChannelCount();
        info.sourceMime = playbackController.getMime();
        info.isDsd = playbackController.isDsd();
        info.dsdRate = playbackController.getDsdRate();
        info.decodedEncoding = playbackController.getEncoding();
        info.codecName = playbackController.getCodecName();

        if (info.isDsd) {
            info.dsdPcmRate = playbackController.getSampleRate();
            String pbMode = playbackController.getDsdPlaybackMode();
            info.dsdPlaybackMode = (pbMode != null) ? pbMode
                    : (playbackController.isDopMode() ? "DoP" : "Native");
            int dr = info.dsdRate;
            if (dr == 2822400) info.sourceFormat = "DSD64";
            else if (dr == 5644800) info.sourceFormat = "DSD128";
            else if (dr == 11289600) info.sourceFormat = "DSD256";
            else info.sourceFormat = "DSD";
            info.sourceRate = dr;
        } else {
            info.sourceFormat = mimeToFormat(info.sourceMime);
        }

        if (currentTrack != null && currentTrack.source == Track.Source.TIDAL) {
            info.sourceType = "TIDAL";
            TidalModels.StreamInfo streamInfo = playbackController.getLastTidalStreamInfo();
            if (streamInfo != null) {
                info.tidalQuality = streamInfo.quality;
                info.tidalRequestedQuality = streamInfo.requestedQuality;
                info.tidalCodec = streamInfo.codec;
                info.tidalFileSize = streamInfo.fileSize > 0
                        ? streamInfo.fileSize : streamInfo.estimatedDashSize;
            }
        } else if (currentTrack != null && currentTrack.source == Track.Source.QOBUZ) {
            info.sourceType = "QOBUZ";
        } else {
            info.sourceType = "LOCAL";
        }

        AudioOutput output = playbackController.getOutput();
        if (output instanceof UsbAudioOutput) {
            UsbAudioOutput usb = (UsbAudioOutput) output;
            int uac = usb.getUacVersion();
            info.uacVersion = uac;
            info.outputDevice = "USB DAC (UAC" + uac + ")";
            info.outputBitDepth = usb.getConfiguredBitDepth();
            info.outputRate = playbackController.getSampleRate();
            info.outputChannels = playbackController.getChannelCount();
            info.usbDeviceInfo = usb.getDeviceInfo();
            info.usbSupportedRates = usb.getSupportedRates();

            int enc = playbackController.getEncoding();
            if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                info.writePathLabel = "float32>int" + info.outputBitDepth;
            } else if (enc == android.media.AudioFormat.ENCODING_PCM_16BIT && info.outputBitDepth != 16) {
                info.writePathLabel = "int16>int" + info.outputBitDepth;
            } else {
                info.writePathLabel = "passthrough";
            }

            info.isBitPerfect = !info.isDsd
                    && info.sourceBitDepth == info.outputBitDepth
                    && ("passthrough".equals(info.writePathLabel)
                        || "float32>int24".equals(info.writePathLabel));

        } else {
            String btName = getBluetoothOutputName();
            if (btName != null) {
                info.outputDevice = "Bluetooth [" + btName + "]";
            } else {
                info.outputDevice = "Speaker";
            }
            info.outputRate = playbackController.getSampleRate();
            info.outputBitDepth = info.sourceBitDepth;
            info.outputChannels = playbackController.getChannelCount();
            info.isBitPerfect = false;
        }

        // EQ info
        if (playbackController.isEqActive()) {
            info.eqActive = true;
            EqProfile ep = playbackController.getEqProfile();
            info.eqProfileName = ep != null ? ep.name : null;
            info.isBitPerfect = false;
        }

        return info;
    }

    private static String mimeToFormat(String mime) {
        if (mime == null) return "PCM";
        switch (mime) {
            case "audio/flac": return "FLAC";
            case "audio/mpeg": return "MP3";
            case "audio/mp4a-latm":
            case "audio/aac": return "AAC";
            case "audio/vorbis":
            case "audio/ogg": return "OGG";
            case "audio/opus": return "OPUS";
            case "audio/raw":
            case "audio/x-wav": return "WAV";
            case "audio/alac": return "ALAC";
            case "audio/x-ape": return "APE";
            case "audio/aiff": return "AIFF";
            case "audio/x-ms-wma": return "WMA";
            case "audio/dsd": return "DSD";
            default: return mime.replace("audio/", "").toUpperCase();
        }
    }

    private String getBluetoothOutputName() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                CharSequence name = device.getProductName();
                if (name != null && name.length() > 0) {
                    return name.toString();
                }
                return "Bluetooth";
            }
        }
        return null;
    }

    private static String resolveArtworkKey(Track track) {
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            return "tidal:" + track.artworkUrl;
        } else if (track.source == Track.Source.QOBUZ && track.artworkUrl != null) {
            return track.artworkUrl;
        }
        return "album:" + track.albumId;
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    // BluetoothCodecManager.BluetoothCodecListener

    @Override
    public void onCodecConfigApplied(BluetoothDevice device) {
        Toast.makeText(this, R.string.bt_codec_applied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCodecConfigFailed(BluetoothDevice device, String reason) {
        Toast.makeText(this, getString(R.string.bt_codec_apply_failed, reason),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCodecConfigAppliedUnverified(BluetoothDevice device) {
        Toast.makeText(this, R.string.bt_codec_applied_unverified, Toast.LENGTH_SHORT).show();
    }
}
