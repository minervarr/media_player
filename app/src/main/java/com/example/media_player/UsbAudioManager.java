package com.example.media_player;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;

public class UsbAudioManager {

    private static final String TAG = "UsbAudioManager";
    private static final String ACTION_USB_PERMISSION =
            "com.example.media_player.USB_PERMISSION";

    public interface UsbAudioListener {
        void onUsbDacConnected(UsbDevice device);
        void onUsbDacDisconnected();
        void onUsbPermissionGranted(UsbDevice device);
        void onUsbPermissionDenied(UsbDevice device);
    }

    private final Context context;
    private final UsbManager usbManager;
    private final AudioManager audioManager;
    private UsbAudioListener listener;
    private UsbDevice connectedDac;
    private boolean registered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "onReceive: " + action);

            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Log.d(TAG, "USB_ATTACHED: " + (device != null ? device.getDeviceName()
                            + " vendor=" + device.getVendorId()
                            + " product=" + device.getProductId() : "null"));
                    if (device != null && isAudioDevice(device)) {
                        Log.d(TAG, "USB_ATTACHED: audio device confirmed, interfaces=" + device.getInterfaceCount());
                        connectedDac = device;
                        if (listener != null) {
                            listener.onUsbDacConnected(device);
                        }
                    } else if (device != null) {
                        Log.d(TAG, "USB_ATTACHED: not an audio device, ignoring");
                    }
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Log.d(TAG, "USB_DETACHED: " + (device != null ? device.getDeviceName() : "null")
                            + " connectedDac=" + (connectedDac != null ? connectedDac.getDeviceName() : "null"));
                    if (device != null && connectedDac != null
                            && device.getDeviceId() == connectedDac.getDeviceId()) {
                        Log.d(TAG, "USB_DETACHED: matched connected DAC, notifying listener");
                        connectedDac = null;
                        if (listener != null) {
                            listener.onUsbDacDisconnected();
                        }
                    }
                    break;
                }
                case ACTION_USB_PERMISSION: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB_PERMISSION: " + (device != null ? device.getDeviceName() : "null")
                            + " granted=" + granted);
                    if (device != null && listener != null) {
                        if (granted) {
                            listener.onUsbPermissionGranted(device);
                        } else {
                            listener.onUsbPermissionDenied(device);
                        }
                    }
                    break;
                }
            }
        }
    };

    public UsbAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(UsbAudioListener listener) {
        this.listener = listener;
    }

    public void register() {
        if (registered) return;
        Log.d(TAG, "register: registering USB broadcast receiver");
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        registered = true;

        // Check for already-connected USB audio devices
        scanExistingDevices();
    }

    public void unregister() {
        if (!registered) return;
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {}
        registered = false;
    }

    private void scanExistingDevices() {
        if (usbManager == null) {
            Log.w(TAG, "scanExistingDevices: usbManager is null");
            return;
        }
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        Log.d(TAG, "scanExistingDevices: found " + devices.size() + " USB device(s)");
        for (UsbDevice device : devices.values()) {
            Log.d(TAG, "  device: " + device.getDeviceName()
                    + " vendor=" + device.getVendorId()
                    + " product=" + device.getProductId()
                    + " isAudio=" + isAudioDevice(device));
            if (isAudioDevice(device)) {
                connectedDac = device;
                if (listener != null) {
                    listener.onUsbDacConnected(device);
                }
                return;
            }
        }
        Log.d(TAG, "scanExistingDevices: no audio device found");
    }

    public boolean isUsbDacConnected() {
        if (connectedDac != null) return true;
        // Also check AudioManager for USB audio route
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo info : devices) {
            if (info.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                    || info.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }

    public UsbDevice getConnectedDac() {
        return connectedDac;
    }

    public void requestPermission(UsbDevice device) {
        if (usbManager == null || device == null) return;
        Log.d(TAG, "requestPermission: " + device.getDeviceName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, pi);
    }

    public boolean hasPermission(UsbDevice device) {
        boolean has = usbManager != null && device != null && usbManager.hasPermission(device);
        Log.d(TAG, "hasPermission: " + (device != null ? device.getDeviceName() : "null") + " = " + has);
        return has;
    }

    public UsbManager getUsbManager() {
        return usbManager;
    }

    private static boolean isAudioDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                return true;
            }
        }
        return false;
    }
}
