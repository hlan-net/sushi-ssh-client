# User Stories

Prioritised user stories for Sushi SSH client.
Stories are grouped by theme; each has a **priority** (P0 = must-have, P1 = should-have, P2 = nice-to-have) and **status**.

---

## Epic

> As a user, I want to use a shell on a remote host from my Android phone so that I can manage servers on the go.

All stories below break this epic into specific capabilities.

---

## Terminal & Connectivity

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| T-1 | As a user, I want to connect to an SSH server so that I can run commands remotely. | P0 | Done |
| T-2 | As a user, I want the app to auto-retry a failed connection so that transient network issues don't block me. | P0 | Done |
| T-3 | As a user, I want to be notified when a connection drops unexpectedly so that I know to reconnect. | P0 | Done |
| T-4 | As a user, I want to send special keys (Enter, Tab, Backspace, Ctrl+C, Ctrl+D) from on-screen buttons so that I can control programs without a hardware keyboard. | P0 | Done |
| T-5 | As a user, I want to see ANSI-coloured output in the terminal so that command output is readable. | P1 | Done |
| T-6 | As a user, I want to choose a terminal font size so that text is comfortable to read on my device. | P1 | Done |
| T-7 | As a user, I want the SSH session to survive screen rotation so that I don't lose my work. | P0 | Done |
| T-8 | As a user, I want the connection to stay alive when the app is in the background so that it only disconnects when I, the remote host, or a timeout explicitly ends it. | P0 | Backlog |
| T-9 | As a user, I want to re-orient my device (portrait/landscape) to get different display proportions so that I can choose the best layout for the content I'm working with. | P1 | Done |
| T-10 | As a remote host administrator, I don't want the app to generate bursts of invalid connection attempts so that my fail2ban/intrusion detection systems are not triggered by legitimate users. | P1 | Partial |

## Authentication & Key Management

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| A-1 | As a user, I want to authenticate with a password so that I can connect to servers that require one. | P0 | Done |
| A-2 | As a user, I want to authenticate with an SSH key so that I can use key-based auth. | P0 | Done |
| A-3 | As a user, I want to generate an SSH key pair in-app so that I don't need an external tool. | P1 | Done |
| A-4 | As a user, I want to choose an auth preference per host (auto/password/key) so that I control how each server authenticates. | P1 | Done |
| A-5 | As a user, I want to connect through a jump server so that I can reach hosts behind a bastion. | P1 | Done |
| A-6 | As a user, I want to easily reuse my identity (key pair) across multiple hosts so that I don't have to set up authentication separately for each server. | P1 | Backlog |

## Hosts

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| H-1 | As a user, I want to save multiple host configurations so that I can connect quickly. | P0 | Done |
| H-2 | As a user, I want to edit or delete a saved host so that I can keep my list current. | P0 | Done |
| H-3 | As a user, I want to test a host connection from settings so that I can verify config before opening a terminal. | P1 | Done |
| H-4 | As a user, I want to copy connection diagnostics so that I can share debug info when troubleshooting. | P1 | Done |

## Phrases & Automation

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| P-1 | As a user, I want to save reusable command phrases so that I can quickly run frequent commands. | P0 | Done |
| P-2 | As a user, I want to send a saved phrase directly from the terminal so that I don't have to retype commands. | P0 | Done |
| P-3 | As a user, I want to export and import phrases as JSON so that I can back them up or share them. | P1 | Done |
| P-4 | As a user, I want to run automated plays (scripted command sequences) so that I can execute multi-step workflows with one tap. | P1 | Done |
| P-5 | As a user, I want plays to support template parameters so that I can reuse the same play with different values. | P1 | Done |
| P-6 | As a user, I want to choose which host a play runs against so that I can target different servers. | P1 | Done |

## Gemini AI Assistant

> As a user, I want to chat with an AI agent by voice to manage aspects of remote hosts so that I can operate hands-free or without remembering exact commands.

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| G-1 | As a user, I want to describe a task in natural language and get a suggested SSH command so that I don't have to remember syntax. | P1 | Done |
| G-2 | As a user, I want Gemini to run on-device via Nano so that inference works without network or an API key. | P1 | Done |
| G-3 | As a user, I want to toggle between on-device and cloud Gemini models so that I can choose speed vs capability. | P1 | Done |
| G-4 | As a user, I want to choose between Flash and Pro cloud models so that I can balance cost and quality. | P2 | Done |
| G-5 | As a user, I want to copy a Gemini-suggested command so that I can paste and edit it before running. | P1 | Done |
| G-6 | As a user, I want a full transcript of my Gemini voice conversation — what I said, what Gemini replied, and the commands executed — so that I can review, audit, or learn from the interaction. | P1 | Partial |

