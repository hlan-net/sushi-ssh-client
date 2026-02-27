package net.hlan.sushi

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import net.hlan.sushi.databinding.ActivityHostsBinding

class HostsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostsBinding
    private val sshSettings by lazy { SshSettings(this) }
    private lateinit var adapter: HostAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HostAdapter(
            onHostClick = { host ->
                sshSettings.setActiveHostId(host.id)
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
}