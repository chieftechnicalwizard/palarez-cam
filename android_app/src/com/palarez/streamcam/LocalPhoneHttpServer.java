package com.palarez.streamcam;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * LocalPhoneHttpServer — Zero-Dependency Pure-Java HTTP/1.1 Server Embedded on the Phone (:8890).
 *
 * <p>Provides a human-safe browser console and a self-describing, machine-readable REST + streaming
 * interface so local operators, {@code go2rtc} / {@code FFmpeg} media hubs, Home Assistant, or
 * autonomous broadcast scripts can discover, inspect, tune, validate, and pull media from the phone
 * over LAN without cloud relays or proprietary SDKs.
 *
 * <h3>Endpoints (:8890 default)</h3>
 * <ul>
 *   <li>{@code GET /} — Browser remote console with live {@code /mjpeg} preview, quick toggles,
 *       non-publishing RTMP dry-run validator, and 2-step Go Live / Stop confirmation.</li>
 *   <li>{@code GET /api/capabilities} — Enumerated Camera2 physical/logical lenses, encoder presets,
 *       supported resolutions/FPS, HUD modes, and hardware sensor presence flags.</li>
 *   <li>{@code GET /api/schema} — Self-describing JSON schema covering every endpoint and every
 *       supported {@code POST /api/control} field, range, and default.</li>
 *   <li>{@code GET /api/status} — Lifecycle state ({@code IDLE}, {@code ARMED_READY},
 *       {@code CONNECTING}, {@code STREAMING}, {@code ERROR}), readiness flags, masked RTMP target,
 *       on-device music player state, LAN URLs, and actionable {@code warnings[]}.</li>
 *   <li>{@code GET /api/telemetry} — Structured 9-priority sensor block + battery, Wi-Fi, GPS,
 *       audio dBFS, camera settings, and stream counters ({@code ?raw_sensors=1} or {@code ?raw=1}
 *       appends raw sensor float arrays).</li>
 *   <li>{@code GET /snapshot.jpg} — Synchronous {@code 640x360} OpenGL JPEG frame ({@code ?clean=1}
 *       omits HUD without altering live stream HUD state; {@code ?fullres=1} triggers a native
 *       full-sensor Camera2 JPEG capture + JSON sidecar).</li>
 *   <li>{@code GET /mjpeg} — {@code ~5 fps} ({@code 200 ms}) {@code 640x360} multipart MJPEG preview
 *       stream ({@code ?clean=1} omits HUD; auto-idles GPU readback 15s after last client disconnects).</li>
 *   <li>{@code GET /stream.flv} — Live hardware H.264 + AAC-LC {@code 44.1 kHz} HTTP-FLV pull stream
 *       for {@code go2rtc} / {@code FFmpeg} ({@code ?clean=1} switches shared encoder HUD mode to
 *       {@code "clean"} so downstream hubs receive an un-overlaid mezzanine feed).</li>
 *   <li>{@code GET /api/audio/tracks} — Lists uploaded audio files in app storage and current
 *       {@link MusicPcmPlayer} + mic/music gain mixer state.</li>
 *   <li>{@code POST /api/audio/upload?name=<file>} — Streams raw audio binary ({@code 1 B .. 64 MB},
 *       {@code .mp3/.wav/.m4a/.aac/.flac/.ogg}) atomically onto phone storage for standalone RTMP mixing.</li>
 *   <li>{@code POST /api/rtmp/validate} — Non-publishing DNS + TCP + TLS + RTMP C0/C1/C2 + AMF0
 *       {@code connect(app)} handshake check. Stops before {@code FCPublish}/{@code publish} so
 *       YouTube auto-start broadcasts NEVER go live during validation.</li>
 *   <li>{@code POST /api/control} — Atomic JSON configuration, live Camera2 optics tuning, external
 *       temp/humidity injection, on-device music transport, arming, and explicit stream start/stop.</li>
 * </ul>
 */
