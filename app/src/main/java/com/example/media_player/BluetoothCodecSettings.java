package com.example.media_player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

public class BluetoothCodecSettings {

    private static final String PREFS_NAME = "matrix_player_prefs";
    private static final String KEY_PREFIX = "bt_codec_";

    private final SharedPreferences prefs;

    public BluetoothCodecSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public BluetoothDeviceCodecConfig getDeviceConfig(String mac) {
        String json = prefs.getString(KEY_PREFIX + mac, null);
        if (json == null) return null;
        return BluetoothDeviceCodecConfig.fromJson(json);
    }

    public void saveDeviceConfig(String mac, BluetoothDeviceCodecConfig config) {
        prefs.edit().putString(KEY_PREFIX + mac, config.toJson()).apply();
    }

    public void removeDeviceConfig(String mac) {
        prefs.edit().remove(KEY_PREFIX + mac).apply();
    }

    public Map<String, BluetoothDeviceCodecConfig> getAllConfiguredDevices() {
        Map<String, BluetoothDeviceCodecConfig> result = new LinkedHashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_PREFIX) && entry.getValue() instanceof String) {
                String mac = key.substring(KEY_PREFIX.length());
                BluetoothDeviceCodecConfig config =
                        BluetoothDeviceCodecConfig.fromJson((String) entry.getValue());
                result.put(mac, config);
            }
        }
        return result;
    }
}
