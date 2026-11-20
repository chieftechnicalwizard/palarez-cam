#!/usr/bin/env python3
"""
Standalone Phone Direct-to-YouTube Per-Track Live Album Orchestrator.

Reads an `.m3u` album playlist and per-track lyrics (from `<track_stem>.txt` sidecars
or optional structured Markdown/Python spec files), verifies YouTube metadata compliance
(zero leaked secrets, <= 100-char titles, <= 5000-char descriptions), uploads tracks
to the phone via `POST /api/audio/upload`, and sequentially drives PalaRez StreamCam
(`:8890`) directly to YouTube Live RTMPS — creating, binding, transitioning, and
completing one dedicated YouTube Live broadcast per track with zero clipped song intros.

Environment Variables (all overridable via CLI flags):
  PALAREZ_PHONE_URL         Phone HTTP base URL (e.g. http://<PHONE_IP>:8890)
  PALAREZ_M3U               Playlist path (default: ./album.m3u)
  PALAREZ_TRACKS_DIR        Audio directory for relative M3U entries (default: ./tracks)
  PALAREZ_LYRICS_MD         Optional Markdown lyrics source for tracks 1..5
  PALAREZ_LYRICS_SPEC       Optional Python literal spec source for tracks 6..11
  PALAREZ_YT_TOKEN          YouTube OAuth2 refresh-token JSON (default: ./youtube_token.json)
  PALAREZ_YT_CLIENT_SECRET  Optional Google OAuth client_secret.json fallback

Usage:
  # 1. Safe dry-run (verifies titles, descriptions, durations & lyrics; starts NOTHING):
  python3 scripts/album_live_orchestrator.py --dry-run --all
  python3 scripts/album_live_orchestrator.py --dry-run --track 10

  # 2. Stage/upload audio tracks onto phone internal storage (keeps phone IDLE):
  python3 scripts/album_live_orchestrator.py --phone http://<PHONE_IP>:8890 --upload-only --all

  # 3. Live broadcast sequence (ONLY run when explicitly intending to go live!):
  python3 scripts/album_live_orchestrator.py --phone http://<PHONE_IP>:8890 --live --track 10 --privacy unlisted
  python3 scripts/album_live_orchestrator.py --phone http://<PHONE_IP>:8890 --live --all --privacy public
"""

import argparse
import ast
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# RFC 5737 TEST-NET-1 documentation IP placeholder; override via --phone or PALAREZ_PHONE_URL
DEFAULT_PHONE_BASE = os.environ.get("PALAREZ_PHONE_URL", "http://192.0.2.10:8890")
M3U_PATH = os.environ.get("PALAREZ_M3U", "./album.m3u")
TRACKS_DIR = os.environ.get("PALAREZ_TRACKS_DIR", "./tracks")
LYRICS_1_5_MD = os.environ.get("PALAREZ_LYRICS_MD", "")
LYRICS_6_11_PY = os.environ.get("PALAREZ_LYRICS_SPEC", "")
TOKEN_FILE = os.environ.get("PALAREZ_YT_TOKEN", "./youtube_token.json")
CLIENT_SECRET_FILES = [
    p for p in [
        os.environ.get("PALAREZ_YT_CLIENT_SECRET", ""),
        "./client_secret.json",
    ] if p
]

TITLE_PREFIX = os.environ.get("PALAREZ_TITLE_PREFIX", "r/sanpedrocactus Live Sanctuary")
DEDICATION_LINE = os.environ.get(
    "PALAREZ_DEDICATION",
    "Dedicated to r/sanpedrocactusforsale, r/sanpedrocactus, r/Trichocereuspollen, and the entire Trichocereus grower community.",
)
CHANNEL_STREAMS_URL = "https://www.youtube.com/@honeywaxtreefarm/streams"
SUPPORT_BLOCK = (
    "Watch More Live Botanical & Sanctuary Streams:\n"
    f"• All Live Streams: {CHANNEL_STREAMS_URL}\n"
    "\n"
    "Support & Sanctuary Pages:\n"
    "• Honey Wax Tree Farm: https://honeywaxtreefarm.com\n"
    "• Shekinah Shine Sanctuary: https://shekinahshine.org\n"
    "• Taurian Builders: https://taurianbuilders.com\n"
    "• Dave's Bud Botanical Genetics: https://imdavesbud.shop\n"
    "• Sonoran Spores Research: https://sonoranspores.com\n"
    "• Medicactus Rare Trichocereus & SS02 Genetics: https://medicactus.com\n"
    "• East Austin Succulents: https://eastaustinsucculents.com\n"
    "• San Pedro Cactus Community: https://www.reddit.com/r/sanpedrocactus/"
)

