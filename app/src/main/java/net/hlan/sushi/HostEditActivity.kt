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
            binding.deleteButton.visibility = View.VISIBLE
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

        binding.hostAliasInput.setText(host.alias)
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

    private fun saveHost() {
        val host = binding.sshHostInput.text?.toString()?.trim().orEmpty()
        val username = binding.sshUsernameInput.text?.toString()?.trim().orEmpty()
        
        if (host.isBlank() || username.isBlank()) {
            Toast.makeText(this, "Host and Username are required", Toast.LENGTH_SHORT).show()
            return
        }

        val portInput = binding.sshPortInput.text?.toString()?.trim().orEmpty()
        val parsedPort = portInput.toIntOrNull()
        val port = if (parsedPort != null && parsedPort in 1..65535) {
            parsedPort
        } else {
            Toast.makeText(this, getString(R.string.ssh_invalid_port), Toast.LENGTH_SHORT).show()
            binding.sshPortInput.setText("22")
            return
        }

        val jumpEnabled = binding.jumpEnabledSwitch.isChecked && jumpOptions.isNotEmpty()
        if (jumpEnabled && selectedJumpHostId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.jump_required_selection), Toast.LENGTH_SHORT).show()
            return
        }

        val config = SshConnectionConfig(
            id = hostId ?: java.util.UUID.randomUUID().toString(),
            alias = binding.hostAliasInput.text?.toString()?.trim().orEmpty(),
            host = host,
            port = port,
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

        sshSettings.saveHost(config)
        
        // Auto-select if it's the first host or just created
        if (sshSettings.getActiveHostId() == null) {
            sshSettings.setActiveHostId(config.id)
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
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
            .filter { host -> host.id != hostId }

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
