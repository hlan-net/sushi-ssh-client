#!/usr/bin/env bash
#
# Install adb as a systemd service on the Device Tests runner host.
#
# Run this once on the host that has the Android device attached via USB
# (e.g. the RPi5 named "ergo"). It creates and enables a systemd unit so the
# ADB daemon starts on boot and is reachable from the GitHub Actions Docker
# runner container via the Docker bridge.
#
# Usage:
#   sudo ./scripts/install-adb-systemd-service.sh [options]
#
# The daemon binds on 0.0.0.0:<port> (the Debian-packaged adb cannot bind to a
# specific IP). Only safe on a trusted home/office network — do not run on a
# host exposed to the public internet.
#
# Options:
#   --user <name>        Run adb as this user (default: $SUDO_USER, else $USER)
#   --port <port>        ADB daemon port (default: 5037)
#   --uninstall          Stop, disable, and remove the service.
#   -h, --help           Show this help.
#
set -euo pipefail

SERVICE_NAME="adb-server"
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"
DEFAULT_PORT="5037"

usage() {
  sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'
}

err() {
  echo "error: $*" >&2
  exit 1
}

require_root() {
  if [ "$(id -u)" -ne 0 ]; then
    err "must be run as root (use sudo)"
  fi
}

detect_user() {
  if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
    echo "$SUDO_USER"
  else
    echo "${USER:-root}"
  fi
}

detect_bridge_ip() {
  if ! command -v ip >/dev/null 2>&1; then
    return 1
  fi
  ip -4 -o addr show docker0 2>/dev/null \
    | awk '{print $4}' \
    | cut -d/ -f1 \
    | head -n 1
}

ADB_USER=""
PORT="$DEFAULT_PORT"
UNINSTALL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --user)            ADB_USER="$2"; shift 2 ;;
    --port)            PORT="$2"; shift 2 ;;
    --uninstall)       UNINSTALL=1; shift ;;
    -h|--help)         usage; exit 0 ;;
    *)                 err "unknown option: $1" ;;
  esac
done

require_root

if [ "$UNINSTALL" -eq 1 ]; then
  if systemctl list-unit-files | grep -q "^${SERVICE_NAME}.service"; then
    systemctl disable --now "${SERVICE_NAME}.service" || true
  fi
  rm -f "$UNIT_PATH"
  systemctl daemon-reload
  echo "Removed ${SERVICE_NAME}.service"
  exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
  err "adb not found on PATH. Install with: sudo apt install -y android-tools-adb"
fi

ADB_BIN="$(command -v adb)"

if [ -z "$ADB_USER" ]; then
  ADB_USER="$(detect_user)"
fi

if ! id "$ADB_USER" >/dev/null 2>&1; then
  err "user '$ADB_USER' does not exist"
fi

EXEC_START="${ADB_BIN} -a -P ${PORT} nodaemon server"
BIND_DESC="0.0.0.0:${PORT}"

# Used only as the address for the post-install reachability check.
BRIDGE_IP="$(detect_bridge_ip || true)"
CHECK_HOST="${BRIDGE_IP:-127.0.0.1}"

ADB_HOME="$(getent passwd "$ADB_USER" | cut -d: -f6)"
if [ -z "$ADB_HOME" ]; then
  err "could not resolve home directory for user '$ADB_USER'"
fi

echo "Writing $UNIT_PATH"
cat > "$UNIT_PATH" <<UNIT
[Unit]
Description=Android Debug Bridge server (Sushi device tests runner)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${ADB_USER}
Environment=HOME=${ADB_HOME}
# Kill any stray user-started daemon so the bind on :${PORT} doesn't conflict.
ExecStartPre=-${ADB_BIN} kill-server
ExecStart=${EXEC_START}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

chmod 0644 "$UNIT_PATH"

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}.service"

# Make sure no other adb daemon (user-started or previous unit) is holding
# port ${PORT} on any interface before we bring the unit up.
pkill -u "$ADB_USER" -x adb 2>/dev/null || true
sleep 1

systemctl restart "${SERVICE_NAME}.service"

echo
echo "Verifying ADB daemon is reachable on ${BIND_DESC}..."
ATTEMPTS=0
while [ "$ATTEMPTS" -lt 10 ]; do
  if sudo -u "$ADB_USER" "$ADB_BIN" -H "$CHECK_HOST" -P "$PORT" devices >/dev/null 2>&1; then
    echo "  OK — adb -H $CHECK_HOST -P $PORT reachable."
    sudo -u "$ADB_USER" "$ADB_BIN" -H "$CHECK_HOST" -P "$PORT" devices
    break
  fi
  ATTEMPTS=$((ATTEMPTS + 1))
  sleep 1
done

if [ "$ATTEMPTS" -ge 10 ]; then
  echo "  WARNING — could not reach adb daemon at $CHECK_HOST:$PORT after 10s." >&2
  echo "  Check 'journalctl -u ${SERVICE_NAME}.service -n 50' for errors." >&2
  systemctl --no-pager --full status "${SERVICE_NAME}.service" || true
  exit 1
fi

echo
systemctl --no-pager status "${SERVICE_NAME}.service" || true

echo
echo "Done. The daemon will start automatically on reboot."
echo "GitHub Actions runner should set ADB_SERVER_HOST=${CHECK_HOST} and ADB_SERVER_PORT=${PORT}."
