package net.hlan.sushi

import java.util.Locale

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
    private val SECTION_REGEX = Regex("^\\[(.+)]$")

    /**
     * Extract the `log_dir` value from `config.conf` contents. The file is INI-style
     * (see `ManagedPlays.buildInitPersonaScript`), so `log_dir` is only honoured in the
     * `[logging]` section or before any section header — this avoids picking up a same-named
     * key that a future section might introduce. Commented lines (`#` or `;`) are ignored and
     * surrounding quotes stripped. Returns null when the key is absent or empty so callers can
     * fall back to [DEFAULT_LOG_DIR].
     */
    fun parseLogDir(configContent: String): String? {
        var section: String? = null
        for (raw in configContent.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            val sectionMatch = SECTION_REGEX.find(line)
            if (sectionMatch != null) {
                section = sectionMatch.groupValues[1].trim().lowercase(Locale.ROOT)
                continue
            }

            if (section != null && section != "logging") continue

            val match = LOG_DIR_REGEX.find(line) ?: continue
            val value = match.groupValues[1].trim().trim('"', '\'')
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /**
     * Quote a remote path for safe use inside a shell command. A leading `~/` (or a bare `~`)
     * is left unquoted — including the slash right after it — so the shell still performs tilde
     * expansion (which only happens when the character following `~` up to the first slash is
     * unquoted); the remainder, and any fully-qualified path, is single-quoted to guard against
     * spaces and special characters.
     */
    fun shellQuotePath(path: String): String {
        return when {
            path == "~" -> "~"
            path.startsWith("~/") -> "~/${singleQuote(path.removePrefix("~/"))}"
            else -> singleQuote(path)
        }
    }

    private fun singleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
