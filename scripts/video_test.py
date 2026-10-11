"""SpineCare video test harness.

Takes an exercise video (downloaded from YouTube/NHS/CSP sources), extracts
frames with ffmpeg, streams them through the live SpineCare GPU server over the
same WebSocket protocol the mobile app uses, and produces evidence:
  - annotated overlay frames (skeleton + metrics + cues) -> screenshots
  - an evidence video stitched from the annotated frames
  - a JSON metrics log per frame

Usage: python video_test.py <video.mp4> <exercise_id> <out_dir> [server_uri]
"""
import asyncio, base64, json, os, subprocess, sys, time
from pathlib import Path

import websockets  # noqa: F401
from ws_auth import connect
from PIL import Image, ImageDraw, ImageFont

MAX_FRAMES = 900  # cap for long videos


def extract_frames(video: str, out_dir: Path, fps: float = 6.0) -> list[Path]:
    frames_dir = out_dir / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", video,
         "-vf", f"fps={fps},scale=640:-2", str(frames_dir / "f_%05d.jpg")],
        check=True)
    return sorted(frames_dir.glob("f_*.jpg"))[:MAX_FRAMES]


def load_font(size=16):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            pass
    return ImageFont.load_default()


CONNECTIONS = [("lsho", "rsho"), ("lsho", "lelb"), ("lelb", "lwri"), ("rsho", "relb"),
               ("relb", "rwri"), ("lsho", "lhip"), ("rsho", "rhip"), ("lhip", "rhip"),
               ("lhip", "lknee"), ("lknee", "lank"), ("rhip", "rknee"), ("rknee", "rank"),
               ("nose", "lear"), ("nose", "rear")]  # by name: the server replies with named points


def annotate(img_path: Path, pose: dict, out_path: Path, idx: int, total: int):
    img = Image.open(img_path).convert("RGB")
    d = ImageDraw.Draw(img)
    W, H = img.size
    # server returns pixel coords of the frame it was sent
    lm = {p["name"]: p for p in (pose.get("landmarks") or []) if "name" in p}
    for a, b in CONNECTIONS:
        pa, pb = lm.get(a), lm.get(b)
        if pa and pb and pa.get("visibility", 0) > 0.4 and pb.get("visibility", 0) > 0.4:
            d.line([(pa["x"], pa["y"]), (pb["x"], pb["y"])], fill=(0, 255, 140), width=3)
    for p in lm.values():
        if p.get("visibility", 0) > 0.4:
            x, y = p["x"], p["y"]
            d.ellipse([x - 4, y - 4, x + 4, y + 4], fill=(255, 80, 80))
    font = load_font(15)
    m = pose.get("metrics", {})
    cues = pose.get("cues", [])
    framing = pose.get("framing", {})
    panel_h = 118
    d.rectangle([0, H - panel_h, W, H], fill=(10, 18, 28))
    lines = [
        f"SpineCare  frame {idx}/{total}  latency {pose.get('latency_ms', 0):.0f}ms",
        "  ".join(f"{k}={v:.1f}" for k, v in list(m.items())[:6] if v is not None),
        f"framing: {'ready' if framing.get('ready') else 'adjust ' + ','.join(framing.get('missing', []))}  reps: {pose.get('reps', 0)}  phase: {pose.get('phase', '-')}",
        "cues: " + ("; ".join(c.get("cue", str(c)) for c in cues[:3]) if cues else "none"),
    ]
    y = H - panel_h + 8
    for ln in lines:
        d.text((10, y), ln, fill=(230, 240, 250), font=font)
        y += 26
    img.save(out_path, quality=88)


async def run(video: str, exercise_id: str, out_dir: Path,
              uri: str = "ws://127.0.0.1:8300/ws/session"):
    out_dir.mkdir(parents=True, exist_ok=True)
    frames = extract_frames(video, out_dir)
    print(f"frames extracted: {len(frames)}")
    ann_dir = out_dir / "annotated"
    ann_dir.mkdir(exist_ok=True)
    log = []
    async with connect(uri, max_size=8 * 1024 * 1024) as ws:
        await ws.send(json.dumps({"type": "session", "exercise_id": exercise_id}))
        msg = json.loads(await ws.recv())
        assert msg["type"] == "session_started", msg
        print("session:", msg["session_id"], "exercise:", msg["exercise"]["name"])
        lat = []
        for i, fp in enumerate(frames):
            b64 = base64.b64encode(fp.read_bytes()).decode()
            t0 = time.time()
            # video timestamp (ms) so server-side rep timing matches playback speed
            vid_t = int(i / 6.0 * 1000)
            await ws.send(json.dumps({"type": "frame", "t": vid_t,
                                      "img": b64, "w": 0, "h": 0}))
            reply = json.loads(await ws.recv())
            if reply.get("type") != "pose":
                print("skip frame", i, reply.get("error", reply.get("type")))
                continue
            lat.append(reply["latency_ms"])
            log.append({"frame": i, "metrics": reply.get("metrics"),
                        "signal": reply.get("signal"),
                        "reps": reply.get("reps"), "phase": reply.get("phase"),
                        "cues": reply.get("cues"), "framing": reply.get("framing"),
                        "latency_ms": reply["latency_ms"]})
            annotate(fp, reply, ann_dir / f"a_{i:05d}.jpg", i, len(frames))
        await ws.send(json.dumps({"type": "end_session"}))
        saved = json.loads(await ws.recv())
    (out_dir / "metrics_log.json").write_text(json.dumps(
        {"exercise": exercise_id, "session": msg["session_id"],
         "frames": len(log), "latency_p50_ms": sorted(lat)[len(lat) // 2] if lat else 0,
         "latency_mean_ms": sum(lat) / len(lat) if lat else 0,
         "final_reps": saved.get("reps"), "avg_metrics": saved.get("avg_metrics"),
         "log": log}, indent=1))
    print(f"processed {len(log)} frames, mean latency {sum(lat)/len(lat):.0f}ms" if lat else "no pose frames")
    print("session saved:", saved.get("session_id"), "reps:", saved.get("reps"))
    # stitch evidence video
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", "12",
                    "-i", str(ann_dir / "a_%05d.jpg"),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    str(out_dir / "evidence.mp4")], check=True)
    print("evidence video:", out_dir / "evidence.mp4")
    return out_dir


if __name__ == "__main__":
    video, ex, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    uri = sys.argv[4] if len(sys.argv) > 4 else "ws://127.0.0.1:8300/ws/session"
    asyncio.run(run(video, ex, out, uri))
