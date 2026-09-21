package net.hlan.sushi.conversation

import net.hlan.sushi.GeminiResult
import net.hlan.sushi.TerminalBackend

/**
 * What [ConversationViewModel] needs from the rest of the app, behind an interface so the
 * ViewModel is a plain JVM class: the production implementation is
 * [AppConversationEnvironment]; unit tests supply a fake backend and a scripted model.
 */
interface ConversationEnvironment {

    /**
     * Build a [ConversationSession] for [backend]: the manager with its model, stores and host
     * context. Called on an IO dispatcher.
     */
    suspend fun createSession(backend: TerminalBackend): ConversationSession

    /**
     * Turn [prompt] into a shell command with no session to run it on — the path used before
     * any host is connected. Called on an IO dispatcher.
     */
    suspend fun generateCommand(prompt: String): GeminiResult

    /** The persisted multi-step troubleshooting preference. */
    var autoTroubleshootEnabled: Boolean

    /**
     * Release whatever [createSession] and [generateCommand] hold open. Called once, from
     * [ConversationViewModel.onCleared] — the point the conversation is actually done, not an
     * activity recreation the ViewModel survives. A production implementation that owns a
     * closeable model (on-device inference, a network client) overrides this; the default is a
     * no-op, which is what every test fake needs.
     */
    fun close() {}
}
