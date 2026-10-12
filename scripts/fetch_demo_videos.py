"""Download openly licensed exercise demo videos for the CI video-replay tests.

Reads testdata/videos/catalog.json, resolves each entry on Wikimedia Commons
(a pinned file or the best matches of a search), keeps only files whose
license allows redistribution and derivatives, transcodes them to small H.264
clips (fixed fps/width, capped length, no audio) and writes:

  <out>/<id>.mp4      the clip every pipeline replays
  <out>/sources.json  title, author, license, source page and mapping per clip
  <out>/candidates.md every search hit with its license, to help pin files later

Usage: python scripts/fetch_demo_videos.py <out_dir> [--catalog testdata/videos/catalog.json]
"""
import argparse
import html
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://commons.wikimedia.org/w/api.php"
UA = "SpineCare-CI/0.1 (https://github.com/yahoo388yahoo4-design/spinecare-claude; video replay tests)"
ROOT = Path(__file__).resolve().parent.parent


def api(**params) -> dict:
    params.update(format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # network hiccup: back off and retry
            if attempt == 3:
                raise
            print(f"  retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    return {}


def strip_html(s: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()


def search(query: str, limit: int = 20) -> list[str]:
    res = api(action="query", list="search", srnamespace=6, srlimit=limit,
              srsearch=f"{query} filetype:video")
    return [r["title"] for r in res.get("query", {}).get("search", [])]


def file_info(titles: list[str]) -> list[dict]:
    if not titles:
        return []
    res = api(action="query", prop="videoinfo", titles="|".join(titles[:50]),
              viprop="url|size|mime|mediatype|extmetadata|derivatives")
    out = []
    for page in res.get("query", {}).get("pages", []):
        vi = (page.get("videoinfo") or [None])[0]
        if not vi:
            continue
        meta = vi.get("extmetadata", {})
        m = lambda k: strip_html(meta.get(k, {}).get("value", ""))  # noqa: E731
        out.append({
            "title": page["title"],
            "page": vi.get("descriptionurl", ""),
            "url": vi.get("url", ""),
            "size": vi.get("size", 0),
            "duration": float(vi.get("duration") or 0),
            "width": vi.get("width", 0), "height": vi.get("height", 0),
            "mime": vi.get("mime", ""),
            "license": m("LicenseShortName"),
            "license_url": m("LicenseUrl"),
            "author": m("Artist")[:200],
            "credit": m("Credit")[:200],
            "description": m("ImageDescription")[:300],
            "derivatives": vi.get("derivatives", []),
        })
    order = {t: i for i, t in enumerate(titles)}
    return sorted(out, key=lambda r: order.get(r["title"], 1e9))


def license_ok(lic: str, allowed: list[str]) -> bool:
    l = lic.lower()
    if re.search(r"\bnc\b|\bnd\b|non-?commercial|no ?deriv|fair use|non-free", l):
        return False
    return any(a in l for a in allowed)


def pick_source(info: dict) -> str:
    """Smallest transcode that is still >= 360p, else the original file."""
    ders = [d for d in info["derivatives"] if d.get("src") and "video" in d.get("type", "")]
    ders = sorted(ders, key=lambda d: d.get("height", 0) or 0)
    for d in ders:
        if (d.get("height") or 0) >= 360:
            return d["src"]
    if ders:
        return ders[-1]["src"]
    return info["url"]


def download(url: str, dest: Path):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)


def transcode(src: Path, dest: Path, fps: int, width: int, max_s: int):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-t", str(max_s),
                    "-vf", f"fps={fps},scale={width}:-2", "-an", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "23", str(dest)], check=True)


def probe_frames(path: Path) -> int:
    r = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(path)],
                       capture_output=True, text=True)
    try:
        return int(r.stdout.strip().split(",")[0])
    except ValueError:
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--catalog", default=str(ROOT / "testdata" / "videos" / "catalog.json"))
    args = ap.parse_args()
    out = Path(args.out)
    (out / "raw").mkdir(parents=True, exist_ok=True)
    cat = json.loads(Path(args.catalog).read_text())
    allowed = cat["allowed_licenses"]
    fps, width, max_s = cat.get("fps", 6), cat.get("width", 640), cat.get("max_seconds", 45)

    used: set[str] = set()
    chosen, cand_lines = [], ["| query | file | license | duration s | used |", "|---|---|---|---|---|"]
    seen_q: dict[str, list[dict]] = {}

    def candidates(query: str) -> list[dict]:
        if query not in seen_q:
            seen_q[query] = file_info(search(query))
        return seen_q[query]

    for entry in cat["videos"]:
        if "commons_file" in entry:
            pool = file_info([entry["commons_file"]])
        else:
            pool = candidates(entry["commons_search"])
        n = entry.get("pick", 1)
        for info in pool:
            if n <= 0:
                break
            if info["title"] in used or info["duration"] < 4:
                continue
            if not license_ok(info["license"], allowed):
                continue
            text = (info["title"] + " " + info["description"]).lower()
            if entry.get("require_any") and not any(w in text for w in entry["require_any"]):
                continue
            used.add(info["title"])
            vid_id = entry["id"] if entry.get("pick", 1) == 1 else f"{entry['id']}_{entry.get('pick', 1) - n + 1}"
            raw = out / "raw" / (vid_id + Path(urllib.parse.urlparse(pick_source(info)).path).suffix)
            try:
                print(f"[{vid_id}] {info['title']} ({info['license']}, {info['duration']:.0f}s)")
                download(pick_source(info), raw)
                transcode(raw, out / f"{vid_id}.mp4", fps, width, max_s)
            except Exception as e:
                print(f"  failed: {e}", file=sys.stderr)
                continue
            frames = probe_frames(out / f"{vid_id}.mp4")
            chosen.append({
                "id": vid_id, "exercise_id": entry["exercise_id"], "file": f"{vid_id}.mp4",
                "frames": frames, "fps": fps, "query": entry.get("commons_search", ""),
                "relevance": entry.get("relevance", ""),
                **{k: info[k] for k in ("title", "page", "license", "license_url", "author", "credit",
                                        "description", "duration")},
            })
            n -= 1
        if n > 0:
            print(f"[{entry['id']}] no usable licensed match", file=sys.stderr)

    for q in cat.get("discovery_queries", []):
        candidates(q)
    for q, pool in seen_q.items():
        for info in pool:
            ok = "yes" if info["title"] in used else ("" if license_ok(info["license"], allowed) else "license")
            cand_lines.append(f"| {q} | [{info['title']}]({info['page']}) | {info['license']} | "
                              f"{info['duration']:.0f} | {ok} |")

    (out / "sources.json").write_text(json.dumps({"fps": fps, "width": width, "max_seconds": max_s,
                                                  "videos": chosen}, indent=1))
    (out / "candidates.md").write_text("\n".join(cand_lines) + "\n")
    print("\n".join(cand_lines))
    print(f"{len(chosen)} clips ready in {out}")
    if not chosen:
        sys.exit("no videos could be fetched")


if __name__ == "__main__":
    main()
