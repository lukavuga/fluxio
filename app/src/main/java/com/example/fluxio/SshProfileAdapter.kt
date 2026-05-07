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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ssh_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]
        holder.txtLabel.text = profile.label
        holder.txtUser.text = profile.sshUsername
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
