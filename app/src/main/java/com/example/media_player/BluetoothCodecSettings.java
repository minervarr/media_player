package com.example.media_player;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

public class BluetoothCodecSettings {

    private static final String TAG = "BluetoothCodecSettings";
    private static final String LEGACY_PREFS_NAME = "matrix_player_prefs";
    private static final String LEGACY_KEY_PREFIX = "bt_codec_";

    private final MatrixPlayerDatabase dbHelper;

    public BluetoothCodecSettings(MatrixPlayerDatabase dbHelper, Context context) {
        this.dbHelper = dbHelper;
        migrateLegacyPrefs(context);
    }

    public BluetoothDeviceCodecConfig getDeviceConfig(String mac) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(MatrixPlayerDatabase.TABLE_BLUETOOTH_CONFIGS,
                new String[]{"config_json"},
                "mac_address = ?", new String[]{mac},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return BluetoothDeviceCodecConfig.fromJson(c.getString(0));
            }
            return null;
        } finally {
            c.close();
        }
    }

    public void saveDeviceConfig(String mac, BluetoothDeviceCodecConfig config) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("mac_address", mac);
        cv.put("device_name", config.deviceName);
        cv.put("codec_type", config.codecType);
        cv.put("sample_rate", config.sampleRate);
        cv.put("bit_rate", config.bitsPerSample);
        cv.put("channel_mode", config.channelMode);
        cv.put("config_json", config.toJson());
        cv.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict(MatrixPlayerDatabase.TABLE_BLUETOOTH_CONFIGS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void removeDeviceConfig(String mac) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(MatrixPlayerDatabase.TABLE_BLUETOOTH_CONFIGS,
                "mac_address = ?", new String[]{mac});
    }

    public Map<String, BluetoothDeviceCodecConfig> getAllConfiguredDevices() {
        Map<String, BluetoothDeviceCodecConfig> result = new LinkedHashMap<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(MatrixPlayerDatabase.TABLE_BLUETOOTH_CONFIGS,
                new String[]{"mac_address", "config_json"},
                null, null, null, null, "device_name ASC");
        try {
            while (c.moveToNext()) {
                String mac = c.getString(0);
                BluetoothDeviceCodecConfig config =
                        BluetoothDeviceCodecConfig.fromJson(c.getString(1));
                result.put(mac, config);
            }
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * One-time migration: move bt_codec_* keys from SharedPreferences to SQLite,
     * then remove them from the prefs file.
     */
    private void migrateLegacyPrefs(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();

        boolean found = false;
        SharedPreferences.Editor editor = prefs.edit();

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(LEGACY_KEY_PREFIX) && entry.getValue() instanceof String) {
                found = true;
                String mac = key.substring(LEGACY_KEY_PREFIX.length());
                String json = (String) entry.getValue();
                BluetoothDeviceCodecConfig config = BluetoothDeviceCodecConfig.fromJson(json);

                saveDeviceConfig(mac, config);
                editor.remove(key);
                Log.d(TAG, "migrateLegacyPrefs: moved " + mac + " to SQLite");
            }
        }

        if (found) {
            editor.apply();
            Log.d(TAG, "migrateLegacyPrefs: legacy keys cleaned up");
        }
    }
}
