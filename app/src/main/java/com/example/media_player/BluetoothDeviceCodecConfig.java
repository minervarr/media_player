package com.example.media_player;

import org.json.JSONException;
import org.json.JSONObject;

public class BluetoothDeviceCodecConfig {

    public static final int CODEC_SBC = 0;
    public static final int CODEC_AAC = 1;
    public static final int CODEC_APTX = 2;
    public static final int CODEC_APTX_HD = 3;
    public static final int CODEC_LDAC = 4;

    public static final int SAMPLE_RATE_44100 = 0x1;
    public static final int SAMPLE_RATE_48000 = 0x2;
    public static final int SAMPLE_RATE_88200 = 0x4;
    public static final int SAMPLE_RATE_96000 = 0x8;

    public static final int BITS_16 = 0x1;
    public static final int BITS_24 = 0x2;
    public static final int BITS_32 = 0x4;

    public static final int CHANNEL_STEREO = 0x2;

    public static final int CODEC_PRIORITY_HIGHEST = 1000000;

    // LDAC codecSpecific1 values
    public static final long LDAC_QUALITY_990 = 0;
    public static final long LDAC_QUALITY_660 = 1;
    public static final long LDAC_QUALITY_330 = 2;
    public static final long LDAC_QUALITY_ADAPTIVE = 3;

    public int codecType;
    public int sampleRate;
    public int bitsPerSample;
    public int channelMode;
    public long codecSpecific1;
    public String deviceName;

    public BluetoothDeviceCodecConfig() {
    }

    public static BluetoothDeviceCodecConfig defaults() {
        BluetoothDeviceCodecConfig config = new BluetoothDeviceCodecConfig();
        config.codecType = CODEC_LDAC;
        config.sampleRate = SAMPLE_RATE_44100;
        config.bitsPerSample = BITS_16;
        config.channelMode = CHANNEL_STEREO;
        config.codecSpecific1 = LDAC_QUALITY_990;
        config.deviceName = "";
        return config;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("codecType", codecType);
            obj.put("sampleRate", sampleRate);
            obj.put("bitsPerSample", bitsPerSample);
            obj.put("channelMode", channelMode);
            obj.put("codecSpecific1", codecSpecific1);
            obj.put("deviceName", deviceName != null ? deviceName : "");
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static BluetoothDeviceCodecConfig fromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            BluetoothDeviceCodecConfig config = new BluetoothDeviceCodecConfig();
            config.codecType = obj.optInt("codecType", CODEC_LDAC);
            config.sampleRate = obj.optInt("sampleRate", SAMPLE_RATE_44100);
            config.bitsPerSample = obj.optInt("bitsPerSample", BITS_16);
            config.channelMode = obj.optInt("channelMode", CHANNEL_STEREO);
            config.codecSpecific1 = obj.optLong("codecSpecific1", LDAC_QUALITY_990);
            config.deviceName = obj.optString("deviceName", "");
            return config;
        } catch (JSONException e) {
            return defaults();
        }
    }

    public String getCodecName() {
        switch (codecType) {
            case CODEC_SBC: return "SBC";
            case CODEC_AAC: return "AAC";
            case CODEC_APTX: return "aptX";
            case CODEC_APTX_HD: return "aptX HD";
            case CODEC_LDAC: return "LDAC";
            default: return "Unknown";
        }
    }

    public String getSampleRateString() {
        switch (sampleRate) {
            case SAMPLE_RATE_44100: return "44.1 kHz";
            case SAMPLE_RATE_48000: return "48 kHz";
            case SAMPLE_RATE_88200: return "88.2 kHz";
            case SAMPLE_RATE_96000: return "96 kHz";
            default: return "Unknown";
        }
    }

    public String getBitsPerSampleString() {
        switch (bitsPerSample) {
            case BITS_16: return "16-bit";
            case BITS_24: return "24-bit";
            case BITS_32: return "32-bit";
            default: return "Unknown";
        }
    }

    public String getLdacQualityString() {
        switch ((int) codecSpecific1) {
            case 0: return "990 kbps (Best)";
            case 1: return "660 kbps (Standard)";
            case 2: return "330 kbps (Mobile)";
            case 3: return "Adaptive";
            default: return "Unknown";
        }
    }

    public String getSummary() {
        String summary = getCodecName() + " / " + getSampleRateString() + " / " + getBitsPerSampleString();
        if (codecType == CODEC_LDAC) {
            summary += " / " + getLdacQualityString();
        }
        return summary;
    }
}
