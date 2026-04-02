package com.example.fluxio

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader
import java.net.*
import kotlin.math.abs

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var layoutCurrent: LinearLayout
    private lateinit var layoutSaved: LinearLayout
    private lateinit var btnSave: Button
    private lateinit var recyclerCurrent: RecyclerView
    private lateinit var recyclerSaved: RecyclerView

    private lateinit var db: AppDatabase
    private val currentScanResults = mutableListOf<SavedDevice>()
    private lateinit var currentDeviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        layoutCurrent = findViewById(R.id.layoutCurrentNetwork)
        layoutSaved = findViewById(R.id.layoutSavedNetworks)

        val btnScan = findViewById<Button>(R.id.btnScan)
        btnSave = findViewById(R.id.btnSave)
        recyclerCurrent = findViewById(R.id.recyclerCurrent)
        recyclerSaved = findViewById(R.id.recyclerSaved)

        currentDeviceAdapter = DeviceAdapter(mutableListOf(), { device ->
            showDeviceInfoDialog(device)
        }, { device ->
            handlePowerControl(device)
        })

        recyclerCurrent.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = currentDeviceAdapter
        }

        recyclerSaved.layoutManager = LinearLayoutManager(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)
        navView.setCheckedItem(R.id.nav_current)

        setupFullScreenSwipe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnScan.setOnClickListener {
            currentScanResults.clear()
            currentDeviceAdapter.updateList(emptyList())
            btnSave.isEnabled = false
            Toast.makeText(this, getString(R.string.scan_network_toast), Toast.LENGTH_SHORT).show()
            startSmartScan()
        }

        btnSave.setOnClickListener {
            showSaveDialog()
        }

        checkWifiConnection()
    }

    private fun handlePowerControl(device: SavedDevice) {
        if (device.status == getString(R.string.active)) {
            showShutdownDialog(device)
        } else {
            if (device.macAddress.isNullOrBlank()) {
                Toast.makeText(this, "Missing MAC address! Please add it in edit menu.", Toast.LENGTH_LONG).show()
            } else {
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Sending Magic Packet to ${device.macAddress}...", Toast.LENGTH_SHORT).show()
                    WakeOnLan.sendMagicPacket(device.macAddress)
                    
                    delay(3000)
                    refreshDeviceStatus(device)
                }
            }
        }
    }

    private fun showShutdownDialog(device: SavedDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_shutdown_credentials, null)
        val editUser = dialogView.findViewById<EditText>(R.id.editSshUser)
        val editPass = dialogView.findViewById<EditText>(R.id.editSshPass)
        val radioWindows = dialogView.findViewById<RadioButton>(R.id.radioWindows)

        AlertDialog.Builder(this)
            .setTitle("Remote Shutdown")
            .setView(dialogView)
            .setPositiveButton("Shutdown") { _, _ ->
                val user = editUser.text.toString()
                val pass = editPass.text.toString()
                val isWindows = radioWindows.isChecked
                
                if (user.isNotBlank() && pass.isNotEmpty()) {
                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity, "Connecting to ${device.ip}...", Toast.LENGTH_SHORT).show()
                        val success = WakeOnLan.shutdownPC(device.ip, user, pass, isWindows)
                        if (success) {
                            Toast.makeText(this@MainActivity, "Shutdown command sent.", Toast.LENGTH_SHORT).show()
                            delay(5000)
                            refreshDeviceStatus(device)
                        } else {
                            Toast.makeText(this@MainActivity, "SSH Connection failed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun refreshDeviceStatus(device: SavedDevice) {
        val isActive = withContext(Dispatchers.IO) { isHostReachable(device.ip, 1000) }
        val newStatus = if (isActive) getString(R.string.active) else getString(R.string.inactive)
        
        val updatedList = currentScanResults.map { 
            if (it.ip == device.ip) it.copy().apply { status = newStatus } else it 
        }
        withContext(Dispatchers.Main) {
            currentDeviceAdapter.updateList(updatedList)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullScreenSwipe() {
        val swipeListener = object : OnSwipeTouchListener(this) {
            override fun onSwipeRight() {
                if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }
        }
        recyclerCurrent.setOnTouchListener(swipeListener)
        recyclerSaved.setOnTouchListener(swipeListener)
        layoutCurrent.setOnTouchListener(swipeListener)
        layoutSaved.setOnTouchListener(swipeListener)
        findViewById<View>(R.id.txtNoSaved).setOnTouchListener(swipeListener)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_current -> {
                layoutCurrent.visibility = View.VISIBLE
                layoutSaved.visibility = View.GONE
                supportActionBar?.title = getString(R.string.title_current)
            }
            R.id.nav_saved -> {
                layoutCurrent.visibility = View.GONE
                layoutSaved.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.title_saved)
                loadSavedNetworks()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showSaveDialog() {
        if (currentScanResults.isEmpty()) {
            Toast.makeText(this, "No devices to save!", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.hint_network_name)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_network))
            .setMessage(getString(R.string.enter_network_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val existing = db.networkDao().getNetworkByName(name)
                        if (existing == null) {
                            saveNetworkToDb(name)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, getString(R.string.name_already_exists), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun saveNetworkToDb(name: String) {
        val network = SavedNetwork(
            name = name,
            timestamp = System.currentTimeMillis(),
            deviceCount = currentScanResults.size
        )
        val netId = db.networkDao().insertNetwork(network)

        val devicesToSave = currentScanResults.map {
            it.copy(networkId = netId)
        }
        db.networkDao().insertDevices(devicesToSave)

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            btnSave.isEnabled = false
        }
    }

    private fun loadSavedNetworks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val networks = db.networkDao().getAllNetworks()
            withContext(Dispatchers.Main) {
                if (networks.isEmpty()) {
                    findViewById<TextView>(R.id.txtNoSaved).visibility = View.VISIBLE
                    recyclerSaved.visibility = View.GONE
                } else {
                    findViewById<TextView>(R.id.txtNoSaved).visibility = View.GONE
                    recyclerSaved.visibility = View.VISIBLE
                    recyclerSaved.adapter = SavedNetworkAdapter(networks,
                        onClick = { network -> showNetworkDetails(network) },
                        onLongClick = { network -> showDeleteNetworkDialog(network) }
                    )
                }
            }
        }
    }

    private fun showDeleteNetworkDialog(network: SavedNetwork) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_network))
            .setMessage(getString(R.string.confirm_delete_network, network.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.networkDao().deleteNetwork(network)
                    withContext(Dispatchers.Main) { loadSavedNetworks() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNetworkDetails(network: SavedNetwork) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerDeviceList)
        val btnRescan = dialogView.findViewById<Button>(R.id.btnRescanNetwork)

        recyclerView.layoutManager = GridLayoutManager(this, 2)

        val detailsDialog = AlertDialog.Builder(this)
            .setTitle("${getString(R.string.devices)}: ${network.name}")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        detailsDialog.setOnDismissListener {
            loadSavedNetworks()
        }

        btnRescan.setOnClickListener {
            performRescanForNetwork(network, btnRescan, recyclerView)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dbDevices = db.networkDao().getDevicesForNetwork(network.id)
            val devices = dbDevices.map { it.apply { status = getString(R.string.inactive) } }.toMutableList()

            withContext(Dispatchers.Main) {
                val adapter = DeviceAdapter(devices, { device ->
                    showEditDeviceDialog(device) {
                        refreshDeviceList(network.id, recyclerView)
                    }
                }, { device -> handlePowerControl(device) })
                recyclerView.adapter = adapter
                detailsDialog.show()
            }

            checkStatusOnly(devices, recyclerView)
        }
    }

    private suspend fun checkStatusOnly(devices: List<SavedDevice>, recyclerView: RecyclerView) {
        val updatedDevices = withContext(Dispatchers.IO) {
            devices.map { device ->
                if (isHostReachable(device.ip, 500)) {
                    device.copy().apply { status = getString(R.string.active) }
                } else {
                    device
                }
            }
        }
        withContext(Dispatchers.Main) {
            (recyclerView.adapter as? DeviceAdapter)?.updateList(updatedDevices)
        }
    }

    private fun isHostReachable(host: String, timeout: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
            
            // Fallback: Try common ports
            val commonPorts = intArrayOf(135, 445, 80)
            commonPorts.any { port ->
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), timeout / 2)
                        true
                    }
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) { false }
    }

    private fun performRescanForNetwork(network: SavedNetwork, btn: Button, recyclerView: RecyclerView) {
        btn.isEnabled = false
        btn.text = getString(R.string.scanning)

        lifecycleScope.launch {
            val myIp = withContext(Dispatchers.IO) { getLocalIpAddress() }
            if (myIp == null) {
                Toast.makeText(this@MainActivity, getString(R.string.no_wifi), Toast.LENGTH_SHORT).show()
                btn.isEnabled = true
                btn.text = getString(R.string.search_new_devices)
                return@launch
            }

            val subnetPrefix = myIp.substringBeforeLast(".")
            
            val foundDevices = withContext(Dispatchers.IO) {
                (1..254).map { i ->
                    async {
                        val host = "$subnetPrefix.$i"
                        try {
                            if (isHostReachable(host, 600)) {
                                val addr = InetAddress.getByName(host)
                                val rawName = if (addr.canonicalHostName != host) addr.canonicalHostName else "unknown"
                                val name = formatDeviceName(rawName, host)
                                val type = identifyDeviceType(name, host)
                                val mac = getMacFromArp(host)

                                SavedDevice(
                                    networkId = network.id,
                                    ip = host,
                                    name = name,
                                    type = type,
                                    originalName = if (rawName != "unknown") rawName else host,
                                    macAddress = mac ?: ""
                                ).apply { status = getString(R.string.active) }
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }

            withContext(Dispatchers.IO) {
                for (found in foundDevices) {
                    val existing = db.networkDao().getDeviceByOriginalName(network.id, found.originalName)
                    if (existing != null) {
                        // Keep user set name if changed, but update IP/MAC
                        val updated = existing.copy(
                            ip = found.ip,
                            macAddress = if (!found.macAddress.isNullOrBlank()) found.macAddress else existing.macAddress
                        )
                        db.networkDao().updateDevice(updated)
                    } else {
                        db.networkDao().upsertDevice(found)
                    }
                }

                val updatedDevices = db.networkDao().getDevicesForNetwork(network.id)
                db.networkDao().updateNetwork(network.copy(
                    deviceCount = updatedDevices.size,
                    timestamp = System.currentTimeMillis()
                ))
            }

            refreshDeviceListWithStatus(network.id, recyclerView, foundDevices.map { it.ip })
            btn.isEnabled = true
            btn.text = getString(R.string.search_new_devices)
        }
    }

    private fun getMacFromArp(ip: String): String? {
        try {
            val address = InetAddress.getByName(ip)
            // Try NetworkInterface first (usually works for local device)
            val networkInterface = NetworkInterface.getByInetAddress(address)
            if (networkInterface != null) {
                val mac = networkInterface.hardwareAddress
                if (mac != null) return mac.joinToString(":") { "%02X".format(it) }
            }

            // Fallback: Read ARP table (might be restricted on Android 10+)
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains(ip)) {
                    val parts = line!!.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[3].matches("..:..:..:..:..:..".toRegex())) {
                        return parts[3].uppercase()
                    }
                }
            }
            reader.close()
        } catch (_: Exception) { }
        return null
    }

    private suspend fun refreshDeviceListWithStatus(networkId: Long, recyclerView: RecyclerView, activeIps: List<String>) {
        val allDevices = withContext(Dispatchers.IO) { db.networkDao().getDevicesForNetwork(networkId) }
        val uiDevices = allDevices.map { device ->
            device.apply { status = if (activeIps.contains(device.ip)) getString(R.string.active) else getString(R.string.inactive) }
        }
        withContext(Dispatchers.Main) {
            (recyclerView.adapter as? DeviceAdapter)?.updateList(uiDevices)
        }
    }

    private fun refreshDeviceList(networkId: Long, recyclerView: RecyclerView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = db.networkDao().getDevicesForNetwork(networkId)
            devices.forEach { it.status = getString(R.string.inactive) }
            withContext(Dispatchers.Main) {
                (recyclerView.adapter as? DeviceAdapter)?.updateList(devices)
            }
        }
    }

    private fun showEditDeviceDialog(device: SavedDevice, onComplete: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)

        editName.setText(device.name)
        editIp.apply {
            setText(device.ip)
            isFocusable = false
            isClickable = false
        }
        editMac.setText(device.macAddress)

        val types = arrayOf("PC", "PHONE", "TV", "ROUTER", "PRINTER", "IOT")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val typeIndex = types.indexOf(device.type)
        if (typeIndex >= 0) spinner.setSelection(typeIndex)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            val updated = device.copy(
                name = editName.text.toString(),
                type = spinner.selectedItem.toString(),
                macAddress = editMac.text.toString().trim().uppercase()
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db.networkDao().updateDevice(updated)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.updated), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onComplete()
                }
            }
        }

        dialogView.findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_device))
                .setMessage(getString(R.string.confirm_delete_device))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.networkDao().deleteDevice(device)
                        val remainingDevices = db.networkDao().getDevicesForNetwork(device.networkId)
                        db.networkDao().getNetworkById(device.networkId)?.let { network ->
                            db.networkDao().updateNetwork(network.copy(deviceCount = remainingDevices.size))
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onComplete()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
        }
        dialog.show()
    }

    private fun showDeviceInfoDialog(device: SavedDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_info, null)
        dialogView.apply {
            findViewById<TextView>(R.id.txtDeviceNameDialog).text = device.name
            findViewById<TextView>(R.id.txtDeviceIpDialog).text = device.ip
            findViewById<TextView>(R.id.txtDeviceTypeDialog).text = device.type
            findViewById<TextView>(R.id.txtDeviceStatusDialog).text = device.status
            val iconRes = when (device.type) {
                "TV" -> R.drawable.tv
                "PHONE", "MOBILE" -> R.drawable.phone
                "ROUTER" -> R.drawable.router
                "PRINTER" -> R.drawable.printer
                else -> R.drawable.computer
            }
            findViewById<ImageView>(R.id.imgDeviceIconDialog).setImageResource(iconRes)
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton(getString(R.string.ok), null).show()
    }

    private fun checkWifiConnection() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Toast.makeText(this, getString(R.string.no_wifi), Toast.LENGTH_LONG).show()
        }
    }

    private fun startSmartScan() {
        lifecycleScope.launch {
            val myIp = withContext(Dispatchers.IO) { getLocalIpAddress() } ?: run {
                Toast.makeText(this@MainActivity, getString(R.string.no_ip), Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = true
                return@launch
            }

            val me = SavedDevice(0, 0, myIp, getString(R.string.my_device), "PHONE", "MY_DEVICE", "").apply { status = getString(R.string.active) }
            addDeviceToUI(me)

            val subnetPrefix = myIp.substringBeforeLast(".")
            val results = withContext(Dispatchers.IO) {
                (1..254).map { i ->
                    async {
                        val host = "$subnetPrefix.$i"
                        if (host == myIp) return@async null
                        try {
                            if (isHostReachable(host, 600)) {
                                val addr = InetAddress.getByName(host)
                                val rawName = if (addr.canonicalHostName != host) addr.canonicalHostName else "unknown"
                                val name = formatDeviceName(rawName, host)
                                val type = identifyDeviceType(name, host)
                                val mac = getMacFromArp(host)
                                SavedDevice(
                                    networkId = 0,
                                    ip = host,
                                    name = name,
                                    type = type,
                                    originalName = if (rawName != "unknown") rawName else host,
                                    macAddress = mac ?: ""
                                ).apply { status = getString(R.string.active) }
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }

            results.forEach { addDeviceToUI(it) }
            btnSave.isEnabled = true
            Toast.makeText(this@MainActivity, getString(R.string.scan_finished), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addDeviceToUI(device: SavedDevice) {
        synchronized(currentScanResults) {
            if (currentScanResults.none { it.ip == device.ip }) {
                currentScanResults.add(device)
                currentScanResults.sortBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
            }
        }
        currentDeviceAdapter.updateList(currentScanResults)
    }

    private fun getLocalIpAddress(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val link = cm.getLinkProperties(cm.activeNetwork)
        return link?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress
    }

    private fun formatDeviceName(hostname: String, ip: String): String {
        if (hostname == ip || hostname.equals("unknown", ignoreCase = true)) {
            return "Generic Device"
        }

        val cleanName = hostname
            .lowercase()
            .removeSuffix(".local")
            .removeSuffix(".home")
            .removeSuffix(".lan")
            .removeSuffix(".modem")
            .removeSuffix(".gateway")
            .removeSuffix(".broadband")

        return cleanName.replaceFirstChar { it.uppercase() }
    }

    private fun identifyDeviceType(hostname: String, ip: String): String {
        val h = hostname.lowercase()
            .removeSuffix(".local")
            .removeSuffix(".home")
            .removeSuffix(".lan")
            .removeSuffix(".modem")
            .removeSuffix(".gateway")
            .removeSuffix(".broadband")

        val lastOctet = ip.substringAfterLast(".").toIntOrNull() ?: 0

        val mobileKeywords = listOf(
            "samsung", "galaxy", "iphone", "apple", "pixel", "google",
            "xiaomi", "mi", "redmi", "poco", "vivo", "iqoo", "oppo", "realme", "oneplus", "huawei", "mate", "honor", "meizu",
            "motorola", "moto", "sony", "xperia", "lg", "htc", "asus", "zenfone", "rog", "lenovo", "legion", "zte", "nubia", "axon", "nothing", "cmf", "dizo", "fairphone", "nokia",
            "tecno", "infinix", "itel", "tcl", "alcatel", "blackberry", "micromax", "lava", "karbonn", "intex", "cherry mobile", "advan", "mito", "blu", "jolla", "general mobile", "pantech", "microsoft", "lumia", "surface duo",
            "sony ericsson", "siemens", "benq", "gionee", "vertu", "okapia", "nanjing bird",
            "phone", "handset", "mobile", "android",
            "sm-r", "applewatch", "watch", "wearable", "smartband", "fenix", "forerunner", "venu", "versa", "sense", "charge", "amazfit", "ticwatch", "suunto", "polar", "connected", "summit", "withings"
        )

        return when {
            lastOctet == 1 || h.contains("gateway") || h.contains("router") || h.contains("modem") -> "ROUTER"
            h.contains("tv") || h.contains("bravia") || h.contains("chromecast") || h.contains("roku") -> "TV"
            mobileKeywords.any { h.contains(it) } -> "PHONE"
            h.contains("print") || h.contains("hp") || h.contains("epson") || h.contains("canon") -> "PRINTER"
            h.contains("cam") || h.contains("nest") || h.contains("hub") || h.contains("sonos") || h.contains("hue") -> "IOT"
            h.contains("desktop") || h.contains("pc") || h.contains("workstation") || h.contains("laptop") || h.contains("macbook") || h.contains("surface") -> "PC"
            else -> "PC"
        }
    }

    open class OnSwipeTouchListener(context: Context) : View.OnTouchListener {
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > SWIPE_THRESHOLD && abs(vx) > SWIPE_VELOCITY_THRESHOLD) {
                    if (dx > 0) onSwipeRight() else onSwipeLeft()
                    return true
                }
                return false
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val handled = gestureDetector.onTouchEvent(event)
            if (!handled && event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            return handled
        }

        open fun onSwipeRight() {}
        open fun onSwipeLeft() {}
    }
}
