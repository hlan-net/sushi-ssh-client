package net.hlan.sushi

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalSshIntegrationTest {

    @Test
    fun connectsToConfiguredHostViaSsh() {
        val credentials = readCredentialsOrSkip()
        val marker = "SUSHI_LOCAL_TEST_OK_${System.currentTimeMillis()}"
        val receivedLines = Collections.synchronizedList(ArrayList<String>())
        val markerLatch = CountDownLatch(1)

        val config = SshConnectionConfig(
            host = credentials.host,
            port = credentials.port,
            username = credentials.username,
            password = credentials.password,
            privateKey = credentials.privateKey
        )
        val client = SshClient(config)

        val connectResult = client.connect { line ->
            receivedLines.add(line)
            if (line.contains(marker)) {
                markerLatch.countDown()
            }
        }

        assertTrue("SSH connect failed: ${connectResult.message}", connectResult.success)

        try {
            val commandResult = client.sendCommand("echo $marker")
            assertTrue("Failed to send command: ${commandResult.message}", commandResult.success)

            val markerReceived = markerLatch.await(15, TimeUnit.SECONDS)
            assertTrue(
                "Did not receive marker output within timeout. Received lines: ${tail(receivedLines, 20)}",
                markerReceived
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun connectsToConfiguredHostViaMainUi() {
        val credentials = readCredentialsOrSkip()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sshSettings = SshSettings(context)
        sshSettings.setHost(credentials.host)
        sshSettings.setPort(credentials.port)
        sshSettings.setUsername(credentials.username)
        sshSettings.setPassword(credentials.password)
        sshSettings.setPrivateKey(credentials.privateKey)

        val marker = "SUSHI_UI_TEST_OK_${System.currentTimeMillis()}"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.startSessionButton).performClick()
            }

            waitUntil(
                scenario = scenario,
                timeoutMs = 20_000,
                timeoutMessage = "Session did not reach connected state from UI"
            ) { activity ->
                val statusView = activity.findViewById<TextView>(R.id.sessionStatusText)
                activity.getString(R.string.session_status_connected) == statusView.text.toString()
            }

            scenario.onActivity { activity ->
                val commandInput = activity.findViewById<TextView>(R.id.commandInput)
                commandInput.text = "echo $marker"
                activity.findViewById<android.view.View>(R.id.runCommandButton).performClick()
            }

            waitUntil(
                scenario = scenario,
                timeoutMs = 20_000,
                timeoutMessage = "Command output marker not found in terminal log"
            ) { activity ->
                val logView = activity.findViewById<TextView>(R.id.sessionLogText)
                logView.text?.toString()?.contains(marker) == true
            }

            scenario.onActivity { activity ->
                val statusView = activity.findViewById<TextView>(R.id.sessionStatusText)
                if (activity.getString(R.string.session_status_connected) == statusView.text.toString()) {
                    activity.findViewById<android.view.View>(R.id.startSessionButton).performClick()
                }
            }
        }
    }

    private fun readCredentialsOrSkip(): LocalSshCredentials {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString(ARG_HOST).orEmpty().trim()
        val username = args.getString(ARG_USERNAME).orEmpty().trim()
        val password = args.getString(ARG_PASSWORD).orEmpty()
        val privateKeyRaw = args.getString(ARG_PRIVATE_KEY).orEmpty()
        val port = parsePort(args.getString(ARG_PORT))

        assumeTrue(
            "Set sshHost, sshUsername and either sshPassword or sshPrivateKey to run this test.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotBlank() || privateKeyRaw.isNotBlank())
        )

        return LocalSshCredentials(
            host = host,
            port = port,
            username = username,
            password = password,
            privateKey = privateKeyRaw.ifBlank { null }
        )
    }

    private fun parsePort(rawPort: String?): Int {
        return rawPort?.trim()?.toIntOrNull() ?: DEFAULT_SSH_PORT
    }

    private fun tail(lines: List<String>, maxItems: Int): List<String> {
        if (lines.size <= maxItems) {
            return lines
        }
        return lines.subList(lines.size - maxItems, lines.size)
    }

    private fun waitUntil(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        timeoutMessage: String,
        condition: (MainActivity) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            scenario.onActivity { activity ->
                satisfied = condition(activity)
            }
            if (satisfied) {
                return
            }
            Thread.sleep(250)
        }

        var debugState = "(unavailable)"
        scenario.onActivity { activity ->
            val statusView = activity.findViewById<TextView>(R.id.sessionStatusText)
            val targetView = activity.findViewById<TextView>(R.id.sessionTargetText)
            debugState = "status=${statusView.text}, target=${targetView.text}"
        }
        assertTrue("$timeoutMessage | $debugState", false)
    }

    private data class LocalSshCredentials(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val privateKey: String?
    )

    companion object {
        private const val DEFAULT_SSH_PORT = 22

        private const val ARG_HOST = "sshHost"
        private const val ARG_PORT = "sshPort"
        private const val ARG_USERNAME = "sshUsername"
        private const val ARG_PASSWORD = "sshPassword"
        private const val ARG_PRIVATE_KEY = "sshPrivateKey"
    }
}
