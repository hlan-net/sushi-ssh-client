package net.hlan.sushi

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PlayRunResult(
    val success: Boolean,
    val message: String,
    val outputLines: List<String> = emptyList(),
    val renderedCommand: String = ""
)

object PlayRunner {
    private val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

    fun execute(
        play: Play,
        hostConfig: SshConnectionConfig,
        values: Map<String, String>,
        timeoutSec: Long = 30,
        onLine: (String) -> Unit
    ): PlayRunResult {
        val parameters = PlayParameters.decode(play.parametersJson).ifEmpty {
            PlayParameters.inferFromTemplate(play.scriptTemplate)
        }

        parameters.forEach { parameter ->
            val value = values[parameter.key].orEmpty()
            if (parameter.required && value.isBlank()) {
                return PlayRunResult(false, "Missing required value: ${parameter.label}")
            }
        }

        val rendered = renderTemplate(play.scriptTemplate, values)
        if (rendered.isBlank()) {
            return PlayRunResult(false, "Rendered command is empty")
        }

        val lines = Collections.synchronizedList(mutableListOf<String>())
        val marker = "SUSHI_PLAY_DONE_${System.currentTimeMillis()}"
        val markerLatch = CountDownLatch(1)
        val client = SshClient(hostConfig)

        val connectResult = client.connect(onLine = { line ->
            lines.add(line)
            onLine(line)
            if (line.trim() == marker) {
                markerLatch.countDown()
            }
        })
        if (!connectResult.success) {
            return PlayRunResult(false, connectResult.message, renderedCommand = rendered)
        }

        try {
            val commandResult = client.sendCommand("$rendered; printf '\\n$marker\\n'")
            if (!commandResult.success) {
                return PlayRunResult(false, commandResult.message, renderedCommand = rendered)
            }

            val seenMarker = markerLatch.await(timeoutSec, TimeUnit.SECONDS)
            if (!seenMarker) {
                if (!client.isConnected()) {
                    return PlayRunResult(
                        success = true,
                        message = "Play completed (remote session closed)",
                        outputLines = synchronized(lines) { lines.toList() },
                        renderedCommand = rendered
                    )
                }
                return PlayRunResult(false, "Play timed out", renderedCommand = rendered)
            }

            val snapshot = synchronized(lines) { lines.toList() }
            val markerIndex = snapshot.indexOfFirst { it.trim() == marker }
            val output = if (markerIndex >= 0) snapshot.subList(0, markerIndex) else snapshot
            return PlayRunResult(true, "Play completed", outputLines = output, renderedCommand = rendered)
        } finally {
            client.disconnect()
        }
    }

    private fun renderTemplate(template: String, values: Map<String, String>): String {
        return placeholderRegex.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key].orEmpty()
            shellQuote(value)
        }
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) {
            return "''"
        }
        return "'${value.replace("'", "'\\\"'\\\"'")}'"
    }
}
