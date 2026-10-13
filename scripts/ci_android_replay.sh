#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (see .github/workflows/video-evidence.yml):
# installs the app + instrumented test APK, injects the bundled demo videos through the
# on-device pipeline (VideoReplayTest), then pulls the overlay frames/logs and a screenshot.
set -u
OUT=${1:-evidence/android}
MAX_FRAMES=${2:-270}
mkdir -p "$OUT"
echo "device ABIs: $(adb shell getprop ro.product.cpu.abilist)"
echo "native bridge: $(adb shell getprop ro.dalvik.vm.native.bridge)"
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep "\.so$" || true
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c || true
adb shell am instrument -w -r -e class com.spinecare.app.VideoReplayTest -e maxFrames "$MAX_FRAMES" \
  com.spinecare.claude.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"
adb logcat -d -s VideoReplayTest:* AndroidRuntime:E > "$OUT/logcat.txt" || true
pull_app_dir() {  # app-private output (debuggable build): stream it out with run-as
  adb shell run-as com.spinecare.claude ls "files/$1" > /dev/null 2>&1 || { echo "no app output in files/$1"; return; }
  adb exec-out run-as com.spinecare.claude tar cf - -C files "$1" | tar xf - -C "$2" --strip-components=1 || true
}
pull_app_dir replay "$OUT"

# Same clips through the app's own "Analyze a video" screen, screen-recorded (skipped if
# this build has no VideoAnalysisActivity). screenrecord stops by itself after 180 s.
IN_APP=${3:-}
if [ -n "$IN_APP" ]; then
  adb shell svc power stayon true || true
  adb shell input keyevent 82 || true
  adb shell screenrecord --bit-rate 4000000 --time-limit 180 /sdcard/in_app.mp4 &
  REC=$!
  adb shell am instrument -w -r -e class com.spinecare.app.VideoAnalysisScreenTest -e inAppClips "$IN_APP" \
    com.spinecare.claude.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument_in_app.txt"
  adb shell pkill -INT screenrecord || true
  wait $REC || true
  sleep 2
  mkdir -p "$OUT/in_app"
  adb pull /sdcard/in_app.mp4 "$OUT/in_app/screenrecord.mp4" || true
  pull_app_dir in_app "$OUT/in_app"
fi
# the real app on the same emulator, as a smoke screenshot
adb shell am start -W -n com.spinecare.claude/com.spinecare.app.MainActivity || true
sleep 6
adb exec-out screencap -p > "$OUT/app_home.png" || true
adb shell getprop ro.product.cpu.abi > "$OUT/abi.txt" || true
# am instrument exits 0 even when the test fails: decide from its output
grep -q "^OK (" "$OUT/instrument.txt" || { echo "VideoReplayTest failed"; exit 1; }
[ -z "$IN_APP" ] || grep -q "^OK (" "$OUT/instrument_in_app.txt" || { echo "VideoAnalysisScreenTest failed"; exit 1; }
