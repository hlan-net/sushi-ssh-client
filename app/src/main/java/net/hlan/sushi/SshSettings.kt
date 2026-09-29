package net.hlan.sushi

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class SshSettings(private val context: Context) {
    private val prefs = SecurePrefs.get(context)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SshConnectionConfig::class.java)
    private val hostListAdapter = moshi.adapter<List<SshConnectionConfig>>(listType)

    // Global Key Pair
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

    // Passphrase for the global key pair above. Only ever written when the user explicitly
    // opts in via a "remember" toggle (KeysActivity generation, or the connect-time prompt);
    // never persisted by default.
    fun getKeyPassphrase(): String? = prefs.getString(KEY_KEY_PASSPHRASE, null)

    fun setKeyPassphrase(passphrase: String?) {
        if (passphrase == null) {
            prefs.edit().remove(KEY_KEY_PASSPHRASE).apply()
        } else {
            prefs.edit().putString(KEY_KEY_PASSPHRASE, passphrase).apply()
        }
    }

    // Host Management
    fun getHosts(): List<SshConnectionConfig> {
        val json = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        return try {
            hostListAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Saved hosts JSON is corrupt; treating as empty rather than crashing", e)
            prefs.edit().putBoolean(KEY_HOSTS_CORRUPT, true).apply()
            emptyList()
        }
    }

    fun saveHost(config: SshConnectionConfig) {
        backupHostsJsonIfValid()
        val currentHosts = getHosts().toMutableList()
        val index = currentHosts.indexOfFirst { it.id == config.id }
        if (index != -1) {
            currentHosts[index] = config
        } else {
            currentHosts.add(config)
        }
        prefs.edit().putString(KEY_HOSTS_JSON, hostListAdapter.toJson(currentHosts)).apply()
    }

    fun deleteHost(id: String) {
        backupHostsJsonIfValid()
        val currentHosts = getHosts().toMutableList()
        val removed = currentHosts.removeAll { it.id == id }
        val updatedHosts = currentHosts.map { host ->
            if (host.jumpHostId == id) {
                host.copy(
                    jumpEnabled = false,
                    jumpHostId = null,
                    jumpHost = "",
                    jumpPort = 22,
                    jumpUsername = "",
                    jumpPassword = "",
                    jumpAuthPreference = SshAuthPreference.AUTO.value
                )
            } else {
                host
            }
        }
        if (removed) {
            prefs.edit().putString(KEY_HOSTS_JSON, hostListAdapter.toJson(updatedHosts)).apply()
        }
        if (getActiveHostId() == id) {
            setActiveHostId(null)
        }
    }

    /**
     * Keeps the last hosts JSON known to parse under [KEY_HOSTS_JSON_BACKUP], called before every
     * write to [KEY_HOSTS_JSON]. A blob that is already corrupt when this runs is left out of the
     * backup — overwriting a still-good older backup with garbage would defeat the point of
     * having one — so whatever the most recent *good* state was stays recoverable.
     */
    private fun backupHostsJsonIfValid() {
        val json = prefs.getString(KEY_HOSTS_JSON, null) ?: return
        if (runCatching { hostListAdapter.fromJson(json) }.getOrNull() == null) {
            return
        }
        prefs.edit().putString(KEY_HOSTS_JSON_BACKUP, json).apply()
    }

    /** True once [getHosts] has hit a parse failure, until [restoreHostsFromBackup] or [dismissHostsCorruptionNotice]. */
    fun hasCorruptHosts(): Boolean = prefs.getBoolean(KEY_HOSTS_CORRUPT, false)

    /** Whether [restoreHostsFromBackup] has a parseable blob to restore. */
    fun hasHostsBackup(): Boolean {
        val backup = prefs.getString(KEY_HOSTS_JSON_BACKUP, null) ?: return false
        return runCatching { hostListAdapter.fromJson(backup) }.getOrNull() != null
    }

    /** Restores the hosts list from [KEY_HOSTS_JSON_BACKUP]. False if there is nothing usable to restore. */
    fun restoreHostsFromBackup(): Boolean {
        val backup = prefs.getString(KEY_HOSTS_JSON_BACKUP, null) ?: return false
        if (runCatching { hostListAdapter.fromJson(backup) }.getOrNull() == null) {
            return false
        }
        prefs.edit()
            .putString(KEY_HOSTS_JSON, backup)
            .putBoolean(KEY_HOSTS_CORRUPT, false)
            .apply()
        return true
    }

    /** Acknowledges the corruption notice without restoring, so it does not keep reappearing. */
    fun dismissHostsCorruptionNotice() {
        prefs.edit().putBoolean(KEY_HOSTS_CORRUPT, false).apply()
    }

    fun getActiveHostId(): String? = prefs.getString(KEY_ACTIVE_HOST_ID, null)

    fun setActiveHostId(id: String?) {
        if (id == null) {
            prefs.edit().remove(KEY_ACTIVE_HOST_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_HOST_ID, id).apply()
        }
    }

    fun getConfigOrNull(): SshConnectionConfig? {
        val activeId = getActiveHostId() ?: return null
        val host = getHosts().find { it.id == activeId } ?: return null
        return resolveJumpServer(host.copy(privateKey = getPrivateKey()))
    }

    fun resolveJumpServer(config: SshConnectionConfig): SshConnectionConfig {
        if (!config.jumpEnabled) {
            return config
        }

        val jumpHostConfig = config.jumpHostId
            ?.let { jumpId -> getHosts().find { host -> host.id == jumpId } }
            ?: return config

        return config.copy(
            jumpHost = jumpHostConfig.host,
            jumpPort = jumpHostConfig.port,
            jumpUsername = jumpHostConfig.username,
            jumpPassword = jumpHostConfig.password,
            jumpAuthPreference = jumpHostConfig.authPreference
        )
    }

    fun seedLocalHostIfMissing() {
        if (getHosts().any { it.kind == HostKind.LOCAL }) return
        val local = SshConnectionConfig(
            kind = HostKind.LOCAL,
            alias = context.getString(R.string.local_shell_default_alias),
            host = "",
            port = 0,
            username = "",
            password = "",
        )
        saveHost(local)
    }

    // Migration function for old settings
    fun migrateOldSettingsIfNeeded() {
        if (prefs.contains("ssh_host") && !prefs.contains(KEY_HOSTS_JSON)) {
            val host = prefs.getString("ssh_host", "") ?: ""
            val port = prefs.getInt("ssh_port", 22)
            val username = prefs.getString("ssh_username", "") ?: ""
            val password = prefs.getString("ssh_password", "") ?: ""
            
            if (host.isNotBlank() && username.isNotBlank()) {
                val config = SshConnectionConfig(
                    alias = "Default Host",
                    host = host,
                    port = port,
                    username = username,
                    password = password
                )
                saveHost(config)
                setActiveHostId(config.id)
            }
            // Clear old keys
            prefs.edit()
                .remove("ssh_host")
                .remove("ssh_port")
                .remove("ssh_username")
                .remove("ssh_password")
                .apply()
        }
    }

    companion object {
        private const val TAG = "SshSettings"
        private const val KEY_PRIVATE_KEY = "ssh_private_key"
        private const val KEY_PUBLIC_KEY = "ssh_public_key"
        private const val KEY_KEY_PASSPHRASE = "ssh_key_passphrase"
        private const val KEY_HOSTS_JSON = "ssh_hosts_json"
        private const val KEY_HOSTS_JSON_BACKUP = "ssh_hosts_json_backup"
        private const val KEY_HOSTS_CORRUPT = "ssh_hosts_corrupt"
        private const val KEY_ACTIVE_HOST_ID = "ssh_active_host_id"
    }
}
