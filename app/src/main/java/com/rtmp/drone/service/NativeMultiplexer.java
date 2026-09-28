package com.rtmp.drone.service;

import java.io.OutputStream;

public class NativeMultiplexer {
    
    static {
        try {
            System.loadLibrary("rtmpnative");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        }
    }
    
    public native String getVersion();
    public native void initialize();
    public native int distributePacket(byte[] packet, int size, OutputStream[] streams);
    public native int getPacketType(byte[] packet);
    public native int calculateCRC32(byte[] data, int length);
    public native void fastCopy(byte[] src, byte[] dst, int length);
    
    private static NativeMultiplexer instance;
    
    public static NativeMultiplexer getInstance() {
        if (instance == null) {
            instance = new NativeMultiplexer();
            instance.initialize();
        }
        return instance;
    }
}
