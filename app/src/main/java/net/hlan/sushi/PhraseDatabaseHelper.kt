package net.hlan.sushi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhraseDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _phrasesFlow = MutableStateFlow<List<Phrase>>(emptyList())
    val phrasesFlow: Flow<List<Phrase>> = _phrasesFlow.asStateFlow()

    init {
        refreshFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_PHRASES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_COMMAND TEXT NOT NULL
            )
        """.trimIndent()
        db.execPath(createTable)
    }

    private fun SQLiteDatabase.execPath(sql: String) {
        execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PHRASES")
        onCreate(db)
    }

    fun getAllPhrases(): List<Phrase> {
        val phrases = mutableListOf<Phrase>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_PHRASES,
            null, null, null, null, null,
            "$COLUMN_NAME ASC"
        )

        cursor.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndexOrThrow(COLUMN_ID)
                val nameIndex = it.getColumnIndexOrThrow(COLUMN_NAME)
                val commandIndex = it.getColumnIndexOrThrow(COLUMN_COMMAND)

                do {
                    phrases.add(
                        Phrase(
                            id = it.getLong(idIndex),
                            name = it.getString(nameIndex),
                            command = it.getString(commandIndex)
                        )
                    )
                } while (it.moveToNext())
            }
        }
        return phrases
    }

    fun insert(phrase: Phrase): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, phrase.name)
            put(COLUMN_COMMAND, phrase.command)
        }
        val id = db.insert(TABLE_PHRASES, null, values)
        refreshFlow()
        return id
    }

    fun update(phrase: Phrase): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, phrase.name)
            put(COLUMN_COMMAND, phrase.command)
        }
        val rows = db.update(TABLE_PHRASES, values, "$COLUMN_ID = ?", arrayOf(phrase.id.toString()))
        refreshFlow()
        return rows
    }

    fun delete(phrase: Phrase): Int {
        val db = this.writableDatabase
        val rows = db.delete(TABLE_PHRASES, "$COLUMN_ID = ?", arrayOf(phrase.id.toString()))
        refreshFlow()
        return rows
    }

    private fun refreshFlow() {
        _phrasesFlow.value = getAllPhrases()
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "sushi_phrases.db"
        private const val TABLE_PHRASES = "phrases"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_COMMAND = "command"

        @Volatile
        private var INSTANCE: PhraseDatabaseHelper? = null

        fun getInstance(context: Context): PhraseDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                val instance = PhraseDatabaseHelper(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}