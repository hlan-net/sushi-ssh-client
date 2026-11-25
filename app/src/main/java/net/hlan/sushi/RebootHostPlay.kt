package net.hlan.sushi

import android.content.Context
import java.util.Locale

/**
 * What the managed *Reboot Host* Play needs beyond running its script (Figma `node-id=145-75`).
 *
 * The script is `sudo -n reboot`. Plays run over the exec channel, which has no terminal, so sudo
 * cannot ask for a password: `-n` makes it fail at once with "a password is required" instead
 * of waiting for one it will never get. The host therefore needs passwordless sudo for reboot,
 * or a root login — the maintainer chose that over prompting for a password.
 */
object RebootHostPlay {

    const val SCRIPT = "sudo -n reboot"

    enum class Outcome {
        /** The reboot was accepted, or the host went down before an exit status came back. */
        SENT,

        /** sudo refused because it needs a password. Nothing was changed. */
        NEEDS_PASSWORDLESS_SUDO,

        /** Anything else: no connection, no sudo, another error. */
        FAILED
    }

    fun isRebootPlay(play: Play): Boolean = play.managed && play.name == ManagedPlays.PLAY_REBOOT_HOST

    /**
     * Exit 0 is sent. So is a dispatched run with no exit status (`null` on a timeout, `-1` when
     * the channel closed first): a reboot takes the Play's own connection down with it, often
     * before sshd reports anything, and calling that a failure would contradict what the host is
     * doing. A non-zero status means the command ran and was refused.
     */
    fun outcome(result: PlayRunResult): Outcome {
        val status = result.exitStatus
        return when {
            !result.dispatched -> Outcome.FAILED
            status == 0 || status == null || status == -1 -> Outcome.SENT
            needsPassword(result) -> Outcome.NEEDS_PASSWORDLESS_SUDO
            else -> Outcome.FAILED
        }
    }

    private fun needsPassword(result: PlayRunResult): Boolean {
        val output = (result.outputLines + result.message).joinToString("\n").lowercase(Locale.ROOT)
        return "password is required" in output || "a terminal is required" in output
    }

    /** The session-log line for [outcome], or null to fall back to the generic Play result. */
    fun logLine(context: Context, outcome: Outcome, hostLabel: String): String? = when (outcome) {
        Outcome.SENT -> context.getString(R.string.play_reboot_sent, hostLabel)
        Outcome.NEEDS_PASSWORDLESS_SUDO -> context.getString(R.string.play_reboot_needs_passwordless_sudo, hostLabel)
        Outcome.FAILED -> null
    }
}
