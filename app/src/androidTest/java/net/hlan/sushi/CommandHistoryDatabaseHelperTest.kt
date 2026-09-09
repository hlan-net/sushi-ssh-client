package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CommandHistoryDatabaseHelper] (roadmap v0.8.0).
 *
 * SQLite ships with the device runtime, so the helper is exercised against a real on-device
 * database file (deleted before each test for isolation).
 */
@RunWith(AndroidJUnit4::class)
class CommandHistoryDatabaseHelperTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: CommandHistoryDatabaseHelper

    @Before
    fun setUp() {
        CommandHistoryDatabaseHelper.resetInstance()
        context.deleteDatabase("sushi_command_history.db")
        helper = CommandHistoryDatabaseHelper.getInstance(context)
        helper.clearAll()
    }

    @After
    fun tearDown() {
        helper.clearAll()
        CommandHistoryDatabaseHelper.resetInstance()
    }

    @Test
    fun record_persistsRow() {
        val id = helper.record(sample(command = "df -h", output = "45G free"))
        assertTrue("inserted id should be positive", id > 0)

        val rows = helper.getRecentForHost("host-a")
        assertEquals(1, rows.size)
        assertEquals("df -h", rows[0].command)
        assertEquals("45G free", rows[0].outputSummary)
        assertEquals(CommandSource.CONVERSATION, rows[0].source)
        assertEquals(0, rows[0].exitStatus)
    }

    @Test
    fun getRecentForHost_returnsNewestFirstAndIsScopedToHost() {
        helper.record(sample(command = "old", timestamp = 100L))
        helper.record(sample(command = "new", timestamp = 200L))
        helper.record(sample(command = "other host", hostId = "host-b", timestamp = 300L))

        val rows = helper.getRecentForHost("host-a")

        assertEquals(listOf("new", "old"), rows.map { it.command })
    }

    @Test
    fun search_matchesCommandAndOutputAndFiltersByHost() {
        helper.record(sample(command = "systemctl status nginx", output = "active"))
        helper.record(sample(command = "uptime", output = "load 0.12"))
        helper.record(sample(command = "nginx -t", hostId = "host-b", output = "ok"))

        assertEquals(2, helper.search("nginx").size)
        assertEquals(1, helper.search("nginx", hostId = "host-a").size)
        assertEquals(1, helper.search("load 0.12").size)
        assertEquals(3, helper.search().size)
    }

    @Test
    fun search_treatsWildcardsAsLiterals() {
        helper.record(sample(command = "echo 100%"))
        helper.record(sample(command = "echo hello"))

        val rows = helper.search("100%")

        assertEquals(1, rows.size)
        assertEquals("echo 100%", rows[0].command)
    }

    @Test
    fun record_prunesOldestBeyondPerHostCap() {
        val cap = CommandHistoryDatabaseHelper.MAX_ENTRIES_PER_HOST
        repeat(cap + 5) { index ->
            helper.record(sample(command = "cmd-$index", timestamp = 1_000L + index))
        }

        assertEquals(cap, helper.countForHost("host-a"))

        val commands = helper.search(hostId = "host-a").map { it.command }
        assertTrue("newest entry should survive", commands.contains("cmd-${cap + 4}"))
        assertFalse("oldest entry should be pruned", commands.contains("cmd-0"))
    }

    @Test
    fun getHostsInHistory_returnsDistinctHostsNewestFirst() {
        helper.record(sample(command = "a", hostId = "host-a", timestamp = 100L))
        helper.record(sample(command = "b", hostId = "host-b", hostLabel = "vps", timestamp = 200L))
        helper.record(sample(command = "c", hostId = "host-a", timestamp = 300L))

        val hosts = helper.getHostsInHistory()

        assertEquals(listOf("host-a", "host-b"), hosts.map { it.hostId })
        assertEquals("vps", hosts[1].hostLabel)
    }

    @Test
    fun deleteEntry_removesSingleRow() {
        val id = helper.record(sample(command = "df -h"))
        helper.record(sample(command = "uptime"))

        helper.deleteEntry(id)

        assertEquals(listOf("uptime"), helper.search().map { it.command })
    }

    @Test
    fun summarizeOutput_keepsLeadingLinesAndTruncates() {
        val summary = CommandHistoryDatabaseHelper.summarizeOutput(
            (1..20).joinToString("\n") { "line $it" }
        )

        assertEquals(CommandHistoryDatabaseHelper.OUTPUT_SUMMARY_LINES, summary.lines().size)
        assertTrue(summary.startsWith("line 1"))

        val long = CommandHistoryDatabaseHelper.summarizeOutput("x".repeat(2_000))
        assertEquals(CommandHistoryDatabaseHelper.OUTPUT_SUMMARY_MAX_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", CommandHistoryDatabaseHelper.summarizeOutput(null))
    }

    private fun sample(
        command: String,
        output: String = "",
        hostId: String = "host-a",
        hostLabel: String = "pi",
        timestamp: Long = System.currentTimeMillis(),
        success: Boolean = true
    ) = CommandHistoryRecord(
        hostId = hostId,
        hostLabel = hostLabel,
        command = command,
        outputSummary = output,
        exitStatus = if (success) 0 else 1,
        success = success,
        source = CommandSource.CONVERSATION,
        timestamp = timestamp
    )
}