FORBIDDEN_TERMS_RE = re.compile(
    r"\b(ai|artificial intelligence|llm|openai|gemini|claude|gpt|bot)\b",
    re.IGNORECASE,
)


def clean_lyrics_text(raw: str) -> str:
    """Strip parenthetical production cues so public descriptions stay clean & musical."""
    out_lines = []
    for line in raw.strip().splitlines():
        s = line.strip()
        if s.startswith("(") and s.endswith(")"):
            continue
        out_lines.append(s)
    text = "\n".join(out_lines)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text


def parse_tracks_1_to_5_lyrics() -> dict[int, str]:
    with open(LYRICS_1_5_MD, "r", encoding="utf-8") as f:
        text = f.read()

    results: dict[int, str] = {}
    for n in range(1, 6):
        pat = re.compile(
            rf"## Track {n}\b.*?- \*\*Lyrics & Structural Arrangement\*\*:\s*\n\s*```text\n(.*?)\n\s*```",
            re.DOTALL,
        )
        m = pat.search(text)
        if not m:
            raise ValueError(f"Could not extract lyrics for Track {n} from {LYRICS_1_5_MD}")
        raw_block = "\n".join(ln[2:] if ln.startswith("  ") else ln for ln in m.group(1).splitlines())
        results[n] = clean_lyrics_text(raw_block)
    return results


def parse_tracks_6_to_11_lyrics() -> dict[int, str]:
    with open(LYRICS_6_11_PY, "r", encoding="utf-8") as f:
        tree = ast.parse(f.read(), filename=LYRICS_6_11_PY)
    specs = None
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for tgt in node.targets:
                if isinstance(tgt, ast.Name) and tgt.id == "PSYFUNK_SPECS":
                    specs = ast.literal_eval(node.value)
                    break
    if not specs:
        raise ValueError(f"Could not locate PSYFUNK_SPECS literal in {LYRICS_6_11_PY}")
    results: dict[int, str] = {}
    for item in specs:
        results[int(item["track_num"])] = clean_lyrics_text(item["lyrics"])
    return results


def probe_duration_sec(path: str) -> float:
    out = subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
        text=True,
    ).strip()
    return float(out)


def load_album_tracks() -> list[dict]:
    lyrics_map: dict[int, str] = {}
    if LYRICS_1_5_MD and os.path.exists(LYRICS_1_5_MD):
        lyrics_map.update(parse_tracks_1_to_5_lyrics())
    if LYRICS_6_11_PY and os.path.exists(LYRICS_6_11_PY):
        lyrics_map.update(parse_tracks_6_to_11_lyrics())

    with open(M3U_PATH, "r", encoding="utf-8") as f:
        lines = [ln.strip() for ln in f if ln.strip()]

    tracks = []
    idx = 0
    pending_title = None
    for ln in lines:
        if ln.startswith("#EXTINF:"):
            _, raw_title = ln.split(",", 1)
            pending_title = raw_title.strip()
        elif not ln.startswith("#"):
            idx += 1
            filepath = ln if os.path.isabs(ln) else os.path.join(TRACKS_DIR, ln)
            if not os.path.exists(filepath):
                raise FileNotFoundError(f"Missing album track file: {filepath}")
            dur = probe_duration_sec(filepath)
            stem_txt = os.path.splitext(filepath)[0] + ".txt"
            track_lyrics = lyrics_map.get(idx, "")
            if not track_lyrics and os.path.exists(stem_txt):
                with open(stem_txt, "r", encoding="utf-8") as lf:
                    track_lyrics = clean_lyrics_text(lf.read())
            # Clean title without v1.0 clutter while preserving song title
            clean_name = re.sub(r"^\d+\.\s*", "", pending_title or os.path.basename(ln))
            clean_name = clean_name.replace(" v1.0 ", " ").replace(" v1.0", "")
            tracks.append(
                {
                    "num": idx,
                    "filename": os.path.basename(ln),
                    "filepath": filepath,
                    "m3u_title": pending_title or os.path.basename(ln),
                    "short_title": clean_name,
                    "duration_sec": dur,
                    "lyrics": track_lyrics or "(Instrumental session)",
                }
            )
            pending_title = None
    for t in tracks:
        t["total"] = len(tracks)
    return tracks


