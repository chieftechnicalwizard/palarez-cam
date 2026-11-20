package com.palarez.streamcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * StreamService — Persistent Foreground Engine for Camera2 + Sanctuary Glass OpenGL HUD +
 * Shared Hardware H.264/AAC Encoders (YouTube RTMP/RTMPS Push + LAN HTTP-FLV Pull) +
 * On-Device PCM Audio Mixer + NsdManager mDNS Discovery + 9 Priority Sensors + HTTP API (:8890).
 *
 * <h3>Pipeline &amp; Resource Discipline</h3>
 * <ul>
 *   <li><b>Single-Encode Fan-Out</b>: Hardware {@link MediaCodec} H.264 ({@code COLOR_FormatSurface},
 *       CBR) and AAC-LC ({@code 44.1 kHz} mono) start on demand only when outbound RTMP/RTMPS or at
 *       least one LAN {@code GET /stream.flv} client is active, and shut down automatically via
 *       {@link #releaseEncodersIfIdle()} when idle to keep phone thermals low during standby.</li>
 *   <li><b>Privacy-Safe Audio Clock</b>: When {@code audio_enabled == false}, {@link AudioRecord} is
 *       physically stopped and released (clearing Android's microphone indicator) while a monotonic
 *       {@code 23.22 ms} ({@code 1024 / 44100 s}) deadline clock continues feeding either
 *       {@link MusicPcmPlayer} audio or zero-filled silent PCM so YouTube Live RTMP and {@code go2rtc}
 *       receive uninterrupted audio timestamps.</li>
 *   <li><b>mDNS Zero-Config Discovery</b>: Advertises {@code _palarez-cam._tcp.} via {@link NsdManager}
 *       with TXT attributes ({@code station}, {@code model}, {@code api=2.0}, {@code flv=/stream.flv},
 *       {@code status=/api/status}).</li>
 * </ul>
 */
public final class StreamService extends Service implements LocalPhoneHttpServer.Delegate {
    public static final String ACTION_SETTINGS_SYNC = "com.palarez.streamcam.SETTINGS_SYNC";
    private static final String CHANNEL_ID = "palarez_streamcam_fg";
    private static final int NOTIF_ID = 8891;
    private static final int AUDIO_SAMPLE_RATE = 44100;

    public final class LocalBinder extends Binder {
        public StreamService getService() { return StreamService.this; }
    }

    private static final class FlvTag {
        final int tagType; // 8 = audio, 9 = video
        final int timestampMs;
        final byte[] data;
        final boolean keyOrConfig;

        FlvTag(int tagType, int timestampMs, byte[] data, boolean keyOrConfig) {
            this.tagType = tagType;
            this.timestampMs = timestampMs;
            this.data = data;
            this.keyOrConfig = keyOrConfig;
        }
    }

    private static final class FlvClient {
        final ArrayBlockingQueue<FlvTag> queue = new ArrayBlockingQueue<>(96);
        volatile boolean active = true;
        volatile boolean sentKeyframe = false;

        void offer(FlvTag t) {
            if (!active) return;
            if (!queue.offer(t)) {
                if (!t.keyOrConfig) return;
                queue.poll();
                queue.offer(t);
            }
        }
    }

    private final IBinder binder = new LocalBinder();
    private SettingsStore cfg;
    private TelemetryCollector telemetry;
    private ProCameraController camera;
    private HudGlRenderer glRenderer;
    private LocalPhoneHttpServer httpServer;
    private final RtmpMuxer rtmpMuxer = new RtmpMuxer();

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ScheduledExecutorService ticker;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;

    private volatile boolean cameraPipelineReady = false;
    private volatile boolean streamingActive = false;
    private volatile boolean encodersRunning = false;
    private volatile boolean audioWorkerRunning = false;
    private final Object encoderLock = new Object();
    private final Object audioCodecLock = new Object();
    private long audioStreamSamples = 0;

    private MediaCodec videoCodec;
    private Surface codecInputSurface;
    private Surface currentPreviewSurface;
    private int currentPreviewW = 640;
    private int currentPreviewH = 360;
    private Thread videoDrainThread;
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private Thread audioLoopThread;
    private final MusicPcmPlayer musicPlayer = new MusicPcmPlayer();

    private final CopyOnWriteArrayList<FlvClient> flvClients = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream flvVideoBuf = new ByteArrayOutputStream(65536);
    private volatile byte[] latestFlvAvcHeader;
    private volatile byte[] latestFlvAacHeader;
    private volatile byte[] cachedSps;
    private volatile byte[] cachedPps;
    private volatile byte[] cachedAacAsc;

    private long lastTimelapseMs = 0;
    private long lastArchivePushMs = 0;
    private long lastStatsBytes = 0;
    private long lastStatsFrames = 0;
    private long lastStatsTimeMs = 0;
    private volatile String lastCaptureNote = "No captures yet";

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = new SettingsStore(this);
        telemetry = new TelemetryCollector(this);
        camera = new ProCameraController(this);
        glRenderer = new HudGlRenderer();
        httpServer = new LocalPhoneHttpServer(cfg.getLocalHttpPort(), this);
        rtmpMuxer.setErrorListener(reason -> {
            telemetry.lastError = reason;
            telemetry.rtmpConnected = false;
            telemetry.rtmpState = reason;
            streamingActive = false;
            releaseEncodersIfIdle();
            broadcastSettingsSync();
        });

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Initializing sensors & camera..."));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PalaRezStreamCam::WakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PalaRezStreamCam::WifiLock");
            wifiLock.acquire();
        }

        telemetry.start(cfg.getSensorIntervalMs(), cfg.isRawSensorsEnabled());
        httpServer.start();
        registerMdnsService();
        startCameraAndGlPipeline();

        audioWorkerRunning = true;
        audioLoopThread = new Thread(this::runAudioLoop, "PalaRezAudio");
        audioLoopThread.start();

        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(this::periodicTick, 500, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public SettingsStore getSettings() { return cfg; }
    public TelemetryCollector getTelemetry() { return telemetry; }
    public ProCameraController getCameraController() { return camera; }
    public boolean isStreaming() { return streamingActive; }
    public String getLastCaptureNote() { return lastCaptureNote; }

    public void attachPreviewSurface(Surface s, int w, int h) {
        this.currentPreviewSurface = s;
        this.currentPreviewW = Math.max(1, w);
        this.currentPreviewH = Math.max(1, h);
        if (glRenderer != null) glRenderer.setPreviewSurface(s, w, h);
    }

    public void setPreviewLoupe(boolean enabled) {
        if (glRenderer != null) glRenderer.setPreviewLoupe(enabled);
    }

    public void restartCameraPipeline() {
        boolean wasStreaming = streamingActive;
        boolean hadFlv = !flvClients.isEmpty();
        if (wasStreaming) stopRtmpBroadcast();
        else stopEncoders();
        startCameraAndGlPipeline();
        if (wasStreaming) startRtmpBroadcast();
        else if (hadFlv) ensureEncodersStarted();
    }

    public void restartSensors() {
        if (telemetry != null) telemetry.start(cfg.getSensorIntervalMs(), cfg.isRawSensorsEnabled());
    }

    private void startCameraAndGlPipeline() {
        cameraPipelineReady = false;
        camera.stop();
        glRenderer.stop();
        int w = cfg.getVideoWidth();
        int h = cfg.getVideoHeight();
        ProCameraController.CameraSpec spec = camera.selectActiveSpec(cfg.getCameraId());
        telemetry.activeCameraInfo = spec != null ? spec.label : "none";
        if (spec != null) telemetry.setCameraFacing(spec.facing);

        glRenderer.start(w, h, () -> {
            glRenderer.setExtraRotationDeg(cfg.getVideoRotationDeg());
            if (spec != null) glRenderer.setCameraGeometry(spec.sensorOrientation, spec.facing == 0);
            glRenderer.setTargetFps(cfg.getFps());
            glRenderer.setAntiFlickerTmix(cfg.isAntiFlickerTmix());
            glRenderer.updateHud(telemetry.buildHudSnapshot(cfg));
            if (currentPreviewSurface != null && currentPreviewSurface.isValid()) {
                glRenderer.setPreviewSurface(currentPreviewSurface, currentPreviewW, currentPreviewH);
            }
            if (encodersRunning && codecInputSurface != null && codecInputSurface.isValid()) {
                glRenderer.setEncoderSurface(codecInputSurface);
            }
            Surface camSurface = glRenderer.getCameraInputSurface();
            if (camSurface != null) {
                camera.start(camSurface, cfg, () -> {
                    cameraPipelineReady = true;
                    broadcastSettingsSync();
                });
            }
        });
    }

    public void applyLiveCameraControls() {
        if (camera != null) camera.applyLiveControls();
        if (glRenderer != null) {
            glRenderer.setExtraRotationDeg(cfg.getVideoRotationDeg());
            glRenderer.setTargetFps(cfg.getFps());
            glRenderer.setAntiFlickerTmix(cfg.isAntiFlickerTmix());
            glRenderer.updateHud(telemetry.buildHudSnapshot(cfg));
        }
        if (telemetry != null) telemetry.audioLive = cfg.isAudioEnabled() || musicPlayer.isPlaying();
    }

    private void ensureEncodersStarted() {
        synchronized (encoderLock) {
            if (encodersRunning) return;
            int w = cfg.getVideoWidth();
            int h = cfg.getVideoHeight();
            int fps = cfg.getFps();
            int vKbps = cfg.getVideoBitrateKbps();
            int aKbps = cfg.getAudioBitrateKbps();

            latestFlvAvcHeader = null;
            latestFlvAacHeader = null;
            encodersRunning = true;

            try {
                MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                vf.setInteger(MediaFormat.KEY_BIT_RATE, vKbps * 1000);
                vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.getKeyframeSec());
                vf.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

                videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codecInputSurface = videoCodec.createInputSurface();
                videoCodec.start();
                glRenderer.setEncoderSurface(codecInputSurface);

                videoDrainThread = new Thread(this::drainVideoEncoder, "PalaRezH264");
                videoDrainThread.start();
            } catch (Throwable t) {
                telemetry.lastError = "VIDEO_ENC_ERR: " + t.getMessage();
                encodersRunning = false;
                stopEncoders();
                return;
            }

            try {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, aKbps * 1000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                synchronized (audioCodecLock) {
                    audioStreamSamples = 0;
                    audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    audioCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioCodec.start();
                }
            } catch (Throwable t) {
                telemetry.lastError = "AUDIO_ENC_ERR: " + t.getMessage();
            }
        }
    }

    private void stopEncoders() {
        synchronized (encoderLock) {
            encodersRunning = false;
            if (glRenderer != null) glRenderer.setEncoderSurface(null);

            if (videoDrainThread != null) {
                videoDrainThread.interrupt();
                try { videoDrainThread.join(1000); } catch (InterruptedException ignored) {}
                videoDrainThread = null;
            }
            synchronized (audioCodecLock) {
                try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } } catch (Throwable ignored) {}
                audioCodec = null;
                audioStreamSamples = 0;
            }
            try { if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); } } catch (Throwable ignored) {}
            videoCodec = null;
            try { if (codecInputSurface != null) codecInputSurface.release(); } catch (Throwable ignored) {}
            codecInputSurface = null;
            // Clear cached sequence headers so resolution/profile changes never emit stale SPS/PPS
            cachedSps = null;
            cachedPps = null;
            cachedAacAsc = null;
            latestFlvAvcHeader = null;
            latestFlvAacHeader = null;
        }
    }

    private void releaseEncodersIfIdle() {
        if (!streamingActive && flvClients.isEmpty()) {
            stopEncoders();
        }
    }

    public synchronized void startRtmpBroadcast() {
        String url = cfg.getRtmpUrl();
        if (url == null || !url.matches("^rtmps?://[^/]+/[^/]+/.+$")) {
            telemetry.lastError = "INVALID_URL (expected rtmp[s]://host/app/key)";
            telemetry.rtmpState = "ERR: " + telemetry.lastError;
            return;
        }
        stopRtmpBroadcast();
        telemetry.lastError = null;
        streamingActive = true;
        telemetry.streamFramesSent = 0;
        telemetry.streamFramesDropped = 0;
        telemetry.streamStartMs = System.currentTimeMillis();
        telemetry.rtmpState = "CONNECTING";

        ensureEncodersStarted();
        if (!encodersRunning) {
            streamingActive = false;
            telemetry.rtmpState = "ERR: " + (telemetry.lastError != null ? telemetry.lastError : "ENCODER_FAILED");
            return;
        }

        rtmpMuxer.start(url, cfg.getVideoWidth(), cfg.getVideoHeight(), cfg.getFps(),
            cfg.getVideoBitrateKbps(), cfg.getAudioBitrateKbps(), AUDIO_SAMPLE_RATE);
        if (cachedSps != null && cachedPps != null) {
            rtmpMuxer.setAvcConfig(cachedSps, cachedPps);
        }
        if (cachedAacAsc != null) {
            rtmpMuxer.setAacConfig(cachedAacAsc);
        }
        requestSyncKeyframe();
    }

    private void requestSyncKeyframe() {
        synchronized (encoderLock) {
            try {
                if (encodersRunning && videoCodec != null) {
                    android.os.Bundle b = new android.os.Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    videoCodec.setParameters(b);
                }
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void stopRtmpBroadcast() {
        streamingActive = false;
        rtmpMuxer.stop();
        releaseEncodersIfIdle();

        telemetry.rtmpConnected = false;
        telemetry.rtmpState = telemetry.lastError != null ? ("ERR: " + telemetry.lastError)
            : (cfg.isArmed() ? "ARMED_READY" : "IDLE");
        telemetry.measuredFps = 0;
        telemetry.measuredKbps = 0;
    }

    private void drainVideoEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long basePtsUs = -1;
        while (encodersRunning && videoCodec != null) {
            try {
                int idx = videoCodec.dequeueOutputBuffer(info, 25000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = videoCodec.getOutputFormat();
                    ByteBuffer csd0 = outFmt.getByteBuffer("csd-0");
                    ByteBuffer csd1 = outFmt.getByteBuffer("csd-1");
                    if (csd0 != null && csd1 != null) {
                        byte[] sps = trimAnnexBStart(toBytes(csd0));
                        byte[] pps = trimAnnexBStart(toBytes(csd1));
                        publishAvcConfig(sps, pps);
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = videoCodec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(data);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            List<byte[]> nalus = RtmpMuxer.splitAnnexBNalus(data);
                            byte[] sps = null, pps = null;
                            for (byte[] n : nalus) {
                                int t = n[0] & 0x1F;
                                if (t == 7) sps = n;
                                else if (t == 8) pps = n;
                            }
                            if (sps != null && pps != null) publishAvcConfig(sps, pps);
                        } else {
                            if (basePtsUs < 0) basePtsUs = info.presentationTimeUs;
                            int tsMs = (int) Math.max(0L, (info.presentationTimeUs - basePtsUs) / 1000L);
                            boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (streamingActive) {
                                rtmpMuxer.sendVideoAnnexB(data, tsMs, isKey);
                                telemetry.streamFramesSent++;
                            }
                            if (!flvClients.isEmpty() && latestFlvAvcHeader != null) {
                                byte[] tagPayload = buildFlvVideoTagPayload(data, isKey);
                                if (tagPayload != null) {
                                    FlvTag tag = new FlvTag(9, tsMs, tagPayload, isKey);
                                    for (FlvClient c : flvClients) c.offer(tag);
                                }
                            }
                        }
                    }
                    videoCodec.releaseOutputBuffer(idx, false);
                }
            } catch (Throwable t) {
                if (!encodersRunning) break;
            }
        }
    }

    private void publishAvcConfig(byte[] sps, byte[] pps) {
        if (sps == null || pps == null || sps.length < 4) return;
        cachedSps = sps;
        cachedPps = pps;
        rtmpMuxer.setAvcConfig(sps, pps);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x17); b.write(0x00); b.write(0x00); b.write(0x00); b.write(0x00);
        b.write(0x01);
        b.write(sps[1]); b.write(sps[2]); b.write(sps[3]);
        b.write(0xFF); b.write(0xE1);
        b.write((sps.length >> 8) & 0xFF); b.write(sps.length & 0xFF);
        b.write(sps, 0, sps.length);
        b.write(0x01);
        b.write((pps.length >> 8) & 0xFF); b.write(pps.length & 0xFF);
        b.write(pps, 0, pps.length);
        latestFlvAvcHeader = b.toByteArray();
    }

    private byte[] buildFlvVideoTagPayload(byte[] annexB, boolean isKey) {
        List<byte[]> nalus = RtmpMuxer.splitAnnexBNalus(annexB);
        if (nalus.isEmpty()) return null;
        flvVideoBuf.reset();
        flvVideoBuf.write(isKey ? 0x17 : 0x27);
        flvVideoBuf.write(0x01);
        flvVideoBuf.write(0x00); flvVideoBuf.write(0x00); flvVideoBuf.write(0x00);
        for (byte[] nalu : nalus) {
            int ntype = nalu[0] & 0x1F;
            if (ntype == 7 || ntype == 8) continue;
            int len = nalu.length;
            flvVideoBuf.write((len >> 24) & 0xFF);
            flvVideoBuf.write((len >> 16) & 0xFF);
            flvVideoBuf.write((len >> 8) & 0xFF);
            flvVideoBuf.write(len & 0xFF);
            flvVideoBuf.write(nalu, 0, len);
        }
        return flvVideoBuf.size() > 5 ? flvVideoBuf.toByteArray() : null;
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Throwable ignored) {}
            try { audioRecord.release(); } catch (Throwable ignored) {}
            audioRecord = null;
        }
    }

    private void ensureAudioRecordOpen() {
        if (audioRecord != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int readSize = Math.max(2048, minBuf);
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, readSize * 2);
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                rec.startRecording();
                audioRecord = rec;
            } else {
                rec.release();
            }
        } catch (Throwable ignored) {
            releaseAudioRecord();
        }
    }

    private void runAudioLoop() {
        byte[] pcm = new byte[2048];
        float[] musicBuf = new float[1024];
        MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
        final long framePeriodNs = 1024L * 1_000_000_000L / AUDIO_SAMPLE_RATE; // 23,219,955 ns
        long nextDeadlineNs = 0L;

        while (audioWorkerRunning) {
            try {
                boolean wantMic = cfg.isAudioEnabled();
                boolean encodeAudio = encodersRunning;
                boolean musicReady = musicPlayer.isPlaying();

                if (!wantMic) {
                    releaseAudioRecord();
                    if (!encodeAudio) {
                        nextDeadlineNs = 0L;
                        telemetry.audioPeakDbfs = -90.0;
                        telemetry.audioRmsDbfs = -90.0;
                        Thread.sleep(150);
                        continue;
                    }
                    long nowNs = System.nanoTime();
                    if (nextDeadlineNs == 0L || nowNs - nextDeadlineNs > 200_000_000L) {
                        nextDeadlineNs = nowNs;
                    }
                    nextDeadlineNs += framePeriodNs;
                    long waitMs = (nextDeadlineNs - nowNs) / 1_000_000L;
                    int waitNs = (int) Math.max(0L, (nextDeadlineNs - nowNs) % 1_000_000L);
                    if (waitMs > 0 || waitNs > 0) {
                        Thread.sleep(Math.max(0L, waitMs), waitNs);
                    }

                    boolean hasMusic = musicReady && musicPlayer.readMonoSamples(musicBuf);
                    if (hasMusic) {
                        float mGain = cfg.getMusicGain();
                        for (int i = 0; i < 1024; i++) {
                            int s = Math.round(musicBuf[i] * mGain);
                            if (s > 32767) s = 32767;
                            else if (s < -32768) s = -32768;
                            pcm[i * 2] = (byte) (s & 0xFF);
                            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                        }
                        computeAudioLevels(pcm, pcm.length);
                    } else {
                        Arrays.fill(pcm, 0, pcm.length, (byte) 0);
                        telemetry.audioPeakDbfs = -90.0;
                        telemetry.audioRmsDbfs = -90.0;
                    }
                    encodePcmIfActive(pcm, pcm.length, aInfo);
                    continue;
                }

                nextDeadlineNs = 0L;
                ensureAudioRecordOpen();
                int bytesRead = pcm.length;
                if (audioRecord != null) {
                    int n = audioRecord.read(pcm, 0, pcm.length);
                    if (n > 0) {
                        bytesRead = n;
                    } else {
                        Arrays.fill(pcm, 0, pcm.length, (byte) 0);
                        Thread.sleep(23);
                    }
                } else {
                    Arrays.fill(pcm, 0, pcm.length, (byte) 0);
                    Thread.sleep(encodeAudio ? 23 : 150);
                }

                boolean hasMusic = encodeAudio && musicReady && musicPlayer.readMonoSamples(musicBuf);
                float micGain = cfg.getMicGain();
                float musGain = cfg.getMusicGain();
                if (hasMusic || Math.abs(micGain - 1.0f) > 0.001f) {
                    int samples = Math.min(1024, bytesRead / 2);
                    for (int i = 0; i < samples; i++) {
                        int micS = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
                        float mixed = micS * micGain + (hasMusic ? (musicBuf[i] * musGain) : 0f);
                        int out = Math.round(mixed);
                        if (out > 32767) out = 32767;
                        else if (out < -32768) out = -32768;
                        pcm[i * 2] = (byte) (out & 0xFF);
                        pcm[i * 2 + 1] = (byte) ((out >> 8) & 0xFF);
                    }
                }
                if (audioRecord != null || hasMusic) {
                    computeAudioLevels(pcm, bytesRead);
                } else {
                    telemetry.audioPeakDbfs = -90.0;
                    telemetry.audioRmsDbfs = -90.0;
                }

                if (encodeAudio) {
                    encodePcmIfActive(pcm, bytesRead, aInfo);
                }
            } catch (InterruptedException ie) {
                if (!audioWorkerRunning) break;
            } catch (Throwable ignored) {
                try { Thread.sleep(50); } catch (InterruptedException ignored2) { break; }
            }
        }
        releaseAudioRecord();
    }

    private void encodePcmIfActive(byte[] pcm, int bytesRead, MediaCodec.BufferInfo aInfo) {
        synchronized (audioCodecLock) {
            if (!encodersRunning || audioCodec == null) return;
            try {
                int inIdx = audioCodec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = audioCodec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(pcm, 0, bytesRead);
                        long ptsUs = (audioStreamSamples * 1_000_000L) / AUDIO_SAMPLE_RATE;
                        audioCodec.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0);
                        audioStreamSamples += (bytesRead / 2);
                    }
                }

                int outIdx;
                while ((outIdx = audioCodec.dequeueOutputBuffer(aInfo, 0)) >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ByteBuffer csd = audioCodec.getOutputFormat().getByteBuffer("csd-0");
                        if (csd != null) publishAacConfig(toBytes(csd));
                    } else {
                        ByteBuffer outBuf = audioCodec.getOutputBuffer(outIdx);
                        if (outBuf != null && aInfo.size > 0) {
                            byte[] raw = new byte[aInfo.size];
                            outBuf.position(aInfo.offset);
                            outBuf.limit(aInfo.offset + aInfo.size);
                            outBuf.get(raw);
                            if ((aInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                publishAacConfig(raw);
                            } else {
                                int tsMs = (int) Math.max(0L, aInfo.presentationTimeUs / 1000L);
                                if (streamingActive) rtmpMuxer.sendAacFrame(raw, tsMs);
                                if (!flvClients.isEmpty() && latestFlvAacHeader != null) {
                                    byte[] tag = new byte[2 + raw.length];
                                    tag[0] = (byte) 0xAF;
                                    tag[1] = 0x01;
                                    System.arraycopy(raw, 0, tag, 2, raw.length);
                                    FlvTag t = new FlvTag(8, tsMs, tag, true);
                                    for (FlvClient c : flvClients) c.offer(t);
                                }
                            }
                        }
                        audioCodec.releaseOutputBuffer(outIdx, false);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private void publishAacConfig(byte[] asc) {
        if (asc == null || asc.length < 2) return;
        cachedAacAsc = asc;
        rtmpMuxer.setAacConfig(asc);
        byte[] tag = new byte[2 + asc.length];
        tag[0] = (byte) 0xAF;
        tag[1] = 0x00;
        System.arraycopy(asc, 0, tag, 2, asc.length);
        latestFlvAacHeader = tag;
    }

    /**
     * Streams live muxed H.264 + AAC-LC FLV tags over HTTP ({@code GET /stream.flv}).
     *
     * <p>Note: Because {@code /stream.flv} and outbound RTMP share a single zero-copy hardware
     * H.264 encoder surface, passing {@code cleanHud == true} ({@code ?clean=1}) switches the
     * shared pipeline's {@code hud_mode} to {@code "clean"} so downstream {@code go2rtc}/{@code FFmpeg}
     * hubs receive an un-overlaid mezzanine feed. Requests an immediate IDR sync frame on connect
     * and rebases per-client FLV timestamps to {@code 0 ms} at the first keyframe.
     */
    @Override
    public void streamFlvTo(OutputStream out, boolean cleanHud) throws IOException {
        if (cleanHud && !"clean".equals(cfg.getHudMode())) {
            cfg.setHudMode("clean");
            applyLiveCameraControls();
            broadcastSettingsSync();
        }
        FlvClient client = new FlvClient();
        flvClients.add(client);
        long warmWait = System.currentTimeMillis() + 3000;
        while (!cameraPipelineReady && System.currentTimeMillis() < warmWait) {
            try { Thread.sleep(40); } catch (InterruptedException ignored) { break; }
        }
        ensureEncodersStarted();
        try {
            // Standard 13-byte FLV Header (Video + Audio)
            out.write(new byte[] { 'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00 });
            out.flush();

            // Wait briefly for AVC & AAC sequence headers
            long waitEnd = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < waitEnd && (latestFlvAvcHeader == null || latestFlvAacHeader == null)) {
                Thread.sleep(30);
            }
            if (latestFlvAvcHeader != null) writeFlvTag(out, 9, 0, latestFlvAvcHeader);
            if (latestFlvAacHeader != null) writeFlvTag(out, 8, 0, latestFlvAacHeader);
            requestSyncKeyframe();

            int baseTs = -1;
            while (client.active && encodersRunning) {
                FlvTag t = client.queue.poll(500, TimeUnit.MILLISECONDS);
                if (t == null) continue;
                if (!client.sentKeyframe) {
                    if (t.tagType == 9 && t.keyOrConfig) {
                        client.sentKeyframe = true;
                        baseTs = t.timestampMs;
                    } else {
                        continue;
                    }
                }
                int relTs = Math.max(0, t.timestampMs - baseTs);
                writeFlvTag(out, t.tagType, relTs, t.data);
            }
        } catch (InterruptedException ignored) {
        } finally {
            client.active = false;
            flvClients.remove(client);
            releaseEncodersIfIdle();
        }
    }

    private static void writeFlvTag(OutputStream out, int tagType, int tsMs, byte[] payload) throws IOException {
        int size = payload.length;
        byte[] hdr = new byte[11];
        hdr[0] = (byte) (tagType & 0xFF);
        hdr[1] = (byte) ((size >> 16) & 0xFF);
        hdr[2] = (byte) ((size >> 8) & 0xFF);
        hdr[3] = (byte) (size & 0xFF);
        hdr[4] = (byte) ((tsMs >> 16) & 0xFF);
        hdr[5] = (byte) ((tsMs >> 8) & 0xFF);
        hdr[6] = (byte) (tsMs & 0xFF);
        hdr[7] = (byte) ((tsMs >> 24) & 0xFF);
        hdr[8] = 0; hdr[9] = 0; hdr[10] = 0;
        out.write(hdr);
        out.write(payload);
        int total = 11 + size;
        out.write((total >> 24) & 0xFF);
        out.write((total >> 16) & 0xFF);
        out.write((total >> 8) & 0xFF);
        out.write(total & 0xFF);
        out.flush();
    }

    private void computeAudioLevels(byte[] pcm, int len) {
        int peak = 0;
        double sumSq = 0.0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int a = Math.abs(s);
            if (a > peak) peak = a;
            sumSq += (double) s * s;
        }
        double rms = samples > 0 ? Math.sqrt(sumSq / samples) : 0.0;
        telemetry.audioPeakDbfs = peak > 0 ? 20.0 * Math.log10(peak / 32768.0) : -90.0;
        telemetry.audioRmsDbfs = rms > 0 ? 20.0 * Math.log10(rms / 32768.0) : -90.0;
    }

    public byte[] snapFullResPhotoSync(boolean isTimelapse) {
        if (!cameraPipelineReady) return null;
        JSONObject snapTelemetry = telemetry.toJson(cfg);
        AtomicReference<byte[]> capturedRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        camera.captureFullResStill(jpegBytes -> {
            if (jpegBytes != null && jpegBytes.length > 0) {
                capturedRef.set(jpegBytes);
                try {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), isTimelapse ? "timelapse" : "stills");
                    if (!dir.exists()) dir.mkdirs();
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    File imgFile = new File(dir, stamp + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                        fos.write(jpegBytes);
                    }
                    File jsonFile = new File(dir, stamp + ".json");
                    try (FileOutputStream fos = new FileOutputStream(jsonFile)) {
                        fos.write(snapTelemetry.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    if (isTimelapse) telemetry.timelapseCount++; else telemetry.stillCount++;
                    lastCaptureNote = (isTimelapse ? "Timelapse " : "Still ") + imgFile.getName();

                    String archUrl = cfg.getArchiveUrl();
                    if (archUrl != null && !archUrl.isEmpty()) {
                        uploadCaptureAsync(archUrl, imgFile.getName(), jpegBytes, snapTelemetry);
                    }
                } catch (Throwable ignored) {}
            }
            latch.countDown();
        });
        try { latch.await(3500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return capturedRef.get();
    }

    public void snapFullResPhoto(boolean isTimelapse) {
        new Thread(() -> snapFullResPhotoSync(isTimelapse), "PalaRezSnap").start();
    }

    private void periodicTick() {
        long now = System.currentTimeMillis();
        telemetry.audioLive = cfg.isAudioEnabled() || musicPlayer.isPlaying();
        telemetry.rtmpConnected = rtmpMuxer.isConnected();
        if (streamingActive) {
            telemetry.rtmpState = rtmpMuxer.statusText;
        } else if (telemetry.lastError != null && !telemetry.lastError.isEmpty()) {
            telemetry.rtmpState = telemetry.lastError.startsWith("ERR:") ? telemetry.lastError : ("ERR: " + telemetry.lastError);
        } else {
            telemetry.rtmpState = cfg.isArmed() ? "ARMED_READY" : "IDLE";
        }
        telemetry.streamFramesDropped = rtmpMuxer.droppedFrames;

        if (lastStatsTimeMs > 0 && now - lastStatsTimeMs >= 1000) {
            double dt = (now - lastStatsTimeMs) / 1000.0;
            long dBytes = Math.max(0L, rtmpMuxer.bytesSent - lastStatsBytes);
            long dFrames = Math.max(0L, telemetry.streamFramesSent - lastStatsFrames);
            telemetry.measuredKbps = (dBytes * 8.0) / (dt * 1000.0);
            telemetry.measuredFps = dFrames / dt;
            lastStatsBytes = rtmpMuxer.bytesSent;
            lastStatsFrames = telemetry.streamFramesSent;
            lastStatsTimeMs = now;
        } else if (lastStatsTimeMs == 0) {
            lastStatsTimeMs = now;
        }

        if (glRenderer != null) {
            glRenderer.updateHud(telemetry.buildHudSnapshot(cfg));
        }

        if (cfg.isTimelapseEnabled() && now - lastTimelapseMs >= cfg.getTimelapseIntervalSec() * 1000L) {
            lastTimelapseMs = now;
            snapFullResPhoto(true);
        }

        String archUrl = cfg.getArchiveUrl();
        if (archUrl != null && !archUrl.isEmpty() && now - lastArchivePushMs >= cfg.getTelemetryPushSec() * 1000L) {
            lastArchivePushMs = now;
            JSONObject snap = telemetry.toJson(cfg);
            new Thread(() -> postJson(archUrl.replaceAll("/+$", "") + "/api/ingest/telemetry", snap)).start();
        }

        updateNotification(telemetry.rtmpConnected
            ? String.format(Locale.US, "LIVE %.1ffps | %d kbps | http://%s:%d", telemetry.measuredFps, Math.round(telemetry.measuredKbps), telemetry.getLocalIpv4(), cfg.getLocalHttpPort())
            : String.format(Locale.US, "%s | http://%s:%d", telemetry.rtmpState, telemetry.getLocalIpv4(), cfg.getLocalHttpPort()));
    }

    private void uploadCaptureAsync(String baseUrl, String name, byte[] jpeg, JSONObject meta) {
        new Thread(() -> {
            String base = baseUrl.replaceAll("/+$", "");
            HttpURLConnection conn = null;
            try {
                String encodedName = java.net.URLEncoder.encode(name, "UTF-8");
                conn = (HttpURLConnection) new URL(base + "/api/ingest/frame?filename=" + encodedName).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "image/jpeg");
                try (OutputStream os = conn.getOutputStream()) { os.write(jpeg); }
                conn.getResponseCode();
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            postJson(base + "/api/ingest/telemetry", meta);
        }).start();
    }

    private static void postJson(String url, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public JSONObject getTelemetryJson(boolean rawSensors) {
        return telemetry.toJson(cfg, rawSensors);
    }

    @Override
    public JSONObject getCapabilitiesJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("station", cfg.getStationName());
            root.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("android", Build.VERSION.RELEASE);
            root.put("api_version", "2.0");

            JSONArray cams = new JSONArray();
            for (ProCameraController.CameraSpec s : camera.enumerateCameras()) {
                JSONObject c = new JSONObject();
                c.put("id", s.id);
                c.put("label", s.label);
                c.put("facing", s.facing == 0 ? "front" : (s.facing == 1 ? "back" : "external"));
                c.put("focal_mm", s.focalLengthMm);
                c.put("min_focus_diopter", s.minFocusDiopter);
                c.put("has_flash", s.hasFlash);
                c.put("has_ois", s.hasOis);
                c.put("manual_exposure", s.manualSupported);
                c.put("iso_range", new JSONArray(Arrays.asList(s.isoMin, s.isoMax)));
                c.put("max_zoom", s.maxZoom);
                c.put("max_jpeg", s.maxJpegSize.getWidth() + "x" + s.maxJpegSize.getHeight());
                cams.put(c);
            }
            root.put("cameras", cams);

            JSONArray presets = new JSONArray();
            for (SettingsStore.StreamPreset p : SettingsStore.PRESETS) {
                JSONObject po = new JSONObject();
                po.put("id", p.id);
                po.put("label", p.label);
                po.put("resolution", p.width + "x" + p.height);
                po.put("fps", p.fps);
                po.put("video_kbps", p.videoKbps);
                po.put("audio_kbps", p.audioKbps);
                po.put("recommended_hud", p.hudMode);
                presets.put(po);
            }
            root.put("presets", presets);
            root.put("hud_modes", new JSONArray(Arrays.asList("sanctuary_card", "minimal_pill", "clean")));
            root.put("supported_resolutions", new JSONArray(Arrays.asList("1280x720", "1920x1080", "2560x1440")));
            root.put("supported_fps", new JSONArray(Arrays.asList(15, 24, 30)));
            root.put("sensors_hardware", telemetry.hardwareSensorSummary());
        } catch (Throwable ignored) {}
        return root;
    }

    @Override
    public JSONObject getStatusJson() {
        JSONObject root = new JSONObject();
        try {
            String ip = telemetry.getLocalIpv4();
            int port = cfg.getLocalHttpPort();
            String base = "http://" + ip + ":" + port;
            String key = SettingsStore.extractRtmpKey(cfg.getRtmpUrl());
            boolean hasKey = key != null && !key.isEmpty();

            JSONArray warnings = new JSONArray();
            if (!hasKey) warnings.put("EMPTY_STREAM_KEY");
            if (!cfg.isAudioEnabled() && !musicPlayer.isPlaying()) warnings.put("MIC_MUTED_SILENT_PCM");
            if (!cameraPipelineReady) warnings.put("CAMERA_WARMING_UP");
            if (telemetry.lastError != null) warnings.put("LAST_ERROR: " + telemetry.lastError);

            String lifecycle = streamingActive
                ? (rtmpMuxer.isConnected() ? "STREAMING" : "CONNECTING")
                : (telemetry.lastError != null ? "ERROR" : (cfg.isArmed() ? "ARMED_READY" : "IDLE"));

            root.put("station", cfg.getStationName());
            root.put("lifecycle_state", lifecycle);
            root.put("is_live_streaming", streamingActive);
            root.put("rtmp_connected", rtmpMuxer.isConnected());
            root.put("armed", cfg.isArmed());
            root.put("ready_for_hub_ingest", cameraPipelineReady);
            root.put("ready_for_direct_youtube", cameraPipelineReady && hasKey);
            root.put("camera_id", cfg.getCameraId());
            root.put("camera_info", telemetry.activeCameraInfo);
            root.put("preset", cfg.getPresetName());
            root.put("hud_mode", cfg.getHudMode());
            root.put("anti_flicker_enabled", cfg.isAntiFlickerEnabled());
            root.put("anti_flicker_mode", cfg.getAntiFlickerMode());
            root.put("video_format", cfg.getVideoWidth() + "x" + cfg.getVideoHeight() + "@" + cfg.getFps() + "fps " + cfg.getVideoBitrateKbps() + "kbps");
            root.put("rtmp_target_masked", cfg.getMaskedRtmpUrl());
            root.put("last_error", telemetry.lastError != null ? telemetry.lastError : JSONObject.NULL);
            root.put("warnings", warnings);
            root.put("music", musicPlayer.toStatusJson());

            JSONObject endpoints = new JSONObject();
            endpoints.put("http_base", base);
            endpoints.put("flv_stream", base + "/stream.flv");
            endpoints.put("flv_stream_clean", base + "/stream.flv?clean=1");
            endpoints.put("snapshot", base + "/snapshot.jpg");
            endpoints.put("snapshot_clean", base + "/snapshot.jpg?clean=1");
            endpoints.put("snapshot_fullres", base + "/snapshot.jpg?fullres=1");
            endpoints.put("mjpeg", base + "/mjpeg");
            endpoints.put("telemetry", base + "/api/telemetry");
            endpoints.put("audio_tracks", base + "/api/audio/tracks");
            endpoints.put("capabilities", base + "/api/capabilities");
            endpoints.put("schema", base + "/api/schema");
            root.put("lan_endpoints", endpoints);
        } catch (Throwable ignored) {}
        return root;
    }

    private File getTracksDir() {
        File dir = new File(getFilesDir(), "tracks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public JSONObject getAudioTracksJson() {
        JSONObject out = new JSONObject();
        try {
            File dir = getTracksDir();
            File[] files = dir.listFiles();
            JSONArray arr = new JSONArray();
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    if (!f.isFile() || f.getName().endsWith(".tmp")) continue;
                    JSONObject item = new JSONObject();
                    item.put("name", f.getName());
                    item.put("size_bytes", f.length());
                    arr.put(item);
                }
            }
            out.put("ok", true);
            out.put("tracks", arr);
            out.put("mixer", musicPlayer.toStatusJson());
            out.put("mic_enabled", cfg.isAudioEnabled());
            out.put("mic_gain", Math.round(cfg.getMicGain() * 100.0) / 100.0);
            out.put("music_gain", Math.round(cfg.getMusicGain() * 100.0) / 100.0);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", t.getMessage()); } catch (Throwable ignored) {}
        }
        return out;
    }

    @Override
    public JSONObject uploadAudioTrack(String rawName, InputStream bodyStream, long contentLength) {
        JSONObject out = new JSONObject();
        try {
            if (contentLength <= 0 || contentLength > 64L * 1024L * 1024L) {
                out.put("ok", false);
                out.put("error", "Invalid Content-Length (must be 1B..64MB)");
                return out;
            }
            String cleanName = (rawName == null ? "" : new File(rawName).getName()).replaceAll("[^A-Za-z0-9._-]", "_");
            if (cleanName.isEmpty() || cleanName.startsWith(".")) {
                cleanName = "track_" + System.currentTimeMillis() + ".mp3";
            }
            String lowerName = cleanName.toLowerCase(Locale.US);
            if (!(lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".m4a")
                    || lowerName.endsWith(".aac") || lowerName.endsWith(".flac") || lowerName.endsWith(".ogg"))) {
                out.put("ok", false);
                out.put("error", "Unsupported audio extension (allowed: .mp3, .wav, .m4a, .aac, .flac, .ogg)");
                return out;
            }
            File dst = new File(getTracksDir(), cleanName);
            File tmp = new File(getTracksDir(), cleanName + ".tmp");
            byte[] buf = new byte[8192];
            long remaining = contentLength;
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                while (remaining > 0) {
                    int r = bodyStream.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (r < 0) break;
                    fos.write(buf, 0, r);
                    remaining -= r;
                }
            }
            if (remaining > 0) {
                tmp.delete();
                out.put("ok", false);
                out.put("error", "Truncated upload (" + remaining + " bytes missing)");
                return out;
            }
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                out.put("ok", false);
                out.put("error", "Failed to finalize uploaded audio file");
                return out;
            }
            out.put("ok", true);
            out.put("saved", cleanName);
            out.put("size_bytes", dst.length());
            out.put("tracks", getAudioTracksJson().optJSONArray("tracks"));
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", t.getMessage()); } catch (Throwable ignored) {}
        }
        return out;
    }

    @Override
    public JSONObject validateRtmpDryRun(JSONObject opts) {
        String candidateUrl = cfg.getRtmpUrl();
        if (opts != null && opts.has("rtmp_url")) candidateUrl = opts.optString("rtmp_url", candidateUrl);
        String masked = candidateUrl;
        int sl = candidateUrl.lastIndexOf('/');
        if (sl > 0 && sl < candidateUrl.length() - 1) {
            String k = candidateUrl.substring(sl + 1);
            masked = candidateUrl.substring(0, sl + 1) + (k.length() > 4 ? ("****-" + k.substring(k.length() - 4)) : "****");
        }

        RtmpMuxer.ValidationResult vr = RtmpMuxer.validateDryRun(candidateUrl, 6000);
        JSONObject out = new JSONObject();
        try {
            out.put("ok", vr.ok && cameraPipelineReady);
            out.put("stream_started", false);
            out.put("level", "handshake");
            out.put("target_masked", masked);
            out.put("error", vr.error != null ? vr.error : JSONObject.NULL);

            JSONObject checks = new JSONObject();
            checks.put("url_syntax", vr.urlSyntaxOk);
            checks.put("dns_resolved_ip", vr.dnsResolvedIp);
            checks.put("tcp_connect_ms", vr.tcpConnectMs);
            checks.put("tls_handshake_ms", vr.tlsHandshakeMs);
            checks.put("rtmp_c0c1c2_ok", vr.rtmpHandshakeOk);
            checks.put("amf_connect_app_ok", vr.amfConnectOk);
            checks.put("server_message", vr.serverMessage);
            checks.put("camera_session_ready", cameraPipelineReady);
            checks.put("has_stream_key", !SettingsStore.extractRtmpKey(candidateUrl).isEmpty());

            Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int pct = -1;
            if (bi != null) {
                int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (lv >= 0 && sc > 0) pct = Math.round(lv * 100f / sc);
            }
            checks.put("battery_percent", pct);
            checks.put("battery_ok", pct < 0 || pct >= 15);
            out.put("checks", checks);
        } catch (Throwable ignored) {}
        return out;
    }

    @Override
    public byte[] getSnapshotJpeg(boolean fullRes, boolean clean) {
        if (fullRes) {
            byte[] still = snapFullResPhotoSync(false);
            if (still != null && still.length > 0) return still;
        }
        return glRenderer != null ? glRenderer.captureJpegSync(clean) : null;
    }

    @Override
    public JSONObject applyControlJson(JSONObject cmd) {
        boolean needCameraRestart = false;
        List<String> applied = new ArrayList<>();

        if (cmd.has("preset") && cfg.applyPreset(cmd.optString("preset"))) {
            needCameraRestart = true;
            applied.add("preset");
        }
        if (cmd.has("width") && cmd.has("height")) {
            int w = cmd.optInt("width", cfg.getVideoWidth());
            int h = cmd.optInt("height", cfg.getVideoHeight());
            cfg.setResolution(w, h);
            needCameraRestart = true;
            applied.add("resolution");
        }
        if (cmd.has("fps")) {
            cfg.setFps(cmd.optInt("fps", cfg.getFps()));
            needCameraRestart = true;
            applied.add("fps");
        }
        if (cmd.has("video_bitrate_kbps")) {
            cfg.setVideoBitrateKbps(cmd.optInt("video_bitrate_kbps", cfg.getVideoBitrateKbps()));
            applied.add("video_bitrate_kbps");
        }
        if (cmd.has("audio_bitrate_kbps")) {
            cfg.setAudioBitrateKbps(cmd.optInt("audio_bitrate_kbps", cfg.getAudioBitrateKbps()));
            applied.add("audio_bitrate_kbps");
        }
        if (cmd.has("keyframe_sec")) {
            cfg.setKeyframeSec(cmd.optInt("keyframe_sec", cfg.getKeyframeSec()));
            applied.add("keyframe_sec");
        }
        if (cmd.has("camera_id")) {
            cfg.setCameraId(cmd.optString("camera_id", cfg.getCameraId()));
            needCameraRestart = true;
            applied.add("camera_id");
        }
        if (cmd.has("manual_focus")) { cfg.setManualFocus(cmd.optBoolean("manual_focus")); applied.add("manual_focus"); }
        if (cmd.has("focus_distance")) { cfg.setFocusDistance((float) cmd.optDouble("focus_distance")); applied.add("focus_distance"); }
        if (cmd.has("manual_exposure")) { cfg.setManualExposure(cmd.optBoolean("manual_exposure")); applied.add("manual_exposure"); }
        if (cmd.has("iso")) { cfg.setManualIso(cmd.optInt("iso")); applied.add("iso"); }
        if (cmd.has("shutter_ns")) { cfg.setManualShutterNs(cmd.optLong("shutter_ns")); applied.add("shutter_ns"); }
        if (cmd.has("exposure_comp")) { cfg.setExposureComp(cmd.optInt("exposure_comp")); applied.add("exposure_comp"); }
        if (cmd.has("awb")) { cfg.setAwbMode(cmd.optString("awb", "auto")); applied.add("awb"); }
        if (cmd.has("zoom")) { cfg.setZoomRatio((float) cmd.optDouble("zoom", 1.0)); applied.add("zoom"); }
        if (cmd.has("torch")) { cfg.setTorchOn(cmd.optBoolean("torch")); applied.add("torch"); }
        if (cmd.has("stabilization")) { cfg.setStabilizationOn(cmd.optBoolean("stabilization")); applied.add("stabilization"); }
        if (cmd.has("anti_flicker_mode")) {
            cfg.setAntiFlickerMode(cmd.optString("anti_flicker_mode", "off"));
            applied.add("anti_flicker_mode");
        } else if (cmd.has("anti_flicker")) {
            cfg.setAntiFlickerMode(cmd.optBoolean("anti_flicker") ? "60hz_tmix" : "off");
            applied.add("anti_flicker");
        }
        if (cmd.has("rotation_deg")) { cfg.setVideoRotationDeg(cmd.optInt("rotation_deg")); applied.add("rotation_deg"); }
        if (cmd.has("audio_enabled")) { cfg.setAudioEnabled(cmd.optBoolean("audio_enabled")); applied.add("audio_enabled"); }
        if (cmd.has("mic_gain")) { cfg.setMicGain((float) cmd.optDouble("mic_gain", 1.0)); applied.add("mic_gain"); }
        if (cmd.has("music_gain")) { cfg.setMusicGain((float) cmd.optDouble("music_gain", 0.90)); applied.add("music_gain"); }
        if (cmd.has("music_loop")) {
            boolean ml = cmd.optBoolean("music_loop", false);
            cfg.setMusicLoop(ml);
            musicPlayer.setLoop(ml);
            applied.add("music_loop");
        }
        if (cmd.has("music_track")) {
            String tName = cmd.optString("music_track", "").trim();
            if (tName.isEmpty()) {
                musicPlayer.stopAndUnload();
                applied.add("music_track:cleared");
            } else {
                String safeName = new File(tName).getName();
                File f = new File(getTracksDir(), safeName);
                boolean autoPlay = cmd.optBoolean("music_playing", true);
                boolean ok = musicPlayer.load(f, autoPlay, cfg.isMusicLoop());
                applied.add(ok ? "music_track" : "music_track:load_failed");
            }
        } else if (cmd.has("music_playing")) {
            musicPlayer.setPlaying(cmd.optBoolean("music_playing", false));
            applied.add("music_playing");
        }
        if (cmd.optBoolean("music_seek_start", false)) {
            musicPlayer.seekStart();
            applied.add("music_seek_start");
        }
        if (cmd.optBoolean("music_stop", false)) {
            musicPlayer.stopAndUnload();
            applied.add("music_stop");
        }
        if (cmd.has("hud_enabled")) { cfg.setHudEnabled(cmd.optBoolean("hud_enabled")); applied.add("hud_enabled"); }
        if (cmd.has("hud_mode")) { cfg.setHudMode(cmd.optString("hud_mode")); applied.add("hud_mode"); }
        if (cmd.has("hud_env")) { cfg.setHudEnv(cmd.optBoolean("hud_env")); applied.add("hud_env"); }
        if (cmd.has("hud_motion")) { cfg.setHudMotion(cmd.optBoolean("hud_motion")); applied.add("hud_motion"); }
        if (cmd.has("hud_gps")) { cfg.setHudGps(cmd.optBoolean("hud_gps")); applied.add("hud_gps"); }
        if (cmd.has("hud_radio_power")) { cfg.setHudRadioPower(cmd.optBoolean("hud_radio_power")); applied.add("hud_radio_power"); }
        if (cmd.has("hud_camera")) { cfg.setHudCameraParams(cmd.optBoolean("hud_camera")); applied.add("hud_camera"); }
        if (cmd.has("sea_level_hpa")) { cfg.setSeaLevelHpa((float) cmd.optDouble("sea_level_hpa", 1013.25)); applied.add("sea_level_hpa"); }
        if (cmd.has("raw_sensors")) {
            cfg.setRawSensorsEnabled(cmd.optBoolean("raw_sensors"));
            restartSensors();
            applied.add("raw_sensors");
        }
        if (cmd.has("sensor_interval_ms")) {
            cfg.setSensorIntervalMs(cmd.optInt("sensor_interval_ms"));
            restartSensors();
            applied.add("sensor_interval_ms");
        }
        if (cmd.has("timelapse_enabled")) { cfg.setTimelapseEnabled(cmd.optBoolean("timelapse_enabled")); applied.add("timelapse_enabled"); }
        if (cmd.has("timelapse_interval_sec")) { cfg.setTimelapseIntervalSec(cmd.optInt("timelapse_interval_sec")); applied.add("timelapse_interval_sec"); }

        if (cmd.has("rtmp_url")) {
            cfg.setRtmpUrl(cmd.optString("rtmp_url"));
            applied.add("rtmp_url");
        } else if (cmd.has("rtmp_server") || cmd.has("stream_key")) {
            String base = cmd.optString("rtmp_server", SettingsStore.extractRtmpBase(cfg.getRtmpUrl()));
            String key = cmd.optString("stream_key", SettingsStore.extractRtmpKey(cfg.getRtmpUrl()));
            cfg.setRtmpUrl(SettingsStore.combineRtmpUrl(base, key));
            applied.add("rtmp_server_key");
        }
        if (cmd.has("archive_url")) { cfg.setArchiveUrl(cmd.optString("archive_url")); applied.add("archive_url"); }
        if (cmd.has("station_name")) { cfg.setStationName(cmd.optString("station_name")); applied.add("station_name"); }
        if (cmd.has("display_mode")) { cfg.setDisplayMode(cmd.optString("display_mode")); applied.add("display_mode"); }

        if (cmd.has("external_humidity_pct")) {
            telemetry.externalHumidityPct = cmd.optDouble("external_humidity_pct", Double.NaN);
            applied.add("external_humidity_pct");
        }
        if (cmd.has("external_temp_c")) {
            telemetry.externalTempC = cmd.optDouble("external_temp_c", Double.NaN);
            applied.add("external_temp_c");
        }

        if (cmd.optBoolean("clear_error", false)) telemetry.lastError = null;
        if (cmd.has("arm")) {
            boolean armed = cmd.optBoolean("arm", false);
            cfg.setArmed(armed);
            if (armed) telemetry.lastError = null;
            applied.add("arm");
        }

        if (needCameraRestart) restartCameraPipeline(); else applyLiveCameraControls();
        if (cmd.optBoolean("snap", false)) snapFullResPhoto(false);
        if (cmd.optBoolean("start_stream", false)) startRtmpBroadcast();
        if (cmd.optBoolean("stop_stream", false)) stopRtmpBroadcast();

        broadcastSettingsSync();

        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("applied", new JSONArray(applied));
            resp.put("restart_performed", needCameraRestart);
            resp.put("status", getStatusJson());
        } catch (Throwable ignored) {}
        return resp;
    }

    private void broadcastSettingsSync() {
        try {
            Intent i = new Intent(ACTION_SETTINGS_SYNC);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private void registerMdnsService() {
        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(cfg.getStationName());
            info.setServiceType("_palarez-cam._tcp.");
            info.setPort(cfg.getLocalHttpPort());
            if (Build.VERSION.SDK_INT >= 21) {
                info.setAttribute("station", cfg.getStationName());
                info.setAttribute("model", Build.MODEL);
                info.setAttribute("api", "2.0");
                info.setAttribute("flv", "/stream.flv");
                info.setAttribute("status", "/api/status");
            }
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo n) {}
                @Override public void onRegistrationFailed(NsdServiceInfo n, int err) {}
                @Override public void onServiceUnregistered(NsdServiceInfo n) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo n, int err) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Throwable ignored) {}
    }

    private void unregisterMdnsService() {
        try {
            if (nsdManager != null && nsdListener != null) nsdManager.unregisterService(nsdListener);
        } catch (Throwable ignored) {}
        nsdListener = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "PalaRez StreamCam", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 100, i, pFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("PalaRez StreamCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private static byte[] toBytes(ByteBuffer bb) {
        ByteBuffer dup = bb.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static byte[] trimAnnexBStart(byte[] src) {
        int off = 0;
        if (src.length >= 4 && src[0] == 0 && src[1] == 0 && src[2] == 0 && src[3] == 1) off = 4;
        else if (src.length >= 3 && src[0] == 0 && src[1] == 0 && src[2] == 1) off = 3;
        if (off == 0) return src;
        byte[] out = new byte[src.length - off];
        System.arraycopy(src, off, out, 0, out.length);
        return out;
    }

    @Override
    public void onDestroy() {
        stopRtmpBroadcast();
        stopEncoders();
        unregisterMdnsService();
        audioWorkerRunning = false;
        if (audioLoopThread != null) {
            audioLoopThread.interrupt();
            try { audioLoopThread.join(1000); } catch (InterruptedException ignored) {}
            audioLoopThread = null;
        }
        musicPlayer.stopAndUnload();
        releaseAudioRecord();
        if (ticker != null) ticker.shutdownNow();
        if (httpServer != null) httpServer.stop();
        if (camera != null) camera.stop();
        if (glRenderer != null) glRenderer.stop();
        if (telemetry != null) telemetry.stop();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
