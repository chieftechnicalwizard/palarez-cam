package com.palarez.streamcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MainActivity — Programmatic 3-Tab Dark Sanctuary Cockpit for PalaRez StreamCam.
 *
 * <h3>Operator Ergonomics &amp; Safety Controls</h3>
 * <ul>
 *   <li><b>16:9 Live Viewfinder + 2.5x Focus Loupe</b>: Double-tap the preview surface to toggle a
 *       {@code 2.5x} preview-only magnification loupe for critical manual macro focusing without
 *       cropping the encoded broadcast.</li>
 *   <li><b>Display Wake / Sanctuary Blackout Cycle</b>: Top-bar chip cycles {@code keep_awake}
 *       ({@code Awake}), {@code blackout} ({@code 1%} brightness + full-screen dark curtain so zero
 *       stray backlight leaks into dark grow tents; double-tap to wake), and {@code auto_sleep}.</li>
 *   <li><b>3-Tab Layout</b>: (1) {@code CAPTURE & LIVE} (2-step confirmed Go Live/Stop, Snap Photo,
 *       Torch, Mic, 1-tap Broadcast Presets, Priority Sensor summary), (2) {@code PRO OPTICS}
 *       (Manual Diopter Focus, Zoom, OIS/EIS, Mount Rotation, Manual ISO/Shutter/EV, AWB, and 4-mode
 *       LED Grow-Light Anti-Strobe), and (3) {@code SENSORS & RTMP} (masked Stream Key with
 *       {@code Show/Hide} + smart URL {@code Paste} splitter, non-publishing Dry-Run Validator,
 *       QNH altitude calibration, Timelapse, OPSEC-safe HUD toggles, and 9-sensor matrix).</li>
 * </ul>
 */
