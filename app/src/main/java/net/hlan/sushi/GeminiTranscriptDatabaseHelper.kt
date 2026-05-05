package net.hlan.sushi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SQLite-backed store for Gemini conversation transcripts.
 *
 * One row per turn (user message + Gemini reply, optionally with executed command + output).
 * Rows are grouped by [GeminiTranscriptRecord.sessionId] so the history UI can list distinct
 * sessions. The reactive [sessionsFlow] surfaces an up-to-date session list to observers.
 */
class GeminiTranscriptDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _sessionsFlow = MutableStateFlow<List<GeminiTranscriptSessionSummary>>(emptyList())
    val sessionsFlow: Flow<List<GeminiTranscriptSessionSummary>> = _sessionsFlow.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            refreshSessionsFlow()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_ID TEXT NOT NULL,
                $COL_HOST_ID TEXT,
                $COL_HOST_LABEL TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_USER_MESSAGE TEXT NOT NULL,
                $COL_GEMINI_REPLY TEXT NOT NULL,
                $COL_COMMAND_EXECUTED TEXT,
                $COL_COMMAND_OUTPUT TEXT,
                $COL_SUCCESS INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session ON $TABLE($COL_SESSION_ID)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version — no migrations yet.
    }

    fun appendEntry(record: GeminiTranscriptRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_ID, record.sessionId)
            put(COL_HOST_ID, record.hostId)
            put(COL_HOST_LABEL, record.hostLabel)
            put(COL_TIMESTAMP, record.timestamp)
            put(COL_USER_MESSAGE, record.userMessage)
            put(COL_GEMINI_REPLY, record.geminiReply)
            put(COL_COMMAND_EXECUTED, record.commandExecuted)
            put(COL_COMMAND_OUTPUT, record.commandOutput)
            put(COL_SUCCESS, if (record.success) 1 else 0)
        }
        val id = db.insert(TABLE, null, values)
        refreshSessionsFlow()
        return id
    }

    fun getEntriesForSession(sessionId: String): List<GeminiTranscriptRecord> {
        val rows = mutableListOf<GeminiTranscriptRecord>()
        val cursor = readableDatabase.query(
            TABLE,
            null,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_TIMESTAMP ASC, $COL_ID ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val sessionIdx = it.getColumnIndexOrThrow(COL_SESSION_ID)
            val hostIdIdx = it.getColumnIndexOrThrow(COL_HOST_ID)
            val hostLabelIdx = it.getColumnIndexOrThrow(COL_HOST_LABEL)
            val tsIdx = it.getColumnIndexOrThrow(COL_TIMESTAMP)
            val userIdx = it.getColumnIndexOrThrow(COL_USER_MESSAGE)
            val replyIdx = it.getColumnIndexOrThrow(COL_GEMINI_REPLY)
            val cmdIdx = it.getColumnIndexOrThrow(COL_COMMAND_EXECUTED)
            val outIdx = it.getColumnIndexOrThrow(COL_COMMAND_OUTPUT)
            val successIdx = it.getColumnIndexOrThrow(COL_SUCCESS)
            while (it.moveToNext()) {
                rows.add(
                    GeminiTranscriptRecord(
                        id = it.getLong(idIdx),
                        sessionId = it.getString(sessionIdx),
                        hostId = if (it.isNull(hostIdIdx)) null else it.getString(hostIdIdx),
                        hostLabel = if (it.isNull(hostLabelIdx)) null else it.getString(hostLabelIdx),
                        timestamp = it.getLong(tsIdx),
                        userMessage = it.getString(userIdx),
                        geminiReply = it.getString(replyIdx),
                        commandExecuted = if (it.isNull(cmdIdx)) null else it.getString(cmdIdx),
                        commandOutput = if (it.isNull(outIdx)) null else it.getString(outIdx),
                        success = it.getInt(successIdx) != 0
                    )
                )
            }
        }
        return rows
    }

    fun getAllSessions(): List<GeminiTranscriptSessionSummary> {
        val sessions = mutableListOf<GeminiTranscriptSessionSummary>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT
                $COL_SESSION_ID,
                MAX($COL_HOST_LABEL) AS host_label,
                COUNT(*) AS turn_count,
                MIN($COL_TIMESTAMP) AS started_at,
                MAX($COL_TIMESTAMP) AS last_activity_at
            FROM $TABLE
            GROUP BY $COL_SESSION_ID
            ORDER BY last_activity_at DESC
            """.trimIndent(),
            null
        )
        cursor.use {
            val sessionIdx = it.getColumnIndexOrThrow(COL_SESSION_ID)
            val hostLabelIdx = it.getColumnIndexOrThrow("host_label")
            val countIdx = it.getColumnIndexOrThrow("turn_count")
            val startIdx = it.getColumnIndexOrThrow("started_at")
            val lastIdx = it.getColumnIndexOrThrow("last_activity_at")
            while (it.moveToNext()) {
                val sessionId = it.getString(sessionIdx)
                sessions.add(
                    GeminiTranscriptSessionSummary(
                        sessionId = sessionId,
                        hostLabel = if (it.isNull(hostLabelIdx)) null else it.getString(hostLabelIdx),
                        firstMessage = firstMessageFor(sessionId).orEmpty(),
                        turnCount = it.getInt(countIdx),
                        startedAt = it.getLong(startIdx),
                        lastActivityAt = it.getLong(lastIdx)
                    )
                )
            }
        }
        return sessions
    }

    private fun firstMessageFor(sessionId: String): String? {
        val cursor = readableDatabase.query(
            TABLE,
            arrayOf(COL_USER_MESSAGE),
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_TIMESTAMP ASC, $COL_ID ASC",
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun deleteSession(sessionId: String): Int {
        val rows = writableDatabase.delete(TABLE, "$COL_SESSION_ID = ?", arrayOf(sessionId))
        refreshSessionsFlow()
        return rows
    }

    fun clearAll(): Int {
        val rows = writableDatabase.delete(TABLE, null, null)
        refreshSessionsFlow()
        return rows
    }

    private fun refreshSessionsFlow() {
        _sessionsFlow.value = getAllSessions()
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "sushi_gemini_transcripts.db"
        private const val TABLE = "transcripts"
        private const val COL_ID = "id"
        private const val COL_SESSION_ID = "session_id"
        private const val COL_HOST_ID = "host_id"
        private const val COL_HOST_LABEL = "host_label"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_USER_MESSAGE = "user_message"
        private const val COL_GEMINI_REPLY = "gemini_reply"
        private const val COL_COMMAND_EXECUTED = "command_executed"
        private const val COL_COMMAND_OUTPUT = "command_output"
        private const val COL_SUCCESS = "success"

        @Volatile
        private var INSTANCE: GeminiTranscriptDatabaseHelper? = null

        fun getInstance(context: Context): GeminiTranscriptDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeminiTranscriptDatabaseHelper(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
