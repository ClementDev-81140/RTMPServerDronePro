package com.rtmp.drone.service;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TelegramStreamer {
    private static final String TAG = "TelegramStreamer";
    private static final int CHUNK_SIZE = 4096;

    private static final byte[] AAC_SEQ_HEADER = new byte[]{(byte) 0xAF, 0x00, 0x12, 0x10};
    private byte[] aacSilenceTagBody;

    private final String url;
    private final String streamKey;
    private final int targetBitrate;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private volatile boolean streaming = false;
    private volatile boolean droneHasAudio = false;

    private AtomicLong bytesSent = new AtomicLong(0);
    private AtomicInteger videoFramesSent = new AtomicInteger(0);
    private AtomicInteger audioFramesSent = new AtomicInteger(0);
    private long startTime = 0;
    private long lastFrameTime = 0;

    private byte[] cachedVideoHeader;
    private byte[] cachedAudioHeader;
    
    private long djiTimestampOffset = -1;
    private long lastAudioTimestamp = 0;
    
    private ScheduledExecutorService logExecutor;
    private ExecutorService networkReaderExecutor;

    public TelegramStreamer(String url, String streamKey, int targetBitrate) {
        this.url = url;
        this.streamKey = streamKey;
        this.targetBitrate = targetBitrate;
        this.aacSilenceTagBody = generateHardwareAacSilence();
    }

    private byte[] generateHardwareAacSilence() {
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            int inIdx = encoder.dequeueInputBuffer(100000);
            if (inIdx >= 0) {
                ByteBuffer buf = encoder.getInputBuffer(inIdx);
                if (buf != null) {
                    buf.clear();
                    buf.put(new byte[4096]);
                    encoder.queueInputBuffer(inIdx, 0, 4096, 0, 0);
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeout = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < timeout) {
                int outIdx = encoder.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(outIdx, false);
                        continue;
                    }
                    byte[] rawAac = new byte[info.size];
                    if (outBuf != null) {
                        outBuf.position(info.offset);
                        outBuf.get(rawAac);
                    }
                    encoder.releaseOutputBuffer(outIdx, false);
                    encoder.stop();
                    encoder.release();

                    byte[] flvAudio = new byte[2 + rawAac.length];
                    flvAudio[0] = (byte) 0xAF;
                    flvAudio[1] = 0x01;
                    System.arraycopy(rawAac, 0, flvAudio, 2, rawAac.length);
                    return flvAudio;
                }
            }
            encoder.stop();
            encoder.release();
        } catch (Exception ignored) {}
        return new byte[]{(byte) 0xAF, 0x01, 0x21, 0x10, 0x04, 0x60, (byte) 0x8C, 0x1C};
    }

    public void connect() throws Exception {
        String cleanUrl = url.trim();
        if (!cleanUrl.startsWith("rtmp://") && !cleanUrl.startsWith("rtmps://")) {
            cleanUrl = "rtmps://" + cleanUrl;
        }

        boolean isRtmps = cleanUrl.startsWith("rtmps://");
        String withoutProto = cleanUrl.substring(cleanUrl.indexOf("://") + 3);
        String host;
        int port = isRtmps ? 443 : 1935;
        String app = "s";

        int slashIdx = withoutProto.indexOf('/');
        String hostPort = (slashIdx != -1) ? withoutProto.substring(0, slashIdx) : withoutProto;
        if (slashIdx != -1 && slashIdx + 1 < withoutProto.length()) {
            app = withoutProto.substring(slashIdx + 1);
            if (app.endsWith("/")) app = app.substring(0, app.length() - 1);
            if (app.isEmpty()) app = "s";
        }

        if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":");
            host = parts[0];
            try { port = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        } else {
            host = hostPort;
        }

        String tcUrl = cleanUrl.endsWith("/") ? cleanUrl.substring(0, cleanUrl.length() - 1) : cleanUrl;
        Log.i(TAG, "[1/6] Connecting to " + host + ":" + port + (isRtmps ? " over TLS" : "") + "...");

        if (isRtmps) {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());

            SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket();
            try {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(Collections.singletonList(new SNIHostName(host)));
                sslSocket.setSSLParameters(params);
            } catch (Exception ignored) {}

            sslSocket.connect(new InetSocketAddress(host, port), 10000);
            sslSocket.startHandshake();
            sslSocket.setTcpNoDelay(true);
            socket = sslSocket;
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setTcpNoDelay(true);
        }

        socket.setSoTimeout(5000);
        out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);

        performHandshake();
        Log.i(TAG, "[1/6] RTMP handshake completed");

        sendSetChunkSize(CHUNK_SIZE);
        sendConnect(app, tcUrl);
        Log.i(TAG, "[2/6] Waiting for the 'connect' reply...");
        waitForResponse("_result", 4000);
        Log.i(TAG, "[2/6] Connect accepted by the server");

        sendReleaseStream(streamKey);
        sendFCPublish(streamKey);

        sendCreateStream();
        Log.i(TAG, "[3/6] Waiting for the stream allocation (createStream)...");
        waitForResponse("_result", 4000);
        Log.i(TAG, "[3/6] Stream allocated (Stream ID = 1)");

        sendPublish(streamKey);
        Log.i(TAG, "[4/6] Waiting for the publish authorisation...");
        waitForResponse("Publish.Start", 4000);
        Log.i(TAG, "[4/6] Publication confirmed by the server");

        streaming = true;
        startTime = System.currentTimeMillis();
        lastFrameTime = startTime;

        sendMetaData();

        if (cachedAudioHeader != null && cachedAudioHeader.length > 0) {
            Log.i(TAG, "[5/6] Sending the real AAC sequence header of the drone (" + cachedAudioHeader.length + " bytes)");
            sendRtmpPacket(5, 0x08, 1, 0, cachedAudioHeader);
            droneHasAudio = true;
        } else {
            Log.i(TAG, "[5/6] No drone audio detected, starting the AAC silence track...");
            sendRtmpPacket(5, 0x08, 1, 0, AAC_SEQ_HEADER);
            droneHasAudio = false;
        }

        if (cachedVideoHeader != null && cachedVideoHeader.length > 0) {
            sendRtmpPacket(4, 0x09, 1, 0, cachedVideoHeader);
            Log.i(TAG, "[6/6] H.264 sequence header (SPS/PPS) forwarded (" + cachedVideoHeader.length + " bytes)");
        }

        startIncomingPacketConsumer();
        startStatsLogger();

        Log.i(TAG, "=== DESTINATION IS NOW LIVE ===");
    }

    private void waitForResponse(String expected, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[1024];
        socket.setSoTimeout(timeoutMs);
        try {
            while (System.currentTimeMillis() < end) {
                int r = in.read(buf);
                if (r <= 0) throw new IOException("Connection closed by server");
                String s = new String(buf, 0, r, "ISO-8859-1");
                if (s.contains(expected)) return;
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Timeout waiting for RTMP response: " + expected);
        } finally {
            socket.setSoTimeout(5000);
        }
    }

    private void startIncomingPacketConsumer() {
        networkReaderExecutor = Executors.newSingleThreadExecutor();
        networkReaderExecutor.execute(() -> {
            byte[] buf = new byte[4096];
            try {
                while (streaming && in != null) {
                    int r = in.read(buf);
                    if (r <= 0) break;
                }
            } catch (Exception ignored) {}
        });
    }

    private void performHandshake() throws IOException {
        byte[] c0c1 = new byte[1537];
        c0c1[0] = 0x03;
        int t = (int)(System.currentTimeMillis() / 1000);
        c0c1[1] = (byte)(t >> 24); c0c1[2] = (byte)(t >> 16); c0c1[3] = (byte)(t >> 8); c0c1[4] = (byte)t;
        out.write(c0c1);
        out.flush();

        byte[] s0s1s2 = new byte[3073];
        readFully(in, s0s1s2);

        byte[] c2 = new byte[1536];
        System.arraycopy(s0s1s2, 1, c2, 0, 1536);
        out.write(c2);
        out.flush();
    }

    private void sendConnect(String app, String tcUrl) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeAMF0String(b, "connect");
        writeAMF0Number(b, 1.0);
        b.write(0x03);
        writeAMF0Prop(b, "app", app);
        writeAMF0Prop(b, "flashVer", "FMLE/3.0 (compatible; Lavf58.29.100)");
        writeAMF0Prop(b, "tcUrl", tcUrl);
        writeAMF0Prop(b, "type", "nonprivate");
        b.write(new byte[]{0, 0, 9});

        sendRtmpPacket(3, 0x14, 0, 0, b.toByteArray());
    }

    private void sendReleaseStream(String key) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeAMF0String(b, "releaseStream");
        writeAMF0Number(b, 2.0);
        b.write(0x05);
        writeAMF0String(b, key.trim());
        sendRtmpPacket(3, 0x14, 0, 0, b.toByteArray());
    }

    private void sendFCPublish(String key) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeAMF0String(b, "FCPublish");
        writeAMF0Number(b, 3.0);
        b.write(0x05);
        writeAMF0String(b, key.trim());
        sendRtmpPacket(3, 0x14, 0, 0, b.toByteArray());
    }

    private void sendCreateStream() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeAMF0String(b, "createStream");
        writeAMF0Number(b, 4.0);
        b.write(0x05);
        sendRtmpPacket(3, 0x14, 0, 0, b.toByteArray());
    }

    private void sendPublish(String key) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeAMF0String(b, "publish");
        writeAMF0Number(b, 5.0);
        b.write(0x05);
        writeAMF0String(b, key.trim());
        writeAMF0String(b, "live");
        sendRtmpPacket(8, 0x14, 1, 0, b.toByteArray());
    }

    private void sendSetChunkSize(int size) throws IOException {
        byte[] payload = new byte[]{
            (byte)((size >> 24) & 0xFF),
            (byte)((size >> 16) & 0xFF),
            (byte)((size >> 8) & 0xFF),
            (byte)(size & 0xFF)
        };
        sendRtmpPacket(2, 0x01, 0, 0, payload);
    }

    private void sendMetaData() throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "onMetaData");
        
        amf.write(0x08);
        amf.write(new byte[]{0, 0, 0, 8});

        writeAMF0Prop(amf, "width", 1280.0);
        writeAMF0Prop(amf, "height", 720.0);
        writeAMF0Prop(amf, "videocodecid", 7.0);
        writeAMF0Prop(amf, "videodatarate", (double)(targetBitrate > 0 ? targetBitrate / 1000 : 2500));
        writeAMF0Prop(amf, "framerate", 30.0);
        writeAMF0Prop(amf, "audiocodecid", 10.0);
        writeAMF0Prop(amf, "audiodatarate", 64.0);
        writeAMF0Prop(amf, "audiosamplerate", 44100.0);
        
        amf.write(new byte[]{0, 0, 9});
        sendRtmpPacket(4, 0x12, 1, 0, amf.toByteArray());
    }

    private void startStatsLogger() {
        logExecutor = Executors.newSingleThreadScheduledExecutor();
        logExecutor.scheduleAtFixedRate(() -> {
            if (streaming) {
                Log.i(TAG, "Stats -> sent: " + (bytesSent.get() / 1024) + " KB | video: " + videoFramesSent.get() + " | audio: " + audioFramesSent.get() + " | bitrate: " + getBitrate() + " kbps");
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    public synchronized void sendData(byte[] data, int type, int djiTimestamp) {
        if (!streaming || data == null || data.length == 0 || out == null) return;

        try {
            if (djiTimestampOffset == -1) {
                djiTimestampOffset = djiTimestamp;
                lastAudioTimestamp = 0;
            }

            int relativeTimestamp = (int) (djiTimestamp - djiTimestampOffset);
            if (relativeTimestamp < 0) relativeTimestamp = 0;

            int csid = (type == 9) ? 4 : (type == 8 ? 5 : 6);
            int streamId = 1;

            if (type == 8) {
                droneHasAudio = true;
                audioFramesSent.incrementAndGet();
                sendRtmpPacket(csid, type, streamId, relativeTimestamp, data);
            } else if (type == 9) {
                videoFramesSent.incrementAndGet();
                lastFrameTime = System.currentTimeMillis();

                if (!droneHasAudio && aacSilenceTagBody != null) {
                    while (lastAudioTimestamp + 23 < relativeTimestamp) {
                        lastAudioTimestamp += 23;
                        sendRtmpPacket(5, 0x08, 1, (int) lastAudioTimestamp, aacSilenceTagBody);
                        audioFramesSent.incrementAndGet();
                    }
                }

                sendRtmpPacket(csid, type, streamId, relativeTimestamp, data);
            }
            bytesSent.addAndGet(data.length);
        } catch (Exception e) {
            Log.e(TAG, "Send error: " + e.getMessage());
            stop();
        }
    }

    private synchronized void sendRtmpPacket(int csid, int msgType, int streamId, int timestamp, byte[] payload) throws IOException {
        int length = payload.length;

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write((csid & 0x3F));
        chunk.write((timestamp >> 16) & 0xFF);
        chunk.write((timestamp >> 8) & 0xFF);
        chunk.write(timestamp & 0xFF);
        chunk.write((length >> 16) & 0xFF);
        chunk.write((length >> 8) & 0xFF);
        chunk.write(length & 0xFF);
        chunk.write(msgType & 0xFF);
        chunk.write(streamId & 0xFF);
        chunk.write((streamId >> 8) & 0xFF);
        chunk.write((streamId >> 16) & 0xFF);
        chunk.write((streamId >> 24) & 0xFF);

        int pos = 0;
        int firstChunk = Math.min(length, CHUNK_SIZE);
        chunk.write(payload, 0, firstChunk);
        pos += firstChunk;

        out.write(chunk.toByteArray());

        while (pos < length) {
            int chunkSize = Math.min(length - pos, CHUNK_SIZE);
            out.write(0xC0 | (csid & 0x3F));
            out.write(payload, pos, chunkSize);
            pos += chunkSize;
        }

        out.flush();
    }

    public void startStream(byte[] videoHeader, byte[] audioHeader) {
        this.cachedVideoHeader = videoHeader;
        this.cachedAudioHeader = audioHeader;
    }

    private void writeAMF0String(ByteArrayOutputStream b, String s) throws IOException {
        b.write(0x02);
        byte[] bs = s.getBytes("UTF-8");
        b.write((bs.length >> 8) & 0xFF);
        b.write(bs.length & 0xFF);
        b.write(bs);
    }

    private void writeAMF0Number(ByteArrayOutputStream b, double n) throws IOException {
        b.write(0x00);
        long bits = Double.doubleToLongBits(n);
        for (int i = 7; i >= 0; i--) b.write((int)(bits >> (i * 8)) & 0xFF);
    }

    private void writeAMF0Prop(ByteArrayOutputStream b, String k, Object v) throws IOException {
        byte[] kb = k.getBytes("UTF-8");
        b.write((kb.length >> 8) & 0xFF);
        b.write(kb.length & 0xFF);
        b.write(kb);
        if (v instanceof String) writeAMF0String(b, (String)v);
        else if (v instanceof Number) writeAMF0Number(b, ((Number)v).doubleValue());
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int t = 0;
        while (t < buf.length) {
            int r = in.read(buf, t, buf.length - t);
            if (r <= 0) throw new IOException("Connection closed");
            t += r;
        }
    }

    public void stop() {
        streaming = false;
        if (logExecutor != null) logExecutor.shutdownNow();
        if (networkReaderExecutor != null) networkReaderExecutor.shutdownNow();
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        Log.i(TAG, "Streamer stopped.");
    }

    public boolean isStreaming() { return streaming; }
    public long getBytesSent() { return bytesSent.get(); }
    public int getBitrate() {
        long duration = (System.currentTimeMillis() - startTime) / 1000;
        if (duration == 0) return 0;
        return (int)((bytesSent.get() * 8) / duration / 1000);
    }
    public int getLatency() { return (int)(System.currentTimeMillis() - lastFrameTime); }
}
