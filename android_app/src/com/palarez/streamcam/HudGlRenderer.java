package com.palarez.streamcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HudGlRenderer — Zero-Copy Hardware OpenGL ES 2.0 Camera, 5-Frame Temporal Anti-Strobe
 * Filter &amp; Cyber-Flora Sanctuary Glass HUD Compositor.
 *
 * <h3>Rendering &amp; Multi-Surface Architecture</h3>
 * <ul>
 *   <li><b>Single Shared EGLContext</b>: Anchored to an always-valid {@code 640x360} Pbuffer
 *       ({@link #pbufferSurface}) and dynamically bound per frame to the hardware H.264 encoder
 *       {@link EGLSurface} ({@link #encoderEglSurface} with {@link EGLExt#eglPresentationTimeANDROID})
 *       and the cockpit viewfinder {@link EGLSurface} ({@link #previewEglSurface}).</li>
 *   <li><b>5-Frame GPU Temporal Anti-Strobe ({@code 60hz_tmix} / {@code 50hz_tmix})</b>: When
 *       enabled, upright camera frames are pushed into a 5-slot RGBA FBO ring ({@link #ringFbos})
 *       and box-averaged via {@link #FS_TMIX5} <i>before</i> HUD compositing so rolling-shutter
 *       LED grow-light banding is suppressed while HUD text stays 100% crisp. FBO VRAM is freed
 *       immediately via {@link #releaseTmixFbos()} when switched off.</li>
 *   <li><b>Sanctuary Glass HUD ({@code 1280x720} ARGB_8888 Canvas Texture)</b>: Redrawn at most
 *       4 Hz ({@code >= 250 ms}) and alpha-composited with premultiplied blending
 *       ({@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA}) in {@code "sanctuary_card"}, {@code "minimal_pill"},
 *       or zero-burn-in {@code "clean"} mode.</li>
 *   <li><b>Preview-Only 2.5x Focus Loupe &amp; Idle-Gated JPEG Readback</b>: Double-tapping the
 *       phone viewfinder applies a {@code 2.5x} center crop only on {@link #previewEglSurface}
 *       (never affecting the live broadcast), and {@code glReadPixels} JPEG compression runs only
 *       when {@code /snapshot.jpg} is requested or an {@code /mjpeg} viewer was active within 15s.</li>
 * </ul>
 */
public final class HudGlRenderer {
    private static final String VS =
        "uniform mat4 uMvpMatrix;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "attribute vec4 aPos;\n" +
        "attribute vec4 aTex;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = uMvpMatrix * aPos;\n" +
        "  vTex = (uTexMatrix * aTex).xy;\n" +
        "}\n";

    private static final String FS_OES =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES sTex;\n" +
        "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final String FS_2D =
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform sampler2D sTex;\n" +
        "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final String VS_PASS2D =
        "uniform mat4 uMvpMatrix;\n" +
        "attribute vec4 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = uMvpMatrix * aPos;\n" +
        "  vTex = aTex;\n" +
        "}\n";

    private static final String FS_TMIX5 =
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform sampler2D sTex0;\n" +
        "uniform sampler2D sTex1;\n" +
        "uniform sampler2D sTex2;\n" +
        "uniform sampler2D sTex3;\n" +
        "uniform sampler2D sTex4;\n" +
        "uniform float uW0;\n" +
        "uniform float uW1;\n" +
        "uniform float uW2;\n" +
        "uniform float uW3;\n" +
        "uniform float uW4;\n" +
        "uniform float uInvSum;\n" +
        "void main() {\n" +
        "  vec3 c = texture2D(sTex0, vTex).rgb * uW0\n" +
        "         + texture2D(sTex1, vTex).rgb * uW1\n" +
        "         + texture2D(sTex2, vTex).rgb * uW2\n" +
        "         + texture2D(sTex3, vTex).rgb * uW3\n" +
        "         + texture2D(sTex4, vTex).rgb * uW4;\n" +
        "  gl_FragColor = vec4(c * uInvSum, 1.0);\n" +
        "}\n";

    private static final float[] FULL_QUAD = {
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    };

    // Full-frame HUD quad with Android Canvas top-down Y flip (y=+1 -> v=0, y=-1 -> v=1)
    private static final float[] HUD_QUAD = {
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f
    };

    private static final int HUD_W = 1280;
    private static final int HUD_H = 720;

    private HandlerThread glThread;
    private Handler glHandler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLConfig eglConfig;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbufferSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private Surface encoderSurface;
    private Surface previewSurface;

    private int progOes;
    private int prog2d;
    private int progTmix;
    private int oesAPos, oesATex, oesUMvp, oesUTexMtx, oesSTex;
    private int twoDAPos, twoDATex, twoDUMvp, twoDUTexMtx, twoDSTex;
    private int tmixAPos, tmixATex, tmixUMvp, tmixUInvSum;
    private final int[] tmixSTex = new int[5];
    private final int[] tmixUW = new int[5];
    private static final int RING_N = 5;
    private final int[] ringFbos = new int[RING_N];
    private final int[] ringTexs = new int[RING_N];
    private final boolean[] ringValid = new boolean[RING_N];
    private int ringW = 0, ringH = 0, ringIdx = 0, ringCount = 0;
    private volatile boolean antiFlickerTmix = false;
    private int texOes;
    private int texHud;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraInputSurface;
    private final float[] texMatrix = new float[16];
    private final float[] idMatrix = new float[16];
    private final float[] rotMatrix = new float[16];
    private final float[] finalCamMatrix = new float[16];
    private final float[] camMvpMatrix = new float[16];
    private final float[] loupeMvpMatrix = new float[16];

    private FloatBuffer fullQuadBuf;
    private FloatBuffer hudQuadBuf;
    private Bitmap hudBitmap;
    private Canvas hudCanvas;

    private Paint cardFillPaint;
    private Paint cardStrokePaint;
    private Paint emeraldBarPaint;
    private Paint pillFillPaint;
    private Paint topPillFillPaint;
    private Paint footerFillPaint;
    private Paint footerStrokePaint;
    private Paint dividerPaint;
    private Paint reticlePaint;
    private Paint dotPaint;
    private Paint haloPaint;
    private Paint titlePaint;
    private Paint subPaint;
    private Paint badgePaint;
    private Paint dimPaint;

    private volatile boolean hudDirty = true;
    private volatile TelemetryCollector.HudSnapshot pendingHud;
    private volatile boolean hudVisible = true;
    private volatile int extraRotationDeg = 0;
    private volatile int sensorOrientationDeg = 90;
    private volatile boolean cameraFrontFacing = false;
    private volatile boolean previewLoupe = false;
    private volatile long encoderFrameIntervalNs = 0L;
    private long lastEncoderFrameNs = 0L;

    private ByteBuffer grabPixelsBuf;
    private int grabW = 0;
    private int grabH = 0;
    private Bitmap grabBitmap;
    private Bitmap grabUprightBitmap;
    private Canvas grabCanvas;
    private final android.graphics.Matrix grabFlipMatrix = new android.graphics.Matrix();
    private final ByteArrayOutputStream grabBaos = new ByteArrayOutputStream(32768);

    private int width = 1920;
    private int height = 1080;
    private int previewW = 640;
    private int previewH = 360;
    private boolean loggedTexMatrix = false;
    private long lastHudUploadMs = 0;
    private long lastJpegGrabMs = 0;
    private volatile long lastMjpegRequestMs = 0;
    private volatile byte[] latestJpeg;
    private volatile boolean running = false;

    public synchronized void start(int w, int h, Runnable onCameraSurfaceReady) {
        stop();
        this.width = w;
        this.height = h;
        this.loggedTexMatrix = false;
        Matrix.setIdentityM(idMatrix, 0);
        running = true;

        fullQuadBuf = ByteBuffer.allocateDirect(FULL_QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fullQuadBuf.put(FULL_QUAD).position(0);
        hudQuadBuf = ByteBuffer.allocateDirect(HUD_QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        hudQuadBuf.put(HUD_QUAD).position(0);

        hudBitmap = Bitmap.createBitmap(HUD_W, HUD_H, Bitmap.Config.ARGB_8888);
        hudCanvas = new Canvas(hudBitmap);
        initHudPaints();

        glThread = new HandlerThread("PalaRezGL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(() -> {
            initEgl();
            initShadersAndTextures();
            if (onCameraSurfaceReady != null) onCameraSurfaceReady.run();
        });
    }

    private void initHudPaints() {
        cardFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardFillPaint.setShader(new LinearGradient(18f, 572f, 740f, 700f,
            new int[] { Color.argb(178, 7, 18, 10), Color.argb(158, 11, 27, 17), Color.argb(190, 5, 14, 8) },
            null, Shader.TileMode.CLAMP));

        cardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardStrokePaint.setStyle(Paint.Style.STROKE);
        cardStrokePaint.setStrokeWidth(1.4f);
        cardStrokePaint.setColor(Color.argb(135, 16, 185, 129));

        emeraldBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emeraldBarPaint.setShader(new LinearGradient(0f, 572f, 0f, 700f,
            new int[] { Color.parseColor("#34D399"), Color.parseColor("#10B981"), Color.parseColor("#059669") },
            null, Shader.TileMode.CLAMP));

        pillFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillFillPaint.setColor(Color.argb(68, 16, 185, 129));

        topPillFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        topPillFillPaint.setShader(new LinearGradient(720f, 18f, 1260f, 54f,
            Color.argb(215, 7, 18, 10), Color.argb(205, 4, 12, 6), Shader.TileMode.CLAMP));

        footerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerFillPaint.setColor(Color.argb(150, 4, 10, 6));

        footerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerStrokePaint.setStyle(Paint.Style.STROKE);
        footerStrokePaint.setStrokeWidth(1f);
        footerStrokePaint.setColor(Color.argb(65, 16, 185, 129));

        dividerPaint = new Paint();
        dividerPaint.setColor(Color.argb(140, 31, 41, 55));
        dividerPaint.setStrokeWidth(1.2f);

        reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(2.2f);
        reticlePaint.setColor(Color.argb(140, 52, 211, 153));

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titlePaint.setTextSize(17.5f);

        subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(Color.parseColor("#E5E7EB"));
        subPaint.setTypeface(Typeface.MONOSPACE);
        subPaint.setTextSize(15.5f);

        badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setColor(Color.parseColor("#34D399"));
        badgePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        badgePaint.setTextSize(13f);

        dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setColor(Color.parseColor("#9CA3AF"));
        dimPaint.setTypeface(Typeface.MONOSPACE);
        dimPaint.setTextSize(14f);
    }

    public Surface getCameraInputSurface() {
        return cameraInputSurface;
    }

    public void updateHud(TelemetryCollector.HudSnapshot snap) {
        pendingHud = snap;
        hudVisible = snap != null && snap.hudVisible;
        hudDirty = true;
    }

    public void setExtraRotationDeg(int deg) {
        int norm = ((deg % 360) + 360) % 360;
        if (norm != this.extraRotationDeg) {
            this.extraRotationDeg = norm;
            Handler h = glHandler;
            if (h != null) {
                h.post(() -> {
                    ringCount = 0;
                    ringIdx = 0;
                    java.util.Arrays.fill(ringValid, false);
                });
            }
        }
    }

    public void setCameraGeometry(int sensorOrientationDeg, boolean frontFacing) {
        this.sensorOrientationDeg = sensorOrientationDeg;
        this.cameraFrontFacing = frontFacing;
    }

    public void setTargetFps(int fps) {
        this.encoderFrameIntervalNs = fps > 0 ? (1_000_000_000L / fps) : 0L;
    }

    public void setPreviewLoupe(boolean enabled) {
        this.previewLoupe = enabled;
    }

    public void setAntiFlickerTmix(boolean enabled) {
        this.antiFlickerTmix = enabled;
        if (!enabled) {
            Handler h = glHandler;
            if (h != null) {
                h.post(() -> {
                    ringCount = 0;
                    ringIdx = 0;
                    java.util.Arrays.fill(ringValid, false);
                });
            }
        }
    }

    public void setEncoderSurface(Surface s) {
        Handler h = glHandler;
        if (h == null) return;
        CountDownLatch latch = (s == null && Thread.currentThread() != glThread) ? new CountDownLatch(1) : null;
        h.post(() -> {
            try {
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                    }
                    EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
                    encoderEglSurface = EGL14.EGL_NO_SURFACE;
                }
                encoderSurface = s;
                lastEncoderFrameNs = 0L;
                if (s != null && s.isValid() && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, s, new int[] { EGL14.EGL_NONE }, 0);
                }
            } finally {
                if (latch != null) latch.countDown();
            }
        });
        if (latch != null) {
            try { latch.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
    }

    public void setPreviewSurface(Surface s, int pw, int ph) {
        Handler h = glHandler;
        if (h == null) return;
        CountDownLatch latch = (s == null && Thread.currentThread() != glThread) ? new CountDownLatch(1) : null;
        h.post(() -> {
            try {
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                    }
                    EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                    previewEglSurface = EGL14.EGL_NO_SURFACE;
                }
                previewSurface = s;
                previewW = Math.max(1, pw);
                previewH = Math.max(1, ph);
                if (s != null && s.isValid() && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, s, new int[] { EGL14.EGL_NONE }, 0);
                }
            } finally {
                if (latch != null) latch.countDown();
            }
        });
        if (latch != null) {
            try { latch.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
    }

    public byte[] getLatestJpeg() {
        lastMjpegRequestMs = System.currentTimeMillis();
        return latestJpeg;
    }

    public byte[] captureJpegSync(boolean clean) {
        if (!running || glHandler == null || Thread.currentThread() == glThread) {
            return latestJpeg;
        }
        AtomicReference<byte[]> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(() -> {
            try {
                if (running && eglDisplay != EGL14.EGL_NO_DISPLAY && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                    GLES20.glViewport(0, 0, 640, 360);
                    drawScene(!clean, false);
                    out.set(grabPbufferJpegBytes(640, 360));
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(450, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        byte[] res = out.get();
        return res != null ? res : latestJpeg;
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);

        int[] cfgAttrs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, num, 0);
        eglConfig = configs[0];

        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
            new int[] { EGL14.EGL_WIDTH, 640, EGL14.EGL_HEIGHT, 360, EGL14.EGL_NONE }, 0);
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
        if (previewSurface != null && previewSurface.isValid() && previewEglSurface == EGL14.EGL_NO_SURFACE) {
            previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, previewSurface, new int[] { EGL14.EGL_NONE }, 0);
        }
    }

    private void initShadersAndTextures() {
        progOes = buildProgram(VS, FS_OES);
        oesAPos = GLES20.glGetAttribLocation(progOes, "aPos");
        oesATex = GLES20.glGetAttribLocation(progOes, "aTex");
        oesUMvp = GLES20.glGetUniformLocation(progOes, "uMvpMatrix");
        oesUTexMtx = GLES20.glGetUniformLocation(progOes, "uTexMatrix");
        oesSTex = GLES20.glGetUniformLocation(progOes, "sTex");

        prog2d = buildProgram(VS, FS_2D);
        twoDAPos = GLES20.glGetAttribLocation(prog2d, "aPos");
        twoDATex = GLES20.glGetAttribLocation(prog2d, "aTex");
        twoDUMvp = GLES20.glGetUniformLocation(prog2d, "uMvpMatrix");
        twoDUTexMtx = GLES20.glGetUniformLocation(prog2d, "uTexMatrix");
        twoDSTex = GLES20.glGetUniformLocation(prog2d, "sTex");

        progTmix = buildProgram(VS_PASS2D, FS_TMIX5);
        tmixAPos = GLES20.glGetAttribLocation(progTmix, "aPos");
        tmixATex = GLES20.glGetAttribLocation(progTmix, "aTex");
        tmixUMvp = GLES20.glGetUniformLocation(progTmix, "uMvpMatrix");
        tmixUInvSum = GLES20.glGetUniformLocation(progTmix, "uInvSum");
        for (int i = 0; i < RING_N; i++) {
            tmixSTex[i] = GLES20.glGetUniformLocation(progTmix, "sTex" + i);
            tmixUW[i] = GLES20.glGetUniformLocation(progTmix, "uW" + i);
        }

        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);
        texOes = tex[0];
        texHud = tex[1];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHud);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hudBitmap, 0);

        cameraSurfaceTexture = new SurfaceTexture(texOes);
        cameraSurfaceTexture.setDefaultBufferSize(width, height);
        cameraSurfaceTexture.setOnFrameAvailableListener(st -> {
            if (running && glHandler != null) glHandler.post(this::onFrame);
        }, glHandler);
        cameraInputSurface = new Surface(cameraSurfaceTexture);
    }

    private void onFrame() {
        if (!running || cameraSurfaceTexture == null || eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        try {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(texMatrix);
            if (!loggedTexMatrix) {
                loggedTexMatrix = true;
                android.util.Log.i("PalaRezGL", "texMatrix=" + java.util.Arrays.toString(texMatrix) + " camSize=" + width + "x" + height);
            }
            long ptsNs = cameraSurfaceTexture.getTimestamp();
            if (ptsNs <= 0) ptsNs = System.nanoTime();

            if (antiFlickerTmix) {
                pushFrameIntoTmixRing();
            } else if (ringW != 0) {
                releaseTmixFbos();
            }

            long now = System.currentTimeMillis();
            if (hudDirty && now - lastHudUploadMs >= 250) {
                redrawHudTexture();
                hudDirty = false;
                lastHudUploadMs = now;
            }

            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                long intervalNs = encoderFrameIntervalNs;
                // 85% interval gate absorbs minor Camera2 frame-delivery jitter while cleanly
                // decimating 30fps HAL deliveries down to 15fps or 24fps target cadence.
                boolean due = (intervalNs <= 0L) || (lastEncoderFrameNs == 0L) || ((ptsNs - lastEncoderFrameNs) >= (intervalNs * 85L) / 100L);
                if (due) {
                    lastEncoderFrameNs = ptsNs;
                    EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext);
                    GLES20.glViewport(0, 0, width, height);
                    drawScene(hudVisible, false);
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, ptsNs);
                    EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface);
                }
            }

            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext);
                GLES20.glViewport(0, 0, previewW, previewH);
                drawScene(hudVisible, previewLoupe);
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface);
            }

            // Capture throttled 640x360 JPEG into memory when LAN viewer or initial frame needs one
            if (latestJpeg == null || (now - lastMjpegRequestMs < 15000 && now - lastJpegGrabMs >= 200)) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                GLES20.glViewport(0, 0, 640, 360);
                drawScene(hudVisible, false);
                latestJpeg = grabPbufferJpegBytes(640, 360);
                lastJpegGrabMs = now;
            }
        } catch (Throwable ignored) {}
    }

    private void redrawHudTexture() {
        hudBitmap.eraseColor(Color.TRANSPARENT);
        TelemetryCollector.HudSnapshot s = pendingHud;
        if (!hudVisible || s == null || "clean".equals(s.hudMode)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHud);
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, hudBitmap);
            return;
        }

        // 1. Delicate 4-corner Cyber-Flora viewport framing reticles
        drawCornerReticle(18f, 18f, 1f, 1f);
        drawCornerReticle(HUD_W - 18f, 18f, -1f, 1f);
        drawCornerReticle(18f, HUD_H - 18f, 1f, -1f);
        drawCornerReticle(HUD_W - 18f, HUD_H - 18f, -1f, -1f);

        // 2. Top-Right Broadcast Status & Clock Pill
        RectF topPill = new RectF(738f, 18f, HUD_W - 18f, 54f);
        hudCanvas.drawRoundRect(topPill, 18f, 18f, topPillFillPaint);
        hudCanvas.drawRoundRect(topPill, 18f, 18f, cardStrokePaint);

        int dotCol = s.rtmpConnected ? Color.parseColor("#EF4444")
            : ("ARMED".equals(s.streamState) ? Color.parseColor("#F59E0B") : Color.parseColor("#38BDF8"));
        haloPaint.setColor(Color.argb(85, Color.red(dotCol), Color.green(dotCol), Color.blue(dotCol)));
        dotPaint.setColor(dotCol);
        hudCanvas.drawCircle(760f, 36f, 8.5f, haloPaint);
        hudCanvas.drawCircle(760f, 36f, 4.5f, dotPaint);

        String topLabel = s.rtmpConnected
            ? String.format(java.util.Locale.US, "LIVE %.0ffps  |  %s  |  %s", s.measuredFps, s.station, s.clockText)
            : String.format(java.util.Locale.US, "%s  |  %s  |  %s", s.streamState, s.station, s.clockText);
        titlePaint.setTextSize(14f);
        titlePaint.setColor(Color.WHITE);
        hudCanvas.drawText(topLabel, 777f, 41f, titlePaint);

        if ("minimal_pill".equals(s.hudMode)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHud);
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, hudBitmap);
            return;
        }

        // 3. Bottom-Left Compact Sanctuary Translucent Glass Telemetry Card
        float cx = 18f;
        float cw = 770f;
        float ch = s.hudGps ? 140f : 118f;
        float cy = 702f - ch;
        RectF cardRect = new RectF(cx, cy, cx + cw, cy + ch);
        hudCanvas.drawRoundRect(cardRect, 12f, 12f, cardFillPaint);
        hudCanvas.drawRoundRect(cardRect, 12f, 12f, cardStrokePaint);

        // Left vertical emerald bar
        hudCanvas.drawRoundRect(new RectF(cx, cy, cx + 6f, cy + ch), 3f, 3f, emeraldBarPaint);

        // Header SENSORS pill
        RectF pillRect = new RectF(cx + 16f, cy + 10f, cx + 114f, cy + 34f);
        hudCanvas.drawRoundRect(pillRect, 12f, 12f, pillFillPaint);
        hudCanvas.drawRoundRect(pillRect, 12f, 12f, cardStrokePaint);
        badgePaint.setTextSize(12f);
        hudCanvas.drawText("SENSORS", cx + 29f, cy + 26.5f, badgePaint);

        // Row 1: Priority Atmospheric & Light Telemetry
        if (s.hudEnv) {
            String envLine = String.format(java.util.Locale.US,
                "%s   |   %s   |   %s   |   %s   |   %s",
                s.tempText, s.pressureText, s.altitudeText, s.lightText, s.humidityText);
            titlePaint.setTextSize(15.5f);
            hudCanvas.drawText(fitText(envLine, titlePaint, cw - 140f), cx + 126f, cy + 27.5f, titlePaint);
        } else {
            titlePaint.setTextSize(15.5f);
            hudCanvas.drawText(s.station + " Telemetry Active", cx + 126f, cy + 27.5f, titlePaint);
        }

        hudCanvas.drawLine(cx + 16f, cy + 43f, cx + cw - 16f, cy + 43f, dividerPaint);

        // Row 2: Compass + Gravity Spirit Level + Gyroscope (+ Optional GPS)
        if (s.hudMotion) {
            String motionLine = String.format(java.util.Locale.US,
                "HDG %s   |   GRAV %s   |   GYRO %s",
                s.compassText, s.gravityText, s.gyroText);
            subPaint.setTextSize(14f);
            subPaint.setColor(Color.parseColor("#E5E7EB"));
            hudCanvas.drawText(fitText(motionLine, subPaint, cw - 32f), cx + 16f, cy + 68f, subPaint);
        }
        if (s.hudGps && s.gpsText != null && !s.gpsText.isEmpty()) {
            badgePaint.setTextSize(13.5f);
            hudCanvas.drawText(fitText(s.gpsText, badgePaint, cw - 32f), cx + 16f, cy + 91f, badgePaint);
        }

        // Row 3: Inset Dark Footer Meta Strip
        float footY = cy + (s.hudGps ? 100f : 80f);
        RectF foot = new RectF(cx + 14f, footY, cx + cw - 14f, footY + 28f);
        hudCanvas.drawRoundRect(foot, 5f, 5f, footerFillPaint);
        hudCanvas.drawRoundRect(foot, 5f, 5f, footerStrokePaint);

        StringBuilder meta = new StringBuilder();
        if (s.hudCameraParams && s.camBadgeText != null) meta.append(s.camBadgeText);
        if (s.hudRadioPower && s.radioBadgeText != null && !s.radioBadgeText.isEmpty()) {
            if (meta.length() > 0) meta.append("   |   ");
            meta.append(s.radioBadgeText);
        }
        dimPaint.setTextSize(13f);
        hudCanvas.drawText(fitText(meta.toString(), dimPaint, cw - 48f), cx + 24f, footY + 19f, dimPaint);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHud);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, hudBitmap);
    }

    private void drawCornerReticle(float x, float y, float dx, float dy) {
        Path p = new Path();
        p.moveTo(x, y + 26f * dy);
        p.lineTo(x, y);
        p.lineTo(x + 26f * dx, y);
        hudCanvas.drawPath(p, reticlePaint);
    }

    private static String fitText(String txt, Paint p, float maxW) {
        if (txt == null) return "";
        if (p.measureText(txt) <= maxW) return txt;
        String ell = "...";
        int end = txt.length();
        while (end > 4 && p.measureText(txt, 0, end) + p.measureText(ell) > maxW) end--;
        return txt.substring(0, end) + ell;
    }

    private void drawScene(boolean includeHud, boolean applyLoupe) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);

        Matrix.setIdentityM(camMvpMatrix, 0);
        // Pillarbox ONLY when user explicitly chooses Portrait output (90 or 270 deg) inside a 16:9 stream:
        // rotating a 16:9 frame by 90 deg yields a 9:16 image whose width inside a 16:9 canvas is (9/16)^2.
        if (extraRotationDeg == 90 || extraRotationDeg == 270) {
            float aspect = Math.min(1f, (float) height / Math.max(1, width));
            Matrix.scaleM(camMvpMatrix, 0, aspect * aspect, 1f, 1f);
        }
        if (applyLoupe) {
            Matrix.scaleM(loupeMvpMatrix, 0, camMvpMatrix, 0, 2.5f, 2.5f, 1f);
        } else {
            System.arraycopy(camMvpMatrix, 0, loupeMvpMatrix, 0, 16);
        }

        // Android SurfaceTexture.getTransformMatrix(texMatrix) often embeds a 90/270 Portrait transform.
        // Detect & cancel any embedded +/-90 deg rotation in texMatrix so 0 deg = true full-16:9 Landscape.
        int baseFixDeg = 0;
        if (Math.abs(texMatrix[0]) < 0.25f && Math.abs(texMatrix[1]) > 0.5f) {
            baseFixDeg = (texMatrix[1] * texMatrix[4] > 0f) ? 270 : 90;
        }
        // Front cameras with 270 sensorOrientation relative to back (90) need 180 deg flip in landscape
        if (cameraFrontFacing && sensorOrientationDeg == 270) {
            baseFixDeg = (baseFixDeg + 180) % 360;
        }
        int totalUvRotDeg = (baseFixDeg + extraRotationDeg) % 360;

        // Pass 1: Either 5-frame GPU temporal anti-strobe mix OR direct single-frame camera OES
        if (antiFlickerTmix && ringCount > 0) {
            drawTmixCameraQuad(loupeMvpMatrix);
        } else {
            drawOesCameraQuad(loupeMvpMatrix, totalUvRotDeg);
        }

        // Pass 2: HUD RGBA overlay (premultiplied-alpha Bitmap texture -> GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        if (includeHud) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(prog2d);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texHud);
            drawQuad(twoDAPos, twoDATex, twoDUMvp, twoDUTexMtx, twoDSTex, hudQuadBuf, idMatrix, idMatrix);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void drawOesCameraQuad(float[] mvp, int totalUvRotDeg) {
        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        if (totalUvRotDeg == 0) {
            drawQuad(oesAPos, oesATex, oesUMvp, oesUTexMtx, oesSTex, fullQuadBuf, mvp, texMatrix);
        } else {
            Matrix.setIdentityM(rotMatrix, 0);
            Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f);
            Matrix.rotateM(rotMatrix, 0, totalUvRotDeg, 0f, 0f, 1f);
            Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f);
            Matrix.multiplyMM(finalCamMatrix, 0, texMatrix, 0, rotMatrix, 0);
            drawQuad(oesAPos, oesATex, oesUMvp, oesUTexMtx, oesSTex, fullQuadBuf, mvp, finalCamMatrix);
        }
    }

    private void ensureTmixFbos(int w, int h) {
        if (ringW == w && ringH == h && ringFbos[0] != 0) return;
        releaseTmixFbos();
        GLES20.glGenFramebuffers(RING_N, ringFbos, 0);
        GLES20.glGenTextures(RING_N, ringTexs, 0);
        for (int i = 0; i < RING_N; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ringTexs[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbos[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, ringTexs[i], 0);
            ringValid[i] = false;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ringW = w;
        ringH = h;
        ringIdx = 0;
        ringCount = 0;
    }

    private void releaseTmixFbos() {
        if (ringFbos[0] != 0) GLES20.glDeleteFramebuffers(RING_N, ringFbos, 0);
        if (ringTexs[0] != 0) GLES20.glDeleteTextures(RING_N, ringTexs, 0);
        java.util.Arrays.fill(ringFbos, 0);
        java.util.Arrays.fill(ringTexs, 0);
        java.util.Arrays.fill(ringValid, false);
        ringW = 0;
        ringH = 0;
        ringIdx = 0;
        ringCount = 0;
    }

    private void pushFrameIntoTmixRing() {
        ensureTmixFbos(width, height);
        int baseFixDeg = 0;
        if (Math.abs(texMatrix[0]) < 0.25f && Math.abs(texMatrix[1]) > 0.5f) {
            baseFixDeg = (texMatrix[1] * texMatrix[4] > 0f) ? 270 : 90;
        }
        if (cameraFrontFacing && sensorOrientationDeg == 270) {
            baseFixDeg = (baseFixDeg + 180) % 360;
        }
        int totalUvRotDeg = (baseFixDeg + extraRotationDeg) % 360;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbos[ringIdx]);
        GLES20.glViewport(0, 0, ringW, ringH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawOesCameraQuad(idMatrix, totalUvRotDeg);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        ringValid[ringIdx] = true;
        ringIdx = (ringIdx + 1) % RING_N;
        if (ringCount < RING_N) ringCount++;
    }

    private void drawTmixCameraQuad(float[] mvp) {
        GLES20.glUseProgram(progTmix);
        GLES20.glUniformMatrix4fv(tmixUMvp, 1, false, mvp, 0);
        float active = 0f;
        for (int i = 0; i < RING_N; i++) {
            float w = ringValid[i] ? 1.0f : 0.0f;
            active += w;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ringTexs[i]);
            GLES20.glUniform1i(tmixSTex[i], i);
            GLES20.glUniform1f(tmixUW[i], w);
        }
        GLES20.glUniform1f(tmixUInvSum, active > 0f ? (1.0f / active) : 1.0f);

        fullQuadBuf.position(0);
        GLES20.glEnableVertexAttribArray(tmixAPos);
        GLES20.glVertexAttribPointer(tmixAPos, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf);
        fullQuadBuf.position(2);
        GLES20.glEnableVertexAttribArray(tmixATex);
        GLES20.glVertexAttribPointer(tmixATex, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(tmixAPos);
        GLES20.glDisableVertexAttribArray(tmixATex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void drawQuad(int aPos, int aTex, int uMvp, int uTexMtx, int sTex, FloatBuffer qb, float[] mvpMtx, float[] texMtx) {
        GLES20.glUniform1i(sTex, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMtx, 0);
        GLES20.glUniformMatrix4fv(uTexMtx, 1, false, texMtx, 0);

        qb.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, qb);
        qb.position(2);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, qb);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    private byte[] grabPbufferJpegBytes(int w, int h) {
        if (grabPixelsBuf == null || grabW != w || grabH != h) {
            grabW = w;
            grabH = h;
            grabPixelsBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
            if (grabBitmap != null) grabBitmap.recycle();
            if (grabUprightBitmap != null) grabUprightBitmap.recycle();
            grabBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            grabUprightBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            grabCanvas = new Canvas(grabUprightBitmap);
            grabFlipMatrix.reset();
            grabFlipMatrix.setScale(1f, -1f, 0f, h * 0.5f);
        }
        grabPixelsBuf.rewind();
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, grabPixelsBuf);
        grabPixelsBuf.rewind();
        grabBitmap.copyPixelsFromBuffer(grabPixelsBuf);
        grabUprightBitmap.eraseColor(Color.BLACK);
        grabCanvas.drawBitmap(grabBitmap, grabFlipMatrix, null);
        grabBaos.reset();
        grabUprightBitmap.compress(Bitmap.CompressFormat.JPEG, 82, grabBaos);
        return grabBaos.toByteArray();
    }

    private static int buildProgram(String vsSrc, String fsSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    public synchronized void stop() {
        running = false;
        if (glHandler != null) {
            glHandler.post(() -> {
                try { if (cameraInputSurface != null) cameraInputSurface.release(); } catch (Throwable ignored) {}
                cameraInputSurface = null;
                try { if (cameraSurfaceTexture != null) cameraSurfaceTexture.release(); } catch (Throwable ignored) {}
                cameraSurfaceTexture = null;
                try {
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                        releaseTmixFbos();
                        int[] texs = new int[] { texOes, texHud };
                        GLES20.glDeleteTextures(2, texs, 0);
                        texOes = 0;
                        texHud = 0;
                        if (progOes != 0) { GLES20.glDeleteProgram(progOes); progOes = 0; }
                        if (prog2d != 0) { GLES20.glDeleteProgram(prog2d); prog2d = 0; }
                        if (progTmix != 0) { GLES20.glDeleteProgram(progTmix); progTmix = 0; }
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
                        if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                        if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface);
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                        EGL14.eglTerminate(eglDisplay);
                    }
                } catch (Throwable ignored) {}
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                eglContext = EGL14.EGL_NO_CONTEXT;
                encoderEglSurface = EGL14.EGL_NO_SURFACE;
                previewEglSurface = EGL14.EGL_NO_SURFACE;
                pbufferSurface = EGL14.EGL_NO_SURFACE;
                if (hudBitmap != null) { hudBitmap.recycle(); hudBitmap = null; }
                hudCanvas = null;
                if (grabBitmap != null) { grabBitmap.recycle(); grabBitmap = null; }
                if (grabUprightBitmap != null) { grabUprightBitmap.recycle(); grabUprightBitmap = null; }
                grabCanvas = null;
                grabPixelsBuf = null;
                grabW = 0;
                grabH = 0;
            });
        }
        if (glThread != null) {
            glThread.quitSafely();
            try { glThread.join(1200); } catch (InterruptedException ignored) {}
            glThread = null;
        }
        glHandler = null;
    }
}
