package com.example.fluxio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class SavedNetworkAdapter(
    private var networks: List<SupabaseNetwork>,
    private val onClick: (SupabaseNetwork) -> Unit,
    private val onLongClick: (SupabaseNetwork) -> Unit
) : RecyclerView.Adapter<SavedNetworkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.txtNetworkName)
        val detailsTextView: TextView = view.findViewById(R.id.txtNetworkDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val network = networks[position]
        holder.nameTextView.text = network.name
        
        // Supabase timestamp is usually ISO8601 string
        val dateString = network.timestamp?.take(16)?.replace("T", " ") ?: "Unknown date"
        holder.detailsTextView.text = "$dateString | ${network.deviceCount} devices"

        holder.itemView.setOnClickListener { onClick(network) }
        holder.itemView.setOnLongClickListener {
            onLongClick(network)
            true
        }
    }

    fun updateList(newList: List<SupabaseNetwork>) {
        networks = newList
        notifyDataSetChanged()
    }

    override fun getItemCount() = networks.size
}
