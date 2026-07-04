# Connection reliability improvements

## 1. Sessions die when the app backgrounds (P1)

**Finding.** There is no service in the manifest; the SSH session lives in
process memory (`TerminalSessionHolder`) and dies whenever Android kills or
freezes the process — switching to check a 2FA app is often enough. This is
roadmap item **T-8** ("Connection keep-alive in background").

**Proposal.**

1. A **foreground service** (`connectedDevice` type, `dataSync` fallback) that
   owns the `TerminalBackend` while at least one session is connected, with a
   persistent notification (host name, connect duration, disconnect action).
2. Move session ownership out of the activity: activities *bind* to the service
   and observe output; rotation and task-kill no longer touch the socket.
3. Battery reality check: document that Doze will still throttle the socket;
   `serverAliveInterval` (already set to 10 s — consider making it configurable
   and less aggressive on battery) keeps NAT mappings alive while the service
   runs.

**Effort.** ~1 week including notification UX and lifecycle testing.

## 2. No automatic reconnect (P1)

**Finding.** `ConnectFailure.isRetryable` exists (SshClient.kt:72) and the UI
gates a manual retry button, but nothing reconnects automatically after a
network blip (Wi-Fi ↔ cellular handover is routine on a phone).

**Proposal.**

- On `onConnectionClosed` while the user did not explicitly disconnect,
  attempt reconnect with exponential backoff (2 s/4 s/8 s, max 3) for
  retryable failures; surface "Reconnecting (2/3)…" in the terminal banner.
- Register a `ConnectivityManager.NetworkCallback` to trigger an immediate
  retry when a validated network returns instead of waiting out the backoff.
- Consider protocol-level session continuity later (mosh is out of scope for
  JSch; reconnect + `tmux attach` hint is a pragmatic substitute — could even
  be a managed Play).

**Effort.** ~2–3 days.

## 3. `keyboard-interactive` authentication is unsupported (P1)

**Finding.** Auth is password + public key only (`resolveAuthPlan`,
SshClient.kt:159). Servers configured with `ChallengeResponseAuthentication`/
PAM (very common, and required for TOTP 2FA setups) will fail even with a
correct password, and the failure will be misclassified as `AUTH_PASSWORD`.

**Proposal.** Implement JSch `UIKeyboardInteractive` on the session `UserInfo`:
prompts are forwarded to a dialog (or answered with the stored password when
the prompt is a plain password echo-off prompt, which covers the common PAM
case non-interactively). This also gives a natural hook for OTP entry.

**Effort.** ~2 days including the prompt dialog.

## 4. Exception classification by message strings is fragile (P2)

**Finding.** `classifyException()` (SshClient.kt:260) matches on message
substrings ("Auth fail", "timeout", …). JSch message texts are not a stable
API — the mwiede fork can reword them in any release, silently degrading
classification to `UNKNOWN` (or worse, the auth-vs-key heuristic mislabeling).

**Proposal.** Prefer type checks first: `JSchAuthCancelException`,
`JSchProxyException`, `SocketTimeoutException`, `UnknownHostException`,
`ConnectException` (partially done), and only fall back to string matching.
Add a unit test per JSch version bump (the existing
`ConnectionFailureClassificationTest` is the right home) so dependabot updates
that change messages fail loudly.

**Effort.** ~1 day.

## 5. Single session at a time (P2)

**Finding.** `TerminalSessionHolder` is a singleton holding one backend; the
host list supports many hosts but only one live connection.

**Proposal.** Turn the holder into a session registry (`Map<hostId, Session>`),
add a session switcher (tabs or a sheet in `TerminalActivity`). This pairs
naturally with the foreground service work (item 1) — do them together to
avoid redesigning lifecycle twice.

**Effort.** ~1 week after item 1 lands.

## 6. Smaller items

- **Configurable timeouts (P2).** `CONNECTION_TIMEOUT_MS = 10000` is hardcoded;
  slow links (cellular, far-away jump hosts) need more, impatient users less.
  One "connection timeout" setting covering session/channel connect is enough.
- **Compression (P2).** `zlib@openssh.com` compression (jzlib is already a
  dependency!) helps a lot on cellular for text-heavy sessions; expose as a
  per-host toggle.
- **Exec-channel host key parity (P0, part of security item 1).**
  `execCommand()` and `sftpUpload()` create sessions through the same
  `configureSession()`, so the host-key fix automatically covers the AI exec
  path and uploads — verify with a test.
- **Jump chain depth (P3).** Only one hop is supported; multi-hop
  (`ProxyJump a,b,c` semantics) can wait until someone asks.
