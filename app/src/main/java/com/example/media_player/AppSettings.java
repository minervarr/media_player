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
    private static final String KEY_TIDAL_AUDIO_QUALITY = "tidal_audio_quality";
    private static final String KEY_QOBUZ_AUDIO_QUALITY = "qobuz_audio_quality";
    private static final String KEY_SIGNAL_PATH_MODE = "signal_path_mode";
    private static final String KEY_LYRICS_ENABLED = "lyrics_enabled";
    private static final String KEY_EQ_ENABLED = "eq_enabled";
    private static final String KEY_EQ_PROFILE_NAME = "eq_profile_name";
    private static final String KEY_EQ_PROFILE_SOURCE = "eq_profile_source";
    private static final String KEY_EQ_PROFILE_FORM = "eq_profile_form";
    private static final String KEY_DSD_PLAYBACK_MODE = "dsd_playback_mode";
    private static final String KEY_VOLUME_MODE = "volume_mode";
    private static final String KEY_SHOW_VOLUME_WARNING = "show_volume_warning";
    private static final String KEY_VOLUME_INTRO_SHOWN = "volume_intro_shown";

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

    public String getTidalAudioQuality() {
        return prefs.getString(KEY_TIDAL_AUDIO_QUALITY, "HI_RES_LOSSLESS");
    }

    public void setTidalAudioQuality(String quality) {
        Log.d(TAG, "setTidalAudioQuality: " + quality);
        prefs.edit().putString(KEY_TIDAL_AUDIO_QUALITY, quality).apply();
    }

    public String getQobuzAudioQuality() {
        return prefs.getString(KEY_QOBUZ_AUDIO_QUALITY, "SMART");
    }

    public void setQobuzAudioQuality(String quality) {
        Log.d(TAG, "setQobuzAudioQuality: " + quality);
        prefs.edit().putString(KEY_QOBUZ_AUDIO_QUALITY, quality).apply();
    }

    public int getSignalPathMode() {
        return prefs.getInt(KEY_SIGNAL_PATH_MODE, 0);
    }

    public void setSignalPathMode(int mode) {
        prefs.edit().putInt(KEY_SIGNAL_PATH_MODE, mode).apply();
    }

    public boolean isLyricsEnabled() {
        return prefs.getBoolean(KEY_LYRICS_ENABLED, true);
    }

    public void setLyricsEnabled(boolean enabled) {
        Log.d(TAG, "setLyricsEnabled: " + enabled);
        prefs.edit().putBoolean(KEY_LYRICS_ENABLED, enabled).apply();
    }

    public boolean isEqEnabled() {
        return prefs.getBoolean(KEY_EQ_ENABLED, false);
    }

    public void setEqEnabled(boolean enabled) {
        Log.d(TAG, "setEqEnabled: " + enabled);
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply();
    }

    public String getEqProfileName() {
        return prefs.getString(KEY_EQ_PROFILE_NAME, "");
    }

    public String getEqProfileSource() {
        return prefs.getString(KEY_EQ_PROFILE_SOURCE, "");
    }

    public String getEqProfileForm() {
        return prefs.getString(KEY_EQ_PROFILE_FORM, "");
    }

    public void setEqProfile(String name, String source, String form) {
        Log.d(TAG, "setEqProfile: " + name + " (" + source + ")");
        prefs.edit()
                .putString(KEY_EQ_PROFILE_NAME, name)
                .putString(KEY_EQ_PROFILE_SOURCE, source)
                .putString(KEY_EQ_PROFILE_FORM, form)
                .apply();
    }

    public String getDsdPlaybackMode() {
        return prefs.getString(KEY_DSD_PLAYBACK_MODE, "AUTO");
    }

    public void setDsdPlaybackMode(String mode) {
        Log.d(TAG, "setDsdPlaybackMode: " + mode);
        prefs.edit().putString(KEY_DSD_PLAYBACK_MODE, mode).apply();
    }

    public String getVolumeMode() {
        return prefs.getString(KEY_VOLUME_MODE, "AUTO");
    }

    public void setVolumeMode(String mode) {
        Log.d(TAG, "setVolumeMode: " + mode);
        prefs.edit().putString(KEY_VOLUME_MODE, mode).apply();
    }

    public boolean isShowVolumeWarning() {
        return prefs.getBoolean(KEY_SHOW_VOLUME_WARNING, true);
    }

    public void setShowVolumeWarning(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHOW_VOLUME_WARNING, enabled).apply();
    }

    public boolean isVolumeIntroShown() {
        return prefs.getBoolean(KEY_VOLUME_INTRO_SHOWN, false);
    }

    public void setVolumeIntroShown(boolean shown) {
        prefs.edit().putBoolean(KEY_VOLUME_INTRO_SHOWN, shown).apply();
    }

    // Per-DAC volume memory. Keyed by USB VID:PID so the user's level is
    // restored when reconnecting the same dongle (and isolated per DAC).
    private static String dacVolumeKey(int vid, int pid) {
        return "volume_dac_" + vid + "_" + pid;
    }

    public float getVolumeForDac(int vid, int pid) {
        return prefs.getFloat(dacVolumeKey(vid, pid), 0f);
    }

    public void setVolumeForDac(int vid, int pid, float linear01) {
        if (Float.isNaN(linear01)) linear01 = 0f;
        if (linear01 < 0f) linear01 = 0f;
        if (linear01 > 1f) linear01 = 1f;
        prefs.edit().putFloat(dacVolumeKey(vid, pid), linear01).apply();
    }
}
