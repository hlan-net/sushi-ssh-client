package com.sushi.sshclient

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sushi.sshclient.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { GeminiSettings(this) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }

    private val driveSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, getString(R.string.drive_sign_in_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val account = driveAuthManager.handleSignInResult(result.data)
        if (account == null) {
            Toast.makeText(this, getString(R.string.drive_sign_in_failed), Toast.LENGTH_SHORT).show()
        }
        refreshDriveState()
    }

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

        binding.driveAlwaysSaveSwitch.isChecked = driveLogSettings.isAlwaysSaveEnabled()

        binding.driveSignInButton.setOnClickListener {
            driveSignInLauncher.launch(driveAuthManager.getSignInIntent())
        }

        binding.driveSignOutButton.setOnClickListener {
            driveAuthManager.signOut {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.drive_sign_out_done), Toast.LENGTH_SHORT).show()
                    refreshDriveState()
                }
            }
        }

        binding.saveButton.setOnClickListener {
            settings.setEnabled(binding.geminiEnabledSwitch.isChecked)
            settings.setApiKey(binding.apiKeyInput.text?.toString()?.trim().orEmpty())
            driveLogSettings.setAlwaysSaveEnabled(binding.driveAlwaysSaveSwitch.isChecked)
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        refreshDriveState()
    }

    private fun refreshDriveState() {
        val account = driveAuthManager.getSignedInAccount()
        if (account == null) {
            binding.driveStatusText.text = getString(R.string.drive_status_signed_out)
            binding.driveSignOutButton.visibility = android.view.View.GONE
            binding.driveSignInButton.visibility = android.view.View.VISIBLE
            binding.driveAlwaysSaveSwitch.isEnabled = false
        } else {
            binding.driveStatusText.text = getString(
                R.string.drive_status_signed_in,
                account.email ?: account.displayName ?: ""
            )
            binding.driveSignOutButton.visibility = android.view.View.VISIBLE
            binding.driveSignInButton.visibility = android.view.View.GONE
            binding.driveAlwaysSaveSwitch.isEnabled = true
        }
    }
}
