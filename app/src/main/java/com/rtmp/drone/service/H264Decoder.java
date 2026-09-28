package com.rtmp.drone.service;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class H264Decoder {
    private static final String TAG = "H264Decoder";
    private static final String MIME_TYPE = "video/avc";
    
    private MediaCodec mediaCodec;
    private Surface surface;
    private boolean isConfigured = false;
    private byte[] cachedHeader = null;

    public H264Decoder() {}

    public H264Decoder(Surface surface) {
        this.surface = surface;
    }

    public synchronized void setSurface(Surface surface) {
        this.surface = surface;
        if (cachedHeader != null && surface != null && surface.isValid()) {
            stop();
            init(cachedHeader);
        }
    }

    public synchronized void init(byte[] avcConfigHeader) {
        if (avcConfigHeader == null || avcConfigHeader.length < 11) return;
        this.cachedHeader = avcConfigHeader;

        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "Surface not ready yet, SPS/PPS cached");
            return;
        }

        try {
            if (mediaCodec != null) {
                stop();
            }

            byte[] csd0 = extractSpsPpsAnnexB(avcConfigHeader);
            if (csd0 == null) {
                Log.e(TAG, "Failed to extract SPS/PPS from sequence header");
                return;
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, 1280, 720);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));

            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
            isConfigured = true;
            Log.i(TAG, "✓ Hardware MediaCodec H.264 started successfully");
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec init error: " + e.getMessage());
            isConfigured = false;
        }
    }

    private byte[] extractSpsPpsAnnexB(byte[] data) {
        try {
            int offset = 5; // Skip FLV Video Tag header
            if (data.length <= offset + 6) return null;

            int spsCount = data[offset + 5] & 0x1F;
            int pos = offset + 6;
            if (spsCount == 0 || pos + 2 >= data.length) return null;

            int spsLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (pos + spsLen >= data.length) return null;
            byte[] sps = Arrays.copyOfRange(data, pos, pos + spsLen);
            pos += spsLen;

            int ppsCount = data[pos] & 0xFF;
            pos += 1;
            if (ppsCount == 0 || pos + 2 >= data.length) return null;

            int ppsLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (pos + ppsLen > data.length) return null;
            byte[] pps = Arrays.copyOfRange(data, pos, pos + ppsLen);

            byte[] annexB = new byte[4 + sps.length + 4 + pps.length];
            annexB[0] = 0; annexB[1] = 0; annexB[2] = 0; annexB[3] = 1;
            System.arraycopy(sps, 0, annexB, 4, sps.length);
            int ppsOffset = 4 + sps.length;
            annexB[ppsOffset] = 0; annexB[ppsOffset + 1] = 0; annexB[ppsOffset + 2] = 0; annexB[ppsOffset + 3] = 1;
            System.arraycopy(pps, 0, annexB, ppsOffset + 4, pps.length);

            return annexB;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting SPS/PPS: " + e.getMessage());
            return null;
        }
    }

    public synchronized void decodeFrame(byte[] data) {
        if (data == null || data.length < 5) return;

        if (data[0] == 0x17 && data[1] == 0x00) {
            init(data);
            return;
        }

        if (!isConfigured || mediaCodec == null) {
            if (cachedHeader != null && surface != null && surface.isValid()) {
                init(cachedHeader);
            }
            if (!isConfigured || mediaCodec == null) return;
        }

        int offset = 5;
        while (offset + 4 < data.length) {
            int naluLen = ((data[offset] & 0xFF) << 24) |
                          ((data[offset + 1] & 0xFF) << 16) |
                          ((data[offset + 2] & 0xFF) << 8) |
                          (data[offset + 3] & 0xFF);
            offset += 4;

            if (naluLen <= 0 || offset + naluLen > data.length) break;

            byte[] nalu = new byte[4 + naluLen];
            nalu[0] = 0; nalu[1] = 0; nalu[2] = 0; nalu[3] = 1;
            System.arraycopy(data, offset, nalu, 4, naluLen);
            offset += naluLen;

            feedMediaCodec(nalu);
        }
    }

    private void feedMediaCodec(byte[] nalu) {
        try {
            int inIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(nalu);
                    mediaCodec.queueInputBuffer(inIndex, 0, nalu.length, System.nanoTime() / 1000, 0);
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex = mediaCodec.dequeueOutputBuffer(info, 0);
            while (outIndex >= 0) {
                mediaCodec.releaseOutputBuffer(outIndex, true);
                outIndex = mediaCodec.dequeueOutputBuffer(info, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Decode error: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        isConfigured = false;
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception ignored) {}
            mediaCodec = null;
        }
    }
}
