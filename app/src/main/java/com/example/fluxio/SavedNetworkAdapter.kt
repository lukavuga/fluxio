package com.example.fluxio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SavedNetworkAdapter(
    private var networks: List<SupabaseNetwork>,
    private val onClick: (SupabaseNetwork) -> Unit,
    private val onEdit: (SupabaseNetwork) -> Unit,
    private val onDelete: (SupabaseNetwork) -> Unit
) : RecyclerView.Adapter<SavedNetworkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.txtNetworkName)
        val detailsTextView: TextView = view.findViewById(R.id.txtNetworkDetails)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditNetwork)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteNetwork)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val network = networks[position]
        holder.nameTextView.text = network.name
        
        val dateString = network.timestamp?.take(10) ?: "Unknown date"
        holder.detailsTextView.text = "$dateString | ${network.deviceCount} devices"

        holder.itemView.setOnClickListener { onClick(network) }
        holder.btnEdit.setOnClickListener { onEdit(network) }
        holder.btnDelete.setOnClickListener { onDelete(network) }
    }

    fun updateList(newList: List<SupabaseNetwork>) {
        networks = newList
        notifyDataSetChanged()
    }

    override fun getItemCount() = networks.size
}
