package com.rtmp.drone.ui;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.*;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rtmp.drone.R;
import com.rtmp.drone.database.AppDatabase;
import com.rtmp.drone.model.*;
import com.rtmp.drone.service.LocalRecorder;
import com.rtmp.drone.service.StreamService;
import com.rtmp.drone.utils.*;
import java.util.*;

public class MainActivity extends AppCompatActivity
        implements ChannelAdapter.ChannelListener, StreamService.StreamSignalListener {

    private static final int REQUEST_CHANNEL_CONFIG = 100;
    private static final int REQUEST_SETTINGS = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int MAX_ACTIVE_CHANNELS = 3;
    private static final int NO_CHANNEL_ID = -1;

    private TextView tvStatus, tvActiveChannels, tvDroneUrl, tvDroneUrlLabel, tvGlobalStats;
    private TextView tvPreviewPlaceholder, tvRecordingIndicator, tvBatteryHint, tvEmptyChannels;
    private TextView labelPreview;
    private Button btnStart, btnStop, btnAddChannel;
    private SwitchMaterial switchPreview, switchRecording, switchBattery;
    private RecyclerView recyclerChannels;
    private SurfaceView surfacePreview;
    private View cardPreview;
    private ImageButton btnFullscreen, btnSettings;

    private AppDatabase db;
    private PreferenceManager preferences;
    private ChannelAdapter adapter;
    private final List<StreamChannel> channels = new ArrayList<>();
    private final GlobalStats globalStats = new GlobalStats();

    private StreamService streamService;
    private boolean serviceBound = false;
    private boolean applyDefaultsOnResume = true;
    private boolean autoStartAttempted = false;
    /** Channel currently edited, so its changes can be pushed to a running session. */
    private int editedChannelId = NO_CHANNEL_ID;
    private boolean updatingSwitches = false;
    private boolean batterySaverActive = false;
    private boolean droneSignalActive = false;
    /** Value of the preview switch before Battery Saver forced it off. */
    private boolean previewSwitchBeforePause = true;

    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private Runnable statsRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            StreamService.LocalBinder binder = (StreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;
            streamService.addStreamSignalListener(MainActivity.this);
            attachPreviewSurface();
            syncUiWithService();
            maybeAutoStartServer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            streamService = null;
            stopStatsUpdate();
            adapter.clearStats();
            updateUIState(false, false);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new PreferenceManager(this);
        db = AppDatabase.getInstance(this);

        setContentView(R.layout.activity_main);
        initViews();
        setupRecyclerView();
        loadChannels();
        applyPreferences();
        applyBatterySaverUi();

        requestNotificationPermissionIfNeeded();
        bindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPreferences();
        if (applyDefaultsOnResume) {
            applyDefaultsToSwitches();
            applyDefaultsOnResume = false;
        }
        loadChannels();
        attachPreviewSurface();
        syncUiWithService();
        maybeAutoStartServer();
    }

    /** Starts the RTMP server automatically when the option is enabled in the Configuration. */
    private void maybeAutoStartServer() {
        if (autoStartAttempted) return;
        if (!preferences.isAutoStartServerEnabled()) return;
        if (!serviceBound || streamService == null || streamService.isStreaming()) return;
        if (applyDefaultsOnResume) {
            applyDefaultsToSwitches();
            applyDefaultsOnResume = false;
        }
        autoStartAttempted = true;
        startStreaming();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatsUpdate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statsHandler.removeCallbacksAndMessages(null);
        if (serviceBound && streamService != null) {
            streamService.removeStreamSignalListener(this);
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            serviceBound = false;
        }
    }

    // ----------------------------------------------------------------------- views

    private void initViews() {
        tvStatus = findViewById(R.id.textViewStatus);
        tvActiveChannels = findViewById(R.id.textViewActiveChannels);
        tvDroneUrl = findViewById(R.id.textViewDroneUrl);
        tvDroneUrlLabel = findViewById(R.id.textViewDroneUrlLabel);
        tvGlobalStats = findViewById(R.id.textViewGlobalStats);
        tvPreviewPlaceholder = findViewById(R.id.tvPreviewPlaceholder);
        tvRecordingIndicator = findViewById(R.id.tvRecordingIndicator);
        tvBatteryHint = findViewById(R.id.textViewBatteryHint);
        tvEmptyChannels = findViewById(R.id.textViewEmptyChannels);
        labelPreview = findViewById(R.id.labelPreview);
        cardPreview = findViewById(R.id.cardPreview);
        surfacePreview = findViewById(R.id.surfacePreview);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnSettings = findViewById(R.id.buttonSettings);

        btnStart = findViewById(R.id.buttonStart);
        btnStop = findViewById(R.id.buttonStop);
        btnAddChannel = findViewById(R.id.buttonAddChannel);

        switchPreview = findViewById(R.id.switchPreview);
        switchRecording = findViewById(R.id.switchLocalRecording);
        switchBattery = findViewById(R.id.switchBatterySaver);
        recyclerChannels = findViewById(R.id.recyclerViewChannels);

        styleSwitch(switchPreview, Color.parseColor("#6C5CE7"), Color.parseColor("#FFFFFF"));
        styleSwitch(switchRecording, Color.parseColor("#FF3B5C"), Color.parseColor("#FFFFFF"));
        styleSwitch(switchBattery, Color.parseColor("#00F593"), Color.parseColor("#FFFFFF"));

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnAddChannel.setOnClickListener(v -> addChannel());
        btnSettings.setOnClickListener(v -> startActivityForResult(
                new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));
        btnFullscreen.setOnClickListener(v -> openFullscreen());
        surfacePreview.setOnClickListener(v -> openFullscreen());

        switchPreview.setOnCheckedChangeListener((button, isChecked) -> {
            if (updatingSwitches) return;
            cardPreview.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                tvPreviewPlaceholder.setText(R.string.no_signal);
                tvPreviewPlaceholder.setVisibility(droneSignalActive ? View.GONE : View.VISIBLE);
            }
            if (serviceBound && streamService != null && streamService.isStreaming()) {
                streamService.setPreviewEnabled(isChecked);
            }
        });

        switchRecording.setOnCheckedChangeListener((button, isChecked) -> {
            if (updatingSwitches) return;
            if (!isChecked) tvRecordingIndicator.setVisibility(View.GONE);
            if (serviceBound && streamService != null) {
                streamService.setRecordingEnabled(isChecked);
            }
        });

        switchBattery.setOnCheckedChangeListener((button, isChecked) -> {
            if (updatingSwitches) return;
            batterySaverActive = isChecked;
            applyBatterySaverUi();
            if (serviceBound && streamService != null) {
                streamService.setBatterySaver(isChecked);
            }
        });

        surfacePreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                attachPreviewSurface();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (serviceBound && streamService != null) {
                    streamService.setPreviewSurface(null);
                }
            }
        });

        updateUIState(false, false);
    }

    private void setupRecyclerView() {
        adapter = new ChannelAdapter(channels, this);
        recyclerChannels.setLayoutManager(new LinearLayoutManager(this));
        recyclerChannels.setAdapter(adapter);
    }

    private void styleSwitch(SwitchMaterial sw, int colorOn, int colorOff) {
        if (sw == null) return;
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] thumbColors = new int[] { colorOn, colorOff };
        int[] trackColors = new int[] { adjustAlpha(colorOn, 0.3f), Color.parseColor("#3D3D48") };

        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ------------------------------------------------------------------ settings

    private void applyPreferences() {
        int port = preferences.getDronePort();
        tvDroneUrlLabel.setText(getString(R.string.drone_url_label, port));
        tvDroneUrl.setText("rtmp://" + NetworkUtils.getLocalIpAddress(this) + ":" + port + "/" + preferences.getStreamPath());
        applyKeepScreenOn();
    }

    private void applyKeepScreenOn() {
        if (preferences.isKeepScreenOnEnabled()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyDefaultsToSwitches() {
        if (isStreaming()) return;
        setSwitchChecked(switchPreview, preferences.isDefaultPreviewEnabled());
        setSwitchChecked(switchRecording, preferences.isDefaultRecordingEnabled());
        setSwitchChecked(switchBattery, preferences.isDefaultBatterySaverEnabled());
        cardPreview.setVisibility(switchPreview.isChecked() ? View.VISIBLE : View.GONE);
        batterySaverActive = switchBattery.isChecked();
        applyBatterySaverUi();
    }

    private void setSwitchChecked(SwitchMaterial sw, boolean checked) {
        updatingSwitches = true;
        sw.setChecked(checked);
        updatingSwitches = false;
    }

    private void applyBatterySaverUi() {
        boolean paused = batterySaverActive && preferences.isBatterySaverPausesPreview();

        if (paused && switchPreview.isEnabled()) {
            // Remember the user choice so it can be restored when Battery Saver is turned off.
            previewSwitchBeforePause = switchPreview.isChecked();
        }

        tvBatteryHint.setVisibility(batterySaverActive ? View.VISIBLE : View.GONE);
        labelPreview.setAlpha(paused ? 0.5f : 1.0f);
        switchPreview.setEnabled(!paused);

        if (paused) {
            setSwitchChecked(switchPreview, false);
            cardPreview.setVisibility(View.GONE);
            tvPreviewPlaceholder.setText(R.string.preview_battery_saver);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
        } else {
            boolean previewOn = isStreaming() ? streamService.isPreviewEnabled() : previewSwitchBeforePause;
            setSwitchChecked(switchPreview, previewOn);
            tvPreviewPlaceholder.setText(R.string.no_signal);
            cardPreview.setVisibility(previewOn ? View.VISIBLE : View.GONE);
            tvPreviewPlaceholder.setVisibility(droneSignalActive && previewOn ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ service

    private void bindService() {
        Intent intent = new Intent(this, StreamService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void attachPreviewSurface() {
        if (serviceBound && streamService != null && surfacePreview.getHolder().getSurface().isValid()) {
            streamService.setPreviewSurface(surfacePreview.getHolder().getSurface());
        }
    }

    private boolean isStreaming() {
        return serviceBound && streamService != null && streamService.isStreaming();
    }

    /** Aligns the screen with the state of the (possibly already running) service. */
    private void syncUiWithService() {
        if (!serviceBound || streamService == null) {
            updateUIState(false, false);
            return;
        }
        boolean streaming = streamService.isStreaming();
        droneSignalActive = streamService.isDroneSignalActive();
        updateUIState(streaming, droneSignalActive);

        if (streaming) {
            updatingSwitches = true;
            switchPreview.setChecked(streamService.isPreviewEnabled());
            switchRecording.setChecked(streamService.isRecordingEnabled());
            switchBattery.setChecked(streamService.isBatterySaverEnabled());
            updatingSwitches = false;
            batterySaverActive = streamService.isBatterySaverEnabled();
            applyBatterySaverUi();
            startStatsUpdate();
            updateStats();
        } else {
            stopStatsUpdate();
            tvRecordingIndicator.setVisibility(View.GONE);
        }
    }

    // --------------------------------------------------------------- destinations

    private void loadChannels() {
        List<StreamChannel> stored = db.channelDao().getAllChannels();
        channels.clear();
        if (stored != null) channels.addAll(stored);

        if (adapter != null) adapter.updateChannels(channels);
        if (tvEmptyChannels != null) {
            tvEmptyChannels.setVisibility(channels.isEmpty() ? View.VISIBLE : View.GONE);
        }
        updateActiveChannelsCount();
    }

    private void addChannel() {
        editedChannelId = NO_CHANNEL_ID;
        startActivityForResult(new Intent(this, ChannelConfigActivity.class), REQUEST_CHANNEL_CONFIG);
    }

    @Override
    public void onEdit(StreamChannel channel) {
        editedChannelId = channel.id;
        Intent intent = new Intent(this, ChannelConfigActivity.class);
        intent.putExtra(ChannelConfigActivity.EXTRA_CHANNEL_ID, channel.id);
        startActivityForResult(intent, REQUEST_CHANNEL_CONFIG);
    }

    @Override
    public void onDelete(StreamChannel channel) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, channel.name))
            .setPositiveButton(R.string.yes, (dialog, which) -> {
                if (isStreaming()) streamService.removeChannel(channel);
                db.channelDao().deleteChannel(channel);
                loadChannels();
            })
            .setNegativeButton(R.string.no, null)
            .show();
    }

    @Override
    public void onDuplicate(StreamChannel channel) {
        StreamChannel copy = channel.copy();
        db.channelDao().insertChannel(copy);
        loadChannels();
    }

    @Override
    public void onToggleActive(StreamChannel channel, boolean active) {
        if (active) {
            int activeCount = 0;
            for (StreamChannel ch : channels) {
                if (ch.isActive) activeCount++;
            }
            if (activeCount >= MAX_ACTIVE_CHANNELS) {
                Toast.makeText(this, R.string.max_active_channels, Toast.LENGTH_SHORT).show();
                loadChannels();
                return;
            }
        }

        channel.isActive = active;
        db.channelDao().updateChannel(channel);

        if (isStreaming()) {
            if (active) streamService.addChannel(channel);
            else streamService.removeChannel(channel);
        }

        loadChannels();
        adapter.setChannelActiveState(channel.id, active);
    }

    private void updateActiveChannelsCount() {
        int active = 0;
        for (StreamChannel ch : channels) {
            if (ch.isActive) active++;
        }
        tvActiveChannels.setText(getString(R.string.active_count, active, MAX_ACTIVE_CHANNELS));
    }

    // ------------------------------------------------------------------ streaming

    private void startStreaming() {
        if (!serviceBound || streamService == null) {
            Toast.makeText(this, R.string.toast_service_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        List<StreamChannel> activeChannels = new ArrayList<>();
        for (StreamChannel ch : channels) {
            if (ch.isActive) activeChannels.add(ch);
        }
        if (activeChannels.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_active_channel, Toast.LENGTH_LONG).show();
        }

        batterySaverActive = switchBattery.isChecked();
        streamService.startStreaming(preferences.getDronePort(), activeChannels,
                switchRecording.isChecked(), switchPreview.isChecked(), batterySaverActive);

        updateUIState(true, false);
        startStatsUpdate();
    }

    private void stopStreaming() {
        if (serviceBound && streamService != null) {
            streamService.stopStreaming();
        }
        droneSignalActive = false;
        updateUIState(false, false);
        stopStatsUpdate();
        tvRecordingIndicator.setVisibility(View.GONE);
        tvPreviewPlaceholder.setText(R.string.no_signal);
        tvPreviewPlaceholder.setVisibility(switchPreview.isChecked() ? View.VISIBLE : View.GONE);
        adapter.clearStats();
        loadChannels();
    }

    private void openFullscreen() {
        if (batterySaverActive && preferences.isBatterySaverPausesPreview()) {
            Toast.makeText(this, R.string.toast_preview_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!switchPreview.isChecked()) {
            setSwitchChecked(switchPreview, true);
            cardPreview.setVisibility(View.VISIBLE);
            if (serviceBound && streamService != null && streamService.isStreaming()) {
                streamService.setPreviewEnabled(true);
            }
        }
        startActivity(new Intent(this, FullscreenPreviewActivity.class));
    }

    private void updateUIState(boolean streaming, boolean droneLive) {
        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(20f);

        if (streaming) {
            btnStart.setEnabled(false);
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2D2D35")));
            btnStart.setTextColor(Color.parseColor("#A0A0A8"));

            btnStop.setEnabled(true);
            btnStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
            btnStop.setTextColor(Color.parseColor("#FFFFFF"));

            if (droneLive) {
                tvStatus.setText(R.string.status_live);
                tvStatus.setTextColor(Color.parseColor("#FFFFFF"));
                badge.setColor(Color.parseColor("#FF3B5C"));
            } else {
                tvStatus.setText(R.string.status_waiting);
                tvStatus.setTextColor(Color.parseColor("#0E0E10"));
                badge.setColor(Color.parseColor("#FFD166"));
            }
        } else {
            btnStart.setEnabled(true);
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
            btnStart.setTextColor(Color.parseColor("#FFFFFF"));

            btnStop.setEnabled(false);
            btnStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2D2D35")));
            btnStop.setTextColor(Color.parseColor("#A0A0A8"));

            tvStatus.setText(R.string.status_offline);
            tvStatus.setTextColor(Color.parseColor("#A0A0A8"));
            badge.setColor(Color.parseColor("#25252B"));
            badge.setStroke(2, Color.parseColor("#3D3D48"));
        }
        tvStatus.setBackground(badge);
        tvStatus.setPadding(24, 8, 24, 8);
    }

    // ---------------------------------------------------------------------- stats

    private void startStatsUpdate() {
        if (statsRunnable != null) return;
        statsRunnable = () -> {
            updateStats();
            long interval = batterySaverActive ? 3000L : 1000L;
            statsHandler.postDelayed(statsRunnable, interval);
        };
        statsHandler.post(statsRunnable);
    }

    private void stopStatsUpdate() {
        if (statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsRunnable = null;
        }
    }

    private void updateStats() {
        if (!serviceBound || streamService == null) return;

        Map<Integer, ChannelStats> snapshot = streamService.getStatsSnapshot();
        for (StreamChannel channel : channels) {
            ChannelStats stats = snapshot.get(channel.id);
            if (stats != null) {
                channel.isConnected = stats.connected;
                channel.currentBitrate = stats.bitrateKbps;
                channel.bitrate = stats.bitrateKbps;
                channel.latency = stats.latencyMs;
                channel.latencyMs = stats.latencyMs;
                channel.bytesSent = stats.bytesSent;
                channel.status = stats.error ? StreamChannel.Status.ERROR
                        : (stats.connected ? StreamChannel.Status.LIVE : StreamChannel.Status.CONNECTING);
            } else {
                channel.isConnected = false;
                channel.currentBitrate = 0;
                channel.bitrate = 0;
                channel.latency = 0;
                channel.bytesSent = 0;
                channel.status = StreamChannel.Status.OFFLINE;
            }
        }
        adapter.applyStats(snapshot);

        globalStats.updateFromChannels(channels);
        globalStats.droneConnected = streamService.isDroneSignalActive();
        globalStats.uptime = streamService.getSessionUptimeMs();
        tvGlobalStats.setText(globalStats.getFormattedStats());

        updateRecordingIndicator();
    }

    private void updateRecordingIndicator() {
        if (!serviceBound || streamService == null) {
            tvRecordingIndicator.setVisibility(View.GONE);
            return;
        }
        if (streamService.isCapturing()) {
            tvRecordingIndicator.setVisibility(View.VISIBLE);
            tvRecordingIndicator.setText(getString(R.string.recording_indicator,
                    formatDuration(streamService.getRecordingDurationMs())));
        } else if (streamService.isRecording()) {
            tvRecordingIndicator.setVisibility(View.VISIBLE);
            tvRecordingIndicator.setText(R.string.recording_indicator_armed);
        } else {
            tvRecordingIndicator.setVisibility(View.GONE);
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ------------------------------------------------------------ service signals

    @Override
    public void onDroneSignalReceived() {
        runOnUiThread(() -> {
            droneSignalActive = true;
            if (switchPreview.isChecked() && !(batterySaverActive && preferences.isBatterySaverPausesPreview())) {
                tvPreviewPlaceholder.setVisibility(View.GONE);
            }
            updateUIState(isStreaming(), true);
            updateStats();
        });
    }

    @Override
    public void onDroneSignalLost() {
        runOnUiThread(() -> {
            droneSignalActive = false;
            tvPreviewPlaceholder.setText(R.string.no_signal);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            tvRecordingIndicator.setVisibility(View.GONE);
            updateUIState(isStreaming(), false);
        });
    }

    @Override
    public void onRecordingStateChanged(boolean recording, String fileName, String location, long sizeBytes) {
        runOnUiThread(() -> {
            if (recording) {
                if (fileName != null) {
                    Toast.makeText(this, getString(R.string.recording_started, fileName), Toast.LENGTH_SHORT).show();
                }
            } else if (fileName != null) {
                Toast.makeText(this,
                        getString(R.string.recording_saved, fileName, LocalRecorder.formatSize(sizeBytes)),
                        Toast.LENGTH_LONG).show();
            }
            updateRecordingIndicator();
        });
    }

    @Override
    public void onRecordingError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.recording_failed, message), Toast.LENGTH_LONG).show();
            tvRecordingIndicator.setVisibility(View.GONE);
        });
    }

    @Override
    public void onStreamError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            // The service gave up (server could not start): do not stay in "WAITING FOR DRONE".
            droneSignalActive = false;
            updateUIState(false, false);
            stopStatsUpdate();
            adapter.clearStats();
            tvRecordingIndicator.setVisibility(View.GONE);
            tvPreviewPlaceholder.setText(R.string.no_signal);
            tvPreviewPlaceholder.setVisibility(switchPreview.isChecked() ? View.VISIBLE : View.GONE);
        });
    }

    // ------------------------------------------------------------------ results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            applyDefaultsOnResume = true;
        }
        if (requestCode == REQUEST_CHANNEL_CONFIG && resultCode == ChannelConfigActivity.RESULT_SAVED
                && editedChannelId != NO_CHANNEL_ID && isStreaming()) {
            // The destination was edited while the session is running: apply it right away.
            StreamChannel updated = db.channelDao().getChannelById(editedChannelId);
            if (updated != null) {
                streamService.refreshChannel(updated);
            }
        }
        editedChannelId = NO_CHANNEL_ID;
        loadChannels();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }
}
