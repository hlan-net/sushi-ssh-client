package net.hlan.sushi

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityHostEditBinding

class HostEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostEditBinding
    private val sshSettings by lazy { SshSettings(this) }
    private var hostId: String? = null
    private var currentKind: HostKind = HostKind.SSH
    private var jumpOptions: List<SshConnectionConfig> = emptyList()
    private var selectedJumpHostId: String? = null
    private var authPreferenceOptions: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostId = intent.getStringExtra(EXTRA_HOST_ID)
        val isEditMode = hostId != null

        setupAuthPreferenceOptions()
        setupJumpOptions()

        if (isEditMode) {
            binding.editHostTitle.text = getString(R.string.edit_host_title)
            loadHostData(hostId!!)
        } else {
            binding.editHostTitle.text = getString(R.string.add_host_title)
            binding.deleteButton.visibility = View.GONE
            binding.sshPortInput.setText("22")
            binding.authPreferenceInput.setText(getString(R.string.auth_method_auto), false)
        }

        binding.jumpEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateJumpSectionVisibility(isChecked)
        }
        updateJumpSectionVisibility(binding.jumpEnabledSwitch.isChecked)

        binding.saveButton.setOnClickListener {
            saveHost()
        }

        binding.deleteButton.setOnClickListener {
            hostId?.let { id ->
                sshSettings.deleteHost(id)
                Toast.makeText(this, getString(R.string.action_delete), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadHostData(id: String) {
        val host = sshSettings.getHosts().find { it.id == id }
        if (host == null) {
            finish()
            return
        }

        currentKind = host.kind
        binding.hostAliasInput.setText(host.alias)

        if (host.kind == HostKind.LOCAL) {
            applySshFieldVisibility(visible = false)
            binding.deleteButton.visibility = View.GONE
        } else {
            applySshFieldVisibility(visible = true)
            binding.deleteButton.visibility = View.VISIBLE
            binding.sshHostInput.setText(host.host)
            binding.sshPortInput.setText(host.port.toString())
            binding.sshUsernameInput.setText(host.username)
            binding.sshPasswordInput.setText(host.password)
            binding.authPreferenceInput.setText(authLabelForValue(host.authPreference), false)

            val jumpHostId = host.jumpHostId
            if (!jumpHostId.isNullOrBlank()) {
                val index = jumpOptions.indexOfFirst { option -> option.id == jumpHostId }
                if (index >= 0) {
                    selectedJumpHostId = jumpOptions[index].id
                    binding.jumpServerInput.setText(buildJumpLabel(jumpOptions[index]), false)
                }
            }
            binding.jumpEnabledSwitch.isChecked = host.jumpEnabled && selectedJumpHostId != null
            updateJumpSectionVisibility(binding.jumpEnabledSwitch.isChecked)
        }
    }

    private fun applySshFieldVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.sshHostLayout.visibility = v
        binding.sshPortLayout.visibility = v
        binding.sshUsernameLayout.visibility = v
        binding.sshPasswordLayout.visibility = v
        binding.authPreferenceLayout.visibility = v
        binding.jumpEnabledSwitch.visibility = v
        binding.jumpServerLayout.visibility = View.GONE
        binding.jumpHelperText.visibility = View.GONE
    }

    private fun saveHost() {
        val alias = binding.hostAliasInput.text?.toString()?.trim().orEmpty()
        val config = if (currentKind == HostKind.LOCAL) {
            buildLocalConfig(alias)
        } else {
            buildSshConfig(alias) ?: return
        }
        sshSettings.saveHost(config)
        if (sshSettings.getActiveHostId() == null) {
            sshSettings.setActiveHostId(config.id)
        }
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buildLocalConfig(alias: String): SshConnectionConfig =
        SshConnectionConfig(
            kind = HostKind.LOCAL,
            id = hostId ?: java.util.UUID.randomUUID().toString(),
            alias = alias,
            host = "",
            port = 0,
            username = "",
            password = "",
        )

    private fun buildSshConfig(alias: String): SshConnectionConfig? {
        val host = binding.sshHostInput.text?.toString()?.trim().orEmpty()
        val username = binding.sshUsernameInput.text?.toString()?.trim().orEmpty()
        if (host.isBlank() || username.isBlank()) {
            Toast.makeText(this, "Host and Username are required", Toast.LENGTH_SHORT).show()
            return null
        }

        val portInput = binding.sshPortInput.text?.toString()?.trim().orEmpty()
        val parsedPort = portInput.toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            Toast.makeText(this, getString(R.string.ssh_invalid_port), Toast.LENGTH_SHORT).show()
            binding.sshPortInput.setText("22")
            return null
        }

        val jumpEnabled = binding.jumpEnabledSwitch.isChecked && jumpOptions.isNotEmpty()
        if (jumpEnabled && selectedJumpHostId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.jump_required_selection), Toast.LENGTH_SHORT).show()
            return null
        }

        return SshConnectionConfig(
            kind = HostKind.SSH,
            id = hostId ?: java.util.UUID.randomUUID().toString(),
            alias = alias,
            host = host,
            port = parsedPort,
            username = username,
            password = binding.sshPasswordInput.text?.toString().orEmpty(),
            authPreference = authValueFromInput(),
            jumpEnabled = jumpEnabled,
            jumpHostId = if (jumpEnabled) selectedJumpHostId else null,
            jumpHost = "",
            jumpPort = 22,
            jumpUsername = "",
            jumpPassword = ""
        )
    }

    private fun setupAuthPreferenceOptions() {
        authPreferenceOptions = listOf(
            getString(R.string.auth_method_auto) to SshAuthPreference.AUTO.value,
            getString(R.string.auth_method_password) to SshAuthPreference.PASSWORD.value,
            getString(R.string.auth_method_key) to SshAuthPreference.KEY.value
        )
        val labels = authPreferenceOptions.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.authPreferenceInput.setAdapter(adapter)
    }

    private fun authValueFromInput(): String {
        val label = binding.authPreferenceInput.text?.toString().orEmpty()
        return authPreferenceOptions.firstOrNull { it.first == label }?.second
            ?: SshAuthPreference.AUTO.value
    }

    private fun authLabelForValue(value: String?): String {
        return authPreferenceOptions.firstOrNull { it.second == value }?.first
            ?: getString(R.string.auth_method_auto)
    }

    private fun setupJumpOptions() {
        jumpOptions = sshSettings
            .getHosts()
            .filter { host -> host.id != hostId && host.kind == HostKind.SSH }

        val labels = jumpOptions.map { option -> buildJumpLabel(option) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.jumpServerInput.setAdapter(adapter)
        binding.jumpServerInput.setOnItemClickListener { _, _, position, _ ->
            selectedJumpHostId = jumpOptions.getOrNull(position)?.id
        }

        if (jumpOptions.isEmpty()) {
            binding.jumpEnabledSwitch.isEnabled = false
            binding.jumpEnabledSwitch.isChecked = false
            binding.jumpEnabledSwitch.text = getString(R.string.jump_enabled_label_disabled)
            binding.jumpServerLayout.helperText = getString(R.string.jump_no_hosts_available)
        } else {
            binding.jumpEnabledSwitch.isEnabled = true
            binding.jumpEnabledSwitch.text = getString(R.string.jump_enabled_label)
            binding.jumpServerLayout.helperText = null
        }
    }

    private fun updateJumpSectionVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.jumpServerLayout.visibility = visibility
        binding.jumpHelperText.visibility = visibility
        if (!enabled) {
            selectedJumpHostId = null
            binding.jumpServerInput.setText("", false)
        }
    }

    private fun buildJumpLabel(host: SshConnectionConfig): String {
        val left = host.alias.ifBlank { host.host }
        return "$left (${host.username}@${host.host}:${host.port})"
    }

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }
}
