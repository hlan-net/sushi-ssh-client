package net.hlan.sushi

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.hlan.sushi.databinding.ItemPlayBinding

class PlayAdapter(
    private val onClick: (Play) -> Unit,
    private val onDeleteClick: (Play) -> Unit
) : ListAdapter<Play, PlayAdapter.PlayViewHolder>(PlayDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayViewHolder {
        val binding = ItemPlayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlayViewHolder(private val binding: ItemPlayBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(play: Play) {
            binding.playNameText.text = play.name
            binding.playDescriptionText.text = play.description
            binding.playScriptText.text = play.scriptTemplate
            binding.root.setOnClickListener { onClick(play) }
            binding.deletePlayButton.setOnClickListener { onDeleteClick(play) }
            binding.deletePlayButton.isEnabled = !play.managed
            binding.deletePlayButton.alpha = if (play.managed) 0.4f else 1f
        }
    }

    class PlayDiffCallback : DiffUtil.ItemCallback<Play>() {
        override fun areItemsTheSame(oldItem: Play, newItem: Play): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Play, newItem: Play): Boolean = oldItem == newItem
    }
}
