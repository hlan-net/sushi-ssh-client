package net.hlan.sushi

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                val labels = phrases.map { "${it.name}\n${it.command}" }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.action_phrases_short))
                    .setItems(labels) { _, which -> onSelected(phrases[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
