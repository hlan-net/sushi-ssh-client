# Security improvements

## 1. Host key verification is disabled (P0)

**Finding.** `SshClient.configureSession()` sets:

```kotlin
session.setConfig("StrictHostKeyChecking", "no")   // SshClient.kt:295
```

Every connection — target *and* jump host — accepts any host key. A network
attacker (rogue Wi-Fi AP, compromised router) can transparently proxy the SSH
session and capture the password and everything typed. Notably,
`ConnectFailure.HOST_KEY_MISMATCH` and its classification branch in
`classifyException()` exist but can never fire, because JSch never rejects a key.

**Proposal.**

1. Persist accepted host keys in an app-private known-hosts store
   (`JSch.setKnownHosts(...)` accepts an `InputStream`; keep the file in
   app-internal storage, or store entries in `SecurePrefs`).
2. Switch to `StrictHostKeyChecking=ask` with a `UserInfo`/`HostKeyRepository`
   implementation that:
   - on first connect, shows the key type + SHA-256 fingerprint and asks the
     user to accept (TOFU — trust on first use);
   - on mismatch, refuses to connect and shows a prominent warning with both
     fingerprints and a deliberate "replace key" escape hatch.
3. Add a host-key management screen (list known keys per host, delete entry).
4. Wire the now-reachable `HOST_KEY_MISMATCH` into the existing error banner.

**Effort.** ~2–4 days including UI. The `ConnectFailure` plumbing and error
banner already exist, which cuts the integration cost.

## 2. Passphrase-protected private keys are unsupported (P0)

**Finding.** `addPrivateKeyIdentity()` hardcodes a null passphrase:

```kotlin
val keyPassphrase: ByteArray? = null              // SshClient.kt:184
jsch.addIdentity("key", privateKeyBytes, null, keyPassphrase)
```

An encrypted key — which is what every SSH guide tells users to generate —
fails with a cryptic JSch error. This also nudges users toward storing
*unencrypted* keys on a phone.

**Proposal.** Add an optional passphrase field: prompt at connect time (do not
persist by default; offer "remember" backed by `SecurePrefs`). Pass it through
`SshConnectionConfig` → `addIdentity`. Zero the `ByteArray` after use.

**Effort.** ~1 day.

## 3. One global key pair for all hosts (P1)

**Finding.** `SshSettings` stores a single "Global Key Pair"
(`KEY_PRIVATE_KEY`/`KEY_PUBLIC_KEY`, SshSettings.kt:15–33) that
`getConfigOrNull()` injects into every host config. Jump hosts additionally
only ever authenticate with a password (`establishJumpSession` never adds a
separate identity).

**Proposal.** Introduce named identities (list of key pairs in `SecurePrefs`),
let each host reference one, keep the current key as the "default" identity for
migration. This is roadmap item **A-6**. While at it, allow key-based auth for
jump hosts.

**Effort.** ~3 days including `KeysActivity` rework and migration.

## 4. Deprecated crypto APIs (P2)

**Finding.** `SecurePrefs` uses `MasterKeys` + `EncryptedSharedPreferences`
from `androidx.security:security-crypto` (SecurePrefs.kt:12). The library has
been deprecated/in maintenance mode; `MasterKeys` was superseded by
`MasterKey.Builder` long before that.

**Proposal.** Short term: move to `MasterKey.Builder` (drop-in). Medium term:
plan a migration path to Tink-backed storage or Android Keystore-wrapped AES
keys under app control, with a one-time re-encryption migration of existing
prefs. Do this *before* the library breaks on a future SDK, not after.

**Effort.** Short term hours; full migration ~2–3 days (migration code + tests).

## 5. `CommandSafety` is bypassable — document it as best-effort (P2)

**Finding.** The classifier (CommandSafety.kt) blocks pipe-to-shell and known
destructive patterns, but a determined or hallucinating model can still slip
through, e.g.:

- `env bash script.sh`, `xargs bash`, `nohup python3 x.py` — interpreter not
  the *first* word of the segment;
- command substitution: `echo $(reboot)` / backticks (substring "reboot" is
  caught here, but `echo $(rm -fr ~)` style variants depend on pattern list);
- `awk 'BEGIN{system("...")}'`, `find -exec`, `sed e`;
- quoting/escaping tricks (`re'boot'` is not caught by substring match).

This matters because SAFE-classified commands are **auto-executed** by the AI
conversation layer.

**Proposal.**
1. Extend the interpreter check to scan all words of a segment for
   interpreter/execution primitives (`env`, `xargs`, `find … -exec`, `awk
   system`, `$(`, backtick).
2. Consider inverting the model for auto-execution: only an explicit
   *allowlist* of read-only binaries (`ls`, `cat`, `df`, `uptime`, …) is SAFE;
   everything else is at least CONFIRM. Blocklist remains for BLOCKED.
3. State clearly in docs/UI that classification is heuristic; the CONFIRM
   dialog is the real safety boundary.

**Effort.** Allowlist inversion ~1–2 days; the unit-test suite
(`CommandSafetyTest`) already exists to lock behaviour in.

## 6. Smaller items

- **App lock (P2).** Host passwords and keys are one screen tap away. Offer an
  optional biometric/PIN gate (BiometricPrompt) for opening the app or for
  revealing/editing credentials.
- **Backup rules are good — keep them.** `backup_rules.xml` /
  `data_extraction_rules.xml` already exclude all shared prefs; any future move
  of secrets to files/SQLite must extend these exclusions.
- **Memory hygiene (P2).** Passwords and keys travel as `String` (immutable,
  lingers in heap). Where practical (passphrase, password at connect time),
  prefer `CharArray`/`ByteArray` and wipe after use.
- **Jump-host password reuse (P2).** `resolveJumpServer()` copies the jump
  host's stored password into the outgoing config; fine, but the same TOFU host
  key check from item 1 must also apply to the jump session (it does if done in
  `configureSession`).
