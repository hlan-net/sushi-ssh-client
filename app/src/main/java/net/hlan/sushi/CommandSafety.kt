package net.hlan.sushi

import java.util.Locale

/**
 * Safety classifier for shell commands.
 * 
 * Classifies commands into three categories:
 * - SAFE: Can be auto-executed (read-only operations)
 * - CONFIRM: Requires user confirmation (write operations, service restarts)
 * - BLOCKED: Never allowed (system shutdown, destructive operations)
 */
object CommandSafety {

    enum class SafetyLevel {
        SAFE,       // Auto-execute
        CONFIRM,    // Ask user first
        BLOCKED     // Never allow
    }

    /**
     * Classify a command's safety level.
     * 
     * @param command The shell command to classify
     * @return SafetyLevel indicating whether the command is safe, needs confirmation, or is blocked
     */
    fun classify(command: String): SafetyLevel {
        val normalized = command.trim().lowercase(Locale.ROOT)

        // Check blocked patterns first (highest priority)
        if (isBlocked(normalized)) {
            return SafetyLevel.BLOCKED
        }

        // Check safe patterns
        if (isSafe(normalized)) {
            return SafetyLevel.SAFE
        }

        // Default to CONFIRM for anything not explicitly safe or blocked
        return SafetyLevel.CONFIRM
    }

    /**
     * Check if a command matches blocked patterns.
     * These commands are NEVER allowed by the app.
     */
    private fun isBlocked(command: String): Boolean {
        val blockedPatterns = listOf(
            // System shutdown/reboot
            "shutdown",
            "reboot",
            "poweroff",
            "halt",
            "init 0",
            "init 6",
            "systemctl reboot",
            "systemctl poweroff",
            "systemctl halt",
            
            // Dangerous file operations
            "rm -rf /",
            "rm -rf /*",
            "rm -r /",
            "rm --recursive /",
            
            // Disk operations
            "mkfs",
            "mkfs.",
            "dd if=/dev/",
            "dd if=/dev/zero",
            "dd if=/dev/random",
            "fdisk",
            "parted",
            "gdisk",
            
            // Boot/system critical
            "rm -rf /boot",
            "rm -rf /etc",
            "rm -rf /bin",
            "rm -rf /sbin",
            "rm -rf /lib",
            "rm -rf /usr",
            
            // Fork bombs and malicious patterns
            ":(){ :|:& };:",
            "while true; do; done",
            
            // Package manager destructive operations
            "apt-get autoremove --purge",
            "dnf autoremove",
            "yum autoremove"
        )

        return blockedPatterns.any { pattern ->
            command.contains(pattern)
        }
    }

    /**
     * Check if a command matches safe patterns.
     * These commands can be auto-executed without confirmation.
     */
    private fun isSafe(command: String): Boolean {
        // Extract the first word (command name)
        val firstWord = command.split(Regex("\\s+")).firstOrNull() ?: return false

        // Safe commands (read-only operations)
        val safeCommands = setOf(
            "ls", "ll", "dir",
            "pwd",
            "cat", "less", "more", "head", "tail",
            "grep", "egrep", "fgrep",
            "find",
            "which", "whereis", "whatis", "man",
            "echo",
            "date",
            "uptime",
            "whoami", "id",
            "hostname",
            "uname",
            "df", "du",
            "free",
            "top", "htop", "ps",
            "netstat", "ss", "ip",
            "ifconfig", "iwconfig",
            "ping", "traceroute",
            "nslookup", "dig", "host",
            "file",
            "stat",
            "wc",
            "sort", "uniq",
            "diff",
            "env", "printenv",
            "history"
        )

        if (safeCommands.contains(firstWord)) {
            return true
        }

        // Safe command patterns
        val safePatterns = listOf(
            // systemctl status (but not start/stop/restart)
            Regex("^systemctl\\s+status"),
            Regex("^systemctl\\s+list-units"),
            Regex("^systemctl\\s+is-active"),
            Regex("^systemctl\\s+is-enabled"),
            
            // journalctl (read logs)
            Regex("^journalctl"),
            
            // Raspberry Pi monitoring commands
            Regex("^vcgencmd\\s+measure_temp"),
            Regex("^vcgencmd\\s+measure_volts"),
            Regex("^vcgencmd\\s+get_throttled"),
            Regex("^vcgencmd\\s+get_mem"),
            Regex("^pinout"),
            Regex("^gpio\\s+readall"),
            Regex("^gpio\\s+read"),
            
            // Package manager queries (not install/remove)
            Regex("^apt\\s+list"),
            Regex("^apt\\s+search"),
            Regex("^apt\\s+show"),
            Regex("^apt-cache"),
            Regex("^dpkg\\s+-l"),
            Regex("^dpkg\\s+--list"),
            Regex("^rpm\\s+-q"),
            Regex("^yum\\s+list"),
            Regex("^dnf\\s+list"),
            
            // Docker read-only
            Regex("^docker\\s+ps"),
            Regex("^docker\\s+images"),
            Regex("^docker\\s+logs"),
            Regex("^docker\\s+inspect")
        )

        return safePatterns.any { pattern -> pattern.matches(command) }
    }

    /**
     * Get a human-readable explanation of why a command was classified as it was.
     */
    fun explainClassification(command: String): String {
        val level = classify(command)
        return when (level) {
            SafetyLevel.SAFE -> "This is a safe read-only command that can be executed automatically."
            SafetyLevel.CONFIRM -> "This command may modify the system and requires your confirmation."
            SafetyLevel.BLOCKED -> "This command is blocked for safety. It could damage the system or cause data loss."
        }
    }
}
