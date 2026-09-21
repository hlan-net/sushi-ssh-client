package net.hlan.sushi.conversation

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import net.hlan.sushi.CommandHistoryDatabaseHelper
import net.hlan.sushi.ConversationContextBuilder
import net.hlan.sushi.ConversationManager
import net.hlan.sushi.GeminiClient
import net.hlan.sushi.GeminiNanoClient
import net.hlan.sushi.GeminiResult
import net.hlan.sushi.GeminiSettings
import net.hlan.sushi.GeminiTranscriptDatabaseHelper
import net.hlan.sushi.HostLabels
import net.hlan.sushi.SshSettings
import net.hlan.sushi.TerminalBackend
import java.util.UUID

/**
 * The production [ConversationEnvironment]: the configured Gemini clients, the transcript and
 * command-history stores, and the saved hosts, all resolved from [context].
 */
class AppConversationEnvironment(
    context: Context,
    private val geminiSettings: GeminiSettings,
    private val geminiClient: GeminiClient,
    private val nanoClient: GeminiNanoClient,
    private val sshSettings: SshSettings
) : ConversationEnvironment {

    private val appContext = context.applicationContext

    override suspend fun createSession(backend: TerminalBackend): ConversationSession {
        val useNano = geminiSettings.getNanoPreferred() && isNanoAvailable()
        val activeConfig = sshSettings.getConfigOrNull()
        val hostLabel = activeConfig?.let { HostLabels.shortLabel(appContext, it) }
        val infrastructure = ConversationContextBuilder.infrastructureSection(
            hosts = sshSettings.getHosts(),
            activeHostId = activeConfig?.id
        )
        val manager = ConversationManager(
            backend = backend,
            geminiClient = geminiClient,
            geminiNanoClient = nanoClient,
            useNano = useNano,
            transcriptStore = GeminiTranscriptDatabaseHelper.getInstance(appContext),
            commandHistoryStore = CommandHistoryDatabaseHelper.getInstance(appContext),
            sessionId = UUID.randomUUID().toString(),
            hostId = activeConfig?.id,
            hostLabel = hostLabel,
            infrastructureContext = infrastructure
        )
        return ConversationSession(manager, activeConfig?.id, hostLabel)
    }

    override suspend fun generateCommand(prompt: String): GeminiResult {
        val useNano = geminiSettings.getNanoPreferred() && isNanoAvailable()
        return if (useNano) {
            Log.d(TAG, "Routing voice command to Gemini Nano (on-device)")
            nanoClient.generateCommand(prompt)
        } else {
            Log.d(TAG, "Routing voice command to cloud Gemini (${geminiSettings.getCloudModel()})")
            geminiClient.generateCommand(prompt)
        }
    }

    override var autoTroubleshootEnabled: Boolean
        get() = geminiSettings.getAutoTroubleshootEnabled()
        set(value) = geminiSettings.setAutoTroubleshootEnabled(value)

    /**
     * [nanoClient] is constructed by [net.hlan.sushi.MainActivity] and handed to this
     * environment, but the environment — not the activity — is what a rotation-surviving
     * [ConversationViewModel] keeps calling into, so closing it belongs here too: the activity
     * that constructed [nanoClient] is destroyed on every rotation, while this environment (and
     * the model it wraps) is meant to outlive that. Closing from `Activity.onDestroy()` instead
     * would close the model out from under a ViewModel that survives the same rotation.
     */
    override fun close() {
        nanoClient.close()
    }

    private suspend fun isNanoAvailable(): Boolean =
        runCatching { nanoClient.checkStatus() == FeatureStatus.AVAILABLE }.getOrDefault(false)

    private companion object {
        const val TAG = "ConversationEnv"
    }
}
