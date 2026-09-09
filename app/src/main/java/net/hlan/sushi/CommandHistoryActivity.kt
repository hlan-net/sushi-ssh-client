package net.hlan.sushi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityCommandHistoryBinding
import net.hlan.sushi.databinding.ItemCommandHistoryBinding
import java.util.Date

/**
 * Browser for the local command history (roadmap v0.8.0).
 *
 * Lists every command Sushi executed — from the AI conversation, Raw Terminal Mode, or a Play —
 * with free-text search and a per-host filter. Tapping an entry offers copy, delete, and re-run;
 * re-run hands the command back to the caller (see [EXTRA_RERUN_COMMAND]) rather than opening a
 * connection of its own, so it runs through the same safety classification as any other command.
 */
class CommandHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandHistoryBinding
    private val db by lazy { CommandHistoryDatabaseHelper.getInstance(this) }
    private lateinit var adapter: HistoryAdapter

    private var searchQuery: String = ""
    private var hostFilterId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.commandHistoryToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.command_history_title)

        adapter = HistoryAdapter(this) { record -> showEntryActions(record) }
        binding.commandHistoryRecycler.layoutManager = LinearLayoutManager(this)
        binding.commandHistoryRecycler.adapter = adapter

        binding.commandHistorySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                reload()
            }
        })

        binding.commandHistoryHostFilterButton.setOnClickListener { showHostFilterDialog() }

        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_command_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_clear_command_history -> { confirmClearAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reload() {
        val query = searchQuery
        val hostId = hostFilterId
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = runCatching { db.search(query, hostId) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                adapter.submitList(entries)
                val empty = entries.isEmpty()
                binding.commandHistoryEmptyText.visibility = if (empty) View.VISIBLE else View.GONE
                binding.commandHistoryRecycler.visibility = if (empty) View.GONE else View.VISIBLE
                binding.commandHistoryEmptyText.text = getString(
                    if (query.isBlank() && hostId == null) {
                        R.string.command_history_empty
                    } else {
                        R.string.command_history_empty_filtered
                    }
                )
            }
        }
    }

    private fun showHostFilterDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hosts = runCatching { db.getHostsInHistory() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                val labels = mutableListOf(getString(R.string.command_history_filter_all))
                labels += hosts.map { it.hostLabel.ifBlank { getString(R.string.command_history_unknown_host) } }

                AlertDialog.Builder(this@CommandHistoryActivity)
                    .setTitle(R.string.command_history_filter_title)
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == 0) {
                            hostFilterId = null
                            binding.commandHistoryHostFilterButton.text =
                                getString(R.string.command_history_filter_all)
                        } else {
                            val host = hosts[which - 1]
                            hostFilterId = host.hostId
                            binding.commandHistoryHostFilterButton.text =
                                getString(R.string.command_history_filter_host, labels[which])
                        }
                        reload()
                    }
                    .setNegativeButton(R.string.phrase_cancel, null)
                    .show()
            }
        }
    }

    private fun showEntryActions(record: CommandHistoryRecord) {
        val actions = arrayOf(
            getString(R.string.command_history_action_rerun),
            getString(R.string.command_history_action_copy),
            getString(R.string.command_history_action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(record.command)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> rerun(record)
                    1 -> copyCommand(record.command)
                    2 -> deleteEntry(record)
                }
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    /** Hand the command back to the caller, which decides whether it can be run right now. */
    private fun rerun(record: CommandHistoryRecord) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RERUN_COMMAND, record.command))
        finish()
    }

    private fun copyCommand(command: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.command_history_title), command)
        )
        Toast.makeText(this, R.string.command_history_copied, Toast.LENGTH_SHORT).show()
    }

    private fun deleteEntry(record: CommandHistoryRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.deleteEntry(record.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@CommandHistoryActivity,
                    R.string.command_history_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.command_history_clear_all_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.clearAll()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@CommandHistoryActivity,
                            R.string.command_history_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class HistoryAdapter(
        private val context: Context,
        private val onClick: (CommandHistoryRecord) -> Unit
    ) : ListAdapter<CommandHistoryRecord, HistoryAdapter.HistoryVH>(DIFF) {

        private val dateFormat = DateFormat.getMediumDateFormat(context)
        private val timeFormat = DateFormat.getTimeFormat(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryVH {
            val b = ItemCommandHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return HistoryVH(b)
        }

        override fun onBindViewHolder(holder: HistoryVH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class HistoryVH(private val b: ItemCommandHistoryBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(record: CommandHistoryRecord) {
                b.historyHostLabel.text = record.hostLabel.ifBlank {
                    context.getString(R.string.command_history_unknown_host)
                }
                val date = Date(record.timestamp)
                b.historyTimestamp.text = "${dateFormat.format(date)} ${timeFormat.format(date)}"
                b.historyCommand.text = record.command

                if (record.outputSummary.isBlank()) {
                    b.historyOutput.visibility = View.GONE
                } else {
                    b.historyOutput.visibility = View.VISIBLE
                    b.historyOutput.text = record.outputSummary
                }

                val source = context.getString(sourceLabel(record.source))
                val status = when {
                    record.success -> context.getString(R.string.command_history_status_ok)
                    record.exitStatus != null ->
                        context.getString(R.string.command_history_status_exit, record.exitStatus)
                    else -> context.getString(R.string.command_history_status_failed)
                }
                b.historyMeta.text = context.getString(
                    R.string.command_history_meta, source, status
                )

                b.root.setOnClickListener { onClick(record) }
            }

            private fun sourceLabel(source: CommandSource): Int = when (source) {
                CommandSource.CONVERSATION -> R.string.command_history_source_conversation
                CommandSource.RAW -> R.string.command_history_source_raw
                CommandSource.PLAY -> R.string.command_history_source_play
                CommandSource.UNKNOWN -> R.string.command_history_source_unknown
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<CommandHistoryRecord>() {
                override fun areItemsTheSame(a: CommandHistoryRecord, b: CommandHistoryRecord) =
                    a.id == b.id

                override fun areContentsTheSame(a: CommandHistoryRecord, b: CommandHistoryRecord) =
                    a == b
            }
        }
    }

    companion object {
        /** Result extra carrying the command the user asked to re-run. */
        const val EXTRA_RERUN_COMMAND = "extra_rerun_command"

        fun createIntent(context: Context): Intent =
            Intent(context, CommandHistoryActivity::class.java)
    }
}
