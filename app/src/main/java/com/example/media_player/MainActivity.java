package com.example.media_player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

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

import com.example.media_player.databinding.ActivityMainBinding;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity
        implements TrackDataProvider, UsbAudioManager.UsbAudioListener,
        BluetoothCodecManager.BluetoothCodecListener {

    private static final String TAG = "MatrixPlayer";

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".flac", ".mp3", ".wav", ".aac", ".ogg", ".m4a",
            ".opus", ".wma", ".dsf", ".dff", ".ape", ".aiff"));

    private ActivityMainBinding binding;
    private final List<Track> tracks = new ArrayList<>();
    private AudioEngine audioEngine;
    private boolean isUserSeeking = false;

    private List<Track> currentQueue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private long playingTrackId = -1;

    private final Fragment[] fragments = new Fragment[7];
    private int currentTabIndex = 0;

    private AppSettings settings;
    private BluetoothCodecManager bluetoothCodecManager;
    private UsbAudioManager usbAudioManager;
    private UsbDeviceConnection usbConnection;
    private boolean usbOutputActive;

    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean pausedByFocusLoss;

    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Runnable seekUpdater = new Runnable() {
        @Override
        public void run() {
            if (audioEngine != null && audioEngine.isPlaying() && !isUserSeeking) {
                int pos = audioEngine.getCurrentPosition();
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
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        enableFullscreen();
        setSupportActionBar(binding.toolbar);

        setupTabs();
        setupPlayerControls();
        checkPermissionAndLoad();

        usbAudioManager = new UsbAudioManager(this);
        usbAudioManager.setListener(this);
        usbAudioManager.register();

        if (BluetoothCodecManager.isFeatureAvailable(this)) {
            bluetoothCodecManager = new BluetoothCodecManager(this);
            bluetoothCodecManager.setListener(this);
            bluetoothCodecManager.register();
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

        fragments[0] = new TracksFragment();
        fragments[1] = GroupedFragment.newInstance(GroupedFragment.MODE_ALBUM);
        fragments[2] = GroupedFragment.newInstance(GroupedFragment.MODE_EP);
        fragments[3] = GroupedFragment.newInstance(GroupedFragment.MODE_SINGLE);
        fragments[4] = GroupedFragment.newInstance(GroupedFragment.MODE_REMIX);
        fragments[5] = GroupedFragment.newInstance(GroupedFragment.MODE_ARTIST);
        fragments[6] = GroupedFragment.newInstance(GroupedFragment.MODE_FOLDER);

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
        binding.btnPlayPause.setOnClickListener(v -> togglePlayPause());
        binding.btnNext.setOnClickListener(v -> playNext());
        binding.btnPrevious.setOnClickListener(v -> playPrevious());

        binding.ivNowPlayingArtwork.setOnClickListener(v -> {
            if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
                Track track = currentQueue.get(currentQueueIndex);
                startActivity(ArtworkActivity.newIntent(this, track.albumId));
            }
        });

        binding.seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioEngine != null) {
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
                if (audioEngine != null) {
                    Log.d(TAG, "seekTo: " + formatTime(seekBar.getProgress()));
                    audioEngine.seekTo(seekBar.getProgress());
                }
            }
        });
    }

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

        // Walk directories and collect audio files
        List<File> audioFiles = new ArrayList<>();
        for (String path : folderPaths) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                scanDirectory(dir, audioFiles);
            }
        }
        Collections.sort(audioFiles, (a, b) ->
                a.getAbsolutePath().compareTo(b.getAbsolutePath()));

        // Extract metadata in parallel
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

    // TrackDataProvider

    @Override
    public List<Track> getAllTracks() {
        return tracks;
    }

    @Override
    public void playTrack(Track track, List<Track> queue) {
        currentQueue = new ArrayList<>(queue);
        currentQueueIndex = -1;
        for (int i = 0; i < currentQueue.size(); i++) {
            if (currentQueue.get(i).id == track.id) {
                currentQueueIndex = i;
                break;
            }
        }
        if (currentQueueIndex < 0) return;
        playCurrentQueueTrack();
    }

    @Override
    public long getPlayingTrackId() {
        return playingTrackId;
    }

    private void playCurrentQueueTrack() {
        if (currentQueueIndex < 0 || currentQueueIndex >= currentQueue.size()) return;

        releasePlayer();

        Track track = currentQueue.get(currentQueueIndex);
        Log.d(TAG, "playTrack: \"" + track.title + "\" by " + track.artist + " [" + track.album + "] (" + (currentQueueIndex + 1) + "/" + currentQueue.size() + ")");
        playingTrackId = track.id;

        binding.tvNowPlayingTitle.setText(track.title);
        binding.tvNowPlayingTitle.setSelected(true);
        binding.tvNowPlayingArtist.setText(track.artist);
        binding.tvTotalTime.setText(track.getFormattedDuration());
        binding.tvCurrentTime.setText("0:00");
        binding.seekbar.setMax((int) track.durationMs);
        binding.seekbar.setProgress(0);
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause);

        ArtworkCache.getInstance(this).loadArtwork(
                "album:" + track.albumId, binding.ivNowPlayingArtwork, 144);

        if (!requestAudioFocus()) {
            Toast.makeText(this, "Could not get audio focus", Toast.LENGTH_SHORT).show();
            return;
        }

        audioEngine = new AudioEngine();
        audioEngine.setOnPreparedListener(engine ->
                runOnUiThread(() -> {
                    updateOutputInfo();
                    // If USB DAC is connected and we have permission, switch to it
                    if (settings.isUsbExclusiveMode() && !usbOutputActive) {
                        UsbDevice dac = usbAudioManager.getConnectedDac();
                        if (dac != null && usbAudioManager.hasPermission(dac)) {
                            switchToUsbOutput(dac);
                        }
                    }
                }));
        audioEngine.setOnCompletionListener(engine ->
                runOnUiThread(this::playNext));
        audioEngine.setOnErrorListener((engine, message) ->
                runOnUiThread(() -> {
                    if ("OUTPUT_FAILED".equals(message) && usbOutputActive) {
                        Log.w("MainActivity", "USB output failed, falling back to speaker");
                        switchToSpeakerOutput();
                    } else {
                        Toast.makeText(this, "Could not play track", Toast.LENGTH_SHORT).show();
                        releasePlayer();
                    }
                }));
        audioEngine.play(this, track.uri);

        seekHandler.removeCallbacks(seekUpdater);
        seekHandler.post(seekUpdater);

        Bitmap artwork = ArtworkCache.getInstance(this).getCachedBitmap("album:" + track.albumId);
        MusicService.setPendingArtwork(artwork);
        startMusicService(track.title, track.artist);
        notifyPlaybackObservers();
        ArtworkActivity.notifyAlbumChanged(track.albumId);
    }

    private void startMusicService(String title, String artist) {
        Intent intent = new Intent(this, MusicService.class);
        intent.putExtra(MusicService.EXTRA_TITLE, title);
        intent.putExtra(MusicService.EXTRA_ARTIST, artist);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopMusicService() {
        stopService(new Intent(this, MusicService.class));
    }

    private void notifyPlaybackObservers() {
        for (Fragment f : fragments) {
            if (f instanceof PlaybackObserver) {
                ((PlaybackObserver) f).onPlayingTrackChanged(playingTrackId);
            }
        }
    }

    private void togglePlayPause() {
        if (audioEngine == null) {
            if (!tracks.isEmpty()) {
                playTrack(tracks.get(0), tracks);
            }
            return;
        }
        if (audioEngine.isPlaying()) {
            Log.d(TAG, "pause");
            audioEngine.pause();
            binding.btnPlayPause.setImageResource(R.drawable.ic_play);
        } else {
            Log.d(TAG, "resume");
            audioEngine.resume();
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
        }
    }

    private void playNext() {
        if (currentQueue.isEmpty()) return;
        int nextIndex = currentQueueIndex + 1;
        if (nextIndex < currentQueue.size()) {
            Log.d(TAG, "playNext: advancing to queue index " + nextIndex);
            currentQueueIndex = nextIndex;
            playCurrentQueueTrack();
        } else if (settings.isContinuousPlayback()) {
            Log.d(TAG, "playNext: end of queue, continuing to next album");
            continueToNextAlbum();
        } else {
            Log.d(TAG, "playNext: end of queue, wrapping to start");
            currentQueueIndex = 0;
            playCurrentQueueTrack();
        }
    }

    private void continueToNextAlbum() {
        if (currentQueue.isEmpty() || tracks.isEmpty()) return;

        Track lastTrack = currentQueue.get(currentQueue.size() - 1);
        long currentAlbumId = lastTrack.albumId;

        // Build sorted list of unique albums
        Map<Long, List<Track>> albumMap = new LinkedHashMap<>();
        for (Track t : tracks) {
            List<Track> list = albumMap.get(t.albumId);
            if (list == null) {
                list = new ArrayList<>();
                albumMap.put(t.albumId, list);
            }
            list.add(t);
        }

        // Sort each album's tracks by trackNumber then title
        for (List<Track> list : albumMap.values()) {
            Collections.sort(list, (a, b) -> {
                int cmp = Integer.compare(a.trackNumber, b.trackNumber);
                return cmp != 0 ? cmp : a.title.compareToIgnoreCase(b.title);
            });
        }

        // Sort albums alphabetically by album name
        List<Long> albumIds = new ArrayList<>(albumMap.keySet());
        Collections.sort(albumIds, (a, b) -> {
            String nameA = albumMap.get(a).get(0).album;
            String nameB = albumMap.get(b).get(0).album;
            return nameA.compareToIgnoreCase(nameB);
        });

        // Find current album index and get next
        int currentIdx = albumIds.indexOf(currentAlbumId);
        int nextIdx = (currentIdx + 1) % albumIds.size();
        long nextAlbumId = albumIds.get(nextIdx);

        currentQueue = new ArrayList<>(albumMap.get(nextAlbumId));
        currentQueueIndex = 0;
        playCurrentQueueTrack();
    }

    private void playPrevious() {
        if (currentQueue.isEmpty()) return;
        if (audioEngine != null && audioEngine.getCurrentPosition() > 3000) {
            Log.d(TAG, "playPrevious: restarting current track");
            audioEngine.seekTo(0);
            binding.seekbar.setProgress(0);
            binding.tvCurrentTime.setText("0:00");
            return;
        }
        Log.d(TAG, "playPrevious: going to previous track");
        currentQueueIndex = (currentQueueIndex - 1 + currentQueue.size()) % currentQueue.size();
        playCurrentQueueTrack();
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private boolean requestAudioFocus() {
        if (hasAudioFocus) return true;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChange -> {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            hasAudioFocus = true;
                            if (pausedByFocusLoss && audioEngine != null) {
                                audioEngine.resume();
                                binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
                                pausedByFocusLoss = false;
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            hasAudioFocus = false;
                            pausedByFocusLoss = false;
                            if (audioEngine != null && audioEngine.isPlaying()) {
                                audioEngine.pause();
                                binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            hasAudioFocus = false;
                            if (audioEngine != null && audioEngine.isPlaying()) {
                                audioEngine.pause();
                                binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                                pausedByFocusLoss = true;
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Could lower volume, but for bit-perfect we just pause
                            hasAudioFocus = false;
                            if (audioEngine != null && audioEngine.isPlaying()) {
                                audioEngine.pause();
                                binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                                pausedByFocusLoss = true;
                            }
                            break;
                    }
                })
                .build();

        int result = audioManager.requestAudioFocus(audioFocusRequest);
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        }
        hasAudioFocus = false;
        pausedByFocusLoss = false;
    }

    private void releasePlayer() {
        if (audioEngine != null) {
            audioEngine.stop();
            audioEngine = null;
        }
        usbOutputActive = false;
        abandonAudioFocus();
    }

    // UsbAudioManager.UsbAudioListener

    @Override
    public void onUsbDacConnected(UsbDevice device) {
        Log.d(TAG, "onUsbDacConnected: " + device.getDeviceName()
                + " exclusiveMode=" + settings.isUsbExclusiveMode()
                + " hasPermission=" + usbAudioManager.hasPermission(device)
                + " audioEngine=" + (audioEngine != null));
        Toast.makeText(this, R.string.usb_dac_connected, Toast.LENGTH_SHORT).show();
        if (settings.isUsbExclusiveMode() && !usbAudioManager.hasPermission(device)) {
            Log.d(TAG, "onUsbDacConnected: requesting permission");
            usbAudioManager.requestPermission(device);
        } else if (settings.isUsbExclusiveMode()) {
            Log.d(TAG, "onUsbDacConnected: switching to USB output");
            switchToUsbOutput(device);
        }
    }

    @Override
    public void onUsbDacDisconnected() {
        Log.d(TAG, "onUsbDacDisconnected: usbOutputActive=" + usbOutputActive + " audioEngine=" + (audioEngine != null));
        Toast.makeText(this, R.string.usb_dac_disconnected, Toast.LENGTH_SHORT).show();
        usbExecutor.execute(() -> {
            if (usbOutputActive && audioEngine != null) {
                // 2-second timeout guard: if switchOutput hangs, force-release
                Thread switchThread = new Thread(() ->
                        audioEngine.switchOutput(new AudioTrackOutput()));
                switchThread.start();
                try {
                    switchThread.join(2000);
                } catch (InterruptedException ignored) {}
                if (switchThread.isAlive()) {
                    Log.w("MainActivity", "switchOutput timed out on disconnect, forcing release");
                    switchThread.interrupt();
                }
                usbOutputActive = false;
            }
            if (usbConnection != null) {
                usbConnection.close();
                usbConnection = null;
            }
            runOnUiThread(this::updateOutputInfo);
        });
    }

    @Override
    public void onUsbPermissionGranted(UsbDevice device) {
        Log.d(TAG, "onUsbPermissionGranted: " + device.getDeviceName());
        if (settings.isUsbExclusiveMode()) {
            switchToUsbOutput(device);
        }
    }

    @Override
    public void onUsbPermissionDenied(UsbDevice device) {
        Log.w(TAG, "onUsbPermissionDenied: " + device.getDeviceName());
        Toast.makeText(this, R.string.usb_permission_denied, Toast.LENGTH_SHORT).show();
    }

    private void switchToUsbOutput(UsbDevice device) {
        if (audioEngine == null) return;
        Log.d(TAG, "switchToUsbOutput: " + device.getDeviceName());

        usbExecutor.execute(() -> {
            UsbDeviceConnection conn = usbAudioManager.getUsbManager().openDevice(device);
            if (conn == null) {
                Log.e(TAG, "switchToUsbOutput: openDevice returned null");
                return;
            }
            Log.d(TAG, "switchToUsbOutput: opened device, fd=" + conn.getFileDescriptor());

            int fd = conn.getFileDescriptor();
            UsbAudioOutput usbOutput = new UsbAudioOutput(fd);

            if (!usbOutput.open()) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to open USB device", Toast.LENGTH_SHORT).show());
                usbOutput.release();
                conn.close();
                return;
            }

            // Check if current sample rate is supported
            int currentRate = audioEngine.getSampleRate();
            if (currentRate > 0) {
                int[] supportedRates = usbOutput.getSupportedRates();
                Log.d(TAG, "switchToUsbOutput: currentRate=" + currentRate
                        + " supportedRates=" + java.util.Arrays.toString(supportedRates));
                boolean rateSupported = false;
                for (int rate : supportedRates) {
                    if (rate == currentRate) {
                        rateSupported = true;
                        break;
                    }
                }
                if (!rateSupported) {
                    Log.w(TAG, "switchToUsbOutput: rate " + currentRate + " not supported by DAC");
                    runOnUiThread(() -> Toast.makeText(this, R.string.usb_rate_unsupported, Toast.LENGTH_SHORT).show());
                    usbOutput.release();
                    conn.close();
                    return;
                }
            }

            if (!audioEngine.switchOutput(usbOutput)) {
                Log.d(TAG, "switchToUsbOutput: pipeline failed, staying on speaker");
                runOnUiThread(() -> Toast.makeText(this, "USB audio pipeline failed, using speaker",
                        Toast.LENGTH_SHORT).show());
                usbOutputActive = false;
                conn.close();
            } else {
                Log.d(TAG, "switchToUsbOutput: success");
                usbConnection = conn;
                usbOutputActive = true;
            }
            runOnUiThread(this::updateOutputInfo);
        });
    }

    private void switchToSpeakerOutput() {
        if (audioEngine == null) return;
        Log.d(TAG, "switchToSpeakerOutput");
        usbExecutor.execute(() -> {
            audioEngine.switchOutput(new AudioTrackOutput());
            usbOutputActive = false;
            Log.d(TAG, "switchToSpeakerOutput: done");
            runOnUiThread(this::updateOutputInfo);
        });
    }

    private void updateOutputInfo() {
        if (audioEngine == null || !audioEngine.isPlaying()) {
            binding.tvAudioOutputInfo.setVisibility(View.GONE);
            return;
        }

        int rate = audioEngine.getSampleRate();
        int channels = audioEngine.getChannelCount();
        int enc = audioEngine.getEncoding();

        String rateStr;
        if (rate % 1000 == 0) {
            rateStr = (rate / 1000) + "kHz";
        } else {
            rateStr = String.format("%.1fkHz", rate / 1000.0);
        }

        String bitStr;
        if (usbOutputActive) {
            // Show DAC's actual configured bit depth
            AudioOutput out = audioEngine.getOutput();
            if (out instanceof UsbAudioOutput) {
                int dacBits = ((UsbAudioOutput) out).getConfiguredBitDepth();
                bitStr = dacBits + "bit";
            } else {
                bitStr = "16bit";
            }
        } else {
            // Show codec encoding for speaker output
            if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                bitStr = "32f";
            } else if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                bitStr = "24bit";
            } else if (enc == AudioFormat.ENCODING_PCM_32BIT) {
                bitStr = "32bit";
            } else {
                bitStr = "16bit";
            }
        }

        String outputName;
        if (usbOutputActive) {
            AudioOutput out = audioEngine.getOutput();
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
        String info = rateStr + "/" + bitStr + "/" + channels + "ch > " + outputName;

        binding.tvAudioOutputInfo.setText(info);
        binding.tvAudioOutputInfo.setVisibility(View.VISIBLE);
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

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        seekHandler.removeCallbacks(seekUpdater);
        if (audioEngine != null) {
            audioEngine.release();
            audioEngine = null;
        }
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
        usbAudioManager.unregister();
        if (bluetoothCodecManager != null) {
            bluetoothCodecManager.unregister();
        }
        usbExecutor.shutdownNow();
        stopMusicService();
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
