package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityMainBinding
import net.hlan.sushi.databinding.DialogGeminiControlsBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    private val sshSettings by lazy { SshSettings(this) }
    private val playDb by lazy { PlayDatabaseHelper.getInstance(this) }

    private var sshClient: SshClient? = null
    private var isConnecting = false
    private var activeConfig: SshConnectionConfig? = null
    private var lastConnectFailed = false
    private var geminiDialog: AlertDialog? = null
    private var geminiDialogBinding: DialogGeminiControlsBinding? = null
    private var lastGeminiPrompt = ""
    private var lastGeminiOutput = ""
    private var isGeminiRequestRunning = false

    private val voiceResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val voiceText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()

        if (voiceText.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.gemini_output_error), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        lastGeminiPrompt = voiceText
        updateGeminiDialogState()
        requestGeminiCommand(voiceText)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            Toast.makeText(this, getString(R.string.gemini_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startSessionButton.setOnClickListener {
            startActivity(TerminalActivity.createIntent(this, autoConnect = true))
        }

        binding.playsButton.setOnClickListener {
            showPlayHostDialog()
        }

        binding.geminiVoiceButton.setOnClickListener {
            showGeminiDialog()
        }

        binding.geminiSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateGeminiState()
        ManagedPlays.ensure(this, sshSettings.getPublicKey())
        refreshSessionLog()
        updateSessionUi()
        
        val appVersion = AppUtils.getAppVersionInfo(this)
        binding.footerText.text = getString(R.string.placeholder_footer, appVersion.name)
    }

    override fun onResume() {
        super.onResume()
        updateGeminiState()
        updateSessionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiDialog?.dismiss()
        geminiDialog = null
        geminiDialogBinding = null
        sshClient?.disconnect()
        sshClient = null
    }

    private fun updateGeminiState() {
        val status = when {
            !geminiSettings.isEnabled() -> getString(R.string.gemini_status_disabled)
            geminiSettings.getApiKey().isBlank() -> getString(R.string.gemini_status_missing_key)
            else -> getString(R.string.gemini_status_ready)
        }
        binding.geminiStatusText.text = status
        geminiDialogBinding?.geminiDialogStatusText?.text = status
    }

    private fun handleGeminiVoice() {
        if (!geminiSettings.isEnabled() || geminiSettings.getApiKey().isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.gemini_voice_prompt))
        }
        voiceResultLauncher.launch(intent)
    }

    private fun requestGeminiCommand(voiceText: String) {
        isGeminiRequestRunning = true
        lastGeminiOutput = getString(R.string.gemini_output_waiting)
        updateGeminiDialogState()

        Thread {
            val result = geminiClient.generateCommand(voiceText)
            runOnUiThread {
                isGeminiRequestRunning = false
                lastGeminiOutput = result.message
                updateGeminiDialogState()
            }
        }.start()
    }

    private fun showGeminiDialog() {
        if (geminiDialog?.isShowing == true) {
            return
        }

        val dialogBinding = DialogGeminiControlsBinding.inflate(layoutInflater)
        geminiDialogBinding = dialogBinding

        dialogBinding.geminiDialogVoiceButton.setOnClickListener {
            handleGeminiVoice()
        }
        dialogBinding.geminiDialogSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()
        dialog.setOnDismissListener {
            geminiDialog = null
            geminiDialogBinding = null
        }
        geminiDialog = dialog
        updateGeminiState()
        updateGeminiDialogState()
        dialog.show()
    }

    private fun updateGeminiDialogState() {
        val dialogBinding = geminiDialogBinding ?: return
        dialogBinding.geminiDialogPromptText.text = if (lastGeminiPrompt.isBlank()) {
            getString(R.string.gemini_prompt_placeholder)
        } else {
            lastGeminiPrompt
        }
        dialogBinding.geminiDialogOutputText.text = if (lastGeminiOutput.isBlank()) {
            getString(R.string.gemini_output_placeholder)
        } else {
            lastGeminiOutput
        }
        dialogBinding.geminiDialogProgressBar.visibility = if (isGeminiRequestRunning) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showPlayHostDialog() {
        if (isConnecting) {
            Toast.makeText(this, getString(R.string.session_status_connecting), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val plays = playDb.getAllPlays()
            val hosts = sshSettings.getHosts()
            val activeHostId = sshSettings.getActiveHostId()

            withContext(Dispatchers.Main) {
                if (plays.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.plays_empty_toast, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, PlaysActivity::class.java))
                    return@withContext
                }
                if (hosts.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.play_missing_hosts, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    return@withContext
                }

                val hostLabels = hosts.map { host ->
                    if (host.id == activeHostId) {
                        getString(R.string.play_host_item_active, host.displayTarget())
                    } else {
                        getString(R.string.play_host_item, host.displayTarget())
                    }
                }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.play_select_host_title)
                    .setItems(hostLabels) { _, which ->
                        showPlaySelectionDialog(plays, hosts[which])
                    }
                    .setNegativeButton(R.string.phrase_cancel, null)
                    .show()
            }
        }
    }

    private fun showPlaySelectionDialog(plays: List<Play>, host: SshConnectionConfig) {
        val playNames = plays.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.plays_title)
            .setItems(playNames) { _, which ->
                val play = plays[which]
                promptPlayParametersAndRun(play, host)
            }
            .setPositiveButton(R.string.action_manage) { _, _ ->
                startActivity(Intent(this, PlaysActivity::class.java))
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    private fun promptPlayParametersAndRun(play: Play, host: SshConnectionConfig) {
        val parameters = PlayParameters.decode(play.parametersJson).ifEmpty {
            PlayParameters.inferFromTemplate(play.scriptTemplate)
        }

        if (parameters.isEmpty()) {
            runPlay(play, host, emptyMap())
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val fields = linkedMapOf<PlayParameter, TextInputEditText>()

        parameters.forEachIndexed { index, parameter ->
            val layout = TextInputLayout(this).apply {
                hint = parameter.label
            }
            val input = TextInputEditText(this).apply {
                inputType = if (parameter.secret) {
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT
                }
            }
            layout.addView(input)
            if (index > 0) {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 12
                container.addView(layout, params)
            } else {
                container.addView(layout)
            }
            fields[parameter] = input
        }

        val content = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_prompt_title, play.name))
            .setView(content)
            .setPositiveButton(R.string.action_run_play, null)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()

        dialog.setOnShowListener {
            val runButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            runButton.setOnClickListener {
                val values = mutableMapOf<String, String>()
                var hasError = false
                fields.forEach { (parameter, input) ->
                    val value = input.text?.toString().orEmpty()
                    val layout = input.parent.parent as? TextInputLayout
                    layout?.error = null
                    if (parameter.required && value.isBlank()) {
                        layout?.error = getString(R.string.play_parameter_required)
                        hasError = true
                    } else {
                        values[parameter.key] = value
                    }
                }
                if (hasError) {
                    return@setOnClickListener
                }
                dialog.dismiss()
                runPlay(play, host, values)
            }
        }
        dialog.show()
    }

    private fun runPlay(play: Play, host: SshConnectionConfig, values: Map<String, String>) {
        val config = host.copy(privateKey = sshSettings.getPrivateKey())
        appendSessionLog(getString(R.string.play_run_started, play.name, config.displayTarget()))

        Thread {
            val result = PlayRunner.execute(
                play = play,
                hostConfig = config,
                values = values,
                onLine = { line -> appendSessionLog("[Play] $line") }
            )

            runOnUiThread {
                if (result.success) {
                    appendSessionLog(getString(R.string.play_run_finished, play.name))
                } else {
                    appendSessionLog(getString(R.string.play_run_failed, play.name, result.message))
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleSessionAction() {
        if (isConnecting) {
            return
        }

        if (sshClient?.isConnected() == true) {
            disconnectSession()
            return
        }

        val config = sshSettings.getConfigOrNull()
        if (config == null) {
            Toast.makeText(this, getString(R.string.ssh_missing_config), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        activeConfig = config
        isConnecting = true
        lastConnectFailed = false
        updateSessionUi()
        val connectStartedAt = System.currentTimeMillis()
        appendSessionLog(
            getString(
                R.string.session_connect_attempt_start,
                config.displayTarget(),
                formatTimestamp(connectStartedAt)
            )
        )

        Thread {
            val client = SshClient(config)
            val result = client.connect { line ->
                appendSessionLog(line)
            }
            val elapsedMs = System.currentTimeMillis() - connectStartedAt
            runOnUiThread {
                isConnecting = false
                if (result.success) {
                    sshClient = client
                    lastConnectFailed = false
                    appendSessionLog(getString(R.string.session_connect_attempt_success, elapsedMs))
                    appendSessionLog(
                        getString(
                            R.string.session_started_at,
                            formatTimestamp(System.currentTimeMillis())
                        )
                    )
                    appendSessionLog(getString(R.string.session_connected_to, config.displayTarget()))
                } else {
                    sshClient = null
                    lastConnectFailed = true
                    appendSessionLog(
                        getString(R.string.session_connect_attempt_failed, elapsedMs, result.message)
                    )
                    appendDebugInfoIfMissing()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
                updateSessionUi()
            }
        }.start()
    }

    private fun disconnectSession() {
        val target = activeConfig?.displayTarget()
        sshClient?.disconnect()
        sshClient = null
        lastConnectFailed = false
        activeConfig = null
        appendSessionLog(
            if (target.isNullOrBlank()) {
                getString(R.string.session_disconnected)
            } else {
                getString(R.string.session_disconnected_from, target)
            }
        )
        updateSessionUi()
        attemptDriveLogUpload()
    }

    private fun appendSessionLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendSessionLog(message) }
            return
        }

        consoleLogRepository.appendLine(message)
        binding.sessionLogText.appendLog(message)
        scrollToSessionLog()
    }

    private fun scrollToSessionLog() {
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, binding.sessionCard.bottom)
        }
    }

    private fun refreshSessionLog() {
        val log = consoleLogRepository.getLog()
        binding.sessionLogText.clearLog()
        if (log.isBlank()) {
            binding.sessionLogText.appendLog(getString(R.string.session_log_placeholder))
        } else {
            binding.sessionLogText.appendLog(log)
        }
    }

    private fun clearSessionLog() {
        consoleLogRepository.clear()
        binding.sessionLogText.clearLog()
        binding.sessionLogText.appendLog(getString(R.string.session_log_placeholder))
        Toast.makeText(this, getString(R.string.session_log_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun copySessionLog() {
        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            Toast.makeText(this, getString(R.string.session_log_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        val reportLog = buildReportLog(log)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.clipboard_session_log_label), reportLog)
        )
        Toast.makeText(this, getString(R.string.session_log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun updateSessionUi() {
        val isConnected = sshClient?.isConnected() == true
        val statusLabel = when {
            isConnecting -> getString(R.string.session_status_connecting)
            isConnected -> getString(R.string.session_status_connected)
            lastConnectFailed -> getString(R.string.session_status_failed)
            else -> getString(R.string.session_status_disconnected)
        }

        val helperText = when {
            isConnecting -> getString(R.string.status_helper_connecting)
            isConnected -> getString(R.string.status_helper_connected)
            lastConnectFailed -> getString(R.string.status_helper_failed)
            else -> getString(R.string.status_helper_disconnected)
        }

        binding.statusText.text = helperText
        binding.sessionStatusText.text = statusLabel

        val displayTarget = if (isConnected || isConnecting) {
            activeConfig?.displayTarget()
        } else {
            sshSettings.getConfigOrNull()?.displayTarget()
        }
        binding.sessionTargetText.text = if (displayTarget.isNullOrBlank()) {
            getString(R.string.session_target_empty)
        } else {
            getString(R.string.session_target, displayTarget)
        }

        binding.startSessionButton.isEnabled = true
        binding.startSessionButton.text = getString(R.string.action_start_session)

        binding.playsButton.isEnabled = !isConnecting
    }

    private fun attemptDriveLogUpload() {
        if (!driveLogSettings.isAlwaysSaveEnabled()) {
            return
        }

        val account = driveAuthManager.getSignedInAccount()
        if (account == null) {
            Toast.makeText(this, getString(R.string.drive_upload_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            return
        }

        driveLogUploader.uploadLog(account, log) { result ->
            runOnUiThread {
                val message = if (result.success) {
                    getString(R.string.drive_upload_success)
                } else {
                    getString(R.string.drive_upload_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendDebugInfoIfMissing() {
        val header = getString(R.string.debug_info_header)
        if (consoleLogRepository.getLog().contains(header)) {
            return
        }
        appendSessionLog(buildDebugInfoBlock())
    }

    private fun buildReportLog(log: String): String {
        val header = getString(R.string.debug_info_header)
        if (log.contains(header)) {
            return log
        }

        val debugInfo = buildDebugInfoBlock()
        return if (log.isBlank()) {
            debugInfo
        } else {
            "$log\n\n$debugInfo"
        }
    }

    private fun buildDebugInfoBlock(): String {
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: getString(R.string.debug_info_unknown)
        val appVersion = AppUtils.getAppVersionInfo(this)
        val buildType = if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "debug"
        } else {
            "release"
        }
        val locale = Locale.getDefault().toLanguageTag()
        val timeZone = TimeZone.getDefault().id

        val lines = listOf(
            getString(R.string.debug_info_header),
            getString(R.string.debug_info_app_version, appVersion.name, appVersion.code),
            getString(R.string.debug_info_build_type, buildType),
            getString(R.string.debug_info_android, androidVersion, Build.VERSION.SDK_INT),
            getString(
                R.string.debug_info_device,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.DEVICE
            ),
            getString(R.string.debug_info_locale, locale),
            getString(R.string.debug_info_timezone, timeZone)
        )

        return lines.joinToString("\n")
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }
}
