# SpineCare — Test Evidence Report (video-driven, GPU server)

> Note: this evidence was produced by the original spinecare-hermes build, before the combined
> version added per-user neutral calibration, relative form rules and corrected chin-tuck /
> scapular signals. Rep counts and cue texts from the current code will differ.

Date: 2026-10-11 · Build: `v0.1.0` · Server: fleet-3090 (2× RTX 3090, YOLOv8n-pose, CUDA)

## What was tested

The GPT-version report (`SpineCare_Raport_Integrat_RO.html`) listed three reference
exercise videos and explicitly noted that a real test "requires playing the material
in front of the camera of a device running the native build". We did the equivalent
programmatically: the `scripts/video_test.py` harness extracts frames from each video
with ffmpeg and streams them through the **exact same WebSocket protocol the mobile
app uses** (`/ws/session`: session → frames → pose replies → end_session), so the
server-side pipeline (GPU pose → metrics → framing → rep counting → cues) is exercised
end-to-end on real exercise footage.

| Source (from the GPT report) | Status | Result |
|---|---|---|
| YouTube cervical kinesiotherapy demo (`youtube.com/watch?v=au7g4INW-ZI`) | Downloaded, 425 s | 900 frames processed, 3 rotation reps detected |
| South Tees NHS — cervical active range of movement (`southtees.nhs.uk` → YouTube `VkL5lek03OI`) | Downloaded, 76 s | 457 frames processed, **5 rotation reps detected** |
| CSP — neck-pain video exercises (`csp.org.uk`) | Videos hosted on sproutvideo, HTTP 403 (hotlink protection) | Not testable from server; documented limitation |

## Results

| Run | Exercise | Frames | Reps | Latency p50 | Notes |
|---|---|---|---|---|---|
| `nhs_rom` | cervical_rotation_rom | 457 | 5 (frames 237, 300, 318, 347, 425) | 9 ms | Demo shows ~5 turn-and-return cycles; count matches |
| `yt_rom` | cervical_rotation_rom | 900 (cap) | 3 (frames 58, 163, 832) | 9 ms | Instructor turns head; some turns below band or out of frame |
| `yt_chin` | chin_tuck | 900 | 0 | 8 ms | **Correct rejection**: chin_tuck requires side view (lear/lsho/lhip); front-facing video → framing gate blocked scoring on 881/900 frames (`missing: lhip`) |

Evidence artifacts (in this repo under `evidence/`):
- `evidence/nhs_rom/evidence.mp4` — annotated video: skeleton overlay (red keypoints,
  green connections) + live metrics/framing/reps/cues panel per frame
- `evidence/yt_rom/evidence.mp4`, `evidence/yt_chin/evidence.mp4` — same for the other runs
- `evidence/*/metrics_log.json` — per-frame metrics, signal, phase, cues, latency
- `evidence/*/annotated/*.jpg` — sample annotated frames

## Bug found and fixed by this test (real value of the harness)

The original neck-rotation estimator used apparent ear separation (`acos` of ear-sep/
shoulder-width). On real footage, when the head turns, one ear occludes and the signal
collapses to 0° — **0 reps counted on the NHS video**. The harness exposed this
immediately. Fixed in all three engines (server `pose.py`, Android `Metrics.kt`,
iOS `Engines.swift`): signed lateral nose offset vs shoulder midline (`asin`), which
stays visible during rotation and gives left/right sign. Required landmarks for the
rotation exercise changed from `lear,rear,lsho,rsho` to `nose,lsho,rsho`, and the rep
signal is now `abs:neck_yaw_est` (each turn-and-return = one rep). After the fix:
5/5 reps on the NHS video, signed yaw visible in overlays (e.g. −26.5°).

## CI + release status (GitHub Actions, repo `spinecare-hermes`)

- `Android CI` on main @ `iOS: VNRecognizedPoint is 2D`: **green** — APK debug build,
  Android unit tests, server pytest (12 passed incl. new ear-occlusion regression test),
  iOS unsigned build on macos-15.