def build_track_metadata(track: dict) -> tuple[str, str]:
    n = track["num"]
    total = track.get("total", 11)
    # Keep YouTube title <= 100 characters
    # Example: "r/sanpedrocactus Live Sanctuary | Track 10/11: Super Pedro Intergalactic Groove"
    short_en = re.sub(r"\s*\([^)]*\)\s*", "", track["short_title"]).strip()
    title = f"{TITLE_PREFIX} | Track {n}/{total}: {short_en}"
    if len(title) > 100:
        title = f"{TITLE_PREFIX} | {n}/{total}: {short_en}"[:100].rstrip()

    dur_m = int(track["duration_sec"] // 60)
    dur_s = int(round(track["duration_sec"] % 60))
    if dur_s == 60:
        dur_m += 1
        dur_s = 0

    dedication_block = f"\n{DEDICATION_LINE}\n" if DEDICATION_LINE.strip() else ""
    intro = (
        f"{TITLE_PREFIX} — Track {n} of {total}: {track['short_title']} ({dur_m}:{dur_s:02d})\n"
        "\n"
        "Streaming directly from our mobile camera with live environmental HUD telemetry "
        "(temperature, barometric pressure, altitude, grow-light lux, compass heading, gravity tilt, "
        "and gyroscope stability). Built with the open-source PalaRez StreamCam app "
        "(https://github.com/chieftechnicalwizard/palarez-cam) — enjoy the live view and soundtrack session.\n"
        f"{dedication_block}"
    )
    lyrics_section = f"\nTrack {n} Lyrics — {track['short_title']}:\n\n{track['lyrics']}\n"
    desc = f"{intro}{lyrics_section}\n{SUPPORT_BLOCK}\n"

    # Guardrails: zero secrets, zero forbidden AI terms, <= 5000 chars
    if FORBIDDEN_TERMS_RE.search(title) or FORBIDDEN_TERMS_RE.search(desc):
        bad = FORBIDDEN_TERMS_RE.findall(title + "\n" + desc)
        raise ValueError(f"Track {n} metadata contains forbidden term(s): {bad}")
    if len(title) > 100:
        raise ValueError(f"Track {n} title exceeds 100 chars ({len(title)})")
    if len(desc) > 5000:
        raise ValueError(f"Track {n} description exceeds 5000 chars ({len(desc)})")
    return title, desc


def http_json(url: str, method: str = "GET", payload=None, headers: dict | None = None, timeout: int = 20):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    hdrs = dict(headers or {})
    if data is not None and "Content-Type" not in hdrs:
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ensure_track_on_phone(phone_base: str, filepath: str, filename: str) -> dict:
    expected_size = os.path.getsize(filepath)
    try:
        listing = http_json(f"{phone_base.rstrip('/')}/api/audio/tracks", timeout=10)
        for item in listing.get("tracks", []):
            if item.get("name") == filename and int(item.get("size_bytes", -1)) == expected_size:
                return {"ok": True, "cached_on_phone": True, "saved": filename, "size_bytes": expected_size}
    except Exception:
        pass
    return upload_track_to_phone(phone_base, filepath, filename)


def upload_track_to_phone(phone_base: str, filepath: str, filename: str) -> dict:
    with open(filepath, "rb") as f:
        blob = f.read()
    url = f"{phone_base.rstrip('/')}/api/audio/upload?name={urllib.parse.quote(filename)}"
    req = urllib.request.Request(
        url,
        data=blob,
        headers={"Content-Type": "audio/mpeg", "Content-Length": str(len(blob))},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_youtube_access_token() -> str:
    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        tok = json.load(f)
    client_id = tok.get("client_id")
    client_secret = tok.get("client_secret")
    if not client_id or not client_secret:
        for p in CLIENT_SECRET_FILES:
            if os.path.exists(p):
                with open(p, "r", encoding="utf-8") as cf:
                    cd = json.load(cf)
                    inner = cd.get("installed") or cd.get("web") or {}
                    client_id = client_id or inner.get("client_id")
                    client_secret = client_secret or inner.get("client_secret")
                break
    body = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "client_secret": client_secret,
            "refresh_token": tok["refresh_token"],
            "grant_type": "refresh_token",
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        tok.get("token_uri", "https://oauth2.googleapis.com/token"),
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        refreshed = json.loads(resp.read().decode("utf-8"))
    return refreshed["access_token"]


def yt_api(method: str, endpoint: str, token: str, payload=None):
    url = f"https://www.googleapis.com/youtube/v3/{endpoint}"
    return http_json(url, method=method, payload=payload, headers={"Authorization": f"Bearer {token}"})


def get_or_create_phone_stream(token: str) -> tuple[str, str]:
    existing = yt_api("GET", "liveStreams?part=id,snippet,cdn&mine=true&maxResults=50", token)
    for item in existing.get("items", []):
        if item.get("snippet", {}).get("title") == "PalaRez Mobile Phone Direct Ingest":
            ing = item["cdn"]["ingestionInfo"]
            return item["id"], f"{ing['ingestionAddress'].rstrip('/')}/{ing['streamName']}"
    created = yt_api(
        "POST",
        "liveStreams?part=snippet,cdn,contentDetails",
        token,
        {
            "snippet": {
                "title": "PalaRez Mobile Phone Direct Ingest",
                "description": "Dedicated 1080p15 mobile sanctuary camera ingest stream",
            },
            "cdn": {
                "frameRate": "variable",
                "ingestionType": "rtmp",
                "resolution": "variable",
            },
            "contentDetails": {"isReusable": True},
        },
    )
    ing = created["cdn"]["ingestionInfo"]
    return created["id"], f"{ing['ingestionAddress'].rstrip('/')}/{ing['streamName']}"


def create_and_bind_broadcast(token: str, stream_id: str, title: str, description: str, privacy: str) -> str:
    start_iso = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=5)).strftime("%Y-%m-%dT%H:%M:%SZ")
    b = yt_api(
        "POST",
        "liveBroadcasts?part=snippet,status,contentDetails",
        token,
        {
            "snippet": {
                "title": title,
                "description": description,
                "scheduledStartTime": start_iso,
            },
            "status": {
                "privacyStatus": privacy,
                "selfDeclaredMadeForKids": False,
            },
            "contentDetails": {
                "enableAutoStart": False,
                "enableAutoStop": False,
                "enableDvr": True,
                "latencyPreference": "normal",
                "monitorStream": {"enableMonitorStream": False},
            },
        },
    )
    bid = b["id"]
    yt_api("POST", f"liveBroadcasts/bind?id={bid}&streamId={stream_id}&part=id,contentDetails", token, {})
    return bid


