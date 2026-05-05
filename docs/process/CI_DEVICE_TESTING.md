# CI Device Testing — RPi5 + Nokia 4.2G

> **Scope note:** This infrastructure is outside the product roadmap.
> It supports the development process but is not a user-facing feature.

## Overview

Instrumented Android tests run on a Nokia 4.2G connected to a Raspberry Pi 5
(`ergo`) via USB. The GitHub Actions runner on that machine is a Docker
container (`github-runner-arm64:2.323.0`), so the phone is not directly
visible inside the container — ADB connectivity is bridged through the host.

The workflow `.github/workflows/device-tests.yml` is **optional and never a
required check**. It is triggered manually or by adding the label
`run-device-tests` to a pull request.

---

## Current state

| Layer | Status |
|-------|--------|
| RPi5 self-hosted runner | Running (Docker container) |
| Nokia 4.2G USB connection | Connected to host `ergo` |
| ADB on host | Managed by `adb-server.service` (see install step below) |
| Workflow | Committed, not yet exercised end-to-end |

---

## One-time host setup

```bash
# Install ADB on ergo (if not already present)
sudo apt update && sudo apt install -y android-tools-adb

# Confirm the phone is visible
adb devices
# → List of devices attached
# → <serial>   device

# Allow the Docker container to reach the host ADB daemon.
# The daemon binds to 127.0.0.1:5037 by default; rebind it to the
# Docker bridge so the container can connect.
adb kill-server
adb -a -P 5037 start-server
# -a  = listen on all interfaces (including the Docker bridge 172.17.0.1)
```

> ⚠️ `adb -a start-server` exposes the daemon on 0.0.0.0. This is safe on a
> closed home/office network but should not be done on untrusted networks.
> Alternatively, bind only to the bridge: `ADB_SERVER_SOCKET=tcp:172.17.0.1:5037 adb start-server`

---

## Make ADB a systemd service

Running `adb start-server` manually means the daemon is lost on reboot or if
the process crashes. Use the helper script to install a systemd unit that
starts the daemon on boot and binds it to the Docker bridge so the runner
container can reach it.

### Install

From a checkout of this repo on the runner host:

```bash
sudo ./scripts/install-adb-systemd-service.sh
```

The script:
- requires `adb` on `PATH` (`sudo apt install -y android-tools-adb`);
- auto-detects the `docker0` bridge IP and runs adb as `$SUDO_USER`;
- writes `/etc/systemd/system/adb-server.service`, enables it, and starts it;
- prints `adb devices` against the bound socket to verify reachability.

Override defaults with `--user <name>`, `--port <port>`, `--bridge-ip <ip>`, or
`--all-interfaces` (binds `0.0.0.0` — only safe on trusted networks).
Remove the service with `sudo ./scripts/install-adb-systemd-service.sh --uninstall`.

### Verify

```bash
sudo systemctl status adb-server

# Confirm the phone is reachable from the container
docker exec github-runner-runner-hlan-net-1 \
  adb -H 172.17.0.1 -P 5037 devices
```

### Phone authorisation after reboot

The Nokia stores the host's ADB RSA key in its authorised-keys list. As long
as the key (`~/.android/adbkey`) on `ergo` is preserved across reboots the
phone will not ask for re-authorisation. If the key is regenerated (e.g. after
`adb keygen`), the phone must be physically unlocked and the prompt accepted
again.

---

## Workflow configuration reference

Set these once in **GitHub → Repository → Settings → Secrets and variables**:

| Kind | Name | Value | Purpose |
|------|------|-------|---------|
| Variable | `ADB_SERVER_HOST` | `172.17.0.1` | Docker bridge IP of host ADB daemon |
| Variable | `ADB_SERVER_PORT` | `5037` | ADB daemon port |
| Secret | `ANDROID_DEVICE_ADDRESS` | `<nokia-ip>:5555` | Only needed for Wi-Fi ADB mode |

The workflow falls back to `172.17.0.1:5037` if the variables are absent.

---

## Wi-Fi ADB (alternative, no USB passthrough needed)

If USB passthrough becomes unreliable, the phone can be reached over Wi-Fi:

```bash
# While phone is still connected via USB:
adb tcpip 5555

# Disconnect USB, then:
adb connect <nokia-lan-ip>:5555
```

Set `ANDROID_DEVICE_ADDRESS = <nokia-lan-ip>:5555` as a repository secret and
the workflow will use `adb connect` automatically. Assign the phone a static
DHCP lease on the router to keep the IP stable.
