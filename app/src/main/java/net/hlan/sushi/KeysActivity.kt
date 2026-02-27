package net.hlan.sushi

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityKeysBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeysActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKeysBinding
    private val sshSettings by lazy { SshSettings(this) }
    private val db by lazy { PhraseDatabaseHelper.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeysBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.generateKeyButton.setOnClickListener {
            generateKeyPair()
        }

        binding.deleteKeyButton.setOnClickListener {
            deleteKeyPair()
        }

        updateUi()
    }

    private fun updateUi() {
        val pubKey = sshSettings.getPublicKey()
        val hasKey = !pubKey.isNullOrBlank()

        if (hasKey) {
            binding.keyStatusText.text = getString(R.string.key_status_configured)
            binding.publicKeyLayout.visibility = View.VISIBLE
            binding.publicKeyInput.setText(pubKey)
            binding.generateKeyButton.text = getString(R.string.action_regenerate_key)
            binding.deleteKeyButton.visibility = View.VISIBLE
        } else {
            binding.keyStatusText.text = getString(R.string.key_status_none)
            binding.publicKeyLayout.visibility = View.GONE
            binding.publicKeyInput.setText("")
            binding.generateKeyButton.text = getString(R.string.action_generate_key)
            binding.deleteKeyButton.visibility = View.GONE
        }
    }

    private fun generateKeyPair() {
        binding.generateKeyButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsch = JSch()
                // JSch 0.1.55 doesn't natively support ED25519 without extensions.
                // We'll use RSA 2048
                val kpair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)

                val prvKeyOut = ByteArrayOutputStream()
                // Prompt user for a passphrase and use it here
                val passphrase: ByteArray? = null // Replace with user-supplied passphrase
                kpair.writePrivateKey(prvKeyOut, passphrase)
                val prvKeyStr = prvKeyOut.toString("UTF-8")

                val pubKeyOut = ByteArrayOutputStream()
                val keyCreatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val keyComment = "Sushi - SSH client key $keyCreatedAt"
                kpair.writePublicKey(pubKeyOut, keyComment)
                val pubKeyStr = pubKeyOut.toString("UTF-8").trim()

                kpair.dispose()

                sshSettings.setPrivateKey(prvKeyStr)
                sshSettings.setPublicKey(pubKeyStr)

                val installCommand = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '$pubKeyStr' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
                val removeCommand = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.sushi.bak && grep -v 'Sushi - SSH client key' ~/.ssh/authorized_keys.sushi.bak > ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

                db.upsertByName(PHRASE_INSTALL_KEY, installCommand)
                db.upsertByName(PHRASE_REMOVE_SUSHI_KEYS, removeCommand)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@KeysActivity, R.string.key_generated_success, Toast.LENGTH_SHORT).show()
                    updateUi()
                    binding.generateKeyButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@KeysActivity, getString(R.string.key_generation_failed, e.message), Toast.LENGTH_LONG).show()
                    binding.generateKeyButton.isEnabled = true
                }
            }
        }
    }

    private fun deleteKeyPair() {
        sshSettings.setPrivateKey(null)
        sshSettings.setPublicKey(null)
        updateUi()
        Toast.makeText(this, R.string.key_deleted, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PHRASE_INSTALL_KEY = "Install SSH Key"
        private const val PHRASE_REMOVE_SUSHI_KEYS = "Remove Sushi SSH Keys"
    }
}
