package com.rtmp.drone.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single place where every user setting of the app is stored.
 * All values are read by MainActivity / StreamService and edited from the Configuration screen.
 */
public class PreferenceManager {

    private static final String PREF_NAME = "rtmp_drone_prefs";

    // Server
    private static final String KEY_DRONE_PORT = "drone_port";
    private static final String KEY_STREAM_PATH = "stream_path";
    private static final String KEY_AUTO_START = "auto_start_server";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";

    // Streaming defaults
    private static final String KEY_DEFAULT_PREVIEW = "default_preview";
    private static final String KEY_DEFAULT_RECORDING = "default_recording";
    private static final String KEY_DEFAULT_BATTERY = "default_battery_saver";

    // Recording
    private static final String KEY_PUBLIC_RECORDINGS = "public_recordings";

    // Battery saver
    private static final String KEY_BATTERY_PAUSES_PREVIEW = "battery_pauses_preview";

    public static final int DEFAULT_PORT = 1935;
    public static final String DEFAULT_PATH = "live";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ Server

    public void setDronePort(int port) {
        prefs.edit().putInt(KEY_DRONE_PORT, port).apply();
    }

    public int getDronePort() {
        int port = prefs.getInt(KEY_DRONE_PORT, DEFAULT_PORT);
        if (port < 1024 || port > 65535) return DEFAULT_PORT;
        return port;
    }

    public void setStreamPath(String path) {
        prefs.edit().putString(KEY_STREAM_PATH, path).apply();
    }

    public String getStreamPath() {
        String path = prefs.getString(KEY_STREAM_PATH, DEFAULT_PATH);
        if (path == null || path.trim().isEmpty()) return DEFAULT_PATH;
        return path.trim();
    }

    public void setAutoStartServer(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply();
    }

    public boolean isAutoStartServerEnabled() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    public void setKeepScreenOn(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    public boolean isKeepScreenOnEnabled() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    // -------------------------------------------------------- Streaming defaults

    public void setDefaultPreview(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEFAULT_PREVIEW, enabled).apply();
    }

    public boolean isDefaultPreviewEnabled() {
        return prefs.getBoolean(KEY_DEFAULT_PREVIEW, true);
    }

    public void setDefaultRecording(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEFAULT_RECORDING, enabled).apply();
    }

    public boolean isDefaultRecordingEnabled() {
        return prefs.getBoolean(KEY_DEFAULT_RECORDING, false);
    }

    public void setDefaultBatterySaver(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEFAULT_BATTERY, enabled).apply();
    }

    public boolean isDefaultBatterySaverEnabled() {
        return prefs.getBoolean(KEY_DEFAULT_BATTERY, false);
    }

    // --------------------------------------------------------------- Recording

    public void setPublicRecordings(boolean enabled) {
        prefs.edit().putBoolean(KEY_PUBLIC_RECORDINGS, enabled).apply();
    }

    public boolean isPublicRecordingsEnabled() {
        return prefs.getBoolean(KEY_PUBLIC_RECORDINGS, false);
    }

    // ----------------------------------------------------------- Battery saver

    public void setBatterySaverPausesPreview(boolean enabled) {
        prefs.edit().putBoolean(KEY_BATTERY_PAUSES_PREVIEW, enabled).apply();
    }

    public boolean isBatterySaverPausesPreview() {
        return prefs.getBoolean(KEY_BATTERY_PAUSES_PREVIEW, true);
    }

    // ------------------------------------------------------------------ Helpers

    public void restoreDefaults() {
        prefs.edit()
            .putInt(KEY_DRONE_PORT, DEFAULT_PORT)
            .putString(KEY_STREAM_PATH, DEFAULT_PATH)
            .putBoolean(KEY_AUTO_START, false)
            .putBoolean(KEY_KEEP_SCREEN_ON, true)
            .putBoolean(KEY_DEFAULT_PREVIEW, true)
            .putBoolean(KEY_DEFAULT_RECORDING, false)
            .putBoolean(KEY_DEFAULT_BATTERY, false)
            .putBoolean(KEY_PUBLIC_RECORDINGS, false)
            .putBoolean(KEY_BATTERY_PAUSES_PREVIEW, true)
            .apply();
    }
}
