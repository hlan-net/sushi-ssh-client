#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE=".local/local-ssh-test.env"

if [[ "${1:-}" == "--setup" ]]; then
  ./scripts/setup-local-ssh-test.sh
  exit 0
fi

if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck source=/dev/null
  source "${CONFIG_FILE}"
fi

if [[ -z "${SSH_HOST:-}" || -z "${SSH_USERNAME:-}" ]]; then
  echo "Missing SSH_HOST or SSH_USERNAME."
  echo "Run ./scripts/setup-local-ssh-test.sh or pass env vars directly."
  exit 1
fi

if [[ -z "${SSH_PASSWORD:-}" && -z "${SSH_PRIVATE_KEY_B64:-}" && -z "${SSH_PRIVATE_KEY:-}" ]]; then
  echo "Missing auth value. Set one of SSH_PASSWORD, SSH_PRIVATE_KEY_B64, or SSH_PRIVATE_KEY."
  echo "Tip: run ./scripts/setup-local-ssh-test.sh"
  exit 1
fi

SSH_PORT="${SSH_PORT:-22}"
SSH_JUMP_ENABLED="${SSH_JUMP_ENABLED:-false}"
SSH_JUMP_PORT="${SSH_JUMP_PORT:-22}"
TEST_CLASS="${TEST_CLASS:-net.hlan.sushi.LocalSshIntegrationTest}"

args=(
  "-Pandroid.testInstrumentationRunnerArguments.class=${TEST_CLASS}"
  "-Pandroid.testInstrumentationRunnerArguments.sshHost=${SSH_HOST}"
  "-Pandroid.testInstrumentationRunnerArguments.sshPort=${SSH_PORT}"
  "-Pandroid.testInstrumentationRunnerArguments.sshUsername=${SSH_USERNAME}"
  "-Pandroid.testInstrumentationRunnerArguments.sshJumpEnabled=${SSH_JUMP_ENABLED}"
)

if [[ -n "${SSH_PASSWORD:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshPassword=${SSH_PASSWORD}")
fi

if [[ -n "${SSH_PRIVATE_KEY:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshPrivateKey=${SSH_PRIVATE_KEY}")
fi

if [[ -n "${SSH_PRIVATE_KEY_B64:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyB64=${SSH_PRIVATE_KEY_B64}")
fi

if [[ "${SSH_JUMP_ENABLED}" == "true" || "${SSH_JUMP_ENABLED}" == "1" ]]; then
  if [[ -z "${SSH_JUMP_HOST:-}" || -z "${SSH_JUMP_USERNAME:-}" ]]; then
    echo "SSH_JUMP_ENABLED is true but SSH_JUMP_HOST or SSH_JUMP_USERNAME is missing."
    exit 1
  fi
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshJumpHost=${SSH_JUMP_HOST}")
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshJumpPort=${SSH_JUMP_PORT}")
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshJumpUsername=${SSH_JUMP_USERNAME}")
  if [[ -n "${SSH_JUMP_PASSWORD:-}" ]]; then
    args+=("-Pandroid.testInstrumentationRunnerArguments.sshJumpPassword=${SSH_JUMP_PASSWORD}")
  fi
fi

STABILITY_LOG_TAG="SushiStabilityTest"

# Clear logcat before the test run so we only capture this session.
for device in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  adb -s "${device}" logcat -c 2>/dev/null || true
done

./gradlew connectedDebugAndroidTest --no-daemon "${args[@]}"
test_exit=$?

# Dump stability test log from logcat.
for device in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  log_lines="$(adb -s "${device}" logcat -d -s "${STABILITY_LOG_TAG}:I" 2>/dev/null | grep "${STABILITY_LOG_TAG}" || true)"
  if [[ -n "${log_lines}" ]]; then
    echo ""
    echo "=== Terminal stability log (${device}) ==="
    echo "${log_lines}" | sed "s/^.*${STABILITY_LOG_TAG}: //"
    echo "=== End log ==="
  fi
done

exit ${test_exit}
