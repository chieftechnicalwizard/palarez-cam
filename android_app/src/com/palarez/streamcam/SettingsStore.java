package com.palarez.streamcam;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SettingsStore — Persistent {@link SharedPreferences} Backing Store for All Pro Camera2,
 * Priority Telemetry, Broadcast Presets, HUD Overlay, Audio Mixer, Timelapse, and RTMP Controls.
 *
 * <p>All setters clamp numeric inputs to safe hardware/encoder bounds before persisting so neither
 * UI widgets nor external {@code POST /api/control} payloads can store out-of-range state.
 * Privacy-sensitive HUD elements ({@code hud_gps} and {@code hud_radio_power}) default to
 * {@code false}, and stream keys are masked via {@link #getMaskedRtmpUrl()} before leaving the store.
 */
public final class SettingsStore {
    /**
     * Immutable broadcast encoder &amp; HUD profile selectable via UI or {@code preset} in {@code /api/control}.
     */
    public static final class StreamPreset {
        public final String id;
        public final String label;
        public final int width;
        public final int height;
        public final int fps;
        public final int videoKbps;
        public final int audioKbps;
        public final int keyframeSec;
        public final String hudMode;

        public StreamPreset(String id, String label, int w, int h, int fps, int vKbps, int aKbps, int kf, String hudMode) {
            this.id = id;
            this.label = label;
            this.width = w;
            this.height = h;
            this.fps = fps;
            this.videoKbps = vKbps;
            this.audioKbps = aKbps;
            this.keyframeSec = kf;
            this.hudMode = hudMode;
        }
    }

    /**
     * Built-in 1-tap broadcast profiles tuned for thermal stability on continuous 24/7 streams,
     * constrained cellular uplinks, higher-motion handheld shots, and clean hub mezzanine pulls.
     */
    public static final List<StreamPreset> PRESETS = Collections.unmodifiableList(Arrays.asList(
        new StreamPreset("sanctuary_eco_1080p15_800k", "Sanctuary Eco 1080p15 (800k)", 1920, 1080, 15, 800, 96, 2, "sanctuary_card"),
        new StreamPreset("relief_1080p15_480k", "Cellular Relief 1080p15 (480k)", 1920, 1080, 15, 480, 80, 2, "sanctuary_card"),
        new StreamPreset("youtube_hd_1080p24_2000k", "YouTube HD 1080p24 (2000k)", 1920, 1080, 24, 2000, 96, 2, "sanctuary_card"),
        new StreamPreset("motion_1080p30_4500k", "High-Motion 1080p30 (4500k)", 1920, 1080, 30, 4500, 128, 2, "sanctuary_card"),
        new StreamPreset("hub_mezzanine_1440p15_4000k", "Hub Mezzanine 2.5K15 (Clean)", 2560, 1440, 15, 4000, 128, 2, "clean")
    ));

    private static final String PREFS = "palarez_streamcam_prefs";
    private final SharedPreferences prefs;

    public SettingsStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getCameraId() { return prefs.getString("camera_id", "0"); }
    public void setCameraId(String v) { prefs.edit().putString("camera_id", v).apply(); }

    public int getVideoWidth() { return prefs.getInt("video_width", 1920); }
    public int getVideoHeight() { return prefs.getInt("video_height", 1080); }
    public void setResolution(int w, int h) {
        prefs.edit().putInt("video_width", w).putInt("video_height", h).putString("preset_name", "custom").apply();
    }

    public int getFps() { return prefs.getInt("video_fps", 15); }
    public void setFps(int v) { prefs.edit().putInt("video_fps", Math.max(5, Math.min(60, v))).putString("preset_name", "custom").apply(); }

    public int getVideoBitrateKbps() { return prefs.getInt("video_bitrate_kbps", 800); }
    public void setVideoBitrateKbps(int v) { prefs.edit().putInt("video_bitrate_kbps", Math.max(300, Math.min(12000, v))).putString("preset_name", "custom").apply(); }

    public int getKeyframeSec() { return prefs.getInt("keyframe_sec", 2); }
    public void setKeyframeSec(int v) { prefs.edit().putInt("keyframe_sec", Math.max(1, Math.min(10, v))).apply(); }

    public String getPresetName() { return prefs.getString("preset_name", "sanctuary_eco_1080p15_800k"); }
    public void setPresetName(String v) { prefs.edit().putString("preset_name", v).apply(); }

    public boolean applyPreset(String presetId) {
        for (StreamPreset p : PRESETS) {
            if (p.id.equalsIgnoreCase(presetId)) {
                prefs.edit()
                    .putString("preset_name", p.id)
                    .putInt("video_width", p.width)
                    .putInt("video_height", p.height)
                    .putInt("video_fps", p.fps)
                    .putInt("video_bitrate_kbps", p.videoKbps)
                    .putInt("audio_bitrate_kbps", p.audioKbps)
                    .putInt("keyframe_sec", p.keyframeSec)
                    .putString("hud_mode", p.hudMode)
                    .apply();
                return true;
            }
        }
        return false;
    }

    // Camera2 Pro Focus & Exposure
    public boolean isManualFocus() { return prefs.getBoolean("manual_focus", false); }
    public void setManualFocus(boolean v) { prefs.edit().putBoolean("manual_focus", v).apply(); }

    public float getFocusDistance() { return prefs.getFloat("focus_distance", 0.0f); }
    public void setFocusDistance(float v) { prefs.edit().putFloat("focus_distance", Math.max(0.0f, v)).apply(); }

    public boolean isManualExposure() { return prefs.getBoolean("manual_exposure", false); }
    public void setManualExposure(boolean v) { prefs.edit().putBoolean("manual_exposure", v).apply(); }

    public int getExposureComp() { return prefs.getInt("exposure_comp", 0); }
    public void setExposureComp(int v) { prefs.edit().putInt("exposure_comp", v).apply(); }

    public int getManualIso() { return prefs.getInt("manual_iso", 400); }
    public void setManualIso(int v) { prefs.edit().putInt("manual_iso", Math.max(50, v)).apply(); }

    public long getManualShutterNs() { return prefs.getLong("manual_shutter_ns", 16_666_667L); }
    public void setManualShutterNs(long v) { prefs.edit().putLong("manual_shutter_ns", Math.max(100_000L, v)).apply(); }

    public String getAwbMode() { return prefs.getString("awb_mode", "auto"); }
    public void setAwbMode(String v) { prefs.edit().putString("awb_mode", v).apply(); }

    public float getZoomRatio() { return prefs.getFloat("zoom_ratio", 1.0f); }
    public void setZoomRatio(float v) { prefs.edit().putFloat("zoom_ratio", Math.max(1.0f, v)).apply(); }

    public boolean isTorchOn() { return prefs.getBoolean("torch_on", false); }
    public void setTorchOn(boolean v) { prefs.edit().putBoolean("torch_on", v).apply(); }

    public boolean isStabilizationOn() { return prefs.getBoolean("stabilization_on", true); }
    public void setStabilizationOn(boolean v) { prefs.edit().putBoolean("stabilization_on", v).apply(); }

    public String getAntiFlickerMode() { return prefs.getString("anti_flicker_mode", "off"); }
    public void setAntiFlickerMode(String v) {
        String m = ("60hz_hw".equals(v) || "60hz_tmix".equals(v) || "50hz_tmix".equals(v)) ? v : "off";
        prefs.edit().putString("anti_flicker_mode", m).apply();
    }
    public boolean isAntiFlickerEnabled() { return !"off".equals(getAntiFlickerMode()); }
    public boolean isAntiFlickerTmix() {
        String m = getAntiFlickerMode();
        return "60hz_tmix".equals(m) || "50hz_tmix".equals(m);
    }

    public int getVideoRotationDeg() { return prefs.getInt("video_rotation_deg", 0); }
    public void setVideoRotationDeg(int v) { prefs.edit().putInt("video_rotation_deg", ((v % 360) + 360) % 360).apply(); }

    // Audio
    public boolean isAudioEnabled() { return prefs.getBoolean("audio_enabled", true); }
    public void setAudioEnabled(boolean v) { prefs.edit().putBoolean("audio_enabled", v).apply(); }

    public int getAudioBitrateKbps() { return prefs.getInt("audio_bitrate_kbps", 96); }
    public void setAudioBitrateKbps(int v) { prefs.edit().putInt("audio_bitrate_kbps", Math.max(32, Math.min(256, v))).apply(); }

    public float getMusicGain() { return prefs.getFloat("music_gain", 0.90f); }
    public void setMusicGain(float v) { prefs.edit().putFloat("music_gain", Math.max(0f, Math.min(2.0f, v))).apply(); }

    public float getMicGain() { return prefs.getFloat("mic_gain", 1.0f); }
    public void setMicGain(float v) { prefs.edit().putFloat("mic_gain", Math.max(0f, Math.min(2.0f, v))).apply(); }

    public boolean isMusicLoop() { return prefs.getBoolean("music_loop", false); }
    public void setMusicLoop(boolean v) { prefs.edit().putBoolean("music_loop", v).apply(); }

    // Telemetry & HUD burn-in (OPSEC-safe defaults: GPS coords & LAN IP are OFF until requested)
    public int getSensorIntervalMs() { return prefs.getInt("sensor_interval_ms", 500); }
    public void setSensorIntervalMs(int v) { prefs.edit().putInt("sensor_interval_ms", Math.max(100, Math.min(10000, v))).apply(); }

    public String getStationName() { return prefs.getString("station_name", "PalaRez-Cam1"); }
    public void setStationName(String v) { prefs.edit().putString("station_name", v).apply(); }

    public String getHudMode() { return prefs.getString("hud_mode", "sanctuary_card"); }
    public void setHudMode(String v) {
        String m = ("clean".equals(v) || "minimal_pill".equals(v)) ? v : "sanctuary_card";
        prefs.edit().putString("hud_mode", m).apply();
    }

    public boolean isHudEnabled() { return prefs.getBoolean("hud_enabled", true); }
    public void setHudEnabled(boolean v) { prefs.edit().putBoolean("hud_enabled", v).apply(); }

    public boolean isHudEnv() { return prefs.getBoolean("hud_env", true); }
    public void setHudEnv(boolean v) { prefs.edit().putBoolean("hud_env", v).apply(); }

    public boolean isHudMotion() { return prefs.getBoolean("hud_motion", true); }
    public void setHudMotion(boolean v) { prefs.edit().putBoolean("hud_motion", v).apply(); }

    public boolean isHudGps() { return prefs.getBoolean("hud_gps", false); }
    public void setHudGps(boolean v) { prefs.edit().putBoolean("hud_gps", v).apply(); }

    public boolean isHudRadioPower() { return prefs.getBoolean("hud_radio_power", false); }
    public void setHudRadioPower(boolean v) { prefs.edit().putBoolean("hud_radio_power", v).apply(); }

    public boolean isHudCameraParams() { return prefs.getBoolean("hud_camera", true); }
    public void setHudCameraParams(boolean v) { prefs.edit().putBoolean("hud_camera", v).apply(); }

    public float getSeaLevelHpa() { return prefs.getFloat("sea_level_hpa", 1013.25f); }
    public void setSeaLevelHpa(float v) { prefs.edit().putFloat("sea_level_hpa", Math.max(850f, Math.min(1100f, v))).apply(); }

    public boolean isRawSensorsEnabled() { return prefs.getBoolean("raw_sensors", false); }
    public void setRawSensorsEnabled(boolean v) { prefs.edit().putBoolean("raw_sensors", v).apply(); }

    public String getDisplayMode() { return prefs.getString("display_mode", "keep_awake"); }
    public void setDisplayMode(String v) { prefs.edit().putString("display_mode", v).apply(); }

    // YouTube Live RTMP/RTMPS & Companion Archive
    public String getRtmpUrl() { return prefs.getString("rtmp_url", "rtmps://a.rtmps.youtube.com/live2/"); }
    public void setRtmpUrl(String v) { prefs.edit().putString("rtmp_url", v == null ? "" : v.trim()).apply(); }

    public boolean isArmed() { return prefs.getBoolean("rtmp_armed", false); }
    public void setArmed(boolean v) { prefs.edit().putBoolean("rtmp_armed", v).apply(); }

    public String getArchiveUrl() { return prefs.getString("archive_url", ""); }
    public void setArchiveUrl(String v) { prefs.edit().putString("archive_url", v == null ? "" : v.trim()).apply(); }

    public boolean isTimelapseEnabled() { return prefs.getBoolean("timelapse_enabled", false); }
    public void setTimelapseEnabled(boolean v) { prefs.edit().putBoolean("timelapse_enabled", v).apply(); }

    public int getTimelapseIntervalSec() { return prefs.getInt("timelapse_interval_sec", 60); }
    public void setTimelapseIntervalSec(int v) { prefs.edit().putInt("timelapse_interval_sec", Math.max(3, Math.min(86400, v))).apply(); }

    public int getTelemetryPushSec() { return prefs.getInt("telemetry_push_sec", 5); }
    public void setTelemetryPushSec(int v) { prefs.edit().putInt("telemetry_push_sec", Math.max(1, Math.min(3600, v))).apply(); }

    public int getLocalHttpPort() { return prefs.getInt("local_http_port", 8890); }
    public void setLocalHttpPort(int v) { prefs.edit().putInt("local_http_port", v).apply(); }

    /**
     * Returns the configured RTMP/RTMPS URL with the secret stream key redacted to its final
     * 4 characters (e.g. {@code rtmps://a.rtmps.youtube.com/live2/****-abcd}) so UI dialogs,
     * logs, and {@code GET /api/status} never leak full broadcast credentials.
     */
    public String getMaskedRtmpUrl() {
        String u = getRtmpUrl();
        if (u == null || u.isEmpty()) return "";
        int lastSlash = u.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash >= u.length() - 1) return u;
        String base = u.substring(0, lastSlash + 1);
        String key = u.substring(lastSlash + 1);
        if (key.length() <= 4) return base + "****";
        return base + "****-" + key.substring(key.length() - 4);
    }

    /** Extracts {@code rtmp[s]://host[:port]/app} without trailing stream key. */
    public static String extractRtmpBase(String url) {
        if (url == null || url.isEmpty()) return "rtmps://a.rtmps.youtube.com/live2";
        String clean = url.trim();
        int idx = clean.indexOf("://");
        if (idx < 0) return clean;
        int s1 = clean.indexOf('/', idx + 3);
        if (s1 < 0) return clean;
        int s2 = clean.indexOf('/', s1 + 1);
        if (s2 < 0) return clean;
        return clean.substring(0, s2);
    }

    /** Extracts the secret stream key segment after {@code rtmp[s]://host[:port]/app/}. */
    public static String extractRtmpKey(String url) {
        if (url == null || url.isEmpty()) return "";
        String clean = url.trim();
        int idx = clean.indexOf("://");
        if (idx < 0) return "";
        int s1 = clean.indexOf('/', idx + 3);
        if (s1 < 0) return "";
        int s2 = clean.indexOf('/', s1 + 1);
        if (s2 < 0 || s2 + 1 >= clean.length()) return "";
        return clean.substring(s2 + 1);
    }

    /** Joins an RTMP server base URL and stream key, or returns {@code key} directly if already a full URL. */
    public static String combineRtmpUrl(String base, String key) {
        String b = base == null ? "" : base.trim().replaceAll("/+$", "");
        String k = key == null ? "" : key.trim().replaceAll("^/+", "");
        if (k.contains("://")) return k;
        return k.isEmpty() ? (b + "/") : (b + "/" + k);
    }
}
