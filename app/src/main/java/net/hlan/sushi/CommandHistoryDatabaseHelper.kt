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
 * SQLite-backed store for executed commands (roadmap v0.8.0 — Command history).
 *
 * One row per command run through Sushi, whatever issued it (AI conversation, Raw Terminal
 * Mode, or a Play). History is per-host and capped at [MAX_ENTRIES_PER_HOST] rows per host —
 * the oldest rows for that host are pruned on insert so the database stays bounded.
 *
 * Output is stored condensed (see [summarizeOutput]) rather than in full for the same reason.
 * Observers get an up-to-date view of the most recent entries via [entriesFlow].
 */
class CommandHistoryDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _entriesFlow = MutableStateFlow<List<CommandHistoryRecord>>(emptyList())

    /** Most recent entries across all hosts, refreshed on every write. */
    val entriesFlow: Flow<List<CommandHistoryRecord>> = _entriesFlow.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            refreshEntriesFlow()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_HOST_ID TEXT NOT NULL,
                $COL_HOST_LABEL TEXT NOT NULL,
                $COL_COMMAND TEXT NOT NULL,
                $COL_OUTPUT_SUMMARY TEXT NOT NULL,
                $COL_EXIT_STATUS INTEGER,
                $COL_SUCCESS INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_host ON $TABLE($COL_HOST_ID)")
        db.execSQL("CREATE INDEX idx_history_timestamp ON $TABLE($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version — no migrations yet.
    }

    /**
     * Persist [record] and prune the oldest rows for its host beyond [MAX_ENTRIES_PER_HOST].
     *
     * @return the new row id, or -1 when the insert failed.
     */
    fun record(record: CommandHistoryRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_HOST_ID, record.hostId)
            put(COL_HOST_LABEL, record.hostLabel)
            put(COL_COMMAND, record.command)
            put(COL_OUTPUT_SUMMARY, record.outputSummary)
            put(COL_EXIT_STATUS, record.exitStatus)
            put(COL_SUCCESS, if (record.success) 1 else 0)
            put(COL_SOURCE, record.source.name)
            put(COL_TIMESTAMP, record.timestamp)
        }
        val id = db.insert(TABLE, null, values)
        if (id > 0) {
            pruneHost(db, record.hostId)
            refreshEntriesFlow()
        }
        return id
    }

    /**
     * Delete the oldest rows for [hostId] once the host has more than [MAX_ENTRIES_PER_HOST].
     * `LIMIT -1 OFFSET n` in the subquery means "everything after the newest n rows".
     */
    private fun pruneHost(db: SQLiteDatabase, hostId: String) {
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE $COL_ID IN (
                SELECT $COL_ID FROM $TABLE
                WHERE $COL_HOST_ID = ?
                ORDER BY $COL_TIMESTAMP DESC, $COL_ID DESC
                LIMIT -1 OFFSET $MAX_ENTRIES_PER_HOST
            )
            """.trimIndent(),
            arrayOf(hostId)
        )
    }

    /** Most recent entries for [hostId] (newest first), used to seed the AI context. */
    fun getRecentForHost(hostId: String, limit: Int = DEFAULT_CONTEXT_ENTRIES): List<CommandHistoryRecord> {
        return query(
            selection = "$COL_HOST_ID = ?",
            selectionArgs = arrayOf(hostId),
            limit = limit
        )
    }

    /**
     * Entries matching [queryText] (command or output substring, case-insensitive), optionally
     * restricted to [hostId]. A blank [queryText] matches everything.
     */
    fun search(
        queryText: String = "",
        hostId: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT
    ): List<CommandHistoryRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (hostId != null) {
            clauses += "$COL_HOST_ID = ?"
            args += hostId
        }
        val trimmed = queryText.trim()
        if (trimmed.isNotEmpty()) {
            // LIKE is case-insensitive for ASCII in SQLite; escape the wildcards the user typed.
            val pattern = "%${trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
            clauses += "($COL_COMMAND LIKE ? ESCAPE '!' OR $COL_OUTPUT_SUMMARY LIKE ? ESCAPE '!')"
            args += pattern
            args += pattern
        }

        return query(
            selection = clauses.joinToString(" AND ").ifEmpty { null },
            selectionArgs = if (args.isEmpty()) null else args.toTypedArray(),
            limit = limit
        )
    }

    /** Distinct hosts present in history, newest activity first, for the host filter. */
    fun getHostsInHistory(): List<CommandHistoryHost> {
        val hosts = mutableListOf<CommandHistoryHost>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT $COL_HOST_ID, MAX($COL_HOST_LABEL) AS label, MAX($COL_TIMESTAMP) AS last_used
            FROM $TABLE
            GROUP BY $COL_HOST_ID
            ORDER BY last_used DESC
            """.trimIndent(),
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                hosts.add(
                    CommandHistoryHost(
                        hostId = it.getString(0),
                        hostLabel = it.getString(1).orEmpty()
                    )
                )
            }
        }
        return hosts
    }

    fun deleteEntry(id: Long): Int {
        val rows = writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        refreshEntriesFlow()
        return rows
    }

    fun clearAll(): Int {
        val rows = writableDatabase.delete(TABLE, null, null)
        refreshEntriesFlow()
        return rows
    }

    fun countForHost(hostId: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_HOST_ID = ?",
            arrayOf(hostId)
        )
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int
    ): List<CommandHistoryRecord> {
        val rows = mutableListOf<CommandHistoryRecord>()
        val cursor = readableDatabase.query(
            TABLE,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_TIMESTAMP DESC, $COL_ID DESC",
            limit.toString()
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val hostIdIdx = it.getColumnIndexOrThrow(COL_HOST_ID)
            val hostLabelIdx = it.getColumnIndexOrThrow(COL_HOST_LABEL)
            val commandIdx = it.getColumnIndexOrThrow(COL_COMMAND)
            val outputIdx = it.getColumnIndexOrThrow(COL_OUTPUT_SUMMARY)
            val exitIdx = it.getColumnIndexOrThrow(COL_EXIT_STATUS)
            val successIdx = it.getColumnIndexOrThrow(COL_SUCCESS)
            val sourceIdx = it.getColumnIndexOrThrow(COL_SOURCE)
            val timestampIdx = it.getColumnIndexOrThrow(COL_TIMESTAMP)
            while (it.moveToNext()) {
                rows.add(
                    CommandHistoryRecord(
                        id = it.getLong(idIdx),
                        hostId = it.getString(hostIdIdx),
                        hostLabel = it.getString(hostLabelIdx),
                        command = it.getString(commandIdx),
                        outputSummary = it.getString(outputIdx),
                        exitStatus = if (it.isNull(exitIdx)) null else it.getInt(exitIdx),
                        success = it.getInt(successIdx) != 0,
                        source = CommandSource.fromStorage(it.getString(sourceIdx)),
                        timestamp = it.getLong(timestampIdx)
                    )
                )
            }
        }
        return rows
    }

    private fun refreshEntriesFlow() {
        _entriesFlow.value = search()
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "sushi_command_history.db"
        private const val TABLE = "command_history"
        private const val COL_ID = "id"
        private const val COL_HOST_ID = "host_id"
        private const val COL_HOST_LABEL = "host_label"
        private const val COL_COMMAND = "command"
        private const val COL_OUTPUT_SUMMARY = "output_summary"
        private const val COL_EXIT_STATUS = "exit_status"
        private const val COL_SUCCESS = "success"
        private const val COL_SOURCE = "source"
        private const val COL_TIMESTAMP = "timestamp"

        /** Per-host row cap; oldest rows for a host are pruned once it is exceeded. */
        const val MAX_ENTRIES_PER_HOST = 500

        /** Lines of command output kept per entry. */
        const val OUTPUT_SUMMARY_LINES = 5

        /** Hard character cap on the stored output summary. */
        const val OUTPUT_SUMMARY_MAX_CHARS = 500

        /** Entries fed back into the AI prompt by default. */
        const val DEFAULT_CONTEXT_ENTRIES = 10

        /**
         * Rows loaded into the browser at once.
         *
         * Must stay well above [MAX_ENTRIES_PER_HOST] so that the unfiltered "All hosts" view
         * and free-text search can still reach rows the database is retaining: with a per-host
         * cap of 500, a limit of 500 would hide everything older than the newest 500 rows
         * across all hosts combined. This covers ten hosts at full retention, which also bounds
         * what the list can ever load into memory.
         */
        const val DEFAULT_LIST_LIMIT = MAX_ENTRIES_PER_HOST * 10

        /**
         * Condense [output] to the first [OUTPUT_SUMMARY_LINES] non-blank lines, truncated to
         * [OUTPUT_SUMMARY_MAX_CHARS]. Kept here (rather than at the call site) so every writer
         * stores output the same way.
         */
        fun summarizeOutput(output: String?): String {
            if (output.isNullOrBlank()) return ""
            val lines = output.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .take(OUTPUT_SUMMARY_LINES)
                .joinToString("\n")
            return if (lines.length > OUTPUT_SUMMARY_MAX_CHARS) {
                lines.take(OUTPUT_SUMMARY_MAX_CHARS) + "…"
            } else {
                lines
            }
        }

        @Volatile
        private var INSTANCE: CommandHistoryDatabaseHelper? = null

        fun getInstance(context: Context): CommandHistoryDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CommandHistoryDatabaseHelper(context.applicationContext)
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

/** A host that appears in command history, for the browser's host filter. */
data class CommandHistoryHost(
    val hostId: String,
    val hostLabel: String
)
