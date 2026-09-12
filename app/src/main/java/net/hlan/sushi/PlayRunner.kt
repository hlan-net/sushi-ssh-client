package net.hlan.sushi

data class PlayRunResult(
    val success: Boolean,
    val message: String,
    val outputLines: List<String> = emptyList(),
    val renderedCommand: String = "",
    /** Exit status of the rendered command, or null when it never produced one (timeout). */
    val exitStatus: Int? = null,
    /**
     * Whether [renderedCommand] has the value of a `secret` parameter substituted into it — the
     * managed "change user password" play renders the typed password straight into
     * `echo user:pass | sudo chpasswd`, for example.
     *
     * Such a command must not be persisted or shown outside the run that produced it: the
     * command history is an unencrypted local database whose entries are searchable, copyable
     * to the clipboard, and re-runnable. Callers record a run only when this is false.
     */
    val carriesSecretValues: Boolean = false,
    /**
     * Whether the rendered command actually reached a shell — see [SshCommandResult.dispatched].
     * False when the play was rejected before execution, or the backend could not start it at
     * all; callers must not record such a run as a command that ran.
     */
    val dispatched: Boolean = false
)

object PlayRunner {
    private val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

    /**
     * Execute [play] against [backend].
     *
     * The backend is provided by the caller; PlayRunner does not create or own the connection.
     * For SSH hosts the caller passes a connected [SshClient]; for LOCAL hosts a
     * [LocalShellBackend]. Each case uses [TerminalBackend.execCommand] which runs the
     * rendered script in an isolated channel/subprocess so the interactive PTY session is
     * not affected.
     *
     * @param play The play to execute.
     * @param backend A [TerminalBackend] that has been connected (or can exec without connecting,
     *   as [LocalShellBackend] does via ProcessBuilder).
     * @param values Parameter values keyed by [PlayParameter.key].
     * @param timeoutSec Maximum seconds to wait for the script to finish.
     * @param onLine Called for each line of script output (delivered after completion).
     */
    fun execute(
        play: Play,
        backend: TerminalBackend,
        values: Map<String, String>,
        timeoutSec: Long = 30,
        onLine: (String) -> Unit
    ): PlayRunResult {
        val parameters = PlayParameters.decode(play.parametersJson).ifEmpty {
            PlayParameters.inferFromTemplate(play.scriptTemplate)
        }

        // Resolve effective values: caller-supplied → parameter default → empty.
        // Required parameters with no value (and no default) are rejected before execution.
        val effectiveValues = parameters.associate { parameter ->
            val supplied = values[parameter.key].orEmpty()
            val effective = supplied.ifBlank { parameter.default.orEmpty() }
            parameter.key to effective
        }

        parameters.forEach { parameter ->
            if (parameter.required && effectiveValues[parameter.key].isNullOrBlank()) {
                return PlayRunResult(false, "Missing required value: ${parameter.label}")
            }
        }

        val rendered = renderTemplate(play.scriptTemplate, effectiveValues)
        if (rendered.isBlank()) {
            return PlayRunResult(false, "Rendered command is empty")
        }

        val carriesSecretValues = carriesSecretValues(parameters, effectiveValues)

        val result = backend.execCommand(rendered, timeoutSec * 1_000L)
        val lines = result.message.lines().filter { it.isNotEmpty() }
        lines.forEach { onLine(it) }

        return if (result.success) {
            PlayRunResult(
                true,
                "Play completed",
                outputLines = lines,
                renderedCommand = rendered,
                exitStatus = result.exitStatus,
                carriesSecretValues = carriesSecretValues,
                dispatched = result.dispatched
            )
        } else {
            PlayRunResult(
                false,
                result.message.ifBlank { "Play failed" },
                outputLines = lines,
                renderedCommand = rendered,
                exitStatus = result.exitStatus,
                carriesSecretValues = carriesSecretValues,
                dispatched = result.dispatched
            )
        }
    }

    /**
     * Whether rendering [parameters] with [effectiveValues] substitutes a secret into the
     * command — the test for [PlayRunResult.carriesSecretValues]. A secret parameter left blank
     * puts nothing in the command, so it does not count.
     */
    internal fun carriesSecretValues(
        parameters: List<PlayParameter>,
        effectiveValues: Map<String, String>
    ): Boolean = parameters.any { parameter ->
        parameter.secret && !effectiveValues[parameter.key].isNullOrBlank()
    }

    private fun renderTemplate(template: String, values: Map<String, String>): String {
        return placeholderRegex.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key].orEmpty()
            ShellUtils.shellQuote(value)
        }
    }
}
