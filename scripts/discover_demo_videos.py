"""Search Wikimedia Commons for openly licensed exercise videos to add to the demo set.

Prints one line per new candidate whose license allows redistribution and
derivatives (same rule as fetch_demo_videos.py): query, file title, license,
duration and the start of the description. Use it to find spine-care clips,
then pin the good ones as "commons_file" entries in testdata/videos/catalog.json.

Usage:
  python scripts/discover_demo_videos.py                      # the catalog's discovery_queries
  python scripts/discover_demo_videos.py "neck stretch" "cat cow yoga"
  python scripts/discover_demo_videos.py --limit 40 --markdown > candidates.md

Commons must be reachable (it is from GitHub Actions; see the "Video discovery" workflow).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_demo_videos as f  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("queries", nargs="*")
    ap.add_argument("--catalog", default=str(f.ROOT / "testdata" / "videos" / "catalog.json"))
    ap.add_argument("--limit", type=int, default=25, help="search hits per query")
    ap.add_argument("--min-seconds", type=float, default=4)
    ap.add_argument("--markdown", action="store_true", help="print a markdown table")
    args = ap.parse_args()
    cat = json.loads(Path(args.catalog).read_text())
    queries = args.queries or cat.get("discovery_queries", [])
    pinned = {v.get("commons_file") for v in cat["videos"]}
    if args.markdown:
        print("| query | file | license | seconds | in catalog | description |\n|---|---|---|---|---|---|")
    seen = set()
    for q in queries:
        try:
            pool = f.file_info(f.search(q, args.limit))
        except Exception as e:  # keep going: one failed query should not lose the rest
            print(f"# {q}: {e}", file=sys.stderr)
            continue
        for i in pool:
            if i["title"] in seen or i["duration"] < args.min_seconds or not f.license_ok(i["license"], cat["allowed_licenses"]):
                continue
            seen.add(i["title"])
            desc = " ".join(i["description"].split())[:120].replace("|", "/")
            mark = "yes" if i["title"] in pinned else ""
            if args.markdown:
                print(f"| {q} | [{i['title']}]({i['page']}) | {i['license']} | {i['duration']:.0f} | {mark} | {desc} |")
            else:
                print(f"{q} || {i['title']} || {i['license']} || {i['duration']:.0f}s || {mark} || {desc}")


if __name__ == "__main__":
    main()
