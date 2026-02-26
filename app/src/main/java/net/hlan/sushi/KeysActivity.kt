package net.hlan.sushi

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityKeysBinding
import java.io.ByteArrayOutputStream

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsch = JSch()
                // JSch 0.1.55 doesn't natively support ED25519 without extensions.
                // We'll use RSA 2048
                val kpair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)

                val prvKeyOut = ByteArrayOutputStream()
                kpair.writePrivateKey(prvKeyOut, null) // No passphrase
                val prvKeyStr = prvKeyOut.toString("UTF-8")

                val pubKeyOut = ByteArrayOutputStream()
                kpair.writePublicKey(pubKeyOut, "sushi-client")
                val pubKeyStr = pubKeyOut.toString("UTF-8").trim()

                kpair.dispose()

                sshSettings.setPrivateKey(prvKeyStr)
                sshSettings.setPublicKey(pubKeyStr)

                // Add or update the "Install SSH Key" phrase
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '$pubKeyStr' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
                val existingPhrase = db.getAllPhrases().find { it.name == "Install SSH Key" }
                if (existingPhrase != null) {
                    db.update(existingPhrase.copy(command = command))
                } else {
                    db.insert(Phrase(name = "Install SSH Key", command = command))
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@KeysActivity, R.string.key_generated_success, Toast.LENGTH_SHORT).show()
                    updateUi()
                    binding.generateKeyButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@KeysActivity, "Failed to generate key: ${e.message}", Toast.LENGTH_LONG).show()
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
}