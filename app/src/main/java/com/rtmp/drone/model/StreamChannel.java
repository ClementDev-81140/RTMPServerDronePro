package com.rtmp.drone.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "channels")
public class StreamChannel {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String name = "";
    public String rtmpUrl = "";
    public String url = "";
    public String streamKey = "";
    public int quality = 1; // 0 = 720p 1Mbps, 1 = 1080p 2.5Mbps, 2 = 1080p 4Mbps
    public int targetBitrate = 2500;
    public boolean isActive = false;
    public boolean isPrimary = false;
    public int sortOrder = 0;

    public enum Status { OFFLINE, CONNECTING, LIVE, ERROR }

    @Ignore
    public Status status = Status.OFFLINE;

    @Ignore
    public boolean isConnected = false;

    @Ignore
    public long bytesSent = 0;

    @Ignore
    public int bitrate = 0;

    @Ignore
    public int currentBitrate = 0;

    @Ignore
    public int latency = 0;

    @Ignore
    public int latencyMs = 0;

    public StreamChannel() {}

    @Ignore
    public StreamChannel(String name, String rtmpUrl, String streamKey, int quality) {
        this.name = name;
        this.rtmpUrl = rtmpUrl;
        this.url = rtmpUrl;
        this.streamKey = streamKey;
        this.quality = quality;
        this.targetBitrate = getBitrateForQuality();
    }

    public int getBitrateForQuality() {
        switch (quality) {
            case 0: return 1000;
            case 2: return 4000;
            case 1:
            default: return 2500;
        }
    }

    public String getUrl() {
        if (rtmpUrl != null && !rtmpUrl.trim().isEmpty()) return rtmpUrl.trim();
        if (url != null && !url.trim().isEmpty()) return url.trim();
        return "";
    }

    public StreamChannel copy() {
        StreamChannel c = new StreamChannel(
            (this.name != null ? this.name : "Channel") + " (Copy)",
            getUrl(),
            this.streamKey != null ? this.streamKey : "",
            this.quality
        );
        c.isActive = false;
        c.isPrimary = false;
        c.sortOrder = this.sortOrder + 1;
        return c;
    }
}