public final class LocalPhoneHttpServer {
    /**
     * Bridge contract implemented by {@link StreamService} to service HTTP API calls safely
     * against the active Camera2, OpenGL HUD, audio mixer, and RTMP/FLV encoder pipelines.
     */
    public interface Delegate {
        /** Returns structured 9-sensor telemetry JSON, optionally including raw sensor vectors. */
        JSONObject getTelemetryJson(boolean rawSensors);
        /** Returns static hardware lens specs, presets, HUD modes, and physical sensor flags. */
        JSONObject getCapabilitiesJson();
        /** Returns live readiness flags, masked RTMP target, warnings, and LAN endpoint URLs. */
        JSONObject getStatusJson();
        /** Runs a non-publishing RTMP/RTMPS handshake check (optional {@code {"rtmp_url": "..."}}). */
        JSONObject validateRtmpDryRun(JSONObject opts);
        /** Lists staged audio tracks on the phone and current {@link MusicPcmPlayer} state. */
        JSONObject getAudioTracksJson();
        /** Saves an uploaded audio file ({@code 1 B .. 64 MB}) into internal {@code files/tracks/}. */
        JSONObject uploadAudioTrack(String rawName, InputStream bodyStream, long contentLength);
        /** Captures either a full-resolution Camera2 JPEG or synchronous {@code 640x360} GL JPEG. */
        byte[] getSnapshotJpeg(boolean fullRes, boolean clean);
        /** Streams muxed H.264 + AAC FLV tags to {@code out} until client disconnect or stop. */
        void streamFlvTo(OutputStream out, boolean cleanHud) throws IOException;
        /** Applies a JSON control payload atomically and returns updated {@code status}. */
        JSONObject applyControlJson(JSONObject cmd);
    }

    private final int port;
    private final Delegate delegate;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    public LocalPhoneHttpServer(int port, Delegate delegate) {
        this.port = port;
        this.delegate = delegate;
    }

