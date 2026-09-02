#!/usr/bin/env bash
set -euo pipefail

# Unlocks the device screen lock by entering the device PIN, one attempt per run.
#
# Usage: ./scripts/unlock-device.sh [device-id]
#
# Espresso runs need the user actually unlocked, not just the keyguard hidden:
# until the credential is entered once per boot, credential-encrypted storage
# stays locked and the app crashes on startup with
#   "SharedPreferences in credential encrypted storage are not available
#    until after user (id 0) is unlocked"
#
# For a SIM that is asking for its PIN or PUK, see ./scripts/unlock-sim.sh.
#
# Note on secrecy: codes are read with `read -s` and never echoed, logged, or
# written to disk. They are passed to `adb shell input text`, so the code is
# briefly visible as a process argument on this host and on the device. That is
# acceptable for a local QA device; do not use these scripts on a machine where
# other users can read the process list.

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH."
  exit 1
fi

DEVICE_ID="${1:-""}"

if [[ -z "$DEVICE_ID" ]]; then
  readarray -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVICES[@]} -eq 1 ]]; then
    DEVICE_ID="${DEVICES[0]}"
    echo "Using the only connected device: ${DEVICE_ID}"
  else
    echo "Connected devices:"
    adb devices
    echo ""
    read -r -p "Enter device ID: " DEVICE_ID
  fi
fi

if [[ -z "$DEVICE_ID" ]]; then
  echo "No device ID given."
  exit 1
fi

UI_PATH=/data/local/tmp/unlock-device-ui.xml   # /sdcard is unreadable while the user is locked

# The dump holds only the view hierarchy -- entry fields are masked -- but there
# is no reason to leave it lying around on the device.
cleanup() { adb -s "$DEVICE_ID" shell rm -f "$UI_PATH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

adb_sh() { adb -s "$DEVICE_ID" shell "$@"; }

unlock_time() {
  adb_sh dumpsys user 2>/dev/null | tr -d '\r' \
    | sed -nE 's/^[[:space:]]*Unlock time:[[:space:]]*(.*)$/\1/p' | head -n 1
}

is_unlocked() { [[ "$(unlock_time)" != "<unknown>" && -n "$(unlock_time)" ]]; }

ui_dump() {
  adb_sh uiautomator dump "$UI_PATH" >/dev/null 2>&1 || true
  adb_sh cat "$UI_PATH" 2>/dev/null | tr -d '\r'
}

bouncer_message() {
  ui_dump | tr '<' '\n' \
    | grep 'bouncer_message_area' \
    | sed -E 's/.*text="([^"]*)".*/\1/' \
    | head -n 1
}

has_entry_field() {
  ui_dump | grep -qE 'id/(pinEntry|passwordEntry)"'
}

credential_type() {
  adb_sh dumpsys lock_settings 2>/dev/null | tr -d '\r' \
    | sed -nE 's/^[[:space:]]*CredentialType:[[:space:]]*(.*)$/\1/p' | head -n 1
}

clear_field() {
  local i
  for i in $(seq 1 16); do
    adb_sh input keyevent KEYCODE_DEL >/dev/null
  done
}

if is_unlocked; then
  echo "Already unlocked (Unlock time: $(unlock_time)) -- nothing to do."
  exit 0
fi

SIM_STATE="$(adb_sh getprop gsm.sim.state | tr -d '\r')"
if [[ "$SIM_STATE" == *PIN_REQUIRED* || "$SIM_STATE" == *PUK_REQUIRED* ]]; then
  echo "The SIM is locked (${SIM_STATE}) and its bouncer sits in front of the device one."
  echo "Clear it first: ./scripts/unlock-sim.sh ${DEVICE_ID}"
  exit 1
fi

echo "Waking the device..."
adb_sh input keyevent KEYCODE_WAKEUP >/dev/null
sleep 1

# dismiss-keyguard summons the credential bouncer on a secure lockscreen. A blind
# swipe is not reliable: if it misses, the keypad never appears and the code below
# would be typed into nothing.
echo "Summoning the credential prompt..."
adb_sh wm dismiss-keyguard >/dev/null
sleep 2

if ! has_entry_field; then
  echo "No PIN/password field on screen -- refusing to type blind."
  echo "On screen: $(bouncer_message)"
  exit 1
fi

CRED_TYPE="$(credential_type)"
echo ""
echo "On screen: $(bouncer_message)"
[[ -n "$CRED_TYPE" ]] && echo "Credential type: ${CRED_TYPE}"
echo ""

read -r -s -p "Enter device PIN: " PIN
echo ""

if [[ -z "$PIN" ]]; then
  echo "Empty input -- nothing submitted."
  exit 1
fi

if [[ "$CRED_TYPE" == "PIN" && ! "$PIN" =~ ^[0-9]+$ ]]; then
  echo "This device uses a numeric PIN, but the input has non-digits -- nothing submitted."
  exit 1
fi

echo "Submitting..."
clear_field
adb_sh input text "$PIN" >/dev/null
adb_sh input keyevent KEYCODE_ENTER >/dev/null

# The unlock is asynchronous; give storage a moment to come up before judging.
for _ in $(seq 1 10); do
  sleep 1
  if is_unlocked; then
    echo ""
    echo "Unlocked. Unlock time: $(unlock_time)"
    echo "Credential-encrypted storage is available; Espresso runs can start."
    exit 0
  fi
done

echo ""
echo "FAILED -- the user is still locked (Unlock time: $(unlock_time))."
echo "On screen: $(bouncer_message)"
echo ""
echo "Do not retry blindly: wrong attempts trigger a lockout timer on this device."
exit 1
