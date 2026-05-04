# Visual System Dashboard

## Concept

A graphical view of live system stats pulled from the target — an alternative to asking the AI "what's my CPU usage?" when you want a persistent at-a-glance view.

## Content

- CPU usage (%) — line graph, updated every 5–10 seconds
- Memory usage (used / total)
- Disk usage per mount (bar chart)
- CPU temperature — with colour coding (green → yellow → red)
- Network traffic (in/out bytes)
- Active services (up/down status for services listed in `SUSHI.md`)

## Implementation

- Data sourced from the same profiling script used during persona initialization (`profile_system.sh`) — extend it to return JSON metrics on demand
- App polls via `EXECUTE:` commands on a background timer while the dashboard is visible
- Displayed in a new tab or a collapsible panel accessible from the Terminal tab
- Charts: `MPAndroidChart` or simple custom `Canvas` drawing — no heavy charting library needed for this use case

## Notes

- Dashboard is a read-only view; no commands are sent except the polling queries (all SAFE level)
- It should be opt-in and off by default — continuous polling has battery and bandwidth cost
- The conversational layer and dashboard should share data: if the AI just checked memory, the dashboard should reflect that result without a separate poll
