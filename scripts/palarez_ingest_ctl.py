#!/usr/bin/env python3
"""
palarez_ingest_ctl.py — Operator & Automation CLI for Discovering, Inspecting, Staging,
and Validating PalaRez StreamCam Phones (WITHOUT Accidentally Starting a Live Broadcast).

Subcommands:
  - `discover`      : Probes mDNS (`avahi-browse -rpt _palarez-cam._tcp`), Companion Hub
                      (`GET :8895/api/phones`), and/or explicit `--ip` targets.
  - `inspect`       : Fetches `/api/status`, `/api/capabilities`, and `/api/telemetry`
                      (pass `--raw` to append raw sensor vectors).
  - `stage-hub`     : Configures the phone for external hub pull (`hub_mezzanine_1440p15_4000k`
                      + `clean` HUD by default), verifies H.264/AAC streams via `ffprobe`, grabs a
                      verification JPEG, and optionally registers `/stream.flv` in `go2rtc`.
  - `stage-youtube` : Reads the YouTube stream key from a gitignored `.env` file (so keys never
                      appear in `ps` / shell history), applies preset + HUD, sets `ARMED_READY`,
                      and executes non-publishing `POST /api/rtmp/validate` handshake check.
  - `validate`      : Runs standalone `POST /api/rtmp/validate` DNS/TCP/TLS/RTMP connect check.
  - `go-live`       : Starts outbound RTMP/RTMPS broadcast ONLY when `--yes-go-live` is passed.
  - `stop`          : Stops an active outbound RTMP/RTMPS broadcast.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_SECRETS_ENV = os.environ.get("PALAREZ_SECRETS_ENV", ".env")


def http_json(url: str, method: str = "GET", payload: dict[str, Any] | None = None, timeout: float = 8.0) -> Any:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if payload is not None else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_bytes(url: str, timeout: float = 8.0) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.read()


def load_secret_var(env_path: str, var_name: str) -> str:
    p = Path(env_path)
    if not p.is_file():
        raise FileNotFoundError(f"Secrets file not found: {env_path}")
    for line in p.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        if k.strip() == var_name:
            return v.strip().strip('"').strip("'")
    raise KeyError(f"{var_name} not found in {env_path}")


def discover_mdns() -> list[dict[str, Any]]:
    avahi = shutil.which("avahi-browse")
    if not avahi:
        return []
    try:
        out = subprocess.run([avahi, "-rpt", "_palarez-cam._tcp"], capture_output=True, text=True, timeout=4)
    except Exception:
        return []
    found: dict[str, dict[str, Any]] = {}
    for line in out.stdout.splitlines():
        if not line.startswith("="):
            continue
        parts = line.split(";")
        if len(parts) < 9 or parts[2] != "IPv4":
            continue
        try:
            name, ip, port = parts[3], parts[7], int(parts[8])
        except ValueError:
            continue
        found[f"{ip}:{port}"] = {"station": name, "ipv4": ip, "port": port, "source": "mdns"}
    return list(found.values())


def probe_phone(ip: str, port: int = 8890) -> dict[str, Any] | None:
    base = f"http://{ip}:{port}"
    try:
        st = http_json(f"{base}/api/status", timeout=2.5)
        caps = http_json(f"{base}/api/capabilities", timeout=2.5)
        return {"http_base": base, "status": st, "capabilities": caps}
    except Exception:
        return None


def cmd_discover(args: argparse.Namespace) -> int:
    candidates: list[tuple[str, int]] = []
    for item in discover_mdns():
        candidates.append((item["ipv4"], item["port"]))
    if args.hub:
        try:
            reg = http_json(args.hub.rstrip("/") + "/api/phones", timeout=2.5)
            for p in reg.get("phones", []):
                if p.get("ipv4"):
                    candidates.append((p["ipv4"], 8890))
        except Exception:
            pass
    for ip in args.ip or []:
        candidates.append((ip, 8890))

    seen = set()
    phones = []
    for ip, port in candidates:
        key = f"{ip}:{port}"
        if key in seen:
            continue
        seen.add(key)
        info = probe_phone(ip, port)
        if info:
            phones.append(info)
    print(json.dumps({"discovered_phones": phones}, indent=2))
    return 0


def cmd_inspect(args: argparse.Namespace) -> int:
    base = f"http://{args.phone}:{args.port}"
    q = "?raw_sensors=1" if args.raw else ""
    out = {
        "status": http_json(f"{base}/api/status"),
        "capabilities": http_json(f"{base}/api/capabilities"),
        "telemetry": http_json(f"{base}/api/telemetry{q}"),
    }
    print(json.dumps(out, indent=2))
    return 0


def cmd_stage_hub(args: argparse.Namespace) -> int:
    base = f"http://{args.phone}:{args.port}"
    ctrl: dict[str, Any] = {
        "preset": args.preset,
        "hud_mode": args.hud_mode,
        "hud_enabled": args.hud_mode != "clean",
    }
    if args.camera is not None:
        ctrl["camera_id"] = str(args.camera)
    if args.station:
        ctrl["station_name"] = args.station

    ctrl_res = http_json(f"{base}/api/control", method="POST", payload=ctrl, timeout=10.0)
    if ctrl_res.get("restart_performed"):
        import time as _time
        _time.sleep(2.0)
    flv_url = f"{base}/stream.flv" + ("?clean=1" if args.hud_mode == "clean" else "")

    ffprobe_info = None
    if shutil.which("ffprobe"):
        p = subprocess.run(
            ["ffprobe", "-v", "error", "-analyzeduration", "2500000", "-probesize", "1000000",
             "-show_entries", "stream=codec_name,codec_type,width,height,r_frame_rate,sample_rate", "-of", "json", flv_url],
            capture_output=True, text=True, timeout=15
        )
        if p.returncode == 0:
            ffprobe_info = json.loads(p.stdout)

    snap_bytes = 0
    if args.verify_frame:
        snap_q = "?clean=1" if args.hud_mode == "clean" else ""
        jpg = http_bytes(f"{base}/snapshot.jpg{snap_q}", timeout=6.0)
        Path(args.verify_frame).write_bytes(jpg)
        snap_bytes = len(jpg)

    go2rtc_registered = False
    if args.go2rtc:
        g_src = f"ffmpeg:{flv_url}#video=copy#audio=copy"
        g_url = args.go2rtc.rstrip("/") + "/api/streams?" + urllib.parse.urlencode({"name": args.name, "src": g_src})
        req = urllib.request.Request(g_url, method="PUT")
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            go2rtc_registered = 200 <= resp.status < 300

    print(json.dumps({
        "mode": "hub_managed",
        "youtube_live_started": False,
        "flv_ingest_url": flv_url,
        "go2rtc_stream_name": args.name if args.go2rtc else None,
        "go2rtc_registered": go2rtc_registered,
        "ffprobe_streams": (ffprobe_info or {}).get("streams", []),
        "verify_frame_path": args.verify_frame if snap_bytes else None,
        "verify_frame_bytes": snap_bytes,
        "status": ctrl_res.get("status"),
    }, indent=2))
    return 0


def cmd_stage_youtube(args: argparse.Namespace) -> int:
    base = f"http://{args.phone}:{args.port}"
    ctrl: dict[str, Any] = {
        "preset": args.preset,
        "hud_mode": args.hud_mode,
        "hud_enabled": args.hud_mode != "clean",
        "arm": True,
    }
    if args.camera is not None:
        ctrl["camera_id"] = str(args.camera)
    if args.station:
        ctrl["station_name"] = args.station

    if args.rtmp_url:
        ctrl["rtmp_url"] = args.rtmp_url
    elif args.secret_var:
        key = load_secret_var(args.secrets_env, args.secret_var)
        ctrl["rtmp_server"] = args.rtmp_server
        ctrl["stream_key"] = key

    ctrl_res = http_json(f"{base}/api/control", method="POST", payload=ctrl, timeout=10.0)
    val_res = None
    if not args.skip_validate:
        val_res = http_json(f"{base}/api/rtmp/validate", method="POST", payload={"level": "handshake"}, timeout=12.0)

    print(json.dumps({
        "mode": "direct_youtube",
        "youtube_live_started": False,
        "armed_ready": ctrl_res.get("status", {}).get("lifecycle_state") == "ARMED_READY",
        "status": ctrl_res.get("status"),
        "dry_run_validation": val_res,
    }, indent=2))
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    base = f"http://{args.phone}:{args.port}"
    payload = {"rtmp_url": args.rtmp_url} if args.rtmp_url else {"level": "handshake"}
    res = http_json(f"{base}/api/rtmp/validate", method="POST", payload=payload, timeout=12.0)
    print(json.dumps(res, indent=2))
    return 0 if res.get("ok") else 1


def cmd_go_live(args: argparse.Namespace) -> int:
    if not args.yes_go_live:
        print("Refusing to start live broadcast without explicit --yes-go-live flag.", file=sys.stderr)
        return 2
    base = f"http://{args.phone}:{args.port}"
    st = http_json(f"{base}/api/status")
    if not st.get("ready_for_direct_youtube"):
        print(json.dumps({"error": "Phone not ready for direct YouTube broadcast", "status": st}, indent=2), file=sys.stderr)
        return 1
    res = http_json(f"{base}/api/control", method="POST", payload={"start_stream": True})
    print(json.dumps(res, indent=2))
    return 0


def cmd_stop(args: argparse.Namespace) -> int:
    base = f"http://{args.phone}:{args.port}"
    res = http_json(f"{base}/api/control", method="POST", payload={"stop_stream": True})
    print(json.dumps(res, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="PalaRez StreamCam Agent Ingest Control CLI")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_disc = sub.add_parser("discover", help="Find phones via mDNS / companion hub / direct IPs")
    p_disc.add_argument("--hub", default="http://127.0.0.1:8895", help="Companion hub URL")
    p_disc.add_argument("--ip", action="append", help="Direct phone IP(s) to probe")
    p_disc.set_defaults(func=cmd_discover)

    p_insp = sub.add_parser("inspect", help="Inspect phone status, capabilities & 9 priority sensors")
    p_insp.add_argument("--phone", required=True)
    p_insp.add_argument("--port", type=int, default=8890)
    p_insp.add_argument("--raw", action="store_true")
    p_insp.set_defaults(func=cmd_inspect)

    p_hub = sub.add_parser("stage-hub", help="Stage phone FLV ingest for go2rtc/ffmpeg WITHOUT going live")
    p_hub.add_argument("--phone", required=True)
    p_hub.add_argument("--port", type=int, default=8890)
    p_hub.add_argument("--name", default="palarez_cam1", help="go2rtc stream name")
    p_hub.add_argument("--go2rtc", default="", help="Optional go2rtc HTTP URL (e.g. http://127.0.0.1:1984)")
    p_hub.add_argument("--camera", default=None, help="Lens ID (0=Main, 1=Front, 2=Macro)")
    p_hub.add_argument("--preset", default="hub_mezzanine_1440p15_4000k")
    p_hub.add_argument("--hud-mode", default="clean", choices=["clean", "minimal_pill", "sanctuary_card"])
    p_hub.add_argument("--station", default="")
    p_hub.add_argument("--verify-frame", default="/tmp/palarez_stage_verify.jpg")
    p_hub.set_defaults(func=cmd_stage_hub)

    p_yt = sub.add_parser("stage-youtube", help="Stage direct YouTube RTMPS + dry-run validate WITHOUT going live")
    p_yt.add_argument("--phone", required=True)
    p_yt.add_argument("--port", type=int, default=8890)
    p_yt.add_argument("--camera", default=None)
    p_yt.add_argument("--preset", default="sanctuary_eco_1080p15_800k")
    p_yt.add_argument("--hud-mode", default="sanctuary_card", choices=["sanctuary_card", "minimal_pill", "clean"])
    p_yt.add_argument("--station", default="")
    p_yt.add_argument("--rtmp-server", default="rtmps://a.rtmps.youtube.com/live2")
    p_yt.add_argument("--rtmp-url", default="")
    p_yt.add_argument("--secrets-env", default=DEFAULT_SECRETS_ENV)
    p_yt.add_argument("--secret-var", default="", help="Env var key name in --secrets-env file")
    p_yt.add_argument("--skip-validate", action="store_true")
    p_yt.set_defaults(func=cmd_stage_youtube)

    p_val = sub.add_parser("validate", help="Run non-publishing RTMP handshake validation")
    p_val.add_argument("--phone", required=True)
    p_val.add_argument("--port", type=int, default=8890)
    p_val.add_argument("--rtmp-url", default="")
    p_val.set_defaults(func=cmd_validate)

    p_live = sub.add_parser("go-live", help="Start direct RTMP broadcast (requires --yes-go-live)")
    p_live.add_argument("--phone", required=True)
    p_live.add_argument("--port", type=int, default=8890)
    p_live.add_argument("--yes-go-live", action="store_true")
    p_live.set_defaults(func=cmd_go_live)

    p_stop = sub.add_parser("stop", help="Stop active RTMP broadcast")
    p_stop.add_argument("--phone", required=True)
    p_stop.add_argument("--port", type=int, default=8890)
    p_stop.set_defaults(func=cmd_stop)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
