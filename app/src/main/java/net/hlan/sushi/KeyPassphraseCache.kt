package net.hlan.sushi

import android.content.Context

/**
 * Thin wrapper over the "remember passphrase" setting for the app's single global SSH key pair.
 * Kept separate from [SshSettings] so callers that only need passphrase caching (e.g.
 * [DialogUserInfo]) don't need to know it's backed by the same store as the key material.
 */
class KeyPassphraseCache(context: Context) {
    private val sshSettings = SshSettings(context)

    fun get(): String? = sshSettings.getKeyPassphrase()

    fun set(passphrase: String?) {
        sshSettings.setKeyPassphrase(passphrase)
    }
}
