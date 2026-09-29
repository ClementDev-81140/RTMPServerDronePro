package com.rtmp.drone.service;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import com.rtmp.drone.R;
import com.rtmp.drone.model.ChannelStats;
import com.rtmp.drone.model.StreamChannel;
import com.rtmp.drone.ui.MainActivity;
import com.rtmp.drone.utils.NotificationHelper;
import com.rtmp.drone.utils.PreferenceManager;
import java.util.*;
import java.util.concurrent.*;

/**
 * Foreground service holding the local RTMP server, the preview decoder,
 * the optional local recorder and one streamer per active destination.
 */
public class StreamService extends Service implements RTMPServer.StreamCallback, LocalRecorder.Listener {
    private static final String TAG = "StreamService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_RETRY_ATTEMPTS = 6;

    private final IBinder binder = new LocalBinder();
    private RTMPServer rtmpServer;
    private H264Decoder decoder;
    private LocalRecorder recorder;
    private PreferenceManager preferences;

    private final Map<Integer, TelegramStreamer> streamers = new ConcurrentHashMap<>();
    private final Set<Integer> failedChannels = Collections.synchronizedSet(new HashSet<Integer>());
    private final List<StreamChannel> channels = new CopyOnWriteArrayList<>();
    private final List<StreamSignalListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isStreaming = false;
    private volatile boolean isDroneSignalActive = false;
    private volatile boolean batterySaver = false;
    private volatile boolean previewEnabled = true;
    private volatile boolean recordingEnabled = false;
    private volatile int serverPort = PreferenceManager.DEFAULT_PORT;
    private volatile long sessionStartMs = 0;
    private volatile byte[] cachedVideoHeader;
    private volatile byte[] cachedAudioHeader;
    private volatile int[] videoSize;
    private volatile String lastRecordingName;
    private volatile String lastRecordingLocation;
    private final Map<Integer, Integer> retryAttempts = new ConcurrentHashMap<>();
    /** Destinations whose connection is being established (avoids two parallel attempts). */
    private final Set<Integer> connectingChannels = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    /** Bumped when a destination is removed/edited, so a stale connection is discarded. */
    private final Map<Integer, Integer> channelGenerations = new ConcurrentHashMap<>();

    public interface StreamSignalListener {
        void onDroneSignalReceived();

        void onDroneSignalLost();

        default void onRecordingStateChanged(boolean recording, String fileName, String location, long sizeBytes) {}

        default void onRecordingError(String message) {}

        default void onStreamError(String message) {}
    }

    public class LocalBinder extends Binder {
        public StreamService getService() {
            return StreamService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isStreaming) {
            // Started without an active session (or restarted by the system): nothing to do.
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        decoder = new H264Decoder();
        preferences = new PreferenceManager(this);
    }

    // ------------------------------------------------------------- UI plumbing

    public void setStreamSignalListener(StreamSignalListener listener) {
        addStreamSignalListener(listener);
    }

    public void addStreamSignalListener(StreamSignalListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        if (isDroneSignalActive) listener.onDroneSignalReceived();
        LocalRecorder current = recorder;
        if (current != null && current.isCapturing()) {
            listener.onRecordingStateChanged(true, current.getCurrentFileName(), current.getCurrentLocation(), 0);
        }
    }

    public void removeStreamSignalListener(StreamSignalListener listener) {
        listeners.remove(listener);
    }

    public void setPreviewSurface(Surface surface) {
        if (decoder != null) {
            decoder.setSurface(surface);
        }
    }

    // ------------------------------------------------------------ stream control

