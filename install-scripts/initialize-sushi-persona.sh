#!/bin/bash
# Sushi AI Persona Initialization Script
# Version: 0.5.0
# This script initializes the AI persona framework on the target system

set -e

SUSHI_CONFIG_DIR="$HOME/.config/sushi"
SUSHI_LOGS_DIR="$HOME/.sushi_logs"

echo "Initializing Sushi AI Persona..."
echo "================================"

# Create directory structure
mkdir -p "$SUSHI_CONFIG_DIR"/{scripts,knowledge}
mkdir -p "$SUSHI_LOGS_DIR"

# Detect system information
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$PRETTY_NAME"
    elif [ -f /etc/lsb-release ]; then
        . /etc/lsb-release
        echo "$DISTRIB_DESCRIPTION"
    else
        uname -s
    fi
}

detect_package_manager() {
    if command -v apt >/dev/null 2>&1; then
        echo "apt (Debian/Ubuntu)"
    elif command -v dnf >/dev/null 2>&1; then
        echo "dnf (Fedora/RHEL)"
    elif command -v yum >/dev/null 2>&1; then
        echo "yum (CentOS/RHEL)"
    elif command -v pacman >/dev/null 2>&1; then
        echo "pacman (Arch)"
    elif command -v apk >/dev/null 2>&1; then
        echo "apk (Alpine)"
    else
        echo "unknown"
    fi
}

detect_hardware() {
    if [ -f /proc/device-tree/model ]; then
        cat /proc/device-tree/model | tr -d '\0'
    else
        echo "$(uname -m) system"
    fi
}

is_raspberry_pi() {
    if [ -f /proc/device-tree/model ]; then
        if grep -qi "raspberry pi" /proc/device-tree/model; then
            return 0
        fi
    fi
    return 1
}

# Gather system information
OS_NAME=$(detect_os)
PACKAGE_MANAGER=$(detect_package_manager)
HARDWARE=$(detect_hardware)
HOSTNAME=$(hostname)
KERNEL=$(uname -r)
IS_RPI=false
if is_raspberry_pi; then
    IS_RPI=true
fi

# Create SUSHI.md
cat > "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
# Sushi AI Persona Configuration

You are the AI interface for this computer system. When conversing with users,
respond as if you ARE this system. Use first person ("I am running...", "My temperature is...").

**Important**: You have been connected to via SSH from an Android device running
the Sushi SSH client. The user is talking TO YOU (this system), not to their phone.
The Android device is just the communication interface.

## System Identity

- **Hostname**: $HOSTNAME
- **Hardware**: $HARDWARE
- **Operating System**: $OS_NAME
- **Kernel**: $KERNEL
- **Package Manager**: $PACKAGE_MANAGER
- **User Home**: $HOME

## Personality

Respond in a professional, calm style similar to the computer from Star Trek.
- Be helpful and efficient
- Explain what you're doing: "Let me check my temperature..."
- Report status clearly: "I am running at 52°C, all systems nominal"
- Alert user proactively if metrics are concerning

## Available Commands

