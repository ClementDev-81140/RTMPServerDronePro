package com.rtmp.drone.ui;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.*;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rtmp.drone.R;
import com.rtmp.drone.database.AppDatabase;
import com.rtmp.drone.model.*;
import com.rtmp.drone.service.StreamService;
import com.rtmp.drone.utils.*;
import java.util.*;

public class MainActivity extends AppCompatActivity implements ChannelAdapter.ChannelListener, StreamService.StreamSignalListener {
    private static final int REQUEST_CHANNEL_CONFIG = 100;
    private static final int MAX_ACTIVE_CHANNELS = 3;
    private static final int DRONE_PORT = 1935;

    private TextView tvStatus, tvActiveChannels, tvDroneUrl, tvGlobalStats, tvPreviewPlaceholder;
    private Button btnStart, btnStop, btnAddChannel;
    private SwitchMaterial switchPreview, switchRecording, switchBattery;
    private RecyclerView recyclerChannels;
    private SurfaceView surfacePreview;
    private View cardPreview;
    private ImageButton btnFullscreen;

    private AppDatabase db;
    private ChannelAdapter adapter;
    private List<StreamChannel> channels = new ArrayList<>();
    private StreamService streamService;
    private boolean serviceBound = false;
    private boolean isFullscreen = false;

    private Handler statsHandler = new Handler(Looper.getMainLooper());
    private Runnable statsRunnable;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            StreamService.LocalBinder binder = (StreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;

            if (surfacePreview.getHolder().getSurface().isValid()) {
                streamService.setPreviewSurface(surfacePreview.getHolder().getSurface());
            }

            streamService.setStreamSignalListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);
        initViews();
        setupRecyclerView();
        loadChannels();
        updateDroneUrl();
        bindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadChannels();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.textViewStatus);
        tvActiveChannels = findViewById(R.id.textViewActiveChannels);
        tvDroneUrl = findViewById(R.id.textViewDroneUrl);
        tvGlobalStats = findViewById(R.id.textViewGlobalStats);
        tvPreviewPlaceholder = findViewById(R.id.tvPreviewPlaceholder);
        cardPreview = findViewById(R.id.cardPreview);
        surfacePreview = findViewById(R.id.surfacePreview);
        btnFullscreen = findViewById(R.id.btnFullscreen);

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

        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        surfacePreview.setOnClickListener(v -> {
            if (isFullscreen) {
                toggleFullscreen();
            }
        });

        switchPreview.setOnCheckedChangeListener((bv, isChecked) -> {
            cardPreview.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        surfacePreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (serviceBound && streamService != null) {
                    streamService.setPreviewSurface(holder.getSurface());
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (serviceBound && streamService != null) {
                    streamService.setPreviewSurface(null);
                }
            }
        });

        updateUIState(false);
    }

    // Callbacks réels du signal vidéo du drone
    @Override
    public void onDroneSignalReceived() {
        runOnUiThread(() -> {
            if (tvPreviewPlaceholder != null) {
                tvPreviewPlaceholder.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onDroneSignalLost() {
        runOnUiThread(() -> {
            if (tvPreviewPlaceholder != null) {
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            }
        });
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
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        setNonPreviewViewsVisibility(root, isFullscreen ? View.GONE : View.VISIBLE);

        if (isFullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            cardPreview.setVisibility(View.VISIBLE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            if (controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars());
            }
            cardPreview.setVisibility(switchPreview.isChecked() ? View.VISIBLE : View.GONE);
        }
    }

    private void setNonPreviewViewsVisibility(ViewGroup parent, int visibility) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == cardPreview) {
                child.setVisibility(View.VISIBLE);
            } else if (child instanceof ViewGroup) {
                if (hasChild(child, cardPreview)) {
                    setNonPreviewViewsVisibility((ViewGroup) child, visibility);
                } else {
                    child.setVisibility(visibility);
                }
            } else {
                child.setVisibility(visibility);
            }
        }
    }

    private boolean hasChild(View parent, View target) {
        if (parent == target) return true;
        if (parent instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) parent;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasChild(vg.getChildAt(i), target)) return true;
            }
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen();
        } else {
            super.onBackPressed();
        }
    }

    private void setupRecyclerView() {
        adapter = new ChannelAdapter(channels, this);
        recyclerChannels.setLayoutManager(new LinearLayoutManager(this));
        recyclerChannels.setAdapter(adapter);
    }

    private void loadChannels() {
        channels.clear();
        List<StreamChannel> list = db.channelDao().getAllChannels();
        if (list != null) {
            channels.addAll(list);
        }
        if (adapter != null) {
            adapter.updateChannels(channels);
        }
        updateActiveChannelsCount();
    }

    private void updateDroneUrl() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        tvDroneUrl.setText("rtmp://" + ip + ":" + DRONE_PORT + "/live");
    }

    private void bindService() {
        Intent intent = new Intent(this, StreamService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void addChannel() {
        startActivityForResult(new Intent(this, ChannelConfigActivity.class), REQUEST_CHANNEL_CONFIG);
    }

    @Override
    public void onEdit(StreamChannel channel) {
        Intent intent = new Intent(this, ChannelConfigActivity.class);
        intent.putExtra(ChannelConfigActivity.EXTRA_CHANNEL_ID, channel.id);
        startActivityForResult(intent, REQUEST_CHANNEL_CONFIG);
    }

    @Override
    public void onDelete(StreamChannel channel) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Remove '" + channel.name + "'?")
            .setPositiveButton("Yes", (d, w) -> {
                db.channelDao().deleteChannel(channel);
                loadChannels();
            })
            .setNegativeButton("No", null)
            .show();
    }

    @Override
    public void onDuplicate(StreamChannel channel) {
        db.channelDao().insertChannel(channel.copy());
        loadChannels();
    }

    @Override
    public void onToggleActive(StreamChannel channel, boolean active) {
        if (active) {
            int activeCount = 0;
            for (StreamChannel ch : channels) if (ch.isActive) activeCount++;
            if (activeCount >= MAX_ACTIVE_CHANNELS) {
                Toast.makeText(this, "Max 3 active destinations", Toast.LENGTH_SHORT).show();
                adapter.updateChannel(channel);
                return;
            }
        }
        channel.isActive = active;
        db.channelDao().updateChannel(channel);

        if (serviceBound && streamService.isStreaming()) {
            if (active) streamService.addChannel(channel);
            else streamService.removeChannel(channel);
        }
        adapter.updateChannel(channel);
        updateActiveChannelsCount();
    }

    private void startStreaming() {
        if (!serviceBound) return;

        List<StreamChannel> activeChannels = new ArrayList<>();
        for (StreamChannel ch : channels) if (ch.isActive) activeChannels.add(ch);

        streamService.startStreaming(DRONE_PORT, activeChannels,
            switchRecording.isChecked(), switchPreview.isChecked(), switchBattery.isChecked());

        updateUIState(true);
        startStatsUpdate();
    }

    private void stopStreaming() {
        if (serviceBound) streamService.stopStreaming();
        updateUIState(false);
        stopStatsUpdate();
        if (tvPreviewPlaceholder != null) {
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void updateUIState(boolean isLive) {
        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(20f);

        if (isLive) {
            btnStart.setEnabled(false);
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2D2D35")));
            btnStart.setTextColor(Color.parseColor("#A0A0A8"));

            btnStop.setEnabled(true);
            btnStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
            btnStop.setTextColor(Color.parseColor("#FFFFFF"));

            tvStatus.setText("● LIVE");
            tvStatus.setTextColor(Color.parseColor("#FFFFFF"));
            badge.setColor(Color.parseColor("#FF3B5C"));
            tvStatus.setBackground(badge);
            tvStatus.setPadding(24, 8, 24, 8);
        } else {
            btnStart.setEnabled(true);
            btnStart.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
            btnStart.setTextColor(Color.parseColor("#FFFFFF"));

            btnStop.setEnabled(false);
            btnStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2D2D35")));
            btnStop.setTextColor(Color.parseColor("#A0A0A8"));

            tvStatus.setText("● OFFLINE");
            tvStatus.setTextColor(Color.parseColor("#A0A0A8"));
            badge.setColor(Color.parseColor("#25252B"));
            badge.setStroke(2, Color.parseColor("#3D3D48"));
            tvStatus.setBackground(badge);
            tvStatus.setPadding(24, 8, 24, 8);
        }
    }

    private void startStatsUpdate() {
        statsRunnable = () -> {
            if (serviceBound && streamService.isStreaming()) {
                channels.clear();
                channels.addAll(streamService.getChannels());
                adapter.updateChannels(channels);
                statsHandler.postDelayed(statsRunnable, 1000);
            }
        };
        statsHandler.post(statsRunnable);
    }

    private void stopStatsUpdate() {
        if (statsRunnable != null) statsHandler.removeCallbacks(statsRunnable);
    }

    private void updateActiveChannelsCount() {
        int active = 0;
        for (StreamChannel ch : channels) if (ch.isActive) active++;
        tvActiveChannels.setText(active + "/3 Active");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        loadChannels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) unbindService(serviceConnection);
    }
}