public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 101;
    private static final long[] SHUTTER_PRESETS_NS = {
        1_000_000_000L / 2000, 1_000_000_000L / 1000, 1_000_000_000L / 500,
        1_000_000_000L / 250, 1_000_000_000L / 120, 1_000_000_000L / 60,
        1_000_000_000L / 30, 1_000_000_000L / 15, 1_000_000_000L / 8,
        1_000_000_000L / 4, 1_000_000_000L / 2, 1_000_000_000L
    };

    // RFC 5737 TEST-NET-1 (192.0.2.10) template for local LAN RTMP hub selection
    private static final String[] RTMP_SERVER_URLS = {
        "rtmps://a.rtmps.youtube.com/live2",
        "rtmp://a.rtmp.youtube.com/live2",
        "rtmp://192.0.2.10:1935/live",
        ""
    };
    private static final String[] RTMP_SERVER_LABELS = {
        "YouTube RTMPS (rtmps://a.rtmps.youtube.com/live2)",
        "YouTube RTMP (rtmp://a.rtmp.youtube.com/live2)",
        "Local LAN RTMP Hub (rtmp://192.0.2.10:1935/live)",
        "Custom RTMP URL"
    };

    private static final int COL_BG = Color.parseColor("#0B1220");
    private static final int COL_CARD = Color.parseColor("#0F172A");
    private static final int COL_INPUT = Color.parseColor("#1E293B");
    private static final int COL_BORDER = Color.parseColor("#1E293B");
    private static final int COL_BORDER_HI = Color.parseColor("#34D399");
    private static final int COL_CYAN = Color.parseColor("#38BDF8");
    private static final int COL_EMERALD = Color.parseColor("#10B981");
    private static final int COL_BLUE = Color.parseColor("#0284C7");
    private static final int COL_GREEN = Color.parseColor("#059669");
    private static final int COL_AMBER = Color.parseColor("#D97706");
    private static final int COL_RED = Color.parseColor("#DC2626");
    private static final int COL_SLATE = Color.parseColor("#334155");
    private static final int COL_MUTED = Color.parseColor("#94A3B8");
    private static final int COL_TEXT = Color.parseColor("#E2E8F0");

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SettingsStore cfg;
    private StreamService svc;
    private boolean bound = false;

    private FrameLayout rootStack;
    private View blackoutCurtain;
    private SurfaceView previewView;
    private TextView loupeBadgeView;
    private TextView liveBannerView;
    private TextView ribbonView;
    private Button displayModeBtn;
    private Button copyUrlBtn;
    private LinearLayout lensBar;
    private final List<Button> lensPills = new ArrayList<>();

    private final Button[] tabBtns = new Button[3];
    private final LinearLayout[] tabPanels = new LinearLayout[3];
    private ScrollView mainScroll;
    private int activeTab = 0;

    private TextView quickTelemetryView;
    private TextView sensorReadoutView;
    private TextView dryRunResultView;
    private Button streamToggleBtn;
    private Button torchBtn;
    private Button audioBtn;

    private Spinner presetSpinner;
    private Spinner resSpinner;
    private Spinner fpsSpinner;
    private Spinner awbSpinner;
    private Spinner rotSpinner;
    private Spinner hudModeSpinner;
    private Spinner rtmpServerSpinner;
    private Switch manualFocusSwitch;
    private SeekBar focusSeek;
    private TextView focusLabel;
    private Switch manualExpSwitch;
    private SeekBar isoSeek;
    private TextView isoLabel;
    private SeekBar shutterSeek;
    private TextView shutterLabel;
    private SeekBar evSeek;
    private TextView evLabel;
    private SeekBar zoomSeek;
    private TextView zoomLabel;
    private Switch stabSwitch;
    private Switch antiFlickerSwitch;
    private Spinner antiFlickerSpinner;

    private EditText rtmpServerInput;
    private EditText rtmpKeyInput;
    private Button keyRevealBtn;
    private boolean keyRevealed = false;
    private EditText archiveInput;
    private EditText stationInput;
    private EditText bitrateInput;
    private EditText qnhInput;
    private EditText timelapseSecInput;
    private EditText sensorMsInput;
    private Switch timelapseSwitch;
    private CheckBox hudChk, hudEnvChk, hudMotChk, hudGpsChk, hudNetChk, hudCamChk, rawSensorsChk;

    private List<ProCameraController.CameraSpec> camSpecs = new ArrayList<>();
    private boolean ignoreSpinnerInit = true;
    private boolean loupeActive = false;

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (bound && svc != null) syncUiFromSettings();
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((StreamService.LocalBinder) binder).getService();
            bound = true;
            syncUiFromSettings();
            if (previewView.getHolder().getSurface() != null && previewView.getHolder().getSurface().isValid()) {
                svc.attachPreviewSurface(previewView.getHolder().getSurface(), previewView.getWidth(), previewView.getHeight());
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            svc = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COL_BG);
        getWindow().setNavigationBarColor(COL_BG);
        cfg = new SettingsStore(this);
        buildUi();
        applyDisplayMode(cfg.getDisplayMode(), false);
        requestMissingPermissions();
    }

    private void requestMissingPermissions() {
        List<String> needed = new ArrayList<>(Arrays.asList(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ));
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);

        List<String> req = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) req.add(p);
        }
        if (req.isEmpty()) {
            startAndBindEngine();
        } else {
            requestPermissions(req.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startAndBindEngine();
    }

    private void startAndBindEngine() {
        Intent i = new Intent(this, StreamService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    private void buildUi() {
        rootStack = new FrameLayout(this);
        rootStack.setBackgroundColor(COL_BG);
        rootStack.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(COL_BG);

        // 1. Exact 16:9 Live Viewfinder + Double-Tap 2.5x Focus Loupe Overlay
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int vfHeight = Math.max(dp(180), Math.round(screenW * 9f / 16f));
        FrameLayout vfWrap = new FrameLayout(this);
        vfWrap.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, vfHeight));

        previewView = new SurfaceView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        previewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (svc != null) svc.attachPreviewSurface(holder.getSurface(), previewView.getWidth(), previewView.getHeight());
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (svc != null) svc.attachPreviewSurface(holder.getSurface(), width, height);
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (svc != null) svc.attachPreviewSurface(null, 0, 0);
            }
        });
        vfWrap.addView(previewView);

        loupeBadgeView = new TextView(this);
        loupeBadgeView.setText("2.5x FOCUS LOUPE (PREVIEW ONLY)");
        loupeBadgeView.setTextColor(Color.WHITE);
        loupeBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        loupeBadgeView.setTypeface(null, Typeface.BOLD);
        loupeBadgeView.setBackground(pillBg(Color.argb(190, 5, 150, 105), COL_BORDER_HI, 8));
        loupeBadgeView.setPadding(dp(8), dp(4), dp(8), dp(4));
        loupeBadgeView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lblp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lblp.setMargins(dp(10), dp(10), 0, 0);
        vfWrap.addView(loupeBadgeView, lblp);

        GestureDetector vfGest = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                loupeActive = !loupeActive;
                if (svc != null) svc.setPreviewLoupe(loupeActive);
                loupeBadgeView.setVisibility(loupeActive ? View.VISIBLE : View.GONE);
                Toast.makeText(MainActivity.this,
                    loupeActive ? "2.5x Preview Focus Loupe ON" : "1.0x Full Framing", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        previewView.setOnTouchListener((v, ev) -> {
            vfGest.onTouchEvent(ev);
            return true;
        });
        outer.addView(vfWrap);

        // 2. Status, LAN Chip & Display Wake/Blackout Ribbon
        LinearLayout ribbonWrap = new LinearLayout(this);
        ribbonWrap.setOrientation(LinearLayout.VERTICAL);
        ribbonWrap.setBackgroundColor(COL_CARD);
        ribbonWrap.setPadding(dp(10), dp(6), dp(10), dp(6));

        LinearLayout topStatusRow = new LinearLayout(this);
        topStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        topStatusRow.setGravity(Gravity.CENTER_VERTICAL);

        liveBannerView = new TextView(this);
        liveBannerView.setTextColor(COL_CYAN);
        liveBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        liveBannerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        liveBannerView.setSingleLine(true);
        liveBannerView.setEllipsize(TextUtils.TruncateAt.END);
        liveBannerView.setText("STANDBY | Initializing...");
        topStatusRow.addView(liveBannerView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        copyUrlBtn = makeChipBtn("Copy LAN", COL_INPUT, v -> {
            if (svc == null) return;
            String url = "http://" + svc.getTelemetry().getLocalIpv4() + ":" + cfg.getLocalHttpPort();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PalaRez LAN URL", url));
            Toast.makeText(this, "Copied: " + url + " (/stream.flv, /api/status)", Toast.LENGTH_LONG).show();
        });
        displayModeBtn = makeChipBtn("Awake", COL_INPUT, v -> cycleDisplayMode());
        LinearLayout.LayoutParams chipLp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp1.setMargins(dp(4), 0, 0, 0);
        LinearLayout.LayoutParams chipLp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp2.setMargins(dp(4), 0, 0, 0);
        topStatusRow.addView(copyUrlBtn, chipLp1);
        topStatusRow.addView(displayModeBtn, chipLp2);
        ribbonWrap.addView(topStatusRow);

        ribbonView = new TextView(this);
        ribbonView.setTextColor(COL_TEXT);
        ribbonView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        ribbonView.setTypeface(Typeface.MONOSPACE);
        ribbonView.setSingleLine(true);
        ribbonView.setEllipsize(TextUtils.TruncateAt.END);
        ribbonView.setPadding(0, dp(3), 0, 0);
        ribbonView.setText("TEMP -- | BARO -- | ALT -- | LUX -- | HDG -- | MIC --");
        ribbonWrap.addView(ribbonView);
        outer.addView(ribbonWrap);

        // 3. Quick Lens Selector Pills Bar
        lensBar = new LinearLayout(this);
        lensBar.setOrientation(LinearLayout.HORIZONTAL);
        lensBar.setBackgroundColor(COL_BG);
        lensBar.setPadding(dp(8), dp(5), dp(8), dp(3));
        outer.addView(lensBar);

        // 4. 3-Tab Switcher
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(COL_CARD);
        tabRow.setPadding(dp(8), dp(4), dp(8), dp(6));
        String[] tabNames = { "CAPTURE & LIVE", "PRO OPTICS", "SENSORS & RTMP" };
        for (int idx = 0; idx < 3; idx++) {
            final int tabIdx = idx;
            Button tb = new Button(this);
            tb.setText(tabNames[idx]);
            tb.setAllCaps(false);
            tb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tb.setTypeface(null, Typeface.BOLD);
            tb.setPadding(dp(4), dp(6), dp(4), dp(6));
            tb.setMinHeight(dp(34));
            tb.setMinimumHeight(dp(34));
            tb.setOnClickListener(v -> selectTab(tabIdx));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMargins(dp(3), 0, dp(3), 0);
            tabRow.addView(tb, tlp);
            tabBtns[idx] = tb;
        }
        outer.addView(tabRow);

        // 5. Scrollable Tab Host
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        LinearLayout tabHost = new LinearLayout(this);
        tabHost.setOrientation(LinearLayout.VERTICAL);
        tabHost.setPadding(dp(12), dp(8), dp(12), dp(24));
        mainScroll.addView(tabHost);
        outer.addView(mainScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < 3; i++) {
            tabPanels[i] = new LinearLayout(this);
            tabPanels[i].setOrientation(LinearLayout.VERTICAL);
            tabHost.addView(tabPanels[i]);
        }

        buildCaptureTab(tabPanels[0]);
        buildOpticsTab(tabPanels[1]);
        buildSensorsRtmpTab(tabPanels[2]);
        selectTab(0);

        rootStack.addView(outer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 6. Sanctuary Blackout Overlay (Zero stray backlight in IR grow tents)
        LinearLayout curtain = new LinearLayout(this);
        curtain.setOrientation(LinearLayout.VERTICAL);
        curtain.setGravity(Gravity.CENTER);
        curtain.setBackgroundColor(Color.parseColor("#FA000000"));
        curtain.setVisibility(View.GONE);
        TextView cTxt = new TextView(this);
        cTxt.setText("SANCTUARY BLACKOUT ACTIVE\n\nDouble-tap anywhere to restore Cockpit");
        cTxt.setGravity(Gravity.CENTER);
        cTxt.setTextColor(Color.parseColor("#10B981"));
        cTxt.setAlpha(0.45f);
        cTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        curtain.addView(cTxt);
        GestureDetector cGest = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                applyDisplayMode("keep_awake", true);
                return true;
            }
        });
        curtain.setOnTouchListener((v, ev) -> {
            cGest.onTouchEvent(ev);
            return true;
        });
        blackoutCurtain = curtain;
        rootStack.addView(blackoutCurtain, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootStack);
    }

    private void buildCaptureTab(LinearLayout root) {
        LinearLayout actCard = makeCard(root, "PRIMARY BROADCAST & CAPTURE (2-STEP SAFETY)");

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        streamToggleBtn = makeIconBtn("Go Live (RTMP)", COL_GREEN, R.drawable.ic_broadcast, v -> promptLiveToggle());
        Button snapBtn = makeIconBtn("Snap Photo", "#2563EB", R.drawable.ic_snap, v -> {
            if (svc != null) {
                svc.snapFullResPhoto(false);
                Toast.makeText(this, "Captured full-res JPEG + 9-sensor telemetry JSON", Toast.LENGTH_SHORT).show();
            }
        });
        quickRow.addView(streamToggleBtn, weightLp());
        quickRow.addView(snapBtn, weightLp());
        actCard.addView(quickRow);

        LinearLayout quickRow2 = new LinearLayout(this);
        quickRow2.setOrientation(LinearLayout.HORIZONTAL);
        torchBtn = makeIconBtn("Torch: OFF", "#475569", R.drawable.ic_torch, v -> {
            ProCameraController.CameraSpec sp = svc != null ? svc.getCameraController().getActiveSpec() : null;
            if (sp != null && !sp.hasFlash) {
                Toast.makeText(this, "Active lens has no flash/torch", Toast.LENGTH_SHORT).show();
                return;
            }
            cfg.setTorchOn(!cfg.isTorchOn());
            if (svc != null) svc.applyLiveCameraControls();
            updateToggleLabels();
        });
        audioBtn = makeIconBtn("Mic: ON", COL_BLUE, R.drawable.ic_mic, v -> {
            cfg.setAudioEnabled(!cfg.isAudioEnabled());
            if (svc != null) svc.applyLiveCameraControls();
            updateToggleLabels();
        });
        quickRow2.addView(torchBtn, weightLp());
        quickRow2.addView(audioBtn, weightLp());
        actCard.addView(quickRow2);

        LinearLayout fmtCard = makeCard(root, "BROADCAST PROFILE & STREAM ENCODER");
        fmtCard.addView(smallLabel("1-Tap Stream Preset"));
        presetSpinner = makeSpinner(fmtCard);
        List<String> presetLabels = new ArrayList<>();
        for (SettingsStore.StreamPreset p : SettingsStore.PRESETS) presetLabels.add(p.label);
        presetLabels.add("Custom (Manual Res / FPS / Bitrate)");
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presetLabels));
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit || pos >= SettingsStore.PRESETS.size()) return;
                SettingsStore.StreamPreset p = SettingsStore.PRESETS.get(pos);
                if (p.id.equals(cfg.getPresetName())) return;
                cfg.applyPreset(p.id);
                if (svc != null) svc.restartCameraPipeline();
                syncUiFromSettings();
                Toast.makeText(MainActivity.this, "Preset applied: " + p.label, Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout fmtRow = new LinearLayout(this);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        resSpinner = new Spinner(this);
        resSpinner.setBackground(pillBg(COL_INPUT, COL_BORDER, 8));
        resSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] { "1920x1080 (1080p)", "1280x720 (720p)", "2560x1440 (2K)" }));
        fpsSpinner = new Spinner(this);
        fpsSpinner.setBackground(pillBg(COL_INPUT, COL_BORDER, 8));
        fpsSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] { "15 fps", "24 fps", "30 fps" }));
        fmtRow.addView(resSpinner, weightLp());
        fmtRow.addView(fpsSpinner, weightLp());
        fmtCard.addView(fmtRow);

        bitrateInput = labeledEdit(fmtCard, "Video Bitrate (kbps)", String.valueOf(cfg.getVideoBitrateKbps()), InputType.TYPE_CLASS_NUMBER);
        Button applyFmtBtn = makeBtn("Apply Custom Res / FPS / Bitrate", "#334155", v -> applyFormatAndRestart());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.setMargins(0, dp(8), 0, 0);
        fmtCard.addView(applyFmtBtn, bLp);

        LinearLayout sumCard = makeCard(root, "PRIORITY SENSORS & STREAM READINESS");
        quickTelemetryView = new TextView(this);
        quickTelemetryView.setTextColor(COL_TEXT);
        quickTelemetryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        quickTelemetryView.setTypeface(Typeface.MONOSPACE);
        quickTelemetryView.setText("Loading priority sensor suite...");
        sumCard.addView(quickTelemetryView);
    }

    private void buildOpticsTab(LinearLayout root) {
        LinearLayout focusCard = makeCard(root, "FOCUS, STABILIZATION & FRAMING (DBL-TAP PREVIEW FOR 2.5x LOUPE)");
        manualFocusSwitch = makeSwitch("Manual Focus (Diopter Control)", cfg.isManualFocus(), (b, on) -> {
            if (ignoreSpinnerInit) return;
            cfg.setManualFocus(on);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        focusCard.addView(manualFocusSwitch);

        focusLabel = smallLabel("Focus Distance: Auto");
        focusCard.addView(focusLabel);
        focusSeek = makeSeek(100, p -> {
            ProCameraController.CameraSpec sp = svc != null ? svc.getCameraController().getActiveSpec() : null;
            float maxD = sp != null && sp.minFocusDiopter > 0 ? sp.minFocusDiopter : 10f;
            float diopter = (p / 100f) * maxD;
            cfg.setFocusDistance(diopter);
            cfg.setManualFocus(true);
            manualFocusSwitch.setChecked(true);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        focusCard.addView(focusSeek);

        zoomLabel = smallLabel("Zoom Ratio: 1.0x");
        focusCard.addView(zoomLabel);
        zoomSeek = makeSeek(70, p -> {
            float z = 1.0f + (p / 10f);
            cfg.setZoomRatio(z);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        focusCard.addView(zoomSeek);

        stabSwitch = makeSwitch("Optical / Video Stabilization (OIS/EIS)", cfg.isStabilizationOn(), (b, on) -> {
            if (ignoreSpinnerInit) return;
            cfg.setStabilizationOn(on);
            if (svc != null) svc.applyLiveCameraControls();
        });
        focusCard.addView(stabSwitch);

        focusCard.addView(smallLabel("Camera Mount Rotation Fix"));
        rotSpinner = makeSpinner(focusCard);
        final int[] rotVals = { 0, 90, 180, 270 };
        rotSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] { "0 deg (Landscape Upright)", "90 deg (Portrait CW)", "180 deg (Landscape Flipped)", "270 deg (Portrait CCW)" }));
        rotSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit) return;
                cfg.setVideoRotationDeg(rotVals[pos]);
                if (svc != null) svc.applyLiveCameraControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout expCard = makeCard(root, "EXPOSURE & WHITE BALANCE (AWB)");
        manualExpSwitch = makeSwitch("Manual Exposure (ISO & Shutter)", cfg.isManualExposure(), (b, on) -> {
            if (ignoreSpinnerInit) return;
            cfg.setManualExposure(on);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        expCard.addView(manualExpSwitch);

        evLabel = smallLabel("Auto Exposure Compensation (EV): 0");
        expCard.addView(evLabel);
        evSeek = makeSeek(24, p -> {
            cfg.setExposureComp(p - 12);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        expCard.addView(evSeek);

        isoLabel = smallLabel("Manual ISO: 400");
        expCard.addView(isoLabel);
        isoSeek = makeSeek(100, p -> {
            int iso = 50 + Math.round((p / 100f) * 3150f);
            cfg.setManualIso(iso);
            cfg.setManualExposure(true);
            manualExpSwitch.setChecked(true);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        expCard.addView(isoSeek);

        shutterLabel = smallLabel("Manual Shutter: 1/60s");
        expCard.addView(shutterLabel);
        shutterSeek = makeSeek(SHUTTER_PRESETS_NS.length - 1, p -> {
            cfg.setManualShutterNs(SHUTTER_PRESETS_NS[p]);
            cfg.setManualExposure(true);
            manualExpSwitch.setChecked(true);
            if (svc != null) svc.applyLiveCameraControls();
            updateControlReadouts();
        });
        expCard.addView(shutterSeek);

        expCard.addView(smallLabel("White Balance (AWB)"));
        awbSpinner = makeSpinner(expCard);
        final String[] awbList = { "auto", "daylight", "cloudy", "incandescent", "fluorescent", "shade" };
        awbSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, awbList));
        awbSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit) return;
                cfg.setAwbMode(awbList[pos]);
                if (svc != null) svc.applyLiveCameraControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        antiFlickerSwitch = makeSwitch("Reduce LED Strobe (60Hz AE + 5-Frame Mix)", cfg.isAntiFlickerEnabled(), (b, on) -> {
            if (ignoreSpinnerInit) return;
            String target = on ? (cfg.isAntiFlickerEnabled() ? cfg.getAntiFlickerMode() : "60hz_tmix") : "off";
            cfg.setAntiFlickerMode(target);
            ignoreSpinnerInit = true;
            antiFlickerSpinner.setSelection("60hz_hw".equals(target) ? 1 : ("60hz_tmix".equals(target) ? 2 : ("50hz_tmix".equals(target) ? 3 : 0)));
            antiFlickerSpinner.post(() -> ignoreSpinnerInit = false);
            if (svc != null) svc.applyLiveCameraControls();
        });
        expCard.addView(antiFlickerSwitch);

        expCard.addView(smallLabel("LED Anti-Strobe Filter Mode"));
        antiFlickerSpinner = makeSpinner(expCard);
        final String[] afModes = { "off", "60hz_hw", "60hz_tmix", "50hz_tmix" };
        antiFlickerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] {
                "Off (Single-Frame Raw)",
                "60Hz Hardware AE Only (Zero Blur)",
                "60Hz AE + 5-Frame GPU Anti-Strobe (Recommended)",
                "50Hz AE + 5-Frame GPU Anti-Strobe"
            }));
        antiFlickerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit) return;
                String mode = afModes[pos];
                cfg.setAntiFlickerMode(mode);
                ignoreSpinnerInit = true;
                antiFlickerSwitch.setChecked(!"off".equals(mode));
                ignoreSpinnerInit = false;
                if (svc != null) svc.applyLiveCameraControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void buildSensorsRtmpTab(LinearLayout root) {
        LinearLayout netCard = makeCard(root, "YOUTUBE LIVE INGEST TARGET (MASKED KEY + DRY-RUN VALIDATOR)");
        netCard.addView(smallLabel("Ingest Endpoint Target"));
        rtmpServerSpinner = makeSpinner(netCard);
        rtmpServerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RTMP_SERVER_LABELS));
        rtmpServerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit) return;
                if (!RTMP_SERVER_URLS[pos].isEmpty()) {
                    rtmpServerInput.setText(RTMP_SERVER_URLS[pos]);
                    saveRtmpInputsQuietly();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        rtmpServerInput = labeledEdit(netCard, "RTMP Server URL", SettingsStore.extractRtmpBase(cfg.getRtmpUrl()),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        netCard.addView(smallLabel("Stream Key (Masked · Never Shown in Screenshots)"));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        rtmpKeyInput = new EditText(this);
        rtmpKeyInput.setSingleLine(true);
        rtmpKeyInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        rtmpKeyInput.setText(SettingsStore.extractRtmpKey(cfg.getRtmpUrl()));
        rtmpKeyInput.setTextColor(Color.WHITE);
        rtmpKeyInput.setHint("Paste stream key or full rtmps://.../key");
        rtmpKeyInput.setHintTextColor(COL_MUTED);
        rtmpKeyInput.setBackground(pillBg(COL_INPUT, COL_BORDER, 8));
        rtmpKeyInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        rtmpKeyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        keyRow.addView(rtmpKeyInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        keyRevealBtn = makeChipBtn("Show", COL_SLATE, v -> {
            keyRevealed = !keyRevealed;
            rtmpKeyInput.setTransformationMethod(keyRevealed
                ? SingleLineTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
            keyRevealBtn.setText(keyRevealed ? "Hide" : "Show");
        });
        Button pasteBtn = makeChipBtn("Paste", COL_BLUE, v -> pasteAndSplitRtmpUrl());
        LinearLayout.LayoutParams kblp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kblp.setMargins(dp(4), 0, 0, 0);
        keyRow.addView(keyRevealBtn, kblp);
        keyRow.addView(pasteBtn, kblp);
        netCard.addView(keyRow);

        archiveInput = labeledEdit(netCard, "Companion Archive URL (:8895)", cfg.getArchiveUrl(),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        stationInput = labeledEdit(netCard, "Station / HUD Callsign", cfg.getStationName(), InputType.TYPE_CLASS_TEXT);

        LinearLayout intRow = new LinearLayout(this);
        intRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colL = new LinearLayout(this); colL.setOrientation(LinearLayout.VERTICAL);
        LinearLayout colR = new LinearLayout(this); colR.setOrientation(LinearLayout.VERTICAL);
        sensorMsInput = labeledEdit(colL, "Sensor Rate (ms)", String.valueOf(cfg.getSensorIntervalMs()), InputType.TYPE_CLASS_NUMBER);
        timelapseSecInput = labeledEdit(colR, "Timelapse (sec)", String.valueOf(cfg.getTimelapseIntervalSec()), InputType.TYPE_CLASS_NUMBER);
        intRow.addView(colL, weightLp());
        intRow.addView(colR, weightLp());
        netCard.addView(intRow);

        qnhInput = labeledEdit(netCard, "Sea-Level Pressure Cal (QNH hPa for Indoor Altitude)",
            String.format(Locale.US, "%.2f", cfg.getSeaLevelHpa()),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        timelapseSwitch = makeSwitch("Scheduled Full-Res Timelapse Capture", cfg.isTimelapseEnabled(), (b, on) -> {
            if (ignoreSpinnerInit) return;
            cfg.setTimelapseEnabled(on);
        });
        netCard.addView(timelapseSwitch);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button saveBtn = makeBtn("Save & Arm Config", "#0D9488", v -> saveConfigInputs(true));
        Button dryRunBtn = makeBtn("Test Ingest (Dry-Run)", "#2563EB", v -> runDryRunTest());
        btnRow.addView(saveBtn, weightLp());
        btnRow.addView(dryRunBtn, weightLp());
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, dp(8), 0, 0);
        netCard.addView(btnRow, brLp);

        dryRunResultView = new TextView(this);
        dryRunResultView.setTextColor(COL_EMERALD);
        dryRunResultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        dryRunResultView.setTypeface(Typeface.MONOSPACE);
        dryRunResultView.setPadding(0, dp(6), 0, 0);
        dryRunResultView.setVisibility(View.GONE);
        netCard.addView(dryRunResultView);

        LinearLayout hudCard = makeCard(root, "SANCTUARY GLASS STREAM HUD OVERLAY");
        hudCard.addView(smallLabel("HUD Style Profile"));
        hudModeSpinner = makeSpinner(hudCard);
        final String[] hudModes = { "sanctuary_card", "minimal_pill", "clean" };
        hudModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] { "Sanctuary Glass Card (Cyber-Flora)", "Minimal Top Pill + Reticles", "Clean Camera Feed (For Hub Compositor)" }));
        hudModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ignoreSpinnerInit) return;
                cfg.setHudMode(hudModes[pos]);
                if (svc != null) svc.applyLiveCameraControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        hudChk = makeCheck("Enable HUD Overlay", cfg.isHudEnabled(), on -> { if (ignoreSpinnerInit) return; cfg.setHudEnabled(on); if (svc != null) svc.applyLiveCameraControls(); });
        hudEnvChk = makeCheck("Env Row (Temp · Pressure · Altitude · Lux · RH)", cfg.isHudEnv(), on -> { if (ignoreSpinnerInit) return; cfg.setHudEnv(on); if (svc != null) svc.applyLiveCameraControls(); });
        hudMotChk = makeCheck("Motion Row (Compass · Gravity Tilt · Gyroscope)", cfg.isHudMotion(), on -> { if (ignoreSpinnerInit) return; cfg.setHudMotion(on); if (svc != null) svc.applyLiveCameraControls(); });
        hudCamChk = makeCheck("Optics Footer (Lens · Focus · ISO/AE · Mic dBFS)", cfg.isHudCameraParams(), on -> { if (ignoreSpinnerInit) return; cfg.setHudCameraParams(on); if (svc != null) svc.applyLiveCameraControls(); });
        hudGpsChk = makeCheck("Burn GPS Coords onto Video (OFF for Home OPSEC)", cfg.isHudGps(), on -> { if (ignoreSpinnerInit) return; cfg.setHudGps(on); if (svc != null) svc.applyLiveCameraControls(); });
        hudNetChk = makeCheck("Burn Battery & LAN IP onto Video (OFF for OPSEC)", cfg.isHudRadioPower(), on -> { if (ignoreSpinnerInit) return; cfg.setHudRadioPower(on); if (svc != null) svc.applyLiveCameraControls(); });
        rawSensorsChk = makeCheck("Include Debug Vendor Sensors in /api/telemetry", cfg.isRawSensorsEnabled(), on -> { if (ignoreSpinnerInit) return; cfg.setRawSensorsEnabled(on); if (svc != null) svc.restartSensors(); });
        hudCard.addView(hudChk); hudCard.addView(hudEnvChk); hudCard.addView(hudMotChk);
        hudCard.addView(hudCamChk); hudCard.addView(hudGpsChk); hudCard.addView(hudNetChk); hudCard.addView(rawSensorsChk);

        LinearLayout matrixCard = makeCard(root, "PRIORITY 9-SENSOR TELEMETRY MATRIX");
        sensorReadoutView = new TextView(this);
        sensorReadoutView.setTextColor(COL_TEXT);
        sensorReadoutView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sensorReadoutView.setTypeface(Typeface.MONOSPACE);
        sensorReadoutView.setText("Waiting for telemetry engine...");
        matrixCard.addView(sensorReadoutView);
    }

    private void selectTab(int tabIdx) {
        activeTab = tabIdx;
        for (int i = 0; i < 3; i++) {
            tabPanels[i].setVisibility(i == tabIdx ? View.VISIBLE : View.GONE);
            tabBtns[i].setBackground(pillBg(i == tabIdx ? COL_BLUE : COL_CARD, i == tabIdx ? COL_CYAN : COL_BORDER, 8));
            tabBtns[i].setTextColor(i == tabIdx ? Color.WHITE : COL_MUTED);
        }
        if (mainScroll != null) mainScroll.scrollTo(0, 0);
    }

    private void rebuildLensPills() {
        lensBar.removeAllViews();
        lensPills.clear();
        String activeId = cfg.getCameraId();
        for (int i = 0; i < camSpecs.size(); i++) {
            final ProCameraController.CameraSpec sp = camSpecs.get(i);
            String shortName = sp.id + ": " + (sp.facing == 0 ? "FRONT" : "BACK");
            if ("2".equals(sp.id) || sp.minFocusDiopter >= 25f) shortName = sp.id + ": MACRO";
            else if ("0".equals(sp.id)) shortName = sp.id + ": MAIN";
            else if ("1".equals(sp.id)) shortName = sp.id + ": FRONT";

            Button pill = new Button(this);
            pill.setText(shortName + String.format(Locale.US, " %.1fmm", sp.focalLengthMm));
            pill.setAllCaps(false);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            pill.setTypeface(null, Typeface.BOLD);
            pill.setTextColor(Color.WHITE);
            boolean sel = sp.id.equals(activeId);
            pill.setBackground(pillBg(sel ? COL_BLUE : COL_INPUT, sel ? COL_CYAN : COL_BORDER, 8));
            pill.setMinHeight(dp(34));
            pill.setMinimumHeight(dp(34));
            pill.setPadding(dp(6), dp(4), dp(6), dp(4));
            pill.setOnClickListener(v -> {
                if (svc == null || cfg.getCameraId().equals(sp.id)) return;
                cfg.setCameraId(sp.id);
                svc.restartCameraPipeline();
                syncUiFromSettings();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            lensBar.addView(pill, lp);
            lensPills.add(pill);
        }
    }

    private void syncUiFromSettings() {
        if (svc == null) return;
        ignoreSpinnerInit = true;
        camSpecs = svc.getCameraController().enumerateCameras();
        rebuildLensPills();

        int presetIdx = SettingsStore.PRESETS.size();
        String curPreset = cfg.getPresetName();
        for (int i = 0; i < SettingsStore.PRESETS.size(); i++) {
            if (SettingsStore.PRESETS.get(i).id.equals(curPreset)) { presetIdx = i; break; }
        }
        presetSpinner.setSelection(presetIdx);

        int w = cfg.getVideoWidth();
        resSpinner.setSelection(w == 1280 ? 1 : (w == 2560 ? 2 : 0));
        int fps = cfg.getFps();
        fpsSpinner.setSelection(fps == 24 ? 1 : (fps == 30 ? 2 : 0));
        bitrateInput.setText(String.valueOf(cfg.getVideoBitrateKbps()));

        List<String> awbList = Arrays.asList("auto", "daylight", "cloudy", "incandescent", "fluorescent", "shade");
        awbSpinner.setSelection(Math.max(0, awbList.indexOf(cfg.getAwbMode())));
        List<String> afList = Arrays.asList("off", "60hz_hw", "60hz_tmix", "50hz_tmix");
        antiFlickerSpinner.setSelection(Math.max(0, afList.indexOf(cfg.getAntiFlickerMode())));
        antiFlickerSwitch.setChecked(cfg.isAntiFlickerEnabled());
        int rot = cfg.getVideoRotationDeg();
        rotSpinner.setSelection(rot == 90 ? 1 : (rot == 180 ? 2 : (rot == 270 ? 3 : 0)));

        String hm = cfg.getHudMode();
        hudModeSpinner.setSelection("minimal_pill".equals(hm) ? 1 : ("clean".equals(hm) ? 2 : 0));

        // Sync Tab 3 inputs cleanly without clobbering while typing
        String base = SettingsStore.extractRtmpBase(cfg.getRtmpUrl());
        String key = SettingsStore.extractRtmpKey(cfg.getRtmpUrl());
        if (!rtmpServerInput.hasFocus()) rtmpServerInput.setText(base);
        if (!rtmpKeyInput.hasFocus()) rtmpKeyInput.setText(key);
        int srvIdx = RTMP_SERVER_URLS.length - 1;
        for (int i = 0; i < RTMP_SERVER_URLS.length - 1; i++) {
            if (RTMP_SERVER_URLS[i].equalsIgnoreCase(base)) { srvIdx = i; break; }
        }
        rtmpServerSpinner.setSelection(srvIdx);

        if (!archiveInput.hasFocus()) archiveInput.setText(cfg.getArchiveUrl());
        if (!stationInput.hasFocus()) stationInput.setText(cfg.getStationName());
        if (!sensorMsInput.hasFocus()) sensorMsInput.setText(String.valueOf(cfg.getSensorIntervalMs()));
        if (!timelapseSecInput.hasFocus()) timelapseSecInput.setText(String.valueOf(cfg.getTimelapseIntervalSec()));
        if (!qnhInput.hasFocus()) qnhInput.setText(String.format(Locale.US, "%.2f", cfg.getSeaLevelHpa()));
        timelapseSwitch.setChecked(cfg.isTimelapseEnabled());

        hudChk.setChecked(cfg.isHudEnabled());
        hudEnvChk.setChecked(cfg.isHudEnv());
        hudMotChk.setChecked(cfg.isHudMotion());
        hudCamChk.setChecked(cfg.isHudCameraParams());
        hudGpsChk.setChecked(cfg.isHudGps());
        hudNetChk.setChecked(cfg.isHudRadioPower());
        rawSensorsChk.setChecked(cfg.isRawSensorsEnabled());

        ProCameraController.CameraSpec sp = svc.getCameraController().getActiveSpec();
        boolean canFocus = sp != null && sp.minFocusDiopter > 0f;
        manualFocusSwitch.setEnabled(canFocus);
        focusSeek.setEnabled(canFocus);
        manualFocusSwitch.setChecked(canFocus && cfg.isManualFocus());
        manualExpSwitch.setChecked(cfg.isManualExposure());
        stabSwitch.setChecked(cfg.isStabilizationOn());

        float maxD = canFocus ? sp.minFocusDiopter : 10f;
        focusSeek.setProgress(Math.min(100, Math.round((cfg.getFocusDistance() / maxD) * 100f)));
        evSeek.setProgress(Math.max(0, Math.min(24, cfg.getExposureComp() + 12)));
        isoSeek.setProgress(Math.max(0, Math.min(100, Math.round(((cfg.getManualIso() - 50f) / 3150f) * 100f))));
        zoomSeek.setProgress(Math.max(0, Math.min(70, Math.round((cfg.getZoomRatio() - 1.0f) * 10f))));

        int bestShutter = 0;
        long targetNs = cfg.getManualShutterNs();
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < SHUTTER_PRESETS_NS.length; i++) {
            long d = Math.abs(SHUTTER_PRESETS_NS[i] - targetNs);
            if (d < bestDiff) { bestDiff = d; bestShutter = i; }
        }
        shutterSeek.setProgress(bestShutter);

        // Post a guard reset after Android's async Spinner selection pass settles
        presetSpinner.post(() -> ignoreSpinnerInit = false);
        applyDisplayMode(cfg.getDisplayMode(), false);
        updateToggleLabels();
        updateControlReadouts();
    }

    private void promptLiveToggle() {
        if (svc == null) return;
        if (svc.isStreaming()) {
            new AlertDialog.Builder(this)
                .setTitle("Stop Live Broadcast?")
                .setMessage("End the active RTMP stream to:\n" + cfg.getMaskedRtmpUrl())
                .setPositiveButton("Stop Stream", (d, w) -> {
                    svc.stopRtmpBroadcast();
                    refreshUiTick();
                })
                .setNegativeButton("Keep Live", null)
                .show();
            return;
        }

        saveRtmpInputsQuietly();
        String key = SettingsStore.extractRtmpKey(cfg.getRtmpUrl());
        if (key == null || key.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Stream Key Missing")
                .setMessage("Enter your YouTube or Hub stream key in SENSORS & RTMP before going live.\n\nIf an agent is pulling /stream.flv via go2rtc, you do NOT need to click Go Live here.")
                .setPositiveButton("Open Sensors & RTMP", (d, w) -> selectTab(2))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        String summary = String.format(Locale.US,
            "Target: %s\nFormat: %dx%d @ %d fps (%d kbps)\nMic Audio: %s\nHUD Style: %s\nLens: %s",
            cfg.getMaskedRtmpUrl(),
            cfg.getVideoWidth(), cfg.getVideoHeight(), cfg.getFps(), cfg.getVideoBitrateKbps(),
            cfg.isAudioEnabled() ? "LIVE" : "MUTED (Silent PCM)",
            cfg.getHudMode(),
            svc.getTelemetry().activeCameraInfo);

        new AlertDialog.Builder(this)
            .setTitle("Start Live RTMP Broadcast?")
            .setMessage(summary)
            .setPositiveButton("Confirm Go Live", (d, w) -> {
                svc.startRtmpBroadcast();
                refreshUiTick();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveRtmpInputsQuietly() {
        if (rtmpServerInput == null || rtmpKeyInput == null) return;
        String srv = rtmpServerInput.getText().toString().trim();
        String key = rtmpKeyInput.getText().toString().trim();
        if (key.contains("://")) {
            cfg.setRtmpUrl(key);
            rtmpServerInput.setText(SettingsStore.extractRtmpBase(key));
            rtmpKeyInput.setText(SettingsStore.extractRtmpKey(key));
        } else {
            cfg.setRtmpUrl(SettingsStore.combineRtmpUrl(srv, key));
        }
    }

    private void pasteAndSplitRtmpUrl() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (cs == null) return;
        String txt = cs.toString().trim();
        if (txt.contains("://")) {
            rtmpServerInput.setText(SettingsStore.extractRtmpBase(txt));
            rtmpKeyInput.setText(SettingsStore.extractRtmpKey(txt));
            Toast.makeText(this, "Split server & stream key cleanly", Toast.LENGTH_SHORT).show();
        } else {
            rtmpKeyInput.setText(txt);
            Toast.makeText(this, "Pasted stream key", Toast.LENGTH_SHORT).show();
        }
        saveRtmpInputsQuietly();
    }

    private void applyFormatAndRestart() {
        if (svc == null) return;
        int r = resSpinner.getSelectedItemPosition();
        if (r == 1) cfg.setResolution(1280, 720); else if (r == 2) cfg.setResolution(2560, 1440); else cfg.setResolution(1920, 1080);
        int f = fpsSpinner.getSelectedItemPosition();
        cfg.setFps(f == 1 ? 24 : (f == 2 ? 30 : 15));
        try { cfg.setVideoBitrateKbps(Integer.parseInt(bitrateInput.getText().toString().trim())); } catch (Throwable ignored) {}
        svc.restartCameraPipeline();
        syncUiFromSettings();
        Toast.makeText(this, "Applied video format & restarted pipeline", Toast.LENGTH_SHORT).show();
    }

    private void saveConfigInputs(boolean armStation) {
        saveRtmpInputsQuietly();
        cfg.setArchiveUrl(archiveInput.getText().toString().trim());
        cfg.setStationName(stationInput.getText().toString().trim());
        try { cfg.setSensorIntervalMs(Integer.parseInt(sensorMsInput.getText().toString().trim())); } catch (Throwable ignored) {}
        try { cfg.setTimelapseIntervalSec(Integer.parseInt(timelapseSecInput.getText().toString().trim())); } catch (Throwable ignored) {}
        try { cfg.setVideoBitrateKbps(Integer.parseInt(bitrateInput.getText().toString().trim())); } catch (Throwable ignored) {}
        try { cfg.setSeaLevelHpa(Float.parseFloat(qnhInput.getText().toString().trim())); } catch (Throwable ignored) {}
        if (armStation) {
            cfg.setArmed(!SettingsStore.extractRtmpKey(cfg.getRtmpUrl()).isEmpty());
            if (svc != null) svc.getTelemetry().lastError = null;
        }
        if (svc != null) {
            svc.restartSensors();
            svc.applyLiveCameraControls();
        }
        Toast.makeText(this, "Saved stream, sensor & QNH configuration", Toast.LENGTH_SHORT).show();
    }

    private void runDryRunTest() {
        if (svc == null) return;
        saveRtmpInputsQuietly();
        dryRunResultView.setVisibility(View.VISIBLE);
        dryRunResultView.setTextColor(COL_CYAN);
        dryRunResultView.setText("Running non-live DNS/TCP/TLS/RTMP handshake check...");
        new Thread(() -> {
            JSONObject res = svc.validateRtmpDryRun(null);
            uiHandler.post(() -> {
                boolean ok = res.optBoolean("ok", false);
                dryRunResultView.setTextColor(ok ? COL_EMERALD : COL_RED);
                dryRunResultView.setText("DRY-RUN (NO STREAM STARTED):\n" + res.toString());
            });
        }, "PalaRezValidate").start();
    }

    private void cycleDisplayMode() {
        String cur = cfg.getDisplayMode();
        String next = "keep_awake".equals(cur) ? "blackout" : ("blackout".equals(cur) ? "auto_sleep" : "keep_awake");
        applyDisplayMode(next, true);
    }

    private void applyDisplayMode(String mode, boolean toast) {
        cfg.setDisplayMode(mode);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if ("blackout".equals(mode)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = 0.01f;
            blackoutCurtain.setVisibility(View.VISIBLE);
            displayModeBtn.setText("Blackout");
        } else if ("auto_sleep".equals(mode)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            blackoutCurtain.setVisibility(View.GONE);
            displayModeBtn.setText("AutoSleep");
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            blackoutCurtain.setVisibility(View.GONE);
            displayModeBtn.setText("Awake");
        }
        getWindow().setAttributes(lp);
        if (toast) Toast.makeText(this, "Display mode: " + displayModeBtn.getText(), Toast.LENGTH_SHORT).show();
    }

    private void updateToggleLabels() {
        ProCameraController.CameraSpec sp = svc != null ? svc.getCameraController().getActiveSpec() : null;
        boolean hasFlash = sp == null || sp.hasFlash;
        torchBtn.setEnabled(hasFlash);
        torchBtn.setText(!hasFlash ? "No Flash" : (cfg.isTorchOn() ? "Torch: ON" : "Torch: OFF"));
        torchBtn.setBackground(pillBg(!hasFlash ? COL_INPUT : (cfg.isTorchOn() ? COL_AMBER : COL_SLATE), COL_BORDER, 10));
        audioBtn.setText(cfg.isAudioEnabled() ? "Mic: ON" : "Mic: MUTED");
        audioBtn.setBackground(pillBg(cfg.isAudioEnabled() ? COL_BLUE : COL_RED, COL_BORDER, 10));
    }

    private void updateControlReadouts() {
        ProCameraController.CameraSpec sp = svc != null ? svc.getCameraController().getActiveSpec() : null;
        float d = cfg.getFocusDistance();
        String cm = d > 0.05f ? String.format(Locale.US, " (~%.1fcm)", 100f / d) : " (Infinity)";
        if (sp != null && sp.minFocusDiopter <= 0f) {
            focusLabel.setText("Focus: Fixed Infinity Lens");
        } else {
            focusLabel.setText(cfg.isManualFocus()
                ? String.format(Locale.US, "Focus: MANUAL %.2fD%s [max %.0fD]", d, cm, sp != null ? sp.minFocusDiopter : 10f)
                : "Focus: AUTO Continuous");
        }
        evLabel.setText("Auto Exposure Bias (EV): " + cfg.getExposureComp());
        isoLabel.setText(cfg.isManualExposure() ? ("Manual ISO: " + cfg.getManualIso()) : "ISO: AUTO");
        shutterLabel.setText(cfg.isManualExposure() ? ("Manual Shutter: 1/" + Math.max(1, Math.round(1e9 / cfg.getManualShutterNs())) + "s") : "Shutter: AUTO");
        zoomLabel.setText(String.format(Locale.US, "Zoom Ratio: %.1fx", cfg.getZoomRatio()));
    }

    private void refreshUiTick() {
        if (svc == null) return;
        TelemetryCollector t = svc.getTelemetry();
        boolean live = svc.isStreaming();
        streamToggleBtn.setText(live ? "STOP Live RTMP" : "Go Live (RTMP)");
        streamToggleBtn.setBackground(pillBg(live ? COL_RED : COL_GREEN, COL_BORDER, 10));

        liveBannerView.setTextColor(live ? Color.parseColor("#4ADE80")
            : (t.lastError != null ? Color.parseColor("#F87171") : COL_CYAN));
        liveBannerView.setText(String.format(Locale.US,
            "%s  |  http://%s:%d  |  %s",
            t.rtmpState, t.getLocalIpv4(), cfg.getLocalHttpPort(), svc.getLastCaptureNote()));

        JSONObject j = t.toJson(cfg, cfg.isRawSensorsEnabled());
        JSONObject bat = j.optJSONObject("battery");
        JSONObject net = j.optJSONObject("network");
        JSONObject gps = j.optJSONObject("gps");
        JSONObject aud = j.optJSONObject("audio");
        JSONObject strm = j.optJSONObject("stream");
        JSONObject pr = j.optJSONObject("priority");
        if (pr == null) return;

        JSONObject pTemp = pr.optJSONObject("temperature");
        JSONObject pBaro = pr.optJSONObject("pressure");
        JSONObject pAlt = pr.optJSONObject("altitude");
        JSONObject pLux = pr.optJSONObject("light");
        JSONObject pCmp = pr.optJSONObject("compass");
        JSONObject pRh = pr.optJSONObject("humidity");
        JSONObject pGrv = pr.optJSONObject("gravity");
        JSONObject pGyr = pr.optJSONObject("gyroscope");
        JSONObject pTime = pr.optJSONObject("time");

        String tStr = (pTemp != null && !pTemp.isNull("primary_c")) ? String.format(Locale.US, "%.1fC", pTemp.optDouble("primary_c")) : "--";
        String bStr = (pBaro != null && !pBaro.isNull("hpa")) ? String.format(Locale.US, "%.0fhPa", pBaro.optDouble("hpa")) : "--";
        String aStr = (pAlt != null && !pAlt.isNull("effective_m")) ? String.format(Locale.US, "%.0fm", pAlt.optDouble("effective_m")) : "--";
        String lStr = (pLux != null && !pLux.isNull("lux")) ? String.format(Locale.US, "%.0flx", pLux.optDouble("lux")) : "--";
        String hStr = (pCmp != null && !pCmp.isNull("heading_deg")) ? String.format(Locale.US, "%.0f%s", pCmp.optDouble("heading_deg"), pCmp.optString("cardinal")) : "--";
        String micShort = (aud != null && aud.optBoolean("enabled"))
            ? String.format(Locale.US, "%.0fdB", aud.optDouble("peak_dbfs", -90.0)) : "OFF";
        ribbonView.setText(String.format(Locale.US,
            "T:%s | %s | ALT:%s | %s | HDG:%s | MIC:%s",
            tStr, bStr, aStr, lStr, hStr, micShort));

        if (activeTab == 0 && quickTelemetryView != null) {
            StringBuilder q = new StringBuilder();
            if (pTime != null) q.append("Time:     ").append(pTime.optString("iso_local")).append("\n");
            if (pTemp != null) q.append(String.format(Locale.US, "Temp:     %sF / %sC (%s; IMU %sC)\n",
                fmtNum(pTemp, "primary_f", "%.1f"), fmtNum(pTemp, "primary_c", "%.1f"),
                pTemp.optString("source"), fmtNum(pTemp, "imu_die_c", "%.1f")));
            if (pBaro != null) q.append(String.format(Locale.US, "Pressure: %s hPa (QNH %.2f)\n",
                fmtNum(pBaro, "hpa", "%.2f"), pBaro.optDouble("sea_level_ref_hpa", 1013.25)));
            if (pAlt != null) q.append(String.format(Locale.US, "Altitude: %sm / %sft (%s)\n",
                fmtNum(pAlt, "effective_m", "%.1f"), fmtNum(pAlt, "effective_ft", "%.0f"), pAlt.optString("source")));
            if (pLux != null) q.append(String.format(Locale.US, "Light:    %s lux | Flicker %s Hz\n",
                fmtNum(pLux, "lux", "%.0f"), fmtNum(pLux, "flicker_hz", "%.0f")));
            if (pCmp != null) q.append(String.format(Locale.US, "Compass:  %s deg (%s)\n",
                fmtNum(pCmp, "heading_deg", "%.1f"), pCmp.optString("cardinal")));
            if (pRh != null) q.append("Humidity: ").append(pRh.optBoolean("available")
                ? String.format(Locale.US, "%.1f%% (%s)", pRh.optDouble("rh_pct"), pRh.optString("source"))
                : "N/A (No HW hygrometer; accepts hub push)").append("\n");
            if (pGrv != null && pGrv.has("x")) q.append(String.format(Locale.US, "Gravity:  (%.2f, %.2f, %.2f) Tilt %.1fd\n",
                pGrv.optDouble("x"), pGrv.optDouble("y"), pGrv.optDouble("z"), pGrv.optDouble("pitch_deg")));
            if (pGyr != null && pGyr.has("x")) q.append(String.format(Locale.US, "Gyro:     (%.3f, %.3f, %.3f) r/s [%s]\n",
                pGyr.optDouble("x"), pGyr.optDouble("y"), pGyr.optDouble("z"), pGyr.optBoolean("stable") ? "STABLE" : "MOTION"));
            if (bat != null && net != null) q.append(String.format(Locale.US, "Power/Net: %d%% %s | %s (%ddBm)",
                bat.optInt("percent"), bat.optString("source"), net.optString("ipv4"), net.optInt("rssi_dbm")));
            quickTelemetryView.setText(q.toString());
        } else if (activeTab == 2 && sensorReadoutView != null) {
            StringBuilder sb = new StringBuilder();
            if (pTime != null) sb.append("Time:     ").append(pTime.optString("iso_local")).append(" (").append(pTime.optString("timezone")).append(")\n");
            if (pTemp != null) sb.append(String.format(Locale.US, "Temp:     %sF / %sC (src=%s | bat=%sC | imu=%sC)\n",
                fmtNum(pTemp, "primary_f", "%.1f"), fmtNum(pTemp, "primary_c", "%.1f"), pTemp.optString("source"),
                fmtNum(pTemp, "battery_c", "%.1f"), fmtNum(pTemp, "imu_die_c", "%.1f")));
            if (pBaro != null) sb.append(String.format(Locale.US, "Pressure: %s hPa (QNH %.2f hPa)\n",
                fmtNum(pBaro, "hpa", "%.2f"), pBaro.optDouble("sea_level_ref_hpa", 1013.25)));
            if (pAlt != null) sb.append(String.format(Locale.US, "Altitude: eff=%sm (%sft) | baro=%sm | gps=%s\n",
                fmtNum(pAlt, "effective_m", "%.1f"), fmtNum(pAlt, "effective_ft", "%.0f"), fmtNum(pAlt, "baro_alt_m", "%.1f"),
                pAlt.isNull("gps_alt_m") ? "null" : (pAlt.optDouble("gps_alt_m") + "m")));
            if (pLux != null) sb.append(String.format(Locale.US, "Light:    %s lux | Flicker %s Hz\n",
                fmtNum(pLux, "lux", "%.1f"), fmtNum(pLux, "flicker_hz", "%.0f")));
            if (pCmp != null) sb.append(String.format(Locale.US, "Compass:  %s deg %s (%s)\n",
                fmtNum(pCmp, "heading_deg", "%.1f"), pCmp.optString("cardinal"), pCmp.optString("reference")));
            if (pRh != null) sb.append("Humidity: ").append(pRh.optBoolean("available") ? (pRh.optDouble("rh_pct") + "% (" + pRh.optString("source") + ")") : "N/A (No HW sensor; accepts hub /api/control push)").append("\n");
            if (pGrv != null && pGrv.has("x")) sb.append(String.format(Locale.US, "Gravity:  (%.2f, %.2f, %.2f) m/s2 | Pitch %.1f | Roll %.1f\n",
                pGrv.optDouble("x"), pGrv.optDouble("y"), pGrv.optDouble("z"), pGrv.optDouble("pitch_deg"), pGrv.optDouble("roll_deg")));
            if (pGyr != null && pGyr.has("x")) sb.append(String.format(Locale.US, "Gyro:     (%.3f, %.3f, %.3f) rad/s | rms=%.3f\n",
                pGyr.optDouble("x"), pGyr.optDouble("y"), pGyr.optDouble("z"), pGyr.optDouble("rms_rads")));
            if (gps != null) sb.append(gps.optBoolean("fix")
                ? String.format(Locale.US, "GPS Lock: %.5f, %.5f | +/-%.0fm | %d/%dsat\n", gps.optDouble("lat"), gps.optDouble("lon"), gps.optDouble("accuracy_m"), gps.optInt("sats_used"), gps.optInt("sats_visible"))
                : String.format(Locale.US, "GPS Sky:  Searching (%d used / %d visible sats)\n", gps.optInt("sats_used"), gps.optInt("sats_visible")));
            if (strm != null) sb.append(String.format(Locale.US, "Ingest:   %s | FLV Pull http://%s:%d/stream.flv",
                strm.optString("state"), net != null ? net.optString("ipv4") : "?", cfg.getLocalHttpPort()));

            JSONObject rawSns = j.optJSONObject("sensors");
            if (rawSns != null) {
                sb.append("\nRaw Sensors:\n");
                JSONArray keys = rawSns.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String k = keys.optString(i);
                        sb.append("  • ").append(k).append(": ").append(rawSns.opt(k)).append("\n");
                    }
                }
            }
            sensorReadoutView.setText(sb.toString());
        }
    }

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshUiTick();
            uiHandler.postDelayed(this, 600);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(StreamService.ACTION_SETTINGS_SYNC);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(syncReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(syncReceiver, f);
        uiHandler.post(uiTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(syncReceiver); } catch (Throwable ignored) {}
        uiHandler.removeCallbacks(uiTicker);
    }

    @Override
    protected void onDestroy() {
        if (bound) { unbindService(conn); bound = false; }
        super.onDestroy();
    }

    private static String fmtNum(JSONObject obj, String key, String fmt) {
        if (obj == null || obj.isNull(key)) return "--";
        double v = obj.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? "--" : String.format(Locale.US, fmt, v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable pillBg(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private LinearLayout makeCard(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(pillBg(COL_CARD, COL_BORDER, 12));
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        TextView hdr = new TextView(this);
        hdr.setText(title);
        hdr.setTextColor(COL_EMERALD);
        hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hdr.setTypeface(null, Typeface.BOLD);
        hdr.setPadding(0, 0, 0, dp(6));
        card.addView(hdr);
        parent.addView(card, lp);
        return card;
    }

    private Spinner makeSpinner(LinearLayout parent) {
        Spinner sp = new Spinner(this);
        sp.setBackground(pillBg(COL_INPUT, COL_BORDER, 8));
        sp.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(6));
        parent.addView(sp, lp);
        return sp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private TextView smallLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private EditText labeledEdit(LinearLayout parent, String label, String val, int inputType) {
        parent.addView(smallLabel(label));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(val);
        e.setTextColor(Color.WHITE);
        e.setBackground(pillBg(COL_INPUT, COL_BORDER, 8));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        parent.addView(e);
        return e;
    }

    private Button makeChipBtn(String label, int fillColor, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(pillBg(fillColor, COL_BORDER, 7));
        b.setMinHeight(dp(26));
        b.setMinimumHeight(dp(26));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setOnClickListener(click);
        return b;
    }

    private Button makeBtn(String label, String hex, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(pillBg(Color.parseColor(hex), COL_BORDER, 10));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setOnClickListener(click);
        return b;
    }

    private Button makeIconBtn(String label, Object colorOrHex, int iconResId, View.OnClickListener click) {
        int col = colorOrHex instanceof Integer ? (Integer) colorOrHex : Color.parseColor((String) colorOrHex);
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        b.setBackground(pillBg(col, COL_BORDER, 10));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        Drawable d = getDrawable(iconResId);
        if (d != null) {
            d = d.mutate();
            d.setBounds(0, 0, dp(18), dp(18));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(6));
        }
        b.setOnClickListener(click);
        return b;
    }

    private Switch makeSwitch(String label, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(checked);
        sw.setPadding(0, dp(6), 0, dp(6));
        sw.setOnCheckedChangeListener(l);
        return sw;
    }

    private CheckBox makeCheck(String label, boolean checked, java.util.function.Consumer<Boolean> onToggle) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(COL_TEXT);
        c.setChecked(checked);
        c.setOnCheckedChangeListener((b, on) -> onToggle.accept(on));
        return c;
    }

    private SeekBar makeSeek(int max, java.util.function.IntConsumer onChange) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) onChange.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return sb;
    }
}
