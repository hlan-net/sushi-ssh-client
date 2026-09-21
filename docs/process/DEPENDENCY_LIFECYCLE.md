# Dependency lifecycle

*Audit of 2026-09-21 against `main` at `bb4ea00` (v0.8.3). Versions were read
from Maven Central, Google Maven, Google's SDK repository and ai.google.dev on
that day, not from memory. Re-run `./scripts/check-versions.sh` for today's
picture.*

The goal is the longest possible life for what the app depends on: platform
and Jetpack before third parties, stable before beta, things named at runtime
before things named in code, and one maintainer never being a single point of
failure for a release.

## 1. Act now

| Dependency | Now | Finding | Action |
|---|---|---|---|
| Gemini Flash model id `gemini-1.5-flash` (`GeminiClient.MODEL_FLASH`) | constant in code | **Gone.** ai.google.dev's model list carries no `gemini-1.5-*` at all; the Flash choice in Settings fails. `gemini-2.5-pro` (`MODEL_PRO`, the default) is still listed but a generation behind; current is `gemini-3.1-flash` / `gemini-3.1-pro`. | User-visible bug, P1 (`ROADMAP.md` v0.9.x). Move to the 3.1 ids **and stop hard-coding ids**: fetch `GET /v1beta/models` once, store the user's choice as a capability (*fast* / *capable*), resolve it to an id at call time. Model ids age out in 12–18 months; the app must outlive them. |
| `androidx.credentials`, `credentials-play-services-auth` | 1.3.0 | 1.6.0 published — three minors behind although the Dependabot auto-merge accepts non-major bumps. | Update; find out why auto-merge did not carry it (no PR, or a PR that failed CI). |
| `googleid` | 1.2.0 | 1.2.1 | Update. |
| `com.jcraft:jzlib` | 1.1.3 (2013) | **Unnecessary.** The JSch jar ships `com.jcraft.jsch.juz.Compression` (java.util.zip); the app never configures compression. | Remove; set `compression.s2c` / `compression.c2s` explicitly to `none` (or `zlib@openssh.com` on `juz`). |
| Android Gradle Plugin | 9.4.0 | 9.4.1 | Update. |
| NDK | r27 (2024) | r28, r29, r30 published | `sushi-pty.c` is 217 lines of plain C; move to the newest LTS line with the CMake bump. |
| CMake | 3.22.1 | 4.1.2 in the SDK manager | Same PR as the NDK. |

These are one dependency PR plus the Gemini fix; both are in `ROADMAP.md`
v0.9.x.

## 2. Deprecated or in maintenance — the rewrite plan already replaces them

| Dependency | State | Plan |
|---|---|---|
| `androidx.security:security-crypto` 1.1.0 | Latest, and Google has deprecated the library | Own Keystore-backed store (plan §2.2); library kept only to read the legacy file during migration, removed two releases later |
| `moshi-kotlin` 1.15.2 | Latest; Moshi is in maintenance; the reflection adapter needs `kotlin-reflect` and is R8-fragile | kotlinx.serialization (plan §2.2) |
| `google-api-client-*` 2.9.1, `google-http-client-android` 2.2.0 | Latest of a line Google keeps but no longer develops for Android | Drive REST directly over OkHttp (plan §2.2) |
| `google-api-services-drive` `v3-rev20230815` | Three years of revisions behind (`v3-rev20260901`) | Goes with the line above; if kept, bump the rev |
| `play-services-auth` 22.0.0 | Latest; `GoogleSignIn` is deprecated but `AuthorizationClient` (Identity) lives here | Keep |
| `mlkit:genai-prompt` 1.0.0-beta4 | **No stable line** after more than a year of betas | Keep behind `ConversationLlm` so an API break touches one file; watch each release |
| JUnit 4.13.2 | Frozen but stable; AndroidX Test is JUnit4-based | Keep for instrumented tests; JVM modules may use `kotlin.test` |

## 3. Current — nothing to do

Exactly at the latest stable on the audit day: `core-ktx` 1.19.0, `appcompat`
1.8.0, `constraintlayout` 2.2.2, `viewpager2` 1.1.0, `material` 1.14.0,
`espresso` 3.7.0, `test.ext:junit` 1.3.0, `concurrent-futures` 1.3.0,
**JSch (mwiede) 2.28.7**, **Bouncy Castle 1.86**, `kotlinx-coroutines` 1.11.0,
`guava` 33.7.1-android, **Gradle 9.7.1**, **Kotlin 2.3.21** (AGP's built-in),
JDK 17 (LTS to 2029), `compileSdk` 37, `targetSdk` 36 (Play's next floor is 37
in August 2027). GitHub Actions — `checkout@v7`, `setup-java@v6`,
`upload-artifact@v7`, `github-script@v9`, `setup-gradle@v6.3.0` — are on
current majors and Dependabot watches them weekly.

The rewrite plan's additions were checked too: Compose BOM 2026.09.00, Room
2.8.5, DataStore 1.2.1, Navigation 2.10.1, OkHttp 5.5.0, kotlinx.serialization
1.11.0, Turbine 1.2.1.

## 4. Services the app depends on

| Service | Used for | Lifecycle note |
|---|---|---|
| Gemini API (`generativelanguage.googleapis.com/v1beta`) | cloud model | `v1beta` is the feature-complete path and stays; **model ids do not** — see §1 |
| ML Kit GenAI (Gemini Nano) | on-device model | beta; device-gated (recent Pixel / Galaxy); keep optional |
| Google Drive API v3 + Credential Manager / Identity | log upload, sign-in | REST v3 is stable; the client library is the moving part |
| GitHub Device Flow + REST | in-app feedback | stable, public client id, no secret in the app |
| Google Play (service-account JSON, `r0adkll/upload-google-play@v1`) | release upload | the action is one person's project; the alternative that lives in the build is Gradle Play Publisher (`com.github.triplet.play`) — worth adopting the day the action stalls |
| SonarCloud, Copilot code review, Dependabot | CI | hosted; nothing pinned |
| Figma (MCP) | UX proposals | file-level; see `UX_PROPOSALS.md` |

## 5. Principles

1. **A service's names are data, not constants.** Model ids, feature names,
   API revisions — anything the other side can rename — is fetched at runtime
   or is changeable without a release. The Gemini 1.5 case is the textbook
   example: right at v0.5.0, dead by v0.8.3, nothing in CI could see it.
2. **Platform before Jetpack before third party; stable before beta.** Each
   step down that ladder is a shorter expected life and someone else's
   roadmap.
3. **One maintainer is a risk.** Prefer a dependency that lives in the build
   (a Gradle plugin, a library on Maven Central with several maintainers)
   over an action or tool with one author.
4. **`minSdk` is a dependency too.** `minSdk 26` (Android 8, 2017) is why
   Bouncy Castle is bundled (Ed25519 arrives in the platform at API 33). When
   the floor rises to 33, an 8 MB dependency leaves with it.
5. **Dependabot proposes; someone must notice what it did not.** The
   `credentials` case shows a bump can stall silently. Run
   `./scripts/check-versions.sh` at each release and read the `->` rows.

## 6. The script

`./scripts/check-versions.sh` reads `app/build.gradle.kts`, asks Maven Central
and Google Maven for each artifact's versions, ignores pre-releases (and
compares classifier suffixes like `-android` only with their own kind), and
prints current → latest for every dependency, AGP, Gradle, NDK and CMake. It
ends with the named service resources (Gemini model ids) that no repository
can check, with the page to check them against. Read-only; needs `curl`.
