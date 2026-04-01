package com.example.fluxio

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private var devices: MutableList<SavedDevice>,
    private val onItemClick: (SavedDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device_card, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onItemClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    fun updateList(newDevices: List<SavedDevice>) {
        val listCopy = ArrayList(newDevices)
        devices.clear()
        devices.addAll(listCopy)
        notifyDataSetChanged()
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceIcon: ImageView = itemView.findViewById(R.id.imgDeviceIcon)
        private val deviceName: TextView = itemView.findViewById(R.id.txtDeviceName)
        private val deviceIp: TextView = itemView.findViewById(R.id.txtDeviceIp)
        private val deviceStatus: TextView = itemView.findViewById(R.id.txtDeviceStatus)

        fun bind(device: SavedDevice) {
            deviceName.text = device.name
            deviceIp.text = device.ip
            deviceStatus.text = device.status

            // 1. Nastavi barvo statusa (Active = Zelena, Inactive = Rdeča)
            if (device.status == "Active") {
                deviceStatus.setTextColor(Color.parseColor("#00E676")) // Neon zelena
            } else {
                deviceStatus.setTextColor(Color.parseColor("#FF1744")) // Neon rdeča
            }

            // 2. Izberi pravo ikono
            val iconRes = when(device.type) {
                "TV" -> R.drawable.tv
                "PHONE" -> R.drawable.phone
                "LAPTOP" -> R.drawable.laptop
                "ROUTER" -> R.drawable.router
                "PRINTER" -> R.drawable.printer
                else -> R.drawable.computer
            }
            deviceIcon.setImageResource(iconRes)

            // 3. POMEMBNO: Odstranili smo kodo "setColorFilter", ki je ikono prebarvala v modro kocko.
            // Zdaj se bo prikazala originalna slika.
            deviceIcon.clearColorFilter()
        }
    }
}