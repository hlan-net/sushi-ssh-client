# Privacy Policy

Effective date: 2026-11-24

Sushi - SSH Client is an Android application for connecting to SSH servers and, optionally, using Google services for voice input, command generation, and log uploads, and GitHub for sending feedback.

## Data we collect
- User-provided inputs such as SSH connection details, commands, and settings stored on your device. When you connect to an SSH server you configure, your username, password or key signature, and the commands you run are sent to that server; their output comes back from it. Your password goes to no one else, and your private key never leaves your device: only a signature made with it is sent. If you enable Gemini (cloud), your saved hosts' names, usernames and addresses and your commands can also be part of a Gemini request, as described below. Commands run in the built-in local shell run on your device and are not sent to any SSH server; like any session, their output can still be part of a Gemini request or a Drive log upload if you turn those on.
- If you enable Gemini (cloud), each request is sent to the Google Gemini API using your API key or Google sign-in. Besides the text you type, a request can include the output of commands run on your server, the server's `~/.config/sushi/SUSHI.md` persona file, the names, usernames, addresses and ports of your saved hosts (when you have two or more), and recent commands run on the connected host. Command output can contain whatever your server prints, so keep this in mind before asking about sensitive systems. Gemini Nano, when available, runs on your device and sends nothing.
- If you send feedback from Settings, the feedback text you write — and, if you choose to include it, the app version, device manufacturer and model, and Android version — is posted as an issue in the public GitHub repository hlan-net/sushi-ssh-client under your GitHub account. Issues there are publicly visible.
- If you use voice input, your device's speech recognition service processes audio to produce text. This is typically provided by Google on Android devices. Sushi receives only the text, never the audio.
- If you enable Google Drive log uploads, session log text is uploaded to your Google Drive account.

## How we use data
- To connect to SSH servers you configure.
- To generate command suggestions, summaries and troubleshooting steps when Gemini is enabled.
- To receive bug reports and feedback you choose to send.
- To upload logs to Google Drive when you enable that feature.

## Data sharing
- We do not sell or share your data with third parties for marketing.
- Network requests are made only to services you enable (your SSH servers, Google Speech, Google Gemini, Google Drive, GitHub).

## Permissions
- INTERNET: required for SSH, Gemini, Drive and feedback features.
- RECORD_AUDIO: required only for voice input. Voice input is optional.

## Data retention
- App settings and logs are stored locally on your device. Secrets such as API keys and the GitHub sign-in token are stored encrypted on the device.
- If you upload logs to Google Drive, those logs are stored in your Google Drive until you delete them.
- Feedback issues stay on GitHub until they are deleted; you can ask for one to be removed by opening an issue.

## Children
This app is not directed at children.

## Changes
We may update this policy from time to time. The latest version will be published at this URL.

## Contact
If you have questions, please open an issue at:
https://github.com/hlan-net/sushi-ssh-client/issues
