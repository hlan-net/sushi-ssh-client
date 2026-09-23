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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.conversation.AppConversationEnvironment
import net.hlan.sushi.conversation.ConversationEvent
import net.hlan.sushi.conversation.ConversationScreen
import net.hlan.sushi.conversation.ConversationScreenActions
import net.hlan.sushi.conversation.ConversationStatus
import net.hlan.sushi.conversation.ConversationViewModel
import net.hlan.sushi.databinding.ActivityMainBinding
import net.hlan.sushi.databinding.PageMainPlaysBinding
import net.hlan.sushi.databinding.PageMainTerminalBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    // Application context: geminiClient only uses it for getString(), and the copy handed to
    // AppConversationEnvironment is kept by ConversationViewModel across rotation — an Activity
    // context there would leak this (destroyed) instance. nanoClient is a process-wide
    // singleton (GeminiClients.nano) rather than an Activity-scoped one: see its kdoc for why.
    private val geminiClient by lazy { GeminiClient(applicationContext, geminiSettings, driveAuthManager) }
    private val nanoClient by lazy { GeminiClients.nano(applicationContext) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }
    // Application context, for the same reason as geminiClient above: the copy handed to
    // AppConversationEnvironment is kept by ConversationViewModel across rotation, so an
    // Activity context here would keep this (destroyed) instance reachable through it.
    private val sshSettings by lazy { SshSettings(applicationContext) }
    private val playDb by lazy { PlayDatabaseHelper.getInstance(this) }
    private val phraseDb by lazy { PhraseDatabaseHelper.getInstance(this) }
    private val commandHistoryDb by lazy { CommandHistoryDatabaseHelper.getInstance(this) }

    private var isPlayRunning = false
    private var lastRenderedStatus: ConversationStatus? = null
    private var playsPageBinding: PageMainPlaysBinding? = null
    private var terminalPageBinding: PageMainTerminalBinding? = null
    private var toolsTabMediator: TabLayoutMediator? = null
    private var toolsPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var playsPageStateRequestId = 0

    /**
     * The Gemini availability text [ConversationScreen] falls back to when
     * [ConversationStatus.Disconnected] — computed from [geminiSettings]/[geminiClient]/
     * [nanoClient], which is Activity-level environment info the ViewModel does not own.
     * Written by [updateGeminiState]; a Compose [androidx.compose.runtime.State] so the screen
     * recomposes when it changes.
     */
    private val geminiAvailabilityStatus = mutableStateOf("")

    /**
     * The AI conversation as a full-screen Compose destination (ROADMAP.md v0.9.0), mounted
     * over [binding]'s content the same way a `DialogFragment` would sit over it, but as a
     * plain `ComposeView` per `CLAUDE.md`'s migration pattern — added once in [onCreate] via
     * [addContentView] and toggled with [View.VISIBLE]/[View.GONE] rather than recreated on
     * every open, so [ConversationViewModel]'s state survives being shown and hidden.
     */
    private val conversationComposeView by lazy { ComposeView(this) }

    private val conversationBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideConversationScreen()
    }

    /**
     * Owns the AI conversation (ROADMAP.md v0.9.0). Survives rotation; the activity mounts
     * its state into [conversationComposeView] and the terminal page's status line.
     */
    private val conversationViewModel: ConversationViewModel by lazy {
        val environment = AppConversationEnvironment(
            context = this,
            geminiSettings = geminiSettings,
            geminiClient = geminiClient,
            nanoClient = nanoClient,
            sshSettings = sshSettings
        )
        ViewModelProvider(this, ConversationViewModel.Factory(environment))[ConversationViewModel::class.java]
    }

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

        conversationViewModel.send(voiceText)
    }

    /**
     * Re-run request coming back from [CommandHistoryActivity]. The command goes through the
     * same raw-mode path as a user-typed one, so [CommandSafety] still classifies it. With no
     * live conversation there is nothing to run it on, so it is copied to the clipboard instead.
     */
    private val commandHistoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val command = result.data
            ?.getStringExtra(CommandHistoryActivity.EXTRA_RERUN_COMMAND)
            ?.trim()
        if (command.isNullOrEmpty()) {
            return@registerForActivityResult
        }

        if (conversationViewModel.state.value.status !is ConversationStatus.Connected) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.command_history_title), command)
            )
            Toast.makeText(
                this,
                R.string.command_history_rerun_needs_connection,
                Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }

        val recordedHostId = result.data
            ?.getStringExtra(CommandHistoryActivity.EXTRA_RERUN_HOST_ID)
            .orEmpty()
        val recordedHostLabel = result.data
            ?.getStringExtra(CommandHistoryActivity.EXTRA_RERUN_HOST_LABEL)
            .orEmpty()
        val activeHostId = sshSettings.getActiveHostId().orEmpty()

        // A command recorded on one host can be wrong or destructive on another (different
        // paths, services, or data), and CommandSafety classifies the command text alone —
        // it cannot see which system it was meant for. Ask before crossing hosts.
        val crossesHosts = recordedHostId.isNotEmpty() &&
            activeHostId.isNotEmpty() &&
            recordedHostId != activeHostId

        if (crossesHosts) {
            confirmCrossHostRerun(command, recordedHostLabel)
        } else {
            showConversationScreen()
            conversationViewModel.sendRaw(command)
        }
    }

    /**
     * Ask before re-running a command on a host other than the one it was recorded on.
     */
    private fun confirmCrossHostRerun(command: String, recordedHostLabel: String) {
        val recorded = recordedHostLabel.ifBlank {
            getString(R.string.command_history_unknown_host)
        }
        val current = conversationViewModel.state.value.hostLabel
            ?: getString(R.string.command_history_unknown_host)

        AlertDialog.Builder(this)
            .setTitle(R.string.command_history_rerun_other_host_title)
            .setMessage(
                getString(
                    R.string.command_history_rerun_other_host_message,
                    recorded,
                    current,
                    command
                )
            )
            .setPositiveButton(R.string.command_history_action_rerun) { _, _ ->
                showConversationScreen()
                conversationViewModel.sendRaw(command)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpConversationScreen()
        onBackPressedDispatcher.addCallback(this, conversationBackCallback)

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

        observeConversation()

        if (!isChangingConfigurations) {
            maybeResumePendingGitHubSignIn()
        }
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
        // Not nanoClient.close() here: nanoClient is the process-wide GeminiClients singleton
        // (see its kdoc), so nothing scoped to one Activity or ViewModel — including this
        // onDestroy() and ConversationViewModel.onCleared() — closes it. Closing it from here
        // would leave the next MainActivity/ConversationViewModel that resolves the same
        // singleton (whether after a rotation or after this Activity is simply relaunched) with
        // an already-closed model, since GeminiClients.nano() would keep returning it.
    }

    private fun maybeResumePendingGitHubSignIn() {
        val feedbackSettings = FeedbackSettings(this)
        if (feedbackSettings.isConfigured()) {
            return
        }
        val flowState = feedbackSettings.getPendingGitHubDeviceFlow() ?: return
        if (flowState.toDeviceCode() == null) {
            feedbackSettings.clearPendingGitHubDeviceFlow()
            return
        }
        startActivity(Intent(this, SettingsActivity::class.java))
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
            showConversationScreen()
        }
        pageBinding.phrasesButton.setOnClickListener {
            showPhraseCopyPicker()
        }
        pageBinding.commandHistoryButton.setOnClickListener {
            commandHistoryLauncher.launch(CommandHistoryActivity.createIntent(this))
        }
        pageBinding.downloadFileButton.setOnClickListener {
            startActivity(Intent(this, SftpDownloadActivity::class.java))
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
     * Updates the Gemini status text shown on the terminal page and, via
     * [geminiAvailabilityStatus], in [ConversationScreen]. Checks Nano status asynchronously so
     * the check doesn't block the UI thread.
     */
    private fun updateGeminiState() {
        if (!geminiSettings.isEnabled()) {
            val status = getString(R.string.gemini_status_disabled)
            terminalPageBinding?.geminiStatusText?.text = status
            geminiAvailabilityStatus.value = status
            applyConversationStatus()
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
        geminiAvailabilityStatus.value = cloudStatus
        applyConversationStatus()

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
                geminiAvailabilityStatus.value = statusText
                applyConversationStatus()
            }
        }
    }

    private fun handleGeminiVoice() {
        val missingReason = when {
            !geminiSettings.isEnabled() -> R.string.gemini_status_disabled
            geminiClient.getAuthMode() == GeminiClient.AuthMode.NONE -> R.string.gemini_status_missing_key
            else -> null
        }
        if (missingReason != null) {
            Toast.makeText(this, missingReason, Toast.LENGTH_LONG).show()
            startActivity(SettingsActivity.createIntent(this, SettingsActivity.TAB_GEMINI))
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
     * Adds [conversationComposeView] over [binding]'s content and gives it its content once;
     * shown and hidden afterward with [showConversationScreen]/[hideConversationScreen] rather
     * than recreated, so [ConversationViewModel] keeps one live collector for the screen's
     * lifetime instead of a new one per open.
     */
    private fun setUpConversationScreen() {
        conversationComposeView.visibility = View.GONE
        addContentView(
            conversationComposeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        conversationComposeView.setContent {
            val state by conversationViewModel.state.collectAsStateWithLifecycle()
            val availabilityStatus by geminiAvailabilityStatus
            ConversationScreen(
                state = state,
                availabilityStatus = availabilityStatus,
                actions = ConversationScreenActions(
                    onBack = ::hideConversationScreen,
                    onSend = conversationViewModel::send,
                    onVoice = ::handleGeminiVoice,
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onHistory = { startActivity(GeminiHistoryActivity.createIntent(this)) },
                    onCopy = ::copyGeminiCommand,
                    onRawModeChange = conversationViewModel::setRawMode,
                    onAutoTroubleshootChange = conversationViewModel::setAutoTroubleshoot,
                    onConfirmPending = conversationViewModel::confirmPending,
                    onDeclinePending = conversationViewModel::declinePending
                )
            )
        }
    }

    private fun showConversationScreen() {
        conversationComposeView.visibility = View.VISIBLE
        conversationBackCallback.isEnabled = true
    }

    private fun hideConversationScreen() {
        conversationComposeView.visibility = View.GONE
        conversationBackCallback.isEnabled = false
    }

    private fun copyGeminiCommand() {
        val output = conversationViewModel.state.value.lastOutput
        if (output.isBlank()) {
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.gemini_output_label), output)
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

    /**
     * Record a Play's rendered command in the local command history, so Plays appear alongside
     * AI- and raw-mode commands (roadmap v0.8.0). Called from the IO thread; a Play rejected
     * before rendering (missing required parameter) has no command to record, and one whose
     * command never reached a shell ([PlayRunResult.dispatched]) is not a command that ran.
     *
     * A Play that substituted a `secret` parameter into its command is skipped entirely
     * ([PlayRunResult.carriesSecretValues]): the rendered command holds the typed value in
     * plaintext — the managed "change user password" play is exactly that — and the history is
     * an unencrypted database that is searched, copied to the clipboard and re-run. Storing a
     * redacted command instead is worse than storing nothing, because re-running it would send
     * the mask to the shell as if it were the password. This is the same reason the interactive
     * terminal's keystrokes are not recorded.
     */
    private fun recordPlayInCommandHistory(host: SshConnectionConfig, result: PlayRunResult) {
        if (result.renderedCommand.isBlank() || !result.dispatched) {
            return
        }
        if (result.carriesSecretValues) {
            Log.d(TAG, "Not recording play in command history: command carries a secret value")
            return
        }
        runCatching {
            commandHistoryDb.record(
                CommandHistoryRecord(
                    hostId = host.id,
                    hostLabel = HostLabels.shortLabel(this, host),
                    command = result.renderedCommand,
                    outputSummary = CommandHistoryDatabaseHelper.summarizeOutput(
                        result.outputLines.joinToString("\n")
                    ),
                    exitStatus = result.exitStatus,
                    success = result.success,
                    source = CommandSource.PLAY,
                    timestamp = System.currentTimeMillis()
                )
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to record play in command history", e)
        }
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
                SshClient(
                    config,
                    DialogUserInfo(this@MainActivity, config.displayTarget(), KeyPassphraseCache(this@MainActivity)),
                    SshKnownHosts.file(this@MainActivity)
                )
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
                recordPlayInCommandHistory(host, result)
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
        playsPageBinding?.sessionLogText?.appendLogLine(message)
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
        binding.configureHostButton.text = if (config == null) {
            getString(R.string.action_configure_host)
        } else {
            getString(R.string.action_switch_host)
        }

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
        val doneColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, getColor(R.color.sushi_green))
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

    /**
     * Mirror [ConversationViewModel]'s status into the terminal page's status line, and act on
     * its one-off events. [ConversationScreen] collects the rest of the state itself, directly
     * from the ViewModel, so it isn't mirrored here.
     */
    private fun observeConversation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { conversationViewModel.state.collect { renderConversationStatus(it.status) } }
                launch { conversationViewModel.events.collect { handleConversationEvent(it) } }
            }
        }
    }

    /**
     * The terminal page's status line: the conversation's state while a session exists,
     * otherwise the Gemini availability text from [updateGeminiState].
     */
    private fun renderConversationStatus(status: ConversationStatus) {
        if (status == lastRenderedStatus) return
        lastRenderedStatus = status
        if (status is ConversationStatus.Disconnected) {
            updateGeminiState()
        } else {
            applyConversationStatus()
        }
    }

    private fun applyConversationStatus() {
        val text = when (val status = conversationViewModel.state.value.status) {
            ConversationStatus.Disconnected -> return
            ConversationStatus.Initializing -> getString(R.string.conversation_initializing)
            is ConversationStatus.Connected -> getString(R.string.conversation_connected_to, status.identity)
            is ConversationStatus.Failed -> getString(R.string.conversation_init_failed, status.message)
        }
        terminalPageBinding?.geminiStatusText?.text = text
    }

    private fun handleConversationEvent(event: ConversationEvent) {
        when (event) {
            is ConversationEvent.LogLine -> appendSessionLog(event.text)
            is ConversationEvent.CommandGenerated ->
                appendSessionLog(getString(R.string.gemini_log_entry, event.prompt, event.command))
            is ConversationEvent.Error ->
                Toast.makeText(this, "Error: ${event.message}", Toast.LENGTH_LONG).show()
            ConversationEvent.NotConnected -> Toast.makeText(
                this,
                getString(R.string.conversation_init_failed, "Not connected"),
                Toast.LENGTH_SHORT
            ).show()
            ConversationEvent.DefaultPersonaUsed -> Toast.makeText(
                this,
                "Tip: Run 'Initialize AI Persona' Play for better experience",
                Toast.LENGTH_LONG
            ).show()
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
