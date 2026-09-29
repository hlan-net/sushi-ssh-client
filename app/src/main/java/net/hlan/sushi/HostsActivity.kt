package net.hlan.sushi

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import net.hlan.sushi.databinding.ActivityHostsBinding

class HostsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostsBinding
    private val sshSettings by lazy { SshSettings(this) }
    private lateinit var adapter: HostAdapter
    private var corruptionNoticeAnswered = false
    private var corruptionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        corruptionNoticeAnswered = savedInstanceState?.getBoolean(STATE_CORRUPTION_NOTICE_ANSWERED) == true
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivityHostsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HostAdapter(
            onHostClick = { host ->
                sshSettings.setActiveHostId(host.id)
                startActivity(TerminalActivity.createIntent(this, autoConnect = true))
                finish()
            },
            onEditClick = { host ->
                val intent = Intent(this, HostEditActivity::class.java).apply {
                    putExtra(HostEditActivity.EXTRA_HOST_ID, host.id)
                }
                startActivity(intent)
            }
        )

        binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.hostsRecyclerView.adapter = adapter

        binding.addHostFab.setOnClickListener {
            startActivity(Intent(this, HostEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHosts()
        maybeShowCorruptionNotice()
    }

    private fun refreshHosts() {
        val hosts = sshSettings.getHosts()
        adapter.activeHostId = sshSettings.getActiveHostId()
        adapter.submitList(hosts)

        if (hosts.isEmpty()) {
            binding.emptyHostsText.visibility = View.VISIBLE
            binding.hostsRecyclerView.visibility = View.GONE
        } else {
            binding.emptyHostsText.visibility = View.GONE
            binding.hostsRecyclerView.visibility = View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CORRUPTION_NOTICE_ANSWERED, corruptionNoticeAnswered)
    }

    override fun onDestroy() {
        corruptionDialog?.dismiss()
        super.onDestroy()
    }

    /**
     * One-time notice for a hosts blob [SshSettings.getHosts] could not parse (ROADMAP.md
     * v0.9.x). Once answered it stays answered for this visit — across rotation too, through
     * the saved state — so it doesn't nag on every `onResume`. An unanswered one that rotation
     * tore down is shown again: the Restore choice must not vanish with the old window.
     */
    private fun maybeShowCorruptionNotice() {
        if (corruptionNoticeAnswered || corruptionDialog?.isShowing == true || !sshSettings.hasCorruptHosts()) {
            return
        }

        val hasBackup = sshSettings.hasHostsBackup()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.hosts_corrupt_title)
            .setMessage(
                if (hasBackup) R.string.hosts_corrupt_message_with_backup
                else R.string.hosts_corrupt_message_no_backup
            )
            .setNegativeButton(R.string.action_dismiss) { _, _ ->
                corruptionNoticeAnswered = true
                sshSettings.dismissHostsCorruptionNotice()
            }
            .setOnCancelListener {
                corruptionNoticeAnswered = true
                sshSettings.dismissHostsCorruptionNotice()
            }
        if (hasBackup) {
            builder.setPositiveButton(R.string.action_restore) { _, _ ->
                corruptionNoticeAnswered = true
                sshSettings.restoreHostsFromBackup()
                refreshHosts()
            }
        }
        corruptionDialog = builder.show()
    }

    companion object {
        private const val STATE_CORRUPTION_NOTICE_ANSWERED = "corruption_notice_answered"
    }
}
