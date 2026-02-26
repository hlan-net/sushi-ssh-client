package net.hlan.sushi

import android.content.Context

class SshSettings(context: Context) {
    private val prefs = SecurePrefs.get(context)

    fun getHost(): String = prefs.getString(KEY_HOST, "") ?: ""

    fun setHost(host: String) {
        prefs.edit().putString(KEY_HOST, host.trim()).apply()
    }

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun setUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username.trim()).apply()
    }

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun setPassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun getPrivateKey(): String? = prefs.getString(KEY_PRIVATE_KEY, null)

    fun setPrivateKey(privateKey: String?) {
        if (privateKey == null) {
            prefs.edit().remove(KEY_PRIVATE_KEY).apply()
        } else {
            prefs.edit().putString(KEY_PRIVATE_KEY, privateKey).apply()
        }
    }

    fun getPublicKey(): String? = prefs.getString(KEY_PUBLIC_KEY, null)

    fun setPublicKey(publicKey: String?) {
        if (publicKey == null) {
            prefs.edit().remove(KEY_PUBLIC_KEY).apply()
        } else {
            prefs.edit().putString(KEY_PUBLIC_KEY, publicKey).apply()
        }
    }

    fun isConfigured(): Boolean {
        // Now it's either password OR private key that's needed
        val hasCredentials = getPassword().isNotBlank() || !getPrivateKey().isNullOrBlank()
        return getHost().isNotBlank() && getUsername().isNotBlank() && hasCredentials
    }

    fun getConfigOrNull(): SshConnectionConfig? {
        if (!isConfigured()) {
            return null
        }
        return SshConnectionConfig(
            host = getHost(),
            port = getPort(),
            username = getUsername(),
            password = getPassword(),
            privateKey = getPrivateKey()
        )
    }

    companion object {
        private const val DEFAULT_PORT = 22
        private const val KEY_HOST = "ssh_host"
        private const val KEY_PORT = "ssh_port"
        private const val KEY_USERNAME = "ssh_username"
        private const val KEY_PASSWORD = "ssh_password"
        private const val KEY_PRIVATE_KEY = "ssh_private_key"
        private const val KEY_PUBLIC_KEY = "ssh_public_key"
    }
}
