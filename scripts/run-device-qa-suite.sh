#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH."
  exit 1
fi

TEST_CLASSES=(
  net.hlan.sushi.ExampleInstrumentedTest
  net.hlan.sushi.JschRuntimeTest
  net.hlan.sushi.DeviceQaSuiteTest
)
PACKAGE_NAME="net.hlan.sushi"
RESULTS_DIR="app/build/outputs/androidTest-results/connected"

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

# Espresso requires the device screen to be on and unlocked.
for device in "${DEVICES[@]}"; do
  adb -s "${device}" shell settings put global stay_on_while_plugged_in 3
  adb -s "${device}" shell input keyevent KEYCODE_WAKEUP
  adb -s "${device}" shell wm dismiss-keyguard
done
sleep 1

# Reads the run just written to RESULTS_DIR and prints one line per device. Fails when a
# class contributed no test case at all: a filter that matches nothing leaves Gradle green,
# which is how this suite silently shrank to a single test.
verify_run() {
  local test_class="$1"
  local reports=() report device tests failures errors classes
  local class_tests=0 class_failures=0 status=0

  shopt -s nullglob
  reports=("${RESULTS_DIR}"/*/TEST-*.xml)
  shopt -u nullglob

  if [[ ${#reports[@]} -eq 0 ]]; then
    echo "  no test report written — the run never reached the devices"
    return 1
  fi

  for report in "${reports[@]}"; do
    device="$(basename "${report}" .xml)"
    device="${device#TEST-}"
    tests="$(sed -n 's/.*<testsuites[^>]*tests="\([0-9]*\)".*/\1/p' "${report}" | head -n 1)"
    failures="$(sed -n 's/.*<testsuites[^>]*failures="\([0-9]*\)".*/\1/p' "${report}" | head -n 1)"
    errors="$(sed -n 's/.*<testsuites[^>]*errors="\([0-9]*\)".*/\1/p' "${report}" | head -n 1)"
    tests="${tests:-0}"
    failures="${failures:-0}"
    errors="${errors:-0}"

    echo "  ${device}: ${tests} tests, ${failures} failures, ${errors} errors"
    class_tests=$((class_tests + tests))
    class_failures=$((class_failures + failures + errors))

    classes="$(grep -o 'classname="[^"]*"' "${report}" | sed 's/classname="//;s/"$//' | sort -u)"
    if [[ -n "${classes}" ]] && ! grep -qxF "${test_class}" <<<"${classes}"; then
      echo "  ${device}: expected ${test_class}, report holds: ${classes//$'\n'/, }"
      status=1
    fi
  done

  if [[ "${class_tests}" -eq 0 ]]; then
    echo "  ${test_class} contributed no test case — the class filter matched nothing"
    status=1
  fi

  SUMMARY+=("${test_class}: ${class_tests} tests, ${class_failures} failures")
  if [[ "${class_failures}" -gt 0 ]]; then
    status=1
  fi
  return "${status}"
}

echo "Running comprehensive QA suite..."

# One Gradle invocation per class. A comma-separated value in
# android.testInstrumentationRunnerArguments.class runs only its first entry and still exits 0,
# so the list this script used to pass ran ExampleInstrumentedTest and nothing else.
SUMMARY=()
OVERALL_STATUS=0

for test_class in "${TEST_CLASSES[@]}"; do
  echo
  echo "=== ${test_class} ==="
  rm -rf "${RESULTS_DIR}"

  gradle_status=0
  ./gradlew connectedDebugAndroidTest \
    -PversionCode="${TARGET_VERSION_CODE}" \
    -Pandroid.testInstrumentationRunnerArguments.class="${test_class}" \
    --no-daemon || gradle_status=$?

  verify_status=0
  verify_run "${test_class}" || verify_status=$?

  if [[ "${gradle_status}" -ne 0 || "${verify_status}" -ne 0 ]]; then
    OVERALL_STATUS=1
  fi
done

echo
echo "=== QA suite summary ==="
for line in "${SUMMARY[@]}"; do
  echo "  ${line}"
done

if [[ "${OVERALL_STATUS}" -ne 0 ]]; then
  echo "QA suite failed."
  exit 1
fi

echo "QA suite passed on ${#DEVICES[@]} device(s)."
