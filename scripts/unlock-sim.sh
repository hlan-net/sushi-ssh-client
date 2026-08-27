#!/usr/bin/env bash
set -euo pipefail

# Unlocks a SIM that is asking for its PIN or its PUK, one attempt per run.
#
# Usage: ./scripts/unlock-sim.sh [pin|puk|auto] [device-id]
#
# Both codes have attempt counters with unpleasant ends: three wrong SIM PINs
# escalate to PUK, and ten wrong PUKs destroy the SIM permanently. So this
# script reads the on-screen state before typing anything, refuses to type
# blind, and stops dead on the first failure rather than letting you guess.
#
# The device screen lock is a separate thing -- see ./scripts/unlock-device.sh.
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

ACTION="${1:-auto}"
DEVICE_ID="${2:-""}"

case "$ACTION" in
  pin|puk|auto) ;;
  "") ACTION=auto ;;
  *)
    # Tolerate a bare device id as the first argument, the way unlock-device.sh
    # takes it, instead of failing with a usage message.
    if [[ -z "$DEVICE_ID" ]]; then
      DEVICE_ID="$ACTION"
      ACTION=auto
    else
      echo "Usage: $0 [pin|puk|auto] [device-id]"
      exit 1
    fi
    ;;
esac

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

UI_PATH=/data/local/tmp/unlock-sim-ui.xml   # /sdcard is unreadable while the user is locked

