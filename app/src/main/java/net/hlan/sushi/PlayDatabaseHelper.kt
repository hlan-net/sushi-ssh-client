package net.hlan.sushi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _playsFlow = MutableStateFlow<List<Play>>(emptyList())
    val playsFlow: Flow<List<Play>> = _playsFlow.asStateFlow()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            refreshFlow()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_PLAYS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_DESCRIPTION TEXT NOT NULL,
                $COLUMN_SCRIPT_TEMPLATE TEXT NOT NULL,
                $COLUMN_PARAMETERS_JSON TEXT NOT NULL,
                $COLUMN_MANAGED INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema migrations yet.
    }

    fun getAllPlays(): List<Play> {
        val plays = mutableListOf<Play>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_PLAYS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_NAME ASC"
        )

        cursor.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndexOrThrow(COLUMN_ID)
                val nameIndex = it.getColumnIndexOrThrow(COLUMN_NAME)
                val descriptionIndex = it.getColumnIndexOrThrow(COLUMN_DESCRIPTION)
                val scriptTemplateIndex = it.getColumnIndexOrThrow(COLUMN_SCRIPT_TEMPLATE)
                val parametersJsonIndex = it.getColumnIndexOrThrow(COLUMN_PARAMETERS_JSON)
                val managedIndex = it.getColumnIndexOrThrow(COLUMN_MANAGED)

                do {
                    plays.add(
                        Play(
                            id = it.getLong(idIndex),
                            name = it.getString(nameIndex),
                            description = it.getString(descriptionIndex),
                            scriptTemplate = it.getString(scriptTemplateIndex),
                            parametersJson = it.getString(parametersJsonIndex),
                            managed = it.getInt(managedIndex) == 1
                        )
                    )
                } while (it.moveToNext())
            }
        }

        return plays
    }

    fun getPlayByName(name: String): Play? {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_PLAYS,
            null,
            "$COLUMN_NAME = ?",
            arrayOf(name),
            null,
            null,
            null,
            "1"
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val idIndex = it.getColumnIndexOrThrow(COLUMN_ID)
            val nameIndex = it.getColumnIndexOrThrow(COLUMN_NAME)
            val descriptionIndex = it.getColumnIndexOrThrow(COLUMN_DESCRIPTION)
            val scriptTemplateIndex = it.getColumnIndexOrThrow(COLUMN_SCRIPT_TEMPLATE)
            val parametersJsonIndex = it.getColumnIndexOrThrow(COLUMN_PARAMETERS_JSON)
            val managedIndex = it.getColumnIndexOrThrow(COLUMN_MANAGED)
            return Play(
                id = it.getLong(idIndex),
                name = it.getString(nameIndex),
                description = it.getString(descriptionIndex),
                scriptTemplate = it.getString(scriptTemplateIndex),
                parametersJson = it.getString(parametersJsonIndex),
                managed = it.getInt(managedIndex) == 1
            )
        }
    }

    fun insert(play: Play): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, play.name)
            put(COLUMN_DESCRIPTION, play.description)
            put(COLUMN_SCRIPT_TEMPLATE, play.scriptTemplate)
            put(COLUMN_PARAMETERS_JSON, play.parametersJson)
            put(COLUMN_MANAGED, if (play.managed) 1 else 0)
        }
        val id = db.insert(TABLE_PLAYS, null, values)
        refreshFlow()
        return id
    }

    fun update(play: Play): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, play.name)
            put(COLUMN_DESCRIPTION, play.description)
            put(COLUMN_SCRIPT_TEMPLATE, play.scriptTemplate)
            put(COLUMN_PARAMETERS_JSON, play.parametersJson)
            put(COLUMN_MANAGED, if (play.managed) 1 else 0)
        }
        val rows = db.update(TABLE_PLAYS, values, "$COLUMN_ID = ?", arrayOf(play.id.toString()))
        refreshFlow()
        return rows
    }

    fun delete(play: Play): Int {
        val db = this.writableDatabase
        val rows = db.delete(TABLE_PLAYS, "$COLUMN_ID = ?", arrayOf(play.id.toString()))
        refreshFlow()
        return rows
    }

    fun upsertByName(
        name: String,
        description: String,
        scriptTemplate: String,
        parametersJson: String = "[]",
        managed: Boolean = false
    ): UpsertResult {
        val normalizedName = name.trim()
        val existing = getPlayByName(normalizedName)
        return if (existing == null) {
            insert(
                Play(
                    name = normalizedName,
                    description = description.trim(),
                    scriptTemplate = scriptTemplate.trim(),
                    parametersJson = parametersJson,
                    managed = managed
                )
            )
            UpsertResult(inserted = 1, updated = 0)
        } else {
            update(
                existing.copy(
                    description = description.trim(),
                    scriptTemplate = scriptTemplate.trim(),
                    parametersJson = parametersJson,
                    managed = managed
                )
            )
            UpsertResult(inserted = 0, updated = 1)
        }
    }

    private fun refreshFlow() {
        _playsFlow.value = getAllPlays()
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "sushi_plays.db"
        private const val TABLE_PLAYS = "plays"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_DESCRIPTION = "description"
        private const val COLUMN_SCRIPT_TEMPLATE = "script_template"
        private const val COLUMN_PARAMETERS_JSON = "parameters_json"
        private const val COLUMN_MANAGED = "managed"

        @Volatile
        private var INSTANCE: PlayDatabaseHelper? = null

        fun getInstance(context: Context): PlayDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                val instance = PlayDatabaseHelper(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
