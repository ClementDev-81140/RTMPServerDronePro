package com.rtmp.drone.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    
    private static final String PREF_NAME = "rtmp_drone_prefs";
    private static final String KEY_DRONE_PORT = "drone_port";
    private static final String KEY_LOCAL_RECORDING = "local_recording";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_BATTERY_SAVER = "battery_saver";
    
    private final SharedPreferences prefs;
    
    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public void setDronePort(int port) {
        prefs.edit().putInt(KEY_DRONE_PORT, port).apply();
    }
    
    public int getDronePort() {
        return prefs.getInt(KEY_DRONE_PORT, 1935);
    }
    
    public void setLocalRecording(boolean enabled) {
        prefs.edit().putBoolean(KEY_LOCAL_RECORDING, enabled).apply();
    }
    
    public boolean isLocalRecordingEnabled() {
        return prefs.getBoolean(KEY_LOCAL_RECORDING, false);
    }
    
    public void setPreview(boolean enabled) {
        prefs.edit().putBoolean(KEY_PREVIEW, enabled).apply();
    }
    
    public boolean isPreviewEnabled() {
        return prefs.getBoolean(KEY_PREVIEW, false);
    }
    
    public void setBatterySaver(boolean enabled) {
        prefs.edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply();
    }
    
    public boolean isBatterySaverEnabled() {
        return prefs.getBoolean(KEY_BATTERY_SAVER, false);
    }
}
