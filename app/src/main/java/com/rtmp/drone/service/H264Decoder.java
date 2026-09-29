package com.rtmp.drone.service;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;

/**
 * Hardware H.264 decoder used for the local preview (SurfaceView).
 * The SPS/PPS sent by the drone are converted to Annex-B and used as csd-0,
 * the AVCC frames are converted on the fly, and the video size is read from the SPS.
 */
public class H264Decoder {
    private static final String TAG = "H264Decoder";
    private static final String MIME_TYPE = "video/avc";

    private static final int FALLBACK_WIDTH = 1280;
    private static final int FALLBACK_HEIGHT = 720;

    private MediaCodec mediaCodec;
    private Surface surface;
    private boolean isConfigured = false;
    private byte[] cachedHeader = null;
    private boolean enabled = true;

    public H264Decoder() {}

    public H264Decoder(Surface surface) {
        this.surface = surface;
    }

    /** Battery saver can disable the decoding completely (no preview, no CPU). */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stop();
        } else if (cachedHeader != null && surface != null && surface.isValid()) {
            init(cachedHeader);
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized boolean isConfigured() {
        return isConfigured && mediaCodec != null;
    }

    public synchronized void setSurface(Surface surface) {
        this.surface = surface;
        if (!enabled) return;
        if (cachedHeader != null && surface != null && surface.isValid()) {
            stop();
            init(cachedHeader);
        } else if (surface == null || !surface.isValid()) {
            stop();
        }
    }

    public synchronized void init(byte[] avcConfigHeader) {
        if (avcConfigHeader == null || avcConfigHeader.length < 11) return;
        this.cachedHeader = avcConfigHeader;

        if (!enabled) return;
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "Surface not ready yet, SPS/PPS cached");
            return;
        }

        try {
            if (mediaCodec != null) {
                stop();
            }

            byte[] csd0 = H264Utils.extractSpsPpsAnnexB(avcConfigHeader);
            if (csd0 == null || csd0.length == 0) {
                Log.e(TAG, "Failed to extract SPS/PPS from the sequence header");
                return;
            }

            int width = FALLBACK_WIDTH;
            int height = FALLBACK_HEIGHT;
            int[] dimensions = H264Utils.parseSpsDimensions(H264Utils.extractSps(avcConfigHeader));
            if (dimensions != null) {
                width = dimensions[0];
                height = dimensions[1];
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));

            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
            isConfigured = true;
            Log.i(TAG, "Hardware H.264 decoder started (" + width + "x" + height + ")");
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec init error: " + e.getMessage());
            isConfigured = false;
        }
    }

    public synchronized void decodeFrame(byte[] data) {
        if (!enabled || data == null || data.length < 5) return;

        if (H264Utils.isSequenceHeader(data)) {
            init(data);
            return;
        }

        if (!isConfigured || mediaCodec == null) {
            if (cachedHeader != null && surface != null && surface.isValid()) {
                init(cachedHeader);
            }
            if (!isConfigured || mediaCodec == null) return;
        }

        int offset = 5; // skip the FLV video tag header
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
            } catch (Exception ignored) {}
            try {
                mediaCodec.release();
            } catch (Exception ignored) {}
            mediaCodec = null;
        }
    }
}
