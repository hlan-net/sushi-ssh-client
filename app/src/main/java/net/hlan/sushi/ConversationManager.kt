package net.hlan.sushi

import android.content.Context
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
    private val context: Context,
    private val sshClient: SshClient,
    private val geminiClient: GeminiClient?,
    private val geminiNanoClient: GeminiNanoClient?,
    private val useNano: Boolean = false
) {
    private val personaClient = PersonaClient(sshClient)
    private val conversationHistory = mutableListOf<ConversationTurn>()
    
    private var isInitialized = false
    private var sushiMdContent: String? = null
    private var systemIdentity: String? = null
    private var currentLogFilePath: String? = null

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
     */
    suspend fun processUserMessage(userMessage: String): ConversationResult {
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
                val executeMatch = Regex("EXECUTE:\\s*(.+?)(?:\n|$)", RegexOption.MULTILINE)
                    .find(response)
                
                if (executeMatch != null) {
                    val command = executeMatch.groupValues[1].trim()
                    
                    // Classify command safety
                    val safety = CommandSafety.classify(command)
                    
                    when (safety) {
                        CommandSafety.SafetyLevel.BLOCKED -> {
                            // Command is blocked - don't execute
                            val explanation = CommandSafety.explainClassification(command)
                            val finalResponse = response.replace(executeMatch.value, "")
                                .trim() + "\n\n[Command blocked: $explanation]"
                            
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
                            executeCommandAndRespond(userMessage, response, command)
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
        command: String
    ): ConversationResult {
        return withContext(Dispatchers.IO) {
            executeCommandAndRespond(userMessage, initialResponse, command)
        }
    }

    /**
     * Execute command via SSH exec channel and generate final conversational response.
     *
     * Uses [SshClient.execCommand] (not [SshClient.sendCommand]) so that actual stdout/stderr
     * is captured and returned to the LLM for interpretation.
     */
    private suspend fun executeCommandAndRespond(
        userMessage: String,
        initialResponse: String,
        command: String
    ): ConversationResult {
        return try {
            // Use execCommand so we get real output back, not just "Command sent".
            val cmdResult = sshClient.execCommand(command)

            if (!cmdResult.success && cmdResult.exitStatus == null) {
                // execCommand itself failed (e.g. not connected, timed out).
                val errorResponse = initialResponse.replace(
                    Regex("EXECUTE:.+"),
                    "[Command failed: ${cmdResult.message}]"
                )
                addToHistory(userMessage, errorResponse, command, null, false)

                return ConversationResult(
                    success = true,
                    systemResponse = errorResponse,
                    userMessage = userMessage,
                    commandExecuted = command,
                    commandOutput = cmdResult.message,
                    commandSuccess = false
                )
            }

            // Command ran — use the captured output (may be empty for commands with no output).
            val output = cmdResult.message.ifEmpty { "(no output)" }
            val interpretPrompt = """
The command was executed. Exit code: ${cmdResult.exitStatus ?: "unknown"}.
Output:

$output

Provide a natural language interpretation of this result, responding as the system.
            """.trimIndent()

            val interpretResult = generateLlmResponse(interpretPrompt, includeInHistory = false)
            
            val finalResponse = if (interpretResult.success) {
                // Remove EXECUTE: line from initial response and add interpretation
                val cleanInitial = initialResponse.replace(Regex("EXECUTE:.+"), "").trim()
                "$cleanInitial\n\n${interpretResult.message}"
            } else {
                // Fallback: show raw output
                val cleanInitial = initialResponse.replace(Regex("EXECUTE:.+"), "").trim()
                "$cleanInitial\n\nCommand output:\n$output"
            }

            addToHistory(userMessage, finalResponse, command, output, true)
            
            ConversationResult(
                success = true,
                systemResponse = finalResponse,
                userMessage = userMessage,
                commandExecuted = command,
                commandOutput = output,
                commandSuccess = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
            val errorResponse = "$initialResponse\n\n[Error executing command: ${e.message}]"
            addToHistory(userMessage, errorResponse, command, null, false)
            
            ConversationResult(
                success = false,
                systemResponse = errorResponse,
                userMessage = userMessage,
                commandExecuted = command
            )
        }
    }

    /**
     * Generate LLM response using configured client (Nano or Cloud).
     */
    private suspend fun generateLlmResponse(
        userMessage: String,
        includeInHistory: Boolean = true
    ): GeminiResult {
        val context = sushiMdContent ?: return GeminiResult(
            false,
            "Persona context not available"
        )
        
        val history = if (includeInHistory) conversationHistory else emptyList()

        return if (useNano && geminiNanoClient != null) {
            geminiNanoClient.generateConversationalResponse(userMessage, context, history)
        } else if (geminiClient != null) {
            geminiClient.generateConversationalResponse(userMessage, context, history)
        } else {
            GeminiResult(false, "No Gemini client available")
        }
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
     * Uses [SshClient.execCommand] so we can detect failures. Commands are chained with
     * `&&` so the first failure short-circuits and [currentLogFilePath] is cleared.
     */
    private suspend fun initializeLogFile() {
        withContext(Dispatchers.IO) {
            runCatching {
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH_mm", Locale.US).format(Date())
                val logPath = "~/.sushi_logs/$timestamp.log"
                currentLogFilePath = logPath

                val safeIdentity = (systemIdentity ?: "Unknown").replace("'", "'\\''")
                val cmd = "mkdir -p ~/.sushi_logs && " +
                    "printf '=== Sushi AI Conversation Log ===\\n" +
                    "========================================\\n\\n' > '" + logPath + "'"

                val result = sshClient.execCommand(cmd)
                if (!result.success) {
                    Log.w(TAG, "Failed to initialize log file: ${result.message}")
                    currentLogFilePath = null
                } else {
                    Log.d(TAG, "Initialized log file: $logPath")
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to initialize log file", e)
                currentLogFilePath = null
            }
        }
    }

    /**
     * Write a conversation turn to the log file on the target system.
     */
    private suspend fun writeToLog(turn: ConversationTurn) {
        val logPath = currentLogFilePath ?: return
        
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
                sshClient.execCommand("printf '%s' '$escapedEntry' >> '$logPath'")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write to log file", e)
            }
        }
    }

    companion object {
        private const val TAG = "ConversationManager"
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
