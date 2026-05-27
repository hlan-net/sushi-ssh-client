package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Instrumented tests for LocalShellBackend. These run entirely on-device via the
 * real PTY shim (libsushi-pty.so) with no network required — suitable for CI
 * emulators and for regression-testing after ProGuard minification.
 */
@RunWith(AndroidJUnit4::class)
class LocalShellBackendTest {

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

    @Test
    fun connectReturnsSuccessAndIsConnectedIsTrue() {
        backend = LocalShellBackend(context)
        val result = backend!!.connect(onLine = {}, streamMode = true)
        assertTrue("connect() should succeed: ${result.message}", result.success)
        assertTrue("isConnected() should be true after connect", backend!!.isConnected())
    }

    @Test
    fun disconnectClearsConnectedState() {
        backend = LocalShellBackend(context)
        backend!!.connect(onLine = {}, streamMode = true)
        assertTrue(backend!!.isConnected())
        backend!!.disconnect()
        assertFalse("isConnected() should be false after disconnect", backend!!.isConnected())
    }

    @Test
    fun echoCommandOutputIsReceived() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        backend = LocalShellBackend(context)
        val connectResult = backend!!.connect(onLine = { lines.add(it) }, streamMode = true)
        assertTrue("connect() should succeed", connectResult.success)

        val marker = "SUSHI_PTY_TEST_${System.currentTimeMillis()}"
        Thread.sleep(300) // wait for shell to be ready
        backend!!.sendCommand("echo $marker")

        waitUntil(timeoutMs = 5_000, message = "Output should contain marker '$marker'") {
            lines.any { it.contains(marker) }
        }
    }

    @Test
    fun resizePtyDoesNotCrash() {
        backend = LocalShellBackend(context)
        backend!!.connect(onLine = {}, streamMode = true)
        // Should not throw — verifies that TIOCSWINSZ ioctl reaches the kernel
        backend!!.resizePty(col = 120, row = 40, widthPx = 1200, heightPx = 800)
        backend!!.resizePty(col = 80, row = 24, widthPx = 800, heightPx = 480)
    }

    @Test
    fun sendCtrlCDoesNotCrash() {
        backend = LocalShellBackend(context)
        backend!!.connect(onLine = {}, streamMode = true)
        Thread.sleep(200)
        backend!!.sendCtrlC()
    }

    @Test
    fun sendTextReturnsSuccess() {
        backend = LocalShellBackend(context)
        backend!!.connect(onLine = {}, streamMode = true)
        Thread.sleep(200)
        val result = backend!!.sendText("ls\n")
        assertTrue("sendText should return success: ${result.message}", result.success)
    }

    @Test
    fun termEnvIsXterm256color() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        backend = LocalShellBackend(context)
        backend!!.connect(onLine = { lines.add(it) }, streamMode = true)

        Thread.sleep(300)
        backend!!.sendCommand("echo TERM=\$TERM")

        waitUntil(timeoutMs = 5_000, message = "TERM should be xterm-256color") {
            lines.any { it.contains("xterm-256color") }
        }
    }

    @Test
    fun sendTextOnDisconnectedBackendReturnsFailure() {
        backend = LocalShellBackend(context)
        // Never connected — nativeHandle is 0
        val result = backend!!.sendText("hello")
        assertFalse("sendText without connect should return failure", result.success)
    }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }
}
