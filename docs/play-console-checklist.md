# Play Console checklist: internal testing → public production

Sushi has so far only shipped on the **internal testing** track (see `CHANGELOG.md` / release
history). This checklist covers everything that has to be done **directly in Play Console** —
none of it can be done from this repo — before switching a release to the **production** track.

Repo-side prep that's already done, referenced from here:
- Store listing texts: `fastlane/metadata/android/en-US/title.txt`, `short_description.txt`,
  `full_description.txt`, `changelogs/22.txt` (drafts — copy/paste and adjust in Console).
- Screenshots: `docs/store-assets/screenshots/01-terminal.png`, `02-plays.png`, `03-settings.png`
  (captured from a real device on 0.7.6; review and re-crop/replace as needed).
- Privacy policy: already live at https://sushi.hlan.net/privacy-policy.html.
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
| Gemini text (optional) | Sent to Google's Gemini API using the user's own API key, only if the user enables it. |
| Google Drive log upload (optional) | Session logs uploaded to the *user's own* Google Drive, only if enabled. |

Suggested top-level answers:
- "Does your app collect or share any of the required user data types?" → **Yes** (audio, if voice
  input is used; app info/performance if you enable crash reporting — check whether any is
  enabled before answering).
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
- Ads: none (unless you've added an ad SDK — verify `app/build.gradle.kts` doesn't pull one in;
  it doesn't as of 0.7.6).

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
