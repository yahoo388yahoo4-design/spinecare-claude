# SpineCare

Offline-first cervical-spine rehabilitation companion (Android) with real-time
camera coaching, on-device MediaPipe pose, optional GPU-server enhancement,
BLE posture wearables, and Health Connect integration.

Built for the clinical picture in `../clinical-report.html`: multilevel cervical
disc protrusions (C2–C7) with right C5/C7 radiculopathy, degenerative changes,
mild cervico-dorsal scoliosis, and a C6 focal lesion under imaging follow-up.

> Medical device boundary: SpineCare guides and measures a **clinician-approved**
> plan. It does not diagnose, does not image the spine, and cannot tell whether a
> disc, nerve root, or the C6 lesion is safe to load. Stop and seek care on
> red flags (progressive weakness, hand clumsiness, gait/balance change,
> bowel/bladder change).

## Layout

```
android/    Android app (Kotlin, CameraX + MediaPipe Tasks Vision)
server/     GPU coaching server (FastAPI + WebSocket + YOLOv8-pose)
firmware/   ESP32-S3 BLE posture node (BMI270 IMU)
scripts/    GPU benchmark, end-to-end WebSocket test, VM setup
testdata/   sample images for pose/framing tests
```

## How the three coaching modes work

| Mode | Coaching source | Network |
| --- | --- | --- |
| `ON_DEVICE` | MediaPipe BlazePose (33 landmarks) on the phone | never used |
| `AUTO` (default) | GPU server if reachable, else on-device | optional |
| `SERVER` | GPU server required | required |

The app is **offline-first**: pose, framing verification, rep counting, angle
metrics, haptic/audio cues and session logging all run on the phone with no
network. The server only *enhances* results (GPU pose + temporal form scoring).
If the server drops mid-session, coaching continues on-device without a gap.

Configure everything in the app's **Settings** tab: mode, server URL, user,
password, frame intervals, minimum confidence, haptics, audio cues, CSV export.

## Build the APK

Requires JDK 17+, Android SDK (platform 34, build-tools 34), Gradle 8.7.

```bash
cd android
gradle assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest      # RepEngine / Metrics / FramingGuide unit tests
```

CI: `.github/workflows/android.yml` builds the debug APK and runs both the
Android unit tests and the server pose tests on every push.

The MediaPipe model (`app/src/main/assets/pose_landmarker_full.task`, ~9.4 MB)
is committed so the app works fully offline out of the box.

## Run the GPU server

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8300
curl localhost:8300/health    # {"ok":true,"gpu":true,...}
```

WebSocket protocol (`/ws/session`): client sends `session` then `frame`
(base64 JPEG + timestamp, optional IMU payload); server returns `pose`
messages with metrics, framing status, rep count and coaching cues.
Frames are processed in memory; only derived metrics and session summaries are
persisted (data minimization).

## ESP32 posture node

```bash
cd firmware/esp32_posture_node
pio run -t upload
```

Streams head and upper-trunk orientation over BLE GATT at 50 Hz; the app fuses
it with camera pose for occlusion-free curvature tracking.

## Testing

```bash
cd server && python -m pytest test_pose.py -v     # pose/framing/rep logic
python scripts/e2e_ws_test.py                     # full client->GPU->feedback loop
```