# The dump holds only the view hierarchy -- entry fields are masked -- but there
# is no reason to leave it lying around on the device.
cleanup() { adb -s "$DEVICE_ID" shell rm -f "$UI_PATH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

adb_sh() { adb -s "$DEVICE_ID" shell "$@"; }

sim_state() { adb_sh getprop gsm.sim.state | tr -d '\r'; }

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

remaining_attempts() {
  printf '%s' "$1" | grep -oE '[0-9]+ remaining attempts' | grep -oE '^[0-9]+' || true
}

is_pin_step() {
  printf '%s' "$1" | grep -qiE 'sim pin' && ! printf '%s' "$1" | grep -qiE 'puk'
}

is_puk_step() {
  printf '%s' "$1" | grep -qiE 'enter puk|puk code'
}

# Steps 2-3 of the PUK flow: the PUK was accepted, it wants a new SIM PIN twice.
is_new_pin_step() {
  printf '%s' "$1" | grep -qiE 'new pin|desired pin'
}

clear_field() {
  local i
  for i in $(seq 1 16); do
    adb_sh input keyevent KEYCODE_DEL >/dev/null
  done
}

submit_code() {
  adb_sh input text "$1" >/dev/null
  adb_sh input keyevent KEYCODE_ENTER >/dev/null
}

# Reads a code twice without echoing it. Sets READ_CODE on success.
read_code_twice() {
  local label="$1" pattern="$2" shape="$3" first second
  read -r -s -p "${label}: " first
  echo ""
  read -r -s -p "Re-enter it to catch typos: " second
  echo ""
  if [[ "$first" != "$second" ]]; then
    echo "The two entries differ -- nothing submitted, no attempt used."
    return 1
  fi
  if [[ ! "$first" =~ $pattern ]]; then
    echo "${shape} -- nothing submitted, no attempt used."
    return 1
  fi
  READ_CODE="$first"
}

# Puts the bouncer on the expected step, resetting the flow with BACK if needed.
# BACK submits nothing, so it never costs an attempt. Sets MESSAGE.
require_step() {
  local check="$1" label="$2"
  MESSAGE="$(bouncer_message)"
  if [[ -z "$MESSAGE" ]]; then
    echo "Could not read the lockscreen UI -- refusing to type blind."
    echo "Check that the screen is on and that 'adb shell uiautomator dump' works."
    exit 1
  fi
  if ! "$check" "$MESSAGE"; then
    echo "Not on the ${label} step (\"${MESSAGE}\") -- pressing BACK to reset the flow."
    adb_sh input keyevent KEYCODE_BACK >/dev/null
    sleep 2
    MESSAGE="$(bouncer_message)"
  fi
  if ! "$check" "$MESSAGE"; then
    echo "Still not on the ${label} step: \"${MESSAGE}\""
    echo "Refusing to submit anything. Handle the device by hand."
    exit 1
  fi
}

announce() {
  local warning="$1" attempts
  attempts="$(remaining_attempts "$MESSAGE")"
  echo ""
  echo "On screen: ${MESSAGE}"
  [[ -n "$attempts" ]] && echo "Remaining attempts: ${attempts}"
  echo ""
  echo "$warning"
  echo ""
  ATTEMPTS_BEFORE="$attempts"
}

confirm_submit() {
  local reply
  read -r -p "Submit this code? Type 'yes' to continue: " reply
  if [[ "$reply" != "yes" ]]; then
    echo "Aborted -- nothing submitted, no attempt used."
    exit 1
  fi
}

report_failure() {
  local attempts_after
  attempts_after="$(remaining_attempts "$MESSAGE")"
  echo ""
  echo "FAILED: ${MESSAGE}"
  if [[ -n "${ATTEMPTS_BEFORE:-}" && -n "$attempts_after" ]]; then
    echo "Attempts: ${ATTEMPTS_BEFORE} -> ${attempts_after}"
  fi
  echo ""
  echo "STOP. Do not run this again with another guess."
  echo "$1"
  exit 1
}

do_pin() {
  require_step is_pin_step "SIM PIN entry"
  announce "Three wrong PINs escalate the SIM to PUK. Do not guess."

  read_code_twice "Enter SIM PIN (4-8 digits)" '^[0-9]{4,8}$' "A SIM PIN is 4-8 digits" || exit 1
  confirm_submit

  echo "Submitting..."
  clear_field
  submit_code "$READ_CODE"
  sleep 3

  local state
  state="$(sim_state)"
  if [[ "$state" == "PIN_REQUIRED" || "$state" == "PUK_REQUIRED" ]]; then
    MESSAGE="$(bouncer_message)"
    report_failure "Get the real PIN, or pull the SIM out of the device."
  fi

  echo "SIM unlocked (state: ${state})."
}

do_puk() {
  require_step is_puk_step "PUK entry"
  announce "A wrong code costs one attempt. At zero the SIM is permanently dead.
Do not guess -- the PUK is on the SIM's original card or in your operator's self-service."

  read_code_twice "Enter PUK code (8 digits)" '^[0-9]{8}$' "A PUK is 8 digits" || exit 1
  confirm_submit

  echo "Submitting..."
  clear_field
  submit_code "$READ_CODE"
  sleep 3

  MESSAGE="$(bouncer_message)"
  if ! is_new_pin_step "$MESSAGE"; then
    report_failure "Get the real PUK from your operator, or pull the SIM out of the device."
  fi

  echo "PUK accepted. The SIM now wants a new PIN."
  read_code_twice "New SIM PIN (4-8 digits)" '^[0-9]{4,8}$' "A SIM PIN is 4-8 digits" || exit 1

  clear_field
  submit_code "$READ_CODE"
  sleep 2
  clear_field
  submit_code "$READ_CODE"
  sleep 3

  local state
  state="$(sim_state)"
  if [[ "$state" == "PUK_REQUIRED" ]]; then
    MESSAGE="$(bouncer_message)"
    echo "Still PUK-locked -- on screen: ${MESSAGE}"
    exit 1
  fi

  echo "SIM unlocked (state: ${state})."
}

SIM_STATE="$(sim_state)"
echo "SIM state: ${SIM_STATE}"

if [[ "$ACTION" == "auto" ]]; then
  case "$SIM_STATE" in
    PIN_REQUIRED) ACTION=pin ;;
    PUK_REQUIRED) ACTION=puk ;;
    *)
      echo "The SIM is not asking for a PIN or a PUK -- nothing for this script to do."
      echo "(Use ./scripts/unlock-device.sh for the device screen lock.)"
      exit 0
      ;;
  esac
  echo "Detected action: ${ACTION}"
fi

if [[ "$ACTION" == "pin" && "$SIM_STATE" != "PIN_REQUIRED" ]]; then
  echo "Asked for PIN unlock, but the SIM state is ${SIM_STATE}."
  [[ "$SIM_STATE" == "PUK_REQUIRED" ]] && echo "The PIN is no longer accepted; this SIM needs its PUK: $0 puk ${DEVICE_ID}"
  exit 1
fi

if [[ "$ACTION" == "puk" && "$SIM_STATE" != "PUK_REQUIRED" ]]; then
  echo "Asked for PUK unlock, but the SIM state is ${SIM_STATE}."
  [[ "$SIM_STATE" == "PIN_REQUIRED" ]] && echo "This SIM only needs its PIN: $0 pin ${DEVICE_ID}"
  exit 1
fi

adb_sh input keyevent KEYCODE_WAKEUP >/dev/null
sleep 1

"do_${ACTION}"

echo ""
echo "The device screen lock is separate: ./scripts/unlock-device.sh ${DEVICE_ID}"
echo "Verify with: adb -s ${DEVICE_ID} shell dumpsys user | grep 'Unlock time'"