    public synchronized void start() {
        stop();
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                while (running) {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                }
            } catch (Throwable ignored) {}
        }, "PalaRezHTTP");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Throwable ignored) {}
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handleClient(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(12000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];
            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
            String query = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "";

            long contentLength = 0;
            String headerLine;
            int headerCount = 0;
            while (headerCount++ < 64 && (headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int idx = headerLine.indexOf(':');
                if (idx > 0 && "content-length".equalsIgnoreCase(headerLine.substring(0, idx).trim())) {
                    try {
                        long parsedLen = Long.parseLong(headerLine.substring(idx + 1).trim());
                        contentLength = Math.max(0L, parsedLen);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(out, "204 No Content", "text/plain", new byte[0]);
                return;
            }

            if ("GET".equals(method) && "/".equals(path)) {
                String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>PalaRez StreamCam</title>" +
                    "<style>body{background:#0b1220;color:#e2e8f0;font-family:system-ui;padding:16px;max-width:920px;margin:auto}" +
                    "img{width:100%;border-radius:12px;border:1px solid #10b98166}pre{background:#07120a;border:1px solid #10b98144;padding:12px;border-radius:10px;overflow:auto;font-size:12px}" +
                    "button{background:#0284c7;color:#fff;border:0;padding:9px 14px;border-radius:8px;margin:4px;font-weight:700;cursor:pointer}</style></head>" +
                    "<body><h2>PalaRez StreamCam — Sanctuary Telemetry &amp; YouTube Ingest Console</h2>" +
                    "<img src='/mjpeg'/><div style='margin:12px 0'>" +
                    "<button onclick=\"cmd({torch:true})\">Torch ON</button>" +
                    "<button onclick=\"cmd({torch:false})\">Torch OFF</button>" +
                    "<button onclick=\"cmd({audio_enabled:true})\">Mic ON</button>" +
                    "<button onclick=\"cmd({audio_enabled:false})\">Mic MUTE</button>" +
                    "<button onclick=\"cmd({anti_flicker:true})\">Anti-Strobe ON</button>" +
                    "<button onclick=\"cmd({anti_flicker:false})\">Anti-Strobe OFF</button>" +
                    "<button onclick=\"cmd({snap:true})\">Snap Photo</button>" +
                    "<button onclick=\"validate()\" style='background:#0d9488'>Dry-Run Validate Ingest</button>" +
                    "<button onclick=\"confirmGoLive()\" style='background:#059669'>Go Live RTMP</button>" +
                    "<button onclick=\"confirmStopLive()\" style='background:#dc2626'>Stop RTMP</button></div>" +
                    "<pre id='v' style='display:none'></pre><pre id='t'>Loading status &amp; priority sensors...</pre>" +
                    "<script>async function cmd(o){await fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)});}" +
                    "async function validate(){const r=await fetch('/api/rtmp/validate',{method:'POST',headers:{'Content-Type':'application/json'},body:'{\"level\":\"handshake\"}'});" +
                    "const el=document.getElementById('v');el.style.display='block';el.textContent='DRY-RUN RESULT (STREAM NOT STARTED):\\n'+JSON.stringify(await r.json(),null,2);}" +
                    "async function confirmGoLive(){const s=await (await fetch('/api/status')).json();if(!s.ready_for_direct_youtube){alert('Not ready: '+(s.warnings||[]).join(', '));return;}" +
                    "if(confirm('START LIVE BROADCAST to '+s.rtmp_target_masked+' ('+s.video_format+')?'))cmd({start_stream:true});}" +
                    "function confirmStopLive(){if(confirm('Stop active RTMP broadcast?'))cmd({stop_stream:true});}" +
                    "setInterval(async()=>{const [st,tl]=await Promise.all([fetch('/api/status').then(r=>r.json()),fetch('/api/telemetry').then(r=>r.json())]);" +
                    "document.getElementById('t').textContent=JSON.stringify({status:st,priority:tl.priority,audio:tl.audio,camera:tl.camera},null,2);},1000);</script>" +
                    "</body></html>";
                sendResponse(out, "200 OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equals(method) && "/api/capabilities".equals(path)) {
                sendResponse(out, "200 OK", "application/json; charset=utf-8",
                    delegate.getCapabilitiesJson().toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equals(method) && "/api/status".equals(path)) {
                sendResponse(out, "200 OK", "application/json; charset=utf-8",
                    delegate.getStatusJson().toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equals(method) && "/api/schema".equals(path)) {
                String schemaJson = "{\n" +
                    "  \"api_version\": \"2.0\",\n" +
                    "  \"mdns_service\": \"_palarez-cam._tcp.local.\",\n" +
                    "  \"endpoints\": {\n" +
                    "    \"GET /\": \"Browser remote console with live /mjpeg preview, quick toggles, dry-run validator & 2-step Go Live confirmation\",\n" +
                    "    \"GET /api/capabilities\": \"Static lens inventory, broadcast presets, supported resolutions/FPS, HUD modes & hardware sensor flags\",\n" +
                    "    \"GET /api/schema\": \"Self-describing JSON contract for all HTTP endpoints and POST /api/control fields\",\n" +
                    "    \"GET /api/status\": \"Readiness flags, lifecycle_state, masked RTMP target, music mixer state, warnings[] & LAN endpoints\",\n" +
                    "    \"GET /api/telemetry?raw_sensors=1\": \"9 priority sensors + battery, Wi-Fi, GPS, audio dBFS, camera & stream stats (?raw_sensors=1 or ?raw=1 adds raw vectors)\",\n" +
                    "    \"GET /snapshot.jpg?clean=1&fullres=1\": \"Synchronous 640x360 GL JPEG (?clean=1 omits HUD non-destructively; ?fullres=1 captures native sensor JPEG + JSON sidecar)\",\n" +
                    "    \"GET /mjpeg?clean=1\": \"~5fps 640x360 multipart MJPEG preview (?clean=1 omits HUD; zero GPU readback overhead when idle)\",\n" +
                    "    \"GET /stream.flv?clean=1\": \"Live hardware H.264 + AAC-LC 44.1kHz HTTP-FLV pull stream (?clean=1 sets shared encoder hud_mode to clean)\",\n" +
                    "    \"GET /api/audio/tracks\": \"List staged audio tracks in phone storage plus MusicPcmPlayer & mic/music gain state\",\n" +
                    "    \"POST /api/audio/upload?name=track.mp3\": \"Raw binary body upload (1B..64MB; .mp3/.wav/.m4a/.aac/.flac/.ogg) for on-device PCM mixing\",\n" +
                    "    \"POST /api/rtmp/validate\": \"Non-publishing DNS/TCP/TLS/RTMP C0-C2 + AMF0 connect(app) dry-run; optional body {\\\"rtmp_url\\\": \\\"rtmps://...\\\", \\\"level\\\": \\\"handshake\\\"}\",\n" +
                    "    \"POST /api/control\": {\n" +
                    "      \"preset\": \"sanctuary_eco_1080p15_800k | relief_1080p15_480k | youtube_hd_1080p24_2000k | motion_1080p30_4500k | hub_mezzanine_1440p15_4000k (restarts camera/encoder pipeline)\",\n" +
                    "      \"width\": \"int (e.g. 1280, 1920, 2560; requires height, restarts pipeline)\",\n" +
                    "      \"height\": \"int (e.g. 720, 1080, 1440; requires width, restarts pipeline)\",\n" +
                    "      \"fps\": \"int 5..60 (UI presets: 15 | 24 | 30; default 15, restarts pipeline)\",\n" +
                    "      \"video_bitrate_kbps\": \"int 300..12000 (default 800; applies on next encoder start or pipeline restart)\",\n" +
                    "      \"audio_bitrate_kbps\": \"int 32..256 (default 96; AAC-LC 44.1kHz mono)\",\n" +
                    "      \"keyframe_sec\": \"int 1..10 (default 2; H.264 I-frame interval in seconds)\",\n" +
                    "      \"camera_id\": \"string ('0' Back Main OIS | '1' Front | '2' Back Macro; restarts pipeline)\",\n" +
                    "      \"manual_focus\": \"bool (default false = AF continuous video; true = lock LENS_FOCUS_DISTANCE)\",\n" +
                    "      \"focus_distance\": \"float >= 0.0 diopters (0.0 = infinity, 10.0 = 10cm, 40.0 = 2.5cm macro; hot-applied)\",\n" +
                    "      \"manual_exposure\": \"bool (default false = AE with exposure_comp; true = manual iso + shutter_ns)\",\n" +
                    "      \"iso\": \"int >= 50 (clamped to lens iso_range, default 400; hot-applied when manual_exposure=true)\",\n" +
                    "      \"shutter_ns\": \"long >= 100000 (exposure ns, clamped to lens range <= 500000000ns [1/2s], default 16666667 [1/60s])\",\n" +
                    "      \"exposure_comp\": \"int (AE EV bias steps clamped to lens evMin..evMax, default 0; hot-applied)\",\n" +
                    "      \"awb\": \"auto | daylight | cloudy | incandescent | tungsten | fluorescent | shade (default auto)\",\n" +
                    "      \"zoom\": \"float >= 1.0 (clamped to lens max_zoom, default 1.0; hot-applied)\",\n" +
                    "      \"torch\": \"bool (rear LED flashlight toggle if lens has_flash, default false)\",\n" +
                    "      \"stabilization\": \"bool (OIS + EIS video stabilization toggle, default true)\",\n" +
                    "      \"anti_flicker\": \"bool shorthand (true -> '60hz_tmix', false -> 'off')\",\n" +
                    "      \"anti_flicker_mode\": \"off | 60hz_hw | 60hz_tmix | 50hz_tmix (default 'off')\",\n" +
                    "      \"rotation_deg\": \"int 0 | 90 | 180 | 270 (0=Landscape upright, 90/270=Portrait pillarboxed in 16:9, 180=Landscape flipped)\",\n" +
                    "      \"audio_enabled\": \"bool (hardware mic toggle, default true; false physically closes AudioRecord & feeds music or silent PCM)\",\n" +
                    "      \"mic_gain\": \"float 0.0..2.0 (default 1.0)\",\n" +
                    "      \"music_gain\": \"float 0.0..2.0 (default 0.90)\",\n" +
                    "      \"music_loop\": \"bool (loop active on-device audio track, default false)\",\n" +
                    "      \"music_track\": \"string (filename in phone tracks dir to load, or '' to unload; auto-plays unless music_playing=false)\",\n" +
                    "      \"music_playing\": \"bool (play/pause loaded on-device audio track)\",\n" +
                    "      \"music_seek_start\": \"bool (true seeks loaded track to 0:00)\",\n" +
                    "      \"music_stop\": \"bool (true stops and unloads active on-device audio track)\",\n" +
                    "      \"hud_enabled\": \"bool (master HUD visibility toggle, default true)\",\n" +
                    "      \"hud_mode\": \"sanctuary_card | minimal_pill | clean (default 'sanctuary_card')\",\n" +
                    "      \"hud_env\": \"bool (show Temp/Pressure/Altitude/Lux/Humidity row, default true)\",\n" +
                    "      \"hud_motion\": \"bool (show Compass/Gravity/Gyroscope row, default true)\",\n" +
                    "      \"hud_camera\": \"bool (show Lens/Res/Focus/Exposure/Zoom/Mic footer, default true)\",\n" +
                    "      \"hud_gps\": \"bool (burn GPS coordinates onto video; default false for OPSEC)\",\n" +
                    "      \"hud_radio_power\": \"bool (burn Battery % & LAN IP onto video; default false for OPSEC)\",\n" +
                    "      \"sea_level_hpa\": \"float 850.0..1100.0 (QNH reference for indoor barometric altitude, default 1013.25)\",\n" +
                    "      \"raw_sensors\": \"bool (register & include non-priority OEM debug sensors in /api/telemetry, default false)\",\n" +
                    "      \"sensor_interval_ms\": \"int 100..10000 (sensor sampling period in ms, default 500)\",\n" +
                    "      \"timelapse_enabled\": \"bool (periodic full-res JPEG + JSON sidecar capture, default false)\",\n" +
                    "      \"timelapse_interval_sec\": \"int 3..86400 (timelapse period in seconds, default 60)\",\n" +
                    "      \"rtmp_url\": \"string (full rtmp[s]://host/app/stream_key target)\",\n" +
                    "      \"rtmp_server\": \"string (ingest base URL e.g. 'rtmps://a.rtmps.youtube.com/live2'; combined with stream_key)\",\n" +
                    "      \"stream_key\": \"string (secret stream key; never returned unmasked in status/telemetry)\",\n" +
                    "      \"archive_url\": \"string (optional Companion Hub URL e.g. 'http://<HUB_IP>:8895' for telemetry/frame archival)\",\n" +
                    "      \"station_name\": \"string (HUD callsign, mDNS name & telemetry station ID, default 'PalaRez-Cam1')\",\n" +
                    "      \"display_mode\": \"keep_awake | blackout | auto_sleep (default 'keep_awake')\",\n" +
                    "      \"external_humidity_pct\": \"float (inject external hygrometer %RH from Home Assistant / hub; NaN clears)\",\n" +
                    "      \"external_temp_c\": \"float (inject external ambient temperature in °C from hub; NaN clears)\",\n" +
                    "      \"clear_error\": \"bool (true clears sticky last_error banner)\",\n" +
                    "      \"arm\": \"bool (stage lifecycle_state as ARMED_READY without starting outbound RTMP)\",\n" +
                    "      \"snap\": \"bool (true triggers async full-res JPEG + JSON telemetry sidecar capture)\",\n" +
                    "      \"start_stream\": \"bool (STARTS LIVE RTMP/RTMPS BROADCAST — never pass true during staging!)\",\n" +
                    "      \"stop_stream\": \"bool (true stops active outbound RTMP/RTMPS broadcast)\"\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n";
                sendResponse(out, "200 OK", "application/json; charset=utf-8", schemaJson.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equals(method) && "/api/telemetry".equals(path)) {
                boolean raw = queryFlag(query, "raw_sensors") || queryFlag(query, "raw");
                JSONObject j = delegate.getTelemetryJson(raw);
                sendResponse(out, "200 OK", "application/json; charset=utf-8", j.toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equals(method) && "/snapshot.jpg".equals(path)) {
                boolean fullRes = queryFlag(query, "fullres");
                boolean clean = queryFlag(query, "clean");
                byte[] jpg = delegate.getSnapshotJpeg(fullRes, clean);
                if (jpg == null || jpg.length == 0) {
                    sendResponse(out, "503 Service Unavailable", "text/plain", "Camera warming up".getBytes(StandardCharsets.UTF_8));
                } else {
                    sendResponse(out, "200 OK", "image/jpeg", jpg);
                }
                return;
            }

            if ("GET".equals(method) && "/mjpeg".equals(path)) {
                sock.setSoTimeout(0);
                boolean clean = queryFlag(query, "clean");
                String hdr = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=palarezframe\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                while (running) {
                    byte[] jpg = delegate.getSnapshotJpeg(false, clean);
                    if (jpg != null && jpg.length > 0) {
                        String partHdr = String.format(Locale.US, "--palarezframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", jpg.length);
                        out.write(partHdr.getBytes(StandardCharsets.US_ASCII));
                        out.write(jpg);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    Thread.sleep(200);
                }
                return;
            }

            if ("GET".equals(method) && "/stream.flv".equals(path)) {
                sock.setSoTimeout(0);
                sock.setTcpNoDelay(true);
                boolean clean = queryFlag(query, "clean");
                String hdr = "HTTP/1.1 200 OK\r\nContent-Type: video/x-flv\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                delegate.streamFlvTo(out, clean);
                return;
            }

            if ("GET".equals(method) && "/api/audio/tracks".equals(path)) {
                sendResponse(out, "200 OK", "application/json; charset=utf-8",
                    delegate.getAudioTracksJson().toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("POST".equals(method) && "/api/audio/upload".equals(path)) {
                sock.setSoTimeout(60000);
                String name = queryParam(query, "name");
                JSONObject res = delegate.uploadAudioTrack(name, in, contentLength);
                sendResponse(out, res.optBoolean("ok") ? "200 OK" : "400 Bad Request",
                    "application/json; charset=utf-8", res.toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("POST".equals(method) && "/api/rtmp/validate".equals(path)) {
                byte[] body = readBytes(in, (int) Math.min(contentLength, 65536L));
                JSONObject opts;
                try {
                    opts = body.length > 0 ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
                } catch (JSONException je) {
                    sendResponse(out, "400 Bad Request", "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"Invalid JSON body\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                JSONObject res = delegate.validateRtmpDryRun(opts);
                sendResponse(out, "200 OK", "application/json; charset=utf-8", res.toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("POST".equals(method) && "/api/control".equals(path)) {
                byte[] body = readBytes(in, (int) Math.min(contentLength, 65536L));
                JSONObject cmd;
                try {
                    cmd = new JSONObject(new String(body, StandardCharsets.UTF_8));
                } catch (JSONException je) {
                    sendResponse(out, "400 Bad Request", "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"Invalid JSON body\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                JSONObject res = delegate.applyControlJson(cmd);
                sendResponse(out, "200 OK", "application/json; charset=utf-8", res.toString(2).getBytes(StandardCharsets.UTF_8));
                return;
            }

            sendResponse(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static boolean queryFlag(String query, String key) {
        String v = queryParam(query, key);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Throwable ignored) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return "";
    }

    private static void sendResponse(OutputStream out, String status, String ctype, byte[] body) throws IOException {
        String hdr = String.format(Locale.US,
            "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n",
            status, ctype, body.length);
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') {
                if (b.size() >= 16384) throw new IOException("HTTP line too long");
                b.write(c);
            }
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readBytes(InputStream in, int n) throws IOException {
        if (n <= 0) return new byte[0];
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return off == n ? buf : Arrays.copyOf(buf, off);
    }
}
