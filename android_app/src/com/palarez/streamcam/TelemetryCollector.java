package com.palarez.streamcam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * TelemetryCollector — Hardware Sensor, GNSS &amp; Device Telemetry Engine Focused on the
 * 9 Broadcast-Priority Environmental &amp; Inertial Metrics.
 *
 * <h3>9 Priority Sensor Suite ({@code priority} in {@code GET /api/telemetry})</h3>
 * <ol>
 *   <li><b>Time</b>: {@code epoch_ms}, {@code iso_local}, {@code iso_utc}, {@code timezone}.</li>
 *   <li><b>Temperature</b>: Fallback hierarchy {@code external_hub -> ambient_hw -> battery -> imu_die}
 *       with {@code primary_c}, {@code primary_f}, and individual hardware readings.</li>
 *   <li><b>Pressure</b>: {@code BMP580} / {@link Sensor#TYPE_PRESSURE} {@code hpa} + editable QNH
 *       {@code sea_level_ref_hpa} ({@code 1013.25} hPa default).</li>
 *   <li><b>Altitude</b>: Continuous hybrid indoor/outdoor altitude — prefers 3D GPS ({@code gps_3d})
 *       when horizontal accuracy {@code <= 25m}, otherwise computes barometric altitude
 *       ({@code barometer_bmp580}) via {@link SensorManager#getAltitude(float, float)}.</li>
 *   <li><b>Light Intensity</b>: Ambient light {@code lux} ({@link Sensor#TYPE_LIGHT}) + LED/AC
 *       {@code flicker_hz} (matched from vendor flicker sensors when present).</li>
 *   <li><b>Compass</b>: Fused {@link Sensor#TYPE_ROTATION_VECTOR} (or accel + mag fallback) remapped
 *       onto the active camera optical axis ({@code +Z} out the rear lens, {@code +180°} for front
 *       cameras) with 16-wind cardinal direction ({@code N}, {@code NNE}, ...).</li>
 *   <li><b>Humidity</b>: Hardware {@link Sensor#TYPE_RELATIVE_HUMIDITY} when present, optional
 *       Home Assistant / hub injection ({@code external_humidity_pct}), or clean {@code available=false}
 *       when the phone lacks a physical hygrometer.</li>
 *   <li><b>Gravity</b>: 3-axis {@code x, y, z} ({@code m/s^2}), {@code norm_mps2}, and spirit-level
 *       {@code pitch_deg} / {@code roll_deg}.</li>
 *   <li><b>Gyroscope</b>: 3-axis {@code x, y, z} ({@code rad/s}), {@code rms_rads}, and tripod
 *       vibration indicator {@code stable} ({@code rms_rads < 0.03}).</li>
 * </ol>
 *
 * <h3>Sensor De-Noising</h3>
 * <p>Unless {@code raw_sensors} is enabled, {@link #start(int, boolean)} skips one-shot triggers and
 * non-priority OEM gesture/SAR/proximity sensors (such as {@code flatup}, {@code stowed}, or
 * {@code capsense}) to minimize wakeups and keep telemetry payloads clean.
 */
public final class TelemetryCollector implements SensorEventListener, LocationListener {
    /** Pre-formatted, allocation-light snapshot consumed by {@link HudGlRenderer} every 500 ms. */
    public static final class HudSnapshot {
        public String station = "PalaRez-Cam1";
        public String hudMode = "sanctuary_card";
        public boolean hudVisible = true;
        public boolean hudEnv = true;
        public boolean hudMotion = true;
        public boolean hudGps = false;
        public boolean hudRadioPower = false;
        public boolean hudCameraParams = true;

        public String clockText = "";
        public boolean rtmpConnected = false;
        public String streamState = "IDLE";
        public double measuredFps = 0.0;
        public int measuredKbps = 0;

        public String tempText = "--";
        public String pressureText = "--";
        public String altitudeText = "--";
        public String lightText = "--";
        public String humidityText = "RH N/A";
        public String compassText = "--";
        public String gravityText = "--";
        public String gyroText = "--";
        public String gpsText = "";
        public String camBadgeText = "";
        public String radioBadgeText = "";
    }

    private static final String[] CARDINALS = {
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    private final Context ctx;
    private final SensorManager sensorManager;
    private final LocationManager locationManager;
    private final Map<String, float[]> sensorValues = new ConcurrentHashMap<>();
    private final Map<Integer, String> sensorTypeNames = new ConcurrentHashMap<>();
    private final List<Sensor> registeredSensors = new ArrayList<>();

    private final float[] lastAccel = new float[3];
    private final float[] lastMag = new float[3];
    private final float[] lastGravity = new float[3];
    private final float[] lastGyro = new float[3];
    private final float[] lastRotVec = new float[5];
    private volatile boolean hasAccel = false;
    private volatile boolean hasMag = false;
    private volatile boolean hasGravity = false;
    private volatile boolean hasGyro = false;
    private volatile boolean hasRotVec = false;
    private volatile float compassDeg = Float.NaN;
    private volatile int activeCameraFacing = CameraCharacteristics.LENS_FACING_BACK;

    private volatile boolean hasAmbientTempHw = false;
    private volatile boolean hasHumidityHw = false;
    private volatile boolean hasPressureHw = false;
    private volatile boolean hasLightHw = false;
    private volatile boolean hasGravityHw = false;
    private volatile boolean hasGyroHw = false;
    private volatile boolean hasCompassHw = false;

    private volatile double gpsLat = Double.NaN;
    private volatile double gpsLon = Double.NaN;
    private volatile double gpsAlt = Double.NaN;
    private volatile float gpsSpeed = Float.NaN;
    private volatile float gpsAccuracy = Float.NaN;
    private volatile int gpsSatsVisible = 0;
    private volatile int gpsSatsUsed = 0;
    private volatile String gpsProviderUsed = "";
    private GnssStatus.Callback gnssCallback;

    // Optional Home Assistant / companion hub external environmental values
    public volatile double externalHumidityPct = Double.NaN;
    public volatile double externalTempC = Double.NaN;

    // Live audio + stream counters updated by StreamService
    public volatile double audioPeakDbfs = -90.0;
    public volatile double audioRmsDbfs = -90.0;
    public volatile boolean audioLive = true;
    public volatile boolean rtmpConnected = false;
    public volatile String rtmpState = "IDLE";
    public volatile long streamFramesSent = 0;
    public volatile long streamFramesDropped = 0;
    public volatile double measuredFps = 0.0;
    public volatile double measuredKbps = 0.0;
    public volatile long streamStartMs = 0;
    public volatile int stillCount = 0;
    public volatile int timelapseCount = 0;
    public volatile String activeCameraInfo = "";
    public volatile String lastError = null;

    private int activeIntervalMs = 500;
    private boolean rawDumpEnabled = false;

    public TelemetryCollector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sensorManager = (SensorManager) this.ctx.getSystemService(Context.SENSOR_SERVICE);
        this.locationManager = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
        probeHardwarePresence();
    }

    private void probeHardwarePresence() {
        if (sensorManager == null) return;
        hasAmbientTempHw = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null;
        hasHumidityHw = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null;
        hasPressureHw = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null;
        hasLightHw = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
        hasGravityHw = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null;
        hasGyroHw = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        hasCompassHw = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
    }

    public void setCameraFacing(int facing) {
        this.activeCameraFacing = facing;
    }

    /**
     * Starts hardware sensor and GNSS listeners at {@code intervalMs} (clamped {@code >= 100 ms}).
     * When {@code rawSensors} is {@code false}, only sensors required for the 9 priority metrics
     * are registered with {@link SensorManager}.
     */
    public synchronized void start(int intervalMs, boolean rawSensors) {
        stop();
        this.activeIntervalMs = Math.max(100, intervalMs);
        this.rawDumpEnabled = rawSensors;
        int delayUs = this.activeIntervalMs * 1000;
        if (sensorManager != null) {
            List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : all) {
                if (s.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) continue;
                if (!rawSensors && !isPrioritySensor(s)) continue;
                try {
                    if (sensorManager.registerListener(this, s, delayUs)) {
                        registeredSensors.add(s);
                        sensorTypeNames.put(s.getType(), canonicalName(s));
                    }
                } catch (Throwable ignored) {}
            }
        }
        startLocation();
    }

    private static boolean isPrioritySensor(Sensor s) {
        switch (s.getType()) {
            case Sensor.TYPE_PRESSURE:
            case Sensor.TYPE_LIGHT:
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
            case Sensor.TYPE_RELATIVE_HUMIDITY:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_ROTATION_VECTOR:
                return true;
            default:
                String n = s.getName() != null ? s.getName().toLowerCase(Locale.US) : "";
                return n.contains("flicker") || n.contains("temp");
        }
    }

    private void startLocation() {
        if (locationManager == null) return;
        boolean hasFine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) return;
        Looper mainLooper = Looper.getMainLooper();
        try {
            for (String prov : locationManager.getProviders(true)) {
                Location last = locationManager.getLastKnownLocation(prov);
                if (last != null) onLocationChanged(last);
                locationManager.requestLocationUpdates(prov, 2000L, 0.0f, this, mainLooper);
            }
        } catch (Throwable ignored) {}
        if (hasFine && Build.VERSION.SDK_INT >= 24) {
            try {
                if (gnssCallback == null) {
                    gnssCallback = new GnssStatus.Callback() {
                        @Override
                        public void onSatelliteStatusChanged(GnssStatus status) {
                            int n = status.getSatelliteCount();
                            int used = 0;
                            for (int i = 0; i < n; i++) {
                                if (status.usedInFix(i)) used++;
                            }
                            gpsSatsVisible = n;
                            gpsSatsUsed = used;
                        }
                    };
                }
                locationManager.registerGnssStatusCallback(gnssCallback, new Handler(mainLooper));
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void stop() {
        if (sensorManager != null) {
            try { sensorManager.unregisterListener(this); } catch (Throwable ignored) {}
        }
        registeredSensors.clear();
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Throwable ignored) {}
            if (gnssCallback != null && Build.VERSION.SDK_INT >= 24) {
                try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Throwable ignored) {}
            }
        }
    }

    private static String canonicalName(Sensor s) {
        switch (s.getType()) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetometer";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope";
            case Sensor.TYPE_LIGHT: return "light_lux";
            case Sensor.TYPE_PRESSURE: return "pressure_hpa";
            case Sensor.TYPE_PROXIMITY: return "proximity_cm";
            case Sensor.TYPE_GRAVITY: return "gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "linear_accel";
            case Sensor.TYPE_ROTATION_VECTOR: return "rotation_vector";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "humidity_pct";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "ambient_temp_c";
            case Sensor.TYPE_STEP_COUNTER: return "step_counter";
            default:
                return s.getName().toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) return;
        String key = sensorTypeNames.get(event.sensor.getType());
        if (key == null) key = canonicalName(event.sensor);
        sensorValues.put(key, event.values.clone());

        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
            System.arraycopy(event.values, 0, lastAccel, 0, 3);
            hasAccel = true;
            if (!hasRotVec) updateCompassFromAccelMag();
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD && event.values.length >= 3) {
            System.arraycopy(event.values, 0, lastMag, 0, 3);
            hasMag = true;
            if (!hasRotVec) updateCompassFromAccelMag();
        } else if (type == Sensor.TYPE_ROTATION_VECTOR && event.values.length >= 3) {
            int copyLen = Math.min(lastRotVec.length, event.values.length);
            System.arraycopy(event.values, 0, lastRotVec, 0, copyLen);
            hasRotVec = true;
            updateCompassFromRotationVector();
        } else if (type == Sensor.TYPE_GRAVITY && event.values.length >= 3) {
            System.arraycopy(event.values, 0, lastGravity, 0, 3);
            hasGravity = true;
        } else if (type == Sensor.TYPE_GYROSCOPE && event.values.length >= 3) {
            System.arraycopy(event.values, 0, lastGyro, 0, 3);
            hasGyro = true;
        }
    }

    /**
     * Computes true camera-sightline azimuth ({@code 0..360°}) from the fused rotation vector.
     *
     * <p>Standard Android {@code getOrientation(r, ...)} assumes the device lies flat on a table,
     * which suffers gimbal lock when a phone stands upright on a tripod. Remapping {@code (X, Y)}
     * to {@code (AXIS_X, AXIS_Z)} projects the world horizontal plane against the rear camera's
     * optical axis ({@code -Z} device / {@code +Z} remapped) so upright tripod panning stays
     * continuous across all 360°, and flips {@code +180°} when a front-facing lens is active.
     */
    private void updateCompassFromRotationVector() {
        float[] r = new float[9];
        float[] remap = new float[9];
        SensorManager.getRotationMatrixFromVector(r, lastRotVec);
        // Remap to rear camera optical sightline (+Z out back of phone) so vertical tripod orientation stays stable
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap);
        float[] orient = new float[3];
        SensorManager.getOrientation(remap, orient);
        float deg = (float) Math.toDegrees(orient[0]);
        if (activeCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) deg += 180f;
        deg = ((deg % 360f) + 360f) % 360f;
        compassDeg = deg;
    }

    private void updateCompassFromAccelMag() {
        if (!hasAccel || !hasMag) return;
        float[] r = new float[9];
        float[] remap = new float[9];
        float[] i = new float[9];
        if (SensorManager.getRotationMatrix(r, i, lastAccel, lastMag)) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap);
            float[] orient = new float[3];
            SensorManager.getOrientation(remap, orient);
            float deg = (float) Math.toDegrees(orient[0]);
            if (activeCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) deg += 180f;
            compassDeg = ((deg % 360f) + 360f) % 360f;
        }
    }

    private static String toCardinal(float deg) {
        if (Float.isNaN(deg)) return "--";
        int idx = Math.round(((deg % 360f) + 360f) % 360f / 22.5f) % 16;
        return CARDINALS[idx];
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;
        gpsLat = loc.getLatitude();
        gpsLon = loc.getLongitude();
        gpsAlt = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
        gpsSpeed = loc.hasSpeed() ? loc.getSpeed() : Float.NaN;
        gpsAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN;
        if (loc.getProvider() != null) gpsProviderUsed = loc.getProvider();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    public String getLocalIpv4() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                LinkProperties lp = active != null ? cm.getLinkProperties(active) : null;
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                            return la.getAddress().getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "0.0.0.0";
    }

    private Float findSensorScalarBySubstring(String substr) {
        return findSensorScalarBySubstringExcluding(substr, null);
    }

    private Float findSensorScalarBySubstringExcluding(String substr, String excludeKey) {
        for (Map.Entry<String, float[]> e : sensorValues.entrySet()) {
            if (excludeKey != null && excludeKey.equals(e.getKey())) continue;
            if (e.getKey().contains(substr) && e.getValue() != null && e.getValue().length > 0) {
                return e.getValue()[0];
            }
        }
        return null;
    }

    public JSONObject hardwareSensorSummary() {
        JSONObject hw = new JSONObject();
        try {
            hw.put("temperature_ambient_hw", hasAmbientTempHw);
            hw.put("temperature_imu_hw", findSensorScalarBySubstringExcluding("temp", "ambient_temp_c") != null);
            hw.put("pressure_barometer_hw", hasPressureHw);
            hw.put("light_als_hw", hasLightHw);
            hw.put("flicker_hw", findSensorScalarBySubstring("flicker") != null);
            hw.put("compass_magnetometer_hw", hasCompassHw);
            hw.put("humidity_hw", hasHumidityHw);
            hw.put("gravity_hw", hasGravityHw);
            hw.put("gyroscope_hw", hasGyroHw);
        } catch (Throwable ignored) {}
        return hw;
    }

    public JSONObject toJson(SettingsStore cfg) {
        return toJson(cfg, false);
    }

    public JSONObject toJson(SettingsStore cfg, boolean includeRawSensors) {
        JSONObject root = new JSONObject();
        try {
            long nowMs = System.currentTimeMillis();
            Date nowDate = new Date(nowMs);
            root.put("timestamp_ms", nowMs);
            root.put("station", cfg.getStationName());
            root.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("android", Build.VERSION.RELEASE);

            // Battery
            double batTempC = Double.NaN;
            JSONObject bat = new JSONObject();
            Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int pct = scale > 0 ? Math.round(level * 100f / scale) : -1;
                int tempDeci = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                batTempC = tempDeci / 10.0;
                int voltMv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                int plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                bat.put("percent", pct);
                bat.put("temp_c", batTempC);
                bat.put("voltage_mv", voltMv);
                bat.put("charging", plugged != 0);
                bat.put("source", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                    : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "WIRELESS" : "BATTERY");
            }
            root.put("battery", bat);

            // Network / Wi-Fi
            JSONObject net = new JSONObject();
            net.put("ipv4", getLocalIpv4());
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                if (wi != null) {
                    net.put("rssi_dbm", wi.getRssi());
                    net.put("link_mbps", wi.getLinkSpeed());
                    if (Build.VERSION.SDK_INT >= 29) net.put("freq_mhz", wi.getFrequency());
                }
            } catch (Throwable ignored) {}
            root.put("network", net);

            // GPS & GNSS Sky Status
            JSONObject gps = new JSONObject();
            boolean hasFix = !Double.isNaN(gpsLat);
            gps.put("fix", hasFix);
            gps.put("sats_visible", gpsSatsVisible);
            gps.put("sats_used", gpsSatsUsed);
            if (hasFix) {
                gps.put("lat", gpsLat);
                gps.put("lon", gpsLon);
                if (!Double.isNaN(gpsAlt)) gps.put("alt_m", Math.round(gpsAlt * 10.0) / 10.0);
                if (!Float.isNaN(gpsSpeed)) gps.put("speed_mps", Math.round(gpsSpeed * 100.0) / 100.0);
                if (!Float.isNaN(gpsAccuracy)) gps.put("accuracy_m", Math.round(gpsAccuracy * 10.0) / 10.0);
                if (!gpsProviderUsed.isEmpty()) gps.put("provider", gpsProviderUsed);
            }
            root.put("gps", gps);

            // 9 Priority Sensors Structured Block
            JSONObject prio = new JSONObject();

            // 1. Time
            JSONObject tObj = new JSONObject();
            SimpleDateFormat isoLocalFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            SimpleDateFormat isoUtcFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoUtcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            tObj.put("epoch_ms", nowMs);
            tObj.put("iso_local", isoLocalFmt.format(nowDate));
            tObj.put("iso_utc", isoUtcFmt.format(nowDate));
            tObj.put("timezone", TimeZone.getDefault().getID());
            prio.put("time", tObj);

            // 2. Temperature
            float[] ambArr = sensorValues.get("ambient_temp_c");
            Float imuTemp = findSensorScalarBySubstringExcluding("temp", "ambient_temp_c");
            double primaryC = Double.NaN;
            String tempSrc = "none";
            if (!Double.isNaN(externalTempC)) {
                primaryC = externalTempC; tempSrc = "external_hub";
            } else if (ambArr != null && ambArr.length > 0) {
                primaryC = ambArr[0]; tempSrc = "ambient_hw";
            } else if (!Double.isNaN(batTempC)) {
                primaryC = batTempC; tempSrc = "battery";
            } else if (imuTemp != null) {
                primaryC = imuTemp; tempSrc = "imu_die";
            }
            JSONObject tempObj = new JSONObject();
            tempObj.put("source", tempSrc);
            if (!Double.isNaN(primaryC)) {
                tempObj.put("primary_c", Math.round(primaryC * 10.0) / 10.0);
                tempObj.put("primary_f", Math.round(((primaryC * 9.0 / 5.0) + 32.0) * 10.0) / 10.0);
            } else {
                tempObj.put("primary_c", JSONObject.NULL);
                tempObj.put("primary_f", JSONObject.NULL);
            }
            tempObj.put("ambient_hw_c", (ambArr != null && ambArr.length > 0) ? Math.round(ambArr[0] * 10.0) / 10.0 : JSONObject.NULL);
            tempObj.put("imu_die_c", imuTemp != null ? Math.round(imuTemp * 10.0) / 10.0 : JSONObject.NULL);
            tempObj.put("battery_c", !Double.isNaN(batTempC) ? Math.round(batTempC * 10.0) / 10.0 : JSONObject.NULL);
            prio.put("temperature", tempObj);

            // 3. Pressure & 4. Altitude (GPS + Barometric)
            float[] pArr = sensorValues.get("pressure_hpa");
            float seaRef = cfg.getSeaLevelHpa();
            double pHpa = (pArr != null && pArr.length > 0) ? pArr[0] : Double.NaN;
            double baroAltM = !Double.isNaN(pHpa) ? SensorManager.getAltitude(seaRef, (float) pHpa) : Double.NaN;
            JSONObject pObj = new JSONObject();
            pObj.put("hpa", !Double.isNaN(pHpa) ? Math.round(pHpa * 100.0) / 100.0 : JSONObject.NULL);
            pObj.put("sea_level_ref_hpa", seaRef);
            prio.put("pressure", pObj);

            double effAltM = Double.NaN;
            String altSrc = "none";
            if (!Double.isNaN(gpsAlt) && !Float.isNaN(gpsAccuracy) && gpsAccuracy <= 25.0f) {
                effAltM = gpsAlt; altSrc = "gps_3d";
            } else if (!Double.isNaN(baroAltM)) {
                effAltM = baroAltM; altSrc = "barometer_bmp580";
            } else if (!Double.isNaN(gpsAlt)) {
                effAltM = gpsAlt; altSrc = "gps_3d";
            }
            JSONObject altObj = new JSONObject();
            altObj.put("source", altSrc);
            altObj.put("effective_m", !Double.isNaN(effAltM) ? Math.round(effAltM * 10.0) / 10.0 : JSONObject.NULL);
            altObj.put("effective_ft", !Double.isNaN(effAltM) ? Math.round(effAltM * 3.28084 * 10.0) / 10.0 : JSONObject.NULL);
            altObj.put("baro_alt_m", !Double.isNaN(baroAltM) ? Math.round(baroAltM * 10.0) / 10.0 : JSONObject.NULL);
            altObj.put("gps_alt_m", !Double.isNaN(gpsAlt) ? Math.round(gpsAlt * 10.0) / 10.0 : JSONObject.NULL);
            prio.put("altitude", altObj);

            // 5. Light Intensity
            float[] lxArr = sensorValues.get("light_lux");
            Float flkVal = findSensorScalarBySubstring("flicker");
            JSONObject lObj = new JSONObject();
            lObj.put("lux", (lxArr != null && lxArr.length > 0) ? Math.round(lxArr[0] * 10.0) / 10.0 : JSONObject.NULL);
            lObj.put("flicker_hz", flkVal != null ? Math.round(flkVal * 10.0) / 10.0 : JSONObject.NULL);
            prio.put("light", lObj);

            // 6. Compass
            JSONObject cmpObj = new JSONObject();
            if (!Float.isNaN(compassDeg)) {
                cmpObj.put("heading_deg", Math.round(compassDeg * 10.0) / 10.0);
                cmpObj.put("cardinal", toCardinal(compassDeg));
                cmpObj.put("reference", "camera_optical_axis");
            } else {
                cmpObj.put("heading_deg", JSONObject.NULL);
                cmpObj.put("cardinal", "--");
            }
            prio.put("compass", cmpObj);

            // 7. Humidity
            float[] rhArr = sensorValues.get("humidity_pct");
            JSONObject rhObj = new JSONObject();
            if (rhArr != null && rhArr.length > 0) {
                rhObj.put("available", true);
                rhObj.put("rh_pct", Math.round(rhArr[0] * 10.0) / 10.0);
                rhObj.put("source", "humidity_hw");
            } else if (!Double.isNaN(externalHumidityPct)) {
                rhObj.put("available", true);
                rhObj.put("rh_pct", Math.round(externalHumidityPct * 10.0) / 10.0);
                rhObj.put("source", "external_hub");
            } else {
                rhObj.put("available", false);
                rhObj.put("rh_pct", JSONObject.NULL);
                rhObj.put("source", "none");
            }
            prio.put("humidity", rhObj);

            // 8. Gravity
            JSONObject grvObj = new JSONObject();
            if (hasGravity) {
                float gx = lastGravity[0], gy = lastGravity[1], gz = lastGravity[2];
                double norm = Math.sqrt(gx * gx + gy * gy + gz * gz);
                double pitchDeg = Math.toDegrees(Math.atan2(gz, Math.sqrt(gx * gx + gy * gy)));
                double rollDeg = Math.toDegrees(Math.atan2(gx, Math.sqrt(gy * gy + gz * gz)));
                grvObj.put("x", Math.round(gx * 100.0) / 100.0);
                grvObj.put("y", Math.round(gy * 100.0) / 100.0);
                grvObj.put("z", Math.round(gz * 100.0) / 100.0);
                grvObj.put("norm_mps2", Math.round(norm * 100.0) / 100.0);
                grvObj.put("pitch_deg", Math.round(pitchDeg * 10.0) / 10.0);
                grvObj.put("roll_deg", Math.round(rollDeg * 10.0) / 10.0);
            }
            prio.put("gravity", grvObj);

            // 9. Gyroscope
            JSONObject gyrObj = new JSONObject();
            if (hasGyro) {
                float rx = lastGyro[0], ry = lastGyro[1], rz = lastGyro[2];
                double rms = Math.sqrt((rx * rx + ry * ry + rz * rz) / 3.0);
                gyrObj.put("x", Math.round(rx * 1000.0) / 1000.0);
                gyrObj.put("y", Math.round(ry * 1000.0) / 1000.0);
                gyrObj.put("z", Math.round(rz * 1000.0) / 1000.0);
                gyrObj.put("rms_rads", Math.round(rms * 1000.0) / 1000.0);
                gyrObj.put("stable", rms < 0.03);
            }
            prio.put("gyroscope", gyrObj);
            root.put("priority", prio);

            // Audio + Stream
            JSONObject aud = new JSONObject();
            aud.put("enabled", audioLive);
            aud.put("peak_dbfs", Math.round(audioPeakDbfs * 10.0) / 10.0);
            aud.put("rms_dbfs", Math.round(audioRmsDbfs * 10.0) / 10.0);
            root.put("audio", aud);

            JSONObject strm = new JSONObject();
            strm.put("rtmp_connected", rtmpConnected);
            strm.put("state", rtmpState);
            strm.put("armed", cfg.isArmed());
            strm.put("preset", cfg.getPresetName());
            strm.put("hud_mode", cfg.getHudMode());
            strm.put("last_error", lastError != null ? lastError : JSONObject.NULL);
            strm.put("fps", Math.round(measuredFps * 10.0) / 10.0);
            strm.put("kbps", Math.round(measuredKbps));
            strm.put("frames_sent", streamFramesSent);
            strm.put("frames_dropped", streamFramesDropped);
            strm.put("uptime_sec", rtmpConnected && streamStartMs > 0 ? (System.currentTimeMillis() - streamStartMs) / 1000 : 0);
            strm.put("still_captures", stillCount);
            strm.put("timelapse_captures", timelapseCount);
            root.put("stream", strm);

            // Camera config snapshot
            JSONObject cam = new JSONObject();
            cam.put("camera_id", cfg.getCameraId());
            cam.put("info", activeCameraInfo);
            cam.put("resolution", cfg.getVideoWidth() + "x" + cfg.getVideoHeight());
            cam.put("target_fps", cfg.getFps());
            cam.put("video_bitrate_kbps", cfg.getVideoBitrateKbps());
            cam.put("focus", cfg.isManualFocus() ? String.format(Locale.US, "MF %.2fD", cfg.getFocusDistance()) : "AF");
            cam.put("exposure", cfg.isManualExposure() ? ("ISO " + cfg.getManualIso() + " | 1/" + Math.round(1e9 / cfg.getManualShutterNs()) + "s") : ("AE " + cfg.getExposureComp()));
            cam.put("awb", cfg.getAwbMode());
            cam.put("zoom", cfg.getZoomRatio());
            cam.put("torch", cfg.isTorchOn());
            cam.put("stabilization", cfg.isStabilizationOn());
            root.put("camera", cam);

            if (includeRawSensors || rawDumpEnabled) {
                JSONObject sns = new JSONObject();
                for (Map.Entry<String, float[]> e : sensorValues.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (float v : e.getValue()) arr.put(Math.round(v * 1000.0) / 1000.0);
                    sns.put(e.getKey(), arr);
                }
                if (!Float.isNaN(compassDeg)) sns.put("compass_deg", Math.round(compassDeg * 10.0) / 10.0);
                root.put("sensors", sns);
            }
        } catch (Throwable ignored) {}
        return root;
    }

    public HudSnapshot buildHudSnapshot(SettingsStore cfg) {
        HudSnapshot h = new HudSnapshot();
        h.station = cfg.getStationName();
        h.hudMode = cfg.getHudMode();
        h.hudVisible = cfg.isHudEnabled() && !"clean".equals(h.hudMode);
        h.hudEnv = cfg.isHudEnv();
        h.hudMotion = cfg.isHudMotion();
        h.hudGps = cfg.isHudGps();
        h.hudRadioPower = cfg.isHudRadioPower();
        h.hudCameraParams = cfg.isHudCameraParams();

        h.clockText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        h.rtmpConnected = rtmpConnected;
        h.streamState = rtmpConnected ? "LIVE" : (cfg.isArmed() ? "ARMED" : "STANDBY");
        h.measuredFps = measuredFps > 0.1 ? measuredFps : cfg.getFps();
        h.measuredKbps = (int) Math.round(measuredKbps);

        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        double batTempC = bi != null ? bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0 : Double.NaN;
        int batPct = -1;
        if (bi != null) {
            int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (sc > 0) batPct = Math.round(lv * 100f / sc);
        }

        float[] ambArr = sensorValues.get("ambient_temp_c");
        Float imuTemp = findSensorScalarBySubstringExcluding("temp", "ambient_temp_c");
        double primaryC = !Double.isNaN(externalTempC) ? externalTempC
            : (ambArr != null && ambArr.length > 0 ? ambArr[0] : (!Double.isNaN(batTempC) ? batTempC : (imuTemp != null ? imuTemp : Double.NaN)));
        if (!Double.isNaN(primaryC)) {
            double f = primaryC * 9.0 / 5.0 + 32.0;
            h.tempText = String.format(Locale.US, "%.1f°F / %.1f°C", f, primaryC);
        }

        float[] pArr = sensorValues.get("pressure_hpa");
        if (pArr != null && pArr.length > 0) {
            h.pressureText = String.format(Locale.US, "%.1f hPa", pArr[0]);
            double baroAlt = SensorManager.getAltitude(cfg.getSeaLevelHpa(), pArr[0]);
            if (!Double.isNaN(gpsAlt) && !Float.isNaN(gpsAccuracy) && gpsAccuracy <= 25.0f) {
                h.altitudeText = String.format(Locale.US, "%.0fm GPS", gpsAlt);
            } else {
                h.altitudeText = String.format(Locale.US, "%.0fm BARO", baroAlt);
            }
        } else if (!Double.isNaN(gpsAlt)) {
            h.altitudeText = String.format(Locale.US, "%.0fm GPS", gpsAlt);
        }

        float[] lxArr = sensorValues.get("light_lux");
        Float flk = findSensorScalarBySubstring("flicker");
        if (lxArr != null && lxArr.length > 0) {
            h.lightText = flk != null
                ? String.format(Locale.US, "%.0f lx (%.0fHz)", lxArr[0], flk)
                : String.format(Locale.US, "%.0f lx", lxArr[0]);
        }

        float[] rhArr = sensorValues.get("humidity_pct");
        if (rhArr != null && rhArr.length > 0) {
            h.humidityText = String.format(Locale.US, "RH %.0f%%", rhArr[0]);
        } else if (!Double.isNaN(externalHumidityPct)) {
            h.humidityText = String.format(Locale.US, "RH %.0f%%", externalHumidityPct);
        } else {
            h.humidityText = "RH N/A";
        }

        if (!Float.isNaN(compassDeg)) {
            h.compassText = String.format(Locale.US, "%.0f° %s", compassDeg, toCardinal(compassDeg));
        }

        if (hasGravity) {
            double pitch = Math.toDegrees(Math.atan2(lastGravity[2], Math.sqrt(lastGravity[0] * lastGravity[0] + lastGravity[1] * lastGravity[1])));
            h.gravityText = String.format(Locale.US, "%.1f,%.1f,%.1f (%.0f°)", lastGravity[0], lastGravity[1], lastGravity[2], pitch);
        }
        if (hasGyro) {
            h.gyroText = String.format(Locale.US, "%.2f,%.2f,%.2f r/s", lastGyro[0], lastGyro[1], lastGyro[2]);
        }

        if (h.hudGps) {
            h.gpsText = !Double.isNaN(gpsLat)
                ? String.format(Locale.US, "GPS %.4f,%.4f (%d/%dsat)", gpsLat, gpsLon, gpsSatsUsed, gpsSatsVisible)
                : String.format(Locale.US, "GPS SEARCH (%d/%dsat)", gpsSatsUsed, gpsSatsVisible);
        }

        String focusStr = cfg.isManualFocus() ? String.format(Locale.US, "MF %.1fD", cfg.getFocusDistance()) : "AF";
        String expStr = cfg.isManualExposure()
            ? ("ISO" + cfg.getManualIso() + " 1/" + Math.max(1, Math.round(1e9 / cfg.getManualShutterNs())))
            : ("AE" + cfg.getExposureComp());
        String audStr = audioLive ? String.format(Locale.US, "MIC %.0fdB", audioPeakDbfs) : "MIC OFF";
        h.camBadgeText = String.format(Locale.US, "CAM#%s %dx%dp%d · %s · %s · %.1fx · %s",
            cfg.getCameraId(), cfg.getVideoWidth(), cfg.getVideoHeight(), cfg.getFps(), focusStr, expStr, cfg.getZoomRatio(), audStr);

        if (h.hudRadioPower) {
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int rssi = wm != null && wm.getConnectionInfo() != null ? wm.getConnectionInfo().getRssi() : 0;
                h.radioBadgeText = String.format(Locale.US, "BAT %d%% · WIFI %ddBm · %s", batPct, rssi, getLocalIpv4());
            } catch (Throwable ignored) {}
        }
        return h;
    }
}
