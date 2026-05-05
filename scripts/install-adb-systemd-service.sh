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
# Options:
#   --user <name>        Run adb as this user (default: $SUDO_USER, else $USER)
#   --port <port>        ADB daemon port (default: 5037)
#   --bridge-ip <ip>     Bind ADB to this IP only (default: auto-detect docker0)
#   --all-interfaces     Bind ADB to 0.0.0.0 instead of the bridge IP. Less safe
#                        — only use on a trusted network.
#   --uninstall          Stop, disable, and remove the service.
#   -h, --help           Show this help.
#
set -euo pipefail

SERVICE_NAME="adb-server"
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"
DEFAULT_PORT="5037"

usage() {
  sed -n '3,21p' "$0" | sed 's/^# \{0,1\}//'
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
BRIDGE_IP=""
ALL_INTERFACES=0
UNINSTALL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --user)            ADB_USER="$2"; shift 2 ;;
    --port)            PORT="$2"; shift 2 ;;
    --bridge-ip)       BRIDGE_IP="$2"; shift 2 ;;
    --all-interfaces)  ALL_INTERFACES=1; shift ;;
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

if [ "$ALL_INTERFACES" -eq 1 ]; then
  EXEC_START="${ADB_BIN} -a -P ${PORT} nodaemon server"
  BIND_DESC="0.0.0.0:${PORT}"
else
  if [ -z "$BRIDGE_IP" ]; then
    BRIDGE_IP="$(detect_bridge_ip || true)"
  fi
  if [ -z "$BRIDGE_IP" ]; then
    err "could not auto-detect docker0 bridge IP. Pass --bridge-ip <ip> or --all-interfaces."
  fi
  EXEC_START="${ADB_BIN} -L tcp:${BRIDGE_IP}:${PORT} nodaemon server"
  BIND_DESC="${BRIDGE_IP}:${PORT}"
fi

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
ExecStart=${EXEC_START}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

chmod 0644 "$UNIT_PATH"

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}.service"
systemctl restart "${SERVICE_NAME}.service"

if [ "$ALL_INTERFACES" -eq 1 ]; then
  CHECK_HOST="127.0.0.1"
else
  CHECK_HOST="$BRIDGE_IP"
fi

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
