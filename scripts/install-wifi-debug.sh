#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-192.168.1.136:43333}"
PACKAGE_NAME="net.hlan.sushi"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH."
  exit 1
fi

if [[ ! -f "./gradlew" ]]; then
  echo "Run this script from the repository root."
  exit 1
fi

echo "Checking device: ${DEVICE}"
adb -s "${DEVICE}" get-state >/dev/null

INSTALLED_VERSION_CODE="$(adb -s "${DEVICE}" shell dumpsys package "${PACKAGE_NAME}" 2>/dev/null \
  | tr -d '\r' \
  | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' \
  | head -n 1)"

if [[ -z "${INSTALLED_VERSION_CODE}" ]]; then
  TARGET_VERSION_CODE=10000
else
  TARGET_VERSION_CODE=$((INSTALLED_VERSION_CODE + 1))
fi

echo "Building debug APK with versionCode=${TARGET_VERSION_CODE}"
./gradlew :app:assembleDebug -PversionCode="${TARGET_VERSION_CODE}"

echo "Installing ${APK_PATH}"
adb -s "${DEVICE}" install -r "${APK_PATH}"

echo "Done."
