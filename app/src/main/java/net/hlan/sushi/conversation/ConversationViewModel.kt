package net.hlan.sushi.conversation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hlan.sushi.ConversationManager
import net.hlan.sushi.ConversationResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the AI conversation: the transcript, streaming output, the CONFIRM state and the raw /
 * auto-troubleshoot toggles, as one [StateFlow] the screen renders (`ROADMAP.md` v0.9.0).
 *
 * The ViewModel attaches to whatever SSH session [connection] reports, builds a
 * [ConversationManager] for it through [environment], and survives the activity's
 * recreation — so a rotation no longer tears the conversation down. Every mutation goes
 * through [MutableStateFlow.update], which is atomic, so output chunks arriving on the IO
 * thread and completions on the main thread never race on the transcript. No dispatcher is
 * threaded through here: every suspend function this class calls, on [environment] and on
 * [ConversationManager] alike, is main-safe and dispatches its own I/O.
 */
class ConversationViewModel(
    private val environment: ConversationEnvironment,
    private val connection: ActiveConnection
) : ViewModel() {

    private val _state = MutableStateFlow(
        ConversationUiState(autoTroubleshoot = environment.autoTroubleshootEnabled)
    )
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val _events = Channel<ConversationEvent>(Channel.BUFFERED)
    val events: Flow<ConversationEvent> = _events.receiveAsFlow()

    private var manager: ConversationManager? = null

    /**
     * The host the previous conversation ran on. Kept across disconnects — a host switch is a
     * disconnect followed by a connect, so the state's `hostLabel` is already null by the time
     * the new conversation starts and cannot be used to detect the change.
     */
    private var lastHostId: String? = null
    private var lastHostLabel: String? = null

    private val turnIds = AtomicLong(0)

    /**
     * Scope for everything bound to the current SSH session — [initializeSession] and every
     * AI/raw-mode run. A fresh child of [viewModelScope]'s job on each connect, cancelled on
     * disconnect (and superseded by a new one on the next connect), so a coroutine started
     * against a backend that has since gone away cannot publish its result: without this, a
     * request in flight when the session drops could complete afterward and push `Connected`
     * or a transcript row for a session the user is no longer in.
     */
    private var sessionScope: CoroutineScope = newSessionScope()

    private val listener = object : ActiveConnection.Listener {
        override fun onConnected() {
            sessionScope = newSessionScope()
            sessionScope.launch { initializeSession() }
        }

        override fun onDisconnected() {
            // Not suspend, and callbacks may arrive on any thread; run it inline so the scope
            // is cancelled and the state cleared before anything else can observe either.
            clearSession()
        }
    }

    init {
        connection.addListener(listener)
        if (connection.isConnected()) {
            sessionScope.launch { initializeSession() }
        }
    }

    override fun onCleared() {
        connection.removeListener(listener)
        sessionScope.cancel()
        environment.close()
    }

    private fun newSessionScope(): CoroutineScope =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    // ---------------------------------------------------------------- intents

    /**
     * Send what the user typed or said. Raw mode runs it as a shell command; a live session
     * hands it to the AI; with no session it becomes a command suggestion, as before v0.7.
     *
     * A session that exists but has not finished initialising (still connecting, or its
     * persona failed to load) still goes to [runMessage]: [ConversationManager.processUserMessage]
     * itself returns a "not initialized" [ConversationResult] in that case, which the normal
     * turn/log handling below surfaces. Only the absence of a session at all — [current] null
     * — falls back to a standalone suggestion; a failed session must not silently behave as if
     * none existed.
     */
    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.isBusy) return
        val current = manager
        when {
            _state.value.isRawMode -> sendRaw(message)
            current != null -> runMessage(current, message)
            else -> generateStandalone(message)
        }
    }

    /** Run [command] against the shell regardless of the raw-mode toggle (history re-run). */
    fun sendRaw(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || _state.value.isBusy) return
        val current = manager
        if (current == null || !current.isInitialized()) {
            _events.trySend(ConversationEvent.NotConnected)
            return
        }
        runRaw(current, trimmed)
    }

    fun setRawMode(enabled: Boolean) {
        _state.update { it.copy(isRawMode = enabled) }
    }

    fun setAutoTroubleshoot(enabled: Boolean) {
        environment.autoTroubleshootEnabled = enabled
        manager?.autoTroubleshootEnabled = enabled
        _state.update { it.copy(autoTroubleshoot = enabled) }
    }

    /** The user approved the pending CONFIRM-tier command. */
    fun confirmPending() {
        val pending = _state.value.pendingConfirmation ?: return
        val current = manager ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        when (pending.kind) {
            PendingConfirmation.Kind.AI -> runConfirmed(current, pending)
            PendingConfirmation.Kind.RAW -> runConfirmedRaw(current, pending)
        }
    }

    /**
     * The user declined the pending command. An AI run that already executed steps before
     * pausing has them written to the transcript and the target-side log, so that work is
     * not lost.
     */
    fun declinePending() {
        val pending = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        val current = manager ?: return
        if (pending.kind == PendingConfirmation.Kind.AI && pending.result.commandExecuted != null) {
            sessionScope.launch {
                runCatching { current.persistDeclinedRun(pending.result) }
                    .onFailure { e -> Log.w(TAG, "Failed to persist declined run", e) }
            }
        }
    }

    // ---------------------------------------------------------------- session

    private suspend fun initializeSession() {
        val backend = connection.activeBackend() ?: return
        _state.update { it.copy(status = ConversationStatus.Initializing) }

        val session = environment.createSession(backend)

        val previousHostId = lastHostId
        val newHostId = session.hostId
        if (previousHostId != null && newHostId != null && previousHostId != newHostId) {
            appendHostSwitch(previousHost = lastHostLabel, newHost = session.hostLabel)
        }
        lastHostId = newHostId
        lastHostLabel = session.hostLabel

        val created = session.manager.apply {
            autoTroubleshootEnabled = _state.value.autoTroubleshoot
        }
        manager = created
        _state.update { it.copy(hostLabel = session.hostLabel) }

        val result = created.initialize()
        if (result.success) {
            _state.update {
                it.copy(status = ConversationStatus.Connected(result.systemIdentity ?: "Unknown System"))
            }
            if (result.isDefaultPersona) {
                _events.send(ConversationEvent.DefaultPersonaUsed)
            }
        } else {
            _state.update { it.copy(status = ConversationStatus.Failed(result.message)) }
        }
    }

    private fun clearSession() {
        // Cancel first: nothing started against the old backend gets to publish after this.
        // Cancelling leaves a run that was mid-flight without its normal completion (which is
        // what would have cleared isBusy), so this update clears it explicitly — otherwise a
        // disconnect that arrives while a request is running leaves the UI stuck busy forever.
        sessionScope.cancel()
        manager?.clearHistory()
        manager = null
        _state.update {
            it.copy(
                isBusy = false,
                hostLabel = null,
                status = ConversationStatus.Disconnected,
                pendingConfirmation = null
            )
        }
    }

    /**
     * Mark a mid-conversation host switch. The persona context is rebuilt for the new host
     * anyway; this makes the boundary visible. An empty transcript needs no marker.
     */
    private fun appendHostSwitch(previousHost: String?, newHost: String?) {
        _state.update {
            if (it.transcript.isEmpty()) it
            else it.copy(transcript = it.transcript + TranscriptItem.HostSwitch(previousHost, newHost))
        }
    }

    // ---------------------------------------------------------------- AI turns

    private fun runMessage(current: ConversationManager, message: String) {
        val turnId = turnIds.incrementAndGet()
        setBusy(true)
        sessionScope.launch {
            runTracked("Error processing message") {
                val result = current.processUserMessage(message) { chunk ->
                    appendChunk(turnId, message, chunk, isRaw = false)
                }
                finishTurn(turnId, message, result, isRaw = false)
                when {
                    !result.success -> log("Error: ${result.systemResponse}")
                    result.needsConfirmation -> askConfirmation(message, turnId, result)
                    result.commandBlocked -> log("Blocked: ${result.commandAttempted}")
                    result.commandExecuted != null -> log(
                        "Executed: ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                    )
                }
            }
        }
    }

    private fun runConfirmed(current: ConversationManager, pending: PendingConfirmation) {
        setBusy(true)
        sessionScope.launch {
            runTracked("Error executing confirmed command") {
                val result = current.executeConfirmedCommand(
                    pending.userMessage,
                    pending.result.systemResponse,
                    pending.command
                ) { chunk ->
                    appendChunk(pending.turnId, pending.userMessage, chunk, isRaw = false)
                }
                finishTurn(pending.turnId, pending.userMessage, result, isRaw = false)
                if (result.commandExecuted != null) {
                    log(
                        "Executed (confirmed): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                    )
                }
                // A troubleshooting chain can hit a second CONFIRM step after this one was
                // approved; without this the run would stop silently at that step.
                if (result.needsConfirmation) {
                    askConfirmation(pending.userMessage, pending.turnId, result)
                }
            }
        }
    }

    private fun askConfirmation(userMessage: String, turnId: Long, result: ConversationResult) {
        val command = result.commandToConfirm ?: return
        _state.update {
            it.copy(
                pendingConfirmation = PendingConfirmation(
                    command = command,
                    userMessage = userMessage,
                    kind = PendingConfirmation.Kind.AI,
                    turnId = turnId,
                    result = result
                )
            )
        }
    }

    // ---------------------------------------------------------------- raw mode

    private fun runRaw(current: ConversationManager, command: String) {
        val turnId = turnIds.incrementAndGet()
        setBusy(true)
        sessionScope.launch {
            runTracked("Error executing raw command") {
                val result = current.executeRawCommand(command) { chunk ->
                    appendChunk(turnId, command, chunk, isRaw = true)
                }
                if (result.needsConfirmation) {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            pendingConfirmation = PendingConfirmation(
                                command = command,
                                userMessage = command,
                                kind = PendingConfirmation.Kind.RAW,
                                turnId = turnId,
                                result = result
                            )
                        )
                    }
                    return@runTracked
                }
                finishTurn(turnId, command, result, isRaw = true)
                if (result.commandBlocked) {
                    log("Blocked (raw): $command")
                } else if (result.commandExecuted != null) {
                    log(
                        "Executed (raw): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                    )
                }
            }
        }
    }

    private fun runConfirmedRaw(current: ConversationManager, pending: PendingConfirmation) {
        setBusy(true)
        sessionScope.launch {
            runTracked("Error executing confirmed raw command") {
                val result = current.executeConfirmedRawCommand(pending.command) { chunk ->
                    appendChunk(pending.turnId, pending.command, chunk, isRaw = true)
                }
                finishTurn(pending.turnId, pending.command, result, isRaw = true)
                if (result.commandExecuted != null) {
                    log(
                        "Executed (raw, confirmed): ${result.commandExecuted}\n" +
                            "Result: ${if (result.commandSuccess) "success" else "failed"}"
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- no session

    /** The pre-conversation path: a command suggestion with nothing to run it on. */
    private fun generateStandalone(prompt: String) {
        val turnId = turnIds.incrementAndGet()
        setBusy(true)
        viewModelScope.launch {
            runTracked("Error generating command") {
                val result = environment.generateCommand(prompt)
                _state.update {
                    it.copy(
                        isBusy = false,
                        lastOutput = result.message,
                        transcript = it.transcript + TranscriptItem.Turn(turnId, prompt, result.message)
                    )
                }
                _events.send(ConversationEvent.CommandGenerated(prompt, result.message))
            }
        }
    }

    // ---------------------------------------------------------------- transcript

    private fun setBusy(busy: Boolean) {
        _state.update { it.copy(isBusy = busy) }
    }

    /**
     * Append streamed output to the turn [turnId], creating its row the first time a chunk
     * arrives. Safe to call from any thread.
     */
    private fun appendChunk(turnId: Long, prompt: String, chunk: String, isRaw: Boolean) {
        _state.update { it.copy(transcript = it.transcript.upsertTurn(turnId, prompt, isRaw) { it + chunk }) }
    }

    /**
     * Replace the turn's streamed output with the final response — or append the turn when
     * nothing streamed — and record the response as the last output.
     */
    private fun finishTurn(turnId: Long, prompt: String, result: ConversationResult, isRaw: Boolean) {
        _state.update {
            it.copy(
                isBusy = false,
                lastOutput = result.systemResponse,
                transcript = it.transcript.upsertTurn(turnId, prompt, isRaw) { result.systemResponse }
            )
        }
    }

    private fun List<TranscriptItem>.upsertTurn(
        turnId: Long,
        prompt: String,
        isRaw: Boolean,
        response: (String) -> String
    ): List<TranscriptItem> {
        val index = indexOfFirst { it is TranscriptItem.Turn && it.id == turnId }
        if (index < 0) {
            return this + TranscriptItem.Turn(turnId, prompt, response(""), isRaw)
        }
        val existing = this[index] as TranscriptItem.Turn
        return toMutableList().also { it[index] = existing.copy(response = response(existing.response)) }
    }

    private fun log(text: String) {
        _events.trySend(ConversationEvent.LogLine(text))
    }

    /**
     * Runs [block], turning any failure into a [ConversationEvent.Error] — except cancellation,
     * which is rethrown rather than swallowed. [fail] itself is plain, non-suspending code
     * (`setBusy`, `trySend`), so it would otherwise still run to completion inside a coroutine
     * whose job has already been cancelled — publishing a state update for a session (or a
     * ViewModel) that is already gone, exactly what cancelling [sessionScope] on disconnect is
     * meant to prevent.
     */
    private suspend fun runTracked(errorContext: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(errorContext, e)
        }
    }

    private fun fail(what: String, e: Exception) {
        Log.e(TAG, what, e)
        setBusy(false)
        _events.trySend(ConversationEvent.Error(e.message))
    }

    /** Builds the ViewModel with its production dependencies. */
    class Factory(
        private val environment: ConversationEnvironment,
        private val connection: ActiveConnection = TerminalSessionHolderConnection
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationViewModel(environment, connection) as T
    }

    private companion object {
        const val TAG = "ConversationViewModel"
    }
}
