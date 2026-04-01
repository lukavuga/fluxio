package com.example.fluxio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class SavedNetworkAdapter(
    private val networks: List<SavedNetwork>,
    private val onClick: (SavedNetwork) -> Unit,
    private val onLongClick: (SavedNetwork) -> Unit
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
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val dateString = sdf.format(Date(network.timestamp))
        holder.detailsTextView.text = "$dateString | ${network.deviceCount} naprav"

        holder.itemView.setOnClickListener { onClick(network) }
        holder.itemView.setOnLongClickListener {
            onLongClick(network)
            true
        }
    }

    override fun getItemCount() = networks.size
}
