#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH."
  exit 1
fi

TEST_CLASSES="net.hlan.sushi.ExampleInstrumentedTest,net.hlan.sushi.JschRuntimeTest,net.hlan.sushi.DeviceQaSuiteTest"

echo "Connected devices:"
adb devices
echo "Running comprehensive QA suite..."

./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_CLASSES}" \
  --no-daemon
