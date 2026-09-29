package net.hlan.sushi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TerminalSessionHolder] is a plain Kotlin object with no Android dependency — these ran as
 * part of the instrumented `AiConversationTest` for no reason beyond history; moved here on
 * v0.9.0's Compose port so they run on the JVM like the rest of this package's tests.
 */
class TerminalSessionHolderTest {

    private val config = SshConnectionConfig(host = "ekho.local", port = 22, username = "pi", password = "")
    private val backend = FakeTerminalBackend()

    @Before
    fun setUp() {
        TerminalSessionHolder.clearActiveConnection()
    }

    @After
    fun tearDown() {
        TerminalSessionHolder.clearActiveConnection()
    }

    @Test
    fun initialState_isNotConnected() {
        assertFalse(TerminalSessionHolder.isConnected())
        assertNull(TerminalSessionHolder.getActiveSshClient())
        assertNull(TerminalSessionHolder.getActiveConfig())
        assertNull(TerminalSessionHolder.getActiveBackend())
    }

    @Test
    fun setActiveConnection_reportsConnectedWithTheGivenBackendAndConfig() {
        TerminalSessionHolder.setActiveConnection(backend, config)

        assertTrue(TerminalSessionHolder.isConnected())
        assertEquals(backend, TerminalSessionHolder.getActiveBackend())
        assertEquals(config, TerminalSessionHolder.getActiveConfig())
        // FakeTerminalBackend is not an SshClient, so the typed accessor stays null — only
        // TerminalSessionHolderConnection/getActiveBackend see it through the interface.
        assertNull(TerminalSessionHolder.getActiveSshClient())
    }

    @Test
    fun clearActiveConnection_returnsToNotConnected() {
        TerminalSessionHolder.setActiveConnection(backend, config)

        TerminalSessionHolder.clearActiveConnection()

        assertFalse(TerminalSessionHolder.isConnected())
        assertNull(TerminalSessionHolder.getActiveSshClient())
        assertNull(TerminalSessionHolder.getActiveConfig())
        assertNull(TerminalSessionHolder.getActiveBackend())
    }

    @Test
    fun listeners_areNotifiedOnConnectAndDisconnect() {
        var connectedCount = 0
        var disconnectedCount = 0
        val listener = object : TerminalSessionHolder.ConnectionListener {
            override fun onConnected() { connectedCount++ }
            override fun onDisconnected() { disconnectedCount++ }
        }
        TerminalSessionHolder.addListener(listener)

        TerminalSessionHolder.setActiveConnection(backend, config)
        TerminalSessionHolder.clearActiveConnection()

        assertEquals(1, connectedCount)
        assertEquals(1, disconnectedCount)

        TerminalSessionHolder.removeListener(listener)
        TerminalSessionHolder.setActiveConnection(backend, config)
        assertEquals("a removed listener must not be notified again", 1, connectedCount)
    }

    // --- expectDisconnect / consumeExpectedDisconnect (ROADMAP.md v0.9.x: Reboot Host) ---

    @Test
    fun consumeExpectedDisconnect_trueOnceForTheMatchingHost() {
        TerminalSessionHolder.expectDisconnect(config.id)

        assertTrue(TerminalSessionHolder.consumeExpectedDisconnect(config.id))
        assertFalse(
            "consuming again must not report a second expected disconnect",
            TerminalSessionHolder.consumeExpectedDisconnect(config.id)
        )
    }

    @Test
    fun consumeExpectedDisconnect_falseForADifferentHost_andLeavesItPending() {
        TerminalSessionHolder.expectDisconnect(config.id)

        assertFalse(TerminalSessionHolder.consumeExpectedDisconnect("some-other-host-id"))
        assertTrue(
            "a mismatched check must not consume the expectation for the real host",
            TerminalSessionHolder.consumeExpectedDisconnect(config.id)
        )
    }

    @Test
    fun consumeExpectedDisconnect_falseWithNothingExpected() {
        assertFalse(TerminalSessionHolder.consumeExpectedDisconnect(config.id))
    }

    @Test
    fun clearActiveConnection_dropsAPendingExpectationForTheSameHost() {
        TerminalSessionHolder.setActiveConnection(backend, config)
        TerminalSessionHolder.expectDisconnect(config.id)

        TerminalSessionHolder.clearActiveConnection()

        assertFalse(
            "a manual disconnect must not leave a stale expectation for a later, unrelated one",
            TerminalSessionHolder.consumeExpectedDisconnect(config.id)
        )
    }

    @Test
    fun clearActiveConnection_leavesAPendingExpectationForADifferentHost() {
        val otherConfig = config.copy(id = "other-host-id", host = "other.local")
        TerminalSessionHolder.setActiveConnection(backend, config)
        TerminalSessionHolder.expectDisconnect(otherConfig.id)

        // The active session (config) ends for its own reason, unrelated to otherConfig's Play.
        TerminalSessionHolder.clearActiveConnection()

        assertTrue(
            "a reboot pending elsewhere must survive this session's own disconnect",
            TerminalSessionHolder.consumeExpectedDisconnect(otherConfig.id)
        )
    }
}
