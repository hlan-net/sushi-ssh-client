# Tunneled Web Browsing

## Problem

An established SSH connection can reach web servers the phone itself cannot —
internal dashboards, admin panels, services bound to the server's own network.
Users naturally ask "can I just open that page through this connection?" The
desired experience is one tap: connect with a proxy enabled, open a browser that
routes through the tunnel, and have everything revert to normal networking when
the tunnel is turned off — all scoped per connection.

## How dynamic tunneling works

SSH **dynamic forwarding** (`ssh -D <port>`) turns the session into a local
**SOCKS proxy**. Unlike local forwarding (`-L`), which pins one local port to one
fixed remote host, a SOCKS proxy is generic: the client app tells the proxy which
host/port it wants per connection, and SSH dials that out from the server side. A
browser pointed at the SOCKS proxy therefore fetches every page *through* the SSH
server.

In this codebase the primitive is JSch's `Session.setPortForwardingD("127.0.0.1",
port)` (torn down with `delPortForwardingD`). This mirrors the existing jump-host
code in `SshClient.kt`, which already uses `setPortForwardingL` / `delPortForwardingL`.

## Current state (assessed 2026-07)

Nothing for this feature is built yet.

- The only forwarding today is JSch **local** forwarding used internally for
  jump/bastion hosts (`SshClient.kt`). There is **no** dynamic (SOCKS) forwarding.
- No `VpnService`, no tun2socks, no `WebView`/`androidx.webkit`, and no
  browser-launch or browser-enumeration code anywhere in `app/src`.
- Backlog item **B-11 "SOCKS proxy" (P2)** in `ROADMAP.md` tracks the underlying
  capability.

## The Android constraint (decides the architecture)

On Android a normal app **cannot** reach into a separately-installed browser and
flip its proxy on and off — there is no supported API (a system-wide proxy needs
`WRITE_SECURE_SETTINGS`, which is ADB-only and not viable for real users). So
"route my *external* browser through the tunnel, then revert" can only be done one
of two ways:

- **VPN (any external browser).** The app runs an Android `VpnService` that captures
  device traffic and pipes it through the SSH SOCKS proxy via a tun2socks engine.
  Any browser then works unchanged, and stopping the VPN reverts routing instantly.
  This is the only path that satisfies "my pre-selected external browser."
- **In-app WebView (Sushi's own browser).** A `WebView` configured via
  `androidx.webkit` `ProxyController` to use the SOCKS proxy. No VPN permission, no
  system prompt, clean auto-revert when the screen closes — but it is not the user's
  external browser.

Two caveats apply to any SOCKS-based routing here:

- **DNS.** OpenSSH's SOCKS is **TCP-only** (no UDP `ASSOCIATE`), so UDP DNS cannot
  traverse it directly. The tun2socks engine must be configured to resolve DNS over
  **TCP through the tunnel**. This is the most fragile part and needs end-to-end
  verification against the target server.
- **"Browser closed" is not observable.** Android gives no reliable signal that an
  external browser was closed, so revert is driven by an explicit toggle / a
  foreground-notification "Stop" action and by SSH disconnect — not by watching the
  browser.

## Requirements the implementation would need

Layered so the SOCKS foundation can ship independently of the VPN.

1. **SOCKS dynamic forwarding.** Add `socksProxyEnabled: Boolean` and
   `socksProxyPort: Int` to `SshConnectionConfig` (Moshi round-trips them like the
   existing `jumpEnabled`). After the target session connects in `SshClient`, call
   `setPortForwardingD("127.0.0.1", port)`; clean up with `delPortForwardingD` in
   `disconnect()`, mirroring the jump-host teardown. Bind to loopback only — the
   proxy must never be reachable off-device.

2. **tun2socks VPN service.** New `SshTunnelVpnService` extending `VpnService`, run
   as its own foreground service (notification + Stop action, like
   `SshConnectionService`). Route all traffic (`addRoute 0.0.0.0/0`) but
   `addDisallowedApplication(packageName)` for Sushi itself so the underlying SSH
   socket to the real host escapes the VPN. A native tun2socks engine (e.g.
   hev-socks5-tunnel — verify its license before vendoring) built through the
   existing NDK/CMake setup (`app/src/main/cpp/CMakeLists.txt`) bridges the tun fd to
   `127.0.0.1:<socksPort>`. Manifest additions: `<service>` with
   `android:permission="android.permission.BIND_VPN_SERVICE"` and the
   `android.net.VpnService` intent-filter, plus `FOREGROUND_SERVICE_SPECIAL_USE`.

3. **Pre-selected browser + one-tap toggle.** A `<queries>` manifest block (intent
   `ACTION_VIEW` / `CATEGORY_BROWSABLE` / `https`) enables enumerating installed
   browsers via `PackageManager`; store the chosen `preferredBrowserPackage` in
   `SshSettings`. Add a "Browse via tunnel" toggle button to the SSH settings page,
   reusing the async pattern in `SettingsActivity.testActiveConnection()`. On tap:
   run `VpnService.prepare()` (consent dialog if needed) → ensure the active host is
   connected with SOCKS enabled → start the VPN service → launch the browser via
   `Intent(ACTION_VIEW, homepageUri).setPackage(preferredBrowserPackage)` (the
   `AboutActivity` `ACTION_VIEW` pattern).

4. **Revert / lifecycle.** The button is a toggle; re-tapping stops the VPN and the
   SOCKS forward. The VPN notification carries a "Stop tunnel" action. Everything is
   per connection: SOCKS enablement lives on `SshConnectionConfig`, and the VPN is
   bound to the active connection's lifecycle (SSH disconnect also stops it).

## Use cases

- Reach a server's internal-only web UI (bound to its LAN or `localhost`) from the
  phone without exposing it publicly.
- Browse as if from the server's network — e.g. geo/network-restricted internal
  resources — over the one SSH session already open.
- The SOCKS foundation alone also lets any SOCKS-aware app (including Firefox for
  Android's built-in SOCKS setting) use the tunnel manually.

## Why this is deferred

The full "open my external browser through the tunnel" experience requires the
`VpnService` path. Apps that carry other apps' traffic through a VPN incur
**additional Play Store review requirements**, which is not worth it for a
convenience feature at this stage. The lighter, non-VPN alternatives that avoid
that review burden remain available if a smaller version is wanted later:

- **SOCKS-only exposure (B-11)** — ship just the dynamic-forwarding toggle and the
  loopback SOCKS port; users point a SOCKS-aware browser at it. This is also Layer 1
  above, so it doubles as the foundation for the VPN version.
- **In-app WebView** — Sushi's own browser routed through the tunnel via
  `androidx.webkit`; delivers in-app tunneled browsing with no VPN permission.

## Notes

- Related: backlog **B-11 "SOCKS proxy"** and the jump-host forwarding already in
  `SshClient.kt` (the `setPortForwardingL` pattern that dynamic forwarding mirrors).
- No code changes committed yet — this document is the assessment and backlog.
