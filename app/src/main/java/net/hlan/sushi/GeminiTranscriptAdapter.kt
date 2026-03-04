package net.hlan.sushi

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.hlan.sushi.databinding.ItemGeminiTranscriptBinding

class GeminiTranscriptAdapter(
    private val entries: List<GeminiTranscriptEntry>
) : RecyclerView.Adapter<GeminiTranscriptAdapter.TranscriptViewHolder>() {

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranscriptViewHolder {
        val binding = ItemGeminiTranscriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TranscriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TranscriptViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    class TranscriptViewHolder(
        private val binding: ItemGeminiTranscriptBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: GeminiTranscriptEntry) {
            binding.transcriptPromptContent.text = entry.prompt
            binding.transcriptResponseContent.text = entry.response
        }
    }
}
