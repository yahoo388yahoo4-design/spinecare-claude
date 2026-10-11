#!/usr/bin/env bash
# One-shot toolchain bootstrap for the fleet-3090 build box.
set -uo pipefail
ROOT=/root/workspace/spinecare
LOG=$ROOT/logs
mkdir -p "$ROOT" "$LOG" /root/android-sdk

echo "=== [1/4] Android cmdline-tools + SDK ==="
if [ ! -d /root/android-sdk/cmdline-tools/latest ]; then
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools
  mkdir -p /root/android-sdk/cmdline-tools
  mv /tmp/cmdtools/cmdline-tools /root/android-sdk/cmdline-tools/latest
fi
export ANDROID_HOME=/root/android-sdk
export ANDROID_SDK_ROOT=/root/android-sdk
export PATH=$PATH:/root/android-sdk/cmdline-tools/latest/bin
yes | sdkmanager --licenses >/dev/null 2>&1
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125" >/dev/null 2>&1
echo "sdk: $(ls /root/android-sdk)"

echo "=== [2/4] Gradle 8.7 ==="
if [ ! -x /opt/gradle/gradle-8.7/bin/gradle ]; then
  curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -q -o /tmp/gradle.zip -d /opt/gradle
fi
/opt/gradle/gradle-8.7/bin/gradle --version | grep Gradle

echo "=== [3/4] Python venv (GPU pose stack) ==="
cd "$ROOT"
python3 -m venv .venv
. .venv/bin/activate
pip install -q --upgrade pip wheel
pip install -q torch --index-url https://download.pytorch.org/whl/cu124
pip install -q ultralytics mediapipe "fastapi" "uvicorn[standard]" websockets numpy scipy opencv-python-headless pillow aiofiles python-multipart
python - <<'PY'
import torch, mediapipe, ultralytics, fastapi, cv2, numpy
print("torch", torch.__version__, "cuda", torch.cuda.is_available(), "devices", torch.cuda.device_count())
print("mediapipe", mediapipe.__version__)
print("ultralytics", ultralytics.__version__)
print("cv2", cv2.__version__, "numpy", numpy.__version__)
PY

echo "=== [4/4] PlatformIO (ESP32 firmware) ==="
pip install -q platformio
pio --version

echo "=== SETUP COMPLETE ==="
