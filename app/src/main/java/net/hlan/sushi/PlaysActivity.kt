package net.hlan.sushi

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.ActivityPlaysBinding
import net.hlan.sushi.databinding.DialogEditPlayBinding

class PlaysActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaysBinding
    private lateinit var adapter: PlayAdapter
    private val db by lazy { PlayDatabaseHelper.getInstance(this) }
    private val sshSettings by lazy { SshSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaysBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ManagedPlays.ensure(this, sshSettings.getPublicKey())

        adapter = PlayAdapter(
            onClick = { play -> showEditPlayDialog(play) },
            onDeleteClick = { play -> deletePlay(play) }
        )

        binding.playsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.playsRecyclerView.adapter = adapter

        binding.addPlayButton.setOnClickListener {
            showEditPlayDialog(null)
        }

        lifecycleScope.launch {
            db.playsFlow.collect { plays ->
                adapter.submitList(plays)
            }
        }
    }

    private fun showEditPlayDialog(play: Play?) {
        if (isManagedPlay(play)) {
            showManagedPlayToast()
            return
        }

        val dialogBinding = DialogEditPlayBinding.inflate(LayoutInflater.from(this))
        populatePlayDialog(dialogBinding, play)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (play == null) R.string.action_add_play else R.string.action_edit_play)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.phrase_save, null)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val name = dialogBinding.playNameInput.text?.toString()?.trim().orEmpty()
                val description = dialogBinding.playDescriptionInput.text?.toString()?.trim().orEmpty()
                val script = dialogBinding.playScriptInput.text?.toString()?.trim().orEmpty()

                if (!validatePlayInputs(dialogBinding, name, script)) {
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    if (isDuplicatePlayName(name, play?.id)) {
                        showDuplicatePlayNameError(dialogBinding)
                        return@launch
                    }

                    savePlay(play, name, description, script)
                    dismissDialog(dialog)
                }
            }
        }

        dialog.show()
    }

    private fun isManagedPlay(play: Play?): Boolean = play?.managed == true

    private fun showManagedPlayToast() {
        Toast.makeText(this, getString(R.string.play_managed_read_only), Toast.LENGTH_SHORT).show()
    }

    private fun populatePlayDialog(dialogBinding: DialogEditPlayBinding, play: Play?) {
        if (play == null) {
            return
        }
        dialogBinding.playNameInput.setText(play.name)
        dialogBinding.playDescriptionInput.setText(play.description)
        dialogBinding.playScriptInput.setText(play.scriptTemplate)
    }

    private fun validatePlayInputs(
        dialogBinding: DialogEditPlayBinding,
        name: String,
        script: String
    ): Boolean {
        dialogBinding.playNameLayout.error = null
        dialogBinding.playScriptLayout.error = null

        var hasError = false
        if (name.isBlank()) {
            dialogBinding.playNameLayout.error = getString(R.string.play_name_required)
            hasError = true
        }
        if (script.isBlank()) {
            dialogBinding.playScriptLayout.error = getString(R.string.play_script_required)
            hasError = true
        }
        return !hasError
    }

    private fun isDuplicatePlayName(name: String, playId: Long?): Boolean {
        val existing = db.getPlayByName(name)
        return existing != null && existing.id != playId
    }

    private suspend fun showDuplicatePlayNameError(dialogBinding: DialogEditPlayBinding) {
        withContext(Dispatchers.Main) {
            dialogBinding.playNameLayout.error = getString(R.string.play_name_exists)
        }
    }

    private fun savePlay(play: Play?, name: String, description: String, script: String) {
        if (play == null) {
            db.insert(
                Play(
                    name = name,
                    description = description,
                    scriptTemplate = script,
                    parametersJson = "[]",
                    managed = false
                )
            )
            return
        }
        db.update(
            play.copy(
                name = name,
                description = description,
                scriptTemplate = script
            )
        )
    }

    private suspend fun dismissDialog(dialog: AlertDialog) {
        withContext(Dispatchers.Main) {
            dialog.dismiss()
        }
    }

    private fun deletePlay(play: Play) {
        if (play.managed) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.phrase_delete_confirm_title, play.name))
            .setMessage(R.string.phrase_delete_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.delete(play)
                }
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }
}
