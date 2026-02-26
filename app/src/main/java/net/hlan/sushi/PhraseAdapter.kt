package net.hlan.sushi

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.hlan.sushi.databinding.ItemPhraseBinding

class PhraseAdapter(
    private val onClick: (Phrase) -> Unit,
    private val onDeleteClick: (Phrase) -> Unit
) : ListAdapter<Phrase, PhraseAdapter.PhraseViewHolder>(PhraseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhraseViewHolder {
        val binding = ItemPhraseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhraseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhraseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PhraseViewHolder(private val binding: ItemPhraseBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(phrase: Phrase) {
            binding.phraseNameText.text = phrase.name
            binding.phraseCommandText.text = phrase.command
            binding.root.setOnClickListener { onClick(phrase) }
            binding.deletePhraseButton.setOnClickListener { onDeleteClick(phrase) }
        }
    }

    class PhraseDiffCallback : DiffUtil.ItemCallback<Phrase>() {
        override fun areItemsTheSame(oldItem: Phrase, newItem: Phrase) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Phrase, newItem: Phrase) = oldItem == newItem
    }
}