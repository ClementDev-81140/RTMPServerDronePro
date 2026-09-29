package com.rtmp.drone.model;

/**
 * Immutable snapshot of the live statistics of one destination.
 * The service publishes those values, the UI merges them into the database list
 * (the database list is never replaced by the service list).
 */
public class ChannelStats {

    public final int channelId;
    public final boolean connected;
    public final boolean connecting;
    public final boolean error;
    public final int bitrateKbps;
    public final int latencyMs;
    public final long bytesSent;

    public ChannelStats(int channelId, boolean connected, boolean connecting, boolean error,
                        int bitrateKbps, int latencyMs, long bytesSent) {
        this.channelId = channelId;
        this.connected = connected;
        this.connecting = connecting;
        this.error = error;
        this.bitrateKbps = bitrateKbps;
        this.latencyMs = latencyMs;
        this.bytesSent = bytesSent;
    }
}
