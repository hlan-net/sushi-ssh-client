#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE=".local/local-ssh-test.env"

# Every credential this script knows how to pass through. Also the field names a Vault secret is
# expected to use, so there is no mapping table to keep in sync.
SSH_TEST_VARS=(
  SSH_HOST
  SSH_PORT
  SSH_USERNAME
  SSH_PASSWORD
  SSH_PRIVATE_KEY
  SSH_PRIVATE_KEY_B64
  SSH_ENCRYPTED_PRIVATE_KEY_B64
  SSH_ENCRYPTED_PEM_KEY_B64
  SSH_KEY_PASSPHRASE
  SSH_JUMP_ENABLED
  SSH_JUMP_HOST
  SSH_JUMP_PORT
  SSH_JUMP_USERNAME
  SSH_JUMP_PASSWORD
)

if [[ "${1:-}" == "--setup" ]]; then
  ./scripts/setup-local-ssh-test.sh
  exit 0
fi

# Credentials already exported win over every stored source: CI injects them that way, and a
# stale local file or secret should never silently override what the caller set on purpose.
declare -A PRESET_FROM_ENV=()
for var in "${SSH_TEST_VARS[@]}"; do
  if [[ -n "${!var:-}" ]]; then
    PRESET_FROM_ENV["${var}"]="${!var}"
  fi
done

if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck source=/dev/null
  source "${CONFIG_FILE}"
fi

# Reads credentials from Vault into the same SSH_* variables the rest of this script uses, so
# nothing downstream changes. One `vault kv get` per field rather than one JSON read: it keeps the
# script free of a jq or python dependency, at the cost of a request per credential.
load_credentials_from_vault() {
  if [[ -z "${SSH_TEST_VAULT_PATH:-}" ]]; then
    echo "SSH_TEST_SECRET_SOURCE=vault, but SSH_TEST_VAULT_PATH is not set."
    echo "Point it at the secret holding the credentials, e.g. secret/sushi/local-ssh-test."
    exit 1
  fi
  if ! command -v vault >/dev/null 2>&1; then
    echo "SSH_TEST_SECRET_SOURCE=vault, but the vault CLI is not on PATH."
    exit 1
  fi

  # Probe once before reading fields, so an unreachable Vault or an expired token says so instead
  # of looking like a secret with every field missing.
  if ! vault kv get -field=SSH_HOST "${SSH_TEST_VAULT_PATH}" >/dev/null 2>&1; then
    echo "Cannot read ${SSH_TEST_VAULT_PATH} from Vault at ${VAULT_ADDR:-<VAULT_ADDR unset>}."
    echo "Check the path, and that 'vault token lookup' succeeds."
    exit 1
  fi

  local var value
  for var in "${SSH_TEST_VARS[@]}"; do
    # A missing field exits non-zero; most of these credentials are optional, so that is not an
    # error. An unreachable Vault or a expired token is caught by the probe below instead.
    if value="$(vault kv get -field="${var}" "${SSH_TEST_VAULT_PATH}" 2>/dev/null)"; then
      export "${var}=${value}"
    fi
  done
}

# Default stays the local file, so a clone with no configuration behaves exactly as before and a
# fork never reaches for someone else's secret store. Opting in takes its own variable rather than
# keying off VAULT_ADDR, which developers commonly export for unrelated reasons.
case "${SSH_TEST_SECRET_SOURCE:-file}" in
  file)
    ;;
  vault)
    load_credentials_from_vault
    ;;
  *)
    echo "Unknown SSH_TEST_SECRET_SOURCE='${SSH_TEST_SECRET_SOURCE}'. Use 'file' or 'vault'."
    exit 1
    ;;
esac

for var in "${!PRESET_FROM_ENV[@]}"; do
  export "${var}=${PRESET_FROM_ENV[${var}]}"
done

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

# TEST_CLASS=ALL runs every instrumented test with the credentials injected. Without it the only
# way to reach the whole suite is connectedDebugAndroidTest, which passes no credentials, so
# LocalSshIntegrationTest skips out -- and AGP records those skips as empty <failure/> elements,
# which reads as 16 broken tests. A comma-separated class list is not an option: only its first
# entry runs, and the run still exits 0.
if [[ "${TEST_CLASS}" == "ALL" ]]; then
  TEST_FILTER="-Pandroid.testInstrumentationRunnerArguments.package=net.hlan.sushi"
else
  TEST_FILTER="-Pandroid.testInstrumentationRunnerArguments.class=${TEST_CLASS}"
fi

args=(
  "${TEST_FILTER}"
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

# Passphrase-protected key, kept separate from the plain one so the existing
# unencrypted-key tests keep their regression coverage.
if [[ -n "${SSH_ENCRYPTED_PRIVATE_KEY_B64:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshEncryptedPrivateKeyB64=${SSH_ENCRYPTED_PRIVATE_KEY_B64}")
fi

if [[ -n "${SSH_KEY_PASSPHRASE:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshKeyPassphrase=${SSH_KEY_PASSPHRASE}")
fi

# Legacy PEM-encrypted key. JSch cannot unlock the OpenSSH-format key above (bcrypt KDF), so the
# passphrase-classification tests need a format that actually decrypts.
if [[ -n "${SSH_ENCRYPTED_PEM_KEY_B64:-}" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.sshEncryptedPemKeyB64=${SSH_ENCRYPTED_PEM_KEY_B64}")
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
