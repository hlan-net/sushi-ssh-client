package net.hlan.sushi

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jcraft.jsch.JSch
import net.hlan.sushi.databinding.ActivityHostKeysBinding

class HostKeysActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostKeysBinding
    private lateinit var adapter: HostKeyAdapter
    private var hostFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeSettings(this).applyAccentOverlay(this)
        binding = ActivityHostKeysBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostFilter = intent.getStringExtra(EXTRA_HOST_FILTER)
        hostFilter?.let { filter ->
            binding.hostKeysSubtitle.text = getString(R.string.host_keys_subtitle_filtered, filter)
        }

        adapter = HostKeyAdapter(onDeleteClick = { entry -> deleteHostKey(entry) })
        binding.hostKeysRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.hostKeysRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshHostKeys()
    }

    private fun refreshHostKeys() {
        val jsch = JSch()
        val repo = SshKnownHosts.attach(jsch, SshKnownHosts.file(this))
        val allEntries = repo.hostKey.orEmpty().map { key ->
            HostKeyEntry(host = key.host, type = key.type, fingerprint = key.getFingerPrint(jsch))
        }
        // Entries are keyed by the alias configureSession() sets, i.e. "host:port", while the
        // HOST_KEY_MISMATCH banner deep-links with the bare host. Match on the host part so
        // either form finds the entry.
        val entries = hostFilter?.let { filter ->
            val wanted = filter.substringBeforeLast(':')
            allEntries.filter { it.host.substringBeforeLast(':') == wanted }
        } ?: allEntries

        adapter.submitList(entries)

        if (entries.isEmpty()) {
            binding.emptyHostKeysText.visibility = View.VISIBLE
            binding.hostKeysRecyclerView.visibility = View.GONE
        } else {
            binding.emptyHostKeysText.visibility = View.GONE
            binding.hostKeysRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun deleteHostKey(entry: HostKeyEntry) {
        val jsch = JSch()
        val repo = SshKnownHosts.attach(jsch, SshKnownHosts.file(this))
        repo.remove(entry.host, entry.type)
        Toast.makeText(this, R.string.host_key_deleted, Toast.LENGTH_SHORT).show()
        refreshHostKeys()
    }

    companion object {
        const val EXTRA_HOST_FILTER = "extra_host_filter"
    }
}
