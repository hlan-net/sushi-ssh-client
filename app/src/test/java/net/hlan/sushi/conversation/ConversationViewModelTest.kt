package net.hlan.sushi.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import net.hlan.sushi.ConversationManager
import net.hlan.sushi.FakeTerminalBackend
import net.hlan.sushi.GeminiResult
import net.hlan.sushi.ScriptedConversationLlm
import net.hlan.sushi.SshCommandResult
import net.hlan.sushi.TerminalBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [ConversationViewModel]: what the transcript, the CONFIRM state and the
 * toggles do in response to the intents the screen sends, against a [FakeTerminalBackend]
 * and a scripted model — the logic that until v0.9.0 lived in `MainActivity` and could only
 * be exercised on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var backend: FakeTerminalBackend
    private lateinit var connection: FakeConnection
    private lateinit var environment: FakeEnvironment

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        backend = FakeTerminalBackend()
        connection = FakeConnection()
        environment = FakeEnvironment()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- session

    @Test
    fun activeConnection_initialisesTheSessionOnCreation() = runBlocking {
        connection.connect(backend)
        environment.hostLabel = "ekho"

        val vm = viewModel()
        vm.awaitIdle()

        assertTrue(vm.state.value.status is ConversationStatus.Connected)
        assertEquals("ekho", vm.state.value.hostLabel)
        assertTrue(environment.lastManager!!.isInitialized())
    }

    @Test
    fun send_sessionInitializationFailed_reportsFailureInsteadOfStandaloneFallback() = runBlocking {
        backend.sushiMdReadResult = SshCommandResult(false, 1, "permission denied")
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()
        assertTrue(vm.state.value.status is ConversationStatus.Failed)

        vm.send("is it up?")
        vm.awaitIdle()

        // A session exists even though its initialisation failed, so the message must go
        // through ConversationManager — whose own not-initialized guard produces the response
        // below — rather than silently behaving as if no session existed at all and falling
        // back to the standalone, no-session suggestion path.
        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertEquals("is it up?", turn.prompt)
        assertTrue(turn.response.contains("not initialized", ignoreCase = true))
        assertTrue(backend.executed.isEmpty())
    }

    @Test
    fun disconnect_clearsHostStatusAndPendingConfirmation() = runBlocking {
        connection.connect(backend)
        environment.replies("Restarting.\nEXECUTE: sudo systemctl restart nginx")
        val vm = viewModel()
        vm.awaitIdle()
        vm.send("restart nginx")
        vm.awaitIdle()
        assertNotNull(vm.state.value.pendingConfirmation)

        connection.disconnect()
        vm.awaitIdle()

        assertNull(vm.state.value.hostLabel)
        assertEquals(ConversationStatus.Disconnected, vm.state.value.status)
        assertNull(vm.state.value.pendingConfirmation)
    }

    @Test
    fun disconnect_whileATurnIsInFlight_discardsItsLateResultWithoutPublishingAnError() = runBlocking {
        connection.connect(backend)
        val gate = CountDownLatch(1)
        backend.beforeExecute = { gate.await(5, TimeUnit.SECONDS) }
        environment.replies("Checking.\nEXECUTE: uptime")
        val vm = viewModel()
        vm.awaitIdle()
        val events = recordEventsOf(vm)

        vm.send("is it up?")
        assertTrue(vm.state.value.isBusy)

        connection.disconnect()
        assertEquals(ConversationStatus.Disconnected, vm.state.value.status)
        assertFalse(
            "a disconnect mid-request must not leave the UI stuck showing one in flight",
            vm.state.value.isBusy
        )

        // Let the stalled backend call return on its own (real, non-test) thread. The coroutine
        // it belongs to was cancelled along with the session above, so its resumption must not
        // publish a late transcript row, a reconnected status, or an error event.
        gate.countDown()
        val ranAfterDisconnect = withTimeoutOrNull(2_000) {
            while (backend.executed.isEmpty()) {
                yield()
                delay(10)
            }
            true
        }
        assertTrue("the stalled command should still complete on its own thread", ranAfterDisconnect == true)
        // A moment for the cancelled coroutine's resumption to (fail to) publish anything.
        delay(100)

        assertEquals(ConversationStatus.Disconnected, vm.state.value.status)
        assertTrue(vm.state.value.transcript.isEmpty())
        assertFalse(vm.state.value.isBusy)
        assertTrue(events.all.none { it is ConversationEvent.Error })
        events.job.cancel()
    }

    @Test
    fun hostSwitch_marksTheBoundaryOnlyWhenTheTranscriptHasContent() = runBlocking {
        environment.hostId = "a"
        environment.hostLabel = "alpha"
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()

        // An empty transcript needs no marker.
        connection.disconnect()
        environment.hostId = "b"
        environment.hostLabel = "beta"
        connection.connect(backend)
        vm.awaitIdle()
        assertTrue(vm.state.value.transcript.isEmpty())

        environment.replies("Hello from beta.")
        vm.send("hi")
        vm.awaitIdle()

        connection.disconnect()
        environment.hostId = "c"
        environment.hostLabel = "gamma"
        connection.connect(backend)
        vm.awaitIdle()

        val last = vm.state.value.transcript.last()
        assertEquals(TranscriptItem.HostSwitch(previousHost = "beta", newHost = "gamma"), last)
        assertEquals("gamma", vm.state.value.hostLabel)
    }

    // ---------------------------------------------------------------- AI turns

    @Test
    fun send_plainAnswer_appendsOneTurn() = runBlocking {
        connection.connect(backend)
        environment.replies("The load is fine.")
        val vm = viewModel()
        vm.awaitIdle()

        vm.send("  how is the load?  ")
        vm.awaitIdle()

        val turns = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>()
        assertEquals(1, turns.size)
        assertEquals("how is the load?", turns[0].prompt)
        assertEquals("The load is fine.", turns[0].response)
        assertFalse(turns[0].isRaw)
        assertEquals("The load is fine.", vm.state.value.lastOutput)
        assertTrue(vm.state.value.hasOutput)
        assertTrue(backend.executed.isEmpty())
    }

    @Test
    fun send_safeCommand_streamsIntoTheTurnThenReplacesItWithTheNarrative() = runBlocking {
        connection.connect(backend)
        backend.on("uptime", SshCommandResult(true, 0, "10:00 up 3 days"), streamed = listOf("10:00 ", "up 3 days"))
        environment.replies("Checking.\nEXECUTE: uptime", "Up three days, nothing to do.")
        val vm = viewModel()
        vm.awaitIdle()
        val events = recordEventsOf(vm)

        vm.send("is it up?")
        vm.awaitIdle()

        assertEquals(listOf("uptime"), backend.executed)
        val turns = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>()
        assertEquals("streamed chunks and the final answer share one row", 1, turns.size)
        assertTrue(turns[0].response.contains("$ uptime"))
        assertTrue(turns[0].response.contains("Up three days"))
        assertFalse(turns[0].response.startsWith("10:00 "))
        assertTrue(events.hasLog { it.startsWith("Executed: uptime") })
        events.job.cancel()
    }

    @Test
    fun send_confirmCommand_pausesForConfirmationAndRunsOnConfirm() = runBlocking {
        connection.connect(backend)
        environment.replies("Restarting.\nEXECUTE: sudo systemctl restart nginx", "Restarted.")
        val vm = viewModel()
        vm.awaitIdle()

        vm.send("restart nginx")
        vm.awaitIdle()

        val pending = vm.state.value.pendingConfirmation
        assertNotNull(pending)
        assertEquals("sudo systemctl restart nginx", pending!!.command)
        assertEquals(PendingConfirmation.Kind.AI, pending.kind)
        assertTrue(backend.executed.isEmpty())
        val before = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()

        vm.confirmPending()
        vm.awaitIdle()

        assertNull(vm.state.value.pendingConfirmation)
        assertEquals(listOf("sudo systemctl restart nginx"), backend.executed)
        val after = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertEquals("the confirmed run continues in the same row", before.id, after.id)
        assertTrue(after.response.contains("$ sudo systemctl restart nginx"))
        assertTrue(after.response.contains("Restarted."))
    }

    @Test
    fun declinePending_keepsTheStepsAlreadyRunInTheHistory() = runBlocking {
        connection.connect(backend)
        environment.replies(
            "Looking.\nEXECUTE: systemctl status nginx",
            "It is down.\nEXECUTE: sudo systemctl restart nginx"
        )
        val vm = viewModel()
        vm.awaitIdle()

        vm.send("nginx is down")
        vm.awaitIdle()
        val pending = vm.state.value.pendingConfirmation!!
        assertEquals("systemctl status nginx", pending.result.commandExecuted)

        vm.declinePending()
        vm.awaitIdle()

        assertNull(vm.state.value.pendingConfirmation)
        assertEquals(listOf("systemctl status nginx"), backend.executed)
        val history = environment.lastManager!!.getHistory()
        assertEquals(1, history.size)
        assertTrue(history[0].systemResponse.contains("[Not run: sudo systemctl restart nginx"))
    }

    @Test
    fun send_blockedCommand_isNotRunAndSaysSo() = runBlocking {
        connection.connect(backend)
        environment.replies("Rebooting.\nEXECUTE: sudo reboot")
        val vm = viewModel()
        vm.awaitIdle()
        val events = recordEventsOf(vm)

        vm.send("reboot it")
        vm.awaitIdle()

        assertTrue(backend.executed.isEmpty())
        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertTrue(turn.response.contains("[Command blocked"))
        assertTrue(events.hasLog { it.startsWith("Blocked: sudo reboot") })
        events.job.cancel()
    }

    @Test
    fun send_whileBusy_isIgnored() = runBlocking {
        connection.connect(backend)
        val gate = CountDownLatch(1)
        backend.beforeExecute = { gate.await(5, TimeUnit.SECONDS) }
        environment.replies("Checking.\nEXECUTE: uptime", "Fine.")
        val vm = viewModel()
        vm.awaitIdle()

        vm.send("first")
        assertTrue(vm.state.value.isBusy)
        vm.send("second")
        gate.countDown()
        vm.awaitIdle()

        assertEquals(1, vm.state.value.transcript.size)
        assertEquals(listOf("uptime"), backend.executed)
    }

    // ---------------------------------------------------------------- raw mode

    @Test
    fun rawMode_runsTheCommandWithoutTheModel() = runBlocking {
        connection.connect(backend)
        backend.on("ls", SshCommandResult(true, 0, "a  b"))
        val vm = viewModel()
        vm.awaitIdle()
        val events = recordEventsOf(vm)

        vm.setRawMode(true)
        vm.send("ls")
        vm.awaitIdle()

        assertEquals(listOf("ls"), backend.executed)
        assertTrue(environment.llm.prompts.isEmpty())
        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertEquals(TranscriptItem.Turn(turn.id, "ls", "a  b", isRaw = true), turn)
        assertTrue(events.hasLog { it.startsWith("Executed (raw): ls") })
        events.job.cancel()
    }

    @Test
    fun rawMode_confirmCommand_waitsAndRunsOnConfirm() = runBlocking {
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()

        vm.setRawMode(true)
        vm.send("rm -f /tmp/x")
        vm.awaitIdle()

        val pending = vm.state.value.pendingConfirmation
        assertNotNull(pending)
        assertEquals(PendingConfirmation.Kind.RAW, pending!!.kind)
        assertTrue(backend.executed.isEmpty())
        assertTrue("nothing is shown until the command runs", vm.state.value.transcript.isEmpty())

        vm.confirmPending()
        vm.awaitIdle()

        assertEquals(listOf("rm -f /tmp/x"), backend.executed)
        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertTrue(turn.isRaw)
        assertEquals("rm -f /tmp/x", turn.prompt)
    }

    @Test
    fun rawMode_blockedCommand_isNotRun() = runBlocking {
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()

        vm.setRawMode(true)
        vm.send("sudo reboot")
        vm.awaitIdle()

        assertTrue(backend.executed.isEmpty())
        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertTrue(turn.response.contains("[Command blocked"))
    }

    @Test
    fun sendRaw_bypassesTheToggle() = runBlocking {
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()

        vm.sendRaw("uptime")
        vm.awaitIdle()

        assertFalse(vm.state.value.isRawMode)
        assertEquals(listOf("uptime"), backend.executed)
    }

    @Test
    fun sendRaw_withoutSession_reportsNotConnected() = runBlocking {
        val vm = viewModel()
        val events = recordEventsOf(vm)

        vm.sendRaw("uptime")
        vm.awaitIdle()

        assertTrue(events.has { it is ConversationEvent.NotConnected })
        assertTrue(vm.state.value.transcript.isEmpty())
        events.job.cancel()
    }

    // ---------------------------------------------------------------- no session

    @Test
    fun send_withoutSession_generatesACommandInstead() = runBlocking {
        environment.standalone = GeminiResult(true, "df -h")
        val vm = viewModel()
        val events = recordEventsOf(vm)

        vm.send("show disk space")
        vm.awaitIdle()

        val turn = vm.state.value.transcript.filterIsInstance<TranscriptItem.Turn>().single()
        assertEquals("show disk space", turn.prompt)
        assertEquals("df -h", turn.response)
        assertEquals("df -h", vm.state.value.lastOutput)
        assertTrue(events.has { it == ConversationEvent.CommandGenerated("show disk space", "df -h") })
        events.job.cancel()
    }

    // ---------------------------------------------------------------- toggles

    @Test
    fun autoTroubleshoot_isPersistedAndReachesTheManager() = runBlocking {
        environment.autoTroubleshootEnabled = true
        connection.connect(backend)
        val vm = viewModel()
        vm.awaitIdle()
        assertTrue(vm.state.value.autoTroubleshoot)

        vm.setAutoTroubleshoot(false)

        assertFalse(vm.state.value.autoTroubleshoot)
        assertFalse(environment.autoTroubleshootEnabled)
        assertFalse(environment.lastManager!!.autoTroubleshootEnabled)
    }

    @Test
    fun autoTroubleshoot_initialValueComesFromTheEnvironment() {
        environment.autoTroubleshootEnabled = false

        val vm = viewModel()

        assertFalse(vm.state.value.autoTroubleshoot)
    }

    // ---------------------------------------------------------------- helpers

    private fun viewModel() = ConversationViewModel(environment, connection, ioDispatcher = mainDispatcher)

    private suspend fun ConversationViewModel.awaitIdle() {
        withTimeout(5_000) {
            state.first { !it.isBusy && it.status !is ConversationStatus.Initializing }
        }
    }

    private class RecordedEvents(val all: MutableList<ConversationEvent>, val job: Job) {
        /**
         * Whether an event matching [predicate] arrives. The collector runs on the test's event
         * loop, so it is given turns until the event shows up or the wait runs out.
         */
        suspend fun has(predicate: (ConversationEvent) -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (all.any(predicate)) return true
                yield()
                delay(10)
            }
            return false
        }

        suspend fun hasLog(predicate: (String) -> Boolean): Boolean =
            has { it is ConversationEvent.LogLine && predicate(it.text) }
    }

    /** Collects the ViewModel's events for the rest of the test; cancel `job` when done. */
    private fun CoroutineScope.recordEventsOf(vm: ConversationViewModel): RecordedEvents {
        val all = mutableListOf<ConversationEvent>()
        val job = launch { vm.events.collect { all += it } }
        return RecordedEvents(all, job)
    }

    /** [ActiveConnection] the test drives by hand. */
    private class FakeConnection : ActiveConnection {
        private var backend: TerminalBackend? = null
        private val listeners = mutableListOf<ActiveConnection.Listener>()

        fun connect(backend: TerminalBackend) {
            this.backend = backend
            listeners.toList().forEach { it.onConnected() }
        }

        fun disconnect() {
            backend = null
            listeners.toList().forEach { it.onDisconnected() }
        }

        override fun isConnected(): Boolean = backend != null
        override fun activeBackend(): TerminalBackend? = backend
        override fun addListener(listener: ActiveConnection.Listener) { listeners += listener }
        override fun removeListener(listener: ActiveConnection.Listener) { listeners -= listener }
    }

    /** [ConversationEnvironment] built on the scripted model and the fake backend. */
    private class FakeEnvironment : ConversationEnvironment {
        var hostId: String? = "host-1"
        var hostLabel: String? = "ekho"
        var llm = ScriptedConversationLlm(mutableListOf())
        var standalone = GeminiResult(false, "no session")
        var lastManager: ConversationManager? = null
        override var autoTroubleshootEnabled: Boolean = true

        fun replies(vararg replies: String) {
            llm = ScriptedConversationLlm(replies.toMutableList())
        }

        override suspend fun createSession(backend: TerminalBackend): ConversationSession {
            val manager = ConversationManager(
                backend = backend,
                geminiClient = null,
                geminiNanoClient = null,
                llm = llm
            )
            lastManager = manager
            return ConversationSession(manager, hostId, hostLabel)
        }

        override suspend fun generateCommand(prompt: String): GeminiResult = standalone
    }
}
