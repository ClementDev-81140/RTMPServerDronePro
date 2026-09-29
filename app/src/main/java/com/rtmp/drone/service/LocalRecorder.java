package com.rtmp.drone.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the incoming drone stream (FLV H.264 / AAC tags forwarded by RTMPServer) to a MP4 file.
 *
 * <p>The drone already sends H.264 in AVCC format and AAC without ADTS, which is exactly what
 * {@link MediaMuxer} expects, so the recording is a straight copy: no re-encoding, no CPU burn.
 * The samples are written by a dedicated worker thread so the RTMP reader is never blocked.</p>
 */
public class LocalRecorder {

    private static final String TAG = "LocalRecorder";
    private static final String MIME_VIDEO = "video/avc";
    private static final String MIME_AUDIO = "audio/mp4a-latm";
    private static final String FOLDER_NAME = "RTMP Drone Recorder";
    private static final String FILE_PREFIX = "DroneRec_";

    private static final int QUEUE_CAPACITY = 300;
    private static final long AUDIO_CONFIG_TIMEOUT_MS = 1500L;
    private static final long DRAIN_TIMEOUT_MS = 4000L;

    private static final int[] AAC_SAMPLE_RATES = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    };

    /** Called from a background thread: do not touch the UI directly. */
    public interface Listener {
        void onRecordingStarted(String fileName, String location, int width, int height);

        /**
         * @param sizeBytes number of bytes written, 0 when the file was discarded
         * @param error     null on success, otherwise the reason of the failure
         */
        void onRecordingFinished(String fileName, String location, long sizeBytes, String error);
    }

    // ------------------------------------------------------------------- state

    private final Context context;
    private final Listener listener;
    private final boolean usePublicStorage;
    private final ArrayBlockingQueue<Sample> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private Thread writerThread;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile String currentFileName;
    private volatile String currentLocation;
    private volatile long captureStartMs;
    private volatile int segmentIndex;
    private final AtomicLong totalBytes = new AtomicLong(0);
    private volatile long droppedFrames;
    private long totalSamples;
    private long samplesInSegment;
    private String lastError;
    private boolean errorReported;

    // Written by the producer thread (RTMP reader), read by the writer thread.
    private volatile MediaFormat videoFormat;
    private volatile MediaFormat audioFormat;
    private volatile long videoConfigTimeMs;
    private volatile boolean restartRequested;
    /** True once at least one AAC frame has been received (a muxer track must not stay empty). */
    private volatile boolean audioSampleSeen;

    // Writer thread only.
    private MediaMuxer muxer;
    private Target target;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted;
    private long lastVideoPtsUs = -1;
    private long lastAudioPtsUs = -1;

    private static class Sample {
        final byte[] data;
        final long ptsUs;
        final int flags;
        final boolean video;

        Sample(byte[] data, long ptsUs, int flags, boolean video) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.video = video;
        }
    }

    public LocalRecorder(Context context, Listener listener, boolean usePublicStorage) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usePublicStorage = usePublicStorage;
    }

    // --------------------------------------------------------------- lifecycle

    public void start() {
        if (running) return;
        running = true;
        stopRequested = false;
        restartRequested = false;
        videoFormat = null;
        audioFormat = null;
        audioSampleSeen = false;
        droppedFrames = 0;
        totalSamples = 0;
        segmentIndex = 0;
        lastError = null;
        totalBytes.set(0);

        writerThread = new Thread(this::writerLoop, "local-recorder");
        writerThread.setPriority(Thread.NORM_PRIORITY - 1);
        writerThread.start();
        Log.i(TAG, "Recorder armed, waiting for the drone video header");
    }

    /** Asks the writer thread to finish the current file. Never blocks the caller. */
    public void stop() {
        if (stopRequested) return;
        stopRequested = true;
        Thread thread = writerThread;
        if (thread != null && thread.isAlive()) thread.interrupt();
    }

    public boolean isRunning() {
        return running && !stopRequested;
    }

    /** True as soon as a file is really being written. */
    public boolean isCapturing() {
        return currentFileName != null;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public long getCaptureDurationMs() {
        long start = captureStartMs;
        if (start <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - start);
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    // ------------------------------------------------------ producer entry points

    /** Receives a FLV video tag (RTMP message type 9) with its timestamp in milliseconds. */
    public void writeVideo(byte[] flvTag, int timestampMs) {
        if (!isRunning() || flvTag == null || flvTag.length < 6) return;

        if (H264Utils.isSequenceHeader(flvTag)) {
            handleVideoConfig(flvTag);
            return;
        }

        byte[] payload = H264Utils.videoPayload(flvTag);
        if (payload == null || payload.length == 0) return;

        long ptsUs = (timestampMs + H264Utils.compositionTimeOffsetMs(flvTag)) * 1000L;
        int flags = H264Utils.isKeyFrame(flvTag) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        enqueue(new Sample(payload, ptsUs, flags, true));
    }

    /** Receives a FLV audio tag (RTMP message type 8) with its timestamp in milliseconds. */
    public void writeAudio(byte[] flvTag, int timestampMs) {
        if (!isRunning() || flvTag == null || flvTag.length < 2) return;

        boolean isAac = (flvTag[0] & 0xF0) == 0xA0;
        if (!isAac) return; // only AAC is muxed

        if (flvTag[1] == 0x00) {
            handleAudioConfig(flvTag);
            return;
        }

        if (flvTag.length <= 2) return;
        byte[] payload = new byte[flvTag.length - 2];
        System.arraycopy(flvTag, 2, payload, 0, payload.length);
        audioSampleSeen = true;
        enqueue(new Sample(payload, timestampMs * 1000L, 0, false));
    }

    // ---------------------------------------------------------------- internals

    private void handleVideoConfig(byte[] flvTag) {
        byte[] csd = H264Utils.extractSpsPpsAnnexB(flvTag);
        if (csd == null || csd.length == 0) {
            Log.w(TAG, "Video sequence header received but SPS/PPS could not be parsed");
            return;
        }

        int width = 0;
        int height = 0;
        int[] dimensions = H264Utils.parseSpsDimensions(H264Utils.extractSps(flvTag));
        if (dimensions != null) {
            width = dimensions[0];
            height = dimensions[1];
        }

        MediaFormat current = videoFormat;
        if (current != null && java.util.Arrays.equals(csd, getCsd0(current))) {
            return; // same configuration, nothing to do
        }

        if (width <= 0 || height <= 0) {
            width = 1920;
            height = 1080;
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        videoFormat = format;
        videoConfigTimeMs = System.currentTimeMillis();

        if (muxerStarted) {
            // Stream resolution or encoder settings changed (e.g. a new flight session):
            // close the current file and start a new segment with the new configuration.
            restartRequested = true;
        }
        Log.i(TAG, "Video configuration " + width + "x" + height + " (" + csd.length + " bytes SPS/PPS)");
    }

    private void handleAudioConfig(byte[] flvTag) {
        if (flvTag.length < 4) return;
        int objectType = (flvTag[2] & 0xFF) >> 3;
        int frequencyIndex = ((flvTag[2] & 0x07) << 1) | ((flvTag[3] & 0xF8) >> 7);
        int channelConfig = (flvTag[3] >> 3) & 0x0F;
        if (objectType == 31 || frequencyIndex >= AAC_SAMPLE_RATES.length) return;

        int sampleRate = AAC_SAMPLE_RATES[frequencyIndex];
        int channels = (channelConfig >= 1 && channelConfig <= 7) ? channelConfig : 2;

        byte[] asc = new byte[flvTag.length - 2];
        System.arraycopy(flvTag, 2, asc, 0, asc.length);
        if (asc.length < 2) return;

        MediaFormat current = audioFormat;
        if (current != null
                && current.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate
                && current.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels) {
            return;
        }

        MediaFormat format = MediaFormat.createAudioFormat(MIME_AUDIO, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
        audioFormat = format;
        Log.i(TAG, "Audio configuration " + sampleRate + " Hz, " + channels + " channel(s)");
    }

    private static byte[] getCsd0(MediaFormat format) {
        try {
            ByteBuffer buffer = format.getByteBuffer("csd-0");
            if (buffer == null) return null;
            byte[] out = new byte[buffer.remaining()];
            buffer.duplicate().get(out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void enqueue(Sample sample) {
        if (!queue.offer(sample)) {
            droppedFrames++;
            if (droppedFrames % 60 == 1) {
                Log.w(TAG, "Recorder queue full, " + droppedFrames + " frame(s) dropped so far");
            }
        }
    }

    private void writerLoop() {
        long drainDeadline = Long.MAX_VALUE;
        try {
            while (true) {
                if (stopRequested) {
                    if (drainDeadline == Long.MAX_VALUE) drainDeadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
                    if (queue.isEmpty() || System.currentTimeMillis() > drainDeadline) break;
                }

                if (!muxerStarted) {
                    if (videoFormat == null) {
                        if (stopRequested) break; // nothing was received, nothing to write
                        idleSleep(80);
                        continue;
                    }
                    boolean audioReady = audioFormat != null && audioSampleSeen;
                    boolean waitedEnough = (System.currentTimeMillis() - videoConfigTimeMs) >= AUDIO_CONFIG_TIMEOUT_MS;
                    if (audioReady || waitedEnough || stopRequested) {
                        if (!startSegment()) break;
                    } else {
                        idleSleep(60);
                    }
                    continue;
                }

                if (restartRequested) {
                    restartRequested = false;
                    closeSegment();
                    continue;
                }

                Sample sample;
                try {
                    sample = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue;
                }
                if (sample == null) continue;
                if (!writeSample(sample)) break;
            }
        } catch (Throwable t) {
            lastError = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
            Log.e(TAG, "Recorder error: " + lastError);
        } finally {
            closeSegment();
            queue.clear();
            running = false;
            Log.i(TAG, "Recorder stopped (" + totalSamples + " samples, "
                    + totalBytes.get() / 1024 + " KB, " + droppedFrames + " dropped)");
        }
    }

    private boolean startSegment() {
        MediaFormat vf = videoFormat;
        if (vf == null) return false;

        try {
            segmentIndex++;
            target = createTarget();
            if (target == null) {
                lastError = "Storage unavailable";
                return false;
            }
            muxer = target.createMuxer();
            videoTrack = muxer.addTrack(vf);
            // An audio track is only added when the audio configuration AND at least one
            // AAC frame are available, so the MP4 never contains an empty audio track.
            MediaFormat af = (audioFormat != null && audioSampleSeen) ? audioFormat : null;
            audioTrack = (af != null) ? muxer.addTrack(af) : -1;
            muxer.start();

            muxerStarted = true;
            samplesInSegment = 0;
            lastVideoPtsUs = -1;
            lastAudioPtsUs = -1;
            captureStartMs = System.currentTimeMillis();
            currentFileName = target.displayName;
            currentLocation = target.displayPath;
            restartRequested = false;

            Log.i(TAG, "Recording started -> " + target.displayPath);
            if (listener != null) {
                listener.onRecordingStarted(target.displayName, target.displayPath,
                        vf.getInteger(MediaFormat.KEY_WIDTH), vf.getInteger(MediaFormat.KEY_HEIGHT));
            }
            return true;
        } catch (Exception e) {
            lastError = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "Unable to start the recording: " + lastError);
            discardTarget();
            muxerStarted = false;
            return false;
        }
    }

    private void closeSegment() {
        if (muxer != null) {
            try {
                muxer.stop();
            } catch (Exception e) {
                if (lastError == null) lastError = (e.getMessage() != null) ? e.getMessage() : "MP4 finalisation failed";
            }
            try {
                muxer.release();
            } catch (Exception ignored) {}
            muxer = null;
        }
        muxerStarted = false;
        videoTrack = -1;
        audioTrack = -1;
        captureStartMs = 0;

        Target finished = target;
        target = null;
        currentFileName = null;
        currentLocation = null;

        if (finished != null) {
            if (samplesInSegment > 0 && lastError == null) {
                long size = finished.size();
                totalBytes.addAndGet(size);
                finished.release();
                reportFinished(finished.displayName, finished.displayPath, size, null);
            } else {
                String error = lastError;
                finished.discard();
                if (samplesInSegment > 0 && !errorReported) {
                    reportFinished(finished.displayName, finished.displayPath, 0,
                            error != null ? error : "No sample could be written");
                }
            }
        } else if (lastError != null && !errorReported) {
            // The recording never started (storage error, unsupported format...)
            reportFinished(null, null, 0, lastError);
        }

        samplesInSegment = 0;
        lastError = null;
        errorReported = false;
    }

    private void reportFinished(String fileName, String location, long sizeBytes, String error) {
        errorReported = (error != null);
        if (listener != null) {
            listener.onRecordingFinished(fileName, location, sizeBytes, error);
        }
    }

    private boolean writeSample(Sample sample) {
        if (muxer == null || !muxerStarted) return true;
        if (sample.video && videoTrack < 0) return true;
        if (!sample.video && audioTrack < 0) return true;
        if (sample.data.length == 0) return true;

        try {
            long ptsUs = normalizeTimestamp(sample.ptsUs, sample.video);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, sample.data.length, ptsUs, sample.flags);
            muxer.writeSampleData(sample.video ? videoTrack : audioTrack, ByteBuffer.wrap(sample.data), info);
            samplesInSegment++;
            totalSamples++;
            return true;
        } catch (Exception e) {
            lastError = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "writeSampleData failed: " + lastError);
            return false;
        }
    }

    /** MediaMuxer requires strictly increasing timestamps on each track. */
    private long normalizeTimestamp(long ptsUs, boolean video) {
        long last = video ? lastVideoPtsUs : lastAudioPtsUs;
        if (ptsUs <= last) ptsUs = last + 1000L; // +1 ms
        if (video) lastVideoPtsUs = ptsUs;
        else lastAudioPtsUs = ptsUs;
        return ptsUs;
    }

    private void discardTarget() {
        if (muxer != null) {
            try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
        }
        if (target != null) {
            target.discard();
            target = null;
        }
        muxerStarted = false;
        currentFileName = null;
        currentLocation = null;
    }

    private void idleSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {}
    }

    private String buildFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String suffix = (segmentIndex > 1) ? "_p" + segmentIndex : "";
        return FILE_PREFIX + stamp + suffix + ".mp4";
    }

    // ------------------------------------------------------------------ storage

    private Target createTarget() {
        String fileName = buildFileName();
        if (usePublicStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Target publicTarget = createMediaStoreTarget(fileName);
                if (publicTarget != null) return publicTarget;
            } catch (Exception e) {
                Log.w(TAG, "Public storage unavailable (" + e.getMessage() + "), using app storage");
            }
        }
        return createPrivateTarget(fileName);
    }

    private Target createPrivateTarget(String fileName) {
        File directory = getPrivateRecordingsDir(context);
        if (directory == null) return null;
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Cannot create " + directory.getAbsolutePath());
            return null;
        }
        File file = new File(directory, fileName);
        Target t = new Target(fileName, file.getAbsolutePath());
        t.file = file;
        return t;
    }

    private Target createMediaStoreTarget(String fileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
        if (pfd == null) {
            resolver.delete(uri, null, null);
            return null;
        }
        Target t = new Target(fileName, Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME + "/" + fileName);
        t.uri = uri;
        t.pfd = pfd;
        return t;
    }

    /** One recording destination: either a plain file or a MediaStore entry. */
    private class Target {
        final String displayName;
        final String displayPath;
        File file;
        Uri uri;
        ParcelFileDescriptor pfd;

        Target(String displayName, String displayPath) {
            this.displayName = displayName;
            this.displayPath = displayPath;
        }

        MediaMuxer createMuxer() throws IOException {
            if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            return new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        long size() {
            try {
                if (file != null) return file.length();
                if (uri != null) {
                    Cursor cursor = context.getContentResolver().query(uri,
                            new String[]{MediaStore.Video.Media.SIZE}, null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) return cursor.getLong(0);
                        } finally {
                            cursor.close();
                        }
                    }
                }
            } catch (Exception ignored) {}
            return 0;
        }

        /** Makes the recording visible in the gallery once the MP4 is complete. */
        void release() {
            if (uri != null) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                } catch (Exception ignored) {}
            }
            closeDescriptor();
        }

        void discard() {
            closeDescriptor();
            try {
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                } else if (uri != null) {
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ignored) {}
        }

        private void closeDescriptor() {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {}
                pfd = null;
            }
        }
    }

    // ------------------------------------------------------------- static helpers

    public static File getPrivateRecordingsDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(context.getFilesDir(), "movies");
        return new File(base, FOLDER_NAME);
    }

    public static boolean isPublicStorageSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Human readable location of the recordings, for the Configuration screen. */
    public static String getLocationLabel(Context context, boolean usePublicStorage) {
        if (usePublicStorage && isPublicStorageSupported()) {
            return Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME;
        }
        File dir = getPrivateRecordingsDir(context);
        return dir != null ? dir.getAbsolutePath() : "-";
    }

    /** {count, totalSizeInBytes} of the recordings already stored on the device. */
    public static long[] getRecordingsSummary(Context context, boolean usePublicStorage) {
        long count = 0;
        long bytes = 0;
        if (usePublicStorage && isPublicStorageSupported()) {
            try {
                String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
                String[] args = new String[]{"%" + FOLDER_NAME + "%"};
                Cursor cursor = context.getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Video.Media.SIZE}, selection, args, null);
                if (cursor != null) {
                    try {
                        while (cursor.moveToNext()) {
                            count++;
                            bytes += cursor.getLong(0);
                        }
                    } finally {
                        cursor.close();
                    }
                }
                return new long[]{count, bytes};
            } catch (Exception ignored) {}
        }

        File dir = getPrivateRecordingsDir(context);
        File[] files = (dir != null && dir.exists()) ? dir.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".mp4")) {
                    count++;
                    bytes += f.length();
                }
            }
        }
        return new long[]{count, bytes};
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
