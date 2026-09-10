package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the prompt context blocks appended to `SUSHI.md`
 * (roadmap v0.8.0 — command history + multi-system awareness).
 */
class ConversationContextBuilderTest {

    private val fixedFormat: (Long) -> String = { "2026-04-28 14:32" }

    // --- compose ---

    @Test
    fun compose_withNoSections_returnsPersonaUnchanged() {
        assertEquals("I am a Raspberry Pi.", ConversationContextBuilder.compose("I am a Raspberry Pi."))
    }

    @Test
    fun compose_dropsBlankSections() {
        val composed = ConversationContextBuilder.compose("persona", "", "   ")
        assertEquals("persona", composed)
    }

    @Test
    fun compose_separatesSectionsWithRule() {
        val composed = ConversationContextBuilder.compose("persona", "## Extra\n\nbody")
        assertEquals("persona\n\n---\n\n## Extra\n\nbody", composed)
    }

    // --- infrastructure ---

    @Test
    fun infrastructureSection_isEmptyForSingleHost() {
        val hosts = listOf(sshHost(id = "a", alias = "pi"))
        assertEquals("", ConversationContextBuilder.infrastructureSection(hosts, "a"))
    }

    @Test
    fun infrastructureSection_marksActiveHostOnly() {
        val hosts = listOf(
            sshHost(id = "a", alias = "pi", host = "192.168.1.5", username = "pi"),
            sshHost(id = "b", alias = "vps", host = "1.2.3.4", username = "root")
        )

        val section = ConversationContextBuilder.infrastructureSection(hosts, "a")

        assertTrue(section.contains("- pi (pi@192.168.1.5:22) — this system"))
        assertTrue(section.contains("- vps (root@1.2.3.4:22)"))
        assertFalse(section.contains("- vps (root@1.2.3.4:22) — this system"))
    }

    @Test
    fun infrastructureSection_describesLocalShellAndJumpHost() {
        val hosts = listOf(
            SshConnectionConfig(
                kind = HostKind.LOCAL,
                id = "local",
                alias = "Local shell",
                host = "",
                port = 0,
                username = "",
                password = ""
            ),
            sshHost(id = "bastion", alias = "bastion", host = "10.0.0.1", username = "jump"),
            sshHost(id = "internal", alias = "internal", host = "10.0.0.9", username = "app")
                .copy(jumpEnabled = true, jumpHostId = "bastion")
        )

        val section = ConversationContextBuilder.infrastructureSection(hosts, "internal")

        assertTrue(section.contains("- Local shell (Android device shell)"))
        assertTrue(section.contains("- internal (app@10.0.0.9:22) — reached via bastion — this system"))
    }

    // --- recent commands ---

    @Test
    fun recentCommandsSection_isEmptyWithoutRecords() {
        assertEquals("", ConversationContextBuilder.recentCommandsSection(emptyList(), "pi"))
    }

    @Test
    fun recentCommandsSection_listsCommandsOldestFirst() {
        val records = listOf(
            historyRecord(command = "uptime", output = "load 0.12", timestamp = 200L),
            historyRecord(command = "df -h", output = "45G free", timestamp = 100L)
        )

        val section = ConversationContextBuilder.recentCommandsSection(records, "pi", fixedFormat)

        val dfIndex = section.indexOf("df -h")
        val uptimeIndex = section.indexOf("uptime")
        assertTrue("older command should be listed first", dfIndex in 0 until uptimeIndex)
        assertTrue(section.contains("## Recent commands on pi"))
        assertTrue(section.contains("- [2026-04-28 14:32] df -h → 45G free"))
    }

    @Test
    fun recentCommandsSection_marksFailedCommandWithoutOutput() {
        val records = listOf(
            historyRecord(command = "systemctl status nginx", output = "", timestamp = 1L, success = false)
        )

        val section = ConversationContextBuilder.recentCommandsSection(records, "", fixedFormat)

        assertTrue(section.contains("## Recent commands on this system"))
        assertTrue(section.contains("systemctl status nginx → (failed)"))
    }

    private fun sshHost(
        id: String,
        alias: String,
        host: String = "example.test",
        username: String = "user"
    ) = SshConnectionConfig(
        id = id,
        alias = alias,
        host = host,
        port = 22,
        username = username,
        password = ""
    )

    private fun historyRecord(
        command: String,
        output: String,
        timestamp: Long,
        success: Boolean = true
    ) = CommandHistoryRecord(
        id = timestamp,
        hostId = "a",
        hostLabel = "pi",
        command = command,
        outputSummary = output,
        exitStatus = if (success) 0 else 1,
        success = success,
        source = CommandSource.CONVERSATION,
        timestamp = timestamp
    )
}
