package net.hlan.sushi

import android.content.Context

/**
 * Short human labels for saved hosts, used in the conversation status line and in command
 * history rows where the full [SshConnectionConfig.displayTarget] would be too long to scan.
 *
 * Lives on the Android side rather than on [SshConnectionConfig] so the fallback name for a
 * local shell comes from `strings.xml` and follows the app's language, instead of being an
 * English literal baked into the model.
 */
object HostLabels {

    fun shortLabel(context: Context, config: SshConnectionConfig): String = when {
        config.kind == HostKind.LOCAL ->
            config.alias.ifBlank { context.getString(R.string.local_shell_default_alias) }
        config.alias.isNotBlank() -> config.alias
        config.username.isNotBlank() -> "${config.username}@${config.host}"
        else -> config.host
    }
}
