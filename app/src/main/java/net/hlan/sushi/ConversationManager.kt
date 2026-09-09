package net.hlan.sushi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages conversational AI interaction with the target system.
 * Handles session state, conversation history, command execution, and persona integration.
 */
class ConversationManager(
    private val backend: TerminalBackend,
    // Not stored as properties: they exist to build the default [llm] below, which is the only
    // thing the manager talks to once constructed.
    geminiClient: GeminiClient?,
    geminiNanoClient: GeminiNanoClient?,
    useNano: Boolean = false,
    private val transcriptStore: GeminiTranscriptDatabaseHelper? = null,
    private val commandHistoryStore: CommandHistoryDatabaseHelper? = null,
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val hostId: String? = null,
    private val hostLabel: String? = null,
    /**
     * Description of the user's other saved hosts, built by
     * [ConversationContextBuilder.infrastructureSection], so the AI knows what else exists in
     * the setup and which system it is speaking as (roadmap v0.8.0 — multi-system awareness).
     */
    private val infrastructureContext: String = "",
    /**
     * How the manager reaches the model. Defaults to the configured Gemini client (on-device
     * when [useNano], cloud otherwise); instrumented and unit tests pass a scripted responder so
     * the troubleshooting chain can be tested without network access.
     */
    private val llm: ConversationLlm = ConversationLlm { message, promptContext, history ->
        if (useNano && geminiNanoClient != null) {
            geminiNanoClient.generateConversationalResponse(message, promptContext, history)
        } else if (geminiClient != null) {
            geminiClient.generateConversationalResponse(message, promptContext, history)
        } else {
            GeminiResult(false, "No Gemini client available")
        }
    }
) {
    private val personaClient = PersonaClient(backend)
    private val conversationHistory = mutableListOf<ConversationTurn>()
    
    private var isInitialized = false
    private var sushiMdContent: String? = null
    private var systemIdentity: String? = null
    private var currentLogShellPath: String? = null

    /**
     * Whether the AI may chain SAFE follow-up commands on its own (multi-step troubleshooting).
     * Mirrors the toggle in the Gemini dialog, so it can change mid-session.
     */
    var autoTroubleshootEnabled: Boolean = true

    /** Commands executed so far in the current troubleshooting chain. */
    private var chainStepsTaken = 0

    /**
     * Initialize the conversation session by reading SUSHI.md from target.
     * Should be called once after SSH connection is established.
     */
    suspend fun initialize(): ConversationInitResult {
        return withContext(Dispatchers.IO) {
            try {
                val initResult = personaClient.initialize()
                
                if (initResult.success) {
                    isInitialized = true
                    sushiMdContent = initResult.sushiMdContent
                    systemIdentity = initResult.systemIdentity
                    
                    // Initialize log file for this session
                    initializeLogFile()
                    
                    Log.d(TAG, "Conversation initialized. Identity: $systemIdentity")
                    ConversationInitResult(
                        success = true,
                        systemIdentity = systemIdentity ?: "Unknown System",
                        message = initResult.message,
                        isDefaultPersona = initResult.isDefault
                    )
                } else {
                    Log.w(TAG, "Failed to initialize conversation: ${initResult.message}")
                    ConversationInitResult(
                        success = false,
                        systemIdentity = null,
                        message = initResult.message
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing conversation", e)
                ConversationInitResult(
                    success = false,
                    systemIdentity = null,
                    message = "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Process a user message and generate a conversational response.
     * Executes commands if the LLM requests it.
     *
     * @param onChunk Optional callback for incremental output while a SAFE command executes,
     * forwarded to [TerminalBackend.execCommand] so the UI can show progress before the LLM's
     * interpretation of the final result is ready.
     */
    suspend fun processUserMessage(
        userMessage: String,
        onChunk: ((String) -> Unit)? = null
    ): ConversationResult {
        if (!isInitialized) {
            return ConversationResult(
                success = false,
                systemResponse = "Conversation not initialized. Please reconnect.",
                userMessage = userMessage
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // Get LLM response with SUSHI.md context
                val llmResult = generateLlmResponse(userMessage)
                
                if (!llmResult.success) {
                    return@withContext ConversationResult(
                        success = false,
                        systemResponse = llmResult.message,
                        userMessage = userMessage
                    )
                }

                // Parse response for EXECUTE: directive
                val response = llmResult.message
                val command = ExecuteDirective.parse(response)

                if (command != null) {
                    // Every user message starts a fresh troubleshooting chain.
                    chainStepsTaken = 0

                    // Classify command safety
                    val safety = CommandSafety.classify(command)
                    
                    when (safety) {
                        CommandSafety.SafetyLevel.BLOCKED -> {
                            // Command is blocked - don't execute
                            val explanation = CommandSafety.explainClassification(command)
                            val finalResponse = ExecuteDirective.strip(response) +
                                "\n\n[Command blocked: $explanation]"
                            
                            addToHistory(userMessage, finalResponse, command, null, false)
                            
                            ConversationResult(
                                success = true,
                                systemResponse = finalResponse,
                                userMessage = userMessage,
                                commandAttempted = command,
                                commandBlocked = true
                            )
                        }
                        
                        CommandSafety.SafetyLevel.CONFIRM -> {
                            // Command needs confirmation - return with flag
                            ConversationResult(
                                success = true,
                                systemResponse = response,
                                userMessage = userMessage,
                                commandToConfirm = command,
                                needsConfirmation = true
                            )
                        }
                        
                        CommandSafety.SafetyLevel.SAFE -> {
                            // Safe to execute automatically
                            executeCommandAndRespond(userMessage, response, command, onChunk)
                        }
                    }
                } else {
                    // No command to execute, just conversational response
                    addToHistory(userMessage, response, null, null, true)
                    
                    ConversationResult(
                        success = true,
                        systemResponse = response,
                        userMessage = userMessage
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                ConversationResult(
                    success = false,
                    systemResponse = "Error: ${e.message}",
                    userMessage = userMessage
                )
            }
        }
    }

    /**
     * Execute a command that was confirmed by the user.
     */
    suspend fun executeConfirmedCommand(
        userMessage: String,
        initialResponse: String,
        command: String,
        onChunk: ((String) -> Unit)? = null
    ): ConversationResult {
        return withContext(Dispatchers.IO) {
            executeCommandAndRespond(userMessage, initialResponse, command, onChunk)
        }
    }

    /**
     * Execute [command] and let the AI work the problem to its end (roadmap v0.8.0 —
     * AI-powered troubleshooting).
     *
     * Each step runs the command via [TerminalBackend.execCommand] (not [TerminalBackend.sendCommand])
     * so real stdout/stderr goes back to the LLM, which interprets the result and may ask for the
     * next diagnostic step with another `EXECUTE:` directive. Chaining continues while:
     * - [autoTroubleshootEnabled] is on,
     * - fewer than [MAX_TROUBLESHOOTING_STEPS] commands have run for this user message,
     * - the next command is classified SAFE, and
     * - it differs from the one just executed (an identical repeat means no progress).
     *
     * A CONFIRM step ends the run and comes back to the user for approval; confirming it calls
     * [executeConfirmedCommand], which resumes the chain from [chainStepsTaken]. A BLOCKED step
     * ends the run with an explanation.
     *
     * Every command that reaches the shell gets its own row in the local command history. The
     * conversation transcript stores one turn per run, whose narrative includes every command
     * line the run executed, so the run reads back in full even though the turn's
     * `commandExecuted` column names the last one.
     */
    private suspend fun executeCommandAndRespond(
        userMessage: String,
        initialResponse: String,
        command: String,
        onChunk: ((String) -> Unit)? = null
    ): ConversationResult = runTroubleshootingStep(
        run = TroubleshootingRun(
            userMessage = userMessage,
            narrative = StringBuilder(ExecuteDirective.strip(initialResponse)),
            onChunk = onChunk
        ),
        command = command,
        isChainedStep = false
    )

    /**
     * State shared by every step of one troubleshooting chain: the message that started it, the
     * narrative being assembled across steps, and where streamed output goes.
     */
    private class TroubleshootingRun(
        val userMessage: String,
        val narrative: StringBuilder,
        val onChunk: ((String) -> Unit)?
    )

    /**
     * Run one step of a troubleshooting chain, appending what happened to the run's narrative.
     *
     * Returns the finished [ConversationResult] when the run ends here; when the AI asks for a
     * SAFE follow-up command, recurses into the next step. Recursion (rather than a loop) keeps
     * each step's state immutable and is bounded by [MAX_TROUBLESHOOTING_STEPS].
     *
     * @param isChainedStep false for the command the user's message produced (or the one it
     * paused at for confirmation), true for every command the AI chose afterwards — those get
     * announced through the run's `onChunk` so the user sees the run progress.
     */
    private suspend fun runTroubleshootingStep(
        run: TroubleshootingRun,
        command: String,
        isChainedStep: Boolean
    ): ConversationResult {
        chainStepsTaken++

        // Every command goes into the narrative, so the stored transcript and the target-side
        // log show the whole run rather than only the last command (the one the transcript's
        // commandExecuted column names). Chained commands are also announced on the live
        // stream; the first one needs no announcement, because the response above it — already
        // streamed — is what explains it.
        run.narrative.appendBlock("$ $command")
        if (isChainedStep) {
            run.onChunk?.invoke("\n\n$ $command\n")
        }

        val cmdResult = try {
            backend.execCommand(command, onChunk = run.onChunk)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
            // The run itself failed here, not merely the command it was running.
            return finishRun(
                run = run,
                command = command,
                output = null,
                commandSucceeded = false,
                closingBlock = "[Error executing command: ${e.message}]"
            ).copy(success = false)
        }

        // Record before the failure branch below: a timed-out command still ran.
        recordInCommandHistory(command, cmdResult, CommandSource.CONVERSATION)

        if (!cmdResult.success && cmdResult.exitStatus == null) {
            // execCommand did not complete (not connected, or the command timed out).
            return finishRun(
                run = run,
                command = command,
                output = cmdResult.message,
                commandSucceeded = false,
                closingBlock = "[Command failed: ${cmdResult.message}]"
            )
        }

        // Command ran — use the captured output (may be empty for commands with no output).
        val output = cmdResult.message.ifEmpty { "(no output)" }

        val mayChain = autoTroubleshootEnabled && chainStepsTaken < MAX_TROUBLESHOOTING_STEPS
        val interpretResult = generateLlmResponse(
            buildInterpretPrompt(cmdResult.exitStatus, output, mayChain),
            includeInHistory = false
        )

        if (!interpretResult.success) {
            // Fallback: show raw output.
            return finishRun(
                run = run,
                command = command,
                output = output,
                commandSucceeded = cmdResult.success,
                closingBlock = "Command output:\n$output"
            )
        }

        val interpretation = interpretResult.message
        run.narrative.appendBlock(ExecuteDirective.strip(interpretation))

        val nextCommand = if (mayChain) ExecuteDirective.parse(interpretation) else null
        if (nextCommand == null || nextCommand == command) {
            if (nextCommand != null) {
                Log.d(TAG, "Stopping chain: next step repeats the command just run")
            }
            return finishRun(
                run = run,
                command = command,
                output = output,
                commandSucceeded = cmdResult.success
            )
        }

        return when (CommandSafety.classify(nextCommand)) {
            CommandSafety.SafetyLevel.BLOCKED -> finishRun(
                run = run,
                command = command,
                output = output,
                commandSucceeded = cmdResult.success,
                closingBlock = "[Command blocked: ${CommandSafety.explainClassification(nextCommand)}]"
            ).copy(commandAttempted = nextCommand, commandBlocked = true)

            // Pause the chain for user approval; executeConfirmedCommand resumes it. The turn is
            // not written to history yet — the resumed run persists the whole narrative.
            CommandSafety.SafetyLevel.CONFIRM -> ConversationResult(
                success = true,
                systemResponse = run.narrative.toString(),
                userMessage = run.userMessage,
                commandExecuted = command,
                commandOutput = output,
                commandSuccess = cmdResult.success,
                commandToConfirm = nextCommand,
                needsConfirmation = true
            )

            CommandSafety.SafetyLevel.SAFE -> runTroubleshootingStep(
                run = run,
                command = nextCommand,
                isChainedStep = true
            )
        }
    }

    /**
     * Close out a troubleshooting run: append [closingBlock] if there is one, persist the turn,
     * and build the result. Every exit from [runTroubleshootingStep] except the CONFIRM pause
     * goes through here, so the narrative and the stored transcript never diverge. The two
     * exits that need more than this — an outright run failure, and a blocked follow-up — copy
     * the returned result with those fields set.
     *
     * @param commandSucceeded whether the last command itself succeeded.
     */
    private suspend fun finishRun(
        run: TroubleshootingRun,
        command: String,
        output: String?,
        commandSucceeded: Boolean,
        closingBlock: String? = null
    ): ConversationResult {
        closingBlock?.let { run.narrative.appendBlock(it) }
        val response = run.narrative.toString()
        addToHistory(run.userMessage, response, command, output, commandSucceeded)

        return ConversationResult(
            success = true,
            systemResponse = response,
            userMessage = run.userMessage,
            commandExecuted = command,
            commandOutput = output,
            commandSuccess = commandSucceeded
        )
    }

    /** Append [block] to the running narrative, separated by a blank line. */
    private fun StringBuilder.appendBlock(block: String) {
        val trimmed = block.trim()
        if (trimmed.isEmpty()) return
        if (isNotEmpty()) append("\n\n")
        append(trimmed)
    }

    /**
     * Prompt asking the model to interpret a command result.
     *
     * When [mayChain] is true the model is invited to request the next diagnostic step, which is
     * what turns a single question into an end-to-end troubleshooting run; when the step budget
     * is spent (or the user turned chaining off) it is told to wrap up instead, so the run cannot
     * continue past its limit.
     */
    private fun buildInterpretPrompt(exitStatus: Int?, output: String, mayChain: Boolean): String {
        val continuation = if (mayChain) {
            """
If the problem is not yet diagnosed or fixed, say what you are checking next and add a line:
EXECUTE: <command>
If nothing further is needed, state the conclusion and do not add an EXECUTE line.
            """.trimIndent()
        } else {
            "Summarise where things stand. Do not request any further command."
        }

        return """
The command was executed. Exit code: ${exitStatus ?: "unknown"}.
Output:

$output

Provide a natural language interpretation of this result, responding as the system.
$continuation
        """.trimIndent()
    }

    /**
     * Persist a run that stopped at a confirmation prompt the user then declined.
     *
     * The CONFIRM pause returns before the turn is written, because approving it resumes the
     * same run and persists the whole narrative at the end. When the user declines instead,
     * nothing would ever record the steps that already executed — this closes that gap for the
     * transcript, the target-side log, and the in-memory history. Does nothing when the run had
     * not executed anything yet.
     */
    suspend fun persistDeclinedRun(result: ConversationResult) {
        val executed = result.commandExecuted ?: return
        val declined = result.commandToConfirm

        // No dispatcher here: addToHistory's writes each move themselves to IO.
        val response = if (declined != null) {
            "${result.systemResponse}\n\n[Not run: $declined — confirmation declined]"
        } else {
            result.systemResponse
        }
        addToHistory(
            result.userMessage,
            response,
            executed,
            result.commandOutput,
            result.commandSuccess
        )
    }

    /**
     * Run [command] directly against [backend], bypassing the LLM entirely (Raw Terminal Mode).
     * Still goes through [CommandSafety] and the same transcript/log persistence as AI-driven
     * commands so raw-mode activity remains visible in the conversation history.
     */
    suspend fun executeRawCommand(
        command: String,
        onChunk: ((String) -> Unit)? = null
    ): ConversationResult {
        return withContext(Dispatchers.IO) {
            when (val safety = CommandSafety.classify(command)) {
                CommandSafety.SafetyLevel.BLOCKED -> {
                    val explanation = CommandSafety.explainClassification(command)
                    val response = "[Command blocked: $explanation]"
                    addToHistory(rawPrompt(command), response, command, null, false)

                    ConversationResult(
                        success = true,
                        systemResponse = response,
                        userMessage = command,
                        commandAttempted = command,
                        commandBlocked = true
                    )
                }

                CommandSafety.SafetyLevel.CONFIRM -> ConversationResult(
                    success = true,
                    systemResponse = "",
                    userMessage = command,
                    commandToConfirm = command,
                    needsConfirmation = true
                )

                CommandSafety.SafetyLevel.SAFE -> executeRawCommandDirect(command, onChunk)
            }
        }
    }

    /**
     * Run a raw command that was confirmed by the user (CONFIRM tier).
     */
    suspend fun executeConfirmedRawCommand(
        command: String,
        onChunk: ((String) -> Unit)? = null
    ): ConversationResult {
        return withContext(Dispatchers.IO) {
            executeRawCommandDirect(command, onChunk)
        }
    }

    private suspend fun executeRawCommandDirect(
        command: String,
        onChunk: ((String) -> Unit)?
    ): ConversationResult {
        return try {
            val cmdResult = backend.execCommand(command, onChunk = onChunk)
            val output = cmdResult.message.ifEmpty { "(no output)" }
            recordInCommandHistory(command, cmdResult, CommandSource.RAW)
            addToHistory(rawPrompt(command), output, command, output, cmdResult.success)

            ConversationResult(
                success = true,
                systemResponse = output,
                userMessage = command,
                commandExecuted = command,
                commandOutput = output,
                commandSuccess = cmdResult.success
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing raw command", e)
            val response = "[Error executing command: ${e.message}]"
            addToHistory(rawPrompt(command), response, command, null, false)

            ConversationResult(
                success = false,
                systemResponse = response,
                userMessage = command,
                commandExecuted = command
            )
        }
    }

    /**
     * Prefix used so raw-mode turns read distinctly in the transcript/history browser
     * without needing a schema change to track an AI-vs-raw source column.
     */
    private fun rawPrompt(command: String): String = "$ $command"

    /**
     * Persist an executed command to the local command history (roadmap v0.8.0).
     *
     * Only commands that actually reached the shell are stored — see
     * [SshCommandResult.dispatched], which stays true for a command that timed out because it
     * ran and may have had side effects. BLOCKED commands never get here: they are rejected
     * before execution.
     */
    private fun recordInCommandHistory(
        command: String,
        result: SshCommandResult,
        source: CommandSource
    ) {
        val store = commandHistoryStore ?: return
        if (!result.dispatched) return

        runCatching {
            store.record(
                CommandHistoryRecord(
                    hostId = hostId.orEmpty(),
                    hostLabel = hostLabel.orEmpty(),
                    command = command,
                    outputSummary = CommandHistoryDatabaseHelper.summarizeOutput(result.message),
                    exitStatus = result.exitStatus,
                    success = result.success,
                    source = source,
                    timestamp = System.currentTimeMillis()
                )
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to record command history", e)
        }
    }

    /**
     * Recent commands on this host, rendered as a prompt section, or "" when history is
     * unavailable or empty.
     *
     * Restricted to [CommandSource.CONVERSATION] entries: those already passed through the model
     * once, so replaying them uploads nothing the model has not already seen.
     */
    private fun recentCommandsContext(): String {
        val store = commandHistoryStore ?: return ""
        val records = runCatching {
            // Only commands the AI itself issued are fed back. Raw Terminal Mode is advertised
            // as having no AI in the loop, and rendered Play commands can carry parameters the
            // user typed as secrets — neither may reach a cloud prompt through the back door.
            store.getRecentForHost(hostId.orEmpty(), sources = setOf(CommandSource.CONVERSATION))
        }.getOrElse { e ->
            Log.w(TAG, "Failed to read command history", e)
            return ""
        }
        return ConversationContextBuilder.recentCommandsSection(
            records = records,
            hostLabel = hostLabel.orEmpty()
        )
    }

    /**
     * Generate LLM response using configured client (Nano or Cloud).
     */
    private suspend fun generateLlmResponse(
        userMessage: String,
        includeInHistory: Boolean = true
    ): GeminiResult {
        val persona = sushiMdContent ?: return GeminiResult(
            false,
            "Persona context not available"
        )
        val promptContext = ConversationContextBuilder.compose(
            persona,
            infrastructureContext,
            recentCommandsContext()
        )

        val history = if (includeInHistory) conversationHistory else emptyList()

        return llm.respond(userMessage, promptContext, history)
    }

    /**
     * Add a conversation turn to history.
     */
    private suspend fun addToHistory(
        userMessage: String,
        systemResponse: String,
        commandExecuted: String?,
        commandOutput: String?,
        success: Boolean
    ) {
        val turn = ConversationTurn(
            timestamp = System.currentTimeMillis(),
            userMessage = userMessage,
            systemResponse = systemResponse,
            commandExecuted = commandExecuted,
            commandOutput = commandOutput,
            executionSuccess = success
        )
        
        conversationHistory.add(turn)

        // Keep last 10 turns only (manage memory)
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }

        // Write to target-side log file
        writeToLog(turn)

        // Persist to local SQLite history if a store is configured (G-6).
        persistTurn(turn)
    }

    private suspend fun persistTurn(turn: ConversationTurn) {
        val store = transcriptStore ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                store.appendEntry(
                    GeminiTranscriptRecord(
                        sessionId = sessionId,
                        hostId = hostId,
                        hostLabel = hostLabel,
                        timestamp = turn.timestamp,
                        userMessage = turn.userMessage,
                        geminiReply = turn.systemResponse,
                        commandExecuted = turn.commandExecuted,
                        commandOutput = turn.commandOutput,
                        success = turn.executionSuccess
                    )
                )
            }.onFailure { e ->
                Log.w(TAG, "Failed to persist transcript turn", e)
            }
        }
    }

    /**
     * Get the conversation history.
     */
    fun getHistory(): List<ConversationTurn> = conversationHistory.toList()

    /**
     * Clear conversation history (e.g., on new session).
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * Get the system identity name.
     */
    fun getSystemIdentity(): String? = systemIdentity

    /**
     * Check if conversation is initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Initialize a new log file for this conversation session on the remote host.
     *
     * Uses [TerminalBackend.execCommand] so we can detect failures. Commands are chained with
     * `&&` so the first failure short-circuits and [currentLogShellPath] is cleared.
     */
    private suspend fun initializeLogFile() {
        withContext(Dispatchers.IO) {
            runCatching {
                // Keep at least "/" if the configured dir is the filesystem root (or only
                // slashes) so trimming does not collapse it into an empty, invalid path.
                val logDir = resolveLogDir().trimEnd('/').ifEmpty { "/" }
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH_mm", Locale.US).format(Date())
                // Avoid a doubled slash when logDir is exactly the root.
                val logPath = if (logDir == "/") "/$timestamp.log" else "$logDir/$timestamp.log"

                val shellDir = SushiConfig.shellQuotePath(logDir)
                val shellLogPath = SushiConfig.shellQuotePath(logPath)
                currentLogShellPath = shellLogPath

                val safeIdentity = (systemIdentity ?: "Unknown").replace("'", "'\\''")
                val cmd = "mkdir -p $shellDir && " +
                    "printf '=== Sushi AI Conversation Log ===\\nSystem: %s\\n" +
                    "========================================\\n\\n' '$safeIdentity' > $shellLogPath"

                val result = backend.execCommand(cmd)
                if (!result.success) {
                    Log.w(TAG, "Failed to initialize log file: ${result.message}")
                    currentLogShellPath = null
                } else {
                    Log.d(TAG, "Initialized log file: $logPath")
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to initialize log file", e)
                currentLogShellPath = null
            }
        }
    }

    /**
     * Resolve the directory conversation logs are written to.
     *
     * Reads the `log_dir` key from `~/.config/sushi/config.conf` on the target and honours it,
     * so users can direct logs to a mount point, RAM disk, or network share. Falls back to the
     * default `~/.sushi_logs` when the config is missing, unreadable, or does not set the key.
     */
    private suspend fun resolveLogDir(): String {
        val result = runCatching {
            backend.execCommand("cat ~/.config/sushi/config.conf 2>/dev/null")
        }.getOrNull()
        if (result == null || !result.success) return SushiConfig.DEFAULT_LOG_DIR
        return SushiConfig.parseLogDir(result.message) ?: SushiConfig.DEFAULT_LOG_DIR
    }

    /**
     * Write a conversation turn to the log file on the target system.
     */
    private suspend fun writeToLog(turn: ConversationTurn) {
        val shellLogPath = currentLogShellPath ?: return

        withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(Date(turn.timestamp))
                
                val logEntry = buildString {
                    append("[$timestamp]\n")
                    append("USER: ${turn.userMessage}\n")
                    
                    if (turn.commandExecuted != null) {
                        append("COMMAND: ${turn.commandExecuted}\n")
                        if (turn.commandOutput != null) {
                            append("OUTPUT: ${turn.commandOutput.take(500)}")
                            if (turn.commandOutput.length > 500) append("...")
                            append("\n")
                        }
                        append("STATUS: ${if (turn.executionSuccess) "SUCCESS" else "FAILED"}\n")
                    }
                    
                    append("SYSTEM: ${turn.systemResponse}\n")
                    append("\n")
                }
                
                // Escape single quotes for POSIX shell single-quote strings.
                val escapedEntry = logEntry.replace("'", "'\\''")

                // Use execCommand so that append failures surface as errors rather than
                // silently mixing into the interactive PTY stream.
                backend.execCommand("printf '%s' '$escapedEntry' >> $shellLogPath")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write to log file", e)
            }
        }
    }

    companion object {
        private const val TAG = "ConversationManager"

        /**
         * Commands a single user message may trigger, including the first one. Bounds an
         * automatic troubleshooting run so a model that keeps asking for "one more check"
         * cannot execute indefinitely.
         */
        const val MAX_TROUBLESHOOTING_STEPS = 5
    }
}

/**
 * Result of conversation initialization.
 */
data class ConversationInitResult(
    val success: Boolean,
    val systemIdentity: String?,
    val message: String,
    val isDefaultPersona: Boolean = false
)

/**
 * Result of processing a user message.
 */
data class ConversationResult(
    val success: Boolean,
    val systemResponse: String,
    val userMessage: String,
    val commandExecuted: String? = null,
    val commandOutput: String? = null,
    val commandSuccess: Boolean = true,
    val commandToConfirm: String? = null,
    val needsConfirmation: Boolean = false,
    val commandAttempted: String? = null,
    val commandBlocked: Boolean = false
)
