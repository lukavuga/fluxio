package com.example.fluxio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter that displays devices using the relational DeviceView (Device + Type + Status).
 */
class DeviceAdapter(
    private var deviceViews: MutableList<DeviceView>,
    private val onItemClick: (SavedDevice) -> Unit,
    private val onPowerControlClick: (SavedDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device_card, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val viewItem = deviceViews[position]
        holder.bind(viewItem, onItemClick, onPowerControlClick)
    }

    override fun getItemCount(): Int = deviceViews.size

    fun updateList(newDeviceViews: List<DeviceView>) {
        val diffCallback = DeviceDiffCallback(deviceViews, newDeviceViews)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        deviceViews.clear()
        deviceViews.addAll(newDeviceViews)
        diffResult.dispatchUpdatesTo(this)
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceIcon: ImageView = itemView.findViewById(R.id.imgDeviceIcon)
        private val deviceName: TextView = itemView.findViewById(R.id.txtDeviceName)
        private val deviceIp: TextView = itemView.findViewById(R.id.txtDeviceIp)
        private val deviceMac: TextView = itemView.findViewById(R.id.txtDeviceMac)
        private val deviceStatus: TextView = itemView.findViewById(R.id.txtDeviceStatus)
        private val btnPowerControl: Button = itemView.findViewById(R.id.btnPowerControl)

        fun bind(
            viewItem: DeviceView,
            onItemClick: (SavedDevice) -> Unit,
            onPowerControlClick: (SavedDevice) -> Unit
        ) {
            val device = viewItem.device
            val typeName = viewItem.typeEntity?.typeName?.uppercase() ?: "OTHER"
            val statusLabel = viewItem.statusEntity?.statusLabel ?: "Inactive"

            deviceName.text = device.name
            deviceIp.text = device.ip
            
            // Actually display the MAC address from the database
            deviceMac.text = if (!device.macAddress.isNullOrBlank()) "MAC: ${device.macAddress}" else "MAC: Unknown"
            deviceStatus.text = statusLabel

            // 1. Set status color
            if (statusLabel == "Active" || statusLabel == "Online") {
                deviceStatus.setTextColor("#00E676".toColorInt()) // Neon Green
            } else {
                deviceStatus.setTextColor("#FF1744".toColorInt()) // Neon Red
            }

            // 2. Select correct icon based on type name string
            val iconRes = when (typeName) {
                "TV" -> R.drawable.tv
                "SMARTPHONE", "PHONE" -> R.drawable.phone
                "PRINTER" -> R.drawable.printer
                "PC", "LAPTOP" -> R.drawable.computer
                "ROUTER" -> R.drawable.router
                else -> R.drawable.fluxio
            }
            deviceIcon.setImageResource(iconRes)
            deviceIcon.clearColorFilter()

            // 3. Power Control Button logic for PCs
            if (typeName == "PC" || typeName == "LAPTOP") {
                btnPowerControl.visibility = View.VISIBLE
                if (statusLabel == "Active" || statusLabel == "Online") {
                    btnPowerControl.text = itemView.context.getString(R.string.shutdown)
                } else {
                    btnPowerControl.text = itemView.context.getString(R.string.power_on)
                }
                btnPowerControl.setOnClickListener { onPowerControlClick(device) }
            } else {
                btnPowerControl.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(device) }
        }
    }

    class DeviceDiffCallback(
        private val oldList: List<DeviceView>,
        private val newList: List<DeviceView>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].device.id == newList[newItemPosition].device.id
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.device.ip == new.device.ip && 
                   old.device.macAddress == new.device.macAddress &&
                   old.statusEntity?.statusLabel == new.statusEntity?.statusLabel &&
                   old.typeEntity?.typeName == new.typeEntity?.typeName
        }
    }
}
