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

    fun getDeviceByIp(ip: String): SupabaseDevice? {
        return devices.find { it.ipAddress == ip }
    }

    /**
     * Requirement: Streaming UI updates.
     */
    fun addOrUpdateDevice(device: SupabaseDevice) {
        val index = devices.indexOfFirst { it.ipAddress == device.ipAddress }
        if (index != -1) {
            devices[index] = device
            notifyItemChanged(index)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
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
            val typeName = (device.deviceType ?: device.type)?.uppercase() ?: "PC"
            val statusLabel = device.status ?: "Offline"

            deviceName.text = device.name
            deviceIp.text = device.ipAddress
            
            val formattedMac = device.macAddress?.uppercase() ?: "Unknown"
            deviceMac.text = "MAC: $formattedMac"
            
            deviceStatus.text = statusLabel

            if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                deviceStatus.setTextColor("#00E676".toColorInt())
                deviceStatus.text = "Online"
            } else {
                deviceStatus.setTextColor("#FF1744".toColorInt())
                deviceStatus.text = "Offline"
            }

            val iconRes = when (typeName) {
                "PRINTER" -> R.drawable.printer
                "ROUTER" -> R.drawable.router
                else -> R.drawable.computer // PC default
            }
            deviceIcon.setImageResource(iconRes)

            if (typeName == "PC") {
                btnPowerControl.visibility = View.VISIBLE
                if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                    btnPowerControl.text = "TURN OFF"
                } else {
                    btnPowerControl.text = "TURN ON"
                }
                btnPowerControl.setOnClickListener { onPowerControlClick(device) }
            } else {
                btnPowerControl.visibility = View.INVISIBLE
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
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.ipAddress == new.ipAddress
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old == new
        }
    }
}
