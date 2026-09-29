package com.rtmp.drone.utils;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Finds the IPv4 address the drone has to connect to.
 *
 * <p>When the phone shares its connection (hotspot / tethering), the interesting address is the
 * one of the local network interface — not the cellular one. The interfaces are therefore ranked
 * so that Wi-Fi, hotspot and Ethernet win over mobile data.</p>
 */
public class NetworkUtils {

    public static String getLocalIpAddress(Context context) {
        List<String> addresses = getLocalIpv4Addresses(context);
        return addresses.isEmpty() ? "192.168.X.X" : addresses.get(0);
    }

    /** Every usable IPv4 address of the device, most likely hotspot address first. */
    public static List<String> getLocalIpv4Addresses(Context context) {
        List<String> result = new ArrayList<>();

        String wifiAddress = getWifiAddress(context);
        if (wifiAddress != null && !result.contains(wifiAddress)) {
            result.add(wifiAddress);
        }

        List<String> ranked = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                 interfaces != null && interfaces.hasMoreElements(); ) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                String name = networkInterface.getName() == null
                        ? "" : networkInterface.getName().toLowerCase(Locale.US);
                boolean local = isLocalInterface(name);

                for (Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                     inetAddresses.hasMoreElements(); ) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!(inetAddress instanceof Inet4Address)) continue;
                    if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) continue;
                    if (!inetAddress.isSiteLocalAddress()) continue; // private ranges only

                    String address = inetAddress.getHostAddress();
                    if (address == null || address.isEmpty()) continue;
                    if (local) {
                        if (!ranked.contains(address)) ranked.add(address);
                    } else {
                        if (!others.contains(address)) others.add(address);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        result.addAll(ranked);
        result.addAll(others);
        return Collections.unmodifiableList(result);
    }

    private static String getWifiAddress(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return null;
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            if (ipAddress == 0) return null;
            String address = Formatter.formatIpAddress(ipAddress);
            if (address == null || address.isEmpty() || "0.0.0.0".equals(address)) return null;
            return address;
        } catch (Exception e) {
            // On Android 12+ this call may be denied without the location permission.
            return null;
        }
    }

    /** Wi-Fi, hotspot and Ethernet interfaces are the ones the drone can reach. */
    private static boolean isLocalInterface(String name) {
        return name.startsWith("wlan")
                || name.startsWith("ap")
                || name.startsWith("swlan")
                || name.startsWith("softap")
                || name.startsWith("eth")
                || name.startsWith("rndis")
                || name.startsWith("p2p");
    }

    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.US).trim();
        return lower.startsWith("rtmp://") || lower.startsWith("rtmps://");
    }

    public static int getDefaultPortFromUrl(String url) {
        if (url != null && url.toLowerCase(Locale.US).startsWith("rtmps://")) {
            return 443;
        }
        return 1935;
    }
}
