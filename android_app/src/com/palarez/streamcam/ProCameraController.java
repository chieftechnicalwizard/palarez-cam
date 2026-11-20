package com.palarez.streamcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ProCameraController — Full Camera2 Hardware Enumeration &amp; Zero-Drop Hot-Tunable Engine.
 *
 * <p>Enumerates both logical and hidden/auxiliary physical Camera2 sensors (such as dedicated
 * {@code 40D} / {@code 2.5cm} macro lenses and OIS primary sensors on Motorola / Qualcomm HALs)
 * and maintains a dual-target {@link CameraCaptureSession}:
 * <ol>
 *   <li>Continuous {@link CameraDevice#TEMPLATE_RECORD} repeating stream into {@link HudGlRenderer}'s
 *       {@code SurfaceTexture} for zero-copy OpenGL HUD compositing and H.264 hardware encoding.</li>
 *   <li>Secondary {@link ImageReader} surface ({@code ImageFormat.JPEG} at maximum native sensor
 *       dimensions) for concurrent {@link CameraDevice#TEMPLATE_STILL_CAPTURE} photos &amp; timelapse
 *       frames without interrupting an active live broadcast.</li>
 * </ol>
 *
 * <p>Optical and exposure parameters (manual diopter focus, ISO, shutter speed, EV compensation,
 * AWB, AE anti-banding, zoom ratio, torch, OIS/EIS) update atomically via {@link #applyLiveControls()}
 * on the active repeating request without tearing down the capture session.
 */
public final class ProCameraController {
    /** Static hardware capabilities discovered for a single physical or logical Camera2 lens. */
    public static final class CameraSpec {
        public final String id;
        public final String label;
        public final int facing;
        public final int sensorOrientation;
        public final float focalLengthMm;
        public final float minFocusDiopter;
        public final boolean hasFlash;
        public final boolean hasOis;
        public final int isoMin;
        public final int isoMax;
        public final long expMinNs;
        public final long expMaxNs;
        public final int evMin;
        public final int evMax;
        public final float maxZoom;
        public final Size maxJpegSize;
        public final Rect activeArray;
        public final boolean manualSupported;

        CameraSpec(
                String id, String label, int facing, int sensorOrientation, float focalLengthMm, float minFocusDiopter,
                boolean hasFlash, boolean hasOis, int isoMin, int isoMax, long expMinNs, long expMaxNs,
                int evMin, int evMax, float maxZoom, Size maxJpegSize, Rect activeArray, boolean manualSupported) {
            this.id = id;
            this.label = label;
            this.facing = facing;
            this.sensorOrientation = sensorOrientation;
            this.focalLengthMm = focalLengthMm;
            this.minFocusDiopter = minFocusDiopter;
            this.hasFlash = hasFlash;
            this.hasOis = hasOis;
            this.isoMin = isoMin;
            this.isoMax = isoMax;
            this.expMinNs = expMinNs;
            this.expMaxNs = expMaxNs;
            this.evMin = evMin;
            this.evMax = evMax;
            this.maxZoom = maxZoom;
            this.maxJpegSize = maxJpegSize;
            this.activeArray = activeArray;
            this.manualSupported = manualSupported;
        }
    }

    /** Callback invoked on the Camera2 background thread when a full-resolution JPEG is ready. */
    public interface StillCallback {
        void onJpegCaptured(byte[] jpegBytes);
    }

    private final Context ctx;
    private final CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader stillReader;
    private Surface glInputSurface;
    private CameraSpec activeSpec;
    private SettingsStore activeSettings;
    private volatile StillCallback pendingStillCallback;

    public ProCameraController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.manager = (CameraManager) this.ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * Discovers all accessible logical and physical Camera2 sensors on the device.
     *
     * <p>Many Android OEMs (including Motorola) omit dedicated auxiliary macro or ultrawide
     * sensors from {@link CameraManager#getCameraIdList()} while still allowing direct open
     * by physical ID ({@code 0..4}) or via {@link CameraCharacteristics#getPhysicalCameraIds()}.
     * Probing both lists ensures macro lenses (e.g. {@code CAM 2} {@code 1.7mm} {@code 40D})
     * are selectable in the UI and {@code /api/capabilities}.
     */
    public List<CameraSpec> enumerateCameras() {
        List<CameraSpec> list = new ArrayList<>();
        if (manager == null) return list;
        try {
            String[] rawIds = manager.getCameraIdList();
            Log.i("PalaRezCam", "getCameraIdList=" + Arrays.toString(rawIds));
            List<String> candidateIds = new ArrayList<>(Arrays.asList(rawIds));
            // Also probe Motorola/Android hidden physical/aux camera IDs (0..5) if present
            for (String id : rawIds) {
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        for (String pid : manager.getCameraCharacteristics(id).getPhysicalCameraIds()) {
                            if (!candidateIds.contains(pid)) candidateIds.add(pid);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            for (int extra = 0; extra <= 4; extra++) {
                String eid = String.valueOf(extra);
                if (!candidateIds.contains(eid)) candidateIds.add(eid);
            }

            for (String id : candidateIds) {
                try {
                    CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                    Integer facingObj = cc.get(CameraCharacteristics.LENS_FACING);
                    int facing = facingObj != null ? facingObj : CameraCharacteristics.LENS_FACING_BACK;
                    Integer orientObj = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    int sensorOrient = orientObj != null ? orientObj : 90;
                    float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float focal = (focals != null && focals.length > 0) ? focals[0] : 0f;
                    Float minFocus = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    float minD = minFocus != null ? minFocus : 0f;
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    int[] oisModes = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                    boolean hasOis = false;
                    if (oisModes != null) {
                        for (int m : oisModes) if (m == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) hasOis = true;
                    }
                    Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    int isoMin = isoRange != null ? isoRange.getLower() : 100;
                    int isoMax = isoRange != null ? isoRange.getUpper() : 3200;
                    Range<Long> expRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    long expMin = expRange != null ? expRange.getLower() : 100_000L;
                    // Cap maximum manual shutter at 500 ms (1/2s) so multi-second exposures cannot stall live video pipelines
                    long expMax = expRange != null ? Math.min(expRange.getUpper(), 500_000_000L) : 100_000_000L;
                    Range<Integer> evRange = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    int evMin = evRange != null ? evRange.getLower() : -6;
                    int evMax = evRange != null ? evRange.getUpper() : 6;

                    float maxZoom = 4.0f;
                    if (Build.VERSION.SDK_INT >= 30) {
                        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                        if (zr != null) maxZoom = zr.getUpper();
                    } else {
                        Float mz = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                        if (mz != null) maxZoom = mz;
                    }

                    StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size bestJpeg = new Size(1920, 1080);
                    if (map != null) {
                        Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                        if (jpegSizes != null && jpegSizes.length > 0) {
                            Arrays.sort(jpegSizes, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
                            bestJpeg = jpegSizes[0];
                        }
                    }
                    Rect arr = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean manual = false;
                    if (caps != null) {
                        for (int c : caps) {
                            if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manual = true;
                        }
                    }
                    String fTag = facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT"
                        : facing == CameraCharacteristics.LENS_FACING_EXTERNAL ? "EXT" : "BACK";
                    String roleHint = minD >= 20f ? "MACRO" : (hasOis ? "OIS" : "");
                    String label = String.format(Locale.US, "CAM %s (%s %.1fmm%s %.0fD)",
                        id, fTag, focal, roleHint.isEmpty() ? "" : " " + roleHint, minD);

                    list.add(new CameraSpec(
                        id, label, facing, sensorOrient, focal, minD, Boolean.TRUE.equals(flash), hasOis,
                        isoMin, isoMax, expMin, expMax, evMin, evMax, maxZoom, bestJpeg, arr, manual
                    ));
                    Log.i("PalaRezCam", "Discovered " + label);
                } catch (Throwable tPerCam) {
                    Log.w("PalaRezCam", "Skip camera id=" + id + ": " + tPerCam.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w("PalaRezCam", "enumerateCameras failed: " + t.getMessage());
        }
        return list;
    }

    public CameraSpec findSpec(String cameraId) {
        List<CameraSpec> all = enumerateCameras();
        for (CameraSpec s : all) if (s.id.equals(cameraId)) return s;
        return all.isEmpty() ? null : all.get(0);
    }

    public synchronized void start(Surface glInputSurface, SettingsStore cfg, Runnable onReady) {
        stop();
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        this.glInputSurface = glInputSurface;
        this.activeSettings = cfg;
        this.activeSpec = findSpec(cfg.getCameraId());
        if (this.activeSpec == null || manager == null) return;

        camThread = new HandlerThread("PalaRezCam2");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        Size stillSize = activeSpec.maxJpegSize;
        stillReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(reader -> {
            try (Image img = reader.acquireLatestImage()) {
                if (img == null) return;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                StillCallback cb = pendingStillCallback;
                pendingStillCallback = null;
                if (cb != null) cb.onJpegCaptured(bytes);
            } catch (Throwable ignored) {}
        }, camHandler);

        try {
            manager.openCamera(activeSpec.id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession(onReady);
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, camHandler);
        } catch (CameraAccessException | SecurityException ignored) {}
    }

    private void createSession(Runnable onReady) {
        if (cameraDevice == null || glInputSurface == null || stillReader == null) return;
        try {
            List<Surface> targets = Arrays.asList(glInputSurface, stillReader.getSurface());
            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    applyLiveControls();
                    if (onReady != null) onReady.run();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {}
            }, camHandler);
        } catch (Throwable ignored) {}
    }

    /**
     * Rebuilds and submits the repeating {@link CameraDevice#TEMPLATE_RECORD} request so focus,
     * exposure, AWB, anti-banding, zoom, torch, and stabilization changes take effect seamlessly
     * on the next sensor frame without dropping the active stream.
     */
    public synchronized void applyLiveControls() {
        if (cameraDevice == null || captureSession == null || glInputSurface == null || activeSettings == null || activeSpec == null) {
            return;
        }
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(glInputSurface);
            populateControls(b);
            captureSession.setRepeatingRequest(b.build(), null, camHandler);
        } catch (Throwable ignored) {}
    }

    /**
     * Captures a single native-resolution JPEG ({@code quality=95}) via the secondary
     * {@link ImageReader} surface while the repeating video stream continues uninterrupted.
     */
    public synchronized void captureFullResStill(StillCallback cb) {
        if (cameraDevice == null || captureSession == null || stillReader == null || activeSettings == null || activeSpec == null) {
            return;
        }
        try {
            pendingStillCallback = cb;
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(stillReader.getSurface());
            populateControls(b);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            captureSession.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {}
            }, camHandler);
        } catch (Throwable ignored) {}
    }

    private void populateControls(CaptureRequest.Builder b) {
        SettingsStore s = activeSettings;
        CameraSpec spec = activeSpec;

        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        // FPS range lock aligned with target FPS
        int fps = s.getFps();
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(Math.min(15, fps), fps));

        // Focus: Auto Continuous Video OR Manual Diopter Distance
        if (s.isManualFocus() && spec.minFocusDiopter > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            float d = Math.max(0f, Math.min(spec.minFocusDiopter, s.getFocusDistance()));
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }

        // Exposure: Manual ISO + Shutter OR Auto + EV compensation
        if (s.isManualExposure() && spec.manualSupported) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            int iso = Math.max(spec.isoMin, Math.min(spec.isoMax, s.getManualIso()));
            long expNs = Math.max(spec.expMinNs, Math.min(spec.expMaxNs, s.getManualShutterNs()));
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            int ev = Math.max(spec.evMin, Math.min(spec.evMax, s.getExposureComp()));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        }

        // White balance
        b.set(CaptureRequest.CONTROL_AWB_MODE, parseAwbMode(s.getAwbMode()));

        // Grow-light / AC mains AE anti-banding: locks auto-exposure integration times to integer
        // half-cycles (1/120s for 60Hz, 1/100s for 50Hz) when AE is active. Note: when manual_exposure
        // is enabled (CONTROL_AE_MODE_OFF), hardware AE antibanding is bypassed by Camera2; pair manual
        // shutter speeds (e.g. 1/120s or 1/60s) with 60hz_tmix/50hz_tmix GPU temporal averaging if needed.
        String afMode = s.getAntiFlickerMode();
        if ("50hz_tmix".equals(afMode)) {
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
        } else if ("60hz_hw".equals(afMode) || "60hz_tmix".equals(afMode)) {
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ);
        } else {
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        }

        // Torch / Flashlight
        if (spec.hasFlash) {
            b.set(CaptureRequest.FLASH_MODE, s.isTorchOn() ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }

        // Zoom
        float zoom = Math.max(1.0f, Math.min(spec.maxZoom, s.getZoomRatio()));
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        } else if (spec.activeArray != null && zoom > 1.0f) {
            int cx = spec.activeArray.centerX();
            int cy = spec.activeArray.centerY();
            int dx = (int) (spec.activeArray.width() / (2f * zoom));
            int dy = (int) (spec.activeArray.height() / (2f * zoom));
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cx - dx, cy - dy, cx + dx, cy + dy));
        }

        // Stabilization (OIS / EIS)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            s.isStabilizationOn() ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (spec.hasOis) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                s.isStabilizationOn() ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
    }

    private static int parseAwbMode(String mode) {
        if ("daylight".equalsIgnoreCase(mode)) return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
        if ("cloudy".equalsIgnoreCase(mode)) return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
        if ("incandescent".equalsIgnoreCase(mode) || "tungsten".equalsIgnoreCase(mode)) return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
        if ("fluorescent".equalsIgnoreCase(mode)) return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
        if ("shade".equalsIgnoreCase(mode)) return CaptureRequest.CONTROL_AWB_MODE_SHADE;
        return CaptureRequest.CONTROL_AWB_MODE_AUTO;
    }

    public CameraSpec selectActiveSpec(String cameraId) {
        this.activeSpec = findSpec(cameraId);
        return this.activeSpec;
    }

    public CameraSpec getActiveSpec() { return activeSpec; }

    public synchronized void stop() {
        try { if (captureSession != null) captureSession.close(); } catch (Throwable ignored) {}
        captureSession = null;
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Throwable ignored) {}
        cameraDevice = null;
        try { if (stillReader != null) stillReader.close(); } catch (Throwable ignored) {}
        stillReader = null;
        if (camThread != null) {
            camThread.quitSafely();
            try { camThread.join(1000); } catch (InterruptedException ignored) {}
            camThread = null;
        }
        camHandler = null;
    }
}
