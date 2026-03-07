package com.example.media_player;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {

    private static final String PREFS_NAME = "matrix_player_prefs";
    private static final String KEY_CONTINUOUS_PLAYBACK = "continuous_playback";
    private static final String KEY_ARTWORK_KEEP_SCREEN_ON = "artwork_keep_screen_on";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isContinuousPlayback() {
        return prefs.getBoolean(KEY_CONTINUOUS_PLAYBACK, true);
    }

    public void setContinuousPlayback(boolean enabled) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_PLAYBACK, enabled).apply();
    }

    public boolean isArtworkKeepScreenOn() {
        return prefs.getBoolean(KEY_ARTWORK_KEEP_SCREEN_ON, true);
    }

    public void setArtworkKeepScreenOn(boolean enabled) {
        prefs.edit().putBoolean(KEY_ARTWORK_KEEP_SCREEN_ON, enabled).apply();
    }
}
