package com.sushi.sshclient

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sushi.sshclient.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { GeminiSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val enabled = settings.isEnabled()
        binding.geminiEnabledSwitch.isChecked = enabled
        binding.apiKeyInput.setText(settings.getApiKey())
        binding.apiKeyLayout.isEnabled = enabled

        binding.geminiEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.apiKeyLayout.isEnabled = isChecked
        }

        binding.saveButton.setOnClickListener {
            settings.setEnabled(binding.geminiEnabledSwitch.isChecked)
            settings.setApiKey(binding.apiKeyInput.text?.toString()?.trim().orEmpty())
            Toast.makeText(this, getString(R.string.gemini_saved), Toast.LENGTH_SHORT).show()
        }
    }
}
