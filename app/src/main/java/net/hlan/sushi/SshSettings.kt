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
    /**
     * The saved hosts, or an empty list when the blob does not parse. A parse failure flags the
     * corruption once ([hasCorruptHosts]) so Hosts can offer [restoreHostsFromBackup]; it stays
     * flagged until the user answers with Restore or Dismiss. A good read on an install from
     * before the backup existed seeds it, so the first corruption after an upgrade is covered too.
     */
    fun getHosts(): List<SshConnectionConfig> {
        val json = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        val hosts = try {
            hostListAdapter.fromJson(json)
        } catch (e: Exception) {
            if (!hasCorruptHosts()) {
                Log.w(TAG, "Saved hosts JSON is corrupt; treating as empty rather than crashing", e)
                prefs.edit().putBoolean(KEY_HOSTS_CORRUPT, true).apply()
            }
            return emptyList()
        } ?: return emptyList()
        if (!prefs.contains(KEY_HOSTS_JSON_BACKUP) && !hasCorruptHosts()) {
            prefs.edit().putString(KEY_HOSTS_JSON_BACKUP, json).apply()
        }
        return hosts
    }

    fun saveHost(config: SshConnectionConfig) {
        val currentHosts = getHosts().toMutableList()
        val index = currentHosts.indexOfFirst { it.id == config.id }
        if (index != -1) {
            currentHosts[index] = config
        } else {
            currentHosts.add(config)
        }
        writeHosts(currentHosts)
    }

    fun deleteHost(id: String) {
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
            writeHosts(updatedHosts)
        }
        if (getActiveHostId() == id) {
            setActiveHostId(null)
        }
    }

    /**
     * Writes [hosts] and, unless a corruption is unanswered, makes the same JSON the backup: the
     * backup is the last list written successfully, so Restore never lags a write behind. While
     * [hasCorruptHosts] is set the backup holds what Restore brings back and must not move —
     * the callers read [getHosts] first, which is what sets the flag before this runs.
     */
    private fun writeHosts(hosts: List<SshConnectionConfig>) {
        val json = hostListAdapter.toJson(hosts)
        val editor = prefs.edit().putString(KEY_HOSTS_JSON, json)
        if (!hasCorruptHosts()) {
            editor.putString(KEY_HOSTS_JSON_BACKUP, json)
        }
        editor.apply()
    }

    private fun parseHosts(json: String?): List<SshConnectionConfig>? =
        json?.let { runCatching { hostListAdapter.fromJson(it) }.getOrNull() }

    /** True from the first failed parse in [getHosts] until [restoreHostsFromBackup] or [dismissHostsCorruptionNotice]. */
    fun hasCorruptHosts(): Boolean = prefs.getBoolean(KEY_HOSTS_CORRUPT, false)

    /** Whether [restoreHostsFromBackup] has a parseable list to restore. */
    fun hasHostsBackup(): Boolean = parseHosts(prefs.getString(KEY_HOSTS_JSON_BACKUP, null)) != null

    /**
     * Restores the backup, keeping hosts added since the corruption: a host in both keeps the
     * current copy, which is the newer one, and a local shell the seed added since is dropped
     * when the backup has its own. False if there is no usable backup.
     */
    fun restoreHostsFromBackup(): Boolean {
        val backup = parseHosts(prefs.getString(KEY_HOSTS_JSON_BACKUP, null)) ?: return false
        val current = parseHosts(prefs.getString(KEY_HOSTS_JSON, null)).orEmpty()
        val backupIds = backup.map { it.id }.toSet()
        val backupHasLocal = backup.any { it.kind == HostKind.LOCAL }
        val merged = backup.map { saved -> current.firstOrNull { it.id == saved.id } ?: saved } +
            current.filter { it.id !in backupIds && !(it.kind == HostKind.LOCAL && backupHasLocal) }
        val json = hostListAdapter.toJson(merged)
        prefs.edit()
            .putString(KEY_HOSTS_JSON, json)
            .putString(KEY_HOSTS_JSON_BACKUP, json)
            .putBoolean(KEY_HOSTS_CORRUPT, false)
            .apply()
        return true
    }

    /**
     * Answers the notice without restoring. A blob that still does not parse is replaced with an
     * empty list, or the next [getHosts] would flag it — and show the notice — again. The backup
     * is left alone until the next write replaces it.
     */
    fun dismissHostsCorruptionNotice() {
        val editor = prefs.edit().putBoolean(KEY_HOSTS_CORRUPT, false)
        val live = prefs.getString(KEY_HOSTS_JSON, null)
        if (live != null && parseHosts(live) == null) {
            editor.putString(KEY_HOSTS_JSON, hostListAdapter.toJson(emptyList()))
        }
        editor.apply()
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
        // A blob that does not parse is left as it is until the user answers the corruption
        // notice: seeding would write over it before they have seen anything.
        val json = prefs.getString(KEY_HOSTS_JSON, null)
        if (json != null && parseHosts(json) == null) {
            getHosts() // flags it
            return
        }
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
