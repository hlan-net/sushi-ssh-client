# Proactive Monitoring

## Concept

Instead of waiting for the user to ask, the system detects notable conditions on the target and surfaces them as notifications or banners when the user opens the app.

## Examples

- "I have been above 70°C for the past 10 minutes."
- "Disk space on `/` is at 95% — may need attention."
- "The `nginx` service has been down for 2 hours."
- "A new SSH login from `192.168.1.42` occurred while you were away."

## Architecture

The monitoring logic lives on the target system, not in the app — consistent with the persona-on-target model.

1. A background script (`~/.config/sushi/scripts/monitor.sh`) runs on the target via cron or systemd timer
2. Alerts are written to a shared file (e.g., `~/.config/sushi/alerts.json`)
3. On connect, `PersonaClient` reads the alerts file and surfaces any unacknowledged entries
4. User can acknowledge alerts inline or ask the AI to investigate

## App-side

- On persona initialization, offer to install the monitoring script and a systemd timer
- Poll `alerts.json` on connect (not continuously — only on explicit session start)
- Display alerts as a dismissible banner above the conversation transcript
- Tapping an alert opens a conversation pre-filled with "Tell me about this alert"

## Notes

- Monitoring thresholds defined in `~/.config/sushi/config.conf` (temperature limit, disk threshold, etc.)
- This does not require always-on network connectivity from the app — it is a "check when you open it" model, not push notifications
- True push (Android notification when app is closed) would require a server-side relay, which is a separate and larger undertaking
