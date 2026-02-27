package net.hlan.sushi

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityHostEditBinding

class HostEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostEditBinding
    private val sshSettings by lazy { SshSettings(this) }
    private var hostId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostId = intent.getStringExtra(EXTRA_HOST_ID)
        val isEditMode = hostId != null

        if (isEditMode) {
            binding.editHostTitle.text = getString(R.string.edit_host_title)
            binding.deleteButton.visibility = View.VISIBLE
            loadHostData(hostId!!)
        } else {
            binding.editHostTitle.text = getString(R.string.add_host_title)
            binding.deleteButton.visibility = View.GONE
            binding.sshPortInput.setText("22")
        }

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

        val config = SshConnectionConfig(
            id = hostId ?: java.util.UUID.randomUUID().toString(),
            alias = binding.hostAliasInput.text?.toString()?.trim().orEmpty(),
            host = host,
            port = port,
            username = username,
            password = binding.sshPasswordInput.text?.toString().orEmpty()
        )

        sshSettings.saveHost(config)
        
        // Auto-select if it's the first host or just created
        if (sshSettings.getActiveHostId() == null) {
            sshSettings.setActiveHostId(config.id)
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }
}