package com.example.media_player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BluetoothCodecActivity extends AppCompatActivity
        implements BluetoothCodecManager.BluetoothCodecListener {

    private BluetoothCodecSettings codecSettings;
    private BluetoothCodecManager codecManager;
    private BluetoothAdapter bluetoothAdapter;
    private LinearLayout pairedDevicesList;
    private LinearLayout permissionBanner;
    private TextView tvActiveDeviceName;
    private TextView tvActiveDeviceCodec;

    private boolean permissionGranted;
    private boolean proxyReady;

    // Pending CDM association callback (for pre-API 33 flow)
    private BluetoothCodecManager.AssociationCallback pendingCdmCallback;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    permissionGranted = true;
                    loadDevicesIfReady();
                } else {
                    Toast.makeText(this, R.string.bt_codec_permission_required,
                            Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_codec);
        getWindow().setNavigationBarColor(getColor(R.color.bg_primary));

        Toolbar toolbar = findViewById(R.id.bt_codec_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.bt_codec_title);
        }

        codecSettings = new BluetoothCodecSettings(this);
        codecManager = new BluetoothCodecManager(this);
        codecManager.setListener(this);
        codecManager.setOnProxyReady(() -> {
            proxyReady = true;
            loadDevicesIfReady();
        });
        codecManager.register();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        pairedDevicesList = findViewById(R.id.paired_devices_list);
        permissionBanner = findViewById(R.id.permission_banner);
        tvActiveDeviceName = findViewById(R.id.tv_active_device_name);
        tvActiveDeviceCodec = findViewById(R.id.tv_active_device_codec);

        setupPermissionBanner();
        requestBluetoothPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        codecManager.unregister();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupPermissionBanner() {
        TextView tvTitle = findViewById(R.id.tv_banner_title);
        TextView tvBody = findViewById(R.id.tv_banner_body);
        View tvCommand = findViewById(R.id.tv_banner_command);
        TextView tvCopy = findViewById(R.id.tv_banner_copy);

        if (BluetoothCodecManager.isCdmAvailable(this)) {
            // CDM is the primary path. Check if the active device already has an association.
            boolean activeAssociated = isActiveDeviceAssociated();
            if (activeAssociated) {
                // Association exists -- show "enabled"
                permissionBanner.setVisibility(View.VISIBLE);
                tvTitle.setText(R.string.bt_codec_method_ready);
                tvTitle.setTextColor(getColor(R.color.green_bright));
                tvBody.setVisibility(View.GONE);
                tvCommand.setVisibility(View.GONE);
                tvCopy.setVisibility(View.GONE);
            } else {
                // CDM available but no association yet -- instruct user
                permissionBanner.setVisibility(View.VISIBLE);
                tvTitle.setText(R.string.bt_codec_cdm_title);
                tvTitle.setTextColor(getColor(R.color.text_primary));
                tvBody.setText(R.string.bt_codec_cdm_body);
                tvBody.setVisibility(View.VISIBLE);
                tvCommand.setVisibility(View.GONE);
                tvCopy.setVisibility(View.GONE);
            }
        } else if (BluetoothCodecManager.hasWriteSecureSettings(this)) {
            // ADB permission granted, no CDM
            permissionBanner.setVisibility(View.VISIBLE);
            tvTitle.setText(R.string.bt_codec_method_ready);
            tvTitle.setTextColor(getColor(R.color.green_bright));
            tvBody.setVisibility(View.GONE);
            tvCommand.setVisibility(View.GONE);
            tvCopy.setVisibility(View.GONE);
        } else {
            // No CDM, no ADB -- show ADB instructions as fallback
            permissionBanner.setVisibility(View.VISIBLE);
            tvCopy.setOnClickListener(v -> {
                String command = getString(R.string.bt_codec_adb_command);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command));
                Toast.makeText(this, R.string.bt_codec_copied, Toast.LENGTH_SHORT).show();
            });
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isActiveDeviceAssociated() {
        BluetoothA2dp proxy = codecManager.getA2dpProxy();
        if (proxy == null) return false;
        List<BluetoothDevice> connected = proxy.getConnectedDevices();
        if (connected == null || connected.isEmpty()) return false;
        return codecManager.hasAssociation(this, connected.get(0).getAddress());
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
        }
        permissionGranted = true;
        loadDevicesIfReady();
    }

    private void loadDevicesIfReady() {
        if (permissionGranted && proxyReady) {
            loadDevices();
        }
    }

    @SuppressLint("MissingPermission")
    private void loadDevices() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bt_codec_no_bluetooth, Toast.LENGTH_SHORT).show();
            return;
        }

        updateActiveDevice();

        pairedDevicesList.removeAllViews();
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded == null) return;

        for (BluetoothDevice device : bonded) {
            addDeviceRow(device);
        }
    }

    @SuppressLint("MissingPermission")
    private void updateActiveDevice() {
        BluetoothA2dp proxy = codecManager.getA2dpProxy();
        if (proxy == null) {
            tvActiveDeviceName.setText(R.string.bt_codec_no_active);
            tvActiveDeviceCodec.setVisibility(View.GONE);
            return;
        }

        List<BluetoothDevice> connected = proxy.getConnectedDevices();
        if (connected == null || connected.isEmpty()) {
            tvActiveDeviceName.setText(R.string.bt_codec_no_active);
            tvActiveDeviceCodec.setVisibility(View.GONE);
            return;
        }

        BluetoothDevice active = connected.get(0);
        tvActiveDeviceName.setText(active.getName() != null ? active.getName() : active.getAddress());

        // Try getting codec status via reflection first
        BluetoothCodecManager.CodecStatusResult result =
                codecManager.invokeGetCodecStatusResult(active);

        if (result.securityException) {
            // CDM association needed for this device
            tvActiveDeviceCodec.setText(R.string.bt_codec_needs_association);
            tvActiveDeviceCodec.setVisibility(View.VISIBLE);
            return;
        }

        if (result.status != null) {
            BluetoothCodecConfig current = result.status.getCodecConfig();
            if (current != null) {
                String codecInfo = describeCodecConfig(current);
                tvActiveDeviceCodec.setText(getString(R.string.bt_codec_current, codecInfo));
                tvActiveDeviceCodec.setVisibility(View.VISIBLE);
                return;
            }
        }

        // Fallback: try reading from Settings.Global
        BluetoothDeviceCodecConfig fromSettings = codecManager.readCodecFromSettings();
        if (fromSettings != null) {
            tvActiveDeviceCodec.setText(getString(R.string.bt_codec_current,
                    fromSettings.getSummary()));
            tvActiveDeviceCodec.setVisibility(View.VISIBLE);
        } else {
            tvActiveDeviceCodec.setText(R.string.bt_codec_current_unknown);
            tvActiveDeviceCodec.setVisibility(View.VISIBLE);
        }
    }

    @SuppressLint("MissingPermission")
    private void addDeviceRow(BluetoothDevice device) {
        String mac = device.getAddress();
        String name = device.getName() != null ? device.getName() : mac;
        BluetoothDeviceCodecConfig saved = codecSettings.getDeviceConfig(mac);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        row.setBackgroundColor(getColor(R.color.bg_surface));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dpToPx(2);
        row.setLayoutParams(rowParams);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(getColor(R.color.text_primary));
        tvName.setTextSize(14);
        row.addView(tvName);

        TextView tvConfig = new TextView(this);
        if (saved != null) {
            tvConfig.setText(saved.getSummary());
            tvConfig.setTextColor(getColor(R.color.green_bright));
        } else {
            tvConfig.setText(R.string.bt_codec_no_config);
            tvConfig.setTextColor(getColor(R.color.text_secondary));
        }
        tvConfig.setTextSize(12);
        LinearLayout.LayoutParams configParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        configParams.topMargin = dpToPx(2);
        tvConfig.setLayoutParams(configParams);
        row.addView(tvConfig);

        row.setOnClickListener(v -> showConfigDialog(device));
        row.setClickable(true);
        row.setFocusable(true);

        pairedDevicesList.addView(row);
    }

    @SuppressLint("MissingPermission")
    private void showConfigDialog(BluetoothDevice device) {
        String mac = device.getAddress();
        String name = device.getName() != null ? device.getName() : mac;
        BluetoothDeviceCodecConfig existing = codecSettings.getDeviceConfig(mac);
        BluetoothDeviceCodecConfig config = existing != null ? existing : BluetoothDeviceCodecConfig.defaults();
        config.deviceName = name;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_codec_config, null);

        TextView tvDeviceName = dialogView.findViewById(R.id.dialog_device_name);
        tvDeviceName.setText(name);

        Spinner spinnerCodec = dialogView.findViewById(R.id.spinner_codec);
        Spinner spinnerSampleRate = dialogView.findViewById(R.id.spinner_sample_rate);
        Spinner spinnerBitDepth = dialogView.findViewById(R.id.spinner_bit_depth);
        Spinner spinnerLdacQuality = dialogView.findViewById(R.id.spinner_ldac_quality);
        TextView labelLdacQuality = dialogView.findViewById(R.id.label_ldac_quality);

        // Codec spinner
        String[] codecNames = {"SBC", "AAC", "aptX", "aptX HD", "LDAC"};
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, codecNames);
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCodec.setAdapter(codecAdapter);

        // LDAC quality spinner
        String[] ldacQualities = {"990 kbps (Best)", "660 kbps (Standard)", "330 kbps (Mobile)", "Adaptive"};
        ArrayAdapter<String> ldacAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ldacQualities);
        ldacAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLdacQuality.setAdapter(ldacAdapter);

        // Update sample rate and bit depth spinners based on codec selection
        spinnerCodec.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRateAndBitSpinners(position, spinnerSampleRate, spinnerBitDepth);
                boolean isLdac = (position == BluetoothDeviceCodecConfig.CODEC_LDAC);
                labelLdacQuality.setVisibility(isLdac ? View.VISIBLE : View.GONE);
                spinnerLdacQuality.setVisibility(isLdac ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Set initial values
        spinnerCodec.setSelection(config.codecType);
        spinnerLdacQuality.setSelection((int) config.codecSpecific1);

        // Defer setting rate/bit selections until spinners are populated
        spinnerCodec.post(() -> {
            selectSampleRate(spinnerSampleRate, config.sampleRate);
            selectBitDepth(spinnerBitDepth, config.bitsPerSample);
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Media_player_Dialog)
                .setView(dialogView)
                .create();

        // Save button
        dialogView.findViewById(R.id.btn_save).setOnClickListener(v -> {
            BluetoothDeviceCodecConfig newConfig = buildConfigFromDialog(
                    spinnerCodec, spinnerSampleRate, spinnerBitDepth, spinnerLdacQuality);
            newConfig.deviceName = name;
            codecSettings.saveDeviceConfig(mac, newConfig);
            Toast.makeText(this, R.string.bt_codec_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            loadDevices();
        });

        // Apply Now button -- triggers CDM association if needed before applying
        dialogView.findViewById(R.id.btn_apply_now).setOnClickListener(v -> {
            BluetoothDeviceCodecConfig newConfig = buildConfigFromDialog(
                    spinnerCodec, spinnerSampleRate, spinnerBitDepth, spinnerLdacQuality);
            newConfig.deviceName = name;
            codecSettings.saveDeviceConfig(mac, newConfig);
            dialog.dismiss();
            applyWithAssociationIfNeeded(device, newConfig);
        });

        // Remove button
        dialogView.findViewById(R.id.btn_remove).setOnClickListener(v -> {
            codecSettings.removeDeviceConfig(mac);
            Toast.makeText(this, R.string.bt_codec_removed, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            loadDevices();
        });

        dialog.show();
    }

    private void updateRateAndBitSpinners(int codecType, Spinner rateSpinner, Spinner bitSpinner) {
        List<String> rates = new ArrayList<>();
        List<Integer> rateMasks = new ArrayList<>();
        List<String> bits = new ArrayList<>();
        List<Integer> bitMasks = new ArrayList<>();

        switch (codecType) {
            case BluetoothDeviceCodecConfig.CODEC_SBC:
                rates.add("44.1 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
                rates.add("48 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_48000);
                bits.add("16-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_16);
                break;
            case BluetoothDeviceCodecConfig.CODEC_AAC:
                rates.add("44.1 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
                rates.add("48 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_48000);
                bits.add("16-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_16);
                bits.add("24-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_24);
                break;
            case BluetoothDeviceCodecConfig.CODEC_APTX:
                rates.add("44.1 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
                rates.add("48 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_48000);
                bits.add("16-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_16);
                bits.add("24-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_24);
                break;
            case BluetoothDeviceCodecConfig.CODEC_APTX_HD:
                rates.add("44.1 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
                rates.add("48 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_48000);
                bits.add("24-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_24);
                break;
            case BluetoothDeviceCodecConfig.CODEC_LDAC:
                rates.add("44.1 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_44100);
                rates.add("48 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_48000);
                rates.add("88.2 kHz"); rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_88200);
                rates.add("96 kHz");   rateMasks.add(BluetoothDeviceCodecConfig.SAMPLE_RATE_96000);
                bits.add("16-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_16);
                bits.add("24-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_24);
                bits.add("32-bit");    bitMasks.add(BluetoothDeviceCodecConfig.BITS_32);
                break;
        }

        ArrayAdapter<String> rateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rates);
        rateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rateSpinner.setAdapter(rateAdapter);
        rateSpinner.setTag(rateMasks);

        ArrayAdapter<String> bitAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, bits);
        bitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitSpinner.setAdapter(bitAdapter);
        bitSpinner.setTag(bitMasks);
    }

    @SuppressWarnings("unchecked")
    private void selectSampleRate(Spinner spinner, int mask) {
        List<Integer> masks = (List<Integer>) spinner.getTag();
        if (masks == null) return;
        for (int i = 0; i < masks.size(); i++) {
            if (masks.get(i) == mask) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void selectBitDepth(Spinner spinner, int mask) {
        List<Integer> masks = (List<Integer>) spinner.getTag();
        if (masks == null) return;
        for (int i = 0; i < masks.size(); i++) {
            if (masks.get(i) == mask) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private BluetoothDeviceCodecConfig buildConfigFromDialog(
            Spinner codecSpinner, Spinner rateSpinner, Spinner bitSpinner,
            Spinner ldacSpinner) {
        BluetoothDeviceCodecConfig config = new BluetoothDeviceCodecConfig();
        config.codecType = codecSpinner.getSelectedItemPosition();
        config.channelMode = BluetoothDeviceCodecConfig.CHANNEL_STEREO;

        List<Integer> rateMasks = (List<Integer>) rateSpinner.getTag();
        if (rateMasks != null && rateSpinner.getSelectedItemPosition() < rateMasks.size()) {
            config.sampleRate = rateMasks.get(rateSpinner.getSelectedItemPosition());
        } else {
            config.sampleRate = BluetoothDeviceCodecConfig.SAMPLE_RATE_44100;
        }

        List<Integer> bitMasks = (List<Integer>) bitSpinner.getTag();
        if (bitMasks != null && bitSpinner.getSelectedItemPosition() < bitMasks.size()) {
            config.bitsPerSample = bitMasks.get(bitSpinner.getSelectedItemPosition());
        } else {
            config.bitsPerSample = BluetoothDeviceCodecConfig.BITS_16;
        }

        if (config.codecType == BluetoothDeviceCodecConfig.CODEC_LDAC) {
            config.codecSpecific1 = ldacSpinner.getSelectedItemPosition();
        } else {
            config.codecSpecific1 = 0;
        }

        return config;
    }

    private String describeCodecConfig(BluetoothCodecConfig config) {
        String codec;
        switch (config.getCodecType()) {
            case 0: codec = "SBC"; break;
            case 1: codec = "AAC"; break;
            case 2: codec = "aptX"; break;
            case 3: codec = "aptX HD"; break;
            case 4: codec = "LDAC"; break;
            default: codec = "Unknown(" + config.getCodecType() + ")"; break;
        }

        String rate;
        switch (config.getSampleRate()) {
            case 0x1: rate = "44.1kHz"; break;
            case 0x2: rate = "48kHz"; break;
            case 0x4: rate = "88.2kHz"; break;
            case 0x8: rate = "96kHz"; break;
            default: rate = "?kHz"; break;
        }

        String bits;
        switch (config.getBitsPerSample()) {
            case 0x1: bits = "16-bit"; break;
            case 0x2: bits = "24-bit"; break;
            case 0x4: bits = "32-bit"; break;
            default: bits = "?-bit"; break;
        }

        return codec + " / " + rate + " / " + bits;
    }

    @SuppressLint("MissingPermission")
    private void applyWithAssociationIfNeeded(BluetoothDevice device,
                                               BluetoothDeviceCodecConfig config) {
        String mac = device.getAddress();

        // Test if codec APIs actually work for this device by probing getCodecStatus.
        // On Android 16, even WRITE_SECURE_SETTINGS is not enough -- CDM is required.
        boolean needsCdm = false;
        BluetoothCodecManager.CodecStatusResult probe =
                codecManager.invokeGetCodecStatusResult(device);
        if (probe.securityException) {
            needsCdm = true;
        }

        if (!needsCdm) {
            // Codec APIs work (either WRITE_SECURE_SETTINGS or existing CDM association)
            codecManager.applyConfig(device, config, true);
            return;
        }

        // CDM association required
        if (!BluetoothCodecManager.isCdmAvailable(this)) {
            // No CDM support -- try applying anyway (will likely fail)
            codecManager.applyConfig(device, config, true);
            return;
        }

        Toast.makeText(this, R.string.bt_codec_associating, Toast.LENGTH_SHORT).show();

        BluetoothCodecManager.AssociationCallback callback =
                new BluetoothCodecManager.AssociationCallback() {
            @Override
            public void onAssociated() {
                codecManager.applyConfig(device, config, true);
                setupPermissionBanner();
                loadDevices();
            }

            @Override
            public void onFailed(String reason) {
                Toast.makeText(BluetoothCodecActivity.this,
                        getString(R.string.bt_codec_association_failed, reason),
                        Toast.LENGTH_LONG).show();
            }
        };

        // Store for pre-API 33 onActivityResult path
        pendingCdmCallback = callback;
        codecManager.requestAssociation(this, device, callback);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BluetoothCodecManager.CDM_ASSOCIATE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                if (pendingCdmCallback != null) {
                    pendingCdmCallback.onAssociated();
                    pendingCdmCallback = null;
                }
            } else {
                if (pendingCdmCallback != null) {
                    pendingCdmCallback.onFailed("User denied association");
                    pendingCdmCallback = null;
                }
            }
        }
    }

    @Override
    public void onCodecConfigApplied(BluetoothDevice device) {
        Toast.makeText(this, R.string.bt_codec_applied, Toast.LENGTH_SHORT).show();
        loadDevices();
    }

    @Override
    public void onCodecConfigFailed(BluetoothDevice device, String reason) {
        Toast.makeText(this, getString(R.string.bt_codec_apply_failed, reason),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCodecConfigAppliedUnverified(BluetoothDevice device) {
        Toast.makeText(this, R.string.bt_codec_applied_unverified, Toast.LENGTH_SHORT).show();
        loadDevices();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
