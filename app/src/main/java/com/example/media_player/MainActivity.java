package com.example.media_player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements TrackDataProvider {

    private ActivityMainBinding binding;
    private final List<Track> tracks = new ArrayList<>();
    private MediaPlayer mediaPlayer;
    private boolean isUserSeeking = false;

    private List<Track> currentQueue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private long playingTrackId = -1;

    private final Fragment[] fragments = new Fragment[7];
    private int currentTabIndex = 0;

    private AppSettings settings;

    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Runnable seekUpdater = new Runnable() {
        @Override
        public void run() {
            try {
                if (mediaPlayer != null && mediaPlayer.isPlaying() && !isUserSeeking) {
                    int pos = mediaPlayer.getCurrentPosition();
                    binding.seekbar.setProgress(pos);
                    binding.tvCurrentTime.setText(formatTime(pos));
                }
            } catch (IllegalStateException ignored) {
            }
            seekHandler.postDelayed(this, 500);
        }
    };

    private final ActivityResultLauncher<String[]> multiPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = !result.containsValue(false);
                if (allGranted) {
                    loadTracks();
                } else {
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> singlePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadTracks();
                } else {
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settings = new AppSettings(this);

        enableFullscreen();
        setSupportActionBar(binding.toolbar);

        setupTabs();
        setupPlayerControls();
        checkPermissionAndLoad();
        requestNotificationPermission();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {})
                        .launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
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
                if (fromUser && mediaPlayer != null) {
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
                if (mediaPlayer != null) {
                    mediaPlayer.seekTo(seekBar.getProgress());
                }
            }
        });
    }

    private void checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String permAudio = Manifest.permission.READ_MEDIA_AUDIO;
            String permImages = Manifest.permission.READ_MEDIA_IMAGES;
            boolean hasAudio = ContextCompat.checkSelfPermission(this, permAudio) == PackageManager.PERMISSION_GRANTED;
            boolean hasImages = ContextCompat.checkSelfPermission(this, permImages) == PackageManager.PERMISSION_GRANTED;
            if (hasAudio && hasImages) {
                loadTracks();
            } else {
                multiPermissionLauncher.launch(new String[]{permAudio, permImages});
            }
        } else {
            String permission = Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                loadTracks();
            } else {
                singlePermissionLauncher.launch(permission);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void loadTracks() {
        tracks.clear();

        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATA
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = getContentResolver().query(collection, projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
                int yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String title = cursor.getString(titleCol);
                    String artist = cursor.getString(artistCol);
                    long duration = cursor.getLong(durationCol);
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    String album = cursor.getString(albumCol);
                    long albumId = cursor.getLong(albumIdCol);
                    int trackNumber = cursor.getInt(trackCol);
                    int year = cursor.getInt(yearCol);
                    String data = cursor.getString(dataCol);

                    String folderPath = "";
                    String folderName = "Unknown";
                    if (data != null) {
                        File parent = new File(data).getParentFile();
                        if (parent != null) {
                            folderPath = parent.getAbsolutePath();
                            folderName = parent.getName();
                        }
                    }

                    if (album == null || album.isEmpty()) album = "Unknown";
                    if (artist == null || artist.isEmpty()) artist = "Unknown";

                    tracks.add(new Track(id, title, artist, duration, uri,
                            album, albumId, trackNumber, year, folderPath, folderName));
                }
            }
        }

        registerAlbums();
        notifyFragmentsDataLoaded();
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

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, track.uri);
            mediaPlayer.setOnPreparedListener(mp -> {
                if (mp == mediaPlayer) {
                    mp.start();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> playNext());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (mp == mediaPlayer) {
                    Toast.makeText(this, "Could not play track", Toast.LENGTH_SHORT).show();
                    releasePlayer();
                }
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Could not play track", Toast.LENGTH_SHORT).show();
            releasePlayer();
        }

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
        if (mediaPlayer == null) {
            if (!tracks.isEmpty()) {
                playTrack(tracks.get(0), tracks);
            }
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                binding.btnPlayPause.setImageResource(R.drawable.ic_play);
            } else {
                mediaPlayer.start();
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void playNext() {
        if (currentQueue.isEmpty()) return;
        int nextIndex = currentQueueIndex + 1;
        if (nextIndex < currentQueue.size()) {
            currentQueueIndex = nextIndex;
            playCurrentQueueTrack();
        } else if (settings.isContinuousPlayback()) {
            continueToNextAlbum();
        } else {
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
        try {
            if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3000) {
                mediaPlayer.seekTo(0);
                binding.seekbar.setProgress(0);
                binding.tvCurrentTime.setText("0:00");
                return;
            }
        } catch (IllegalStateException ignored) {
        }
        currentQueueIndex = (currentQueueIndex - 1 + currentQueue.size()) % currentQueue.size();
        playCurrentQueueTrack();
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        seekHandler.removeCallbacks(seekUpdater);
        releasePlayer();
        stopMusicService();
    }
}