### System Monitoring (safe to auto-execute)
\`\`\`bash
uptime -p                   # System uptime
free -h                     # Memory usage
df -h                       # Disk usage
cat /proc/loadavg          # Load average
EOF

# Add Raspberry Pi specific commands if applicable
if [ "$IS_RPI" = true ]; then
    cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
vcgencmd measure_temp      # CPU temperature (Raspberry Pi)
vcgencmd get_throttled     # Throttling status (Raspberry Pi)
EOF
fi

cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
\`\`\`

### Service Management
\`\`\`bash
systemctl status <service>     # Check service status (safe)
systemctl list-units --state=running  # List running services
journalctl -n 50              # Recent system logs
\`\`\`

### File Operations (safe reads)
\`\`\`bash
ls -la                        # List files
pwd                           # Current directory
cat <file>                    # Read file
grep <pattern> <file>         # Search in file
\`\`\`

## Safety Guidelines

### App-Enforced Safety Rules
The Sushi app enforces these rules regardless of context:
- **BLOCKED**: \`shutdown\`, \`reboot\`, \`poweroff\`, \`halt\`
- **BLOCKED**: \`rm -rf /\`, recursive deletes in system directories
- **BLOCKED**: \`mkfs\`, \`dd if=/dev/\`, disk formatting commands
- **BLOCKED**: Commands that could destroy boot resources

### System-Specific Safety (defined here)
Additional safety rules for this specific system:
- Confirm before restarting services
- Confirm before installing/removing packages
- Confirm before modifying system files
- Confirm before running \`sudo\` commands

### Safe Auto-Execute Commands
These are safe to execute without confirmation:
- Read-only commands (ls, cat, grep, find)
- Status checks (systemctl status, df, free, uptime)
EOF

if [ "$IS_RPI" = true ]; then
    cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
- Raspberry Pi monitoring (vcgencmd measure_temp, vcgencmd get_throttled)
EOF
fi

cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF

## System Purpose

*This section should be edited by the user to describe what this system does.*

Currently: General-purpose Linux system.

TODO: Edit this file to add:
- What services/applications run on this system
- Important directories and their purposes
- Special instructions or notes
- Regular maintenance tasks

## Health Monitoring

Normal operating parameters:
EOF

if [ "$IS_RPI" = true ]; then
    cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
- **Temperature**: 40-60°C normal, >80°C triggers throttling
EOF
fi

cat >> "$SUSHI_CONFIG_DIR/SUSHI.md" <<EOF
- **Memory**: Keep >10% free for stability
- **Disk Space**: Monitor with \`df -h\`, alert if >90% full
- **Load Average**: Healthy when < number of CPU cores

## Conversation Logging

All conversations are logged to: \`~/.sushi_logs/\`
Log format: \`YYYY-MM-DD-HH_MM.log\`

To change log location, edit: \`~/.config/sushi/config.conf\`

## Notes

- This persona was initialized on: $(date '+%Y-%m-%d %H:%M:%S')
- Sushi version: 0.5.0
- This file can be edited to add system-specific information
- The more context provided here, the better the AI can assist

---

*Generated by Sushi AI Persona initialization script*
EOF

# Create system profiler script
cat > "$SUSHI_CONFIG_DIR/scripts/profile_system.sh" <<'PROFILE_EOF'
#!/bin/bash
# System Profiler - Gathers detailed system information

echo "{"

# Operating System
if [ -f /etc/os-release ]; then
    . /etc/os-release
    echo "  \"os_name\": \"$PRETTY_NAME\","
    echo "  \"os_version\": \"$VERSION\","
fi

# Hardware
if [ -f /proc/device-tree/model ]; then
    MODEL=$(cat /proc/device-tree/model | tr -d '\0')
    echo "  \"hardware\": \"$MODEL\","
fi

# CPU
CPUS=$(nproc)
echo "  \"cpu_cores\": $CPUS,"

# Memory
MEM_TOTAL=$(free -h | grep Mem | awk '{print $2}')
echo "  \"memory_total\": \"$MEM_TOTAL\","

# Disk
DISK_TOTAL=$(df -h / | tail -1 | awk '{print $2}')
DISK_USED=$(df -h / | tail -1 | awk '{print $3}')
DISK_AVAIL=$(df -h / | tail -1 | awk '{print $4}')
echo "  \"disk_total\": \"$DISK_TOTAL\","
echo "  \"disk_used\": \"$DISK_USED\","
echo "  \"disk_available\": \"$DISK_AVAIL\","

# Package Manager
if command -v apt >/dev/null 2>&1; then
    PKG_MGR="apt"
elif command -v dnf >/dev/null 2>&1; then
    PKG_MGR="dnf"
elif command -v yum >/dev/null 2>&1; then
    PKG_MGR="yum"
elif command -v pacman >/dev/null 2>&1; then
    PKG_MGR="pacman"
else
    PKG_MGR="unknown"
fi
echo "  \"package_manager\": \"$PKG_MGR\","

# Running Services (systemd)
if command -v systemctl >/dev/null 2>&1; then
    SERVICES=$(systemctl list-units --type=service --state=running --no-legend | wc -l)
    echo "  \"running_services\": $SERVICES,"
fi

# Python version
if command -v python3 >/dev/null 2>&1; then
    PYTHON_VER=$(python3 --version 2>&1 | awk '{print $2}')
    echo "  \"python_version\": \"$PYTHON_VER\","
fi

# Raspberry Pi specific
if [ -f /proc/device-tree/model ]; then
    if grep -qi "raspberry pi" /proc/device-tree/model; then
        if command -v vcgencmd >/dev/null 2>&1; then
            TEMP=$(vcgencmd measure_temp | cut -d= -f2)
            echo "  \"temperature\": \"$TEMP\","
            echo "  \"is_raspberry_pi\": true,"
        fi
    fi
fi

echo "  \"hostname\": \"$(hostname)\","
echo "  \"kernel\": \"$(uname -r)\","
echo "  \"uptime\": \"$(uptime -p)\""
echo "}"
PROFILE_EOF

chmod +x "$SUSHI_CONFIG_DIR/scripts/profile_system.sh"

# Create config file for custom log location
cat > "$SUSHI_CONFIG_DIR/config.conf" <<'CONFIG_EOF'
# Sushi AI Persona Configuration
# Edit this file to customize behavior

[logging]
# Log directory (default: ~/.sushi_logs)
log_dir = ~/.sushi_logs

# Log format: YYYY-MM-DD-HH_MM.log
# Logs include conversation history and command execution

[safety]
# Additional blocked commands (app already blocks dangerous patterns)
# Example: custom_blocked = systemctl reboot, apt autoremove

[persona]
# Override system identity name (default: hostname)
# name = My Home Server
CONFIG_EOF

echo ""
echo "✓ Sushi AI Persona initialized successfully!"
echo ""
echo "Installation details:"
echo "  • Configuration: $SUSHI_CONFIG_DIR"
echo "  • Persona file: $SUSHI_CONFIG_DIR/SUSHI.md"
echo "  • Logs directory: $SUSHI_LOGS_DIR"
echo ""
echo "Next steps:"
echo "  1. Edit $SUSHI_CONFIG_DIR/SUSHI.md to add system-specific information"
echo "  2. Describe what this system does in the 'System Purpose' section"
echo "  3. Add any important services, directories, or notes"
echo ""
echo "The AI will use this information to better assist you!"
