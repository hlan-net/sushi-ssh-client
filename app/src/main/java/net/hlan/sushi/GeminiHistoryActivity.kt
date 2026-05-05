package net.hlan.sushi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityGeminiHistoryBinding
import net.hlan.sushi.databinding.ItemGeminiHistorySessionBinding
import net.hlan.sushi.databinding.ItemGeminiTranscriptBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeminiHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeminiHistoryBinding
    private val db by lazy { GeminiTranscriptDatabaseHelper.getInstance(this) }
    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeminiHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.geminiHistoryToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        currentSessionId = sessionId
        if (sessionId != null) {
            showTurns(sessionId)
        } else {
            showSessionList()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (currentSessionId != null) {
            menuInflater.inflate(R.menu.menu_gemini_history_session, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_delete_session -> { confirmDeleteSession(currentSessionId!!); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSessionList() {
        supportActionBar?.title = getString(R.string.gemini_history_title)

        lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.getAllSessions()
            withContext(Dispatchers.Main) {
                if (sessions.isEmpty()) {
                    binding.geminiHistoryEmptyText.visibility = View.VISIBLE
                    binding.geminiHistorySessionsRecycler.visibility = View.GONE
                } else {
                    binding.geminiHistoryEmptyText.visibility = View.GONE
                    binding.geminiHistorySessionsRecycler.visibility = View.VISIBLE
                    binding.geminiHistorySessionsRecycler.layoutManager =
                        LinearLayoutManager(this@GeminiHistoryActivity)
                    binding.geminiHistorySessionsRecycler.adapter = SessionListAdapter(sessions) { session ->
                        startActivity(createIntentForSession(this@GeminiHistoryActivity, session.sessionId))
                    }
                }
            }
        }
    }

    private fun showTurns(sessionId: String) {
        supportActionBar?.title = getString(R.string.gemini_history_session_title)

        lifecycleScope.launch(Dispatchers.IO) {
            val turns = db.getEntriesForSession(sessionId)
            withContext(Dispatchers.Main) {
                if (turns.isEmpty()) {
                    binding.geminiHistoryEmptyText.visibility = View.VISIBLE
                    binding.geminiHistoryTurnsRecycler.visibility = View.GONE
                } else {
                    binding.geminiHistoryEmptyText.visibility = View.GONE
                    binding.geminiHistoryTurnsRecycler.visibility = View.VISIBLE
                    binding.geminiHistoryTurnsRecycler.layoutManager =
                        LinearLayoutManager(this@GeminiHistoryActivity)
                    binding.geminiHistoryTurnsRecycler.adapter = TurnListAdapter(turns)
                }
            }
        }
    }

    private fun confirmDeleteSession(sessionId: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.gemini_history_delete_session)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.deleteSession(sessionId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GeminiHistoryActivity,
                            R.string.gemini_history_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Session list adapter ---

    private inner class SessionListAdapter(
        private val sessions: List<GeminiTranscriptSessionSummary>,
        private val onSessionClick: (GeminiTranscriptSessionSummary) -> Unit
    ) : RecyclerView.Adapter<SessionListAdapter.SessionVH>() {

        private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        override fun getItemCount() = sessions.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionVH {
            val b = ItemGeminiHistorySessionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return SessionVH(b)
        }

        override fun onBindViewHolder(holder: SessionVH, position: Int) {
            holder.bind(sessions[position])
        }

        inner class SessionVH(private val b: ItemGeminiHistorySessionBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(session: GeminiTranscriptSessionSummary) {
                b.sessionHostLabel.text = session.hostLabel
                    ?: getString(R.string.gemini_history_unknown_host)
                b.sessionFirstMessage.text = session.firstMessage
                b.sessionTurnCount.text = getString(R.string.gemini_history_turns, session.turnCount)
                b.sessionTimestamp.text = dateFormat.format(Date(session.startedAt))
                b.root.setOnClickListener { onSessionClick(session) }
            }
        }
    }

    // --- Turn detail adapter ---

    private class TurnListAdapter(
        private val turns: List<GeminiTranscriptRecord>
    ) : RecyclerView.Adapter<TurnListAdapter.TurnVH>() {

        override fun getItemCount() = turns.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TurnVH {
            val b = ItemGeminiTranscriptBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TurnVH(b)
        }

        override fun onBindViewHolder(holder: TurnVH, position: Int) {
            holder.bind(turns[position])
        }

        class TurnVH(private val b: ItemGeminiTranscriptBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(record: GeminiTranscriptRecord) {
                b.transcriptPromptContent.text = record.userMessage
                b.transcriptResponseContent.text = record.geminiReply

                val cmd = record.commandExecuted
                if (!cmd.isNullOrBlank()) {
                    b.transcriptCommandSection.visibility = View.VISIBLE
                    b.transcriptCommandContent.text = cmd

                    val output = record.commandOutput
                    if (!output.isNullOrBlank()) {
                        b.transcriptOutputLabel.visibility = View.VISIBLE
                        b.transcriptOutputContent.visibility = View.VISIBLE
                        b.transcriptOutputContent.text = output
                    } else {
                        b.transcriptOutputLabel.visibility = View.GONE
                        b.transcriptOutputContent.visibility = View.GONE
                    }
                } else {
                    b.transcriptCommandSection.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION_ID = "extra_session_id"

        fun createIntent(context: Context): Intent =
            Intent(context, GeminiHistoryActivity::class.java)

        fun createIntentForSession(context: Context, sessionId: String): Intent =
            Intent(context, GeminiHistoryActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
