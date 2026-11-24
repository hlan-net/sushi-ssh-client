# Play Console checklist: internal testing → public production

Sushi has so far only shipped on the **internal testing** track (see `CHANGELOG.md` / release
history). This checklist covers everything that has to be done **directly in Play Console** —
none of it can be done from this repo — before switching a release to the **production** track.

Repo-side drafts, referenced from here. **Nothing reads these automatically** — `release.yml`
uploads the bundle with `r0adkll/upload-google-play@v1` and no `whatsNewDirectory`, and fastlane
is not set up — so they are copy-paste sources for Console and can drift from what is live there:
- Store listing texts: `fastlane/metadata/android/en-US/title.txt`, `short_description.txt`,
  `full_description.txt`.
- "What's new": `fastlane/metadata/android/en-US/changelogs/default.txt` — written for **0.9.0**
  from `CHANGELOG.md`. If production ships 0.8.3 instead, drop the first line (the full-screen
  AI conversation is 0.9.0). Play caps it at 500 characters.
- Screenshots: `docs/store-assets/screenshots/01-terminal.png`, `02-plays.png`, `03-settings.png`
  are from 0.7.6 and **must be recaptured on the build being released** before going public:
  0.8.3 made the terminal follow the light/dark theme and 0.9.0 replaced the AI dialog with a
  full-screen conversation, so these no longer show the UI users install. Open item.
- Privacy policy: live at https://sushi.hlan.net/privacy-policy.html, served by GitHub Pages
  from `docs/privacy-policy.html` on `main`; `docs/privacy-policy.md` is the same text and the two
  are edited together. Both updated with this checklist to
  cover the GitHub feedback flow and everything sent to Gemini — confirm the live page shows the
  new effective date before submitting the Data Safety form.
- App icon: Play Console already has the real "sushi nigiri" icon uploaded as the store listing
  icon. Note: this does **not** match the current in-repo launcher icon
  (`app/src/main/res/drawable/ic_launcher.xml`, a plain green/white circle + bar) — worth
  revisiting separately, not required to unblock this release.

## 1. Data Safety form

Based on `AndroidManifest.xml` permissions and `docs/privacy-policy.md`:

| Permission / behavior | Data Safety answer |
| --- | --- |
| `INTERNET`, `CHANGE_NETWORK_STATE` | App connects to network (your SSH hosts, and optionally Google Gemini/Drive/Speech). No data collected/shared by Sushi's own servers — Sushi has no backend. |
| `RECORD_AUDIO` | Audio collected **only if the user enables voice input**; processed by the device's speech recognition service (typically Google), not stored by Sushi. Mark as optional, not shared with third parties by Sushi itself. |
| `POST_NOTIFICATIONS` | Used only for the local "Session active" foreground-service notification; no data collection. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` (`SshConnectionService`) | Declares "connected device" foreground service type to keep the SSH session alive in the background. This is the permission behind the **FGS declaration** already submitted and approved (see task = "Continuous data transfer to an external device"). No further action needed. |
| SSH connection details (host, credentials) | User-entered, stored locally on-device only (not "collected" by a developer server). |
| Gemini cloud (optional) | Sent to Google's Gemini API with the user's own API key or Google sign-in, only if the user enables Gemini. Each request carries more than the typed text: **command output from the user's servers** (for summaries and multi-step troubleshooting), the target's `SUSHI.md` persona, the saved hosts' aliases, usernames, addresses and ports (when two or more are saved), and recent commands run on the connected host. Terminal output can contain sensitive server data. Declare as **collected**: *App activity → Other user-generated content*, optional, purpose *App functionality*. Not shared under Play's definition if the user-initiated exception applies (the user turns the feature on and sends each request) — confirm against Play's current Data Safety wording. Gemini Nano runs on-device and sends nothing. |
| GitHub feedback (optional) | After GitHub Device Flow sign-in, Settings → Feedback creates an issue in the public `hlan-net/sushi-ssh-client` repo via api.github.com containing the **user-entered feedback text** and, if the user ticks it, app version, device manufacturer and model, and Android version. The issue is public and posted under the user's GitHub account. Declare as **collected**: *App activity → Other user-generated content* and *App info and performance → Other app performance data* (device info), optional, user-initiated, purpose *App functionality / Developer communications*. The GitHub OAuth token is stored on-device only, in `SecurePrefs`. |
| Google Drive log upload (optional) | Session logs uploaded to the *user's own* Google Drive, only if enabled. |

Suggested top-level answers:
- "Does your app collect or share any of the required user data types?" → **Yes**: audio if voice
  input is used, user-generated content sent to Gemini and GitHub feedback, and device info if
  the user attaches it to feedback. The build has no crash reporting or analytics SDK (checked
  against `app/build.gradle.kts` on `main`, 0.9.0 development), so nothing else.
- Data is **not sold**, and not used for advertising.
- Data is user-controlled and optional for every feature beyond core SSH connectivity.
- Link the privacy policy: `https://sushi.hlan.net/privacy-policy.html`.

## 2. Content rating questionnaire

Sushi has no user-generated public content, no violence, no gambling, no in-app purchases, and no
social/chat features between users. Expect a rating like **PEGI 3 / Everyone**. Answer "No" to the
mature-content questions; answer honestly on the "shares location" (no) and "user communication"
(no — Sushi doesn't connect users to each other) questions.

## 3. Target audience & content

- Target age group: **not directed at children** (matches `docs/privacy-policy.md` "Children"
  section — pick an 18+ or general adult/developer-tool framing, not the "primarily child-directed"
  path, which brings extra Play Families requirements you don't want here).
- Ads: none. `app/build.gradle.kts` on `main` (0.9.0 development) pulls in no ad, analytics or
  crash-reporting SDK — `com.google.mlkit:genai-prompt` is the on-device Gemini Nano API. Re-check
  if a dependency is added before the production release.

## 4. Store listing

- Category: suggest **Tools** (or **Productivity**).
- Paste in the drafted texts from `fastlane/metadata/android/en-US/`.
- Upload the screenshots from `docs/store-assets/screenshots/` (phone size class; add tablet
  screenshots later if you want tablet listing support — not required to launch).
- Feature graphic (1024×500): **not prepared in this pass** — check whether one already exists in
  Console from the internal-testing setup; if not, this is an open item before going public.
- Contact details / support email: confirm what's currently set in Console (not stored in this
  repo).

## 5. Final step — NOT done in this pass

Once everything above is confirmed complete in Console, trigger a production release by running
`.github/workflows/release.yml` via `workflow_dispatch` with `track: production` (and the desired
`version` input). Do this deliberately, in a separate session, after reviewing this checklist with
the assistant one more time — it publishes the app to all Play Store users.
