package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityMainBinding
import net.hlan.sushi.databinding.DialogGeminiControlsBinding
import net.hlan.sushi.databinding.PageMainPlaysBinding
import net.hlan.sushi.databinding.PageMainTerminalBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings, driveAuthManager) }
    private val nanoClient by lazy { GeminiNanoClient(this) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    private val sshSettings by lazy { SshSettings(this) }
    private val playDb by lazy { PlayDatabaseHelper.getInstance(this) }
    private val phraseDb by lazy { PhraseDatabaseHelper.getInstance(this) }

    private var isPlayRunning = false
    private var geminiDialog: AlertDialog? = null
    private var geminiDialogBinding: DialogGeminiControlsBinding? = null
    private var lastGeminiPrompt = ""
    private var lastGeminiOutput = ""
    private var isGeminiRequestRunning = false
    private val geminiTranscript = mutableListOf<GeminiTranscriptEntry>()
    private var transcriptAdapter: GeminiTranscriptAdapter? = null
    /** Raw Terminal Mode — input goes straight to the shell, bypassing Gemini. Persists across dialog re-opens. */
    private var isRawTerminalMode = false
    private var playsPageBinding: PageMainPlaysBinding? = null
    private var terminalPageBinding: PageMainTerminalBinding? = null
    private var toolsTabMediator: TabLayoutMediator? = null
    private var toolsPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var playsPageStateRequestId = 0
    
    // Conversation management
    private var conversationManager: ConversationManager? = null
    private var connectionListener: TerminalSessionHolder.ConnectionListener? = null

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

        // Use conversation manager if connected, otherwise fall back to old behavior
        if (isRawTerminalMode && conversationManager != null && conversationManager!!.isInitialized()) {
            handleRawCommand(voiceText)
        } else if (conversationManager != null && conversationManager!!.isInitialized()) {
            handleUserMessage(voiceText)
        } else {
            // Fallback to old command generation for backwards compatibility
            lastGeminiPrompt = voiceText
            updateGeminiDialogState()
            requestGeminiCommand(voiceText)
        }
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

        binding.returnTerminalButton.setOnClickListener {
            startActivity(TerminalActivity.createIntent(this, autoConnect = true))
        }

        binding.configureHostButton.setOnClickListener {
            val hosts = sshSettings.getHosts()
            if (hosts.isEmpty()) {
                startActivity(Intent(this, HostEditActivity::class.java))
            } else {
                startActivity(Intent(this, HostsActivity::class.java))
            }
        }

        binding.mainSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupToolsPager()
        setupChecklistClickHandlers()

        updateGeminiState()
        ManagedPlays.ensure(this, sshSettings.getPublicKey())
        refreshSessionLog()
        updateSessionUi()

        val appVersion = AppUtils.getAppVersionInfo(this)
        binding.footerText.text = getString(R.string.placeholder_footer, appVersion.name)

        // Set up SSH connection listener for conversation
        setupConnectionListener()
    }

    override fun onResume() {
        super.onResume()
        updateGeminiState()
        updateSessionUi()
        refreshPlaysPageState()
        warmUpNanoIfAvailable()
        updateSetupChecklist()
    }

    override fun onDestroy() {
        super.onDestroy()
        toolsPageChangeCallback?.let { callback ->
            binding.mainToolsViewPager.unregisterOnPageChangeCallback(callback)
        }
        toolsPageChangeCallback = null
        toolsTabMediator?.detach()
        toolsTabMediator = null
        geminiDialog?.dismiss()
        geminiDialog = null
        geminiDialogBinding = null
        nanoClient.close()
        
        // Clean up connection listener
        connectionListener?.let { TerminalSessionHolder.removeListener(it) }
        connectionListener = null
    }

    /**
     * Warm up Gemini Nano in the background if it's already downloaded.
     * This reduces first-inference latency without blocking the UI.
     */
    private fun warmUpNanoIfAvailable() {
        if (!geminiSettings.isEnabled() || !geminiSettings.getNanoPreferred()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val status = nanoClient.checkStatus()
                if (status == FeatureStatus.AVAILABLE) {
                    nanoClient.warmup()
                    Log.d(TAG, "Gemini Nano warmed up")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nano warmup check failed: ${e.message}")
            }
        }
    }

    private fun setupToolsPager() {
        val adapter = MainToolsPagerAdapter(
            onTerminalReady = { setupTerminalPage(it) },
            onPlaysReady = { setupPlaysPage(it) }
        )
        binding.mainToolsViewPager.adapter = adapter
        binding.mainToolsViewPager.offscreenPageLimit = 1

        toolsTabMediator = TabLayoutMediator(binding.mainToolsTabLayout, binding.mainToolsViewPager) { tab, position ->
            tab.text = when (position) {
                PAGE_TERMINAL -> getString(R.string.main_tab_terminal)
                else -> getString(R.string.main_tab_plays)
            }
        }
        toolsTabMediator?.attach()

        binding.mainToolsViewPager.post {
            val lastTab = getLastMainTab()
            binding.mainToolsViewPager.setCurrentItem(lastTab, false)
            adjustToolsPagerHeight(lastTab)
        }

        toolsPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                saveLastMainTab(position)
                adjustToolsPagerHeight(position)
            }
        }.also { callback ->
            binding.mainToolsViewPager.registerOnPageChangeCallback(callback)
        }
    }

    private fun setupTerminalPage(pageBinding: PageMainTerminalBinding) {
        terminalPageBinding = pageBinding
        pageBinding.geminiVoiceButton.setOnClickListener {
            showGeminiDialog()
        }
        pageBinding.phrasesButton.setOnClickListener {
            showPhraseCopyPicker()
        }
        updateGeminiState()
        if (binding.mainToolsViewPager.currentItem == PAGE_TERMINAL) {
            binding.mainToolsViewPager.post { adjustToolsPagerHeight(PAGE_TERMINAL) }
        }
    }

    private fun setupPlaysPage(pageBinding: PageMainPlaysBinding) {
        playsPageBinding = pageBinding
        pageBinding.playsButton.setOnClickListener {
            showPlayHostDialog()
        }
        pageBinding.managePlaysButton.setOnClickListener {
            startActivity(Intent(this, PlaysActivity::class.java))
        }
        pageBinding.addHostButton.setOnClickListener {
            startActivity(Intent(this, HostsActivity::class.java))
        }
        pageBinding.addPlayButton.setOnClickListener {
            startActivity(Intent(this, PlaysActivity::class.java))
        }
        pageBinding.copyLogButton.setOnClickListener {
            copySessionLog()
        }
        pageBinding.clearLogButton.setOnClickListener {
            clearSessionLog()
        }
        pageBinding.playsButton.isEnabled = !isPlayRunning
        refreshSessionLog()
        refreshPlaysPageState()
        if (binding.mainToolsViewPager.currentItem == PAGE_PLAYS) {
            binding.mainToolsViewPager.post { adjustToolsPagerHeight(PAGE_PLAYS) }
        }
    }

    private fun adjustToolsPagerHeight(position: Int) {
        val targetView = when (position) {
            PAGE_TERMINAL -> terminalPageBinding?.root
            PAGE_PLAYS -> playsPageBinding?.root
            else -> null
        } ?: return

        targetView.post {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(binding.mainToolsViewPager.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            targetView.measure(widthSpec, heightSpec)
            val desiredHeight = targetView.measuredHeight
            if (desiredHeight > 0 && binding.mainToolsViewPager.layoutParams.height != desiredHeight) {
                binding.mainToolsViewPager.layoutParams = binding.mainToolsViewPager.layoutParams.apply {
                    height = desiredHeight
                }
            }
        }
    }

    /**
     * Updates the Gemini status text shown on the terminal page and in the Gemini dialog.
     * Checks Nano status asynchronously so the check doesn't block the UI thread.
     */
    private fun updateGeminiState() {
        if (!geminiSettings.isEnabled()) {
            val status = getString(R.string.gemini_status_disabled)
            terminalPageBinding?.geminiStatusText?.text = status
            geminiDialogBinding?.geminiDialogStatusText?.text = status
            return
        }

        // Check cloud auth synchronously — it reads from prefs, no I/O.
        val cloudStatus = when {
            geminiClient.getAuthMode() == GeminiClient.AuthMode.GOOGLE_ACCOUNT ->
                getString(R.string.gemini_status_google_account)
            geminiSettings.getApiKey().isBlank() -> getString(R.string.gemini_status_missing_key)
            else -> getString(R.string.gemini_status_ready)
        }

        // Set cloud status immediately, then refine if Nano is preferred.
        terminalPageBinding?.geminiStatusText?.text = cloudStatus
        geminiDialogBinding?.geminiDialogStatusText?.text = cloudStatus

        if (!geminiSettings.getNanoPreferred()) return

        // Check Nano status on a background thread to avoid blocking the UI.
        lifecycleScope.launch(Dispatchers.IO) {
            val nanoStatus = nanoClient.checkStatus()
            val statusText = when (nanoStatus) {
                FeatureStatus.AVAILABLE -> getString(R.string.gemini_status_nano)
                FeatureStatus.DOWNLOADING -> getString(R.string.gemini_status_nano_downloading)
                FeatureStatus.DOWNLOADABLE -> getString(R.string.gemini_status_nano_downloadable)
                else -> cloudStatus
            }
            withContext(Dispatchers.Main) {
                terminalPageBinding?.geminiStatusText?.text = statusText
                geminiDialogBinding?.geminiDialogStatusText?.text = statusText
            }
        }
    }

    private fun handleGeminiVoice() {
        if (!geminiSettings.isEnabled() || geminiClient.getAuthMode() == GeminiClient.AuthMode.NONE) {
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

    /**
     * Routes the voice command to Gemini Nano (on-device) if available and preferred,
     * falling back to the cloud GeminiClient otherwise.
     */
    private fun requestGeminiCommand(voiceText: String) {
        isGeminiRequestRunning = true
        lastGeminiOutput = getString(R.string.gemini_output_waiting)
        updateGeminiDialogState()

        lifecycleScope.launch(Dispatchers.IO) {
            val useNano = geminiSettings.getNanoPreferred()
                && nanoClient.checkStatus() == FeatureStatus.AVAILABLE

            val result = if (useNano) {
                Log.d(TAG, "Routing voice command to Gemini Nano (on-device)")
                nanoClient.generateCommand(voiceText)
            } else {
                Log.d(TAG, "Routing voice command to cloud Gemini (${geminiSettings.getCloudModel()})")
                geminiClient.generateCommand(voiceText)
            }

            withContext(Dispatchers.Main) {
                isGeminiRequestRunning = false
                lastGeminiOutput = result.message
                updateGeminiDialogState()
                appendSessionLog(getString(R.string.gemini_log_entry, voiceText, result.message))

                geminiTranscript.add(GeminiTranscriptEntry(prompt = voiceText, response = result.message))
                transcriptAdapter?.let { adapter ->
                    adapter.notifyItemInserted(geminiTranscript.size - 1)
                    geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
                    geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                        visibility = View.VISIBLE
                        scrollToPosition(geminiTranscript.size - 1)
                    }
                }
            }
        }
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
        dialogBinding.geminiDialogHistoryButton.setOnClickListener {
            startActivity(GeminiHistoryActivity.createIntent(this))
        }
        dialogBinding.geminiDialogCopyButton.setOnClickListener {
            copyGeminiCommand()
        }

        dialogBinding.geminiDialogRawModeSwitch.isChecked = isRawTerminalMode
        dialogBinding.geminiDialogRawModeSwitch.setOnCheckedChangeListener { _, checked ->
            isRawTerminalMode = checked
            updateGeminiDialogState()
        }

        // NEW: Send button for text input
        dialogBinding.geminiDialogSendButton.setOnClickListener {
            val text = dialogBinding.geminiDialogTextInput.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                dialogBinding.geminiDialogTextInput.text?.clear()
                if (isRawTerminalMode) {
                    handleRawCommand(text)
                } else if (conversationManager != null && conversationManager!!.isInitialized()) {
                    handleUserMessage(text)
                } else {
                    // Fallback to old behavior
                    lastGeminiPrompt = text
                    updateGeminiDialogState()
                    requestGeminiCommand(text)
                }
            }
        }
        
        // NEW: IME action (keyboard Enter key)
        dialogBinding.geminiDialogTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                dialogBinding.geminiDialogSendButton.performClick()
                true
            } else {
                false
            }
        }

        val adapter = GeminiTranscriptAdapter(geminiTranscript)
        transcriptAdapter = adapter
        dialogBinding.geminiTranscriptRecycler.layoutManager =
            LinearLayoutManager(this).also { it.stackFromEnd = true }
        dialogBinding.geminiTranscriptRecycler.adapter = adapter
        if (geminiTranscript.isNotEmpty()) {
            dialogBinding.geminiTranscriptLabel.visibility = View.VISIBLE
            dialogBinding.geminiTranscriptRecycler.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()
        dialog.setOnDismissListener {
            geminiDialog = null
            geminiDialogBinding = null
            transcriptAdapter = null
        }
        geminiDialog = dialog
        updateGeminiState()
        updateGeminiDialogState()
        dialog.show()
    }

    private fun updateGeminiDialogState() {
        val dialogBinding = geminiDialogBinding ?: return
        
        val isBusy = isGeminiRequestRunning
        
        // Disable inputs when busy
        dialogBinding.geminiDialogTextInput.isEnabled = !isBusy
        dialogBinding.geminiDialogSendButton.isEnabled = !isBusy
        dialogBinding.geminiDialogVoiceButton.isEnabled = !isBusy
        
        dialogBinding.geminiDialogProgressBar.visibility = if (isBusy) {
            View.VISIBLE
        } else {
            View.GONE
        }

        dialogBinding.geminiDialogTextInputLayout.hint = getString(
            if (isRawTerminalMode) R.string.raw_terminal_mode_hint else R.string.conversation_input_hint
        )

        val hasCommand = !isBusy
            && lastGeminiOutput.isNotBlank()
            && lastGeminiOutput != getString(R.string.gemini_output_placeholder)
            && lastGeminiOutput != getString(R.string.gemini_output_waiting)
        dialogBinding.geminiDialogCopyButton.visibility = if (hasCommand) View.VISIBLE else View.GONE
    }

    private fun copyGeminiCommand() {
        if (lastGeminiOutput.isBlank()) {
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.gemini_output_label), lastGeminiOutput)
        )
        Toast.makeText(this, getString(R.string.gemini_command_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showPhraseCopyPicker() {
        PhrasePickerHelper.showPicker(this, phraseDb) { phrase ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(phrase.name, phrase.command)
            )
            Toast.makeText(this, getString(R.string.phrase_copied_toast, phrase.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlayHostDialog() {
        if (isPlayRunning) {
            Toast.makeText(this, getString(R.string.play_status_running_toast), Toast.LENGTH_SHORT).show()
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
                promptPlayParametersAndRun(play, host, onBack = { showPlaySelectionDialog(plays, host) })
            }
            .setPositiveButton(R.string.action_manage) { _, _ ->
                startActivity(Intent(this, PlaysActivity::class.java))
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    private fun promptPlayParametersAndRun(
        play: Play,
        host: SshConnectionConfig,
        onBack: (() -> Unit)? = null
    ) {
        val parameters = PlayParameters.decode(play.parametersJson).ifEmpty {
            PlayParameters.inferFromTemplate(play.scriptTemplate)
        }

        if (parameters.isEmpty()) {
            runPlay(play, host, emptyMap())
            return
        }

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val smallGap = (8 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, smallGap, pad, 0)
        }

        // Live command preview
        val previewView = android.widget.TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setBackgroundColor(
                android.graphics.Color.argb(20, 128, 128, 128)
            )
            setPadding(smallGap, smallGap, smallGap, smallGap)
            text = play.scriptTemplate
        }
        container.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = smallGap })

        val fields = linkedMapOf<PlayParameter, TextInputEditText>()

        parameters.forEachIndexed { index, parameter ->
            addParameterField(container, fields, parameter, index > 0)
        }

        // Helper that re-renders preview from current field values
        fun refreshPreview() {
            val current = fields.entries.associate { (param, input) ->
                val typed = input.text?.toString().orEmpty()
                param.key to typed.ifBlank { param.default.orEmpty() }.ifBlank { "…" }
            }
            val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")
            previewView.text = placeholderRegex.replace(play.scriptTemplate) { match ->
                current[match.groupValues[1]] ?: match.value
            }
        }

        // Attach TextWatchers after all fields are created
        fields.values.forEach { input ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { refreshPreview() }
            })
        }
        refreshPreview()

        val content = ScrollView(this).apply { addView(container) }

        val negLabel = if (onBack != null) R.string.play_param_back else R.string.phrase_cancel
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_prompt_title, play.name))
            .setView(content)
            .setPositiveButton(R.string.action_run_play, null)
            .setNegativeButton(negLabel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = collectAndValidatePlayValues(fields) ?: return@setOnClickListener
                dialog.dismiss()
                runPlay(play, host, values)
            }
            if (onBack != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    dialog.dismiss()
                    onBack()
                }
            }
        }
        dialog.show()
    }

    private fun addParameterField(
        container: LinearLayout,
        fields: MutableMap<PlayParameter, TextInputEditText>,
        parameter: PlayParameter,
        withTopMargin: Boolean
    ) {
        val dp = resources.displayMetrics.density
        val hint = if (parameter.required && parameter.default.isNullOrBlank()) {
            "${parameter.label} *"
        } else {
            parameter.label
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            parameter.description?.let { helperText = it }
                ?: parameter.example?.let { helperText = getString(R.string.play_param_example_prefix, it) }
        }
        val input = TextInputEditText(this).apply {
            inputType = if (parameter.secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
            parameter.default?.let { setText(it) }
        }
        layout.addView(input)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (withTopMargin) lp.topMargin = (12 * dp).toInt()
        container.addView(layout, lp)
        fields[parameter] = input
    }

    private fun collectAndValidatePlayValues(
        fields: Map<PlayParameter, TextInputEditText>
    ): Map<String, String>? {
        val values = mutableMapOf<String, String>()
        var hasError = false
        fields.forEach { (parameter, input) ->
            val value = input.text?.toString().orEmpty()
            val layout = input.parent.parent as? TextInputLayout
            layout?.error = null
            // Required with no value AND no default → show error (PlayRunner would also reject).
            if (parameter.required && value.isBlank() && parameter.default.isNullOrBlank()) {
                layout?.error = getString(R.string.play_parameter_required)
                hasError = true
            } else {
                values[parameter.key] = value
            }
        }
        return if (hasError) null else values
    }

    private fun runPlay(play: Play, host: SshConnectionConfig, values: Map<String, String>) {
        isPlayRunning = true
        updateSessionUi()
        appendSessionLog(getString(R.string.play_run_started, play.name, host.displayTarget()))

        lifecycleScope.launch(Dispatchers.IO) {
            val isLocal = host.kind == HostKind.LOCAL
            val backend: TerminalBackend = if (isLocal) {
                LocalShellBackend(this@MainActivity)
            } else {
                val config = sshSettings.resolveJumpServer(host.copy(privateKey = sshSettings.getPrivateKey()))
                SshClient(config)
            }

            // SSH backends require an active session before execCommand can be called.
            // LOCAL backends use ProcessBuilder internally and don't need a PTY session.
            if (!isLocal) {
                val connectResult = backend.connect(onLine = {})
                if (!connectResult.success) {
                    withContext(Dispatchers.Main) {
                        isPlayRunning = false
                        appendSessionLog(getString(R.string.play_run_failed, play.name, connectResult.message))
                        Toast.makeText(this@MainActivity, connectResult.message, Toast.LENGTH_SHORT).show()
                        updateSessionUi()
                    }
                    return@launch
                }
            }

            try {
                val result = PlayRunner.execute(
                    play = play,
                    backend = backend,
                    values = values,
                    onLine = { line -> appendSessionLog("[Play] ${line.trimEnd()}") }
                )
                withContext(Dispatchers.Main) {
                    isPlayRunning = false
                    if (result.success) {
                        appendSessionLog(getString(R.string.play_run_finished, play.name))
                    } else {
                        appendSessionLog(getString(R.string.play_run_failed, play.name, result.message))
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                    updateSessionUi()
                    uploadConsoleLogToDriveIfEnabled()
                }
            } finally {
                if (!isLocal) backend.disconnect()
            }
        }
    }

    private fun uploadConsoleLogToDriveIfEnabled() {
        if (!driveLogSettings.isAlwaysSaveEnabled()) return
        val account = driveAuthManager.getSignedInAccount() ?: return
        val logContent = consoleLogRepository.getLog()
        if (logContent.isBlank()) return
        driveLogUploader.uploadLog(account, logContent, DriveLogUploader.LogType.CONSOLE) { result ->
            val message = if (result.success) {
                getString(R.string.drive_upload_success)
            } else {
                getString(R.string.drive_upload_failed_detail, result.message)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun appendSessionLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendSessionLog(message) }
            return
        }

        consoleLogRepository.appendLine(message)
        playsPageBinding?.sessionLogText?.appendLog(message)
    }

    private fun refreshSessionLog() {
        val log = consoleLogRepository.getLog()
        val sessionLogText = playsPageBinding?.sessionLogText ?: return
        sessionLogText.clearLog()
        if (log.isBlank()) {
            sessionLogText.appendLog(getString(R.string.session_log_placeholder))
        } else {
            sessionLogText.appendLog(log)
        }
    }

    private fun copySessionLog() {
        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            Toast.makeText(this, getString(R.string.session_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.clipboard_session_log_label), log)
        )
        Toast.makeText(this, getString(R.string.session_log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun clearSessionLog() {
        consoleLogRepository.clear()
        refreshSessionLog()
        Toast.makeText(this, getString(R.string.session_log_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun refreshPlaysPageState() {
        val pageBinding = playsPageBinding ?: return
        val requestId = ++playsPageStateRequestId
        lifecycleScope.launch(Dispatchers.IO) {
            val hasHosts = sshSettings.getHosts().isNotEmpty()
            val hasPlays = playDb.getAllPlays().isNotEmpty()
            withContext(Dispatchers.Main) {
                if (requestId != playsPageStateRequestId) {
                    return@withContext
                }
                val messageRes = when {
                    !hasHosts -> R.string.main_plays_empty_add_host
                    !hasPlays -> R.string.main_plays_empty_add_play
                    else -> R.string.main_plays_empty_ready
                }
                pageBinding.playsEmptyStateText.text = getString(messageRes)
                pageBinding.playsEmptyStateText.visibility = View.VISIBLE
                pageBinding.playsEmptyActions.visibility = if (hasHosts && hasPlays) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                pageBinding.addHostButton.isEnabled = !hasHosts
                pageBinding.addPlayButton.isEnabled = hasHosts && !hasPlays
                pageBinding.playsButton.isEnabled = !isPlayRunning && hasHosts && hasPlays
            }
        }
    }

    private fun updateSessionUi() {
        val config = sshSettings.getConfigOrNull()
        val statusLabel = if (isPlayRunning) {
            getString(R.string.session_status_play_running)
        } else {
            getString(R.string.session_status_ready)
        }
        val helperText = if (config == null) {
            getString(R.string.status_helper_disconnected)
        } else if (isPlayRunning) {
            getString(R.string.status_helper_play_running)
        } else {
            getString(R.string.status_helper_ready)
        }

        binding.statusText.text = helperText
        binding.sessionStatusText.text = statusLabel

        val displayTarget = config?.displayTarget()
        binding.sessionTargetText.text = if (displayTarget.isNullOrBlank()) {
            getString(R.string.session_target_empty)
        } else {
            getString(R.string.session_target, displayTarget)
        }

        binding.startSessionButton.isEnabled = config != null
        binding.startSessionButton.text = if (displayTarget.isNullOrBlank()) {
            getString(R.string.action_start_session)
        } else {
            getString(R.string.action_start_session_host, displayTarget)
        }
        binding.returnTerminalButton.visibility = if (config == null) View.GONE else View.VISIBLE
        binding.configureHostButton.visibility = if (config == null) View.VISIBLE else View.GONE

        refreshPlaysPageState()
    }

    private fun setupChecklistClickHandlers() {
        val card = binding.setupChecklistCard
        card.checklistSshHostRow.setOnClickListener {
            startActivity(Intent(this, HostsActivity::class.java))
        }
        card.checklistSshKeyRow.setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }
        card.checklistGeminiRow.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        card.checklistDriveRow.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updateSetupChecklist() {
        val state = SetupChecklist.evaluate(sshSettings, geminiSettings, driveAuthManager)
        val card = binding.setupChecklistCard
        if (state.requiredComplete) {
            card.root.visibility = View.GONE
            return
        }
        card.root.visibility = View.VISIBLE

        val doneMarker = getString(R.string.setup_checklist_done_marker)
        val pendingMarker = getString(R.string.setup_checklist_pending_marker)
        val doneColor = getColor(R.color.sushi_green)
        val pendingColor = getColor(R.color.sushi_slate)

        fun applyRow(statusView: android.widget.TextView, done: Boolean) {
            statusView.text = if (done) doneMarker else pendingMarker
            statusView.setTextColor(if (done) doneColor else pendingColor)
        }

        applyRow(card.checklistSshHostStatus, state.hasSshHost)
        applyRow(card.checklistSshKeyStatus, state.hasSshKey)
        applyRow(card.checklistGeminiStatus, state.hasGeminiKey)
        applyRow(card.checklistDriveStatus, state.hasDriveAuth)
    }

    private inner class MainToolsPagerAdapter(
        private val onTerminalReady: (PageMainTerminalBinding) -> Unit,
        private val onPlaysReady: (PageMainPlaysBinding) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                PAGE_TERMINAL -> {
                    val pageBinding = PageMainTerminalBinding.inflate(layoutInflater, parent, false)
                    onTerminalReady(pageBinding)
                    TerminalPageHolder(pageBinding)
                }

                else -> {
                    val pageBinding = PageMainPlaysBinding.inflate(layoutInflater, parent, false)
                    onPlaysReady(pageBinding)
                    PlaysPageHolder(pageBinding)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Static page content.
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is TerminalPageHolder -> if (terminalPageBinding === holder.binding) {
                    terminalPageBinding = null
                }

                is PlaysPageHolder -> if (playsPageBinding === holder.binding) {
                    playsPageBinding = null
                }
            }
            super.onViewRecycled(holder)
        }
    }

    private class TerminalPageHolder(val binding: PageMainTerminalBinding) : RecyclerView.ViewHolder(binding.root)

    private class PlaysPageHolder(val binding: PageMainPlaysBinding) : RecyclerView.ViewHolder(binding.root)

    private fun getLastMainTab(): Int {
        val prefs = getSharedPreferences(PREFS_MAIN_UI, MODE_PRIVATE)
        return prefs.getInt(PREF_MAIN_TAB, PAGE_TERMINAL).coerceIn(PAGE_TERMINAL, PAGE_PLAYS)
    }

    private fun saveLastMainTab(index: Int) {
        getSharedPreferences(PREFS_MAIN_UI, MODE_PRIVATE)
            .edit()
            .putInt(PREF_MAIN_TAB, index.coerceIn(PAGE_TERMINAL, PAGE_PLAYS))
            .apply()
    }

    // ========== Conversation Management ==========

    private fun setupConnectionListener() {
        connectionListener = object : TerminalSessionHolder.ConnectionListener {
            override fun onConnected() {
                lifecycleScope.launch {
                    initializeConversation()
                }
            }

            override fun onDisconnected() {
                lifecycleScope.launch(Dispatchers.Main) {
                    conversationManager?.clearHistory()
                    conversationManager = null
                    updateConversationStatus(null)
                }
            }
        }
        TerminalSessionHolder.addListener(connectionListener!!)

        // Check if already connected
        if (TerminalSessionHolder.isConnected()) {
            lifecycleScope.launch {
                initializeConversation()
            }
        }
    }

    private suspend fun initializeConversation() {
        val backend = TerminalSessionHolder.getActiveBackend() ?: return

        withContext(Dispatchers.Main) {
            updateConversationStatus(getString(R.string.conversation_initializing))
        }

        val useNano = geminiSettings.getNanoPreferred() && isNanoAvailable()
        val activeConfig = sshSettings.getConfigOrNull()
        val hostLabel = when {
            activeConfig?.kind == HostKind.LOCAL -> activeConfig.alias.ifBlank { "Local shell" }
            activeConfig != null -> activeConfig.alias.ifBlank { "${activeConfig.username}@${activeConfig.host}" }
            else -> null
        }
        conversationManager = ConversationManager(
            context = this,
            backend = backend,
            geminiClient = geminiClient,
            geminiNanoClient = nanoClient,
            useNano = useNano,
            transcriptStore = GeminiTranscriptDatabaseHelper.getInstance(this),
            sessionId = java.util.UUID.randomUUID().toString(),
            hostId = activeConfig?.id,
            hostLabel = hostLabel
        )

        val initResult = withContext(Dispatchers.IO) {
            conversationManager?.initialize()
        }

        withContext(Dispatchers.Main) {
            if (initResult?.success == true) {
                val identity = initResult.systemIdentity ?: "Unknown System"
                updateConversationStatus(
                    getString(R.string.conversation_connected_to, identity)
                )

                if (initResult.isDefaultPersona) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tip: Run 'Initialize AI Persona' Play for better experience",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                updateConversationStatus(
                    getString(R.string.conversation_init_failed, initResult?.message ?: "Unknown error")
                )
            }
        }
    }

    private fun updateConversationStatus(status: String?) {
        terminalPageBinding?.geminiStatusText?.text = status
            ?: getString(R.string.gemini_status_disabled)
    }

    private fun handleUserMessage(message: String) {
        if (conversationManager == null || !conversationManager!!.isInitialized()) {
            Toast.makeText(
                this,
                getString(R.string.conversation_init_failed, "Not connected"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lastGeminiPrompt = message
        isGeminiRequestRunning = true
        updateGeminiDialogState()

        lifecycleScope.launch {
            var streamEntryIndex = -1
            try {
                val result = withContext(Dispatchers.IO) {
                    conversationManager!!.processUserMessage(message) { chunk ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            streamEntryIndex = appendStreamingChunk(streamEntryIndex, message, chunk)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false

                    if (!result.success) {
                        lastGeminiOutput = result.systemResponse
                        appendSessionLog("Error: ${result.systemResponse}")
                    } else {
                        lastGeminiOutput = result.systemResponse

                        when {
                            result.needsConfirmation -> {
                                showCommandConfirmationDialog(
                                    message,
                                    result.systemResponse,
                                    result.commandToConfirm!!
                                )
                            }

                            result.commandBlocked -> {
                                appendSessionLog("Blocked: ${result.commandAttempted}")
                            }

                            result.commandExecuted != null -> {
                                appendSessionLog(
                                    "Executed: ${result.commandExecuted}\n" +
                                    "Result: ${if (result.commandSuccess) "success" else "failed"}"
                                )
                            }
                        }
                    }

                    // If output streamed in while the command ran, replace it with the final
                    // (LLM-interpreted) response in place; otherwise this is a plain
                    // conversational turn with no command, so append a fresh entry.
                    if (streamEntryIndex >= 0 && streamEntryIndex < geminiTranscript.size) {
                        geminiTranscript[streamEntryIndex] = GeminiTranscriptEntry(
                            prompt = message,
                            response = result.systemResponse
                        )
                        transcriptAdapter?.notifyItemChanged(streamEntryIndex)
                    } else {
                        geminiTranscript.add(GeminiTranscriptEntry(
                            prompt = message,
                            response = result.systemResponse
                        ))
                        transcriptAdapter?.let { adapter ->
                            adapter.notifyItemInserted(geminiTranscript.size - 1)
                            geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
                            geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                                visibility = View.VISIBLE
                                scrollToPosition(geminiTranscript.size - 1)
                            }
                        }
                    }

                    updateGeminiDialogState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    updateGeminiDialogState()
                }
            }
        }
    }

    /**
     * Appends [chunk] to the transcript entry at [entryIndex], creating a new entry
     * (prompt=[prompt]) the first time a chunk arrives for a turn. Must run on the Main thread.
     * Returns the entry's index so the caller can track it across subsequent chunks.
     */
    private fun appendStreamingChunk(entryIndex: Int, prompt: String, chunk: String): Int {
        if (entryIndex >= 0 && entryIndex < geminiTranscript.size) {
            val current = geminiTranscript[entryIndex]
            geminiTranscript[entryIndex] = current.copy(response = current.response + chunk)
            transcriptAdapter?.notifyItemChanged(entryIndex)
            return entryIndex
        }

        geminiTranscript.add(GeminiTranscriptEntry(prompt = prompt, response = chunk))
        val idx = geminiTranscript.size - 1
        transcriptAdapter?.let { adapter ->
            adapter.notifyItemInserted(idx)
            geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
            geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                visibility = View.VISIBLE
                scrollToPosition(idx)
            }
        }
        return idx
    }

    private fun showCommandConfirmationDialog(
        userMessage: String,
        initialResponse: String,
        command: String
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.conversation_confirm_command_title))
            .setMessage(getString(R.string.conversation_confirm_command_message, command))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                executeConfirmedCommand(userMessage, initialResponse, command)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeConfirmedCommand(
        userMessage: String,
        initialResponse: String,
        command: String
    ) {
        isGeminiRequestRunning = true
        updateGeminiDialogState()

        lifecycleScope.launch {
            var streamEntryIndex = -1
            try {
                val result = withContext(Dispatchers.IO) {
                    conversationManager!!.executeConfirmedCommand(
                        userMessage,
                        initialResponse,
                        command
                    ) { chunk ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            streamEntryIndex = appendStreamingChunk(streamEntryIndex, userMessage, chunk)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    lastGeminiOutput = result.systemResponse

                    if (result.commandExecuted != null) {
                        appendSessionLog(
                            "Executed (confirmed): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                        )
                    }

                    val targetIdx = if (streamEntryIndex >= 0 && streamEntryIndex < geminiTranscript.size) {
                        streamEntryIndex
                    } else if (geminiTranscript.isNotEmpty()) {
                        geminiTranscript.size - 1
                    } else {
                        -1
                    }
                    if (targetIdx >= 0) {
                        geminiTranscript[targetIdx] = GeminiTranscriptEntry(
                            prompt = userMessage,
                            response = result.systemResponse
                        )
                        transcriptAdapter?.notifyItemChanged(targetIdx)
                    }

                    updateGeminiDialogState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing confirmed command", e)
                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    updateGeminiDialogState()
                }
            }
        }
    }

    private fun handleRawCommand(command: String) {
        if (conversationManager == null || !conversationManager!!.isInitialized()) {
            Toast.makeText(
                this,
                getString(R.string.conversation_init_failed, "Not connected"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        isGeminiRequestRunning = true
        updateGeminiDialogState()

        lifecycleScope.launch {
            var streamEntryIndex = -1
            try {
                val result = withContext(Dispatchers.IO) {
                    conversationManager!!.executeRawCommand(command) { chunk ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            streamEntryIndex = appendStreamingChunk(streamEntryIndex, "$ $command", chunk)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false

                    if (result.needsConfirmation) {
                        showRawCommandConfirmationDialog(command)
                        updateGeminiDialogState()
                        return@withContext
                    }

                    lastGeminiOutput = result.systemResponse

                    if (result.commandBlocked) {
                        appendSessionLog("Blocked (raw): $command")
                    } else if (result.commandExecuted != null) {
                        appendSessionLog(
                            "Executed (raw): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                        )
                    }

                    val entry = GeminiTranscriptEntry(prompt = "$ $command", response = result.systemResponse)
                    if (streamEntryIndex >= 0 && streamEntryIndex < geminiTranscript.size) {
                        geminiTranscript[streamEntryIndex] = entry
                        transcriptAdapter?.notifyItemChanged(streamEntryIndex)
                    } else {
                        geminiTranscript.add(entry)
                        transcriptAdapter?.let { adapter ->
                            adapter.notifyItemInserted(geminiTranscript.size - 1)
                            geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
                            geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                                visibility = View.VISIBLE
                                scrollToPosition(geminiTranscript.size - 1)
                            }
                        }
                    }

                    updateGeminiDialogState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing raw command", e)
                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    updateGeminiDialogState()
                }
            }
        }
    }

    private fun showRawCommandConfirmationDialog(command: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.conversation_confirm_command_title))
            .setMessage(getString(R.string.conversation_confirm_command_message, command))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                executeConfirmedRawCommand(command)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeConfirmedRawCommand(command: String) {
        isGeminiRequestRunning = true
        updateGeminiDialogState()

        lifecycleScope.launch {
            var streamEntryIndex = -1
            try {
                val result = withContext(Dispatchers.IO) {
                    conversationManager!!.executeConfirmedRawCommand(command) { chunk ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            streamEntryIndex = appendStreamingChunk(streamEntryIndex, "$ $command", chunk)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    lastGeminiOutput = result.systemResponse

                    if (result.commandExecuted != null) {
                        appendSessionLog(
                            "Executed (raw, confirmed): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                        )
                    }

                    val entry = GeminiTranscriptEntry(prompt = "$ $command", response = result.systemResponse)
                    if (streamEntryIndex >= 0 && streamEntryIndex < geminiTranscript.size) {
                        geminiTranscript[streamEntryIndex] = entry
                        transcriptAdapter?.notifyItemChanged(streamEntryIndex)
                    } else {
                        geminiTranscript.add(entry)
                        transcriptAdapter?.let { adapter ->
                            adapter.notifyItemInserted(geminiTranscript.size - 1)
                            geminiDialogBinding?.geminiTranscriptLabel?.visibility = View.VISIBLE
                            geminiDialogBinding?.geminiTranscriptRecycler?.apply {
                                visibility = View.VISIBLE
                                scrollToPosition(geminiTranscript.size - 1)
                            }
                        }
                    }

                    updateGeminiDialogState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing confirmed raw command", e)
                withContext(Dispatchers.Main) {
                    isGeminiRequestRunning = false
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    updateGeminiDialogState()
                }
            }
        }
    }

    private suspend fun isNanoAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val status = nanoClient.checkStatus()
                status == FeatureStatus.AVAILABLE
            }.getOrDefault(false)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PAGE_TERMINAL = 0
        private const val PAGE_PLAYS = 1
        private const val PREFS_MAIN_UI = "main_ui"
        private const val PREF_MAIN_TAB = "pref_main_tab"
    }
}
