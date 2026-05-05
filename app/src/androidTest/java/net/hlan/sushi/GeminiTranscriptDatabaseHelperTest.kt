package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [GeminiTranscriptDatabaseHelper].
 *
 * SQLite ships with the device runtime, so the helper is exercised against a real on-device
 * database file (deleted before each test for isolation).
 */
@RunWith(AndroidJUnit4::class)
class GeminiTranscriptDatabaseHelperTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: GeminiTranscriptDatabaseHelper

    @Before
    fun setUp() {
        GeminiTranscriptDatabaseHelper.resetInstance()
        context.deleteDatabase("sushi_gemini_transcripts.db")
        helper = GeminiTranscriptDatabaseHelper.getInstance(context)
        helper.clearAll()
    }

    @After
    fun tearDown() {
        helper.clearAll()
        GeminiTranscriptDatabaseHelper.resetInstance()
    }

    @Test
    fun appendEntry_persistsRow() {
        val record = sampleRecord(sessionId = "s1", userMessage = "hello")
        val id = helper.appendEntry(record)
        assertTrue("inserted id should be positive", id > 0)

        val rows = helper.getEntriesForSession("s1")
        assertEquals(1, rows.size)
        assertEquals("hello", rows[0].userMessage)
        assertEquals("ack", rows[0].geminiReply)
        assertNull(rows[0].commandExecuted)
    }

    @Test
    fun appendEntry_storesCommandAndOutput() {
        helper.appendEntry(
            sampleRecord(
                sessionId = "s1",
                userMessage = "disk?",
                geminiReply = "Checking",
                commandExecuted = "df -h",
                commandOutput = "Filesystem  Size  Used Avail Use%",
                success = true
            )
        )
        val rows = helper.getEntriesForSession("s1")
        assertEquals("df -h", rows[0].commandExecuted)
        assertEquals("Filesystem  Size  Used Avail Use%", rows[0].commandOutput)
        assertTrue(rows[0].success)
    }

    @Test
    fun getEntriesForSession_orderedByTimestampAscending() {
        helper.appendEntry(sampleRecord(sessionId = "s1", timestamp = 200, userMessage = "second"))
        helper.appendEntry(sampleRecord(sessionId = "s1", timestamp = 100, userMessage = "first"))
        helper.appendEntry(sampleRecord(sessionId = "s1", timestamp = 300, userMessage = "third"))

        val rows = helper.getEntriesForSession("s1")
        assertEquals(listOf("first", "second", "third"), rows.map { it.userMessage })
    }

    @Test
    fun getAllSessions_groupsBySessionAndOrdersByLastActivityDesc() {
        helper.appendEntry(sampleRecord(sessionId = "older", timestamp = 100, userMessage = "old1"))
        helper.appendEntry(sampleRecord(sessionId = "older", timestamp = 110, userMessage = "old2"))
        helper.appendEntry(sampleRecord(sessionId = "newer", timestamp = 500, userMessage = "new1"))

        val sessions = helper.getAllSessions()
        assertEquals(listOf("newer", "older"), sessions.map { it.sessionId })
        val older = sessions.first { it.sessionId == "older" }
        assertEquals(2, older.turnCount)
        assertEquals(100L, older.startedAt)
        assertEquals(110L, older.lastActivityAt)
        assertEquals("old1", older.firstMessage)
    }

    @Test
    fun getAllSessions_firstMessageReflectsEarliestTurn() {
        helper.appendEntry(sampleRecord(sessionId = "s1", timestamp = 200, userMessage = "second"))
        helper.appendEntry(sampleRecord(sessionId = "s1", timestamp = 100, userMessage = "first"))

        val summary = helper.getAllSessions().single()
        assertEquals("first", summary.firstMessage)
    }

    @Test
    fun deleteSession_removesAllItsEntries() {
        helper.appendEntry(sampleRecord(sessionId = "keep"))
        helper.appendEntry(sampleRecord(sessionId = "drop"))
        helper.appendEntry(sampleRecord(sessionId = "drop"))

        val deleted = helper.deleteSession("drop")
        assertEquals(2, deleted)
        assertEquals(0, helper.getEntriesForSession("drop").size)
        assertEquals(1, helper.getEntriesForSession("keep").size)
    }

    @Test
    fun nullableFieldsRoundTripCleanly() {
        helper.appendEntry(
            sampleRecord(
                sessionId = "s1",
                hostId = null,
                hostLabel = null,
                commandExecuted = null,
                commandOutput = null
            )
        )
        val row = helper.getEntriesForSession("s1").single()
        assertNull(row.hostId)
        assertNull(row.hostLabel)
        assertNull(row.commandExecuted)
        assertNull(row.commandOutput)
    }

    @Test
    fun getInstance_returnsSameSingleton() {
        val a = GeminiTranscriptDatabaseHelper.getInstance(context)
        val b = GeminiTranscriptDatabaseHelper.getInstance(context)
        assertNotNull(a)
        assertTrue("getInstance should return the same singleton", a === b)
    }

    private fun sampleRecord(
        sessionId: String = "s",
        hostId: String? = "host-1",
        hostLabel: String? = "ergo",
        timestamp: Long = System.currentTimeMillis(),
        userMessage: String = "hi",
        geminiReply: String = "ack",
        commandExecuted: String? = null,
        commandOutput: String? = null,
        success: Boolean = true
    ) = GeminiTranscriptRecord(
        sessionId = sessionId,
        hostId = hostId,
        hostLabel = hostLabel,
        timestamp = timestamp,
        userMessage = userMessage,
        geminiReply = geminiReply,
        commandExecuted = commandExecuted,
        commandOutput = commandOutput,
        success = success
    )
}
