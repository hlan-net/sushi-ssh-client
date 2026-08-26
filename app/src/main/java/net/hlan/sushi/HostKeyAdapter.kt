package net.hlan.sushi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class HostKeyEntry(
    val host: String,
    val type: String,
    val fingerprint: String
)

class HostKeyAdapter(
    private val onDeleteClick: (HostKeyEntry) -> Unit
) : ListAdapter<HostKeyEntry, HostKeyAdapter.HostKeyViewHolder>(HostKeyDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostKeyViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_host_key, parent, false)
        return HostKeyViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: HostKeyViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HostKeyViewHolder(
        itemView: View,
        private val onDeleteClick: (HostKeyEntry) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val hostText: TextView = itemView.findViewById(R.id.hostKeyHostText)
        private val typeText: TextView = itemView.findViewById(R.id.hostKeyTypeText)
        private val fingerprintText: TextView = itemView.findViewById(R.id.hostKeyFingerprintText)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteHostKeyButton)

        fun bind(entry: HostKeyEntry) {
            hostText.text = entry.host
            typeText.text = entry.type
            fingerprintText.text = entry.fingerprint
            deleteButton.setOnClickListener { onDeleteClick(entry) }
        }
    }

    class HostKeyDiffCallback : DiffUtil.ItemCallback<HostKeyEntry>() {
        override fun areItemsTheSame(oldItem: HostKeyEntry, newItem: HostKeyEntry): Boolean {
            return oldItem.host == newItem.host && oldItem.type == newItem.type
        }

        override fun areContentsTheSame(oldItem: HostKeyEntry, newItem: HostKeyEntry): Boolean {
            return oldItem == newItem
        }
    }
}
