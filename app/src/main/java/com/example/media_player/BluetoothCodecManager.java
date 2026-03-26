package com.example.media_player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

public class BluetoothCodecManager {

    private static final String TAG = "BluetoothCodecManager";
    private static final long APPLY_DELAY_MS = 2500;
    private static final long RETRY_DELAY_MS = 2000;
    private static final long VERIFY_DELAY_MS = 1500;

    public interface BluetoothCodecListener {
        void onCodecConfigApplied(BluetoothDevice device);
        void onCodecConfigFailed(BluetoothDevice device, String reason);
        void onCodecConfigAppliedUnverified(BluetoothDevice device);
    }

    public interface AssociationCallback {
        void onAssociated();
        void onFailed(String reason);
    }

    // Request code for pre-API 33 CDM intent sender result
    public static final int CDM_ASSOCIATE_REQUEST_CODE = 7001;

    private final Context context;
    private final BluetoothCodecSettings codecSettings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothA2dp a2dpProxy;
    private BluetoothCodecListener listener;
    private Runnable onProxyReady;
    private boolean registered;

    private final BroadcastReceiver a2dpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (!BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;

            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (state != BluetoothProfile.STATE_CONNECTED) return;

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;

            onA2dpDeviceConnected(device);
        }
    };

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = (BluetoothA2dp) proxy;
                        Log.d(TAG, "A2DP proxy connected");
                        if (onProxyReady != null) {
                            handler.post(onProxyReady);
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = null;
                        Log.d(TAG, "A2DP proxy disconnected");
                    }
                }
            };

    public BluetoothCodecManager(Context context) {
        this.context = context.getApplicationContext();
        MatrixPlayerDatabase db = MatrixPlayerDatabase.getInstance(context);
        this.codecSettings = new BluetoothCodecSettings(db, context);
    }

    public void setListener(BluetoothCodecListener listener) {
        this.listener = listener;
    }

    public void setOnProxyReady(Runnable callback) {
        this.onProxyReady = callback;
    }

    @SuppressLint("MissingPermission")
    public void register() {
        if (registered) return;
        if (!isFeatureAvailable(context)) return;

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) return;

        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP);

        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(a2dpReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(a2dpReceiver, filter);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        try {
            context.unregisterReceiver(a2dpReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (bluetoothAdapter != null && a2dpProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
            a2dpProxy = null;
        }
        handler.removeCallbacksAndMessages(null);
        onProxyReady = null;
        registered = false;
    }

    public static boolean isFeatureAvailable() {
        return probeReflection();
    }

    public static boolean isFeatureAvailable(Context context) {
        return probeReflection() || hasWriteSecureSettings(context) || isCdmAvailable(context);
    }

    public static boolean isCdmAvailable(Context context) {
        return context.getPackageManager()
                .hasSystemFeature("android.software.companion_device_setup");
    }

    public static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    public BluetoothA2dp getA2dpProxy() {
        return a2dpProxy;
    }

    @SuppressLint("MissingPermission")
    private void onA2dpDeviceConnected(BluetoothDevice device) {
        String mac = device.getAddress();
        BluetoothDeviceCodecConfig config = codecSettings.getDeviceConfig(mac);
        if (config == null) return;

        Log.d(TAG, "Saved config found for " + mac + ", applying in " + APPLY_DELAY_MS + "ms");
        handler.postDelayed(() -> applyConfig(device, config, true), APPLY_DELAY_MS);
    }

    @SuppressLint("MissingPermission")
    public void applyConfig(BluetoothDevice device, BluetoothDeviceCodecConfig config,
                            boolean allowRetry) {
        if (a2dpProxy == null) {
            notifyFailed(device, "A2DP proxy not available");
            return;
        }

        List<BluetoothDevice> connected = a2dpProxy.getConnectedDevices();
        boolean deviceConnected = false;
        if (connected != null) {
            for (BluetoothDevice d : connected) {
                if (d.getAddress().equals(device.getAddress())) {
                    deviceConnected = true;
                    break;
                }
            }
        }
        if (!deviceConnected) {
            notifyFailed(device, "Device not connected via A2DP");
            return;
        }

        // Log current codec status for diagnostics
        BluetoothCodecStatus currentStatus = invokeGetCodecStatus(device);
        if (currentStatus != null && currentStatus.getCodecConfig() != null) {
            BluetoothCodecConfig cur = currentStatus.getCodecConfig();
            Log.d(TAG, "Current codec: type=" + cur.getCodecType()
                    + " rate=" + cur.getSampleRate()
                    + " bits=" + cur.getBitsPerSample()
                    + " specific1=" + cur.getCodecSpecific1());
        } else {
            Log.d(TAG, "Could not read current codec status (getCodecStatus returned null)");
        }

        // Build the BluetoothCodecConfig object
        BluetoothCodecConfig codecConfig = buildCodecConfig(config);
        if (codecConfig == null) {
            notifyFailed(device, "Could not build codec config");
            return;
        }

        Log.d(TAG, "Requesting codec: type=" + codecConfig.getCodecType()
                + " rate=" + codecConfig.getSampleRate()
                + " bits=" + codecConfig.getBitsPerSample()
                + " specific1=" + codecConfig.getCodecSpecific1()
                + " priority=" + codecConfig.getCodecPriority());

        // Call setCodecConfigPreference (direct API on 33+, reflection on older)
        boolean called = callSetCodecConfigPreference(device, codecConfig);
        if (called) {
            Log.d(TAG, "setCodecConfigPreference called for " + device.getAddress());
            handler.postDelayed(() -> verifyCodecApplied(device, config), VERIFY_DELAY_MS);
        } else if (allowRetry) {
            Log.d(TAG, "setCodecConfigPreference failed, retrying in " + RETRY_DELAY_MS + "ms");
            handler.postDelayed(() -> applyConfig(device, config, false), RETRY_DELAY_MS);
        } else {
            notifyFailed(device, "setCodecConfigPreference not available");
        }
    }

    private boolean callSetCodecConfigPreference(BluetoothDevice device,
                                                  BluetoothCodecConfig codecConfig) {
        return invokeSetCodecConfigPreference(device, codecConfig);
    }

    @SuppressLint("NewApi")
    private BluetoothCodecConfig buildCodecConfig(BluetoothDeviceCodecConfig config) {
        long codecSpecific1 = config.codecSpecific1;
        if (config.codecType == BluetoothDeviceCodecConfig.CODEC_LDAC) {
            codecSpecific1 = 1000 + config.codecSpecific1;
        }

        // API 33+: use Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return new BluetoothCodecConfig.Builder()
                        .setCodecType(config.codecType)
                        .setSampleRate(config.sampleRate)
                        .setBitsPerSample(config.bitsPerSample)
                        .setChannelMode(config.channelMode)
                        .setCodecSpecific1(codecSpecific1)
                        .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                        .build();
            } catch (Exception e) {
                Log.e(TAG, "Builder failed", e);
            }
        }

        // Pre-33: use hidden constructor via reflection
        try {
            @SuppressWarnings("JavaReflectionMemberAccess")
            Constructor<BluetoothCodecConfig> ctor = BluetoothCodecConfig.class.getConstructor(
                    int.class, int.class, int.class, int.class, int.class,
                    long.class, long.class, long.class, long.class);
            return ctor.newInstance(
                    config.codecType,
                    1000000, // CODEC_PRIORITY_HIGHEST
                    config.sampleRate,
                    config.bitsPerSample,
                    config.channelMode,
                    codecSpecific1,
                    0L, 0L, 0L);
        } catch (Exception e) {
            Log.e(TAG, "Reflection constructor failed", e);
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void verifyCodecApplied(BluetoothDevice device,
                                     BluetoothDeviceCodecConfig requested) {
        BluetoothCodecStatus status = invokeGetCodecStatus(device);
        if (status != null) {
            BluetoothCodecConfig current = status.getCodecConfig();
            if (current != null) {
                Log.d(TAG, "Post-apply codec: type=" + current.getCodecType()
                        + " rate=" + current.getSampleRate()
                        + " bits=" + current.getBitsPerSample()
                        + " specific1=" + current.getCodecSpecific1());
                if (current.getCodecType() == requested.codecType) {
                    if (listener != null) {
                        listener.onCodecConfigApplied(device);
                    }
                    return;
                } else {
                    Log.w(TAG, "Codec mismatch: requested type=" + requested.codecType
                            + " but got type=" + current.getCodecType());
                    notifyFailed(device, "Codec did not change (got type "
                            + current.getCodecType() + ", wanted " + requested.codecType + ")");
                    return;
                }
            }
        }

        // getCodecStatus returned null -- try reading from Settings.Global
        // (the BT stack writes the active codec there after negotiation)
        BluetoothDeviceCodecConfig fromSettings = readCodecFromSettings();
        if (fromSettings != null && fromSettings.codecType == requested.codecType) {
            Log.d(TAG, "Codec verified via Settings.Global: type=" + fromSettings.codecType);
            if (listener != null) {
                listener.onCodecConfigApplied(device);
            }
            return;
        }

        // Can't verify -- report unverified
        Log.d(TAG, "Cannot read codec status to verify, reporting unverified");
        if (listener != null) {
            listener.onCodecConfigAppliedUnverified(device);
        }
    }

    public BluetoothDeviceCodecConfig readCodecFromSettings() {
        try {
            int codecType = Settings.Global.getInt(context.getContentResolver(),
                    "bluetooth_a2dp_codec", -1);
            if (codecType < 0) return null;

            BluetoothDeviceCodecConfig config = new BluetoothDeviceCodecConfig();
            config.codecType = codecType;
            config.sampleRate = Settings.Global.getInt(context.getContentResolver(),
                    "bluetooth_a2dp_sample_rate", BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
            config.bitsPerSample = Settings.Global.getInt(context.getContentResolver(),
                    "bluetooth_a2dp_bits_per_sample", BluetoothDeviceCodecConfig.BITS_16);
            config.channelMode = Settings.Global.getInt(context.getContentResolver(),
                    "bluetooth_a2dp_channel_mode", BluetoothDeviceCodecConfig.CHANNEL_STEREO);
            if (codecType == BluetoothDeviceCodecConfig.CODEC_LDAC) {
                long ldacQuality = Settings.Global.getLong(context.getContentResolver(),
                        "bluetooth_a2dp_ldac_playback_quality", 1000);
                config.codecSpecific1 = ldacQuality - 1000;
            }
            return config;
        } catch (Exception e) {
            Log.w(TAG, "Could not read codec from Settings.Global", e);
            return null;
        }
    }

    private void notifyFailed(BluetoothDevice device, String reason) {
        Log.w(TAG, "Codec config failed: " + reason);
        if (listener != null) {
            listener.onCodecConfigFailed(device, reason);
        }
    }

    public static final int SET_RESULT_OK = 0;
    public static final int SET_RESULT_FAILED = 1;
    public static final int SET_RESULT_SECURITY = 2;

    private int invokeSetCodecConfigPreferenceResult(BluetoothDevice device,
                                                      BluetoothCodecConfig config) {
        try {
            Method method = BluetoothA2dp.class.getMethod(
                    "setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
            method.invoke(a2dpProxy, device, config);
            Log.d(TAG, "Reflection setCodecConfigPreference call completed");
            return SET_RESULT_OK;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                Log.w(TAG, "setCodecConfigPreference: CDM association required", cause);
                return SET_RESULT_SECURITY;
            }
            Log.e(TAG, "setCodecConfigPreference reflection failed", e);
            return SET_RESULT_FAILED;
        }
    }

    private boolean invokeSetCodecConfigPreference(BluetoothDevice device,
                                                   BluetoothCodecConfig config) {
        return invokeSetCodecConfigPreferenceResult(device, config) == SET_RESULT_OK;
    }

    /**
     * Result holder for getCodecStatus that distinguishes "null result" from
     * "SecurityException thrown" so callers can show appropriate UI.
     */
    public static class CodecStatusResult {
        public final BluetoothCodecStatus status;
        public final boolean securityException;

        CodecStatusResult(BluetoothCodecStatus status, boolean securityException) {
            this.status = status;
            this.securityException = securityException;
        }
    }

    public CodecStatusResult invokeGetCodecStatusResult(BluetoothDevice device) {
        if (a2dpProxy == null) return new CodecStatusResult(null, false);
        try {
            Method method = BluetoothA2dp.class.getMethod(
                    "getCodecStatus", BluetoothDevice.class);
            BluetoothCodecStatus status =
                    (BluetoothCodecStatus) method.invoke(a2dpProxy, device);
            return new CodecStatusResult(status, false);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                Log.w(TAG, "getCodecStatus: CDM association required", cause);
                return new CodecStatusResult(null, true);
            }
            Log.e(TAG, "getCodecStatus reflection failed", e);
            return new CodecStatusResult(null, false);
        }
    }

    public BluetoothCodecStatus invokeGetCodecStatus(BluetoothDevice device) {
        return invokeGetCodecStatusResult(device).status;
    }

    private static boolean probeReflection() {
        try {
            BluetoothA2dp.class.getMethod(
                    "setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
            BluetoothA2dp.class.getMethod(
                    "getCodecStatus", BluetoothDevice.class);
            return true;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "Hidden API methods not available");
            return false;
        }
    }

    // --- Companion Device Manager (CDM) association ---

    @SuppressLint("MissingPermission")
    public boolean hasAssociation(Context ctx, String mac) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;

        CompanionDeviceManager cdm = ctx.getSystemService(CompanionDeviceManager.class);
        if (cdm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: getMyAssociations() returns List<AssociationInfo>
            List<AssociationInfo> associations = cdm.getMyAssociations();
            for (AssociationInfo info : associations) {
                String assocMac = info.getDeviceMacAddress() != null
                        ? info.getDeviceMacAddress().toString().toUpperCase()
                        : null;
                if (mac.equalsIgnoreCase(assocMac)) return true;
            }
        } else {
            // API 26-32: getAssociations() returns List<String> (MAC addresses)
            @SuppressWarnings("deprecation")
            List<String> associations = cdm.getAssociations();
            for (String assocMac : associations) {
                if (mac.equalsIgnoreCase(assocMac)) return true;
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    public void requestAssociation(Activity activity, BluetoothDevice device,
                                    AssociationCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onFailed("CDM requires Android 8.0+");
            return;
        }

        CompanionDeviceManager cdm = activity.getSystemService(CompanionDeviceManager.class);
        if (cdm == null) {
            callback.onFailed("CompanionDeviceManager not available");
            return;
        }

        BluetoothDeviceFilter filter = new BluetoothDeviceFilter.Builder()
                .setAddress(device.getAddress())
                .build();

        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build();

        Log.d(TAG, "requestAssociation: starting for " + device.getAddress()
                + " (API " + Build.VERSION.SDK_INT + ")");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: executor-based callback
            Executor mainExecutor = activity.getMainExecutor();
            try {
                cdm.associate(request, mainExecutor, new CompanionDeviceManager.Callback() {
                    @Override
                    public void onAssociationPending(IntentSender intentSender) {
                        // System needs user consent -- launch the dialog
                        Log.d(TAG, "CDM onAssociationPending: launching consent dialog");
                        try {
                            activity.startIntentSenderForResult(intentSender,
                                    CDM_ASSOCIATE_REQUEST_CODE, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            Log.e(TAG, "CDM intent sender failed", e);
                            callback.onFailed("Could not launch association dialog");
                        }
                    }

                    @Override
                    public void onAssociationCreated(AssociationInfo info) {
                        Log.d(TAG, "CDM association created: " + info);
                        callback.onAssociated();
                    }

                    @Override
                    public void onFailure(CharSequence error) {
                        Log.w(TAG, "CDM association failed: " + error);
                        callback.onFailed(error != null ? error.toString() : "Association failed");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "cdm.associate() threw", e);
                callback.onFailed("CDM associate failed: " + e.getMessage());
            }
        } else {
            // API 26-32: handler-based callback, launches IntentSender
            @SuppressWarnings("deprecation")
            CompanionDeviceManager.Callback cdmCallback = new CompanionDeviceManager.Callback() {
                @Override
                public void onDeviceFound(IntentSender chooserLauncher) {
                    try {
                        activity.startIntentSenderForResult(chooserLauncher,
                                CDM_ASSOCIATE_REQUEST_CODE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        Log.e(TAG, "CDM intent sender failed", e);
                        callback.onFailed("Could not launch association dialog");
                    }
                }

                @Override
                public void onFailure(CharSequence error) {
                    Log.w(TAG, "CDM association failed: " + error);
                    callback.onFailed(error != null ? error.toString() : "Association failed");
                }
            };
            cdm.associate(request, cdmCallback, handler);
        }
    }
}