## Google Drive Integration

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| D-1 | As a user, I want to upload session logs to Google Drive so that I have an off-device backup. | P1 | Done |
| D-2 | As a user, I want logs to auto-upload on disconnect when always-save is enabled so that I never forget to save. | P1 | Done |
| D-3 | As a user, I want logs stored in a `sushi-logs/` Drive folder so that they're organised. | P2 | Done |

## Security & Data

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| S-1 | As a user, I want secrets (API keys, tokens, passwords) stored with AES-256 encryption so that my credentials are protected at rest. | P0 | Done |
| S-2 | As a user, I want non-sensitive settings stored in standard preferences so that they're easy to back up. | P2 | Done |

## Vision

> As a user, I want to talk to my remote devices by instructing them and enquiring about their state so that managing infrastructure feels like a natural conversation.

This is the long-term direction: the AI agent evolves from generating single commands to maintaining ongoing awareness of host state, answering questions about it, and carrying out multi-step operations through voice dialogue.

---

## Tunnelling & Network Access

> As a user, I want local applications on my device to access remote-host-accessible resources as if they were local so that I can use any app with remote services transparently.

Port forwarding (B-7, B-9, B-10) and SOCKS proxy (B-11) are specific implementations of this epic.

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| B-11 | As a user, I want to run a SOCKS proxy through my SSH connection so that any app on my device can route traffic through the remote host without configuring individual port forwards. | P2 | Backlog |

---

## Backlog (not yet started)

| ID | Story | Priority | Status |
|----|-------|----------|--------|
| B-1 | As a user, I want to search/filter my saved phrases so that I can find the right one quickly when I have many. | P2 | Backlog |
| B-2 | As a user, I want to reorder phrases by drag-and-drop so that my most-used phrases are at the top. | P2 | Backlog |
| B-3 | As a user, I want to assign phrases to quick-access slots visible in the terminal so that my top commands are one tap away. | P2 | Backlog |
| B-4 | As a user, I want to receive a notification when a long-running play finishes so that I can check the result. | P2 | Backlog |
| B-5 | As a user, I want to browse and restore past session logs on-device so that I can review previous work. | P2 | Backlog |
| B-6 | As a user, I want SFTP file transfer support so that I can upload and download files through the same connection. | P2 | Backlog |
| B-7 | As a user, I want to easily forward a local port to a remote host's port so that I can access remote services (databases, web apps) from my phone. | P1 | Backlog |
| B-9 | As a user, I want to forward a remote port back to my device so that remote services can reach my local environment. | P2 | Backlog |
| B-10 | As a user, I want to forward a local port through the remote host to a third-party host and port so that I can reach services on networks only accessible from the remote host. | P1 | Backlog |
| B-8 | As a user, I want to pin a host as my default so that connecting is a single tap from the main screen. | P2 | Backlog |
| B-12 | As a user, I want to capture data using my phone's sensors (camera, GPS, etc.) and write it as a text file to a selected remote host so that I can transfer data that is easy to capture on a phone but hard to type on a virtual keyboard. | P1 | Backlog |
| B-13 | As a user, I want to scan a QR code or barcode and send its content to a remote host as a file so that I can transfer keys, URLs, or config data from paper to server. | P1 | Backlog |
| B-14 | As a user, I want to share my GPS coordinates to a file on a remote host so that I can log or use location data on the server. | P2 | Backlog |
| B-15 | As a user, I want to use Android's Share sheet to send text or files from any app into Sushi so that text is written to a remote file and files are SCP'd to a selected host, making Sushi a bridge between phone apps and remote servers. | P1 | Backlog |
| B-16 | As a user, I want to SCP files from my phone to a remote host so that I can transfer photos, documents, or any file without needing a separate app. | P1 | Backlog |
| B-17 | As a user, I want to SCP files from a remote host to my phone so that I can retrieve logs, configs, or other files locally. | P2 | Backlog |
