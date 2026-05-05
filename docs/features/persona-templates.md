# Persona Templates

## Concept

Pre-made `SUSHI.md` starters for common system setups. Instead of a generic auto-generated persona, the user picks a template that matches their system role and gets relevant commands, monitoring parameters, and personality notes already filled in.

## Templates

| Template | Target system | Pre-configured content |
|----------|--------------|------------------------|
| Home Media Server | Plex, Jellyfin, Kodi | Media service status, storage monitoring, transcode load |
| Development Environment | Coding Pi/VPS | Language runtimes, git, build tools, running dev servers |
| Web Server | nginx, Apache | Service status, access/error logs, SSL cert expiry, port checks |
| IoT / Sensors | Sensor-equipped Pi | GPIO pin map, sensor read commands, data logging |
| RetroPie | Gaming Pi | EmulationStation status, ROM storage, controller config |
| Home Assistant | HA host | HA service, addon status, entity counts |
| NAS | Samba/NFS host | Share status, disk health (smartctl), backup job status |

## Implementation

- Templates bundled in the app (not fetched from network)
- Shown during "Initialize AI Persona" flow as an optional step: "Pick a template or use auto-detected defaults"
- Template content merged with auto-detected system info (hostname, OS, hardware) — not a full replacement
- "Load Template" also available post-init from the persona editor to add a template section to an existing `SUSHI.md`
- User edits are preserved on template reload (smart merge, not overwrite)
