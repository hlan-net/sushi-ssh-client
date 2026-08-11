package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for LocalShellBackend. These run entirely on-device via the
 * real PTY shim (libsushi-pty.so) with no network required — suitable for CI
 * emulators and for regression-testing after ProGuard minification.
 *
 * A per-test [Timeout] rule ensures that a hung PTY call (e.g. [LocalShellBackend.connect]
 * blocking indefinitely on the API 37 ps16k emulator or Android 14 targets) fails the
 * individual test within [TEST_TIMEOUT_SECONDS] seconds rather than stalling the whole suite.
 *
 * [connectReturnsSuccessAndIsConnectedIsTrue] is a must-pass smoke test that asserts on
 * connect failure so a broad PTY regression shows up as FAILED (not silently SKIPPED).
 * Other PTY-specific tests call [assumePtyAvailable] and are skipped on targets where the
 * native shim cannot open a pseudoterminal.
 */
@RunWith(AndroidJUnit4::class)
class LocalShellBackendTest {

    @get:Rule
    val timeout: Timeout = Timeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var backend: LocalShellBackend? = null

    @After
    fun teardown() {
        backend?.disconnect()
        backend = null
    }

    @Test
    fun nativeLibraryLoads() {
        // Accessing LocalShellBackend triggers System.loadLibrary("sushi-pty") in
        // the companion object init block. This is the ProGuard smoke-test: if the
        // keep rules are wrong the class or its native methods are stripped and
        // this line throws UnsatisfiedLinkError.
        backend = LocalShellBackend(context)
        assertNotNull(backend)
    }

    /**
     * Smoke test: [LocalShellBackend.connect] must succeed on this target.
     * Unlike PTY-specific tests this one uses [assertTrue] (not [Assume]) so a
     * regression that breaks [connect] broadly will surface as a FAILED test
     * rather than silently turning the whole class into SKIPPED.
     */
    @Test
    fun connectReturnsSuccessAndIsConnectedIsTrue() {
        backend = LocalShellBackend(context)
        val result = backend!!.connect(onLine = {}, streamMode = true)
        assertTrue("connect() should succeed on this target: ${result.message}", result.success)
        assertTrue("isConnected() should be true after connect", backend!!.isConnected())
    }

    @Test
    fun disconnectClearsConnectedState() {
        assumePtyAvailable()
        assertTrue(backend!!.isConnected())
        backend!!.disconnect()
        assertFalse("isConnected() should be false after disconnect", backend!!.isConnected())
    }

    @Test
    fun echoCommandOutputIsReceived() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        assumePtyAvailable(onLine = { lines.add(it) })

        val marker = "SUSHI_PTY_TEST_${System.currentTimeMillis()}"
        Thread.sleep(300) // wait for shell to be ready
        backend!!.sendCommand("echo $marker")

        waitUntil(timeoutMs = 5_000, message = "Output should contain marker '$marker'") {
            synchronized(lines) { lines.any { it.contains(marker) } }
        }
    }

    @Test
    fun resizePtyDoesNotCrash() {
        assumePtyAvailable()
        // Should not throw — verifies that TIOCSWINSZ ioctl reaches the kernel
        backend!!.resizePty(col = 120, row = 40, widthPx = 1200, heightPx = 800)
        backend!!.resizePty(col = 80, row = 24, widthPx = 800, heightPx = 480)
    }

    @Test
    fun sendCtrlCDoesNotCrash() {
        assumePtyAvailable()
        Thread.sleep(200)
        backend!!.sendCtrlC()
    }

    @Test
    fun sendTextReturnsSuccess() {
        assumePtyAvailable()
        Thread.sleep(200)
        val result = backend!!.sendText("ls\n")
        assertTrue("sendText should return success: ${result.message}", result.success)
    }

    @Test
    fun termEnvIsXterm256color() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        assumePtyAvailable(onLine = { lines.add(it) })

        Thread.sleep(300)
        backend!!.sendCommand("echo TERM=\$TERM")

        waitUntil(timeoutMs = 5_000, message = "TERM should be xterm-256color") {
            synchronized(lines) { lines.any { it.contains("xterm-256color") } }
        }
    }

    @Test
    fun sendTextOnDisconnectedBackendReturnsFailure() {
        backend = LocalShellBackend(context)
        // Never connected — nativeHandle is 0
        val result = backend!!.sendText("hello")
        assertFalse("sendText without connect should return failure", result.success)
    }

    /**
     * Attempts to connect a [LocalShellBackend] and skips the calling test (via
     * [Assume]) if the PTY is unavailable on this target (e.g. [LocalShellBackend.connect]
     * returns failure). On success [backend] is set so the caller can proceed without a
     * redundant connect call.
     *
     * This is intentionally a blocking call; the per-test [Timeout] rule enforces the
     * upper bound so a hung [connect][LocalShellBackend.connect] will still fail the test
     * rather than stalling the entire suite.
     */
    private fun assumePtyAvailable(onLine: (String) -> Unit = {}) {
        backend = LocalShellBackend(context)
        val result = backend!!.connect(onLine = onLine, streamMode = true)
        Assume.assumeTrue("PTY unavailable on this target — skipping: ${result.message}", result.success)
    }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    companion object {
        private const val TEST_TIMEOUT_SECONDS = 15L
    }
}
