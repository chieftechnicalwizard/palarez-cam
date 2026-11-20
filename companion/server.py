#!/usr/bin/env python3
"""
PalaRez StreamCam — Optional LAN Companion Archive, Phone Registry & APK Host (:8895)
======================================================================================
The Android app (`palarez-cam.apk`) runs 100% standalone on Android 9–16+ devices
(including direct YouTube Live RTMP/RTMPS streaming, HTTP-FLV pull on
`:8890/stream.flv`, mDNS `_palarez-cam._tcp.`, and full `:8890` REST control API).

Run this zero-dependency Python stdlib companion server when you want:
  1. Browser & SVG QR download of `output/palarez-cam.apk` (`GET /`, `GET /apk/palarez-cam.apk`),
  2. Multi-phone LAN registry (`GET /api/phones`) populated automatically when phones set
     `archive_url` to `http://<HUB_IP>:8895`,
  3. Central LAN archival of periodic 9-sensor telemetry (`data/telemetry.jsonl`, `GET /api/latest`)
     and full-resolution still/timelapse frames (`POST /api/ingest/frame`, `GET /frames/<name>`).

Environment Variables:
  PALAREZ_HOST      Bind address (default: 0.0.0.0)
  PALAREZ_PORT      Bind TCP port (default: 8895)
  PALAREZ_DATA_DIR  Directory for telemetry.jsonl, phones.json & frames/ (default: ./data)
  PALAREZ_APK_PATH  Path to signed APK served at /apk/palarez-cam.apk (default: ./output/palarez-cam.apk)
"""

from __future__ import annotations

import html
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Any

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.environ.get("PALAREZ_DATA_DIR", str(ROOT_DIR / "data")))
FRAMES_DIR = DATA_DIR / "frames"
TELEMETRY_LOG = DATA_DIR / "telemetry.jsonl"
PHONES_FILE = DATA_DIR / "phones.json"
APK_PATH = Path(os.environ.get("PALAREZ_APK_PATH", str(ROOT_DIR / "output" / "palarez-cam.apk")))
HOST = os.environ.get("PALAREZ_HOST", "0.0.0.0")
PORT = int(os.environ.get("PALAREZ_PORT", "8895"))
MAX_TELEMETRY_BYTES = 1 * 1024 * 1024
MAX_FRAME_BYTES = 32 * 1024 * 1024

STATE_LOCK = threading.Lock()
LATEST_TELEMETRY: dict[str, Any] = {}
PHONES_REGISTRY: dict[str, dict[str, Any]] = {}


def _safe_http_href(url: str) -> str:
    s = str(url or "").strip()
    if s.startswith(("http://", "https://")):
        return html.escape(s, quote=True)
    return "#"


def _load_phones() -> None:
    if PHONES_FILE.exists():
        try:
            data = json.loads(PHONES_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                with STATE_LOCK:
                    PHONES_REGISTRY.update(data)
        except Exception:
            pass


def _save_phones_locked() -> None:
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = PHONES_FILE.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(PHONES_REGISTRY, indent=2), encoding="utf-8")
        os.replace(tmp, PHONES_FILE)
    except Exception:
        pass


def generate_qr_svg(url: str) -> str:
    qr = shutil.which("qrencode")
    if qr:
        p = subprocess.run([qr, "-t", "SVG", "-m", "2", "-o", "-", url], capture_output=True, text=True, timeout=6)
        if p.returncode == 0 and "<svg" in p.stdout:
            return p.stdout[p.stdout.index("<svg"):]
    return ""


class CompanionHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write(f"[palarez-hub] {self.address_string()} - {fmt % args}\n")

    def _send_json(self, obj: Any, status: int = 200) -> None:
        raw = json.dumps(obj, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/":
            host_hdr = self.headers.get("Host") or f"127.0.0.1:{PORT}"
            apk_url = f"http://{host_hdr}/apk/palarez-cam.apk"
            svg = generate_qr_svg(apk_url)
            frames = sorted(FRAMES_DIR.glob("*.jpg"), reverse=True)[:12] if FRAMES_DIR.exists() else []
            gallery = "".join(
                f"<div style='background:#131c2e;padding:8px;border-radius:8px'><a href='/frames/{urllib.parse.quote(f.name)}' target='_blank'>"
                f"<img src='/frames/{urllib.parse.quote(f.name)}' style='width:100%;border-radius:6px'/></a>"
                f"<div style='font-size:11px;color:#94a3b8;margin-top:4px'>{html.escape(f.name)}</div></div>"
                for f in frames
            )
            now = time.time()
            with STATE_LOCK:
                phones_snapshot = [dict(p) for p in PHONES_REGISTRY.values()]
                tele_snapshot = dict(LATEST_TELEMETRY)
            phone_cards = "".join(
                f"<div style='background:#0b1220;border:1px solid #10b98155;border-radius:8px;padding:10px;margin-bottom:8px'>"
                f"<b>{html.escape(str(p.get('station', '')))}</b> ({html.escape(str(p.get('model', '')))}) · "
                f"<a href='{_safe_http_href(p.get('http_url', ''))}' style='color:#38bdf8' target='_blank'>{html.escape(str(p.get('http_url', '')))}</a> · "
                f"FLV: <code>{html.escape(str(p.get('flv_url', '')))}</code> · State: <b>{html.escape(str(p.get('rtmp_state', '')))}</b> · "
                f"Seen {int(max(0, now - p.get('last_seen_epoch', now)))}s ago</div>"
                for p in phones_snapshot
            )
            html_page = f"""<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>PalaRez StreamCam Companion Hub</title>
<style>body{{background:#0b1220;color:#e2e8f0;font-family:system-ui;margin:0;padding:24px;max-width:1100px;margin:auto}}
.card{{background:#131c2e;border:1px solid #24324d;border-radius:12px;padding:18px;margin-bottom:18px}}
.btn{{display:inline-block;background:#0ea5e9;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none;font-weight:700}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}}
pre{{background:#0b1220;padding:12px;border-radius:8px;overflow:auto;font-size:12px}}</style></head>
<body>
<h1>PalaRez StreamCam — Companion Archive &amp; Agent Hub</h1>
<div class='card'>
  <p>APK Ready: <b>{APK_PATH.exists()}</b> ({APK_PATH.stat().st_size if APK_PATH.exists() else 0} bytes)</p>
  <a class='btn' href='/apk/palarez-cam.apk'>Download palarez-cam.apk</a>
  <div style='background:#fff;padding:10px;border-radius:8px;display:inline-block;vertical-align:middle;margin-left:16px;width:150px'>{svg}</div>
</div>
<div class='card'>
  <h2>Discovered / Registered Phones ({len(phones_snapshot)})</h2>
  {phone_cards or '<span style="color:#94a3b8">No phones have pushed telemetry yet.</span>'}
</div>
<div class='card'>
  <h2>Latest Archived Telemetry</h2>
  <pre id='tele'>{html.escape(json.dumps(tele_snapshot, indent=2))}</pre>
</div>
<div class='card'>
  <h2>Recent Captured Frames ({len(frames)})</h2>
  <div class='grid'>{gallery or '<span style="color:#94a3b8">No frames uploaded yet.</span>'}</div>
</div>
<script>setInterval(async()=>{{const r=await fetch('/api/latest');document.getElementById('tele').textContent=JSON.stringify(await r.json(),null,2);}},2000);</script>
</body></html>""".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html_page)))
            self.end_headers()
            self.wfile.write(html_page)
            return

        if path == "/api/phones":
            now = time.time()
            items = []
            with STATE_LOCK:
                for p in PHONES_REGISTRY.values():
                    rec = dict(p)
                    rec["last_seen_age_sec"] = round(max(0.0, now - p.get("last_seen_epoch", now)), 1)
                    items.append(rec)
            self._send_json({"phones": items})
            return

        if path == "/apk/palarez-cam.apk":
            if not APK_PATH.exists():
                self._send_json({"error": "APK not compiled yet"}, 404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(APK_PATH.stat().st_size))
            self.end_headers()
            with open(APK_PATH, "rb") as fh:
                shutil.copyfileobj(fh, self.wfile)
            return

        if path.startswith("/frames/"):
            fname = os.path.basename(urllib.parse.unquote(path))
            if not fname or fname in (".", "..") or fname.startswith("."):
                self._send_json({"error": "Invalid frame name"}, 400)
                return
            target = (FRAMES_DIR / fname).resolve()
            if target.parent != FRAMES_DIR.resolve() or not target.is_file():
                self._send_json({"error": "Frame not found"}, 404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(target.stat().st_size))
            self.end_headers()
            with open(target, "rb") as fh:
                shutil.copyfileobj(fh, self.wfile)
            return

        if path == "/api/latest":
            with STATE_LOCK:
                tele_snapshot = dict(LATEST_TELEMETRY)
            self._send_json(tele_snapshot)
            return

        self._send_json({"error": "Not found"}, 404)

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json({"error": "Invalid Content-Length"}, 400)
            return
        if length < 0:
            self._send_json({"error": "Invalid Content-Length"}, 400)
            return

        if parsed.path == "/api/ingest/telemetry":
            if length > MAX_TELEMETRY_BYTES:
                self._send_json({"error": "Telemetry payload too large"}, 413)
                return
            body = self.rfile.read(length) if length > 0 else b""
            try:
                obj = json.loads(body.decode("utf-8"))
                if not isinstance(obj, dict):
                    raise ValueError("Telemetry JSON must be an object")

                station = str(obj.get("station") or self.client_address[0])
                ip = (obj.get("network") or {}).get("ipv4") or self.client_address[0]
                base = f"http://{ip}:8890"
                record = {
                    "station": station,
                    "model": obj.get("model", ""),
                    "android": obj.get("android", ""),
                    "ipv4": ip,
                    "http_url": base,
                    "status_url": f"{base}/api/status",
                    "capabilities_url": f"{base}/api/capabilities",
                    "flv_url": f"{base}/stream.flv",
                    "flv_clean_url": f"{base}/stream.flv?clean=1",
                    "rtmp_state": (obj.get("stream") or {}).get("state", "IDLE"),
                    "battery_percent": (obj.get("battery") or {}).get("percent"),
                    "rssi_dbm": (obj.get("network") or {}).get("rssi_dbm"),
                    "priority": obj.get("priority", {}),
                    "last_seen_epoch": time.time(),
                }
                with STATE_LOCK:
                    LATEST_TELEMETRY.clear()
                    LATEST_TELEMETRY.update(obj)
                    PHONES_REGISTRY[station] = record
                    _save_phones_locked()
                    DATA_DIR.mkdir(parents=True, exist_ok=True)
                    with open(TELEMETRY_LOG, "a", encoding="utf-8") as fh:
                        fh.write(json.dumps(obj) + "\n")
                self._send_json({"ok": True})
            except Exception as exc:
                self._send_json({"error": str(exc)}, 400)
            return

        if parsed.path == "/api/ingest/frame":
            if length > MAX_FRAME_BYTES:
                self._send_json({"error": "Frame payload too large"}, 413)
                return
            body = self.rfile.read(length) if length > 0 else b""
            qs = urllib.parse.parse_qs(parsed.query)
            raw_name = (qs.get("filename") or [f"frame_{int(time.time())}.jpg"])[0]
            safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", os.path.basename(raw_name)).lstrip(".")
            if not safe_name:
                safe_name = f"frame_{int(time.time())}.jpg"
            FRAMES_DIR.mkdir(parents=True, exist_ok=True)
            (FRAMES_DIR / safe_name).write_bytes(body)
            self._send_json({"ok": True, "saved": safe_name, "bytes": len(body)})
            return

        self._send_json({"error": "Not found"}, 404)


def main() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    FRAMES_DIR.mkdir(parents=True, exist_ok=True)
    _load_phones()
    srv = http.server.ThreadingHTTPServer((HOST, PORT), CompanionHandler)
    print(f"PalaRez Companion Archive & Agent Hub listening on http://{HOST}:{PORT}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
