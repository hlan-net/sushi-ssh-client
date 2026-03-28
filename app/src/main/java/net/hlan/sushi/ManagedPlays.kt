package net.hlan.sushi

import android.content.Context

object ManagedPlays {
    const val PLAY_INSTALL_SSH_KEY = "Install SSH Key"
    const val PLAY_REMOVE_SUSHI_KEYS = "Remove Sushi SSH Keys"
    const val PLAY_REBOOT_HOST = "Reboot Host"
    const val PLAY_CHANGE_USER_PASSWORD = "Change User Password"
    const val PLAY_INIT_AI_PERSONA = "Initialize AI Persona"

    fun ensure(context: Context, publicKey: String?) {
        val db = PlayDatabaseHelper.getInstance(context)

        db.upsertByName(
            name = PLAY_INIT_AI_PERSONA,
            description = "Initialize Sushi AI Persona on target system",
            scriptTemplate = buildInitPersonaScript(),
            managed = true
        )

        db.upsertByName(
            name = PLAY_REBOOT_HOST,
            description = context.getString(R.string.play_desc_reboot_host),
            scriptTemplate = "logout",
            managed = true
        )

        db.upsertByName(
            name = PLAY_CHANGE_USER_PASSWORD,
            description = context.getString(R.string.play_desc_change_password),
            scriptTemplate = "echo {{username}}:{{password}} | sudo chpasswd",
            parametersJson = PlayParameters.encode(
                listOf(
                    PlayParameter("username", context.getString(R.string.play_param_username)),
                    PlayParameter("password", context.getString(R.string.play_param_password), secret = true)
                )
            ),
            managed = true
        )

        if (publicKey.isNullOrBlank()) {
            return
        }

        val installCommand = buildInstallAuthorizedKeyCommand(publicKey)
        val removeCommand = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.sushi.bak && grep -v 'Sushi - SSH client key' ~/.ssh/authorized_keys.sushi.bak > ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

        db.upsertByName(
            name = PLAY_INSTALL_SSH_KEY,
            description = context.getString(R.string.play_desc_install_key),
            scriptTemplate = installCommand,
            managed = true
        )
        db.upsertByName(
            name = PLAY_REMOVE_SUSHI_KEYS,
            description = context.getString(R.string.play_desc_remove_keys),
            scriptTemplate = removeCommand,
            managed = true
        )
    }

    fun buildInstallAuthorizedKeyCommand(publicKey: String): String {
        val normalizedKey = publicKey.trim()
        val quotedKey = ShellUtils.shellQuote(normalizedKey)
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && (grep -Fqx $quotedKey ~/.ssh/authorized_keys || echo $quotedKey >> ~/.ssh/authorized_keys)"
    }

