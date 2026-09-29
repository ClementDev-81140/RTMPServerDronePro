package com.rtmp.drone.model;

import java.util.List;
import java.util.Locale;

/** Aggregated statistics shown at the bottom of the main screen. */
public class GlobalStats {

    public int activeChannels;
    public int totalChannels;
    public long totalBytesSent;
    public int averageLatency;
    public int totalBitrate;
    public boolean droneConnected;
    public long uptime;

    public GlobalStats() {
        this.activeChannels = 0;
        this.totalChannels = 0;
        this.totalBytesSent = 0;
        this.averageLatency = 0;
        this.totalBitrate = 0;
        this.droneConnected = false;
        this.uptime = 0;
    }

    public String getFormattedStats() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Drone signal : %s%n", droneConnected ? "ONLINE" : "OFFLINE"));
        sb.append(String.format(Locale.US, "Destinations : %d active / %d%n", activeChannels, totalChannels));
        sb.append(String.format(Locale.US, "Bitrate      : %.2f Mbps%n", totalBitrate / 1000.0));
        if (activeChannels > 0) {
            sb.append(String.format(Locale.US, "Latency      : %d ms%n", averageLatency));
        }
        sb.append(String.format(Locale.US, "Sent         : %.2f MB", totalBytesSent / 1024.0 / 1024.0));
        if (uptime > 0) {
            long seconds = uptime / 1000;
            sb.append(String.format(Locale.US, "%nUptime       : %02d:%02d:%02d",
                    seconds / 3600, (seconds / 60) % 60, seconds % 60));
        }
        return sb.toString();
    }

    public void updateFromChannels(List<StreamChannel> channels) {
        activeChannels = 0;
        totalChannels = channels.size();
        totalBytesSent = 0;
        int totalLatency = 0;
        totalBitrate = 0;

        for (StreamChannel channel : channels) {
            if (channel.isActive) {
                activeChannels++;
                totalBytesSent += channel.bytesSent;
                if (channel.isConnected) {
                    totalLatency += channel.latency;
                    totalBitrate += channel.bitrate;
                }
            }
        }

        int connected = 0;
        for (StreamChannel channel : channels) {
            if (channel.isActive && channel.isConnected) connected++;
        }
        averageLatency = (connected > 0) ? (totalLatency / connected) : 0;
    }
}
