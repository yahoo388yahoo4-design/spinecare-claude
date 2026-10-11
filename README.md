# SpineCare

Offline-first neck (cervical spine) rehab companion for **Android and iOS**. It gives real-time camera coaching, counts reps on the device, and can optionally use a coaching server, Bluetooth posture sensors and Health Connect. The app is in **English and Romanian**.

This repo merges and completes two earlier projects:
- [spinecare-hermes](https://github.com/yahoo388yahoo4-design/spinecare-hermes): native apps, GPU server and firmware.
- [SpineCare](https://github.com/yahoo388yahoo4-design/SpineCare): the Expo app's custom exercises, symptom check-ins, EN/RO, secure password storage, HTTPS rules and landmark-only analysis.

> **Medical boundary:** SpineCare guides and measures a plan that **your clinician has approved**.
> - It does not diagnose and does not image the spine.
> - It cannot tell whether a disc, a nerve root or a lesion is safe to load.
> - Every built-in exercise stays locked until you confirm your clinician approved it.
> - Stop and seek care on red flags: progressive weakness, hand clumsiness, gait or balance change, or bowel/bladder change.

## Download

| What | Where |
| --- | --- |
| Android APK (newest `main`) | Releases → **Latest Android build** (`android-latest`). It is refreshed on every push to `main`. |
| Android APK (any CI run) | The **Actions** run, under the `spinecare-debug-apk` artifact. |
| Tagged release | Push a tag like `v0.2.0`. `release.yml` attaches a signed release APK, a debug APK and an unsigned iOS IPA. |

To sign releases with a stable key, add these repository secrets: `SPINECARE_KEYSTORE_B64` (a base64 keystore), `SPINECARE_KEYSTORE_PASSWORD`, `SPINECARE_KEY_ALIAS` and `SPINECARE_KEY_PASSWORD`. Without them, CI generates a throwaway prototype key.

## Layout

```
shared/     exercises.json: the single exercise plan used by server, Android and iOS
            metric_vectors.json: cross-platform metric test cases (generated)
android/    Android app (Kotlin, CameraX + MediaPipe Tasks Vision)
ios/        iOS app (SwiftUI + Vision, generated with xcodegen)
server/     coaching server (FastAPI + WebSocket; YOLOv8-pose optional)
firmware/   ESP32-C3 BLE posture node (BMI270 IMU, PlatformIO)
scripts/    generators, end-to-end WebSocket test, video test, GPU benchmark
testdata/   sample images; evidence/ is the earlier hermes test evidence
```

## Features

- **Guided camera sessions.** Before starting, the app checks the framing and asks for the camera view it needs (side, front or back).
  - It calibrates **your own neutral posture** for 1.5 s, then counts reps relative to it. That way a resting asymmetry, such as scoliosis, isn't penalised.
  - It gives localized cues with optional voice and haptics.
  - Sessions can optionally be recorded with the overlay drawn in, for later review.
- **Three programmes**, picked on the Plan tab by how much of you the camera has to see:
  - **Full posture** (ear to hip in view): chin tuck, thoracic extension, scapular setting.
  - **Upper body only** (waist up, hips can be out of view): shoulder shrug, side arm raise, shoulder blade squeeze.
  - **Neck only** (head and shoulders): chin tuck, neck rotation, side bend and flexion.
  - Upper-body and neck exercises use only metrics that need nothing below the shoulders, so framing, rep counting and form cues all work from a close-up or at a desk.
- **Before and after symptom check-ins** around each session, plus a daily check-in for neck pain, arm symptoms and sleep.
- **Custom exercises** from your clinician, run with a timer and a set counter. Links must use HTTPS.
- **Progress:** session history, pain trend and CSV export through the share sheet. History can be cleared.
- **Sensors:** ESP32 head and trunk posture nodes over BLE (scan, connect, calibrate), Health Connect steps and sleep on Android, and a sleep-setup guide.
- **Privacy:** everything is stored on the phone. The server password is kept in the Android Keystore (EncryptedSharedPreferences) or the iOS Keychain.

## Coaching modes

| Mode | Pose source | Network |
| --- | --- | --- |
| On-device | MediaPipe (Android) or Vision (iOS) | never |
| Auto (default) | Uses the server while it answers; otherwise on-device, with no gap | optional |
| Server | Server required | required |

**What gets streamed:**
- **Landmarks only** (default): only the body-point coordinates leave the phone, never images.
- **Frames:** JPEG frames are sent. This is only needed for the GPU YOLO model.

**Connection rules:**
- The server URL must use `https://`.
- Plain `http://` is allowed only to a private LAN address, and only after you turn on the explicit "allow HTTP on my LAN" switch.

## Server

```bash
cd server
pip install -r requirements.txt            # add requirements-gpu.txt for YOLOv8 frame mode
export SPINECARE_SERVER_USER=me SPINECARE_SERVER_PASSWORD='long-random-secret'
uvicorn main:app --host 0.0.0.0 --port 8300
```

- **Authentication:** every endpoint uses HTTP Basic auth.
  - Without credentials the server refuses all requests. Set `SPINECARE_ALLOW_NO_AUTH=1` only for local testing.
  - On the WebSocket, a failed auth closes the connection with code 1008.
- **Endpoints:** `/health`, `/exercises`, `/sessions`, `POST /analyze/pose` (landmarks only), and `ws /ws/session`.
- **Optional environment variables:**
  - `SPINECARE_DB`: path to the SQLite file.
  - `SPINECARE_EXERCISES`: path to the plan (default `../shared/exercises.json`).
- **Behind TLS:** put the server behind a TLS reverse proxy (Caddy or nginx) when it is used from phones.

## Build and test

```bash
# server and shared logic
pip install -r server/requirements-dev.txt && (cd server && pytest)

# Android (JDK 17, Android SDK 34, Gradle 8.7)
cd android && gradle testDebugUnitTest assembleDebug

# iOS (macOS, Xcode 16)
brew install xcodegen && cd ios && xcodegen generate && open SpineCare.xcodeproj

# firmware
pip install platformio && cd firmware/esp32_posture_node && pio run
```

**Generated files.** After editing the source, re-run the generator and commit the result. CI fails if these files are stale.
- `ios/SpineCare/Core/Strings.swift` comes from `android/.../I18n.kt`. Regenerate it with `python scripts/gen_ios_strings.py`.
- `shared/metric_vectors.json` comes from `server/pose.py`. Regenerate it with `python scripts/gen_metric_vectors.py`.

Both apps' unit tests check their metrics against these same vectors, so all three implementations stay identical.

**CI** (`.github/workflows/ci.yml`) runs on every push and PR:
- the generated-file check
- the server tests
- Android unit tests and the APK build
- the unsigned iOS build
- the firmware build

## Firmware

Hardware: an ESP32-C3 SuperMini and a BMI270 on I2C (SDA = GPIO6, SCL = GPIO7). Set `NODE_ID` to 1 for the head node or 2 for the trunk node.

**Bluetooth (GATT):**
- Service `FFF0`.
- Notify characteristics: `FFF1` for the head, `FFF2` for the trunk.
- Write `FFF3`: send `0` to recalibrate neutral, or `1` to zero the yaw.

Each notification is 20 bytes, little-endian: `seq u16`, then pitch, roll and yaw as `i16` (0.01°), then accel ×3 as `i16` (mg), then gyro ×3 as `i16` (0.1 °/s).

The node measures the orientation of the sensor housing. It does not measure vertebrae.
