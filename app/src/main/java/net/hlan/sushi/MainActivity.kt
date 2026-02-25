package net.hlan.sushi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import net.hlan.sushi.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val geminiSettings by lazy { GeminiSettings(this) }
    private val geminiClient by lazy { GeminiClient(this, geminiSettings) }
    private val driveLogSettings by lazy { DriveLogSettings(this) }
    private val driveAuthManager by lazy { DriveAuthManager(this) }
    private val driveLogUploader by lazy { DriveLogUploader(this) }
    private val consoleLogRepository by lazy { ConsoleLogRepository(this) }

    private val voiceResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val voiceText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()

        if (voiceText.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.gemini_output_error), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        binding.geminiPromptText.text = voiceText
        requestGeminiCommand(voiceText)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            Toast.makeText(this, getString(R.string.gemini_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startSessionButton.setOnClickListener {
            binding.statusText.text = getString(R.string.placeholder_status_active)
            appendSessionLog()
            attemptDriveLogUpload()
        }

        binding.geminiVoiceButton.setOnClickListener {
            handleGeminiVoice()
        }

        binding.geminiSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateGeminiState()
    }

    override fun onResume() {
        super.onResume()
        updateGeminiState()
    }

    private fun updateGeminiState() {
        binding.geminiStatusText.text = when {
            !geminiSettings.isEnabled() -> getString(R.string.gemini_status_disabled)
            geminiSettings.getApiKey().isBlank() -> getString(R.string.gemini_status_missing_key)
            else -> getString(R.string.gemini_status_ready)
        }
    }

    private fun handleGeminiVoice() {
        if (!geminiSettings.isEnabled() || geminiSettings.getApiKey().isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.gemini_voice_prompt))
        }
        voiceResultLauncher.launch(intent)
    }

    private fun requestGeminiCommand(voiceText: String) {
        binding.geminiProgressBar.visibility = View.VISIBLE
        binding.geminiOutputText.text = getString(R.string.gemini_output_waiting)

        Thread {
            val result = geminiClient.generateCommand(voiceText)
            runOnUiThread {
                binding.geminiProgressBar.visibility = View.GONE
                binding.geminiOutputText.text = result.message
            }
        }.start()
    }

    private fun appendSessionLog() {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = formatter.format(Date())
        consoleLogRepository.appendLine("Session started at $timestamp")
    }

    private fun attemptDriveLogUpload() {
        if (!driveLogSettings.isAlwaysSaveEnabled()) {
            return
        }

        val account = driveAuthManager.getSignedInAccount()
        if (account == null) {
            Toast.makeText(this, getString(R.string.drive_upload_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        val log = consoleLogRepository.getLog()
        if (log.isBlank()) {
            return
        }

        driveLogUploader.uploadLog(account, log) { result ->
            runOnUiThread {
                val message = if (result.success) {
                    getString(R.string.drive_upload_success)
                } else {
                    getString(R.string.drive_upload_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
