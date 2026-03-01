package net.hlan.sushi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class HostAdapter(
    private val onHostClick: (SshConnectionConfig) -> Unit,
    private val onEditClick: (SshConnectionConfig) -> Unit
) : ListAdapter<SshConnectionConfig, HostAdapter.HostViewHolder>(HostDiffCallback()) {

    var activeHostId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false)
        return HostViewHolder(view, onHostClick, onEditClick)
    }

    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        holder.bind(getItem(position), activeHostId)
    }

    class HostViewHolder(
        itemView: View,
        private val onHostClick: (SshConnectionConfig) -> Unit,
        private val onEditClick: (SshConnectionConfig) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val aliasText: TextView = itemView.findViewById(R.id.hostAliasText)
        private val targetText: TextView = itemView.findViewById(R.id.hostTargetText)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)

        fun bind(host: SshConnectionConfig, activeHostId: String?) {
            aliasText.text = host.alias.ifBlank { host.host }
            targetText.text = if (host.hasJumpServer()) {
                itemView.context.getString(
                    R.string.host_target_with_jump,
                    host.username,
                    host.host,
                    host.port
                )
            } else {
                "${host.username}@${host.host}:${host.port}"
            }

            if (host.id == activeHostId) {
                aliasText.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.presence_online, 0, 0, 0)
            } else {
                aliasText.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            itemView.setOnClickListener { onHostClick(host) }
            editButton.setOnClickListener { onEditClick(host) }
        }
    }

    class HostDiffCallback : DiffUtil.ItemCallback<SshConnectionConfig>() {
        override fun areItemsTheSame(oldItem: SshConnectionConfig, newItem: SshConnectionConfig): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SshConnectionConfig, newItem: SshConnectionConfig): Boolean {
            return oldItem == newItem
        }
    }
}
