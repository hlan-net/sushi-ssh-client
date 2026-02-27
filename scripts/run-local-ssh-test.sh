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
TEST_CLASS="${TEST_CLASS:-net.hlan.sushi.LocalSshIntegrationTest}"

args=(
  "-Pandroid.testInstrumentationRunnerArguments.class=${TEST_CLASS}"
  "-Pandroid.testInstrumentationRunnerArguments.sshHost=${SSH_HOST}"
  "-Pandroid.testInstrumentationRunnerArguments.sshPort=${SSH_PORT}"
  "-Pandroid.testInstrumentationRunnerArguments.sshUsername=${SSH_USERNAME}"
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

./gradlew connectedDebugAndroidTest --no-daemon "${args[@]}"
