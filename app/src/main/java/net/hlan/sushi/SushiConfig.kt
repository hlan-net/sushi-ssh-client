package net.hlan.sushi

/**
 * Pure helpers for reading the target-side Sushi configuration (`~/.config/sushi/config.conf`)
 * and for building shell-safe remote paths.
 *
 * Kept free of Android and SSH dependencies so the parsing and quoting rules can be unit-tested
 * directly. [ConversationManager] uses these when resolving the conversation log location.
 */
object SushiConfig {

    /** Directory conversation logs are written to when `config.conf` does not override it. */
    const val DEFAULT_LOG_DIR = "~/.sushi_logs"

    private val LOG_DIR_REGEX = Regex("^log_dir\\s*=\\s*(.+)$")

    /**
     * Extract the `log_dir` value from `config.conf` contents. Commented lines (`#` or `;`) are
     * ignored and surrounding quotes are stripped. Returns null when the key is absent or empty,
     * so callers can fall back to [DEFAULT_LOG_DIR].
     */
    fun parseLogDir(configContent: String): String? {
        for (raw in configContent.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val match = LOG_DIR_REGEX.find(line) ?: continue
            val value = match.groupValues[1].trim().trim('"', '\'')
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /**
     * Quote a remote path for safe use inside a shell command. A leading `~` (or `~/`) is left
     * unquoted so the shell still expands it to the home directory; the remainder — and any
     * fully-qualified path — is single-quoted to guard against spaces and special characters.
     */
    fun shellQuotePath(path: String): String {
        return if (path == "~" || path.startsWith("~/")) {
            val rest = path.removePrefix("~")
            if (rest.isEmpty()) "~" else "~'${rest.replace("'", "'\\''")}'"
        } else {
            "'${path.replace("'", "'\\''")}'"
        }
    }
}
