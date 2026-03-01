package net.hlan.sushi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { GeminiSettings(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val sshSettings by lazy { SshSettings(this) }
    // Keep this list in sync with app/src/main/res/xml/locales_config.xml.
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
    private var selectedSettingsTab = SettingsTab.GENERAL
    private var lastConnectionDiagnostics: String = ""

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
        setupTabButtons()

        val enabled = settings.isEnabled()
        binding.geminiEnabledSwitch.isChecked = enabled
        binding.apiKeyInput.setText(settings.getApiKey())
        binding.apiKeyLayout.isEnabled = enabled

        sshSettings.migrateOldSettingsIfNeeded()

        binding.manageHostsButton.setOnClickListener {
            startActivity(Intent(this, HostsActivity::class.java))
        }

        binding.quickAddHostButton.setOnClickListener {
            startActivity(Intent(this, HostEditActivity::class.java))
        }

        binding.manageKeysButton.setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }

        binding.quickGenerateKeyButton.setOnClickListener {
            val hasKey = sshSettings.getPrivateKey().orEmpty().isNotBlank()
            val intent = Intent(this, KeysActivity::class.java)
            if (!hasKey) {
                intent.putExtra(KeysActivity.EXTRA_AUTO_GENERATE, true)
            }
            startActivity(intent)
        }

        binding.managePlaysButton.setOnClickListener {
            startActivity(Intent(this, PlaysActivity::class.java))
        }

        binding.managePhrasesButton.setOnClickListener {
            startActivity(Intent(this, PhrasesActivity::class.java))
        }

        binding.testConnectionButton.setOnClickListener {
            testActiveConnection()
        }

        binding.copyConnectionDiagnosticsButton.setOnClickListener {
            copyConnectionDiagnostics()
        }

        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.geminiEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.apiKeyLayout.isEnabled = isChecked
            refreshGeneralSummary()
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

            refreshGeneralSummary()

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        refreshDriveState()
        refreshGeneralSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshDriveState()
        refreshGeneralSummary()
    }

    private fun setupTabButtons() {
        binding.settingsTabGeneral.setOnClickListener {
            switchToTab(SettingsTab.GENERAL)
        }
        binding.settingsTabGemini.setOnClickListener {
            switchToTab(SettingsTab.GEMINI)
        }
        binding.settingsTabDrive.setOnClickListener {
            switchToTab(SettingsTab.DRIVE)
        }
        switchToTab(SettingsTab.GENERAL)
    }

    private fun switchToTab(tab: SettingsTab) {
        selectedSettingsTab = tab
        binding.settingsGeneralSection.visibility = if (tab == SettingsTab.GENERAL) View.VISIBLE else View.GONE
        binding.settingsGeminiSection.visibility = if (tab == SettingsTab.GEMINI) View.VISIBLE else View.GONE
        binding.settingsDriveSection.visibility = if (tab == SettingsTab.DRIVE) View.VISIBLE else View.GONE

        styleTabButton(binding.settingsTabGeneral, tab == SettingsTab.GENERAL)
        styleTabButton(binding.settingsTabGemini, tab == SettingsTab.GEMINI)
        styleTabButton(binding.settingsTabDrive, tab == SettingsTab.DRIVE)
    }

    private fun styleTabButton(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
        button.alpha = if (selected) 1f else 0.6f
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

        refreshGeneralSummary()
    }

    private fun refreshGeneralSummary() {
        val activeConfig = sshSettings.getConfigOrNull()
        binding.summaryHostValue.text = activeConfig?.displayTarget()
            ?: getString(R.string.settings_summary_host_none)

        val hasKey = sshSettings.getPrivateKey().orEmpty().isNotBlank()
        binding.quickGenerateKeyButton.text = if (hasKey) {
            getString(R.string.action_keys_short)
        } else {
            getString(R.string.action_generate_key_quick)
        }

        binding.summaryAuthValue.text = authModeLabel(activeConfig)

        val geminiEnabled = binding.geminiEnabledSwitch.isChecked
        val geminiApiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        binding.summaryGeminiValue.text = when {
            !geminiEnabled -> getString(R.string.settings_summary_gemini_disabled)
            geminiApiKey.isBlank() -> getString(R.string.settings_summary_gemini_missing_key)
            else -> getString(R.string.settings_summary_gemini_enabled)
        }

        val account = driveAuthManager.getSignedInAccount()
        binding.summaryDriveValue.text = when {
            account == null -> getString(R.string.settings_summary_drive_signed_out)
            driveLogSettings.isAlwaysSaveEnabled() -> getString(R.string.settings_summary_drive_signed_in_autosave)
            else -> getString(R.string.settings_summary_drive_signed_in)
        }
    }

    private fun testActiveConnection() {
        val config = sshSettings.getConfigOrNull()
        if (config == null) {
            Toast.makeText(this, getString(R.string.ssh_missing_config), Toast.LENGTH_SHORT).show()
            return
        }

        binding.testConnectionButton.isEnabled = false
        binding.testConnectionButton.text = getString(R.string.action_test_connection_running)
        binding.testConnectionResultText.text = getString(R.string.session_status_connecting)

        lifecycleScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val outputLines = mutableListOf<String>()
            val client = SshClient(config)
            val connectResult = client.connect(onLine = { line ->
                synchronized(outputLines) {
                    if (outputLines.size < 6) {
                        outputLines.add(line)
                    }
                }
            })
            val elapsed = (System.currentTimeMillis() - startedAt).toInt()

            val diagnostics = buildString {
                appendLine("target=${config.displayTarget()}")
                appendLine("auth=${authModeLabel(config)}")
                appendLine("elapsed_ms=$elapsed")
                appendLine("success=${connectResult.success}")
                appendLine("message=${connectResult.message}")
                val sample = synchronized(outputLines) { outputLines.joinToString(" | ") }
                if (sample.isNotBlank()) {
                    appendLine("sample_output=$sample")
                }
            }.trim()

            if (connectResult.success) {
                client.sendCommand("echo sushi_connection_check")
            }
            client.disconnect()

            withContext(Dispatchers.Main) {
                lastConnectionDiagnostics = diagnostics
                binding.copyConnectionDiagnosticsButton.visibility = View.VISIBLE
                binding.testConnectionButton.isEnabled = true
                binding.testConnectionButton.text = getString(R.string.action_test_connection)
                if (connectResult.success) {
                    binding.testConnectionResultText.text =
                        getString(R.string.test_connection_success, elapsed)
                } else {
                    binding.testConnectionResultText.text =
                        getString(R.string.test_connection_failure, elapsed, connectResult.message)
                }
            }
        }.start()
    }

    private fun copyConnectionDiagnostics() {
        if (lastConnectionDiagnostics.isBlank()) {
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.clipboard_connection_diagnostics_label),
                lastConnectionDiagnostics
            )
        )
        Toast.makeText(this, getString(R.string.connection_diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    private fun authModeLabel(config: SshConnectionConfig?): String {
        val hasPrivateKey = config?.privateKey.orEmpty().isNotBlank()
        val hasPassword = config?.password.orEmpty().isNotBlank()
        return when {
            config == null -> getString(R.string.settings_summary_auth_none)
            hasPrivateKey && hasPassword -> getString(R.string.settings_summary_auth_key_password)
            hasPrivateKey -> getString(R.string.settings_summary_auth_key)
            hasPassword -> getString(R.string.settings_summary_auth_password)
            else -> getString(R.string.settings_summary_auth_none)
        }
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22
    }

    private enum class SettingsTab {
        GENERAL,
        GEMINI,
        DRIVE
    }

    private data class LanguageOption(
        val tag: String,
        val label: String
    )
}
