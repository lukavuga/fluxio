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

class DeviceAdapter(
    private var devices: MutableList<SavedDevice>,
    private val onItemClick: (SavedDevice) -> Unit,
    private val onPowerControlClick: (SavedDevice) -> Unit
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

    fun updateList(newDevices: List<SavedDevice>) {
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
        private val deviceStatus: TextView = itemView.findViewById(R.id.txtDeviceStatus)
        private val btnPowerControl: Button = itemView.findViewById(R.id.btnPowerControl)

        fun bind(
            device: SavedDevice,
            onItemClick: (SavedDevice) -> Unit,
            onPowerControlClick: (SavedDevice) -> Unit
        ) {
            val context = itemView.context
            deviceName.text = device.name
            deviceIp.text = device.ip
            deviceStatus.text = device.status

            // 1. Set status color using KTX toColorInt()
            if (device.status == "Active" || device.status == "Online") {
                deviceStatus.setTextColor("#00E676".toColorInt()) // Neon Green
            } else {
                deviceStatus.setTextColor("#FF1744".toColorInt()) // Neon Red
            }

            // 2. Select correct icon
            val iconRes = when(device.type) {
                "TV" -> R.drawable.tv
                "PHONE", "MOBILE" -> R.drawable.phone
                "ROUTER" -> R.drawable.router
                "PRINTER" -> R.drawable.printer
                else -> R.drawable.computer
            }
            deviceIcon.setImageResource(iconRes)
            deviceIcon.clearColorFilter()

            // 3. Power Control Button logic for PCs using string resources
            if (device.type == "PC") {
                btnPowerControl.visibility = View.VISIBLE
                if (device.status == "Active" || device.status == "Online") {
                    btnPowerControl.text = context.getString(R.string.shutdown)
                } else {
                    btnPowerControl.text = context.getString(R.string.power_on)
                }
                btnPowerControl.setOnClickListener { onPowerControlClick(device) }
            } else {
                btnPowerControl.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(device) }
        }
    }

    class DeviceDiffCallback(
        private val oldList: List<SavedDevice>,
        private val newList: List<SavedDevice>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.ip == new.ip && old.name == new.name && old.status == new.status && old.macAddress == new.macAddress
        }
    }
}