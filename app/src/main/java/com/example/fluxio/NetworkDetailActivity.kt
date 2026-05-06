package com.example.fluxio

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class NetworkDetailActivity : AppCompatActivity() {

    private lateinit var networkId: String
    private lateinit var networkName: String
    private lateinit var adapter: DeviceAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val supabaseRepository = SupabaseRepository()
    private val networkRepository = NetworkRepository()
    
    private var existingDevices = mutableListOf<SupabaseDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_detail)

        networkId = intent.getStringExtra("NETWORK_ID") ?: ""
        networkName = intent.getStringExtra("NETWORK_NAME") ?: "Network Details"

        if (networkId.isEmpty()) {
            Toast.makeText(this, "Critical Error: Network ID missing", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbarDetail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = networkName
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerDeviceList)
        swipeRefresh = findViewById(R.id.swipeRefreshDevices)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAddDevice)

        adapter = DeviceAdapter(mutableListOf(), { device ->
            showEditDeviceDialog(device)
        }, { device ->
            handlePowerControl(device)
        })

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)

        swipeRefresh.setOnRefreshListener {
            performStreamingRescan()
        }

        fabAdd.setOnClickListener {
            showAddDeviceDialog()
        }

        loadDevices()
        observeChanges()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(Menu.NONE, 101, Menu.NONE, "Delete Network")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 101) {
            showDeleteNetworkDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showDeleteNetworkDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Network")
            .setMessage("Are you sure you want to delete '$networkName' and all its saved devices?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        supabaseRepository.deleteNetwork(networkId)
                        Toast.makeText(this@NetworkDetailActivity, "Network deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (e: Exception) {
                        Log.e("Fluxio", "Delete network failed", e)
                        Toast.makeText(this@NetworkDetailActivity, "Deletion failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadDevices() {
        lifecycleScope.launch {
            try {
                val devices = supabaseRepository.getDevices(networkId)
                existingDevices = devices.toMutableList()
                withContext(Dispatchers.Main) {
                    adapter.updateList(devices)
                }
                checkStatusInBackground(devices)
            } catch (e: Exception) {
                Log.e("Fluxio", "Error loading devices: ${e.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkDetailActivity, "Error loading devices", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeChanges() {
        lifecycleScope.launch {
            try {
                supabaseRepository.observeDeviceChanges().collect { device ->
                    if (device.networkId == networkId) {
                        withContext(Dispatchers.Main) {
                            adapter.addOrUpdateDevice(device)
                            val index = existingDevices.indexOfFirst { it.ipAddress == device.ipAddress }
                            if (index != -1) existingDevices[index] = device else existingDevices.add(device)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Real-time observation error: ${e.stackTraceToString()}")
            }
        }
    }

    private fun checkStatusInBackground(devices: List<SupabaseDevice>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
            devices.forEach { device ->
                try {
                    val isAlive = networkRepository.isHostReachable(device.ipAddress, 500)
                    val newStatus = if (isAlive) "Online" else "Offline"
                    
                    if (device.status != newStatus) {
                        val updated = device.copy(
                            status = newStatus,
                            lastSeen = if (isAlive) isoFormat.format(Date()) else device.lastSeen
                        )
                        withContext(Dispatchers.Main) {
                            adapter.addOrUpdateDevice(updated)
                        }
                        supabaseRepository.upsertDevice(updated)
                    }
                } catch (e: Exception) {
                    Log.e("Fluxio", "Status check error for ${device.ipAddress}: ${e.stackTraceToString()}")
                }
            }
        }
    }

    private fun performStreamingRescan() {
        lifecycleScope.launch {
            try {
                val myIp = withContext(Dispatchers.IO) { getLocalIpAddress() }
                if (myIp == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@NetworkDetailActivity, "No Wi-Fi connection", Toast.LENGTH_SHORT).show()
                        swipeRefresh.isRefreshing = false
                    }
                    return@launch
                }

                var newDevicesCount = 0
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
                val subnetPrefix = myIp.substringBeforeLast(".")
                
                networkRepository.discoverDevices(subnetPrefix).collect { discovered ->
                    try {
                        val existing = existingDevices.find { it.ipAddress == discovered.ipAddress }
                        
                        val deviceToUpsert = if (existing != null) {
                            existing.copy(
                                status = "Online",
                                lastSeen = isoFormat.format(Date())
                            )
                        } else {
                            newDevicesCount++
                            discovered.copy(
                                networkId = networkId,
                                macAddress = discovered.macAddress?.uppercase(),
                                lastSeen = isoFormat.format(Date())
                            )
                        }

                        withContext(Dispatchers.Main) {
                            adapter.addOrUpdateDevice(deviceToUpsert)
                            if (existing == null) existingDevices.add(deviceToUpsert)
                        }
                        
                        supabaseRepository.upsertDevice(deviceToUpsert)
                    } catch (e: Exception) {
                        Log.e("Fluxio", "Error processing discovered device: ${e.stackTraceToString()}")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    val message = if (newDevicesCount > 0) "$newDevicesCount new devices added" else "Scan complete."
                    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Scan crash prevented: ${e.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this@NetworkDetailActivity, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePowerControl(device: SupabaseDevice) {
        lifecycleScope.launch {
            try {
                val typeName = (device.deviceType ?: device.type)?.uppercase() ?: "PC"
                if (typeName != "PC") return@launch

                if (device.status.lowercase() == "online" || device.status.lowercase() == "active") {
                    device.id?.let {
                        supabaseRepository.updatePendingCommand(it, "shutdown")
                        Toast.makeText(this@NetworkDetailActivity, "Shutdown command sent", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    device.macAddress?.let {
                        WakeOnLan.sendMagicPacket(it)
                        Toast.makeText(this@NetworkDetailActivity, "WOL Magic Packet sent", Toast.LENGTH_SHORT).show()
                        delay(2000)
                        performStreamingRescan()
                    } ?: Toast.makeText(this@NetworkDetailActivity, "Missing MAC address! Run setup_fluxio.ps1 on the PC first.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Power control failed: ${e.stackTraceToString()}")
                Toast.makeText(this@NetworkDetailActivity, "Power control failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddDeviceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val editUser = dialogView.findViewById<EditText>(R.id.editSshUsername)
        val editPass = dialogView.findViewById<EditText>(R.id.editSshPassword)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteDevice)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
        
        btnDelete.visibility = View.GONE
        
        val typeList = DeviceType.entries.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Device Manually")
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = editName.text.toString().trim()
            val ip = editIp.text.toString().trim()
            val mac = editMac.text.toString().trim().uppercase()
            val type = spinner.selectedItem.toString()

            if (name.isNotEmpty() && ip.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
                        val device = SupabaseDevice(
                            networkId = networkId,
                            name = name,
                            ipAddress = ip,
                            macAddress = mac.ifBlank { null },
                            sshUsername = editUser.text.toString().trim(),
                            sshPassword = editPass.text.toString().trim(),
                            deviceType = type
                        )
                        supabaseRepository.upsertDevice(device)
                        loadDevices()
                        dialog.dismiss()
                    } catch (e: Exception) {
                        Log.e("Fluxio", "Manual add failed: ${e.stackTraceToString()}")
                        Toast.makeText(this@NetworkDetailActivity, "Add failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Name and IP are required", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showEditDeviceDialog(device: SupabaseDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val editUser = dialogView.findViewById<EditText>(R.id.editSshUsername)
        val editPass = dialogView.findViewById<EditText>(R.id.editSshPassword)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteDevice)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)

        editName.setText(device.name)
        editIp.setText(device.ipAddress)
        editMac.setText(device.macAddress)
        editUser.setText(device.sshUsername)
        editPass.setText(device.sshPassword)

        val typeList = DeviceType.entries.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val typeIndex = typeList.indexOf(device.deviceType ?: "PC")
        if (typeIndex != -1) spinner.setSelection(typeIndex)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Device")
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val updated = device.copy(
                name = editName.text.toString().trim(),
                ipAddress = editIp.text.toString().trim(),
                macAddress = editMac.text.toString().trim().uppercase().ifBlank { null },
                sshUsername = editUser.text.toString().trim(),
                sshPassword = editPass.text.toString().trim(),
                deviceType = spinner.selectedItem.toString()
            )
            lifecycleScope.launch {
                try {
                    supabaseRepository.upsertDevice(updated)
                    loadDevices()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.e("Fluxio", "Update failed: ${e.stackTraceToString()}")
                    Toast.makeText(this@NetworkDetailActivity, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Device")
                .setMessage("Are you sure you want to delete ${device.name}?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            device.id?.let { supabaseRepository.deleteDevice(it) }
                            loadDevices()
                            dialog.dismiss()
                        } catch (e: Exception) {
                            Log.e("Fluxio", "Delete failed: ${e.stackTraceToString()}")
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("Fluxio", "IP lookup failed: ${ex.stackTraceToString()}")
        }
        return null
    }
}
