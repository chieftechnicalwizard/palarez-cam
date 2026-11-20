package com.palarez.streamcam;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;

/**
 * MusicPcmPlayer — Streaming On-Device Audio Track Decoder &amp; Exact 44.1 kHz Mono Resampler.
 *
 * <p>Decodes local {@code .mp3}, {@code .wav}, {@code .m4a}, {@code .aac}, {@code .flac}, or
 * {@code .ogg} files staged in {@code files/tracks/} using Android {@link MediaExtractor} +
 * {@link MediaCodec}, downmixes multi-channel PCM ({@code ENCODING_PCM_16BIT} or
 * {@code ENCODING_PCM_FLOAT}) to mono, and performs fractional linear-interpolation resampling
 * ({@code step = srcSampleRate / 44100.0}) into {@code 44,100 Hz} mono samples.
 *
 * <p><b>Why resampling is mandatory:</b> Studio masters and modern codecs often decode at
 * {@code 48,000 Hz} while RTMP/FLV AAC runs at {@code 44,100 Hz} ({@code 1024} samples per frame).
 * Feeding unresampled {@code 48 kHz} PCM at {@code 44.1 kHz} stretches duration by {@code +8.84%}
 * and drops pitch by ~1.47 semitones; linear interpolation over {@link #srcRing} preserves exact
 * musical pitch and wall-clock track length.
 */
public final class MusicPcmPlayer {
    public static final int TARGET_SAMPLE_RATE = 44100;
    private static final int RING_CAPACITY = 65536;

    private MediaExtractor extractor;
    private MediaCodec codec;
    private MediaCodec.BufferInfo codecInfo = new MediaCodec.BufferInfo();

    private String trackName = "";
    private File currentFile;
    private int srcSampleRate = TARGET_SAMPLE_RATE;
    private int srcChannels = 1;
    private int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private double durationSec = 0.0;

    private volatile boolean loaded = false;
    private volatile boolean playing = false;
    private volatile boolean finished = false;
    private volatile boolean loop = false;

    private boolean extractorEos = false;
    private boolean codecEos = false;
    private long outputSamples = 0;

    private final float[] srcRing = new float[RING_CAPACITY];
    private int srcLen = 0;
    private double srcPos = 0.0;

    public synchronized boolean load(File file, boolean autoPlay, boolean shouldLoop) {
        closeInternal();
        if (file == null || !file.exists() || !file.isFile()) return false;
        try {
            MediaExtractor ex = new MediaExtractor();
            ex.setDataSource(file.getAbsolutePath());
            int audioIdx = -1;
            MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    audioIdx = i;
                    fmt = f;
                    mime = m;
                    break;
                }
            }
            if (audioIdx < 0 || fmt == null || mime == null) {
                ex.release();
                return false;
            }
            ex.selectTrack(audioIdx);

            srcSampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            srcChannels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING);
            } else {
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            }
            durationSec = fmt.containsKey(MediaFormat.KEY_DURATION)
                ? (fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0) : 0.0;

            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            this.extractor = ex;
            this.codec = dec;
            this.currentFile = file;
            this.trackName = file.getName();
            this.loop = shouldLoop;
            this.loaded = true;
            this.playing = autoPlay;
            this.finished = false;
            this.extractorEos = false;
            this.codecEos = false;
            this.outputSamples = 0;
            this.srcLen = 0;
            this.srcPos = 0.0;
            return true;
        } catch (Throwable t) {
            closeInternal();
            return false;
        }
    }

    public synchronized void setPlaying(boolean play) {
        if (!loaded) return;
        if (play && finished) {
            seekStartInternal();
        }
        this.playing = play;
        if (play) this.finished = false;
    }

    public synchronized void setLoop(boolean shouldLoop) {
        this.loop = shouldLoop;
    }

    public synchronized void seekStart() {
        if (!loaded) return;
        seekStartInternal();
    }

    public synchronized void stopAndUnload() {
        closeInternal();
    }

    public boolean isPlaying() { return playing && loaded; }
    public boolean isLoaded() { return loaded; }
    public boolean isFinished() { return finished; }
    public String getTrackName() { return trackName; }
    public double getDurationSec() { return durationSec; }
    public double getPositionSec() { return (double) outputSamples / TARGET_SAMPLE_RATE; }

    public synchronized JSONObject toStatusJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("loaded", loaded);
            j.put("playing", isPlaying());
            j.put("finished", finished);
            j.put("loop", loop);
            j.put("track", trackName);
            j.put("src_sample_rate", srcSampleRate);
            j.put("src_channels", srcChannels);
            j.put("position_sec", Math.round(getPositionSec() * 100.0) / 100.0);
            j.put("duration_sec", Math.round(durationSec * 100.0) / 100.0);
        } catch (Throwable ignored) {}
        return j;
    }

    /**
     * Produces up to out.length (typically 1024) 44.1 kHz mono float samples in [-32768, 32767].
     * Returns true if any audio samples were produced for this frame.
     */
    public synchronized boolean readMonoSamples(float[] out) {
        if (!loaded || !playing) return false;
        int produced = 0;

        while (produced < out.length) {
            // Ensure at least two source samples ahead of srcPos for linear interpolation
            int pumpAttempts = 0;
            while (srcPos + 1.0 >= srcLen && !codecEos && pumpAttempts < 12) {
                pumpDecoder();
                pumpAttempts++;
            }
            // Recompute step after pumping so INFO_OUTPUT_FORMAT_CHANGED updates take effect immediately
            double step = (double) srcSampleRate / (double) TARGET_SAMPLE_RATE;

            if (srcPos + 1.0 < srcLen) {
                int i0 = (int) srcPos;
                double frac = srcPos - i0;
                float s0 = srcRing[i0];
                float s1 = srcRing[i0 + 1];
                out[produced++] = (float) (s0 + (s1 - s0) * frac);
                srcPos += step;
                compactRingIfNeeded(false);
                continue;
            }

            if (srcPos < srcLen && codecEos) {
                int i0 = (int) srcPos;
                out[produced++] = srcRing[i0];
                srcPos += step;
                compactRingIfNeeded(false);
                continue;
            }

            if (!codecEos) {
                // Decoder warming up; avoid declaring premature EOF
                break;
            }

            // Reached true end of source stream (codecEos == true and ring drained)
            if (loop && currentFile != null) {
                seekStartInternal();
                if (!pumpDecoder() && codecEos && srcLen == 0) {
                    playing = false;
                    finished = true;
                    break;
                }
            } else {
                playing = false;
                finished = true;
                break;
            }
        }

        for (int i = produced; i < out.length; i++) {
            out[i] = 0f;
        }
        outputSamples += produced;
        return produced > 0;
    }

    private void compactRingIfNeeded(boolean force) {
        int drop = (int) srcPos;
        if (drop >= 8192 || (force && drop > 0 && srcLen > RING_CAPACITY / 2)) {
            int rem = srcLen - drop;
            if (rem > 0) {
                System.arraycopy(srcRing, drop, srcRing, 0, rem);
            } else {
                rem = 0;
            }
            srcLen = rem;
            srcPos -= drop;
        }
    }

    private void seekStartInternal() {
        try {
            if (extractor != null) extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            if (codec != null) codec.flush();
            extractorEos = false;
            codecEos = false;
            finished = false;
            outputSamples = 0;
            srcLen = 0;
            srcPos = 0.0;
        } catch (Throwable t) {
            closeInternal();
        }
    }

    private boolean pumpDecoder() {
        if (codec == null || extractor == null || codecEos) return false;
        boolean addedSamples = false;

        // Compact first if ring is close to full
        compactRingIfNeeded(true);
        if (srcLen > RING_CAPACITY - 8192) return false;

        for (int iter = 0; iter < 24 && !codecEos && srcLen < RING_CAPACITY - 8192; iter++) {
            if (!extractorEos) {
                int inIdx = codec.dequeueInputBuffer(2000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorEos = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, sampleSize, Math.max(0L, pts), 0);
                            extractor.advance();
                        }
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(codecInfo, 3000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    srcSampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    srcChannels = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
                continue;
            }
            if (outIdx < 0) {
                continue;
            }

            try {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && codecInfo.size > 0) {
                    outBuf.position(codecInfo.offset);
                    outBuf.limit(codecInfo.offset + codecInfo.size);
                    appendDecodedBuffer(outBuf);
                    addedSamples = true;
                }
                if ((codecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    codecEos = true;
                }
            } finally {
                codec.releaseOutputBuffer(outIdx, false);
            }
            if (addedSamples && (srcLen - srcPos) >= 4096.0) {
                break;
            }
        }
        return addedSamples;
    }

    private void appendDecodedBuffer(ByteBuffer bb) {
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int ch = Math.max(1, srcChannels);
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            int frames = bb.remaining() / (4 * ch);
            for (int f = 0; f < frames && srcLen < RING_CAPACITY; f++) {
                float sum = 0f;
                for (int c = 0; c < ch; c++) sum += bb.getFloat();
                srcRing[srcLen++] = Math.max(-32768f, Math.min(32767f, (sum / ch) * 32767f));
            }
        } else {
            int frames = bb.remaining() / (2 * ch);
            for (int f = 0; f < frames && srcLen < RING_CAPACITY; f++) {
                float sum = 0f;
                for (int c = 0; c < ch; c++) sum += bb.getShort();
                srcRing[srcLen++] = sum / ch;
            }
        }
    }

    private void closeInternal() {
        playing = false;
        loaded = false;
        finished = false;
        trackName = "";
        currentFile = null;
        durationSec = 0.0;
        outputSamples = 0;
        srcLen = 0;
        srcPos = 0.0;
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            codec = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (Throwable ignored) {}
            extractor = null;
        }
    }
}