    public void startStreaming(int port, List<StreamChannel> targetChannels,
                               boolean recording, boolean preview, boolean batterySaverEnabled) {
        serverPort = port;
        this.recordingEnabled = recording;
        this.previewEnabled = preview;
        this.batterySaver = batterySaverEnabled;

        this.channels.clear();
        if (targetChannels != null) {
            for (StreamChannel channel : targetChannels) {
                this.channels.add(channel.databaseCopy());
            }
        }
        failedChannels.clear();
        retryAttempts.clear();
        this.isStreaming = true;
        this.isDroneSignalActive = false;
        this.sessionStartMs = System.currentTimeMillis();

        startForegroundWithNotification();
        applyPreviewState();
        if (recordingEnabled) ensureRecorder();

        executor.execute(() -> {
            try {
                if (rtmpServer != null) {
                    rtmpServer.stop();
                }
                rtmpServer = new RTMPServer(serverPort, this);
                rtmpServer.start();
                Log.i(TAG, "RTMP server listening on port " + serverPort + ", waiting for the drone...");
                mainHandler.post(this::updateNotification);
            } catch (Exception e) {
                Log.e(TAG, "Cannot start the RTMP server: " + e.getMessage());
                String reason = (e.getMessage() == null) ? "" : e.getMessage();
                mainHandler.post(() -> {
                    // No server: end the session instead of leaving the UI "waiting for drone".
                    release(true);
                    notifyStreamError(getString(R.string.error_server_start, reason));
                });
            }
        });
    }

    public void stopStreaming() {
        release(true);
    }

    /**
     * Retries the destinations that failed to connect, with a growing delay
     * (5 s, 10 s, 20 s, capped at 30 s) and at most {@link #MAX_RETRY_ATTEMPTS} tries each.
     */
    private void scheduleRetry() {
        if (!isStreaming || !isDroneSignalActive) return;
        boolean pending = false;
        int smallestAttempt = Integer.MAX_VALUE;
        for (StreamChannel channel : channels) {
            if (!channel.isActive || streamers.containsKey(channel.id)) continue;
            Integer stored = retryAttempts.get(channel.id);
            int attempts = (stored == null) ? 0 : stored;
            if (attempts < MAX_RETRY_ATTEMPTS) {
                pending = true;
                smallestAttempt = Math.min(smallestAttempt, attempts);
            }
        }
        if (!pending) return;
        long delay = Math.min(30000L, 5000L * (1L << Math.min(smallestAttempt, 3)));
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, delay);
        Log.i(TAG, "Next destination retry in " + (delay / 1000) + " s");
    }

