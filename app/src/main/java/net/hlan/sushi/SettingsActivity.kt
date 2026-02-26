package net.hlan.sushi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import net.hlan.sushi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { GeminiSettings(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val sshSettings by lazy { SshSettings(this) }
    private val languageOptions by lazy {
        listOf(
            LanguageOption("", getString(R.string.language_system_default)),
            LanguageOption("en", getString(R.string.language_english)),
            LanguageOption("fi", getString(R.string.language_finnish)),
            LanguageOption("sv", getString(R.string.language_swedish)),
            LanguageOption("de", getString(R.string.language_german)),
            LanguageOption("es", getString(R.string.language_spanish))
        )
    }

    private val driveSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, getString(R.string.drive_sign_in_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val account = driveAuthManager.handleSignInResult(result.data)
        if (account == null) {
            Toast.makeText(this, getString(R.string.drive_sign_in_failed), Toast.LENGTH_SHORT).show()
        }
        refreshDriveState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguagePicker()

        val enabled = settings.isEnabled()
        binding.geminiEnabledSwitch.isChecked = enabled
        binding.apiKeyInput.setText(settings.getApiKey())
        binding.apiKeyLayout.isEnabled = enabled

        binding.sshHostInput.setText(sshSettings.getHost())
        binding.sshPortInput.setText(sshSettings.getPort().toString())
        binding.sshUsernameInput.setText(sshSettings.getUsername())
        binding.sshPasswordInput.setText(sshSettings.getPassword())

        binding.manageKeysButton.setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }

        binding.geminiEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.apiKeyLayout.isEnabled = isChecked
        }

        binding.driveAlwaysSaveSwitch.isChecked = driveLogSettings.isAlwaysSaveEnabled()

        binding.driveSignInButton.setOnClickListener {
            driveSignInLauncher.launch(driveAuthManager.getSignInIntent())
        }

        binding.driveSignOutButton.setOnClickListener {
            driveAuthManager.signOut {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.drive_sign_out_done), Toast.LENGTH_SHORT).show()
                    refreshDriveState()
                }
            }
        }

        binding.saveButton.setOnClickListener {
            applySelectedLanguage()

            settings.setEnabled(binding.geminiEnabledSwitch.isChecked)
            settings.setApiKey(binding.apiKeyInput.text?.toString()?.trim().orEmpty())
            driveLogSettings.setAlwaysSaveEnabled(binding.driveAlwaysSaveSwitch.isChecked)

            sshSettings.setHost(binding.sshHostInput.text?.toString().orEmpty())
            val portInput = binding.sshPortInput.text?.toString()?.trim().orEmpty()
            val parsedPort = portInput.toIntOrNull()
            val port = if (parsedPort != null && parsedPort in 1..65535) {
                parsedPort
            } else {
                DEFAULT_SSH_PORT
            }
            sshSettings.setPort(port)
            sshSettings.setUsername(binding.sshUsernameInput.text?.toString().orEmpty())
            sshSettings.setPassword(binding.sshPasswordInput.text?.toString().orEmpty())
            if (portInput.isNotBlank() && parsedPort != port) {
                binding.sshPortInput.setText(port.toString())
                Toast.makeText(this, getString(R.string.ssh_invalid_port), Toast.LENGTH_SHORT).show()
            }

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        refreshDriveState()
    }

    private fun setupLanguagePicker() {
        val labels = languageOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.languageInput.setAdapter(adapter)

        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = languageOptions.firstOrNull { it.tag == currentTag }
            ?: languageOptions.first()
        binding.languageInput.setText(selected.label, false)
    }

    private fun applySelectedLanguage() {
        val selectedLabel = binding.languageInput.text?.toString().orEmpty()
        val selected = languageOptions.firstOrNull { it.label == selectedLabel }
            ?: languageOptions.first()
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()

        if (selected.tag == currentTag) {
            return
        }

        val locales = if (selected.tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(selected.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun refreshDriveState() {
        val account = driveAuthManager.getSignedInAccount()
        if (account == null) {
            binding.driveStatusText.text = getString(R.string.drive_status_signed_out)
            binding.driveSignOutButton.visibility = android.view.View.GONE
            binding.driveSignInButton.visibility = android.view.View.VISIBLE
            binding.driveAlwaysSaveSwitch.isEnabled = false
        } else {
            binding.driveStatusText.text = getString(
                R.string.drive_status_signed_in,
                account.email ?: account.displayName ?: ""
            )
            binding.driveSignOutButton.visibility = android.view.View.VISIBLE
            binding.driveSignInButton.visibility = android.view.View.GONE
            binding.driveAlwaysSaveSwitch.isEnabled = true
        }
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22
    }

    private data class LanguageOption(
        val tag: String,
        val label: String
    )
}
