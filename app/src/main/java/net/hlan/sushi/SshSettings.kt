package net.hlan.sushi

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class SshSettings(context: Context) {
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

    // Host Management
    fun getHosts(): List<SshConnectionConfig> {
        val json = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        return try {
            hostListAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHost(config: SshConnectionConfig) {
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
                    jumpPassword = ""
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
            jumpPassword = jumpHostConfig.password
        )
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
        private const val KEY_PRIVATE_KEY = "ssh_private_key"
        private const val KEY_PUBLIC_KEY = "ssh_public_key"
        private const val KEY_HOSTS_JSON = "ssh_hosts_json"
        private const val KEY_ACTIVE_HOST_ID = "ssh_active_host_id"
    }
}
