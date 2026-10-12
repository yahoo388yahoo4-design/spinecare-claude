# Demo videos for detection tests

The spine-care demo clips are **not stored in the repo**. CI downloads them from
Wikimedia Commons on every run and records each clip's source, author and license.
Only public domain, CC0, CC BY and CC BY-SA files are used, so redistributing the
annotated overlays (with attribution) is allowed.

| File | What it does |
|---|---|
| `catalog.json` | The pinned clips: Commons file, the app exercise each is replayed against, and why it is relevant to spine care. Also the license allow-list and the search terms used for discovery. |
| `scripts/discover_demo_videos.py` | Searches Commons and lists licensed candidates (used to find the current clips). |
| `scripts/fetch_demo_videos.py` | Downloads the pinned clips, transcodes them (6 fps, 640 px wide, max 45 s, no audio) and writes `sources.json` + `candidates.md`. |
| `scripts/video_test.py` | Streams a clip to the coaching server over the app's WebSocket protocol and draws the overlay. |
| `android/app/src/androidTest/.../VideoReplayTest.kt` | Runs clips through the Android app's on-device pipeline (MediaPipe, framing, tracker, overlay, recorder). |
| `android/app/src/androidTest/.../VideoAnalysisScreenTest.kt` | Opens clips in the app's "Analyze a video" screen and screenshots it. |
| `scripts/ci_android_replay.sh` | Installs the APKs on an emulator, runs both tests, pulls the results and a screen recording. |
| `scripts/ios_replay/main.swift` | Runs clips through the iOS app's PoseTracker (Apple Vision) and engines on macOS. |
| `scripts/video_evidence_report.py` | Stitches overlay videos, contact sheets and writes `REPORT.md`. |

## Rerun

In CI: every push to a `claude/**` branch or PR to `main` runs **Video replay evidence**
(`.github/workflows/video-evidence.yml`); the `video-evidence` artifact holds the report and
overlays. **Video discovery** (`.github/workflows/video-discovery.yml`, Actions tab ->
Run workflow) lists new candidates.

Locally (needs Python 3.11, ffmpeg and access to commons.wikimedia.org):

```sh
python scripts/discover_demo_videos.py "neck stretch" "cat cow yoga"   # find candidates
python scripts/fetch_demo_videos.py videos                              # download the catalog
# server replay (server running on :8300)
python scripts/video_test.py videos/neck_rotation_yoga.mp4 cervical_rotation_rom out/neck_rotation_yoga
# Android: copy videos/ to android/app/src/androidTest/assets/videos, build both APKs, start an
# emulator with an x86 or arm ABI, then
bash scripts/ci_android_replay.sh evidence/android 270 neck_side_bend_yoga
# iOS engines on a Mac
swiftc -O -target "$(uname -m)-apple-macos14" -o ios_replay scripts/ios_replay/main.swift ios/SpineCare/Core/{PoseTracker,Engines,Plan,Strings,SessionStore}.swift
./ios_replay videos shared/exercises.json evidence/ios
python scripts/video_evidence_report.py evidence   # with sources.json copied into evidence/
```

To add a clip, find it with the discovery script, check it really shows a spine-care
exercise, and add a `commons_file` entry with `exercise_id` and `relevance` to `catalog.json`.
