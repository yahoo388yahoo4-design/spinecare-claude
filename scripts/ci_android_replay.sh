#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (see .github/workflows/video-evidence.yml):
# installs the app + instrumented test APK, injects the bundled demo videos through the
# on-device pipeline (VideoReplayTest), then pulls the overlay frames/logs and a screenshot.
set -u
OUT=${1:-evidence/android}
MAX_FRAMES=${2:-270}
mkdir -p "$OUT"
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c || true
adb shell am instrument -w -r -e class com.spinecare.app.VideoReplayTest -e maxFrames "$MAX_FRAMES" \
  com.spinecare.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"
adb logcat -d -s VideoReplayTest:* AndroidRuntime:E > "$OUT/logcat.txt" || true
adb pull /sdcard/Android/data/com.spinecare.app/files/replay/. "$OUT/" || true
# the real app on the same emulator, as a smoke screenshot
adb shell am start -W -n com.spinecare.app/.MainActivity || true
sleep 6
adb exec-out screencap -p > "$OUT/app_home.png" || true
adb shell getprop ro.product.cpu.abi > "$OUT/abi.txt" || true
# am instrument exits 0 even when the test fails: decide from its output
grep -q "^OK (" "$OUT/instrument.txt"
