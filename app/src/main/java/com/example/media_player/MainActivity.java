package com.example.media_player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.util.List;

public class MainActivity extends AppCompatActivity implements TrackDataProvider {

    private ActivityMainBinding binding;
    private final List<Track> tracks = new ArrayList<>();
    private MediaPlayer mediaPlayer;
    private boolean isUserSeeking = false;

    private List<Track> currentQueue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private long playingTrackId = -1;

    private final Fragment[] fragments = new Fragment[4];
    private int currentTabIndex = 0;

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

    private final ActivityResultLauncher<String> permissionLauncher =
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

        enableFullscreen();
        setSupportActionBar(binding.toolbar);

        setupTabs();
        setupPlayerControls();
        checkPermissionAndLoad();
        requestNotificationPermission();
    }

    private void enableFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_artists));
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_folders));

        fragments[0] = new TracksFragment();
        fragments[1] = GroupedFragment.newInstance(GroupedFragment.MODE_ALBUM);
        fragments[2] = GroupedFragment.newInstance(GroupedFragment.MODE_ARTIST);
        fragments[3] = GroupedFragment.newInstance(GroupedFragment.MODE_FOLDER);

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
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadTracks();
        } else {
            permissionLauncher.launch(permission);
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

        notifyFragmentsDataLoaded();
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

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, track.uri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(mp -> playNext());
        } catch (Exception e) {
            Toast.makeText(this, "Could not play track", Toast.LENGTH_SHORT).show();
            releasePlayer();
        }

        seekHandler.removeCallbacks(seekUpdater);
        seekHandler.post(seekUpdater);

        startMusicService(track.title, track.artist);
        notifyPlaybackObservers();
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
        currentQueueIndex = (currentQueueIndex + 1) % currentQueue.size();
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