    private final Runnable retryRunnable = () -> {
        if (!isStreaming || !isDroneSignalActive) return;
        boolean retried = false;
        for (StreamChannel channel : channels) {
            if (!channel.isActive || streamers.containsKey(channel.id)) continue;
            Integer stored = retryAttempts.get(channel.id);
            int attempts = (stored == null) ? 0 : stored;
            if (attempts >= MAX_RETRY_ATTEMPTS) continue;
            retryAttempts.put(channel.id, attempts + 1);
            Log.i(TAG, "Reconnecting " + channel.name + " (attempt " + (attempts + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
            connectDestination(channel);
            retried = true;
        }
        if (retried) scheduleRetry();
    };

    private void cancelRetry() {
        mainHandler.removeCallbacks(retryRunnable);
    }

    private void release(boolean stopService) {
        isStreaming = false;
        isDroneSignalActive = false;
        cachedVideoHeader = null;
        cachedAudioHeader = null;
        videoSize = null;
        sessionStartMs = 0;

        cancelRetry();
        retryAttempts.clear();
        for (StreamChannel channel : channels) {
            channelGenerations.put(channel.id, generationOf(channel.id) + 1);
        }
        stopRecorderNow();

        if (rtmpServer != null) {
            rtmpServer.stop();
            rtmpServer = null;
        }
        for (TelegramStreamer streamer : streamers.values()) {
            streamer.stop();
        }
        streamers.clear();
        failedChannels.clear();

        if (decoder != null) {
            decoder.stop();
        }
        notifyDroneSignalLost();

        try {
            stopForeground(true);
        } catch (Exception ignored) {}

        if (stopService) {
            stopSelf();
        }
    }

    // --------------------------------------------------------- live settings UI

    public void setPreviewEnabled(boolean enabled) {
        this.previewEnabled = enabled;
        applyPreviewState();
        updateNotification();
    }

    public void setBatterySaver(boolean enabled) {
        this.batterySaver = enabled;
        applyPreviewState();
        updateNotification();
        Log.i(TAG, "Battery saver " + (enabled ? "enabled" : "disabled"));
    }

    public void setRecordingEnabled(boolean enabled) {
        this.recordingEnabled = enabled;
        if (enabled) {
            if (isStreaming) ensureRecorder();
        } else {
            stopRecorderNow();
        }
        updateNotification();
    }

    private void applyPreviewState() {
        if (decoder == null) return;
        boolean decode = previewEnabled && !isPreviewPausedByBatterySaver();
        decoder.setEnabled(decode);
        byte[] header = cachedVideoHeader;
        if (decode && header != null && !decoder.isConfigured()) {
            decoder.init(header);
        }
    }

    private boolean isPreviewPausedByBatterySaver() {
        return batterySaver && (preferences == null || preferences.isBatterySaverPausesPreview());
    }

    public boolean isBatterySaverEnabled() {
        return batterySaver;
    }

    public boolean isBatterySaverPausingPreview() {
        return isPreviewPausedByBatterySaver();
    }

    public boolean isPreviewEnabled() {
        return previewEnabled;
    }

    public boolean isRecordingEnabled() {
        return recordingEnabled;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public boolean isDroneSignalActive() {
        return isDroneSignalActive;
    }

    public int getServerPort() {
        return serverPort;
    }

    /** Time since START SERVER was pressed (0 when the server is stopped). */
    public long getSessionUptimeMs() {
        long start = sessionStartMs;
        if (start <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - start);
    }

    public boolean isRecording() {
        LocalRecorder current = recorder;
        return current != null && current.isRunning();
    }

    public boolean isCapturing() {
        LocalRecorder current = recorder;
        return current != null && current.isCapturing();
    }

    public String getRecordingFileName() {
        LocalRecorder current = recorder;
        if (current != null && current.isCapturing()) return current.getCurrentFileName();
        return lastRecordingName;
    }

    public String getRecordingLocation() {
        LocalRecorder current = recorder;
        if (current != null && current.isCapturing()) return current.getCurrentLocation();
        return lastRecordingLocation;
    }

    public long getRecordingDurationMs() {
        LocalRecorder current = recorder;
        return current != null ? current.getCaptureDurationMs() : 0;
    }

    // ---------------------------------------------------------------- recording

    private synchronized void ensureRecorder() {
        if (!recordingEnabled) return;
        LocalRecorder current = recorder;
        if (current != null && !current.isRunning()) {
            recorder = null;
            current = null;
        }
        if (current == null) {
            boolean usePublicStorage = preferences != null && preferences.isPublicRecordingsEnabled();
            current = new LocalRecorder(this, this, usePublicStorage);
            recorder = current;
            current.start();

            // The drone only sends its SPS/PPS at the beginning of a session: when the
            // recording is enabled in the middle of one, feed the cached headers so the
            // file starts without waiting for the next flight.
            byte[] videoHeader = cachedVideoHeader;
            if (videoHeader != null) current.writeVideo(videoHeader, 0);
            byte[] audioHeader = cachedAudioHeader;
            if (audioHeader != null) current.writeAudio(audioHeader, 0);
        }
    }

    private synchronized void stopRecorderNow() {
        LocalRecorder current = recorder;
        if (current != null) current.stop();
    }

    @Override
    public void onRecordingStarted(String fileName, String location, int width, int height) {
        lastRecordingName = fileName;
        lastRecordingLocation = location;
        mainHandler.post(() -> {
            for (StreamSignalListener listener : listeners) {
                listener.onRecordingStateChanged(true, fileName, location, 0);
            }
            updateNotification();
        });
    }

    @Override
    public void onRecordingFinished(String fileName, String location, long sizeBytes, String error) {
        if (fileName != null) {
            lastRecordingName = fileName;
            lastRecordingLocation = location;
        }
        mainHandler.post(() -> {
            if (error == null) {
                for (StreamSignalListener listener : listeners) {
                    listener.onRecordingStateChanged(false, fileName, location, sizeBytes);
                }
            } else {
                for (StreamSignalListener listener : listeners) {
                    listener.onRecordingStateChanged(false, null, null, 0);
                    listener.onRecordingError(error);
                }
            }
            updateNotification();
        });
    }

    private void notifyStreamError(String message) {
        mainHandler.post(() -> {
            for (StreamSignalListener listener : listeners) {
                listener.onStreamError(message);
            }
        });
    }

    // ------------------------------------------------------------- destinations

    private int generationOf(int channelId) {
        Integer generation = channelGenerations.get(channelId);
        return (generation == null) ? 0 : generation;
    }

    private void connectDestination(StreamChannel channel) {
        final StreamChannel target = channel.databaseCopy();
        if (!connectingChannels.add(target.id)) {
            Log.i(TAG, "Connection to " + target.name + " already in progress, skipping");
            return;
        }
        final int generation = generationOf(target.id);
        executor.execute(() -> {
            TelegramStreamer streamer = null;
            try {
                Log.i(TAG, "Connecting destination " + target.name + " (" + target.getUrl() + ")");
                final TelegramStreamer created =
                        new TelegramStreamer(target.getUrl(), target.streamKey, target.targetBitrate);
                streamer = created;

                int[] size = videoSize;
                if (size != null) created.setVideoSize(size[0], size[1]);
                created.setStrictTls(preferences == null || preferences.isStrictTlsEnabled());
                created.setErrorListener(() -> {
                    // The connection died mid stream: close it cleanly, flag the destination
                    // and let the retry loop try again.
                    created.stop();
                    streamers.remove(target.id, created);
                    failedChannels.add(target.id);
                    mainHandler.post(this::scheduleRetry);
                });

                byte[] videoHeader = cachedVideoHeader;
                if (videoHeader != null) {
                    created.startStream(videoHeader, cachedAudioHeader);
                }

                created.connect();

                // The destination may have been removed, edited or the session stopped while
                // the connection was being established: never publish a stale streamer.
                if (!isStreaming || generation != generationOf(target.id)) {
                    Log.i(TAG, "Dropping the now obsolete connection to " + target.name);
                    created.stop();
                    return;
                }

                TelegramStreamer previous = streamers.put(target.id, created);
                if (previous != null && previous != created) previous.stop();
                failedChannels.remove(target.id);
                retryAttempts.remove(target.id);
                Log.i(TAG, target.name + " is live");
            } catch (Exception e) {
                if (streamer != null) streamer.stop();
                failedChannels.add(target.id);
                Log.e(TAG, "Connection to " + target.name + " failed: " + e.getMessage());
                mainHandler.post(this::scheduleRetry);
            } finally {
                connectingChannels.remove(target.id);
            }
        });
    }

    public void addChannel(StreamChannel channel) {
        if (channel == null) return;
        for (StreamChannel existing : channels) {
            if (existing.id == channel.id) return;
        }
        StreamChannel copy = channel.databaseCopy();
        channels.add(copy);
        if (isStreaming && isDroneSignalActive && copy.isActive) {
            connectDestination(copy);
            scheduleRetry();
        }
    }

    public void removeChannel(StreamChannel channel) {
        if (channel == null) return;
        channelGenerations.put(channel.id, generationOf(channel.id) + 1);
        connectingChannels.remove(channel.id);
        for (StreamChannel existing : new ArrayList<>(channels)) {
            if (existing.id == channel.id) channels.remove(existing);
        }
        TelegramStreamer streamer = streamers.remove(channel.id);
        if (streamer != null) streamer.stop();
        failedChannels.remove(channel.id);
    }

    /**
     * Applies an edition (new URL, key, quality...) to a destination already used by the
     * running session: the old connection is dropped and recreated with the new settings.
     */
    public void refreshChannel(StreamChannel channel) {
        if (channel == null) return;
        boolean wasRunning = false;
        for (StreamChannel existing : channels) {
            if (existing.id == channel.id) {
                wasRunning = streamers.containsKey(channel.id) || existing.isActive;
                break;
            }
        }
        removeChannel(channel);
        if (wasRunning) {
            retryAttempts.remove(channel.id);
            addChannel(channel);
        }
    }

    public int getActiveChannelCount() {
        int count = 0;
        for (StreamChannel channel : channels) {
            if (channel.isActive) count++;
        }
        return count;
    }

    /**
     * Live statistics of every destination, keyed by channel id.
     * The UI merges this into its own database list: the list of the service is
     * never used as the data source of the screen anymore.
     */
    public Map<Integer, ChannelStats> getStatsSnapshot() {
        Map<Integer, ChannelStats> snapshot = new HashMap<>();
        for (StreamChannel channel : channels) {
            TelegramStreamer streamer = streamers.get(channel.id);
            if (streamer != null && streamer.isStreaming()) {
                snapshot.put(channel.id, new ChannelStats(channel.id, true, false, false,
                        streamer.getBitrate(), streamer.getLatency(), streamer.getBytesSent()));
            } else if (streamer != null || (isDroneSignalActive && channel.isActive)) {
                boolean error = failedChannels.contains(channel.id);
                snapshot.put(channel.id, new ChannelStats(channel.id, false, !error, error, 0, 0, 0));
            }
        }
        return snapshot;
    }

    // -------------------------------------------------------------- RTMP events

    @Override
    public void onStreamStarted(byte[] videoHeader, byte[] audioHeader) {
        Log.i(TAG, "Drone signal detected, connecting the active destinations...");
        this.cachedVideoHeader = videoHeader;
        this.cachedAudioHeader = audioHeader;
        this.isDroneSignalActive = true;

        if (videoHeader != null) {
            int[] dimensions = H264Utils.parseSpsDimensions(H264Utils.extractSps(videoHeader));
            if (dimensions != null) videoSize = dimensions;
        }
        if (videoHeader != null && decoder != null && !isPreviewPausedByBatterySaver() && previewEnabled) {
            decoder.init(videoHeader);
        }
        if (recordingEnabled) {
            ensureRecorder();
        }

        notifyDroneSignalReceived();
        updateNotification();

        for (StreamChannel channel : channels) {
            if (channel.isActive && !streamers.containsKey(channel.id)) {
                connectDestination(channel);
            }
        }
        scheduleRetry();
    }

    @Override
    public void onStreamData(byte[] data, int type, int timestamp) {
        if (!isDroneSignalActive || data == null || data.length == 0) return;

        LocalRecorder current = recorder;
        if (type == 9) {
            if (current != null) current.writeVideo(data, timestamp);
            if (decoder != null && decoder.isEnabled()) decoder.decodeFrame(data);
        } else if (type == 8) {
            if (data.length > 1 && data[1] == 0x00 && (data[0] & 0xF0) == 0xA0) {
                // Real AAC sequence header of the drone: keep it for the destinations
                // that will be connected later during the same session.
                cachedAudioHeader = data;
            }
            if (current != null) current.writeAudio(data, timestamp);
        }

        for (TelegramStreamer streamer : streamers.values()) {
            streamer.sendData(data, type, timestamp);
        }
    }

    @Override
    public void onStreamStopped() {
        Log.i(TAG, "Drone stream disconnected");
        isDroneSignalActive = false;
        cancelRetry();
        retryAttempts.clear();
        cachedVideoHeader = null;
        cachedAudioHeader = null;

        for (TelegramStreamer streamer : streamers.values()) {
            streamer.stop();
        }
        streamers.clear();
        failedChannels.clear();

        if (decoder != null) {
            decoder.stop();
        }
        stopRecorderNow();

        notifyDroneSignalLost();
        updateNotification();
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Stream error: " + error);
        notifyStreamError(error);
    }

    private void notifyDroneSignalReceived() {
        mainHandler.post(() -> {
            for (StreamSignalListener listener : listeners) {
                listener.onDroneSignalReceived();
            }
        });
    }

    private void notifyDroneSignalLost() {
        mainHandler.post(() -> {
            for (StreamSignalListener listener : listeners) {
                listener.onDroneSignalLost();
            }
        });
    }

    // ------------------------------------------------------------- notification

    private void startForegroundWithNotification() {
        NotificationHelper.createNotificationChannel(this);
        try {
            Intent serviceIntent = new Intent(getApplicationContext(), StreamService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService failed: " + e.getMessage());
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
        }
    }

    private void updateNotification() {
        if (!isStreaming && !isCapturing()) return;
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "Cannot update the notification: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        NotificationHelper.createNotificationChannel(this);

        StringBuilder text = new StringBuilder();
        if (!isStreaming) {
            text.append(getString(R.string.notification_idle));
        } else if (isDroneSignalActive) {
            text.append(getString(R.string.notification_live, getActiveChannelCount()));
        } else {
            text.append(getString(R.string.notification_waiting, serverPort));
        }
        if (isCapturing()) text.append(" • REC");
        if (batterySaver) text.append(" • ").append(getString(R.string.notification_battery_saver));

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        return new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.toString())
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        release(false);
        executor.shutdownNow();
        Log.i(TAG, "Service destroyed");
    }
}