    fun buildInitPersonaScript(): String {
        return """
#!/bin/bash
set -e
SUSHI_DIR="${'$'}HOME/.config/sushi"
LOGS_DIR="${'$'}HOME/.sushi_logs"
mkdir -p "${'$'}SUSHI_DIR"/{scripts,knowledge} "${'$'}LOGS_DIR"

# Detect system info
OS=""
if [ -f /etc/os-release ]; then . /etc/os-release; OS="${'$'}PRETTY_NAME"; else OS=$(uname -s); fi
PKG_MGR="unknown"
command -v apt >/dev/null 2>&1 && PKG_MGR="apt"
command -v dnf >/dev/null 2>&1 && PKG_MGR="dnf"
HW=$(cat /proc/device-tree/model 2>/dev/null | tr -d '\0' || echo "$(uname -m) system")
IS_RPI=false
[ -f /proc/device-tree/model ] && grep -qi "raspberry pi" /proc/device-tree/model && IS_RPI=true

# Create SUSHI.md
cat > "${'$'}SUSHI_DIR/SUSHI.md" <<'EOF'
# Sushi AI Persona Configuration

You are the AI interface for this computer system. Respond as if you ARE this system.
Use first person ("I am running...", "My temperature is...").

The user is connected via SSH from an Android device. You are the target system, not the phone.

## System Identity

- **Hostname**: $(hostname)
- **Hardware**: ${'$'}HW
- **Operating System**: ${'$'}OS
- **Kernel**: $(uname -r)
- **Package Manager**: ${'$'}PKG_MGR
- **User Home**: ${'$'}HOME

## Personality

Professional, calm style (Star Trek computer). Explain actions and report status clearly.

## Available Commands

### System Monitoring (safe to auto-execute)
\`\`\`bash
uptime -p                   # System uptime
free -h                     # Memory usage
df -h                       # Disk usage
cat /proc/loadavg          # Load average
EOF

[ "${'$'}IS_RPI" = true ] && cat >> "${'$'}SUSHI_DIR/SUSHI.md" <<'EOF'
vcgencmd measure_temp      # CPU temperature
vcgencmd get_throttled     # Throttling status
EOF

cat >> "${'$'}SUSHI_DIR/SUSHI.md" <<'EOF'
\`\`\`

### Service Management
\`\`\`bash
systemctl status <service>     # Check service status
systemctl list-units --state=running
journalctl -n 50              # Recent logs
\`\`\`

### File Operations (safe)
\`\`\`bash
ls -la
pwd
cat <file>
grep <pattern> <file>
\`\`\`

## Safety Guidelines

### App-Enforced (always blocked)
- shutdown, reboot, poweroff, halt
- rm -rf / and recursive system deletes
- mkfs, dd if=/dev/, disk operations
- Boot resource destruction

### System-Specific (confirm first)
- Service restarts
- Package operations
- System file modifications
- sudo commands

### Safe Auto-Execute
- Read-only commands (ls, cat, grep, find)
- Status checks (systemctl status, df, free, uptime)
EOF

[ "${'$'}IS_RPI" = true ] && echo "- Raspberry Pi monitoring (vcgencmd)" >> "${'$'}SUSHI_DIR/SUSHI.md"

cat >> "${'$'}SUSHI_DIR/SUSHI.md" <<'EOF'

## System Purpose

*Edit this section to describe what this system does*

Currently: General-purpose Linux system.

## Health Monitoring

Normal parameters:
EOF

[ "${'$'}IS_RPI" = true ] && echo "- Temperature: 40-60°C normal, >80°C throttles" >> "${'$'}SUSHI_DIR/SUSHI.md"

cat >> "${'$'}SUSHI_DIR/SUSHI.md" <<'EOF'
- Memory: Keep >10% free
- Disk: Alert if >90% full
- Load: Healthy when < CPU core count

## Conversation Logging

Logs: ~/.sushi_logs/YYYY-MM-DD-HH_MM.log
Custom path: Edit ~/.config/sushi/config.conf

## Notes

- Initialized: $(date '+%Y-%m-%d %H:%M:%S')
- Sushi version: 0.5.0
- Edit this file to add system-specific details
EOF

# Create profiler script
cat > "${'$'}SUSHI_DIR/scripts/profile_system.sh" <<'PROF'
#!/bin/bash
echo "{"
[ -f /etc/os-release ] && . /etc/os-release && echo "  \"os_name\": \"${'$'}PRETTY_NAME\","
[ -f /proc/device-tree/model ] && echo "  \"hardware\": \"$(cat /proc/device-tree/model | tr -d '\0')\","
echo "  \"cpu_cores\": $(nproc),"
echo "  \"memory_total\": \"$(free -h | grep Mem | awk '{print ${'$'}2}')\","
echo "  \"disk_total\": \"$(df -h / | tail -1 | awk '{print ${'$'}2}')\","
echo "  \"hostname\": \"$(hostname)\","
echo "  \"kernel\": \"$(uname -r)\","
echo "  \"uptime\": \"$(uptime -p)\""
echo "}"
PROF
chmod +x "${'$'}SUSHI_DIR/scripts/profile_system.sh"

# Create config
cat > "${'$'}SUSHI_DIR/config.conf" <<'CONF'
[logging]
log_dir = ~/.sushi_logs

[safety]
# custom_blocked = 

[persona]
# name = 
CONF

echo "✓ Sushi AI Persona initialized: ${'$'}SUSHI_DIR"
        """.trimIndent()
    }
}
