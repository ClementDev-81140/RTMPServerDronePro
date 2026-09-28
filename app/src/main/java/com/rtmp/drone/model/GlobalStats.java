package com.rtmp.drone.model;

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
        sb.append(String.format("Drone: %s\n", droneConnected ? "✓ Connecté" : "✗ Déconnecté"));
        sb.append(String.format("Chaînes actives: %d/%d\n", activeChannels, totalChannels));
        sb.append(String.format("Latence moyenne: %d ms\n", averageLatency));
        sb.append(String.format("Bitrate total: %.1f Mbps\n", totalBitrate / 1000.0));
        sb.append(String.format("Données envoyées: %.2f MB\n", totalBytesSent / 1024.0 / 1024.0));
        if (uptime > 0) {
            long seconds = uptime / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            sb.append(String.format("Uptime: %02d:%02d:%02d", hours, minutes % 60, seconds % 60));
        }
        return sb.toString();
    }
    
    public void updateFromChannels(java.util.List<StreamChannel> channels) {
        activeChannels = 0;
        totalChannels = channels.size();
        totalBytesSent = 0;
        int totalLatency = 0;
        totalBitrate = 0;
        
        for (StreamChannel channel : channels) {
            if (channel.isActive) {
                activeChannels++;
                totalBytesSent += channel.bytesSent;
                totalLatency += channel.latency;
                totalBitrate += channel.bitrate;
            }
        }
        
        if (activeChannels > 0) {
            averageLatency = totalLatency / activeChannels;
        } else {
            averageLatency = 0;
        }
    }
}
