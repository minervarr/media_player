package com.example.media_player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.Set;

public class AppSettings {

    private static final String TAG = "AppSettings";
    private static final String PREFS_NAME = "matrix_player_prefs";
    private static final String KEY_CONTINUOUS_PLAYBACK = "continuous_playback";
    private static final String KEY_ARTWORK_KEEP_SCREEN_ON = "artwork_keep_screen_on";
    private static final String KEY_USB_EXCLUSIVE_MODE = "usb_exclusive_mode";
    private static final String KEY_MUSIC_FOLDERS = "music_folders";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isContinuousPlayback() {
        return prefs.getBoolean(KEY_CONTINUOUS_PLAYBACK, true);
    }

    public void setContinuousPlayback(boolean enabled) {
        Log.d(TAG, "setContinuousPlayback: " + enabled);
        prefs.edit().putBoolean(KEY_CONTINUOUS_PLAYBACK, enabled).apply();
    }

    public boolean isArtworkKeepScreenOn() {
        return prefs.getBoolean(KEY_ARTWORK_KEEP_SCREEN_ON, true);
    }

    public void setArtworkKeepScreenOn(boolean enabled) {
        Log.d(TAG, "setArtworkKeepScreenOn: " + enabled);
        prefs.edit().putBoolean(KEY_ARTWORK_KEEP_SCREEN_ON, enabled).apply();
    }

    public boolean isUsbExclusiveMode() {
        return prefs.getBoolean(KEY_USB_EXCLUSIVE_MODE, true);
    }

    public void setUsbExclusiveMode(boolean enabled) {
        Log.d(TAG, "setUsbExclusiveMode: " + enabled);
        prefs.edit().putBoolean(KEY_USB_EXCLUSIVE_MODE, enabled).apply();
    }

    public Set<String> getMusicFolders() {
        return prefs.getStringSet(KEY_MUSIC_FOLDERS, Collections.emptySet());
    }

    public void setMusicFolders(Set<String> folders) {
        Log.d(TAG, "setMusicFolders: " + folders.size() + " folders");
        prefs.edit().putStringSet(KEY_MUSIC_FOLDERS, folders).apply();
    }
}
