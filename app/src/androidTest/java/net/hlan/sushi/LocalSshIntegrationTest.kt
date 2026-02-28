package net.hlan.sushi

import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayList
import java.util.Base64
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
            assertTrue("SSH session disconnected before command run", client.isConnected())
            val commandResult = client.sendCommand("echo $marker")
            assertTrue("Failed to send command: ${commandResult.message}", commandResult.success)

            val markerReceived = markerLatch.await(15, TimeUnit.SECONDS)
            assertTrue("Did not receive marker output within timeout.", markerReceived)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun connectsToConfiguredHostViaMainUi() {
        val credentials = readCredentialsOrSkip()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sshSettings = SshSettings(context)
        val testHost = SshConnectionConfig(
            alias = "Test Host",
            host = credentials.host,
            port = credentials.port,
            username = credentials.username,
            password = credentials.password
        )
        sshSettings.saveHost(testHost)
        sshSettings.setActiveHostId(testHost.id)
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

    @Test
    fun passwordLoginThenGenerateKeyInstallViaPhraseThenReconnectWithKey() {
        val credentials = readCredentialsOrSkip(requirePassword = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAppState(context)

        val sshSettings = SshSettings(context)
        val db = PhraseDatabaseHelper.getInstance(context)

        val passwordHost = SshConnectionConfig(
            alias = "Password Host",
            host = credentials.host,
            port = credentials.port,
            username = credentials.username,
            password = credentials.password
        )
        sshSettings.saveHost(passwordHost)
        sshSettings.setActiveHostId(passwordHost.id)
        sshSettings.setPrivateKey(null)

        val installMarker = "SUSHI_KEY_INSTALL_OK_${System.currentTimeMillis()}"
        val installMarkerLatch = CountDownLatch(1)
        val passwordClient = SshClient(passwordHost)

        val passwordConnectResult = passwordClient.connect { line ->
            if (line.contains(installMarker)) {
                installMarkerLatch.countDown()
            }
        }
        assertTrue(
            "Password SSH connect failed: ${passwordConnectResult.message}",
            passwordConnectResult.success
        )

        try {
            ActivityScenario.launch(KeysActivity::class.java).use { keysScenario ->
                keysScenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.generateKeyButton).performClick()
                }

                waitForCondition(
                    scenario = keysScenario,
                    timeoutMs = 20_000,
                    timeoutMessage = "Key generation did not complete"
                ) {
                    val generatedPublicKey = sshSettings.getPublicKey().orEmpty()
                    val generatedPrivateKey = sshSettings.getPrivateKey().orEmpty()
                    val installPhrase = db.getPhraseByName("Install SSH Key")
                    generatedPublicKey.isNotBlank() &&
                        generatedPrivateKey.isNotBlank() &&
                        installPhrase != null
                }
            }

            val installPhrase = db.getPhraseByName("Install SSH Key")
            assertNotNull("Install SSH Key phrase missing after key generation", installPhrase)

            val installCommand = "${installPhrase!!.command} && echo $installMarker"
            val installResult = passwordClient.sendCommand(installCommand)
            assertTrue("Failed to run key install phrase: ${installResult.message}", installResult.success)

            val installed = installMarkerLatch.await(20, TimeUnit.SECONDS)
            assertTrue("Did not observe install marker from host", installed)
        } finally {
            passwordClient.disconnect()
        }

        val generatedPrivateKey = sshSettings.getPrivateKey().orEmpty()
        assertTrue("Generated private key missing", generatedPrivateKey.isNotBlank())

        val keyOnlyHost = passwordHost.copy(password = "", privateKey = generatedPrivateKey)
        val reconnectMarker = "SUSHI_KEY_RELOGIN_OK_${System.currentTimeMillis()}"
        val reconnectMarkerLatch = CountDownLatch(1)
        val keyClient = SshClient(keyOnlyHost)

        val keyConnectResult = keyClient.connect { line ->
            if (line.contains(reconnectMarker)) {
                reconnectMarkerLatch.countDown()
            }
        }
        assertTrue(
            "Key-based SSH reconnect failed: ${keyConnectResult.message}",
            keyConnectResult.success
        )

        try {
            val reconnectCommandResult = keyClient.sendCommand("echo $reconnectMarker")
            assertTrue(
                "Failed to run reconnect marker command: ${reconnectCommandResult.message}",
                reconnectCommandResult.success
            )

            val reconnectMarkerReceived = reconnectMarkerLatch.await(15, TimeUnit.SECONDS)
            assertTrue("Did not receive reconnect marker", reconnectMarkerReceived)
        } finally {
            keyClient.disconnect()
        }
    }

    @Test
    fun runsCommandSequenceAndValidatesResponses() {
        val credentials = readCredentialsOrSkip(requirePassword = true)
        val receivedLines = Collections.synchronizedList(ArrayList<String>())

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
        }

        assertTrue("SSH connect failed: ${connectResult.message}", connectResult.success)

        try {
            val step1Output = runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "date > date.txt",
                marker = "SUSHI_STEP1_DONE_${System.currentTimeMillis()}"
            )
            assertTrue("Step 1 should not fail", step1Output.none { it.contains("No such file") })

            val sleepStart = System.currentTimeMillis()
            runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "sleep 5",
                marker = "SUSHI_STEP2_DONE_${System.currentTimeMillis()}"
            )
            val sleepElapsedMs = System.currentTimeMillis() - sleepStart
            assertTrue("sleep 5 completed too quickly: ${sleepElapsedMs}ms", sleepElapsedMs >= 4_500)

            val step3Output = runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "cat date.txt;date",
                marker = "SUSHI_STEP3_DONE_${System.currentTimeMillis()}"
            )
            val dateOutputLines = meaningfulOutput(step3Output, command = "cat date.txt;date")
            assertTrue(
                "Expected at least two date output lines from 'cat date.txt;date', got: $dateOutputLines",
                dateOutputLines.size >= 2
            )

            val step4Output = runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "whoami",
                marker = "SUSHI_STEP4_DONE_${System.currentTimeMillis()}"
            )
            val whoamiOutput = meaningfulOutput(step4Output, command = "whoami")
            assertTrue(
                "Expected whoami output to contain '${credentials.username}', got: $whoamiOutput",
                whoamiOutput.any { it.trim() == credentials.username }
            )

            val step5Output = runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "ls ..",
                marker = "SUSHI_STEP5_DONE_${System.currentTimeMillis()}"
            )
            val lsOutput = meaningfulOutput(step5Output, command = "ls ..")
            assertTrue("Expected non-empty output from 'ls ..'", lsOutput.isNotEmpty())
            assertTrue(
                "Unexpected ls error output: $lsOutput",
                lsOutput.none { it.contains("No such file or directory") }
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun nanoEditFlowFindsSuccessAndLogsOkTwice() {
        val credentials = readCredentialsOrSkip(requirePassword = true)
        val receivedLines = Collections.synchronizedList(ArrayList<String>())

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
        }

        assertTrue("SSH connect failed: ${connectResult.message}", connectResult.success)

        try {
            runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "rm -f newfile",
                marker = "SUSHI_NANO_CLEAN_START_${System.currentTimeMillis()}"
            )

            val nanoCheckOutput = runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "command -v nano >/dev/null && echo SUSHI_NANO_AVAILABLE || echo SUSHI_NANO_MISSING",
                marker = "SUSHI_NANO_CHECK_DONE_${System.currentTimeMillis()}"
            )
            val nanoCheckLines = meaningfulOutput(
                nanoCheckOutput,
                command = "command -v nano >/dev/null && echo SUSHI_NANO_AVAILABLE || echo SUSHI_NANO_MISSING"
            )
            assertTrue("nano is not available on target host", nanoCheckLines.any { it.contains("SUSHI_NANO_AVAILABLE") })

            runCommandCaptureOutput(
                client = client,
                receivedLines = receivedLines,
                command = "touch newfile",
                marker = "SUSHI_NANO_TOUCH_DONE_${System.currentTimeMillis()}"
            )

            val openNanoResult = client.sendCommand("nano newfile")
            assertTrue("Failed to open nano: ${openNanoResult.message}", openNanoResult.success)
            Thread.sleep(1200)

            assertTrue("Failed to type nano content", client.sendText("ready\nfor\nsuccess").success)
            Thread.sleep(300)

            assertTrue("Failed to send Ctrl+O in nano", client.sendText("\u000f").success)
            Thread.sleep(300)
            assertTrue("Failed to confirm nano write", client.sendText("\n").success)
            Thread.sleep(500)
            assertTrue("Failed to send Ctrl+X in nano", client.sendText("\u0018").success)
            Thread.sleep(1000)

            // Extra escape sequence to get back to shell reliably from any nano prompt state.
            client.sendText("\u0018") // Ctrl+X
            Thread.sleep(250)
            client.sendText("Y\n")
            Thread.sleep(250)
            client.sendText("\n")
            Thread.sleep(400)

            val startIndex = synchronized(receivedLines) { receivedLines.size }
            assertTrue(
                "Failed to run grep step",
                client.sendText("grep -l success * && echo \"OK\"\n").success
            )

            val sawNewfile = waitForLineContains(receivedLines, "newfile", timeoutSec = 20)
            val sawOk = waitForLineContains(receivedLines, "OK", timeoutSec = 20)
            assertTrue("Expected grep output to include newfile", sawNewfile)
            assertTrue("Expected grep step to output OK", sawOk)

            assertTrue(
                "Failed to run second grep verification step",
                client.sendText("grep -l success * && echo \"OK\"\n").success
            )
            val sawSecondOk = waitForNthLineContains(receivedLines, "OK", startIndex, targetCount = 2, timeoutSec = 20)
            assertTrue("Expected two OK lines after grep checks", sawSecondOk)

            val okCount = synchronized(receivedLines) {
                receivedLines.drop(startIndex).count { it.contains("OK") }
            }
            assertTrue("Expected session log to contain OK at least two times, got $okCount", okCount >= 2)
        } finally {
            runCatching {
                runCommandCaptureOutput(
                    client = client,
                    receivedLines = receivedLines,
                    command = "rm -f newfile",
                    marker = "SUSHI_NANO_CLEAN_END_${System.currentTimeMillis()}"
                )
            }
            client.disconnect()
        }
    }

    @Ignore("Enable after custom phrases are supported in automation.")
    @Test
    fun removesSushiKeysViaPhraseThenFailsReconnect() {
        // TODO: Once custom phrase automation is available:
        // 1) Create a phrase that removes "Sushi - SSH client key ..." entries from authorized_keys.
        // 2) Log in to the target host.
        // 3) Run the remove phrase.
        // 4) Disconnect.
        // 5) Verify a new key-only login attempt fails.
    }

    private fun readCredentialsOrSkip(requirePassword: Boolean = false): LocalSshCredentials {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString(ARG_HOST).orEmpty().trim()
        val username = args.getString(ARG_USERNAME).orEmpty().trim()
        val password = args.getString(ARG_PASSWORD).orEmpty().trim()
        val privateKeyRaw = decodePrivateKey(args)
        val port = parsePortOrNull(args.getString(ARG_PORT))

        assumeTrue(
            "sshPort must be a valid integer between 1 and 65535 when provided.",
            port != null
        )

        assumeTrue(
            "Set sshHost, sshUsername and either sshPassword or sshPrivateKey to run this test.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotBlank() || privateKeyRaw.isNotBlank())
        )

        if (requirePassword) {
            assumeTrue(
                "Set sshPassword to run password-to-key migration test.",
                password.isNotBlank()
            )
        }

        return LocalSshCredentials(
            host = host,
            port = port ?: DEFAULT_SSH_PORT,
            username = username,
            password = password,
            privateKey = privateKeyRaw.ifBlank { null }
        )
    }

    private fun decodePrivateKey(args: Bundle): String {
        val base64Key = args.getString(ARG_PRIVATE_KEY_B64).orEmpty().trim()
        if (base64Key.isNotBlank()) {
            return runCatching {
                String(Base64.getDecoder().decode(base64Key), Charsets.UTF_8)
            }.getOrElse { "" }
        }
        return args.getString(ARG_PRIVATE_KEY).orEmpty().trim()
    }

    private fun parsePortOrNull(rawPort: String?): Int? {
        val value = rawPort?.trim().orEmpty()
        if (value.isEmpty()) {
            return DEFAULT_SSH_PORT
        }
        val parsed = value.toIntOrNull() ?: return null
        return if (parsed in 1..65535) parsed else null
    }

    private fun runCommandCaptureOutput(
        client: SshClient,
        receivedLines: MutableList<String>,
        command: String,
        marker: String,
        timeoutSec: Long = 25
    ): List<String> {
        val startIndex = synchronized(receivedLines) { receivedLines.size }
        val result = client.sendCommand("$command; printf '\\n$marker\\n'")
        assertTrue("Failed to send command '$command': ${result.message}", result.success)

        val markerReceived = waitForMarker(receivedLines, marker, timeoutSec)
        assertTrue("Did not receive marker for command '$command'", markerReceived)

        val snapshot = synchronized(receivedLines) { receivedLines.toList() }
        val markerIndex = snapshot.indexOfFirst { it.trim() == marker }
        assertTrue("Could not locate marker line for '$command'", markerIndex >= startIndex)
        if (markerIndex <= startIndex) {
            return emptyList()
        }
        return snapshot.subList(startIndex, markerIndex)
    }

    private fun waitForMarker(
        receivedLines: List<String>,
        marker: String,
        timeoutSec: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(receivedLines) {
                receivedLines.any { it.trim() == marker }
            }
            if (found) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun meaningfulOutput(lines: List<String>, command: String): List<String> {
        return lines.map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.contains("SUSHI_STEP") }
            .filterNot { it == command }
            .filterNot { it.endsWith("$") || it.endsWith("#") }
    }

    private fun waitForLineContains(
        receivedLines: List<String>,
        marker: String,
        timeoutSec: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(receivedLines) {
                receivedLines.any { it.contains(marker) }
            }
            if (found) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun waitForNthLineContains(
        receivedLines: List<String>,
        marker: String,
        startIndex: Int,
        targetCount: Int,
        timeoutSec: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (System.currentTimeMillis() < deadline) {
            val count = synchronized(receivedLines) {
                receivedLines.drop(startIndex).count { it.contains(marker) }
            }
            if (count >= targetCount) {
                return true
            }
            Thread.sleep(100)
        }
        return false
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

    private fun <T : androidx.fragment.app.FragmentActivity> waitForCondition(
        scenario: ActivityScenario<T>,
        timeoutMs: Long,
        timeoutMessage: String,
        condition: (T) -> Boolean
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
        assertTrue(timeoutMessage, false)
    }

    private data class LocalSshCredentials(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val privateKey: String?
    )

    private fun resetAppState(context: android.content.Context) {
        SecurePrefs.get(context).edit().clear().commit()
        ConsoleLogRepository(context).clear()
        context.deleteDatabase("sushi_phrases.db")
        context.deleteDatabase("sushi_plays.db")
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22

        private const val ARG_HOST = "sshHost"
        private const val ARG_PORT = "sshPort"
        private const val ARG_USERNAME = "sshUsername"
        private const val ARG_PASSWORD = "sshPassword"
        private const val ARG_PRIVATE_KEY = "sshPrivateKey"
        private const val ARG_PRIVATE_KEY_B64 = "sshPrivateKeyB64"
    }
}
