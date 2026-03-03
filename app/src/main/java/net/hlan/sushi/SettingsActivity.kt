package net.hlan.sushi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivitySettingsBinding
import net.hlan.sushi.databinding.PageSettingsDriveBinding
import net.hlan.sushi.databinding.PageSettingsGeminiBinding
import net.hlan.sushi.databinding.PageSettingsGeneralBinding
import net.hlan.sushi.databinding.PageSettingsSshBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { GeminiSettings(this) }
    private val appThemeSettings by lazy { AppThemeSettings(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val geminiClient by lazy { GeminiClient(this, settings, driveAuthManager) }
    private val sshSettings by lazy { SshSettings(this) }
    private var generalPageBinding: PageSettingsGeneralBinding? = null
    private var sshPageBinding: PageSettingsSshBinding? = null
    private var geminiPageBinding: PageSettingsGeminiBinding? = null
    private var drivePageBinding: PageSettingsDriveBinding? = null
    private var tabMediator: TabLayoutMediator? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

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
    private val themeOptions by lazy {
        listOf(
            ThemeOption(AppThemeSettings.ThemeMode.AUTO, getString(R.string.theme_mode_auto)),
            ThemeOption(AppThemeSettings.ThemeMode.LIGHT, getString(R.string.theme_mode_light)),
            ThemeOption(AppThemeSettings.ThemeMode.DARK, getString(R.string.theme_mode_dark))
        )
    }
    private var pendingApiKey: String = ""
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

        pendingApiKey = savedInstanceState?.getString(KEY_PENDING_API_KEY)
            ?: settings.getApiKey()
        setupPager(savedInstanceState)

        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.saveButton.setOnClickListener {
            savePendingChanges()
        }

        sshSettings.migrateOldSettingsIfNeeded()
        updateSaveControls()
    }

    override fun onResume() {
        super.onResume()
        refreshDriveState()
        refreshGeminiAuthStatus()
        refreshSshSummary()
        geminiPageBinding?.geminiEnabledSwitch?.let { switch ->
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = settings.isEnabled()
            switch.setOnCheckedChangeListener { _, isChecked ->
                settings.setEnabled(isChecked)
                geminiPageBinding?.apiKeyLayout?.isEnabled = isChecked
                refreshSshSummary()
            }
        }
        geminiPageBinding?.apiKeyLayout?.isEnabled = settings.isEnabled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB_INDEX, binding.settingsViewPager.currentItem)
        outState.putString(KEY_PENDING_API_KEY, pendingApiKey)
    }

    override fun onDestroy() {
        super.onDestroy()
        pageChangeCallback?.let { callback ->
            binding.settingsViewPager.unregisterOnPageChangeCallback(callback)
        }
        pageChangeCallback = null
        tabMediator?.detach()
        tabMediator = null
    }

    private fun setupPager(savedInstanceState: Bundle?) {
        val adapter = SettingsPagerAdapter(
            onGeneralReady = { setupGeneralPage(it) },
            onSshReady = { setupSshPage(it) },
            onGeminiReady = { setupGeminiPage(it) },
            onDriveReady = { setupDrivePage(it) }
        )
        binding.settingsViewPager.adapter = adapter
        binding.settingsViewPager.offscreenPageLimit = 3

        tabMediator = TabLayoutMediator(binding.settingsTabLayout, binding.settingsViewPager) { tab, position ->
            tab.text = when (position) {
                PAGE_GENERAL -> getString(R.string.settings_tab_general)
                PAGE_SSH -> getString(R.string.settings_tab_ssh)
                PAGE_GEMINI -> getString(R.string.settings_tab_gemini)
                else -> getString(R.string.settings_tab_drive)
            }
        }
        tabMediator?.attach()

        val index = savedInstanceState?.getInt(KEY_TAB_INDEX, getLastTabIndex()) ?: getLastTabIndex()
        binding.settingsViewPager.setCurrentItem(index, false)

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                saveLastTabIndex(position)
                updateSaveControls()
            }
        }.also { callback ->
            binding.settingsViewPager.registerOnPageChangeCallback(callback)
        }
    }

    private fun setupGeneralPage(pageBinding: PageSettingsGeneralBinding) {
        generalPageBinding = pageBinding
        setupLanguagePicker(pageBinding)
        setupThemePicker(pageBinding)

        pageBinding.managePlaysButton.setOnClickListener {
            startActivity(Intent(this, PlaysActivity::class.java))
        }

        pageBinding.managePhrasesButton.setOnClickListener {
            startActivity(Intent(this, PhrasesActivity::class.java))
        }
    }

    private fun setupSshPage(pageBinding: PageSettingsSshBinding) {
        sshPageBinding = pageBinding

        pageBinding.manageHostsButton.setOnClickListener {
            startActivity(Intent(this, HostsActivity::class.java))
        }

        pageBinding.quickAddHostButton.setOnClickListener {
            startActivity(Intent(this, HostEditActivity::class.java))
        }

        pageBinding.manageKeysButton.setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }

        pageBinding.quickGenerateKeyButton.setOnClickListener {
            val hasKey = sshSettings.getPrivateKey().orEmpty().isNotBlank()
            val intent = Intent(this, KeysActivity::class.java)
            if (!hasKey) {
                intent.putExtra(KeysActivity.EXTRA_AUTO_GENERATE, true)
            }
            startActivity(intent)
        }

        pageBinding.testConnectionButton.setOnClickListener {
            testActiveConnection()
        }

        pageBinding.copyConnectionDiagnosticsButton.setOnClickListener {
            copyConnectionDiagnostics()
        }

        pageBinding.copyConnectionDiagnosticsButton.visibility = if (lastConnectionDiagnostics.isBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }

        refreshSshSummary()
    }

    private fun setupGeminiPage(pageBinding: PageSettingsGeminiBinding) {
        geminiPageBinding = pageBinding
        pageBinding.geminiEnabledSwitch.setOnCheckedChangeListener(null)
        pageBinding.geminiEnabledSwitch.isChecked = settings.isEnabled()
        pageBinding.apiKeyInput.setText(pendingApiKey)
        pageBinding.apiKeyLayout.isEnabled = settings.isEnabled()

        pageBinding.geminiEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setEnabled(isChecked)
            pageBinding.apiKeyLayout.isEnabled = isChecked
            refreshGeminiAuthStatus()
            refreshSshSummary()
        }

        pageBinding.apiKeyInput.doAfterTextChanged { editable ->
            pendingApiKey = editable?.toString().orEmpty().trim()
            updateSaveControls()
            refreshGeminiAuthStatus()
            refreshSshSummary()
        }

        refreshGeminiAuthStatus()
    }

    private fun setupDrivePage(pageBinding: PageSettingsDriveBinding) {
        drivePageBinding = pageBinding
        pageBinding.driveSignInButton.setOnClickListener {
            driveSignInLauncher.launch(driveAuthManager.getSignInIntent())
        }
        pageBinding.driveSignOutButton.setOnClickListener {
            driveAuthManager.signOut {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.drive_sign_out_done), Toast.LENGTH_SHORT).show()
                    refreshDriveState()
                }
            }
        }
        pageBinding.driveAlwaysSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            driveLogSettings.setAlwaysSaveEnabled(isChecked)
            refreshSshSummary()
        }
        refreshDriveState()
    }

    private fun updateSaveControls() {
        val hasPendingChanges = pendingApiKey != settings.getApiKey().trim()
        val isOnGeminiPage = binding.settingsViewPager.currentItem == PAGE_GEMINI
        binding.saveButton.visibility = if (hasPendingChanges) View.VISIBLE else View.GONE
        binding.pendingChangesText.visibility = if (hasPendingChanges && !isOnGeminiPage) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateTabIndicators(hasPendingChanges)
    }

    private fun updateTabIndicators(hasPendingGeminiChanges: Boolean) {
        val geminiTab = binding.settingsTabLayout.getTabAt(PAGE_GEMINI)
        val geminiLabel = getString(R.string.settings_tab_gemini)
        geminiTab?.text = if (hasPendingGeminiChanges) "$geminiLabel •" else geminiLabel
    }

    private fun savePendingChanges() {
        settings.setApiKey(pendingApiKey)
        updateSaveControls()
        refreshSshSummary()
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun setupLanguagePicker(pageBinding: PageSettingsGeneralBinding) {
        val labels = languageOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        pageBinding.languageInput.setAdapter(adapter)

        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = languageOptions.firstOrNull { it.tag == currentTag }
            ?: languageOptions.first()
        pageBinding.languageInput.setText(selected.label, false)

        pageBinding.languageInput.setOnItemClickListener { _, _, position, _ ->
            val option = languageOptions.getOrNull(position) ?: return@setOnItemClickListener
            applySelectedLanguageTag(option.tag)
        }
    }

    private fun setupThemePicker(pageBinding: PageSettingsGeneralBinding) {
        val labels = themeOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        pageBinding.themeModeInput.setAdapter(adapter)

        val selected = themeOptions.firstOrNull { it.mode == appThemeSettings.getThemeMode() }
            ?: themeOptions.first()
        pageBinding.themeModeInput.setText(selected.label, false)

        pageBinding.themeModeInput.setOnItemClickListener { _, _, position, _ ->
            val option = themeOptions.getOrNull(position) ?: return@setOnItemClickListener
            appThemeSettings.setThemeMode(option.mode)
        }
    }

    private fun applySelectedLanguageTag(languageTag: String) {
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (languageTag == currentTag) {
            return
        }

        val locales = if (languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun refreshGeminiAuthStatus() {
        val pageBinding = geminiPageBinding ?: return
        val account = driveAuthManager.getSignedInAccount()
        if (account != null && settings.isEnabled()) {
            pageBinding.geminiAuthStatusText.text = getString(
                R.string.gemini_auth_using_google,
                account.email ?: account.displayName ?: ""
            )
            pageBinding.geminiAuthStatusText.visibility = View.VISIBLE
            pageBinding.apiKeyHelper.text = getString(R.string.gemini_api_key_helper_fallback)
        } else {
            pageBinding.geminiAuthStatusText.visibility = View.GONE
            pageBinding.apiKeyHelper.text = getString(R.string.gemini_api_key_helper)
        }
    }

    private fun refreshDriveState() {
        val account = driveAuthManager.getSignedInAccount()
        val pageBinding = drivePageBinding
        if (pageBinding != null) {
            if (account == null) {
                pageBinding.driveStatusText.text = getString(R.string.drive_status_signed_out)
                pageBinding.driveSignOutButton.visibility = View.GONE
                pageBinding.driveSignInButton.visibility = View.VISIBLE
                pageBinding.driveAlwaysSaveSwitch.setOnCheckedChangeListener(null)
                pageBinding.driveAlwaysSaveSwitch.isChecked = driveLogSettings.isAlwaysSaveEnabled()
                pageBinding.driveAlwaysSaveSwitch.isEnabled = false
                pageBinding.driveAlwaysSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
                    driveLogSettings.setAlwaysSaveEnabled(isChecked)
                    refreshSshSummary()
                }
            } else {
                pageBinding.driveStatusText.text = getString(
                    R.string.drive_status_signed_in,
                    account.email ?: account.displayName ?: ""
                )
                pageBinding.driveSignOutButton.visibility = View.VISIBLE
                pageBinding.driveSignInButton.visibility = View.GONE
                pageBinding.driveAlwaysSaveSwitch.setOnCheckedChangeListener(null)
                pageBinding.driveAlwaysSaveSwitch.isChecked = driveLogSettings.isAlwaysSaveEnabled()
                pageBinding.driveAlwaysSaveSwitch.isEnabled = true
                pageBinding.driveAlwaysSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
                    driveLogSettings.setAlwaysSaveEnabled(isChecked)
                    refreshSshSummary()
                }
            }
        }

        refreshGeminiAuthStatus()
        refreshSshSummary()
    }

    private fun refreshSshSummary() {
        val pageBinding = sshPageBinding ?: return
        val activeConfig = sshSettings.getConfigOrNull()
        pageBinding.summaryHostValue.text = activeConfig?.displayTarget()
            ?: getString(R.string.settings_summary_host_none)

        val hasKey = sshSettings.getPrivateKey().orEmpty().isNotBlank()
        pageBinding.quickGenerateKeyButton.text = if (hasKey) {
            getString(R.string.action_keys_short)
        } else {
            getString(R.string.action_generate_key_quick)
        }

        pageBinding.summaryAuthValue.text = authModeLabel(activeConfig)

        val geminiEnabled = settings.isEnabled()
        val authMode = geminiClient.getAuthMode()
        pageBinding.summaryGeminiValue.text = when {
            !geminiEnabled -> getString(R.string.settings_summary_gemini_disabled)
            authMode == GeminiClient.AuthMode.GOOGLE_ACCOUNT ->
                getString(R.string.settings_summary_gemini_google)
            authMode == GeminiClient.AuthMode.API_KEY ->
                getString(R.string.settings_summary_gemini_enabled)
            else -> getString(R.string.settings_summary_gemini_missing_key)
        }

        val account = driveAuthManager.getSignedInAccount()
        pageBinding.summaryDriveValue.text = when {
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

        val pageBinding = sshPageBinding ?: return

        pageBinding.testConnectionButton.isEnabled = false
        pageBinding.testConnectionButton.text = getString(R.string.action_test_connection_running)
        pageBinding.testConnectionResultText.text = getString(R.string.session_status_connecting)

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
                val currentBinding = sshPageBinding ?: return@withContext
                lastConnectionDiagnostics = diagnostics
                currentBinding.copyConnectionDiagnosticsButton.visibility = View.VISIBLE
                currentBinding.testConnectionButton.isEnabled = true
                currentBinding.testConnectionButton.text = getString(R.string.action_test_connection)
                if (connectResult.success) {
                    currentBinding.testConnectionResultText.text =
                        getString(R.string.test_connection_success, elapsed)
                } else {
                    currentBinding.testConnectionResultText.text =
                        getString(R.string.test_connection_failure, elapsed, connectResult.message)
                }
            }
        }
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
        private const val PAGE_GENERAL = 0
        private const val PAGE_SSH = 1
        private const val PAGE_GEMINI = 2
        private const val PAGE_DRIVE = 3
        private const val KEY_TAB_INDEX = "key_tab_index"
        private const val KEY_PENDING_API_KEY = "key_pending_api_key"
        private const val PREFS_SETTINGS_UI = "settings_ui"
        private const val PREF_LAST_TAB_INDEX = "pref_last_tab_index"
    }

    private fun getLastTabIndex(): Int {
        val prefs = getSharedPreferences(PREFS_SETTINGS_UI, MODE_PRIVATE)
        return prefs.getInt(PREF_LAST_TAB_INDEX, PAGE_GENERAL)
            .coerceIn(PAGE_GENERAL, PAGE_DRIVE)
    }

    private fun saveLastTabIndex(index: Int) {
        getSharedPreferences(PREFS_SETTINGS_UI, MODE_PRIVATE)
            .edit()
            .putInt(PREF_LAST_TAB_INDEX, index.coerceIn(PAGE_GENERAL, PAGE_DRIVE))
            .apply()
    }

    private data class LanguageOption(
        val tag: String,
        val label: String
    )

    private data class ThemeOption(
        val mode: AppThemeSettings.ThemeMode,
        val label: String
    )

    private inner class SettingsPagerAdapter(
        private val onGeneralReady: (PageSettingsGeneralBinding) -> Unit,
        private val onSshReady: (PageSettingsSshBinding) -> Unit,
        private val onGeminiReady: (PageSettingsGeminiBinding) -> Unit,
        private val onDriveReady: (PageSettingsDriveBinding) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 4

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                PAGE_GENERAL -> {
                    val pageBinding = PageSettingsGeneralBinding.inflate(inflater, parent, false)
                    onGeneralReady(pageBinding)
                    GeneralPageHolder(pageBinding)
                }

                PAGE_SSH -> {
                    val pageBinding = PageSettingsSshBinding.inflate(inflater, parent, false)
                    onSshReady(pageBinding)
                    SshPageHolder(pageBinding)
                }

                PAGE_GEMINI -> {
                    val pageBinding = PageSettingsGeminiBinding.inflate(inflater, parent, false)
                    onGeminiReady(pageBinding)
                    GeminiPageHolder(pageBinding)
                }

                else -> {
                    val pageBinding = PageSettingsDriveBinding.inflate(inflater, parent, false)
                    onDriveReady(pageBinding)
                    DrivePageHolder(pageBinding)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Static pages: bindings are configured in onCreateViewHolder.
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is GeneralPageHolder -> if (generalPageBinding === holder.binding) {
                    generalPageBinding = null
                }

                is SshPageHolder -> if (sshPageBinding === holder.binding) {
                    sshPageBinding = null
                }

                is GeminiPageHolder -> if (geminiPageBinding === holder.binding) {
                    geminiPageBinding = null
                }

                is DrivePageHolder -> if (drivePageBinding === holder.binding) {
                    drivePageBinding = null
                }
            }
            super.onViewRecycled(holder)
        }
    }

    private class GeneralPageHolder(val binding: PageSettingsGeneralBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class SshPageHolder(val binding: PageSettingsSshBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class GeminiPageHolder(val binding: PageSettingsGeminiBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class DrivePageHolder(val binding: PageSettingsDriveBinding) :
        RecyclerView.ViewHolder(binding.root)
}
