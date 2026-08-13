package net.hlan.sushi

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hlan.sushi.databinding.DialogPhrasePickerBinding

object PhrasePickerHelper {

    fun showPicker(
        activity: AppCompatActivity,
        phraseDb: PhraseDatabaseHelper,
        onSelected: (Phrase) -> Unit
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val phrases = phraseDb.getAllPhrases()
            withContext(Dispatchers.Main) {
                if (phrases.isEmpty()) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.phrases_empty_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }

                val dialogBinding = DialogPhrasePickerBinding.inflate(LayoutInflater.from(activity))
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.action_phrases_short))
                    .setView(dialogBinding.root)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()

                val adapter = PhraseAdapter(
                    onClick = { phrase ->
                        dialog.dismiss()
                        onSelected(phrase)
                    },
                    onDeleteClick = {},
                    showDelete = false,
                    compactCommand = true
                )
                dialogBinding.phrasePickerRecyclerView.layoutManager = LinearLayoutManager(activity)
                dialogBinding.phrasePickerRecyclerView.adapter = adapter
                adapter.submitList(phrases)

                dialog.show()
            }
        }
    }
}
