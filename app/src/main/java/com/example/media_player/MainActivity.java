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

import com.example.media_player.databinding.ActivityMainBinding;
import com.example.media_player.tidal.TidalAuth;
import com.example.media_player.tidal.TidalFragment;
import com.example.media_player.tidal.TidalModels;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private final Fragment[] fragments = new Fragment[8];
    private int currentTabIndex = 0;

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: binding to MusicService");
        Intent intent = new Intent(this, MusicService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
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
        if (item.getItemId() == R.id.action_settings) {
            settingsLauncher.launch(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
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

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        for (int i = 0; i < fragments.length; i++) {
            ft.add(R.id.fragment_container, fragments[i], "tab_" + i);
            if (i != 0) ft.hide(fragments[i]);
        }
        ft.commit();

        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int index = tab.getPosition();
                if (index == currentTabIndex) return;
                Log.d(TAG, "tab switched: " + tab.getText());
                FragmentTransaction ft = fm.beginTransaction();
                ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out);
                ft.hide(fragments[currentTabIndex]);
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
        String artworkKey;
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            artworkKey = "tidal:" + track.artworkUrl;
        } else {
            artworkKey = "album:" + track.albumId;
        }
        ArtworkCache.getInstance(this).loadArtwork(artworkKey, binding.ivNowPlayingArtwork, 144);

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

        String artworkKey;
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            artworkKey = "tidal:" + track.artworkUrl;
        } else {
            artworkKey = "album:" + track.albumId;
        }
        ArtworkCache.getInstance(this).loadArtwork(artworkKey, binding.ivNowPlayingArtwork, 144);

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

        List<File> audioFiles = new ArrayList<>();
        for (String path : folderPaths) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                scanDirectory(dir, audioFiles);
            }
        }
        Collections.sort(audioFiles, (a, b) ->
                a.getAbsolutePath().compareTo(b.getAbsolutePath()));

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService scanExecutor = Executors.newFixedThreadPool(threads);
        List<Future<Track>> futures = new ArrayList<>();

        for (File file : audioFiles) {
            futures.add(scanExecutor.submit(() -> extractTrackMetadata(file)));
        }

        for (Future<Track> future : futures) {
            try {
                Track track = future.get();
                if (track != null) {
                    tracks.add(track);
                }
            } catch (Exception e) {
                Log.w("MainActivity", "Failed to extract metadata", e);
            }
        }

        scanExecutor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        Log.d(TAG, "loadTracks: found " + tracks.size() + " tracks in " + elapsed + "ms");

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

            int year = 0;
            String yearStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (yearStr != null) {
                try { year = Integer.parseInt(yearStr.trim()); } catch (NumberFormatException ignored) {}
            }

            long id = (long) file.getAbsolutePath().hashCode();
            long albumId = (long) (album + artist).hashCode();
            Uri uri = Uri.fromFile(file);

            String folderPath = parent != null ? parent.getAbsolutePath() : "";
            String folderName = parent != null ? parent.getName() : "Unknown";

            return new Track(id, title, artist, duration, uri,
                    album, albumId, trackNumber, year, folderPath, folderName);
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to read: " + file.getAbsolutePath(), e);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
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
