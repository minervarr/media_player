package com.example.media_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.provider.Settings;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.media_player.tidal.DashFlacDataSource;
import com.example.media_player.tidal.HttpMediaDataSource;
import com.example.media_player.tidal.TidalApi;
import com.example.media_player.tidal.TidalAuth;
import com.example.media_player.tidal.TidalModels;

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

    private PlaybackCallback callback;
    private boolean foregroundStarted;

    public interface PlaybackCallback {
        void onTrackChanged(Track track, long trackId);
        void onPlayStateChanged(boolean isPlaying);
        void onOutputChanged();
        void onPrepared();
        void onError(String message);
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
            }
        }

        public void setAllTracks(List<Track> tracks) {
            allTracks = new ArrayList<>(tracks);
        }

        public void setCallback(PlaybackCallback cb) {
            callback = cb;
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

        public AppSettings getSettings() {
            return settings;
        }

        public void switchToUsbOutput(UsbDevice device) {
            MusicService.this.switchToUsbOutput(device);
        }

        public void switchToSpeakerOutput() {
            MusicService.this.switchToSpeakerOutput();
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

        usbAudioManager = new UsbAudioManager(this);
        usbAudioManager.setListener(this);
        usbAudioManager.register();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
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
            releasePlayer();
            mainHandler.post(() -> startPlayback(track));
        });
    }

    private void startPlayback(Track track) {
        Log.d(TAG, "playTrack: \"" + track.title + "\" by " + track.artist
                + " [" + track.album + "] (" + (currentQueueIndex + 1)
                + "/" + currentQueue.size() + ")");

        if (!requestAudioFocus()) {
            fireError("Could not get audio focus");
            return;
        }

        audioEngine = new AudioEngine();
        audioEngine.setOnPreparedListener(engine ->
                mainHandler.post(() -> {
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
        } else {
            audioEngine.play(this, track.uri);
        }

        // Start as a started service so it survives unbind
        ContextCompat.startForegroundService(this, new Intent(this, MusicService.class));
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
            // Read active BT codec from system settings
            int codecType = -1;
            try {
                codecType = Settings.Global.getInt(getContentResolver(),
                        "bluetooth_a2dp_codec", -1);
            } catch (Exception ignored) {}

            // LDAC=4, aptX HD=3 -> LOSSLESS; AAC=1, SBC=0, aptX=2 -> HIGH
            if (codecType == BluetoothDeviceCodecConfig.CODEC_LDAC
                    || codecType == BluetoothDeviceCodecConfig.CODEC_APTX_HD) {
                Log.d(TAG, "Smart quality: Bluetooth codec=" + codecType + " -> LOSSLESS");
                return "LOSSLESS";
            } else {
                Log.d(TAG, "Smart quality: Bluetooth codec=" + codecType + " -> HIGH");
                return "HIGH";
            }
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

    private void togglePlayPause() {
        if (audioEngine == null) return;
        if (audioEngine.isPlaying()) {
            Log.d(TAG, "pause");
            audioEngine.pause();
        } else {
            Log.d(TAG, "resume");
            audioEngine.resume();
        }
        boolean playing = audioEngine.isPlaying();
        fireCallback(() -> { if (callback != null) callback.onPlayStateChanged(playing); });
    }

    private void playNext() {
        if (currentQueue.isEmpty()) return;
        int nextIndex = currentQueueIndex + 1;
        if (nextIndex < currentQueue.size()) {
            Log.d(TAG, "playNext: advancing to queue index " + nextIndex);
            currentQueueIndex = nextIndex;
            playCurrentQueueTrack();
        } else if (settings.isContinuousPlayback() && !isQueueTidal()) {
            Log.d(TAG, "playNext: end of queue, continuing to next album");
            continueToNextAlbum();
        } else {
            Log.d(TAG, "playNext: end of queue, wrapping to start");
            currentQueueIndex = 0;
            playCurrentQueueTrack();
        }
    }

    private boolean isQueueTidal() {
        return !currentQueue.isEmpty()
                && currentQueue.get(0).source == Track.Source.TIDAL;
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
        currentQueueIndex++;
        if (currentQueueIndex >= 0 && currentQueueIndex < currentQueue.size()) {
            Track track = currentQueue.get(currentQueueIndex);
            playingTrackId = track.id;
            Log.d(TAG, "gapless transition: \"" + track.title + "\" ("
                    + (currentQueueIndex + 1) + "/" + currentQueue.size() + ")");
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

    private void releasePlayer() {
        if (audioEngine != null) {
            audioEngine.stop();
            audioEngine = null;
        }
        usbOutputActive = false;
        lastTidalStreamInfo = null;
        abandonAudioFocus();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
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

    // -- Notification --

    private void updateNotification(Track track) {
        String artworkKey;
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            artworkKey = "tidal:" + track.artworkUrl;
        } else {
            artworkKey = "album:" + track.albumId;
        }
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
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true);

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

    // -- Helpers --

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
