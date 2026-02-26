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
        
        if (phrase != null) {
            dialogBinding.phraseNameInput.setText(phrase.name)
            dialogBinding.phraseCommandInput.setText(phrase.command)
        }

        AlertDialog.Builder(this)
            .setTitle(if (phrase == null) R.string.action_add_phrase else R.string.action_phrases)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.phrase_save) { _, _ ->
                val name = dialogBinding.phraseNameInput.text.toString().trim()
                val command = dialogBinding.phraseCommandInput.text.toString().trim()
                
                if (name.isNotBlank() && command.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val newPhrase = phrase?.copy(name = name, command = command) 
                            ?: Phrase(name = name, command = command)
                        if (phrase == null) {
                            db.insert(newPhrase)
                        } else {
                            db.update(newPhrase)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.phrase_cancel, null)
            .show()
    }

    private fun deletePhrase(phrase: Phrase) {
        AlertDialog.Builder(this)
            .setTitle(R.string.phrase_delete_confirm)
            .setPositiveButton("Delete") { _, _ ->
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
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Exported Phrases", json))
                Toast.makeText(this@PhrasesActivity, "Phrases exported to clipboard", Toast.LENGTH_SHORT).show()
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
                            phrases.forEach { phrase ->
                                // Reset ID to 0 to auto-generate new ones, avoiding conflicts
                                db.insert(phrase.copy(id = 0))
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@PhrasesActivity, "Imported ${phrases.size} phrases", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PhrasesActivity, "Failed to parse JSON from clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }
}