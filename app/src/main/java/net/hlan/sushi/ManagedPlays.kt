package net.hlan.sushi

import android.content.Context

object ManagedPlays {
    const val PLAY_INSTALL_SSH_KEY = "Install SSH Key"
    const val PLAY_REMOVE_SUSHI_KEYS = "Remove Sushi SSH Keys"
    const val PLAY_REBOOT_HOST = "Reboot Host"
    const val PLAY_CHANGE_USER_PASSWORD = "Change User Password"

    fun ensure(context: Context, publicKey: String?) {
        val db = PlayDatabaseHelper.getInstance(context)

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
}
