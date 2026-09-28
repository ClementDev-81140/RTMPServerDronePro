package com.rtmp.drone.utils;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import java.net.*;
import java.util.Enumeration;

public class NetworkUtils {
    
    public static String getLocalIpAddress(Context context) {
        try {
            // Méthode 1: Via WifiManager
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                if (ipAddress != 0) {
                    return Formatter.formatIpAddress(ipAddress);
                }
            }
            
            // Méthode 2: Via NetworkInterface
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); 
                 en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); 
                     enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "192.168.X.X";
    }
    
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return url.startsWith("rtmp://") || url.startsWith("rtmps://");
    }
    
    public static int getDefaultPortFromUrl(String url) {
        if (url.startsWith("rtmps://")) {
            return 443;
        }
        return 1935;
    }
}
