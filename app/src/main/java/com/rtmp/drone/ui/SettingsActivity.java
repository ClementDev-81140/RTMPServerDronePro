package com.rtmp.drone.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rtmp.drone.R;
import com.rtmp.drone.service.LocalRecorder;
import com.rtmp.drone.utils.PreferenceManager;

/**
 * Configuration screen: RTMP server settings, streaming defaults,
 * recording storage and battery saver behaviour.
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText editPort, editPath;
    private SwitchMaterial switchAutoStart, switchKeepScreenOn;
    private SwitchMaterial switchDefaultPreview, switchDefaultRecording, switchDefaultBattery;
    private SwitchMaterial switchPublicRecordings, switchBatteryDisablesPreview, switchStrictTls;
    private TextView textViewPublicWarning, textViewRecordingsPath, textViewRecordingsStats, textViewVersion;
    private Button buttonRestoreDefaults, buttonCancel, buttonSave;

    private PreferenceManager preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        preferences = new PreferenceManager(this);
        initViews();
        loadPreferences();
    }

    private void initViews() {
        editPort = findViewById(R.id.editTextPort);
        editPath = findViewById(R.id.editTextPath);
        switchAutoStart = findViewById(R.id.switchAutoStart);
        switchKeepScreenOn = findViewById(R.id.switchKeepScreenOn);
        switchDefaultPreview = findViewById(R.id.switchDefaultPreview);
        switchDefaultRecording = findViewById(R.id.switchDefaultRecording);
        switchDefaultBattery = findViewById(R.id.switchDefaultBattery);
        switchPublicRecordings = findViewById(R.id.switchPublicRecordings);
        switchBatteryDisablesPreview = findViewById(R.id.switchBatteryDisablesPreview);
        switchStrictTls = findViewById(R.id.switchStrictTls);
        textViewPublicWarning = findViewById(R.id.textViewPublicWarning);
        textViewRecordingsPath = findViewById(R.id.textViewRecordingsPath);
        textViewRecordingsStats = findViewById(R.id.textViewRecordingsStats);
        textViewVersion = findViewById(R.id.textViewVersion);
        buttonRestoreDefaults = findViewById(R.id.buttonRestoreDefaults);
        buttonCancel = findViewById(R.id.buttonCancelSettings);
        buttonSave = findViewById(R.id.buttonSaveSettings);

        styleSwitch(switchAutoStart, "#6C5CE7");
        styleSwitch(switchKeepScreenOn, "#6C5CE7");
        styleSwitch(switchDefaultPreview, "#6C5CE7");
        styleSwitch(switchDefaultRecording, "#FF3B5C");
        styleSwitch(switchDefaultBattery, "#00F593");
        styleSwitch(switchPublicRecordings, "#00D4FF");
        styleSwitch(switchBatteryDisablesPreview, "#00F593");
        styleSwitch(switchStrictTls, "#6C5CE7");

        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        buttonCancel.setOnClickListener(v -> finish());
        buttonSave.setOnClickListener(v -> savePreferences());
        buttonRestoreDefaults.setOnClickListener(v -> {
            preferences.restoreDefaults();
            loadPreferences();
            Toast.makeText(this, R.string.settings_defaults_restored, Toast.LENGTH_SHORT).show();
        });

        switchPublicRecordings.setOnCheckedChangeListener((button, checked) ->
                textViewRecordingsPath.setText(LocalRecorder.getLocationLabel(this, checked)));

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        textViewVersion.setText(getString(R.string.settings_about_version, version));
    }

    private void styleSwitch(SwitchMaterial sw, String colorOn) {
        if (sw == null) return;
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int on = Color.parseColor(colorOn);
        int[] thumbColors = new int[] { on, Color.parseColor("#FFFFFF") };
        int[] trackColors = new int[] { adjustAlpha(on, 0.3f), Color.parseColor("#3D3D48") };
        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void loadPreferences() {
        editPort.setText(String.valueOf(preferences.getDronePort()));
        editPath.setText(preferences.getStreamPath());
        switchAutoStart.setChecked(preferences.isAutoStartServerEnabled());
        switchKeepScreenOn.setChecked(preferences.isKeepScreenOnEnabled());
        switchDefaultPreview.setChecked(preferences.isDefaultPreviewEnabled());
        switchDefaultRecording.setChecked(preferences.isDefaultRecordingEnabled());
        switchDefaultBattery.setChecked(preferences.isDefaultBatterySaverEnabled());
        switchBatteryDisablesPreview.setChecked(preferences.isBatterySaverPausesPreview());
        switchStrictTls.setChecked(preferences.isStrictTlsEnabled());

        boolean publicSupported = LocalRecorder.isPublicStorageSupported();
        switchPublicRecordings.setEnabled(publicSupported);
        switchPublicRecordings.setChecked(publicSupported && preferences.isPublicRecordingsEnabled());
        textViewPublicWarning.setVisibility(publicSupported ? android.view.View.GONE : android.view.View.VISIBLE);

        refreshRecordingsSummary();
    }

    private void refreshRecordingsSummary() {
        boolean usePublic = switchPublicRecordings.isChecked();
        textViewRecordingsPath.setText(LocalRecorder.getLocationLabel(this, usePublic));

        long[] summary = LocalRecorder.getRecordingsSummary(this, usePublic);
        textViewRecordingsStats.setText(getString(R.string.settings_recordings_stats,
                summary[0], LocalRecorder.formatSize(summary[1])));
    }

    private void savePreferences() {
        String portText = editPort.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (Exception e) {
            port = -1;
        }
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, R.string.settings_error_port, Toast.LENGTH_LONG).show();
            editPort.requestFocus();
            return;
        }

        String path = editPath.getText().toString().trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) {
            Toast.makeText(this, R.string.settings_error_path, Toast.LENGTH_LONG).show();
            editPath.requestFocus();
            return;
        }

        boolean serverSettingsChanged = (port != preferences.getDronePort())
                || !path.equals(preferences.getStreamPath());

        preferences.setDronePort(port);
        preferences.setStreamPath(path);
        preferences.setAutoStartServer(switchAutoStart.isChecked());
        preferences.setKeepScreenOn(switchKeepScreenOn.isChecked());
        preferences.setDefaultPreview(switchDefaultPreview.isChecked());
        preferences.setDefaultRecording(switchDefaultRecording.isChecked());
        preferences.setDefaultBatterySaver(switchDefaultBattery.isChecked());
        preferences.setPublicRecordings(switchPublicRecordings.isChecked());
        preferences.setBatterySaverPausesPreview(switchBatteryDisablesPreview.isChecked());
        preferences.setStrictTls(switchStrictTls.isChecked());

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        if (serverSettingsChanged) {
            // The server currently listening keeps its port until the next START SERVER.
            Toast.makeText(this, R.string.settings_restart_needed, Toast.LENGTH_LONG).show();
        }
        setResult(RESULT_OK);
        finish();
    }
}
