package com.rtmp.drone.service;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import com.rtmp.drone.R;
import com.rtmp.drone.model.StreamChannel;
import com.rtmp.drone.utils.NotificationHelper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class StreamService extends Service implements RTMPServer.StreamCallback {
    private static final String TAG = "StreamService";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();
    private RTMPServer rtmpServer;
    private H264Decoder decoder;
    private final Map<Integer, TelegramStreamer> streamers = new ConcurrentHashMap<>();
    private final List<StreamChannel> channels = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean isStreaming = false;
    private volatile boolean isDroneSignalActive = false;
    private byte[] cachedVideoHeader;
    private byte[] cachedAudioHeader;

    public interface StreamSignalListener {
        void onDroneSignalReceived();
        void onDroneSignalLost();
    }

    private StreamSignalListener signalListener;

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
    public void onCreate() {
        super.onCreate();
        decoder = new H264Decoder();
    }

    public void setPreviewSurface(Surface surface) {
        if (decoder != null) {
            decoder.setSurface(surface);
        }
    }

    public void setStreamSignalListener(StreamSignalListener listener) {
        this.signalListener = listener;
        if (isDroneSignalActive && signalListener != null) {
            signalListener.onDroneSignalReceived();
        }
    }

    public void startStreaming(int port, List<StreamChannel> targetChannels, boolean isRecording, boolean isPreview, boolean isBattery) {
        startForeground(NOTIFICATION_ID, createNotification("Serveur RTMP en attente du drone..."));
        this.channels.clear();
        this.channels.addAll(targetChannels);
        this.isStreaming = true;

        executor.execute(() -> {
            try {
                if (rtmpServer != null) {
                    rtmpServer.stop();
                }
                rtmpServer = new RTMPServer(port, this);
                rtmpServer.start();
                Log.i(TAG, "RTMPServer running on port " + port + ". Waiting for Drone...");
            } catch (IOException e) {
                Log.e(TAG, "Error starting server: " + e.getMessage());
            }
        });
    }

    private void connectDestination(StreamChannel channel) {
        executor.execute(() -> {
            try {
                Log.i(TAG, "📡 [Connect-on-Signal] Connexion de " + channel.name + " (" + channel.getUrl() + ")");
                TelegramStreamer streamer = new TelegramStreamer(channel.getUrl(), channel.streamKey, channel.targetBitrate);
                
                if (cachedVideoHeader != null) {
                    streamer.startStream(cachedVideoHeader, cachedAudioHeader);
                }
                
                streamer.connect();
                streamers.put(channel.id, streamer);
                channel.status = StreamChannel.Status.LIVE;
                channel.isConnected = true;
                Log.i(TAG, "✓ " + channel.name + " est en ligne !");
            } catch (Exception e) {
                Log.e(TAG, "Échec de connexion vers " + channel.name + ": " + e.getMessage());
                channel.status = StreamChannel.Status.ERROR;
                channel.isConnected = false;
            }
        });
    }

    public void addChannel(StreamChannel channel) {
        channels.add(channel);
        if (isStreaming && isDroneSignalActive) {
            connectDestination(channel);
        }
    }

    public void removeChannel(StreamChannel channel) {
        channels.remove(channel);
        TelegramStreamer streamer = streamers.remove(channel.id);
        if (streamer != null) {
            streamer.stop();
        }
        channel.isConnected = false;
    }

    public void stopStreaming() {
        isStreaming = false;
        isDroneSignalActive = false;
        cachedVideoHeader = null;
        cachedAudioHeader = null;
        
        if (rtmpServer != null) {
            rtmpServer.stop();
            rtmpServer = null;
        }
        for (TelegramStreamer s : streamers.values()) {
            s.stop();
        }
        streamers.clear();
        for (StreamChannel c : channels) c.isConnected = false;

        if (decoder != null) {
            decoder.stop();
        }
        if (signalListener != null) {
            signalListener.onDroneSignalLost();
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onStreamStarted(byte[] videoHeader, byte[] audioHeader) {
        Log.i(TAG, "⚡ [Signal reçu] Le drone émet SPS/PPS ! Connexion immédiate des chaînes distantes...");
        this.cachedVideoHeader = videoHeader;
        this.cachedAudioHeader = audioHeader;
        this.isDroneSignalActive = true;

        if (decoder != null && videoHeader != null && videoHeader.length > 0) {
            decoder.init(videoHeader);
        }

        if (signalListener != null) {
            signalListener.onDroneSignalReceived();
        }

        for (StreamChannel channel : channels) {
            if (channel.isActive && !streamers.containsKey(channel.id)) {
                connectDestination(channel);
            }
        }
    }

    @Override
    public void onStreamData(byte[] data, int type, int timestamp) {
        if (!isDroneSignalActive) {
            return;
        }

        if (type == 9 && decoder != null) {
            decoder.decodeFrame(data);
        }

        for (TelegramStreamer s : streamers.values()) {
            s.sendData(data, type, timestamp);
        }
    }

    @Override
    public void onStreamStopped() {
        Log.i(TAG, "Drone stream disconnected");
        isDroneSignalActive = false;
        cachedVideoHeader = null;
        cachedAudioHeader = null;
        
        for (TelegramStreamer s : streamers.values()) {
            s.stop();
        }
        streamers.clear();
        
        if (signalListener != null) {
            signalListener.onDroneSignalLost();
        }
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Stream error: " + error);
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public boolean isDroneSignalActive() {
        return isDroneSignalActive;
    }

    public List<StreamChannel> getChannels() {
        for (StreamChannel ch : channels) {
            TelegramStreamer s = streamers.get(ch.id);
            if (s != null && s.isStreaming()) {
                ch.status = StreamChannel.Status.LIVE;
                ch.currentBitrate = s.getBitrate();
                ch.bitrate = ch.currentBitrate;
                ch.latency = s.getLatency();
                ch.latencyMs = ch.latency;
                ch.bytesSent = s.getBytesSent();
                ch.isConnected = true;
            } else if (!isDroneSignalActive) {
                ch.status = StreamChannel.Status.OFFLINE;
                ch.currentBitrate = 0;
                ch.isConnected = false;
            }
        }
        return channels;
    }

    private Notification createNotification(String text) {
        NotificationHelper.createNotificationChannel(this);
        return new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("RTMP Server Drone PRO")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopStreaming();
        executor.shutdownNow();
    }
}
