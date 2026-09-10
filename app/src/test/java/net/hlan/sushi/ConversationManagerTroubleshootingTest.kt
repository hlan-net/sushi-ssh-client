package net.hlan.sushi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the multi-step troubleshooting chain in [ConversationManager]
 * (roadmap v0.8.0 — AI-powered troubleshooting).
 *
 * The manager talks to a [FakeBackend] instead of a shell and a scripted [ConversationLlm]
 * instead of Gemini, so the orchestration — how far a chain runs, where it stops, and what ends
 * up in the transcript — is verified without a device or network access.
 */
class ConversationManagerTroubleshootingTest {

    /** Backend that answers a fixed script and records what it was asked to run. */
    private class FakeBackend(
        private val sushiMd: String = "# Test system\nYou are a test host.",
        private val results: MutableMap<String, SshCommandResult> = mutableMapOf()
    ) : TerminalBackend {

        val executed = mutableListOf<String>()

        fun on(command: String, result: SshCommandResult) {
            results[command] = result
        }

        override fun execCommand(
            command: String,
            timeoutMs: Long,
            onChunk: ((String) -> Unit)?
        ): SshCommandResult {
            if (command.startsWith("cat ~/.config/sushi/SUSHI.md")) {
                return SshCommandResult(true, 0, sushiMd)
            }
            // Log-file setup and the log-dir lookup are infrastructure, not part of the chain.
            if (command.startsWith("cat ~/.config/sushi/config.conf") ||
                command.startsWith("mkdir -p ") ||
                command.startsWith("printf ")
            ) {
                return SshCommandResult(true, 0, "")
            }
            executed.add(command)
            return results[command] ?: SshCommandResult(true, 0, "ok: $command")
        }

        override fun connect(
            onLine: (String) -> Unit,
            streamMode: Boolean,
            onConnectionClosed: (() -> Unit)?
        ): SshConnectResult = SshConnectResult(true, "connected")

        override fun isConnected(): Boolean = true
        override fun sendText(text: String) = SshCommandResult(true, 0, "")
        override fun sendCommand(command: String) = SshCommandResult(true, 0, "")
        override fun sendCtrlC() = Unit
        override fun sendCtrlD() = Unit
        override fun resizePty(col: Int, row: Int, widthPx: Int, heightPx: Int) = Unit
        override fun disconnect() = Unit
    }

    /** LLM that hands out queued replies in order, then repeats its last one. */
    private class ScriptedLlm(private val replies: MutableList<String>) : ConversationLlm {
        val prompts = mutableListOf<String>()
        private var last: String = ""

        override suspend fun respond(
            userMessage: String,
            promptContext: String,
            history: List<ConversationTurn>
        ): GeminiResult {
            prompts.add(userMessage)
            val reply = if (replies.isEmpty()) last else replies.removeAt(0).also { last = it }
            return GeminiResult(true, reply)
        }
    }

    private fun manager(
        backend: TerminalBackend,
        llm: ConversationLlm
    ): ConversationManager = ConversationManager(
        backend = backend,
        geminiClient = null,
        geminiNanoClient = null,
        llm = llm
    )

    private fun initialized(
        backend: TerminalBackend,
        llm: ConversationLlm
    ): ConversationManager = manager(backend, llm).also {
        runBlocking { assertTrue(it.initialize().success) }
    }

    @Test
    fun chain_runsFollowUpCommandsUntilTheModelStops() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(
            mutableListOf(
                "Let me look at the service.\nEXECUTE: systemctl status nginx",
                "It is failing to start — checking the logs.\nEXECUTE: journalctl -u nginx",
                "The config file has a typo on line 12. Fix that and reload."
            )
        )
        val conversation = initialized(backend, llm)

        val result = conversation.processUserMessage("nginx is down")

        assertEquals(listOf("systemctl status nginx", "journalctl -u nginx"), backend.executed)
        assertEquals("journalctl -u nginx", result.commandExecuted)
        assertFalse(result.needsConfirmation)

