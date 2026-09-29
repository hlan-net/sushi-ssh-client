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
    private var corruptionNoticeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /**
     * One-time notice for a hosts blob [SshSettings.getHosts] could not parse (ROADMAP.md
     * v0.9.x) — shown once per visit to this screen so it doesn't nag on every `onResume`
     * (rotation, returning from [HostEditActivity]) while the underlying blob stays unfixed.
     */
    private fun maybeShowCorruptionNotice() {
        if (corruptionNoticeShown || !sshSettings.hasCorruptHosts()) {
            return
        }
        corruptionNoticeShown = true

        val hasBackup = sshSettings.hasHostsBackup()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.hosts_corrupt_title)
            .setMessage(
                if (hasBackup) R.string.hosts_corrupt_message_with_backup
                else R.string.hosts_corrupt_message_no_backup
            )
            .setNegativeButton(R.string.action_dismiss) { _, _ ->
                sshSettings.dismissHostsCorruptionNotice()
            }
        if (hasBackup) {
            dialog.setPositiveButton(R.string.action_restore) { _, _ ->
                sshSettings.restoreHostsFromBackup()
                refreshHosts()
            }
        }
        dialog.show()
    }
}