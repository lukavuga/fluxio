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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class NetworkDetailActivity : AppCompatActivity() {

    private lateinit var networkId: String
    private lateinit var networkName: String
    private lateinit var adapter: DeviceAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var supabaseRepository: SupabaseRepository
    private val networkRepository = NetworkRepository()
    
    private var existingDevices = mutableListOf<SupabaseDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_detail)

        supabaseRepository = SupabaseRepository(this)

        networkId = intent.getStringExtra("NETWORK_ID") ?: ""
        networkName = intent.getStringExtra("NETWORK_NAME") ?: "Network Details"

        if (networkId.isEmpty()) {
            showFeedback("Critical Error: Network ID missing")
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
                        showFeedback("Network deleted")
                        finish()
                    } catch (e: Exception) {
                        Log.e("Fluxio", "Delete network failed", e)
                        showFeedback("Deletion failed")
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
                
                // Use the robust Socket Ping check
                if (!isRealInternetAvailable()) {
                    showFeedback("Viewing offline data")
                } else {
                    checkStatusInBackground(devices)
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Error loading devices", e)
                withContext(Dispatchers.Main) {
                    showFeedback("Error loading devices")
                }
            }
        }
    }

    private fun observeChanges() {
        lifecycleScope.launch {
            try {
                // Use the robust Socket Ping check
                if (isRealInternetAvailable()) {
                    supabaseRepository.observeDeviceChanges(networkId).collect { device ->
                        withContext(Dispatchers.Main) {
                            adapter.addOrUpdateDevice(device)
                            val index = existingDevices.indexOfFirst { it.ipAddress == device.ipAddress }
                            if (index != -1) existingDevices[index] = device else existingDevices.add(device)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Real-time observation error", e)
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
                    Log.e("Fluxio", "Status check error for ${device.ipAddress}", e)
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
                        showFeedback("No connection detected")
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
                        Log.e("Fluxio", "Error processing discovered device", e)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    val message = if (newDevicesCount > 0) "$newDevicesCount new devices added" else "Scan complete."
                    showFeedback(message)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    swipeRefresh.isRefreshing = false
                    showFeedback("Scan failed: ${e.message}")
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
                    if (device.credentialId.isNullOrBlank()) {
                        showFeedback("No SSH profile linked! Edit device to link one.")
                    } else {
                        showFeedback("Sending Shutdown command...")
                        val cred = supabaseRepository.getCredentialById(device.credentialId)
                        if (cred != null) {
                            val decryptedPass = try {
                                SecurityUtils.decrypt(cred.sshPassword) ?: cred.sshPassword
                            } catch (e: Exception) {
                                cred.sshPassword 
                            }
                            
                            withContext(Dispatchers.IO) {
                                try {
                                    supabaseRepository.executeSshCommand(device.ipAddress, cred.sshUsername, decryptedPass, "shutdown /s /t 0")
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        showFeedback("SSH command failed. Check agent connection.")
                                    }
                                }
                            }
                        } else {
                            showFeedback("Linked SSH profile not found!")
                        }
                    }
                } else {
                    device.macAddress?.let {
                        withContext(Dispatchers.IO) {
                            WakeOnLan.sendMagicPacket(it)
                        }
                        showFeedback("WOL Magic Packet sent")
                        delay(2000)
                        performStreamingRescan()
                    } ?: showFeedback("Missing MAC address! Run setup script on the PC.")
                }
            } catch (e: Exception) {
                showFeedback("Power control failed: ${e.message}")
            }
        }
    }

    private fun showFeedback(message: String) {
        val root = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        snackbar.view.setBackgroundColor(getColor(R.color.flux_card))
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(getColor(R.color.white))
        snackbar.show()
    }

    private fun showAddDeviceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val typeSpinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val sshSpinner = dialogView.findViewById<Spinner>(R.id.spinnerSshCredentials)
        val txtNoCreds = dialogView.findViewById<TextView>(R.id.txtNoCredentialsHint)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteDevice)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
        
        txtTitle.text = "Add Device"
        btnDelete.visibility = View.GONE
        
        val typeList = DeviceType.entries.map { it.name }
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        lifecycleScope.launch {
            val creds = supabaseRepository.getSshCredentials().filter { it.isEnabled }
            withContext(Dispatchers.Main) {
                if (creds.isEmpty()) {
                    txtNoCreds.visibility = View.VISIBLE
                    sshSpinner.visibility = View.GONE
                } else {
                    txtNoCreds.visibility = View.GONE
                    sshSpinner.visibility = View.VISIBLE
                    val profileNames = mutableListOf("None")
                    profileNames.addAll(creds.map { it.label })
                    sshSpinner.adapter = ArrayAdapter(this@NetworkDetailActivity, android.R.layout.simple_spinner_item, profileNames).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = editName.text.toString().trim()
            val ip = editIp.text.toString().trim()
            val mac = editMac.text.toString().trim().uppercase()
            val type = typeSpinner.selectedItem.toString()

            if (name.isNotEmpty() && ip.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
                        val creds = supabaseRepository.getSshCredentials().filter { it.isEnabled }
                        val selectedProfileIdx = sshSpinner.selectedItemPosition
                        val credId = if (selectedProfileIdx > 0) creds[selectedProfileIdx - 1].id else null

                        val device = SupabaseDevice(
                            networkId = networkId,
                            label = name,
                            ipAddress = ip,
                            macAddress = mac.ifBlank { null },
                            credentialId = credId,
                            deviceType = type
                        )
                        supabaseRepository.upsertDevice(device)
                        loadDevices()
                        dialog.dismiss()
                        showFeedback("Device added")
                    } catch (e: Exception) {
                        showFeedback("Add failed: ${e.message}")
                    }
                }
            } else {
                showFeedback("Name and IP are required")
            }
        }
        dialog.show()
    }

    private fun showEditDeviceDialog(device: SupabaseDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val typeSpinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val sshSpinner = dialogView.findViewById<Spinner>(R.id.spinnerSshCredentials)
        val txtNoCreds = dialogView.findViewById<TextView>(R.id.txtNoCredentialsHint)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteDevice)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)

        editName.setText(device.label)
        editIp.setText(device.ipAddress)
        editMac.setText(device.macAddress)

        val typeList = DeviceType.entries.map { it.name }
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val typeIndex = typeList.indexOf(device.deviceType ?: "PC")
        if (typeIndex != -1) typeSpinner.setSelection(typeIndex)

        lifecycleScope.launch {
            val creds = supabaseRepository.getSshCredentials().filter { it.isEnabled }
            withContext(Dispatchers.Main) {
                if (creds.isEmpty()) {
                    txtNoCreds.visibility = View.VISIBLE
                    sshSpinner.visibility = View.GONE
                } else {
                    txtNoCreds.visibility = View.GONE
                    sshSpinner.visibility = View.VISIBLE
                    val profileNames = mutableListOf("None")
                    profileNames.addAll(creds.map { it.label })
                    sshSpinner.adapter = ArrayAdapter(this@NetworkDetailActivity, android.R.layout.simple_spinner_item, profileNames).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    val selectedIdx = creds.indexOfFirst { it.id == device.credentialId }
                    sshSpinner.setSelection(if (selectedIdx != -1) selectedIdx + 1 else 0)
                }
            }
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val creds = supabaseRepository.getSshCredentials().filter { it.isEnabled }
                    val selectedProfileIdx = sshSpinner.selectedItemPosition
                    val credId = if (selectedProfileIdx > 0) creds[selectedProfileIdx - 1].id else null

                    val updated = device.copy(
                        label = editName.text.toString().trim(),
                        ipAddress = editIp.text.toString().trim(),
                        macAddress = editMac.text.toString().trim().uppercase().ifBlank { null },
                        credentialId = credId,
                        deviceType = typeSpinner.selectedItem.toString()
                    )
                    supabaseRepository.upsertDevice(updated)
                    loadDevices()
                    dialog.dismiss()
                    showFeedback("Device updated")
                } catch (e: Exception) {
                    showFeedback("Update failed: ${e.message}")
                }
            }
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Device")
                .setMessage("Are you sure you want to delete ${device.label}?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            device.id?.let { supabaseRepository.deleteDevice(it) }
                            loadDevices()
                            dialog.dismiss()
                            showFeedback("Device deleted")
                        } catch (e: Exception) {
                            showFeedback("Delete failed")
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show()
    }

    private fun getLocalIpAddress(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val link = cm.getLinkProperties(cm.activeNetwork)
        return link?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress
    }
}