def wait_stream_active_and_go_live(token: str, stream_id: str, broadcast_id: str, timeout_s: int = 90):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        res = yt_api("GET", f"liveStreams?part=status&id={stream_id}", token)
        items = res.get("items") or []
        status = items[0]["status"]["streamStatus"] if items else "unknown"
        if status == "active":
            break
        time.sleep(3)
    else:
        raise TimeoutError(f"YouTube liveStream {stream_id} did not become active within {timeout_s}s")

    for target_state in ("testing", "live"):
        try:
            yt_api(
                "POST",
                f"liveBroadcasts/transition?broadcastStatus={target_state}&id={broadcast_id}&part=status",
                token,
                {},
            )
            time.sleep(4)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            if target_state == "testing" and "redundantTransition" in body:
                continue
            if target_state == "testing":
                # Some monitorless streams jump straight from ready -> live
                continue
            raise RuntimeError(f"Failed transition to {target_state}: {body}")


def complete_broadcast(token: str, broadcast_id: str):
    yt_api("POST", f"liveBroadcasts/transition?broadcastStatus=complete&id={broadcast_id}&part=status", token, {})


def run_live_sequence(
    phone_base: str,
    selected: list[dict],
    privacy: str,
    mic_enabled: bool,
    mic_gain: float,
    music_gain: float,
    preset: str,
    video_kbps: int | None,
    audio_kbps: int | None,
):
    phone_base = phone_base.rstrip("/")
    token = get_youtube_access_token()
    stream_id, rtmp_url = get_or_create_phone_stream(token)
    print(f"[+] Using YouTube liveStream ID={stream_id}")

    # Stage audio & video baseline on phone (no stream started yet)
    ctrl_payload = {
        "preset": preset,
        "hud_mode": "sanctuary_card",
        "audio_enabled": mic_enabled,
        "mic_gain": mic_gain,
        "music_gain": music_gain,
        "music_loop": False,
        "rtmp_url": rtmp_url,
        "arm": True,
    }
    if video_kbps is not None:
        ctrl_payload["video_bitrate_kbps"] = video_kbps
    if audio_kbps is not None:
        ctrl_payload["audio_bitrate_kbps"] = audio_kbps
    http_json(f"{phone_base}/api/control", method="POST", payload=ctrl_payload)

    # Validate non-publishing RTMPS handshake before going live
    val = http_json(f"{phone_base}/api/rtmp/validate", method="POST", payload={"level": "handshake"})
    if not val.get("ok"):
        raise RuntimeError(f"Phone RTMP handshake precheck failed: {val}")

    for idx, track in enumerate(selected, 1):
        title, desc = build_track_metadata(track)
        print(f"\n=== [{idx}/{len(selected)}] Track {track['num']}: {title} ({track['duration_sec']:.2f}s) ===", flush=True)
        up = ensure_track_on_phone(phone_base, track["filepath"], track["filename"])
        if not up.get("ok"):
            raise RuntimeError(f"Track upload failed: {up}")

        # Refresh token for each track in case sequence exceeds ~50 min
        token = get_youtube_access_token()
        bid = create_and_bind_broadcast(token, stream_id, title, desc, privacy)
        watch_url = f"https://www.youtube.com/watch?v={bid}"
        print(f"[+] Created & bound broadcast {bid} ({watch_url})", flush=True)

        try:
            # Start RTMPS stream first so YouTube ready->testing->live completes before 0:00 of the track
            http_json(
                f"{phone_base}/api/control",
                method="POST",
                payload={
                    "music_track": track["filename"],
                    "music_playing": False,
                    "music_seek_start": True,
                    "start_stream": True,
                },
            )
            wait_stream_active_and_go_live(token, stream_id, bid)

            # Now LIVE on YouTube: start track cleanly from 0:00
            http_json(
                f"{phone_base}/api/control",
                method="POST",
                payload={"music_playing": True, "music_seek_start": True},
            )
            print(f"[LIVE] {watch_url} — playing Track {track['num']} cleanly from 0:00...", flush=True)

            t_start = time.time()
            t_end_max = t_start + track["duration_sec"] + 15.0
            last_log = 0.0
            while time.time() < t_end_max:
                st = http_json(f"{phone_base}/api/status")
                mus = st.get("music", {})
                now = time.time()
                if now - last_log >= 15.0:
                    last_log = now
                    print(
                        f"  [Track {track['num']:02d}] state={st.get('lifecycle_state')} "
                        f"pos={mus.get('position_sec', 0):.1f}/{track['duration_sec']:.1f}s "
                        f"vid={st.get('video_format')}",
                        flush=True,
                    )
                if mus.get("finished") or (not mus.get("playing") and mus.get("position_sec", 0) > 5):
                    break
                time.sleep(2.0)

            # Allow encoder/YouTube flush tail before completing
            time.sleep(3.0)
            token = get_youtube_access_token()
            complete_broadcast(token, bid)
            print(f"[DONE] Completed {watch_url}", flush=True)
        finally:
            try:
                http_json(f"{phone_base}/api/control", method="POST", payload={"stop_stream": True, "music_stop": True})
            except Exception:
                pass
        if idx < len(selected):
            time.sleep(6.0)


