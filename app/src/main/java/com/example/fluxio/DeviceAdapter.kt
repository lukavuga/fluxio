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
 * Adapter that displays devices using SupabaseDevice.
 */
class DeviceAdapter(
    private var devices: MutableList<SupabaseDevice>,
    private val onItemClick: (SupabaseDevice) -> Unit,
    private val onPowerControlClick: (SupabaseDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device_card, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device, onItemClick, onPowerControlClick)
    }

    override fun getItemCount(): Int = devices.size

    fun updateList(newDevices: List<SupabaseDevice>) {
        val diffCallback = DeviceDiffCallback(devices, newDevices)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        devices.clear()
        devices.addAll(newDevices)
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
            device: SupabaseDevice,
            onItemClick: (SupabaseDevice) -> Unit,
            onPowerControlClick: (SupabaseDevice) -> Unit
        ) {
            val typeName = device.type?.uppercase() ?: "OTHER"
            val statusLabel = device.status ?: "Offline"

            deviceName.text = device.name
            deviceIp.text = device.ipAddress
            
            deviceMac.text = if (!device.macAddress.isNullOrBlank()) "MAC: ${device.macAddress}" else "MAC: Unknown"
            deviceStatus.text = statusLabel

            if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                deviceStatus.setTextColor("#00E676".toColorInt()) // Neon Green
            } else {
                deviceStatus.setTextColor("#FF1744".toColorInt()) // Neon Red
            }

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

            if (typeName == "PC" || typeName == "LAPTOP") {
                btnPowerControl.visibility = View.VISIBLE
                deviceMac.visibility = View.VISIBLE
                if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                    btnPowerControl.text = itemView.context.getString(R.string.shutdown)
                } else {
                    btnPowerControl.text = itemView.context.getString(R.string.power_on)
                }
                btnPowerControl.setOnClickListener { onPowerControlClick(device) }
            } else {
                btnPowerControl.visibility = View.GONE
                deviceMac.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(device) }
        }
    }

    class DeviceDiffCallback(
        private val oldList: List<SupabaseDevice>,
        private val newList: List<SupabaseDevice>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].macAddress == newList[newItemPosition].macAddress
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.ipAddress == new.ipAddress && 
                   old.macAddress == new.macAddress &&
                   old.status == new.status &&
                   old.type == new.type &&
                   old.name == new.name
        }
    }
}
