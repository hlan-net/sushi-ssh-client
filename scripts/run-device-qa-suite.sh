#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH."
  exit 1
fi

TEST_CLASSES="net.hlan.sushi.ExampleInstrumentedTest,net.hlan.sushi.JschRuntimeTest,net.hlan.sushi.DeviceQaSuiteTest"
PACKAGE_NAME="net.hlan.sushi"

echo "Connected devices:"
adb devices

readarray -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "No connected devices found."
  exit 1
fi

MAX_INSTALLED_VERSION=0
for device in "${DEVICES[@]}"; do
  installed_version="$(adb -s "${device}" shell dumpsys package "${PACKAGE_NAME}" 2>/dev/null \
    | tr -d '\r' \
    | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' \
    | head -n 1)"
  if [[ -n "${installed_version}" && "${installed_version}" -gt "${MAX_INSTALLED_VERSION}" ]]; then
    MAX_INSTALLED_VERSION="${installed_version}"
  fi
done

if [[ "${MAX_INSTALLED_VERSION}" -gt 0 ]]; then
  TARGET_VERSION_CODE=$((MAX_INSTALLED_VERSION + 1))
else
  TARGET_VERSION_CODE=10000
fi

echo "Using test build versionCode=${TARGET_VERSION_CODE}"
echo "Running comprehensive QA suite..."

./gradlew connectedDebugAndroidTest \
  -PversionCode="${TARGET_VERSION_CODE}" \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_CLASSES}" \
  --no-daemon
