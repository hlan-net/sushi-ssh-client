package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a *Reboot Host* run is reported (Figma node-id=145-75). */
class RebootHostPlayTest {

    private fun result(
        success: Boolean,
        exitStatus: Int?,
        dispatched: Boolean = true,
        output: List<String> = emptyList(),
        message: String = if (success) "Play completed" else "Play failed"
    ) = PlayRunResult(
        success = success,
        message = message,
        outputLines = output,
        renderedCommand = RebootHostPlay.SCRIPT,
        exitStatus = exitStatus,
        dispatched = dispatched
    )

    @Test
    fun exitZero_isSent() {
        assertEquals(RebootHostPlay.Outcome.SENT, RebootHostPlay.outcome(result(true, 0)))
    }

    /** The host dropped the Play's own connection before sshd reported an exit status. */
    @Test
    fun channelClosedWithoutStatus_isSent() {
        assertEquals(RebootHostPlay.Outcome.SENT, RebootHostPlay.outcome(result(false, -1)))
    }

    @Test
    fun timeoutAfterDispatch_isSent() {
        assertEquals(RebootHostPlay.Outcome.SENT, RebootHostPlay.outcome(result(false, null)))
    }

    @Test
    fun sudoAskingForAPassword_needsPasswordlessSudo() {
        val refused = result(false, 1, output = listOf("sudo: a password is required"), message = "sudo: a password is required")
        assertEquals(RebootHostPlay.Outcome.NEEDS_PASSWORDLESS_SUDO, RebootHostPlay.outcome(refused))
    }

    /** Older sudo without -n support wording, or a sudo that still tried to prompt. */
    @Test
    fun sudoWantingATerminal_needsPasswordlessSudo() {
        val refused = result(false, 1, output = listOf("sudo: a terminal is required to read the password"))
        assertEquals(RebootHostPlay.Outcome.NEEDS_PASSWORDLESS_SUDO, RebootHostPlay.outcome(refused))
    }

    @Test
    fun otherNonZeroExit_isFailed() {
        val missing = result(false, 127, output = listOf("sh: sudo: command not found"))
        assertEquals(RebootHostPlay.Outcome.FAILED, RebootHostPlay.outcome(missing))
    }

    @Test
    fun notDispatched_isFailed() {
        assertEquals(RebootHostPlay.Outcome.FAILED, RebootHostPlay.outcome(result(false, null, dispatched = false)))
    }

    @Test
    fun onlyTheManagedRebootPlayCounts() {
        val managed = Play(name = ManagedPlays.PLAY_REBOOT_HOST, description = "", scriptTemplate = RebootHostPlay.SCRIPT, managed = true)
        assertTrue(RebootHostPlay.isRebootPlay(managed))
        assertFalse(RebootHostPlay.isRebootPlay(managed.copy(managed = false)))
        assertFalse(RebootHostPlay.isRebootPlay(managed.copy(name = "Update packages")))
    }

    @Test
    fun scriptDoesNotPromptForAPassword() {
        assertEquals("sudo -n reboot", RebootHostPlay.SCRIPT)
    }
}
