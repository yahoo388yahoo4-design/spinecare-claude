"""Turns the CI video-replay outputs into reviewable evidence.

For every pipeline output folder (<pipeline>/<clip id>/frames/f_*.jpg) it stitches
the annotated frames into overlay.mp4 (real-time, at the clip fps), builds a
contact sheet (contact.jpg) and removes the loose frames; then it writes
REPORT.md comparing the pipelines per clip, with each clip's source and license.

Usage: python scripts/video_evidence_report.py <evidence_dir>
  <evidence_dir>/sources.json            from fetch_demo_videos.py
  <evidence_dir>/android/summary.json    from VideoReplayTest (emulator)
  <evidence_dir>/ios/summary.json        from scripts/ios_replay (macOS, Apple Vision)
  <evidence_dir>/server/<id>/metrics_log.json  from scripts/video_test.py
Any pipeline folder may be missing; the report says so instead of inventing numbers.
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image


def stitch(frames_dir: Path, out: Path, fps: int):
    files = sorted(frames_dir.glob("*.jpg"))
    if not files:
        return 0
    # frames may skip indices (undecodable frames): use a concat list, not a numbered pattern
    lst = frames_dir / "list.txt"
    lst.write_text("".join(f"file '{f.name}'\nduration {1 / fps:.4f}\n" for f in files))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(lst),
                    "-vf", f"fps={fps},scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", str(out)], check=True)
    return len(files)


def contact_sheet(frames_dir: Path, out: Path, n: int = 6, cols: int = 3, width: int = 360):
    files = sorted(frames_dir.glob("*.jpg"))
    if not files:
        return
    pick = [files[int(i * (len(files) - 1) / max(n - 1, 1))] for i in range(min(n, len(files)))]
    thumbs = []
    for f in pick:
        im = Image.open(f).convert("RGB")
        thumbs.append(im.resize((width, int(im.height * width / im.width))))
    th = max(t.height for t in thumbs)
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * width, rows * th), (11, 18, 32))
    for i, t in enumerate(thumbs):
        sheet.paste(t, ((i % cols) * width, (i // cols) * th))
    sheet.save(out, quality=85)


def finish_pipeline(root: Path, fps: int):
    if not root.is_dir():
        return
    for clip in sorted(p for p in root.iterdir() if p.is_dir()):
        # server runs keep raw frames in frames/ and the overlay in annotated/
        src = clip / "annotated" if (clip / "annotated").is_dir() else clip / "frames"
        if src.is_dir():
            stitch(src, clip / "overlay.mp4", fps)
            contact_sheet(src, clip / "contact.jpg")
        for d in ("frames", "annotated"):
            shutil.rmtree(clip / d, ignore_errors=True)
        (clip / "evidence.mp4").unlink(missing_ok=True)  # video_test.py's 2x-speed copy of overlay.mp4


def load(p: Path):
    try:
        return json.loads(p.read_text())
    except Exception:
        return None


def server_summary(root: Path) -> dict:
    out = {}
    if not root.is_dir():
        return out
    for clip in root.iterdir():
        m = load(clip / "metrics_log.json")
        if not m:
            continue
        log = m.get("log", [])
        n = len(log) or 1
        det = sum(1 for r in log if "no_person" not in (r.get("framing") or {}).get("issues", []))
        ready = sum(1 for r in log if (r.get("framing") or {}).get("ready"))
        out[clip.name] = {"frames": len(log), "detection_rate": det / n, "ready_rate": ready / n,
                          "reps": m.get("final_reps"), "latency_p50_ms": m.get("latency_p50_ms")}
    return out


def pct(v):
    return "–" if v is None else f"{100 * v:.0f}%"


def main():
    ev = Path(sys.argv[1])
    sources = load(ev / "sources.json") or {"videos": []}
    fps = int(sources.get("fps", 6))
    for name in ("android", "ios", "server"):
        finish_pipeline(ev / name, fps)

    android = {v["id"]: v for v in (load(ev / "android" / "summary.json") or {}).get("videos", [])}
    ios = {v["id"]: v for v in (load(ev / "ios" / "summary.json") or {}).get("videos", [])}
    server = server_summary(ev / "server")
    adevice = (load(ev / "android" / "summary.json") or {}).get("device", "not run")
    idevice = (load(ev / "ios" / "summary.json") or {}).get("device", "not run")

    L = ["# Demo video replay evidence", "",
         "Each clip below was downloaded at CI time and replayed frame by frame through three detection paths:",
         f"- **Android app (emulator)**: `VideoReplayTest` on {adevice}: MediaPipe PoseEngine, Metrics, Framing, "
         "ExerciseTracker, OverlayRenderer and the app's SessionRecorder.",
         f"- **iOS app engine**: `scripts/ios_replay` on {idevice}: the app's PoseTracker (Apple Vision), Metrics, "
         "Framing and ExerciseTracker compiled for macOS. Overlay is a CoreGraphics stand-in, not the UIKit renderer.",
         "- **Server**: `scripts/video_test.py` over the app's WebSocket protocol, YOLOv8n-pose on CPU.",
         "",
         "Rep counts are what each pipeline reported on third-party footage, not clinical validation; "
         "the clip-to-exercise mapping is ours.", "",
         "| clip | exercise | Android detect / ready / reps | iOS engine detect / ready / reps | server detect / ready / reps | Android p50 ms | recorder frames |",
         "|---|---|---|---|---|---|---|"]
    for v in sources["videos"]:
        a, i, s = android.get(v["id"]), ios.get(v["id"]), server.get(v["id"])
        fmt = lambda r: "not run" if not r else f"{pct(r.get('detection_rate'))} / {pct(r.get('ready_rate'))} / {r.get('reps')}"  # noqa: E731
        L.append(f"| {v['id']} | {v['exercise_id']} | {fmt(a)} | {fmt(i)} | {fmt(s)} | "
                 f"{a.get('latency_p50_ms') if a else '–'} | {a.get('recorder_frames') if a else '–'} |")
    L += ["", "## Sources", "", "| clip | spine-care relevance | file | author | license | length used |", "|---|---|---|---|---|---|"]
    cell = lambda t: " ".join(str(t).split()).replace("|", "/")[:90]  # noqa: E731  one table cell
    for v in sources["videos"]:
        L.append(f"| {v['id']} | {cell(v.get('relevance') or '–')} | [{v['title']}]({v['page']}) | {cell(v.get('author') or v.get('credit') or '–')} | "
                 f"{v['license']} | {v.get('frames', 0) / fps:.0f} s of {v.get('duration', 0):.0f} s |")
    L += ["", "Files per clip: `android/<id>/overlay.mp4` (annotated frames from the app's OverlayRenderer), "
          "`android/<id>/app_recorder.mp4` (the app's own SessionRecorder), `ios/<id>/overlay.mp4`, "
          "`server/<id>/overlay.mp4`, a `contact.jpg` sheet and a per-frame `log.json` / `metrics_log.json` for each."]
    in_app = load(ev / "android" / "in_app" / "summary.json")
    if in_app:
        L += ["", "## In the app's \"Analyze a video\" screen (emulator)", "",
              "The same clips opened in VideoAnalysisActivity as a user would; `android/in_app/screenrecord.mp4` "
              "records the screen and `android/in_app/<id>/screen_*.png` are screenshots taken while it ran.", "",
              "| clip | exercise | finished | seconds | screenshots |", "|---|---|---|---|---|"]
        for c in in_app.get("clips", []):
            L.append(f"| {c['id']} | {c['exercise_id']} | {c['finished']} | {c['seconds']:.0f} | {c['screenshots']} |")
    report = "\n".join(L) + "\n"
    (ev / "REPORT.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
