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
import com.google.android.material.snackbar.Snackbar
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
    private val currentScanResults = mutableListOf<DeviceView>()
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
        checkConnectivity()
        
        lifecycleScope.launch(Dispatchers.IO) {
            initLookupTables()
        }
    }

    private suspend fun initLookupTables() {
        val types = DeviceType.entries.map { it.name }
        types.forEach { db.networkDao().insertType(DeviceTypeEntity(typeName = it)) }
        
        val statuses = listOf("Active", "Inactive", "Online", "Offline")
        statuses.forEach { db.networkDao().insertStatus(DeviceStatusEntity(statusLabel = it)) }
    }

    private fun checkConnectivity() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isConnected) {
            Snackbar.make(findViewById(android.R.id.content), "No internet connection", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handlePowerControl(device: SavedDevice) {
        lifecycleScope.launch {
            val deviceView = withContext(Dispatchers.IO) { db.networkDao().getDeviceViewByDeviceId(device.id) }
            val statusLabel = deviceView?.statusEntity?.statusLabel ?: "Inactive"

            if (statusLabel == "Active" || statusLabel == "Online") {
                Toast.makeText(this@MainActivity, "Shutdown requested for ${device.ip}", Toast.LENGTH_SHORT).show()
            } else {
                if (device.macAddress.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Missing MAC address!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Sending Magic Packet...", Toast.LENGTH_SHORT).show()
                    WakeOnLan.sendMagicPacket(device.macAddress)
                    delay(3000)
                    startSmartScan() 
                }
            }
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
                                Toast.makeText(this@MainActivity, "Name already exists!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun saveNetworkToDb(name: String) {
        val network = SavedNetwork(name = name)
        val netId = db.networkDao().insertNetwork(network)

        currentScanResults.forEach { view ->
            val typeId = db.networkDao().getTypeIdByName(view.typeEntity?.typeName ?: "OTHER")
            val statusId = db.networkDao().getStatusIdByName(view.statusEntity?.statusLabel ?: "Inactive")
            
            val device = view.device.copy(
                networkId = netId,
                typeId = typeId,
                statusId = statusId
            )
            db.networkDao().upsertDevice(device)
        }

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
            performRescanForNetwork(network, btnRescan)
        }

        lifecycleScope.launch {
            db.networkDao().getDevicesWithDetails(network.id).collect { detailList ->
                withContext(Dispatchers.Main) {
                    val adapter = DeviceAdapter(detailList.toMutableList(), { device ->
                        showEditDeviceDialog(device) {
                            // Flow auto-updates
                        }
                    }, { device -> handlePowerControl(device) })
                    recyclerView.adapter = adapter
                    if (!detailsDialog.isShowing) detailsDialog.show()
                }
                
                checkStatusAndSave(detailList)
            }
        }
    }

    private suspend fun checkStatusAndSave(detailList: List<DeviceView>) {
        withContext(Dispatchers.IO) {
            val activeStatusId = db.networkDao().getStatusIdByName("Active")
            val inactiveStatusId = db.networkDao().getStatusIdByName("Inactive")
            
            detailList.forEach { view ->
                val isActive = isHostReachable(view.device.ip, 500)
                val newStatusId = if (isActive) activeStatusId else inactiveStatusId
                
                if (view.device.statusId != newStatusId) {
                    db.networkDao().updateDevice(view.device.copy(statusId = newStatusId))
                }
            }
        }
    }

    private fun isHostReachable(host: String, timeout: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
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

    private fun performRescanForNetwork(network: SavedNetwork, btn: Button) {
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
            
            val scanResults = withContext(Dispatchers.IO) {
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
                                
                                val typeId = db.networkDao().getTypeIdByName(type.name)
                                val statusId = db.networkDao().getStatusIdByName("Active")

                                SavedDevice(
                                    networkId = network.id,
                                    ip = host,
                                    name = name,
                                    originalName = if (rawName != "unknown") rawName else host,
                                    macAddress = mac,
                                    typeId = typeId,
                                    statusId = statusId
                                )
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }

            withContext(Dispatchers.IO) {
                scanResults.forEach { found ->
                    val existing = db.networkDao().getDeviceByOriginalName(network.id, found.originalName)
                    if (existing != null) {
                        db.networkDao().upsertDevice(existing.copy(
                            ip = found.ip,
                            macAddress = found.macAddress ?: existing.macAddress,
                            statusId = found.statusId
                        ))
                    } else {
                        db.networkDao().upsertDevice(found)
                    }
                }
                
                val count = db.networkDao().getDevicesForNetwork(network.id).size
                db.networkDao().updateNetwork(network.copy(deviceCount = count, timestamp = System.currentTimeMillis()))
            }

            btn.isEnabled = true
            btn.text = getString(R.string.search_new_devices)
        }
    }

    private fun getMacFromArp(ip: String): String? {
        try {
            val address = InetAddress.getByName(ip)
            val networkInterface = NetworkInterface.getByInetAddress(address)
            if (networkInterface != null) {
                val mac = networkInterface.hardwareAddress
                if (mac != null) return mac.joinToString(":") { "%02X".format(it) }
            }

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

    private fun showEditDeviceDialog(device: SavedDevice, onComplete: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)

        editName.setText(device.name)
        editIp.apply {
            setText(device.ip)
            isEnabled = false
        }
        editMac.setText(device.macAddress ?: "")

        val typeList = DeviceType.entries.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        lifecycleScope.launch {
            val viewData = withContext(Dispatchers.IO) { db.networkDao().getDeviceViewByDeviceId(device.id) }
            val typeName = viewData?.typeEntity?.typeName ?: "OTHER"
            spinner.setSelection(typeList.indexOf(typeName.uppercase()))
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val typeId = db.networkDao().getTypeIdByName(spinner.selectedItem.toString())
                val updated = device.copy(
                    name = editName.text.toString(),
                    typeId = typeId,
                    macAddress = editMac.text.toString().trim().uppercase().ifBlank { null }
                )
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
        lifecycleScope.launch {
            val viewData = withContext(Dispatchers.IO) { db.networkDao().getDeviceViewByDeviceId(device.id) }
            
            withContext(Dispatchers.Main) {
                dialogView.apply {
                    findViewById<TextView>(R.id.txtDeviceNameDialog).text = viewData?.device?.name ?: device.name
                    findViewById<TextView>(R.id.txtDeviceIpDialog).text = device.ip
                    findViewById<TextView>(R.id.txtDeviceTypeDialog).text = viewData?.typeEntity?.typeName ?: "OTHER"
                    findViewById<TextView>(R.id.txtDeviceStatusDialog).text = viewData?.statusEntity?.statusLabel ?: "Inactive"
                    
                    val iconRes = when (viewData?.typeEntity?.typeName) {
                        "TV" -> R.drawable.tv
                        "PHONE", "SMARTPHONE" -> R.drawable.phone
                        "PC", "LAPTOP" -> R.drawable.computer
                        "PRINTER" -> R.drawable.printer
                        "ROUTER" -> R.drawable.router
                        else -> R.drawable.fluxio
                    }
                    findViewById<ImageView>(R.id.imgDeviceIconDialog).setImageResource(iconRes)
                }
                AlertDialog.Builder(this@MainActivity).setView(dialogView).setPositiveButton(getString(R.string.ok), null).show()
            }
        }
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
                                
                                DeviceView(
                                    device = SavedDevice(
                                        networkId = 0,
                                        ip = host,
                                        name = name,
                                        originalName = if (rawName != "unknown") rawName else host,
                                        macAddress = mac,
                                        typeId = 0,
                                        statusId = 0
                                    ),
                                    typeEntity = DeviceTypeEntity(typeName = type.name),
                                    statusEntity = DeviceStatusEntity(statusLabel = "Active")
                                )
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }

            currentScanResults.clear()
            currentScanResults.addAll(results)
            currentDeviceAdapter.updateList(currentScanResults)
            btnSave.isEnabled = true
            Toast.makeText(this@MainActivity, getString(R.string.scan_finished), Toast.LENGTH_SHORT).show()
        }
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
        return hostname.lowercase()
            .removeSuffix(".local").removeSuffix(".home").removeSuffix(".lan")
            .replaceFirstChar { it.uppercase() }
    }

    private fun identifyDeviceType(hostname: String, ip: String): DeviceType {
        val h = hostname.lowercase()
        val mobileKeywords = listOf("samsung", "galaxy", "iphone", "apple", "pixel", "google", "xiaomi", "redmi", "vivo", "oppo", "huawei", "honor", "motorola", "phone", "mobile", "android", "watch", "wearable")
        return when {
            h.contains("tv") || h.contains("chromecast") || h.contains("roku") -> DeviceType.TV
            mobileKeywords.any { h.contains(it) } -> DeviceType.PHONE
            h.contains("print") || h.contains("hp") || h.contains("epson") -> DeviceType.PRINTER
            h.contains("desktop") || h.contains("pc") || h.contains("workstation") || h.contains("laptop") || h.contains("macbook") -> DeviceType.PC
            h.contains("router") || h.contains("gateway") || ip.endsWith(".1") -> DeviceType.ROUTER
            else -> DeviceType.OTHER
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
            if (!handled && event.action == MotionEvent.ACTION_UP) v.performClick()
            return handled
        }
        open fun onSwipeRight() {}
        open fun onSwipeLeft() {}
    }
}