def main():
    global M3U_PATH, TRACKS_DIR, LYRICS_1_5_MD, LYRICS_6_11_PY, TOKEN_FILE
    global TITLE_PREFIX, DEDICATION_LINE, SUPPORT_BLOCK
    ap = argparse.ArgumentParser(description="Standalone Phone Per-Track YouTube Live Album Orchestrator")
    ap.add_argument("--phone", default=DEFAULT_PHONE_BASE, help="Phone HTTP base URL")
    ap.add_argument("--track", type=int, action="append", help="Track number(s) 1..11 to include")
    ap.add_argument("--all", action="store_true", help="Include all 11 album tracks in order")
    ap.add_argument("--dry-run", action="store_true", help="Print titles/descriptions/checks only; touch nothing")
    ap.add_argument("--upload-only", action="store_true", help="Upload selected tracks to phone and stay IDLE")
    ap.add_argument("--live", action="store_true", help="Execute live YouTube broadcast sequence from phone")
    ap.add_argument("--privacy", default="unlisted", choices=["unlisted", "public", "private"])
    ap.add_argument("--mic", action="store_true", help="Keep ambient greenhouse mic mixed softly under music")
    ap.add_argument("--mic-gain", type=float, default=0.20)
    ap.add_argument("--music-gain", type=float, default=0.90)
    ap.add_argument("--preset", default="sanctuary_eco_1080p15_800k")
    ap.add_argument("--video-kbps", type=int, default=None)
    ap.add_argument("--audio-kbps", type=int, default=None)
    ap.add_argument("--m3u", default=M3U_PATH, help="Path to album .m3u playlist")
    ap.add_argument("--tracks-dir", default=TRACKS_DIR, help="Directory holding album MP3/WAV files")
    ap.add_argument("--lyrics-md", default=LYRICS_1_5_MD)
    ap.add_argument("--lyrics-spec", default=LYRICS_6_11_PY)
    ap.add_argument("--token-file", default=TOKEN_FILE)
    ap.add_argument("--client-secret", default="")
    ap.add_argument("--title-prefix", default=TITLE_PREFIX, help="Broadcast title prefix (default: %(default)s)")
    ap.add_argument("--dedication", default=DEDICATION_LINE, help="Optional dedication line (pass '' to omit)")
    ap.add_argument("--support-file", default="", help="Path to custom support/links footer text file (e.g. examples/support_links.txt)")
    args = ap.parse_args()

    M3U_PATH = args.m3u
    TRACKS_DIR = args.tracks_dir
    LYRICS_1_5_MD = args.lyrics_md
    LYRICS_6_11_PY = args.lyrics_spec
    TOKEN_FILE = args.token_file
    TITLE_PREFIX = args.title_prefix
    DEDICATION_LINE = args.dedication
    if args.support_file:
        with open(args.support_file, "r", encoding="utf-8") as sf:
            SUPPORT_BLOCK = sf.read().strip()
    if args.client_secret:
        CLIENT_SECRET_FILES.insert(0, args.client_secret)

    tracks = load_album_tracks()
    if args.all:
        selected = tracks
    elif args.track:
        want = set(args.track)
        selected = [t for t in tracks if t["num"] in want]
    else:
        ap.error("Specify --all or --track N")

    if args.dry_run or (not args.upload_only and not args.live):
        total_sec = sum(t["duration_sec"] for t in selected)
        print(f"Verified {len(selected)} track(s), combined duration: {total_sec:.1f}s ({total_sec/60:.1f} min)")
        for t in selected:
            title, desc = build_track_metadata(t)
            print("-" * 72)
            print(f"Track {t['num']:02d} | {t['filename']} | {t['duration_sec']:.2f}s | Title({len(title)}c) | Desc({len(desc)}c)")
            print(f"TITLE: {title}")
            if len(selected) == 1:
                print("\nDESCRIPTION:\n" + desc)
        return

    if args.upload_only:
        for t in selected:
            res = upload_track_to_phone(args.phone, t["filepath"], t["filename"])
            print(f"Uploaded Track {t['num']:02d} -> ok={res.get('ok')} ({t['filename']})")
        return

    if args.live:
        run_live_sequence(
            args.phone,
            selected,
            args.privacy,
            args.mic,
            args.mic_gain,
            args.music_gain,
            args.preset,
            args.video_kbps,
            args.audio_kbps,
        )


if __name__ == "__main__":
    main()
