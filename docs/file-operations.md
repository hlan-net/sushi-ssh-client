# File Operations

## Problem

File transfer is a natural conversational request ("download that log", "send this config to the server") but Sushi only supports upload via the Android Share target. There is no way to pull files from the remote host, and file operations cannot be triggered through the AI conversation layer.

## Concept

Extend the SSH connection to support bidirectional file transfer, and wire common file intents into the conversation layer.

## SFTP download (host → phone)

Read a file from the remote host and save it to local Android storage or share it via the system Share sheet.

**Conversational trigger**: User says "download `/var/log/syslog`" → AI generates `EXECUTE: cat /var/log/syslog` or an SFTP directive → app downloads and offers "Open" / "Share"

**Direct trigger**: A "Download file" button in the terminal or Plays tab; user enters a remote path.

**Implementation**: JSch supports SFTP via `ChannelSftp`. `SshClient` already opens `ChannelExec` sessions; adding an SFTP channel is straightforward. Write to `Context.getExternalFilesDir()` or use `MediaStore` for user-visible storage.

Related user story: B-17 (SCP host → phone, P2)

## SFTP upload (phone → host)

Already partially done via the Android Share target (v0.4.0). Gap: no way to trigger upload from within the app without the Share sheet.

**Additions needed**:
- "Upload file" button that opens Android file picker
- Conversational trigger: "upload this file to `/home/pi/`"

Related user story: B-16 (SCP phone → host, P1)

## Context-aware file operations in conversation

Wire the AI layer to recognise file intents and map them to SFTP actions:

| User says | Resolved to |
|-----------|-------------|
| "Download `/var/log/nginx/error.log`" | SFTP get → share |
| "Show me files in `/home/pi/projects`" | `ls -la /home/pi/projects` (already works) |
| "Upload my SSH key" | SFTP put from file picker |
| "Edit `config.txt`" | Download → in-app text editor → upload on save |

## In-app text file viewer/editor

Lightweight viewer for text files pulled from the remote host. MVP: read-only with a "Save back" action after editing.

## Custom log location

`~/.config/sushi/config.conf` on the target supports a `log_dir` key but the app always writes to `~/.sushi_logs/`. Read the config value and honour it so users can direct logs to a mount point, RAM disk, or network share.
