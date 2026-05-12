package com.example.fluxio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SshProfileAdapter(
    private var profiles: MutableList<SshCredential>,
    private val onEdit: (SshCredential) -> Unit,
    private val onDelete: (SshCredential) -> Unit
) : RecyclerView.Adapter<SshProfileAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtLabel: TextView = view.findViewById(R.id.txtProfileLabel)
        val txtUser: TextView = view.findViewById(R.id.txtProfileUsername)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditProfile)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteProfile)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ssh_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]
        holder.txtLabel.text = profile.label
        holder.txtUser.text = profile.sshUsername
        
        // UI Standard: If isEnabled is false, set card alpha to 0.5f
        holder.root.alpha = if (profile.isEnabled) 1.0f else 0.5f
        
        // UI Protection: For "Fluxio Default", hide the Trash icon and use an "Action" icon for toggle
        if (profile.label == "Fluxio Default") {
            holder.btnDelete.setImageResource(android.R.drawable.ic_menu_view) // Action/Toggle icon
        } else {
            holder.btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
        }

        holder.btnEdit.setOnClickListener { onEdit(profile) }
        holder.btnDelete.setOnClickListener { onDelete(profile) }
    }

    override fun getItemCount() = profiles.size

    fun updateList(newList: List<SshCredential>) {
        profiles.clear()
        profiles.addAll(newList)
        notifyDataSetChanged()
    }
}