- `Release builds` on tag `v0.1.0`: **green** — published to
  https://github.com/yahoo388yahoo4-design/spinecare-hermes/releases/tag/v0.1.0
  - `app-release.apk` (61.4 MB, signed with ephemeral CI test key — prototype only)
  - `app-debug.apk` (63.6 MB)
  - `SpineCare-unsigned.ipa` (118 KB, unsigned; sideloading needs re-sign/developer cert)
- Both APK and IPA verified downloadable (HTTP 200, valid archive contents).

## Honest limitations

- Video replay tests the **server pipeline**, not the phone camera path. On-device
  MediaPipe (Android) / Vision (iOS) paths are compile-verified and unit-tested, but
  not yet exercised on a physical phone/emulator with these videos.
- Rep counting on third-party videos is a sanity check, not clinical validation;
  instructor demos include turns outside the configured band and camera cuts.
- The IPA is unsigned (no Apple Developer account); installable IPA needs signing.
- CSP videos are hotlink-protected (403) and could not be tested.

## CI video replay on spine-care clips (combined app)

Workflow **Video replay evidence** ([run 38187196484](https://github.com/yahoo388yahoo4-design/spinecare-claude/actions/runs/38187196484),
artifact `video-evidence`) replayed 12 openly licensed spine-care clips from Wikimedia Commons
(see `testdata/videos/README.md`) through three detection paths. Detect = frames with a person,
ready = frames passing the exercise's framing gate, reps = reps counted. Not clinical validation;
the clip-to-exercise mapping is ours.

| clip | exercise | Android (emulator) | iOS engine (Apple Vision, macOS) | server (YOLOv8n CPU) |
|---|---|---|---|---|
| neck_rotation_yoga | cervical_rotation_rom | 92% / 87% / 2 | 100% / 79% / 8 | 100% / 55% / 7 |
| neck_side_bend_yoga | neck_side_bend | 76% / 76% / 4 | 100% / 1% / 0 | 100% / 10% / 0 |
| neck_coordination_rehab | cervical_rotation_rom | 18% / 4% / 0 | 6% / 0% / 0 | 9% / 6% / 0 |
| shoulder_rolls | shoulder_shrug | 100% / 100% / 1 | 100% / 0% / 0 | 79% / 6% / 2 |
| shoulder_blades_mobility | side_arm_raise | 96% / 0% / 0 | 96% / 4% / 0 | 86% / 63% / 0 |
| arm_stretch_mobility | side_arm_raise | 100% / 0% / 0 | 100% / 13% / 0 | 85% / 60% / 4 |
| reverse_prayer | scapular_squeeze_side | 100% / 100% / 5 | 100% / 97% / 3 | 83% / 69% / 8 |
| scapular_winging_clinical | scapular_retraction | 80% / 44% / 0 | 2% / 0% / 0 | 99% / 1% / 0 |
| bent_over_row | scapular_squeeze_side | 100% / 100% / 2 | 100% / 100% / 2 | 100% / 84% / 2 |
| serratus_punch | scapular_squeeze_side | 61% / 61% / 1 | 100% / 0% / 0 | 14% / 3% / 0 |
| back_extension | thoracic_extension | 69% / 65% / 1 | 65% / 27% / 1 | 3% / 0% / 0 |
| thoracic_twist | thoracic_extension | 100% / 100% / 0 | 100% / 73% / 0 | 59% / 43% / 0 |

Android on-device pose took ~70 ms per frame (p50) on the API 30 emulator; the app's own
SessionRecorder wrote an MP4 for every clip. Three clips were also opened in the app's
"Analyze a video" screen, which finished each one (13-30 s) and was screen-recorded.

Found and fixed by this run: MediaPipe Tasks ships no x86_64 library, so x86_64 devices lost
on-device pose; the app now packages only arm64-v8a, armeabi-v7a and x86.

Worth a look next: the paths disagree on framing for the same clip (e.g. neck side bend is
ready 76% on Android but 1-10% on iOS/server), and the neck-coordination study clip is barely
detected anywhere (the subject wears head gear and is filmed close up).