        // Both command lines survive into the stored narrative, not only the last one.
        assertTrue(result.systemResponse.contains("$ systemctl status nginx"))
        assertTrue(result.systemResponse.contains("$ journalctl -u nginx"))
        assertFalse(result.systemResponse.contains("EXECUTE:"))
        assertTrue(result.systemResponse.contains("typo on line 12"))
    }

    @Test
    fun chain_stopsAtTheStepLimit() = runBlocking {
        val backend = FakeBackend()
        // A model that never stops asking for one more command.
        val llm = object : ConversationLlm {
            private var step = 0
            override suspend fun respond(
                userMessage: String,
                promptContext: String,
                history: List<ConversationTurn>
            ): GeminiResult = GeminiResult(true, "Checking.\nEXECUTE: uptime ${step++}")
        }

        initialized(backend, llm).processUserMessage("what is wrong?")

        assertEquals(
            ConversationManager.MAX_TROUBLESHOOTING_STEPS,
            backend.executed.size
        )
    }

    @Test
    fun chain_stopsWhenTheModelRepeatsTheSameCommand() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(mutableListOf("Checking.\nEXECUTE: uptime", "Still checking.\nEXECUTE: uptime"))

        val result = initialized(backend, llm).processUserMessage("how long has this been up?")

        assertEquals(listOf("uptime"), backend.executed)
        assertEquals("uptime", result.commandExecuted)
    }

    @Test
    fun chain_doesNotRunWhenTroubleshootingIsOff() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(
            mutableListOf(
                "Checking.\nEXECUTE: systemctl status nginx",
                "Next I would read the logs.\nEXECUTE: journalctl -u nginx"
            )
        )
        val conversation = initialized(backend, llm).apply { autoTroubleshootEnabled = false }

        conversation.processUserMessage("nginx is down")

        assertEquals(listOf("systemctl status nginx"), backend.executed)
    }

    @Test
    fun chain_endsWhenTheNextStepIsBlocked() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(
            mutableListOf(
                "Checking uptime first.\nEXECUTE: uptime",
                "This host needs a restart.\nEXECUTE: reboot"
            )
        )

        val result = initialized(backend, llm).processUserMessage("is this box healthy?")

        assertEquals(listOf("uptime"), backend.executed)
        assertTrue(result.commandBlocked)
        assertEquals("reboot", result.commandAttempted)
        assertTrue(result.systemResponse.contains("[Command blocked:"))
    }

    @Test
    fun chain_pausesForConfirmationAndResumesOnApproval() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(
            mutableListOf(
                "Checking the service.\nEXECUTE: systemctl status nginx",
                "It is dead — restart it.\nEXECUTE: systemctl restart nginx",
                "Restarted cleanly, nginx is serving again."
            )
        )
        val conversation = initialized(backend, llm)

        val paused = conversation.processUserMessage("nginx is down")

        assertTrue(paused.needsConfirmation)
        assertEquals("systemctl restart nginx", paused.commandToConfirm)
        assertEquals(listOf("systemctl status nginx"), backend.executed)

        val resumed = conversation.executeConfirmedCommand(
            paused.userMessage,
            paused.systemResponse,
            paused.commandToConfirm!!
        )

        assertEquals(
            listOf("systemctl status nginx", "systemctl restart nginx"),
            backend.executed
        )
        assertNull(resumed.commandToConfirm)
        // The resumed run carries the whole chain, not just the confirmed step.
        assertTrue(resumed.systemResponse.contains("$ systemctl status nginx"))
        assertTrue(resumed.systemResponse.contains("$ systemctl restart nginx"))
    }

    @Test
    fun declinedRun_persistsTheStepsThatAlreadyRan() = runBlocking {
        val backend = FakeBackend()
        val llm = ScriptedLlm(
            mutableListOf(
                "Checking the service.\nEXECUTE: systemctl status nginx",
                "It is dead — restart it.\nEXECUTE: systemctl restart nginx"
            )
        )
        val conversation = initialized(backend, llm)

        val paused = conversation.processUserMessage("nginx is down")
        assertTrue(paused.needsConfirmation)
        assertTrue(conversation.getHistory().isEmpty())

        conversation.persistDeclinedRun(paused)

        val turn = conversation.getHistory().last()
        assertEquals("systemctl status nginx", turn.commandExecuted)
        assertTrue(turn.systemResponse.contains("$ systemctl status nginx"))
        assertTrue(
            turn.systemResponse.contains("[Not run: systemctl restart nginx — confirmation declined]")
        )
    }
}
