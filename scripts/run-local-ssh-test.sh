#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${SSH_HOST:-}" || -z "${SSH_USERNAME:-}" ]]; then
  echo "Usage: SSH_HOST=host SSH_USERNAME=user [SSH_PORT=22] [SSH_PASSWORD=pass] ./scripts/run-local-ssh-test.sh"
  echo "Either SSH_PASSWORD or SSH_PRIVATE_KEY must be set."
  exit 1
fi

if [[ -z "${SSH_PASSWORD:-}" && -z "${SSH_PRIVATE_KEY:-}" ]]; then
  echo "Either SSH_PASSWORD or SSH_PRIVATE_KEY must be set."
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

./gradlew connectedDebugAndroidTest --no-daemon "${args[@]}"
