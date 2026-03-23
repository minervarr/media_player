package com.example.media_player;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    public static final int RESULT_FOLDERS_CHANGED = 100;

    private AppSettings settings;
    private LinearLayout folderListContainer;
    private TextView tvNoFolders;
    private Set<String> folders;
    private boolean foldersChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getWindow().setNavigationBarColor(getColor(R.color.bg_primary));

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }

        settings = new AppSettings(this);
        folders = new HashSet<>(settings.getMusicFolders());

        folderListContainer = findViewById(R.id.folder_list_container);
        tvNoFolders = findViewById(R.id.tv_no_folders);
        refreshFolderList();

        findViewById(R.id.btn_add_folder).setOnClickListener(v -> showAddFolderDialog());

        SwitchCompat switchContinuous = findViewById(R.id.switch_continuous_playback);
        switchContinuous.setChecked(settings.isContinuousPlayback());
        switchContinuous.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setContinuousPlayback(isChecked));

        SwitchCompat switchUsbExclusive = findViewById(R.id.switch_usb_exclusive);
        switchUsbExclusive.setChecked(settings.isUsbExclusiveMode());
        switchUsbExclusive.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setUsbExclusiveMode(isChecked));

        TextView tvTidalQuality = findViewById(R.id.tv_tidal_quality);
        tvTidalQuality.setText(settings.getTidalAudioQuality());
        tvTidalQuality.setOnClickListener(v -> {
            String[] options = {"SMART", "HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setting_tidal_quality)
                    .setItems(options, (dialog, which) -> {
                        settings.setTidalAudioQuality(options[which]);
                        tvTidalQuality.setText(options[which]);
                    })
                    .show();
        });

        LinearLayout rowBtCodec = findViewById(R.id.row_bt_codec);
        TextView tvBtCodecDesc = findViewById(R.id.tv_bt_codec_desc);
        if (!BluetoothCodecManager.isFeatureAvailable(this)) {
            tvBtCodecDesc.setText(R.string.setting_bt_codec_unavailable);
        }
        rowBtCodec.setOnClickListener(v ->
                startActivity(new Intent(this, BluetoothCodecActivity.class)));
    }

    private void refreshFolderList() {
        folderListContainer.removeAllViews();

        if (folders.isEmpty()) {
            tvNoFolders.setVisibility(View.VISIBLE);
            return;
        }

        tvNoFolders.setVisibility(View.GONE);

        for (String path : folders) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
            row.setPadding(0, pad, 0, pad);

            TextView tvPath = new TextView(this);
            tvPath.setText(path);
            tvPath.setTextColor(getColor(R.color.text_primary));
            tvPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvPath.setLayoutParams(pathParams);

            TextView btnRemove = new TextView(this);
            btnRemove.setText("X");
            btnRemove.setTextColor(getColor(R.color.text_secondary));
            btnRemove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            int btnPad = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
            btnRemove.setPadding(btnPad, 0, btnPad, 0);
            btnRemove.setOnClickListener(v -> {
                folders.remove(path);
                settings.setMusicFolders(folders);
                foldersChanged = true;
                refreshFolderList();
            });

            row.addView(tvPath);
            row.addView(btnRemove);
            folderListContainer.addView(row);
        }
    }

    private void showAddFolderDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.setting_add_folder_hint);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));

        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());

        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_add_folder)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) return;
                    File dir = new File(path);
                    if (!dir.isDirectory()) {
                        Toast.makeText(this, R.string.setting_folder_not_found,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    folders.add(path);
                    settings.setMusicFolders(folders);
                    foldersChanged = true;
                    refreshFolderList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void finish() {
        if (foldersChanged) {
            setResult(RESULT_FOLDERS_CHANGED);
        }
        super.finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
