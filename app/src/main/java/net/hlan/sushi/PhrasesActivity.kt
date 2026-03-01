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
import net.hlan.sushi.databinding.ActivityPhrasesBinding
import net.hlan.sushi.databinding.DialogEditPhraseBinding
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class PhrasesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhrasesBinding
    private lateinit var adapter: PhraseAdapter
    private val db by lazy { PhraseDatabaseHelper.getInstance(this) }
    private val moshi by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhrasesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PhraseAdapter(
            onClick = { phrase -> showEditPhraseDialog(phrase) },
            onDeleteClick = { phrase -> deletePhrase(phrase) }
        )

        binding.phrasesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.phrasesRecyclerView.adapter = adapter

        binding.addPhraseButton.setOnClickListener {
            showEditPhraseDialog(null)
        }

        binding.exportButton.setOnClickListener {
            exportPhrases()
        }

        binding.importButton.setOnClickListener {
            importPhrases()
        }

        lifecycleScope.launch {
            db.phrasesFlow.collect { phrases ->
                adapter.submitList(phrases)
            }
        }
    }

    private fun showEditPhraseDialog(phrase: Phrase?) {
        val dialogBinding = DialogEditPhraseBinding.inflate(LayoutInflater.from(this))

        populatePhraseDialog(dialogBinding, phrase)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (phrase == null) R.string.action_add_phrase else R.string.action_edit_phrase)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.phrase_save, null)
            .setNegativeButton(R.string.phrase_cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val name = dialogBinding.phraseNameInput.text?.toString()?.trim().orEmpty()
                val command = dialogBinding.phraseCommandInput.text?.toString()?.trim().orEmpty()

                if (!validatePhraseInputs(dialogBinding, name, command)) {
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    if (isDuplicateName(name, phrase?.id)) {
                        showDuplicateNameError(dialogBinding)
                        return@launch
                    }

                    savePhrase(phrase, name, command)
                    dismissDialog(dialog)
                }
            }
        }

        dialog.show()
    }

    private fun populatePhraseDialog(dialogBinding: DialogEditPhraseBinding, phrase: Phrase?) {
        if (phrase == null) {
            return
        }
        dialogBinding.phraseNameInput.setText(phrase.name)
        dialogBinding.phraseCommandInput.setText(phrase.command)
    }

    private fun validatePhraseInputs(
        dialogBinding: DialogEditPhraseBinding,
        name: String,
        command: String
    ): Boolean {
        dialogBinding.phraseNameLayout.error = null
        dialogBinding.phraseCommandLayout.error = null

        var hasError = false
        if (name.isBlank()) {
            dialogBinding.phraseNameLayout.error = getString(R.string.phrase_name_required)
            hasError = true
        }
        if (command.isBlank()) {
            dialogBinding.phraseCommandLayout.error = getString(R.string.phrase_command_required)
            hasError = true
        }
        return !hasError
    }

    private fun isDuplicateName(name: String, phraseId: Long?): Boolean {
        val existing = db.getPhraseByName(name)
        return existing != null && existing.id != phraseId
    }

    private suspend fun showDuplicateNameError(dialogBinding: DialogEditPhraseBinding) {
        withContext(Dispatchers.Main) {
            dialogBinding.phraseNameLayout.error = getString(R.string.phrase_name_exists)
        }
    }

    private fun savePhrase(phrase: Phrase?, name: String, command: String) {
        if (phrase == null) {
            db.insert(Phrase(name = name, command = command))
        } else {
            db.update(phrase.copy(name = name, command = command))
        }
    }

    private suspend fun dismissDialog(dialog: AlertDialog) {
        withContext(Dispatchers.Main) {
            dialog.dismiss()
        }
    }

    private fun deletePhrase(phrase: Phrase) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.phrase_delete_confirm_title, phrase.name))
            .setMessage(R.string.phrase_delete_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.delete(phrase)
                }
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    private fun exportPhrases() {
        lifecycleScope.launch(Dispatchers.IO) {
            val phrases = db.getAllPhrases()
            val listType = Types.newParameterizedType(List::class.java, Phrase::class.java)
            val adapter = moshi.adapter<List<Phrase>>(listType)
            val json = adapter.toJson(phrases)
            
            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.export_clipboard_label), json))
                Toast.makeText(this@PhrasesActivity, R.string.export_success_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importPhrases() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val json = clip.getItemAt(0).text?.toString()
            if (!json.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val listType = Types.newParameterizedType(List::class.java, Phrase::class.java)
                        val adapter = moshi.adapter<List<Phrase>>(listType)
                        val phrases = adapter.fromJson(json)
                        if (phrases != null) {
                            var inserted = 0
                            var updated = 0
                            phrases.forEach { phrase ->
                                val result = db.upsertByName(phrase.name, phrase.command)
                                inserted += result.inserted
                                updated += result.updated
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@PhrasesActivity,
                                    getString(R.string.import_success_toast_with_updates, inserted, updated),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@PhrasesActivity, R.string.import_parse_error_toast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PhrasesActivity, R.string.import_parse_error_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, R.string.import_empty_clipboard_toast, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.import_empty_clipboard_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
