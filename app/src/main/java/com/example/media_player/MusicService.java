package com.example.media_player;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

import com.nerio.audioengine.AudioEngine;
import com.nerio.audioengine.AudioOutput;
import com.nerio.audioengine.AudioTrackOutput;
import com.nerio.audioengine.EqProcessor;
import com.nerio.audioengine.EqProfile;
import com.nerio.audioengine.UsbAudioOutput;

import com.example.media_player.qobuz.QobuzApi;
import com.example.media_player.qobuz.QobuzAuth;
import com.example.media_player.qobuz.QobuzModels;
import com.example.media_player.tidal.DashFlacDataSource;
import com.example.media_player.tidal.HttpMediaDataSource;
import com.example.media_player.tidal.TidalApi;
import com.example.media_player.tidal.TidalAuth;
import com.example.media_player.tidal.TidalModels;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service implements UsbAudioManager.UsbAudioListener {

    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_TOGGLE = "com.example.media_player.TOGGLE";
    private static final String ACTION_NEXT = "com.example.media_player.NEXT";
    private static final String ACTION_PREV = "com.example.media_player.PREV";
    public static final String ACTION_RELOAD_EQ = "com.example.media_player.RELOAD_EQ";

    private MediaSessionCompat mediaSession;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackController binder = new PlaybackController();

    private AudioEngine audioEngine;
    private List<Track> currentQueue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private long playingTrackId = -1;

    private List<Track> allTracks = new ArrayList<>();

    private UsbAudioManager usbAudioManager;
    private UsbDeviceConnection usbConnection;
    private boolean usbOutputActive;
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean pausedByFocusLoss;

    private AppSettings settings;
    private TidalAuth tidalAuth;
    private TidalApi tidalApi;
    private final ExecutorService tidalExecutor = Executors.newSingleThreadExecutor();
    private volatile TidalModels.StreamInfo lastTidalStreamInfo;

    private QobuzAuth qobuzAuth;
    private QobuzApi qobuzApi;
    private final ExecutorService qobuzExecutor = Executors.newSingleThreadExecutor();

    private EqProcessor eqProcessor;
    private EqProfile currentEqProfile;
    private EqAssignmentDao eqAssignmentDao;

    private PlaybackCallback callback;
    private boolean foregroundStarted;
    private boolean noisyReceiverRegistered;

    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                if (audioEngine != null && audioEngine.isPlaying()) {
                    mediaSession.getController().getTransportControls().pause();
                }
            }
        }
    };

    // Bluetooth EQ auto-match
    private String activeBtMac;
    private String activeBtDeviceName;
    private BluetoothEqMatcher.MatchResult pendingBtEqMatch;
    private String pendingBtEqMac;
    private boolean btReceiverRegistered;

    private final BroadcastReceiver btConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                onBtDeviceConnected(device);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                onBtDeviceDisconnected(device);
            }
        }
    };

    // Database
    private StatsDao statsDao;
    private QueueDao queueDao;
    private volatile long currentPlayHistoryId = -1;
    private volatile long playStartTimeMs = -1;

    public interface PlaybackCallback {
        void onTrackChanged(Track track, long trackId);
        void onPlayStateChanged(boolean isPlaying);
        void onOutputChanged();
        void onPrepared();
        void onError(String message);
        void onBluetoothEqPrompt(String deviceName, String mac,
                                  BluetoothEqMatcher.MatchResult matchResult);
    }

    public class PlaybackController extends Binder {

        // Control

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

        public void togglePlayPause() {
            MusicService.this.togglePlayPause();
        }

        public void playNext() {
            MusicService.this.playNext();
        }

        public void playPrevious() {
            MusicService.this.playPrevious();
        }

        public void seekTo(int positionMs) {
            if (audioEngine != null) {
                audioEngine.seekTo(positionMs);
                updatePlaybackState();
            }
        }

        public void setAllTracks(List<Track> tracks) {
            allTracks = new ArrayList<>(tracks);
        }

        public void setCallback(PlaybackCallback cb) {
            callback = cb;
            // Deliver pending BT EQ match if the Activity just bound
            if (cb != null && pendingBtEqMatch != null && pendingBtEqMac != null) {
                BluetoothEqMatcher.MatchResult match = pendingBtEqMatch;
                String mac = pendingBtEqMac;
                String name = activeBtDeviceName;
                pendingBtEqMatch = null;
                pendingBtEqMac = null;
                fireCallback(() -> cb.onBluetoothEqPrompt(name, mac, match));
            }
        }

        public void applyBluetoothEqChoice(String mac, EqProfile profile) {
            if (mac == null || profile == null) return;
            eqAssignmentDao.setAssignment(EqAssignmentDao.TYPE_BLUETOOTH,
                    EqAssignmentDao.bluetoothEntityId(mac),
                    profile.name, profile.source, profile.form);
            Log.d(TAG, "BT EQ saved: " + mac + " -> " + profile.name + " [" + profile.source + "]");
            reloadEq();
            Toast.makeText(MusicService.this,
                    "EQ: " + profile.name + " (" + profile.source + ")",
                    Toast.LENGTH_SHORT).show();
        }

        public void dismissBluetoothEqPrompt() {
            pendingBtEqMatch = null;
            pendingBtEqMac = null;
        }

        public String getActiveBtDeviceName() {
            return activeBtDeviceName;
        }

        // Query

        public boolean isPlaying() {
            return audioEngine != null && audioEngine.isPlaying();
        }

        public boolean isPrepared() {
            return audioEngine != null;
        }

        public int getCurrentPosition() {
            return audioEngine != null ? audioEngine.getCurrentPosition() : 0;
        }

        public int getDuration() {
            return audioEngine != null ? audioEngine.getDuration() : 0;
        }

        public long getPlayingTrackId() {
            return playingTrackId;
        }

        public Track getCurrentTrack() {
            if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
                return currentQueue.get(currentQueueIndex);
            }
            return null;
        }

        public List<Track> getCurrentQueue() {
            return currentQueue;
        }

        public int getCurrentQueueIndex() {
            return currentQueueIndex;
        }

        public int getSampleRate() {
            return audioEngine != null ? audioEngine.getSampleRate() : 0;
        }

        public int getChannelCount() {
            return audioEngine != null ? audioEngine.getChannelCount() : 0;
        }

        public int getEncoding() {
            return audioEngine != null ? audioEngine.getEncoding() : 0;
        }

        public int getSourceBitDepth() {
            return audioEngine != null ? audioEngine.getSourceBitDepth() : 0;
        }

        public boolean isDsd() {
            return audioEngine != null && audioEngine.isDsd();
        }

        public int getDsdRate() {
            return audioEngine != null ? audioEngine.getDsdRate() : 0;
        }

        public boolean isDopMode() {
            return audioEngine != null && audioEngine.isDopMode();
        }

        public String getMime() {
            return audioEngine != null ? audioEngine.getMime() : null;
        }

        public String getCodecName() {
            return audioEngine != null ? audioEngine.getCodecName() : null;
        }

        public AudioOutput getOutput() {
            return audioEngine != null ? audioEngine.getOutput() : null;
        }

        public boolean isUsbOutputActive() {
            return usbOutputActive;
        }

        public TidalModels.StreamInfo getLastTidalStreamInfo() {
            return lastTidalStreamInfo;
        }

        public UsbAudioManager getUsbAudioManager() {
            return usbAudioManager;
        }

        public TidalAuth getTidalAuth() {
            return tidalAuth;
        }

        public TidalApi getTidalApi() {
            return tidalApi;
        }

        public QobuzAuth getQobuzAuth() {
            return qobuzAuth;
        }

        public QobuzApi getQobuzApi() {
            return qobuzApi;
        }

        public AppSettings getSettings() {
            return settings;
        }

        public void reloadEq() {
            MusicService.this.reloadEq();
        }

        public boolean isEqActive() {
            return eqProcessor != null && currentEqProfile != null;
        }

        public EqProfile getEqProfile() {
            return currentEqProfile;
        }

        public void switchToUsbOutput(UsbDevice device) {
            MusicService.this.switchToUsbOutput(device);
        }

        public void switchToSpeakerOutput() {
            MusicService.this.switchToSpeakerOutput();
        }

        public StatsDao getStatsDao() {
            return statsDao;
        }

        public QueueDao getQueueDao() {
            return queueDao;
        }

        public EqAssignmentDao getEqAssignmentDao() {
            return eqAssignmentDao;
        }

        /** Attempt to restore saved queue from DB. Returns true if restored. */
        public boolean restoreSavedQueue() {
            QueueDao.SavedQueue saved = queueDao.restoreQueue();
            if (saved == null) return false;
            currentQueue = new ArrayList<>(saved.queue);
            currentQueueIndex = saved.currentIndex;
            if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
                playingTrackId = currentQueue.get(currentQueueIndex).id;
            }
            return true;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();

        settings = new AppSettings(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        tidalAuth = new TidalAuth(this);
        tidalApi = new TidalApi(tidalAuth);
        qobuzAuth = new QobuzAuth(this);
        qobuzApi = new QobuzApi(qobuzAuth);

        MatrixPlayerDatabase dbHelper = MatrixPlayerDatabase.getInstance(this);
        statsDao = new StatsDao(dbHelper);
        queueDao = new QueueDao(dbHelper);
        eqAssignmentDao = new EqAssignmentDao(dbHelper);

        usbAudioManager = new UsbAudioManager(this);
        usbAudioManager.setListener(this);
        usbAudioManager.register();

        initMediaSession();
        registerBtConnectionReceiver();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        unregisterBtConnectionReceiver();
        unregisterNoisyReceiver();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (audioEngine != null) {
            audioEngine.release();
            audioEngine = null;
        }
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
        usbAudioManager.unregister();
        cleanupExecutor.shutdownNow();
        usbExecutor.shutdownNow();
        tidalExecutor.shutdownNow();
        qobuzExecutor.shutdownNow();
        abandonAudioFocus();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        callback = null;
        // If nothing is playing, stop the started service so it can be destroyed
        if (audioEngine == null && foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            stopSelf();
        }
        return true; // return true so onRebind is called
    }

    @Override
    public void onRebind(Intent intent) {
        // Client rebound -- will set callback in onServiceConnected
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) {
                togglePlayPause();
                return START_NOT_STICKY;
            } else if (ACTION_NEXT.equals(action)) {
                playNext();
                return START_NOT_STICKY;
            } else if (ACTION_PREV.equals(action)) {
                playPrevious();
                return START_NOT_STICKY;
            } else if (ACTION_RELOAD_EQ.equals(action)) {
                reloadEq();
                return START_NOT_STICKY;
            }
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        // Service is started for foreground; if no playback, stop self
        if (audioEngine == null && !foregroundStarted) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // -- Playback logic --

    private void playCurrentQueueTrack() {
        if (currentQueueIndex < 0 || currentQueueIndex >= currentQueue.size()) return;

        Track track = currentQueue.get(currentQueueIndex);
        playingTrackId = track.id;

        cleanupExecutor.execute(() -> {
            releaseEngine();
            mainHandler.post(() -> startPlayback(track));
        });
    }

    private void startPlayback(Track track) {
        Log.d(TAG, "playTrack: \"" + track.title + "\" by " + track.artist
                + " [" + track.album + "] (" + (currentQueueIndex + 1)
                + "/" + currentQueue.size() + ")");

        // Finalize previous play stats
        finalizePlayStats();

        if (!requestAudioFocus()) {
            fireError("Could not get audio focus");
            return;
        }

        // Record play start
        currentPlayHistoryId = statsDao.recordPlayStart(track, resolveOutputDeviceName());
        playStartTimeMs = System.currentTimeMillis();

        // Persist queue state
        int posMs = audioEngine != null ? audioEngine.getCurrentPosition() : 0;
        queueDao.saveQueue(currentQueue, currentQueueIndex, posMs, true);

        audioEngine = new AudioEngine();
        configureEq(track);
        audioEngine.setOnPreparedListener(engine ->
                mainHandler.post(() -> {
                    // Configure EQ with actual sample rate now that format is known
                    if (audioEngine != null && eqProcessor != null && currentEqProfile != null
                            && !audioEngine.isDsd()) {
                        eqProcessor.computeCoefficients(currentEqProfile,
                                audioEngine.getSampleRate(), audioEngine.getChannelCount(),
                                audioEngine.getEncoding());
                    }
                    if (audioEngine != null && audioEngine.isDsd() && !usbOutputActive) {
                        UsbDevice dac = usbAudioManager.getConnectedDac();
                        if (dac != null && usbAudioManager.hasPermission(dac)) {
                            switchToUsbOutput(dac);
                        } else {
                            fireError("DSD requires USB DAC");
                            releasePlayer();
                            return;
                        }
                    } else if (settings.isUsbExclusiveMode() && !usbOutputActive) {
                        UsbDevice dac = usbAudioManager.getConnectedDac();
                        if (dac != null && usbAudioManager.hasPermission(dac)) {
                            switchToUsbOutput(dac);
                        }
                    }
                    updatePlaybackState();
                    updateNotificationForCurrentTrack();
                    fireCallback(() -> { if (callback != null) callback.onPrepared(); });
                    queueNextTrack();
                }));
        audioEngine.setOnCompletionListener(engine ->
                mainHandler.post(this::playNext));
        audioEngine.setOnTransitionListener(engine ->
                mainHandler.post(this::handleGaplessTransition));
        audioEngine.setOnErrorListener((engine, message) ->
                mainHandler.post(() -> {
                    if ("OUTPUT_FAILED".equals(message) && usbOutputActive) {
                        Log.w(TAG, "USB output failed, falling back to speaker");
                        switchToSpeakerOutput();
                    } else if ("OUTPUT_FAILED".equals(message)
                            && usbAudioManager.getConnectedDac() != null) {
                        // Speaker output rejected format (e.g. 96kHz float) but USB
                        // switch is pending -- let the output thread retry until the
                        // switch completes
                        Log.d(TAG, "Output failed but USB DAC connected, waiting for switch");
                    } else {
                        fireError("Could not play track");
                        releasePlayer();
                    }
                }));

        if (track.source == Track.Source.TIDAL) {
            playTidalTrack(track);
        } else if (track.source == Track.Source.QOBUZ) {
            playQobuzTrack(track);
        } else {
            audioEngine.play(this, track.uri);
        }

        registerNoisyReceiver();

        // Start as a started service so it survives unbind
        ContextCompat.startForegroundService(this, new Intent(this, MusicService.class));
        mediaSession.setActive(true);
        updateMediaSessionMetadata(track);
        updateNotification(track);

        fireCallback(() -> {
            if (callback != null) callback.onTrackChanged(track, playingTrackId);
        });
    }

    private String resolveQuality() {
        String setting = settings.getTidalAudioQuality();
        if (!"SMART".equals(setting)) return setting;

        // USB DAC -> max quality (check connected too, not just active,
        // since first track resolves quality before USB switch completes)
        if (usbOutputActive || usbAudioManager.getConnectedDac() != null) {
            Log.d(TAG, "Smart quality: USB DAC connected -> HI_RES_LOSSLESS");
            return "HI_RES_LOSSLESS";
        }

        // Check for Bluetooth A2DP output
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        boolean btActive = false;
        for (AudioDeviceInfo d : devices) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                btActive = true;
                break;
            }
        }

        if (btActive) {
            Log.d(TAG, "Smart quality: Bluetooth -> LOSSLESS");
            return "LOSSLESS";
        }

        // Speaker / wired headphones -> lossless is fine
        Log.d(TAG, "Smart quality: speaker/wired -> LOSSLESS");
        return "LOSSLESS";
    }

    private void playTidalTrack(Track track) {
        tidalExecutor.execute(() -> {
            try {
                String quality = resolveQuality();
                long tidalId = Long.parseLong(track.tidalTrackId);
                TidalModels.StreamInfo streamInfo = tidalApi.getStreamInfo(tidalId, quality);

                lastTidalStreamInfo = streamInfo;

                if (streamInfo.wasDowngraded) {
                    mainHandler.post(() -> Toast.makeText(MusicService.this,
                            "TIDAL: " + streamInfo.requestedQuality
                            + " unavailable, playing " + streamInfo.quality,
                            Toast.LENGTH_SHORT).show());
                }

                long durationHintUs = track.durationMs * 1000;

                if (streamInfo.isDash()) {
                    java.io.File tempFile = new java.io.File(getCacheDir(), "dash_" + System.nanoTime() + ".flac");
                    DashFlacDataSource dataSource = new DashFlacDataSource(
                            streamInfo.dashSegmentUrls, tempFile, streamInfo.estimatedDashSize);
                    if (audioEngine != null) {
                        audioEngine.playStream(dataSource, durationHintUs);
                    }
                } else {
                    if (streamInfo.url == null || streamInfo.url.isEmpty()) {
                        mainHandler.post(() -> {
                            fireError("Could not get TIDAL stream");
                            releasePlayer();
                        });
                        return;
                    }
                    HttpMediaDataSource dataSource = new HttpMediaDataSource(
                            streamInfo.url, streamInfo.fileSize, null);
                    if (audioEngine != null) {
                        audioEngine.playStream(dataSource, durationHintUs);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to resolve TIDAL stream", e);
                mainHandler.post(() -> {
                    fireError("Could not get TIDAL stream");
                    releasePlayer();
                });
            }
        });
    }

    private int resolveQobuzQuality() {
        String setting = settings.getQobuzAudioQuality();
        switch (setting) {
            case "ULTRA_HI_RES": return QobuzModels.QUALITY_ULTRA_HI_RES;
            case "HI_RES":       return QobuzModels.QUALITY_HI_RES;
            case "LOSSLESS":     return QobuzModels.QUALITY_LOSSLESS;
            case "MP3":          return QobuzModels.QUALITY_MP3;
            case "SMART":
            default:
                // USB DAC -> Ultra Hi-Res, otherwise -> Lossless
                if (usbOutputActive || usbAudioManager.getConnectedDac() != null) {
                    Log.d(TAG, "Smart Qobuz quality: USB DAC -> ULTRA_HI_RES");
                    return QobuzModels.QUALITY_ULTRA_HI_RES;
                }
                Log.d(TAG, "Smart Qobuz quality: speaker/wired -> LOSSLESS");
                return QobuzModels.QUALITY_LOSSLESS;
        }
    }

    /**
     * Probe the Content-Length of a Qobuz CDN URL with a tiny Range request.
     * Qobuz's track/getFileUrl response does not include the file size, but
     * HttpMediaDataSource needs a known totalSize to drive readAt/fetchRange.
     * Returns -1 on failure.
     */
    private long probeContentLength(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 206) {
                // Content-Range: bytes 0-0/12345678
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0 && slash < cr.length() - 1) {
                        try {
                            return Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (code == 200) {
                // Server ignored Range; fall back to full Content-Length
                long len = conn.getContentLengthLong();
                if (len > 0) return len;
            }
        } catch (Exception e) {
            Log.w(TAG, "probeContentLength failed for Qobuz stream: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1;
    }

    private void playQobuzTrack(Track track) {
        qobuzExecutor.execute(() -> {
            try {
                int quality = resolveQobuzQuality();
                long qobuzId = Long.parseLong(track.qobuzTrackId);
                QobuzModels.StreamUrl streamUrl = qobuzApi.getStreamUrlWithFallback(qobuzId, quality);

                if (streamUrl.url == null || streamUrl.url.isEmpty()) {
                    mainHandler.post(() -> {
                        fireError("Could not get Qobuz stream");
                        releasePlayer();
                    });
                    return;
                }

                if (streamUrl.formatId != quality) {
                    mainHandler.post(() -> Toast.makeText(MusicService.this,
                            "Qobuz: playing " + streamUrl.qualityLabel(),
                            Toast.LENGTH_SHORT).show());
                }

                long durationHintUs = track.durationMs * 1000;
                long contentLength = probeContentLength(streamUrl.url);
                if (contentLength <= 0) {
                    Log.e(TAG, "Could not probe Qobuz stream size for track " + qobuzId);
                    mainHandler.post(() -> {
                        fireError("Could not get Qobuz stream");
                        releasePlayer();
                    });
                    return;
                }
                HttpMediaDataSource dataSource = new HttpMediaDataSource(
                        streamUrl.url, contentLength, null);
                if (audioEngine != null) {
                    audioEngine.playStream(dataSource, durationHintUs);
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to resolve Qobuz stream", e);
                mainHandler.post(() -> {
                    fireError("Could not get Qobuz stream");
                    releasePlayer();
                });
            }
        });
    }

    private void togglePlayPause() {
        if (audioEngine == null) return;
        if (audioEngine.isPlaying()) {
            handlePause();
        } else {
            handleResume();
        }
    }

    private void handlePause() {
        if (audioEngine != null && audioEngine.isPlaying()) {
            audioEngine.pause();
            updatePlaybackState();
            updateNotificationForCurrentTrack();
            fireCallback(() -> { if (callback != null) callback.onPlayStateChanged(false); });
        }
    }

    private void handleResume() {
        if (audioEngine != null && !audioEngine.isPlaying()) {
            if (!requestAudioFocus()) return;
            audioEngine.resume();
            updatePlaybackState();
            updateNotificationForCurrentTrack();
            fireCallback(() -> { if (callback != null) callback.onPlayStateChanged(true); });
        }
    }

    private void playNext() {
        if (currentQueue.isEmpty()) return;
        int nextIndex = currentQueueIndex + 1;
        if (nextIndex < currentQueue.size()) {
            Log.d(TAG, "playNext: advancing to queue index " + nextIndex);
            currentQueueIndex = nextIndex;
            playCurrentQueueTrack();
        } else if (settings.isContinuousPlayback() && !isQueueOnline()) {
            Log.d(TAG, "playNext: end of queue, continuing to next album");
            continueToNextAlbum();
        } else {
            Log.d(TAG, "playNext: end of queue, wrapping to start");
            currentQueueIndex = 0;
            playCurrentQueueTrack();
        }
    }

    private boolean isQueueOnline() {
        return !currentQueue.isEmpty()
                && currentQueue.get(0).source != Track.Source.LOCAL;
    }

    private void continueToNextAlbum() {
        if (currentQueue.isEmpty() || allTracks.isEmpty()) return;

        Track lastTrack = currentQueue.get(currentQueue.size() - 1);
        long currentAlbumId = lastTrack.albumId;

        Map<Long, List<Track>> albumMap = new LinkedHashMap<>();
        for (Track t : allTracks) {
            List<Track> list = albumMap.get(t.albumId);
            if (list == null) {
                list = new ArrayList<>();
                albumMap.put(t.albumId, list);
            }
            list.add(t);
        }

        for (List<Track> list : albumMap.values()) {
            Collections.sort(list, (a, b) -> {
                int cmp = Integer.compare(a.trackNumber, b.trackNumber);
                return cmp != 0 ? cmp : a.title.compareToIgnoreCase(b.title);
            });
        }

        List<Long> albumIds = new ArrayList<>(albumMap.keySet());
        Collections.sort(albumIds, (a, b) -> {
            String nameA = albumMap.get(a).get(0).album;
            String nameB = albumMap.get(b).get(0).album;
            return nameA.compareToIgnoreCase(nameB);
        });

        int currentIdx = albumIds.indexOf(currentAlbumId);
        int nextIdx = (currentIdx + 1) % albumIds.size();
        long nextAlbumId = albumIds.get(nextIdx);

        currentQueue = new ArrayList<>(albumMap.get(nextAlbumId));
        currentQueueIndex = 0;
        playCurrentQueueTrack();
    }

    private void handleGaplessTransition() {
        // Finalize stats for the track that just ended
        finalizePlayStats();

        currentQueueIndex++;
        if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
            Track track = currentQueue.get(currentQueueIndex);
            playingTrackId = track.id;

            // Record start for new track
            currentPlayHistoryId = statsDao.recordPlayStart(track, resolveOutputDeviceName());
            playStartTimeMs = System.currentTimeMillis();

            // Persist queue state
            queueDao.saveQueue(currentQueue, currentQueueIndex, 0, true);
            // Re-evaluate EQ profile for the new track (may differ per-entity)
            if (settings.isEqEnabled()) {
                EqProfile newProfile = resolveEqProfile(track);
                if (newProfile != currentEqProfile) {
                    currentEqProfile = newProfile;
                    if (currentEqProfile != null && eqProcessor != null
                            && audioEngine != null && !audioEngine.isDsd()) {
                        eqProcessor.computeCoefficients(currentEqProfile,
                                audioEngine.getSampleRate(), audioEngine.getChannelCount(),
                                audioEngine.getEncoding());
                    } else if (currentEqProfile == null && eqProcessor != null) {
                        eqProcessor.setEnabled(false);
                    }
                }
            }

            Log.d(TAG, "gapless transition: \"" + track.title + "\" ("
                    + (currentQueueIndex + 1) + "/" + currentQueue.size() + ")");
            updateMediaSessionMetadata(track);
            updatePlaybackState();
            updateNotification(track);
            fireCallback(() -> {
                if (callback != null) callback.onTrackChanged(track, playingTrackId);
                if (callback != null) callback.onPrepared();
            });
            queueNextTrack();
        }
    }

    private void queueNextTrack() {
        if (audioEngine == null) return;
        int nextIndex = currentQueueIndex + 1;
        if (nextIndex >= currentQueue.size()) return;

        Track next = currentQueue.get(nextIndex);
        if (next.source == Track.Source.TIDAL) {
            tidalExecutor.execute(() -> {
                try {
                    String quality = resolveQuality();
                    long tidalId = Long.parseLong(next.tidalTrackId);
                    TidalModels.StreamInfo streamInfo = tidalApi.getStreamInfo(tidalId, quality);
                    long durationHintUs = next.durationMs * 1000;

                    if (streamInfo.isDash()) {
                        java.io.File tempFile = new java.io.File(getCacheDir(), "dash_" + System.nanoTime() + ".flac");
                        DashFlacDataSource dataSource = new DashFlacDataSource(
                                streamInfo.dashSegmentUrls, tempFile, streamInfo.estimatedDashSize);
                        if (audioEngine != null) {
                            audioEngine.queueNextStream(dataSource, durationHintUs);
                        }
                    } else if (streamInfo.url != null && !streamInfo.url.isEmpty()) {
                        HttpMediaDataSource dataSource = new HttpMediaDataSource(
                                streamInfo.url, streamInfo.fileSize, null);
                        if (audioEngine != null) {
                            audioEngine.queueNextStream(dataSource, durationHintUs);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to pre-queue next TIDAL track", e);
                }
            });
        } else if (next.source == Track.Source.QOBUZ) {
            qobuzExecutor.execute(() -> {
                try {
                    int quality = resolveQobuzQuality();
                    long qobuzId = Long.parseLong(next.qobuzTrackId);
                    QobuzModels.StreamUrl streamUrl = qobuzApi.getStreamUrlWithFallback(qobuzId, quality);
                    long durationHintUs = next.durationMs * 1000;

                    if (streamUrl.url != null && !streamUrl.url.isEmpty()) {
                        long contentLength = probeContentLength(streamUrl.url);
                        if (contentLength <= 0) {
                            Log.w(TAG, "Could not probe Qobuz stream size for next track " + qobuzId);
                            return;
                        }
                        HttpMediaDataSource dataSource = new HttpMediaDataSource(
                                streamUrl.url, contentLength, null);
                        if (audioEngine != null) {
                            audioEngine.queueNextStream(dataSource, durationHintUs);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to pre-queue next Qobuz track", e);
                }
            });
        } else {
            audioEngine.queueNext(this, next.uri);
        }
    }

    private void playPrevious() {
        if (currentQueue.isEmpty()) return;
        if (audioEngine != null && audioEngine.getCurrentPosition() > 3000) {
            Log.d(TAG, "playPrevious: restarting current track");
            audioEngine.seekTo(0);
            return;
        }
        Log.d(TAG, "playPrevious: going to previous track");
        currentQueueIndex = (currentQueueIndex - 1 + currentQueue.size()) % currentQueue.size();
        playCurrentQueueTrack();
    }

    // -- Audio focus --

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
                                pausedByFocusLoss = false;
                                updatePlaybackState();
                                updateNotificationForCurrentTrack();
                                fireCallback(() -> {
                                    if (callback != null) callback.onPlayStateChanged(true);
                                });
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            hasAudioFocus = false;
                            pausedByFocusLoss = false;
                            if (audioEngine != null && audioEngine.isPlaying()) {
                                audioEngine.pause();
                                updatePlaybackState();
                                updateNotificationForCurrentTrack();
                                fireCallback(() -> {
                                    if (callback != null) callback.onPlayStateChanged(false);
                                });
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            hasAudioFocus = false;
                            if (audioEngine != null && audioEngine.isPlaying()) {
                                audioEngine.pause();
                                pausedByFocusLoss = true;
                                updatePlaybackState();
                                updateNotificationForCurrentTrack();
                                fireCallback(() -> {
                                    if (callback != null) callback.onPlayStateChanged(false);
                                });
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

    private void configureEq(Track track) {
        boolean eqEnabled = settings.isEqEnabled();

        if (!eqEnabled) {
            currentEqProfile = null;
            if (eqProcessor != null) {
                eqProcessor.destroy();
                eqProcessor = null;
            }
            if (audioEngine != null) audioEngine.setEqProcessor(null);
            return;
        }

        // Per-entity resolution: track -> album -> artist -> folder -> global
        currentEqProfile = resolveEqProfile(track);

        if (currentEqProfile == null) {
            Log.d(TAG, "EQ enabled but no profile resolved");
            if (eqProcessor != null) {
                eqProcessor.destroy();
                eqProcessor = null;
            }
            if (audioEngine != null) audioEngine.setEqProcessor(null);
            return;
        }

        if (eqProcessor == null) {
            eqProcessor = new EqProcessor();
        }
        if (audioEngine != null) {
            audioEngine.setEqProcessor(eqProcessor);
        }
        Log.d(TAG, "EQ configured: " + currentEqProfile.name);
    }

    private EqProfile resolveEqProfile(Track track) {
        // Check per-entity assignments first
        if (eqAssignmentDao != null && track != null) {
            EqAssignmentDao.Assignment a = eqAssignmentDao.resolveForTrack(track);
            if (a != null) {
                EqProfile p = EqProfile.find(this, a.profileName, a.profileSource, a.profileForm);
                if (p != null) {
                    Log.d(TAG, "EQ resolved per-entity: type=" + a.entityType
                            + " profile=" + a.profileName);
                    return p;
                }
            }
        }

        // Bluetooth device profile
        if (activeBtMac != null && eqAssignmentDao != null) {
            EqAssignmentDao.Assignment btA = eqAssignmentDao.getAssignment(
                    EqAssignmentDao.TYPE_BLUETOOTH,
                    EqAssignmentDao.bluetoothEntityId(activeBtMac));
            if (btA != null) {
                EqProfile bp = EqProfile.find(this, btA.profileName,
                        btA.profileSource, btA.profileForm);
                if (bp != null) {
                    Log.d(TAG, "EQ resolved for BT device: " + activeBtDeviceName
                            + " -> " + btA.profileName);
                    return bp;
                }
            }
        }

        // Fall back to global default
        String profileName = settings.getEqProfileName();
        if (profileName == null || profileName.isEmpty()) return null;
        EqProfile p = EqProfile.find(this, profileName,
                settings.getEqProfileSource(), settings.getEqProfileForm());
        if (p == null) {
            Log.w(TAG, "EQ profile not found: " + profileName);
        }
        return p;
    }

    private void reloadEq() {
        Track current = null;
        if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
            current = currentQueue.get(currentQueueIndex);
        }
        configureEq(current);
        // If already playing, reconfigure with current format
        if (audioEngine != null && eqProcessor != null && currentEqProfile != null
                && audioEngine.getSampleRate() > 0 && !audioEngine.isDsd()) {
            eqProcessor.computeCoefficients(currentEqProfile,
                    audioEngine.getSampleRate(), audioEngine.getChannelCount(),
                    audioEngine.getEncoding());
        }
    }

    private String resolveOutputDeviceName() {
        if (usbOutputActive) return "USB DAC";
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                CharSequence name = device.getProductName();
                if (name != null && name.length() > 0) {
                    return "Bluetooth [" + name + "]";
                }
                return "Bluetooth";
            }
        }
        return "Speaker";
    }

    private void finalizePlayStats() {
        if (currentPlayHistoryId >= 0 && playStartTimeMs > 0) {
            long durationPlayed = System.currentTimeMillis() - playStartTimeMs;
            Track current = null;
            if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
                current = currentQueue.get(currentQueueIndex);
            }
            long trackDuration = current != null ? current.durationMs : 0;
            int sr = audioEngine != null ? audioEngine.getSampleRate() : 0;
            int bd = audioEngine != null ? audioEngine.getSourceBitDepth() : 0;
            String tidalQ = lastTidalStreamInfo != null ? lastTidalStreamInfo.quality : null;
            statsDao.recordPlayEnd(currentPlayHistoryId, durationPlayed, trackDuration,
                    sr, bd, tidalQ);
            currentPlayHistoryId = -1;
            playStartTimeMs = -1;
        }
    }

    /** Stop engine and EQ only -- keeps foreground + session active for track switches. */
    private void releaseEngine() {
        finalizePlayStats();
        if (audioEngine != null) {
            audioEngine.stop();
            audioEngine = null;
        }
        if (eqProcessor != null) {
            eqProcessor.destroy();
            eqProcessor = null;
        }
        usbOutputActive = false;
        lastTidalStreamInfo = null;
        unregisterNoisyReceiver();
    }

    /** Full teardown: engine + audio focus + session + foreground notification. */
    private void releasePlayer() {
        releaseEngine();
        abandonAudioFocus();
        mainHandler.post(() -> {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                updatePlaybackState();
            }
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foregroundStarted = false;
            }
        });
    }

    // -- USB audio --

    @Override
    public void onUsbDacConnected(UsbDevice device) {
        Log.d(TAG, "onUsbDacConnected: " + device.getDeviceName()
                + " exclusiveMode=" + settings.isUsbExclusiveMode()
                + " hasPermission=" + usbAudioManager.hasPermission(device)
                + " audioEngine=" + (audioEngine != null));
        mainHandler.post(() -> {
            if (settings.isUsbExclusiveMode() && !usbAudioManager.hasPermission(device)) {
                Log.d(TAG, "onUsbDacConnected: requesting permission");
                usbAudioManager.requestPermission(device);
            } else if (settings.isUsbExclusiveMode()) {
                Log.d(TAG, "onUsbDacConnected: switching to USB output");
                switchToUsbOutput(device);
            }
            fireCallback(() -> { if (callback != null) callback.onOutputChanged(); });
        });
    }

    @Override
    public void onUsbDacDisconnected() {
        Log.d(TAG, "onUsbDacDisconnected: usbOutputActive=" + usbOutputActive
                + " audioEngine=" + (audioEngine != null));
        usbExecutor.execute(() -> {
            if (usbOutputActive && audioEngine != null) {
                if (audioEngine.isDsd()) {
                    audioEngine.stop();
                    usbOutputActive = false;
                    mainHandler.post(() -> {
                        fireError("DSD requires USB DAC");
                        fireCallback(() -> { if (callback != null) callback.onPlayStateChanged(false); });
                    });
                } else {
                    Thread switchThread = new Thread(() ->
                            audioEngine.switchOutput(new AudioTrackOutput()));
                    switchThread.start();
                    try {
                        switchThread.join(2000);
                    } catch (InterruptedException ignored) {}
                    if (switchThread.isAlive()) {
                        Log.w(TAG, "switchOutput timed out on disconnect, forcing release");
                        switchThread.interrupt();
                    }
                    usbOutputActive = false;
                }
            }
            if (usbConnection != null) {
                usbConnection.close();
                usbConnection = null;
            }
            mainHandler.post(() ->
                    fireCallback(() -> { if (callback != null) callback.onOutputChanged(); }));
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
        fireError("USB permission denied");
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
                mainHandler.post(() -> fireError("Failed to open USB device"));
                usbOutput.release();
                conn.close();
                return;
            }

            int currentRate = audioEngine.getSampleRate();
            if (currentRate > 0 && !audioEngine.isDsd()) {
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
                    mainHandler.post(() -> fireError("Sample rate not supported by USB DAC"));
                    usbOutput.release();
                    conn.close();
                    return;
                }
            }

            if (!audioEngine.switchOutput(usbOutput)) {
                Log.d(TAG, "switchToUsbOutput: pipeline failed, staying on speaker");
                mainHandler.post(() -> fireError("USB audio pipeline failed, using speaker"));
                usbOutputActive = false;
                conn.close();
            } else {
                Log.d(TAG, "switchToUsbOutput: success");
                usbConnection = conn;
                usbOutputActive = true;
            }
            mainHandler.post(() ->
                    fireCallback(() -> { if (callback != null) callback.onOutputChanged(); }));
        });
    }

    private void switchToSpeakerOutput() {
        if (audioEngine == null) return;
        if (audioEngine.isDsd()) {
            Log.d(TAG, "switchToSpeakerOutput: DSD active, stopping playback");
            audioEngine.stop();
            usbOutputActive = false;
            fireError("DSD requires USB DAC");
            fireCallback(() -> {
                if (callback != null) callback.onPlayStateChanged(false);
                if (callback != null) callback.onOutputChanged();
            });
            return;
        }
        Log.d(TAG, "switchToSpeakerOutput");
        usbExecutor.execute(() -> {
            audioEngine.switchOutput(new AudioTrackOutput());
            usbOutputActive = false;
            Log.d(TAG, "switchToSpeakerOutput: done");
            mainHandler.post(() ->
                    fireCallback(() -> { if (callback != null) callback.onOutputChanged(); }));
        });
    }

    // -- Media session --

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                handleResume();
            }

            @Override
            public void onPause() {
                handlePause();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                if (audioEngine != null) {
                    audioEngine.seekTo((int) pos);
                    updatePlaybackState();
                }
            }

            @Override
            public void onStop() {
                releasePlayer();
                fireCallback(() -> { if (callback != null) callback.onPlayStateChanged(false); });
            }
        });
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    private static String resolveArtworkKey(Track track) {
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            return "tidal:" + track.artworkUrl;
        } else if (track.source == Track.Source.QOBUZ && track.artworkUrl != null) {
            return track.artworkUrl; // Already prefixed with "qobuz:" by QobuzFragment
        }
        return "album:" + track.albumId;
    }

    private void updateMediaSessionMetadata(Track track) {
        if (mediaSession == null) return;

        String artworkKey = resolveArtworkKey(track);
        Bitmap artwork = ArtworkCache.getInstance(this).getCachedBitmap(artworkKey);

        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs);

        if (artwork != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        }

        mediaSession.setMetadata(meta.build());
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;

        boolean playing = audioEngine != null && audioEngine.isPlaying();
        long position = audioEngine != null ? audioEngine.getCurrentPosition() : 0;

        int state = playing ? PlaybackStateCompat.STATE_PLAYING
                : (audioEngine != null ? PlaybackStateCompat.STATE_PAUSED
                : PlaybackStateCompat.STATE_STOPPED);

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, playing ? 1.0f : 0f, SystemClock.elapsedRealtime())
                .build();

        mediaSession.setPlaybackState(playbackState);
    }

    // -- Notification --

    private void updateNotificationForCurrentTrack() {
        if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
            updateNotification(currentQueue.get(currentQueueIndex));
        }
    }

    private void updateNotification(Track track) {
        String artworkKey = resolveArtworkKey(track);
        Bitmap artwork = ArtworkCache.getInstance(this).getCachedBitmap(artworkKey);

        Notification notification = buildNotification(track.title, track.artist, artwork);
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private Notification buildNotification(String title, String artist, Bitmap artwork) {
        boolean playing = audioEngine != null && audioEngine.isPlaying();

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);

        androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setStyle(mediaStyle)
                .addAction(android.R.drawable.ic_media_previous, "Previous",
                        PendingIntent.getService(this, 0,
                                new Intent(this, MusicService.class).setAction(ACTION_PREV),
                                PendingIntent.FLAG_IMMUTABLE))
                .addAction(playing
                                ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play",
                        PendingIntent.getService(this, 1,
                                new Intent(this, MusicService.class).setAction(ACTION_TOGGLE),
                                PendingIntent.FLAG_IMMUTABLE))
                .addAction(android.R.drawable.ic_media_next, "Next",
                        PendingIntent.getService(this, 2,
                                new Intent(this, MusicService.class).setAction(ACTION_NEXT),
                                PendingIntent.FLAG_IMMUTABLE));

        if (artwork != null) {
            builder.setLargeIcon(artwork);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when music is playing");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    // -- Audio becoming noisy (headphone/BT disconnect) --

    private void registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(becomingNoisyReceiver, filter);
            noisyReceiverRegistered = true;
        }
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver);
            noisyReceiverRegistered = false;
        }
    }

    // -- Helpers --

    // -- Bluetooth EQ auto-match --

    private boolean hasBtConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void registerBtConnectionReceiver() {
        if (!hasBtConnectPermission()) return;
        if (!btReceiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            registerReceiver(btConnectionReceiver, filter);
            btReceiverRegistered = true;
        }

        // Scan for already-connected A2DP devices (handles app start after BT connect)
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            btAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        List<BluetoothDevice> connected =
                                ((BluetoothA2dp) proxy).getConnectedDevices();
                        if (connected != null && !connected.isEmpty()
                                && activeBtMac == null) {
                            BluetoothDevice device = connected.get(0);
                            Log.d(TAG, "BT A2DP already connected at startup: "
                                    + device.getName() + " [" + device.getAddress() + "]");
                            onBtDeviceConnected(device);
                        }
                        btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        }
    }

    private void unregisterBtConnectionReceiver() {
        if (btReceiverRegistered) {
            unregisterReceiver(btConnectionReceiver);
            btReceiverRegistered = false;
        }
    }

    private void onBtDeviceConnected(BluetoothDevice device) {
        String mac = device.getAddress();
        String name = device.getName();
        Log.d(TAG, "BT A2DP connected: " + name + " [" + mac + "]");
        activeBtMac = mac;
        activeBtDeviceName = name != null ? name : mac;

        if (!settings.isEqEnabled()) return;

        // Check for saved BT EQ assignment
        EqAssignmentDao.Assignment saved = eqAssignmentDao.getAssignment(
                EqAssignmentDao.TYPE_BLUETOOTH,
                EqAssignmentDao.bluetoothEntityId(mac));
        if (saved != null) {
            EqProfile p = EqProfile.find(this, saved.profileName,
                    saved.profileSource, saved.profileForm);
            if (p != null) {
                Log.d(TAG, "BT EQ recalled: " + saved.profileName);
                reloadEq();
                mainHandler.post(() -> Toast.makeText(this,
                        "EQ: " + saved.profileName,
                        Toast.LENGTH_SHORT).show());
                return;
            }
        }

        // Run matcher on background thread (loadAll may decompress on first call)
        cleanupExecutor.execute(() -> {
            List<EqProfile> profiles = EqProfile.loadAll(this);
            BluetoothEqMatcher.MatchResult result =
                    BluetoothEqMatcher.match(activeBtDeviceName, profiles);
            if (result == null) {
                Log.d(TAG, "BT EQ: no match for " + activeBtDeviceName);
                return;
            }

            Log.d(TAG, "BT EQ match: tier=" + result.matchTier
                    + " base=" + result.matchedBaseName
                    + " candidates=" + result.allCandidates.size()
                    + " autoApply=" + result.autoApply);

            if (result.autoApply) {
                EqProfile pick = BluetoothEqMatcher.pickAutoApplyProfile(result);
                if (pick != null) {
                    eqAssignmentDao.setAssignment(EqAssignmentDao.TYPE_BLUETOOTH,
                            EqAssignmentDao.bluetoothEntityId(mac),
                            pick.name, pick.source, pick.form);
                    mainHandler.post(() -> {
                        reloadEq();
                        Toast.makeText(this,
                                "EQ: " + pick.name + " (" + pick.source + ")",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            } else {
                // Need user input — show dialog
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onBluetoothEqPrompt(activeBtDeviceName, mac, result);
                    } else {
                        // Activity not bound — store for later delivery
                        pendingBtEqMatch = result;
                        pendingBtEqMac = mac;
                    }
                });
            }
        });
    }

    private void onBtDeviceDisconnected(BluetoothDevice device) {
        String mac = device.getAddress();
        if (mac.equals(activeBtMac)) {
            Log.d(TAG, "BT A2DP disconnected: " + activeBtDeviceName);
            activeBtMac = null;
            activeBtDeviceName = null;
            reloadEq();
        }
    }

    private void fireCallback(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private void fireError(String message) {
        fireCallback(() -> { if (callback != null) callback.onError(message); });
    }
}
