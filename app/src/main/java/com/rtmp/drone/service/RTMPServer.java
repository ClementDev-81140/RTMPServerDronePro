package com.rtmp.drone.service;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

public class RTMPServer {
    private static final String TAG = "RTMPServer";
    private final int port;
    private ServerSocketChannel serverChannel;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private StreamCallback callback;
    private int connectionCount = 0;

    public interface StreamCallback {
        void onStreamStarted(byte[] videoHeader, byte[] audioHeader);
        void onStreamData(byte[] data, int type, int timestamp);
        void onStreamStopped();
        void onError(String error);
    }

    public RTMPServer(int port, StreamCallback callback) {
        this.port = port;
        this.callback = callback;
        executorService = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(true);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.socket().bind(new InetSocketAddress(port));
        running = true;
        executorService.execute(this::acceptLoop);
        Log.i(TAG, "=== SERVER STARTED ON PORT " + port + " ===");
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel client = serverChannel.accept();
                if (client != null) {
                    client.configureBlocking(true);
                    client.socket().setTcpNoDelay(true);
                    client.socket().setReceiveBufferSize(256 * 1024);
                    client.socket().setSendBufferSize(256 * 1024);
                    client.socket().setSoTimeout(15000);
                    connectionCount++;
                    Log.i(TAG, "=== DRONE CONNECTED ===");
                    executorService.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "Accept error: " + e.getMessage());
            }
        }
    }

    private static class RtmpHeader {
        int csid;
        int fmt;
        int timestamp;
        int messageLength;
        int messageTypeId;
        int messageStreamId;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
    }

    private void handleClient(SocketChannel channel) {
        boolean streamStarted = false;
        boolean[] publishState = new boolean[]{false};
        byte[] videoHeader = null;
        byte[] audioHeader = null;

        try {
            InputStream in = new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
            OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);

            if (!performHandshake(in, out)) {
                Log.e(TAG, "Handshake failed");
                return;
            }
            Log.i(TAG, "✓ Handshake OK");

            sendWindowAckSize(out, 2500000);
            sendSetPeerBandwidth(out, 2500000, 2);
            sendSetChunkSize(out, 4096);
            sendStreamBegin(out, 0);

            int inChunkSize = 128;
            long totalBytesRead = 0;
            long lastAckSentBytes = 0;
            final long ackWindowSize = 2500000;

            Map<Integer, RtmpHeader> rtmpChannels = new HashMap<>();

            while (running && channel.isConnected()) {
                int firstByte = in.read();
                if (firstByte == -1) break;
                totalBytesRead++;

                int fmt = (firstByte >> 6) & 0x03;
                int csid = firstByte & 0x3F;

                if (csid == 0) {
                    int b2 = in.read();
                    if (b2 == -1) break;
                    totalBytesRead++;
                    csid = 64 + b2;
                } else if (csid == 1) {
                    int b2 = in.read();
                    int b3 = in.read();
                    if (b2 == -1 || b3 == -1) break;
                    totalBytesRead += 2;
                    csid = 64 + b2 + (b3 << 8);
                }

                RtmpHeader header = rtmpChannels.get(csid);
                if (header == null) {
                    header = new RtmpHeader();
                    header.csid = csid;
                    rtmpChannels.put(csid, header);
                }
                header.fmt = fmt;

                if (fmt == 0) {
                    byte[] h = new byte[11];
                    readFully(in, h);
                    totalBytesRead += 11;
                    header.timestamp = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
                    header.messageLength = ((h[3] & 0xFF) << 16) | ((h[4] & 0xFF) << 8) | (h[5] & 0xFF);
                    header.messageTypeId = h[6] & 0xFF;
                    header.messageStreamId = (h[7] & 0xFF) | ((h[8] & 0xFF) << 8) | ((h[9] & 0xFF) << 16) | ((h[10] & 0xFF) << 24);
                    header.payload.reset();
                } else if (fmt == 1) {
                    byte[] h = new byte[7];
                    readFully(in, h);
                    totalBytesRead += 7;
                    int delta = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
                    header.timestamp += delta;
                    header.messageLength = ((h[3] & 0xFF) << 16) | ((h[4] & 0xFF) << 8) | (h[5] & 0xFF);
                    header.messageTypeId = h[6] & 0xFF;
                    header.payload.reset();
                } else if (fmt == 2) {
                    byte[] h = new byte[3];
                    readFully(in, h);
                    totalBytesRead += 3;
                    int delta = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
                    header.timestamp += delta;
                    header.payload.reset();
                }

                int bytesToRead = Math.min(inChunkSize, header.messageLength - header.payload.size());
                byte[] chunkBytes = new byte[bytesToRead];
                readFully(in, chunkBytes);
                totalBytesRead += bytesToRead;
                header.payload.write(chunkBytes);

                if (totalBytesRead - lastAckSentBytes >= (ackWindowSize / 2)) {
                    sendAck(out, (int) totalBytesRead);
                    lastAckSentBytes = totalBytesRead;
                }

                if (header.payload.size() >= header.messageLength) {
                    byte[] completeBody = header.payload.toByteArray();
                    header.payload.reset();

                    switch (header.messageTypeId) {
                        case 1:
                            inChunkSize = ((completeBody[0] & 0xFF) << 24) |
                                          ((completeBody[1] & 0xFF) << 16) |
                                          ((completeBody[2] & 0xFF) << 8)  |
                                          (completeBody[3] & 0xFF);
                            Log.i(TAG, "DJI chunk size updated: " + inChunkSize);
                            break;

                        case 20:
                        case 17:
                            handleAmfCommand(completeBody, out, publishState);
                            break;

                        case 18:
                            break;

                        case 9:
                            if (completeBody.length > 1) {
                                boolean isSequenceHeader = (completeBody[0] == 0x17 && completeBody[1] == 0x00);
                                if (isSequenceHeader) {
                                    videoHeader = completeBody;
                                    Log.i(TAG, "AVC config header (SPS/PPS) received (" + completeBody.length + " bytes) ★★★");
                                    if (!streamStarted) {
                                        streamStarted = true;
                                        callback.onStreamStarted(videoHeader, audioHeader != null ? audioHeader : new byte[0]);
                                    }
                                }
                                callback.onStreamData(completeBody, 9, header.timestamp);
                            }
                            break;

                        case 8:
                            if (completeBody.length > 1) {
                                boolean isAudioSeqHeader = ((completeBody[0] & 0xF0) == 0xA0 && completeBody[1] == 0x00);
                                if (isAudioSeqHeader) {
                                    audioHeader = completeBody;
                                    Log.i(TAG, "AAC audio sequence header received");
                                }
                                callback.onStreamData(completeBody, 8, header.timestamp);
                            }
                            break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Session ended: " + e.getMessage());
        } finally {
            try { channel.close(); } catch (Exception ignored) {}
            connectionCount--;
            callback.onStreamStopped();
            Log.i(TAG, "=== DRONE DISCONNECTED ===");
        }
    }

    private void handleAmfCommand(byte[] body, OutputStream out, boolean[] publishState) throws IOException {
        String str = new String(body, "ISO-8859-1");

        if (str.contains("connect")) {
            double txId = findTxId(body, "connect");
            Log.i(TAG, "→ Handling 'connect' (txId=" + txId + ")");
            sendConnectResponse(out, txId > 0 ? txId : 1.0);
            sendBWDone(out);
            sendStreamBegin(out, 1);
        }
        if (str.contains("releaseStream")) {
            double txId = findTxId(body, "releaseStream");
            Log.i(TAG, "→ Handling 'releaseStream' (txId=" + txId + ")");
            sendNullResult(out, txId > 0 ? txId : 2.0);
        }
        if (str.contains("FCPublish")) {
            double txId = findTxId(body, "FCPublish");
            Log.i(TAG, "→ Handling 'FCPublish' (txId=" + txId + ")");
            sendNullResult(out, txId > 0 ? txId : 3.0);
        }
        if (str.contains("createStream")) {
            double txId = findTxId(body, "createStream");
            Log.i(TAG, "→ Handling 'createStream' (txId=" + txId + ")");
            sendCreateStreamResponse(out, txId > 0 ? txId : 4.0);
        }
        if (str.contains("publish") && !publishState[0]) {
            publishState[0] = true;
            Log.i(TAG, "DJI publish received -> stream is live!");
            sendPublishResponse(out);
        }
    }

    private double findTxId(byte[] data, String cmdName) {
        try {
            String s = new String(data, "ISO-8859-1");
            int idx = s.indexOf(cmdName);
            if (idx != -1) {
                int markerIdx = idx + cmdName.length();
                for (int i = markerIdx; i < Math.min(markerIdx + 20, data.length - 8); i++) {
                    if (data[i] == 0x00) {
                        long bits = 0;
                        for (int j = 0; j < 8; j++) {
                            bits = (bits << 8) | (data[i + 1 + j] & 0xFFL);
                        }
                        double val = Double.longBitsToDouble(bits);
                        if (val >= 1.0 && val <= 100.0) return val;
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private boolean performHandshake(InputStream in, OutputStream out) throws IOException {
        byte[] c0c1 = new byte[1537];
        readFully(in, c0c1);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x03);

        byte[] s1 = new byte[1536];
        int t = (int) (System.currentTimeMillis() / 1000);
        s1[0] = (byte) (t >> 24); s1[1] = (byte) (t >> 16); s1[2] = (byte) (t >> 8); s1[3] = (byte) t;
        new Random().nextBytes(Arrays.copyOfRange(s1, 8, 1536));
        baos.write(s1);
        baos.write(c0c1, 1, 1536);

        out.write(baos.toByteArray());
        out.flush();

        byte[] c2 = new byte[1536];
        readFully(in, c2);
        return true;
    }

    private void sendWindowAckSize(OutputStream out, int ackSize) throws IOException {
        byte[] pkt = new byte[]{
            0x02, 0, 0, 0, 0, 0, 4, 0x05, 0, 0, 0, 0,
            (byte)((ackSize >> 24) & 0xFF),
            (byte)((ackSize >> 16) & 0xFF),
            (byte)((ackSize >> 8) & 0xFF),
            (byte)(ackSize & 0xFF)
        };
        out.write(pkt);
        out.flush();
    }

    private void sendSetPeerBandwidth(OutputStream out, int ackSize, int limitType) throws IOException {
        byte[] pkt = new byte[]{
            0x02, 0, 0, 0, 0, 0, 5, 0x06, 0, 0, 0, 0,
            (byte)((ackSize >> 24) & 0xFF),
            (byte)((ackSize >> 16) & 0xFF),
            (byte)((ackSize >> 8) & 0xFF),
            (byte)(ackSize & 0xFF),
            (byte)(limitType & 0xFF)
        };
        out.write(pkt);
        out.flush();
    }

    private void sendSetChunkSize(OutputStream out, int chunkSize) throws IOException {
        byte[] pkt = new byte[]{
            0x02, 0, 0, 0, 0, 0, 4, 0x01, 0, 0, 0, 0,
            (byte)((chunkSize >> 24) & 0xFF),
            (byte)((chunkSize >> 16) & 0xFF),
            (byte)((chunkSize >> 8) & 0xFF),
            (byte)(chunkSize & 0xFF)
        };
        out.write(pkt);
        out.flush();
    }

    private void sendAck(OutputStream out, int bytesReceived) throws IOException {
        byte[] pkt = new byte[]{
            0x02, 0, 0, 0, 0, 0, 4, 0x03, 0, 0, 0, 0,
            (byte)((bytesReceived >> 24) & 0xFF),
            (byte)((bytesReceived >> 16) & 0xFF),
            (byte)((bytesReceived >> 8) & 0xFF),
            (byte)(bytesReceived & 0xFF)
        };
        out.write(pkt);
        out.flush();
    }

    private void sendStreamBegin(OutputStream out, int streamId) throws IOException {
        byte[] pkt = new byte[]{
            0x02, 0, 0, 0, 0, 0, 6, 0x04, 0, 0, 0, 0,
            0, 0,
            (byte)((streamId >> 24) & 0xFF),
            (byte)((streamId >> 16) & 0xFF),
            (byte)((streamId >> 8) & 0xFF),
            (byte)(streamId & 0xFF)
        };
        out.write(pkt);
        out.flush();
    }

    private void sendBWDone(OutputStream out) throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "onBWDone");
        writeAMF0Number(amf, 0.0);
        amf.write(0x05);
        sendRtmpPacket(out, 3, 20, 0, amf.toByteArray());
    }

    private void sendConnectResponse(OutputStream out, double txId) throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "_result");
        writeAMF0Number(amf, txId);
        
        amf.write(0x03);
        writeAMF0Prop(amf, "fmsVer", "FMS/3,5,7,7009");
        writeAMF0Prop(amf, "capabilities", 31.0);
        writeAMF0Prop(amf, "mode", 1.0);
        amf.write(new byte[]{0, 0, 9});

        amf.write(0x03);
        writeAMF0Prop(amf, "level", "status");
        writeAMF0Prop(amf, "code", "NetConnection.Connect.Success");
        writeAMF0Prop(amf, "description", "Connection succeeded");
        writeAMF0Prop(amf, "objectEncoding", 0.0);
        amf.write(new byte[]{0, 0, 9});

        sendRtmpPacket(out, 3, 20, 0, amf.toByteArray());
    }

    private void sendNullResult(OutputStream out, double txId) throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "_result");
        writeAMF0Number(amf, txId);
        amf.write(0x05);
        amf.write(0x05);
        sendRtmpPacket(out, 3, 20, 0, amf.toByteArray());
    }

    private void sendCreateStreamResponse(OutputStream out, double txId) throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "_result");
        writeAMF0Number(amf, txId);
        amf.write(0x05);
        writeAMF0Number(amf, 1.0);
        sendRtmpPacket(out, 3, 20, 0, amf.toByteArray());
    }

    private void sendPublishResponse(OutputStream out) throws IOException {
        sendStreamBegin(out, 1);

        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        writeAMF0String(amf, "onStatus");
        writeAMF0Number(amf, 0.0);
        amf.write(0x05);
        amf.write(0x03);
        writeAMF0Prop(amf, "level", "status");
        writeAMF0Prop(amf, "code", "NetStream.Publish.Start");
        writeAMF0Prop(amf, "description", "Stream is now published");
        writeAMF0Prop(amf, "details", "live");
        writeAMF0Prop(amf, "clientid", "DJI");
        amf.write(new byte[]{0, 0, 9});

        sendRtmpPacket(out, 5, 20, 1, amf.toByteArray());
    }

    private void sendRtmpPacket(OutputStream out, int csid, int msgType, int msgStreamId, byte[] data) throws IOException {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(csid & 0x3F);
        pkt.write(new byte[]{0, 0, 0});
        pkt.write((data.length >> 16) & 0xFF);
        pkt.write((data.length >> 8) & 0xFF);
        pkt.write(data.length & 0xFF);
        pkt.write(msgType & 0xFF);
        pkt.write(msgStreamId & 0xFF);
        pkt.write((msgStreamId >> 8) & 0xFF);
        pkt.write((msgStreamId >> 16) & 0xFF);
        pkt.write((msgStreamId >> 24) & 0xFF);
        pkt.write(data);

        out.write(pkt.toByteArray());
        out.flush();
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
            if (r <= 0) throw new IOException("Connection closed during read");
            t += r;
        }
    }

    public void stop() {
        running = false;
        try { if (serverChannel != null) serverChannel.close(); } catch (Exception ignored) {}
        executorService.shutdown();
    }

    public int getConnectionCount() { return connectionCount; }
    public boolean isRunning() { return running; }
}
