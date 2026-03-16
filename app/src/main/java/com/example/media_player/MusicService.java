package com.example.media_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MusicService extends Service {

    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_UPDATE = "com.example.media_player.UPDATE";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";

    private static Bitmap pendingArtwork;

    public static void setPendingArtwork(Bitmap bitmap) {
        pendingArtwork = bitmap;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = "Matrix Player";
        String artist = "";
        if (intent != null) {
            title = intent.getStringExtra(EXTRA_TITLE);
            artist = intent.getStringExtra(EXTRA_ARTIST);
            if (title == null) title = "Matrix Player";
            if (artist == null) artist = "";
        }

        Log.d(TAG, "onStartCommand: \"" + title + "\" by " + artist);

        Notification notification = buildNotification(title, artist);

        startForeground(NOTIFICATION_ID, notification);

        return START_STICKY;
    }

    private Notification buildNotification(String title, String artist) {
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

        if (pendingArtwork != null) {
            builder.setLargeIcon(pendingArtwork);
            pendingArtwork = null;
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
