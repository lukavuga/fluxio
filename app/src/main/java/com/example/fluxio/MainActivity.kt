package com.example.fluxio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import java.net.*
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var layoutCurrent: LinearLayout
    private lateinit var layoutSaved: LinearLayout
    private lateinit var layoutSetup: NestedScrollView
    private lateinit var progressContainer: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnSave: Button
    private lateinit var recyclerCurrent: RecyclerView
    private lateinit var recyclerSaved: RecyclerView
    private lateinit var navView: NavigationView

    private lateinit var repository: NetworkRepository
    private lateinit var supabaseRepository: SupabaseRepository
    private lateinit var authRepository: AuthRepository
    
    private val currentScanResults = mutableListOf<SupabaseDevice>()
    private lateinit var currentDeviceAdapter: DeviceAdapter
    
    private var activeDetailsDialog: AlertDialog? = null
    private var currentViewingNetwork: SupabaseNetwork? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supabaseRepository = SupabaseRepository(
            supabaseUrl = "https://vbpmfulxbpcuboirjokv.supabase.co",
            supabaseKey = "sb_publishable_RtG4GlKSSxRk3fCLYfXwjA_a-d50Yvp"
        )
        authRepository = AuthRepository(supabaseRepository.client)

        // Session Management: Check if user is logged in
        if (!authRepository.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        repository = NetworkRepository()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById<NavigationView>(R.id.nav_view)
        layoutCurrent = findViewById(R.id.layoutCurrentNetwork)
        layoutSaved = findViewById(R.id.layoutSavedNetworks)
        layoutSetup = findViewById(R.id.layoutSetupGuide)
        progressContainer = findViewById(R.id.progressContainer)

        btnScan = findViewById(R.id.btnScan)
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
        
        // Display user email in drawer header if needed
        val headerView = navView.getHeaderView(0)
        headerView.findViewById<TextView>(R.id.textViewUserEmail)?.text = authRepository.currentUserEmail()

        setupFullScreenSwipe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (layoutSetup.visibility == View.VISIBLE || layoutSaved.visibility == View.VISIBLE) {
                    showCurrentNetwork()
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
            startSmartScan()
        }

        btnSave.setOnClickListener {
            showSaveDialog()
        }

        setupSetupGuideHandlers()

        // Onboarding Check
        val prefs = getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_run", true)) {
            showSetupGuide()
        } else {
            showCurrentNetwork()
        }

        checkWifiConnection()
        checkConnectivity()

        observeSupabaseChanges()
    }

    private fun showCurrentNetwork() {
        layoutCurrent.visibility = View.VISIBLE
        layoutSaved.visibility = View.GONE
        layoutSetup.visibility = View.GONE
        supportActionBar?.title = getString(R.string.title_current)
        navView.setCheckedItem(R.id.nav_current)
    }

    private fun showSavedNetworks() {
        layoutCurrent.visibility = View.GONE
        layoutSaved.visibility = View.VISIBLE
        layoutSetup.visibility = View.GONE
        supportActionBar?.title = getString(R.string.title_saved)
        navView.setCheckedItem(R.id.nav_saved)
        loadSavedNetworks()
    }

    private fun showSetupGuide() {
        layoutCurrent.visibility = View.GONE
        layoutSaved.visibility = View.GONE
        layoutSetup.visibility = View.VISIBLE
        supportActionBar?.title = "How to Setup"
        navView.setCheckedItem(R.id.nav_setup)
    }

    private fun setupSetupGuideHandlers() {
        val winScript = "powershell -ExecutionPolicy Bypass -Command \"iwr -useb https://raw.githubusercontent.com/lukavuga/fluxio/main/fluxio_setup_win.bat -OutFile f.bat; .\\\\f.bat\""
        val linuxScript = "curl -sSL https://raw.githubusercontent.com/lukavuga/fluxio/main/fluxio_setup_linux.sh | sudo bash"
        val macScript = "curl -sSL https://raw.githubusercontent.com/lukavuga/fluxio/main/fluxio_setup_mac.sh | zsh"

        val winUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_windows.ps1"
        val linuxUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_linux.sh"
        val macUrl = "https://raw.githubusercontent.com/lukavuga/fluxio/main/setup_mac.sh"

        findViewById<Button>(R.id.btnCopyWindowsScript).setOnClickListener { copyToClipboard(winScript, "Windows command copied") }
        findViewById<Button>(R.id.btnCopyLinuxScript).setOnClickListener { copyToClipboard(linuxScript, "Linux command copied") }
        findViewById<Button>(R.id.btnCopyMacScript).setOnClickListener { copyToClipboard(macScript, "macOS command copied") }

        findViewById<ImageButton>(R.id.btnShareWin).setOnClickListener { shareUrl(winUrl, "Windows") }
        findViewById<ImageButton>(R.id.btnShareLinux).setOnClickListener { shareUrl(linuxUrl, "Linux") }
        findViewById<ImageButton>(R.id.btnShareMac).setOnClickListener { shareUrl(macUrl, "macOS") }

        findViewById<Button>(R.id.btnFinishSetup).setOnClickListener {
            val prefs = getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("first_run", false).apply()
            showCurrentNetwork()
        }
    }

    private fun shareUrl(url: String, os: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Fluxio $os Setup Script")
            putExtra(Intent.EXTRA_TEXT, "Here is the Fluxio setup script for $os: $url")
        }
        startActivity(Intent.createChooser(intent, "Share Script Link"))
    }

    private fun observeSupabaseChanges() {
        lifecycleScope.launch {
            supabaseRepository.observeDeviceChanges().collect { supabaseDevice ->
                // Update current scan results if applicable
                val scanIndex = currentScanResults.indexOfFirst { it.macAddress == supabaseDevice.macAddress }
                if (scanIndex != -1) {
                    currentScanResults[scanIndex] = supabaseDevice
                    withContext(Dispatchers.Main) {
                        currentDeviceAdapter.updateList(ArrayList(currentScanResults))
                    }
                }
                
                // If viewing a specific network, refresh its devices
                if (currentViewingNetwork?.id == supabaseDevice.networkId) {
                    withContext(Dispatchers.Main) {
                        refreshNetworkDetails(currentViewingNetwork!!)
                    }
                }
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Fluxio Setup", text)
        cm.setPrimaryClip(clip)
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
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

    private fun handlePowerControl(device: SupabaseDevice) {
        lifecycleScope.launch {
            val statusLabel = device.status ?: "Offline"

            if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                if (device.macAddress.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Missing MAC address!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Sending Shutdown command via Supabase...", Toast.LENGTH_SHORT).show()
                    withContext(Dispatchers.IO) {
                        supabaseRepository.sendCommand(device.macAddress!!, "shutdown")
                    }
                }
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
        layoutSetup.setOnTouchListener(swipeListener)
        findViewById<View>(R.id.txtNoSaved).setOnTouchListener(swipeListener)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_current -> showCurrentNetwork()
            R.id.nav_saved -> showSavedNetworks()
            R.id.nav_setup -> showSetupGuide()
            R.id.nav_logout -> {
                lifecycleScope.launch {
                    authRepository.signOut()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
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
                    lifecycleScope.launch {
                        try {
                            val networks = supabaseRepository.getNetworks()
                            if (networks.any { it.name.equals(name, ignoreCase = true) }) {
                                Toast.makeText(this@MainActivity, "Name already exists!", Toast.LENGTH_SHORT).show()
                            } else {
                                saveNetworkToSupabase(name)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Error checking networks", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun saveNetworkToSupabase(name: String) {
        try {
            val network = supabaseRepository.createNetwork(name)
            val networkId = network.id ?: return

            val devicesToSave = currentScanResults.map { it.copy(networkId = networkId) }
            supabaseRepository.saveDevices(devicesToSave)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Error saving to Supabase", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedNetworks() {
        lifecycleScope.launch {
            try {
                val networks = supabaseRepository.getNetworks()
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
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error loading networks", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteNetworkDialog(network: SupabaseNetwork) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_network))
            .setMessage(getString(R.string.confirm_delete_network, network.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        network.id?.let { supabaseRepository.deleteNetwork(it) }
                        loadSavedNetworks()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error deleting network", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNetworkDetails(network: SupabaseNetwork) {
        activeDetailsDialog?.dismiss()
        currentViewingNetwork = network

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerDeviceList)
        val btnRescan = dialogView.findViewById<Button>(R.id.btnRescanNetwork)

        recyclerView.layoutManager = GridLayoutManager(this, 2)

        activeDetailsDialog = AlertDialog.Builder(this)
            .setTitle("${getString(R.string.devices)}: ${network.name}")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        activeDetailsDialog?.setOnDismissListener {
            activeDetailsDialog = null
            currentViewingNetwork = null
            loadSavedNetworks()
        }

        btnRescan.setOnClickListener {
            performRescanForNetwork(network, btnRescan)
        }

        refreshNetworkDetails(network)
        activeDetailsDialog?.show()
    }

    private fun refreshNetworkDetails(network: SupabaseNetwork) {
        val recyclerView = activeDetailsDialog?.findViewById<RecyclerView>(R.id.recyclerDeviceList) ?: return
        lifecycleScope.launch {
            try {
                val devices = supabaseRepository.getDevicesForNetwork(network.id!!)
                withContext(Dispatchers.Main) {
                    val adapter = DeviceAdapter(devices.toMutableList(), { device ->
                        showEditDeviceDialog(device) {
                            refreshNetworkDetails(network)
                        }
                    }, { device -> handlePowerControl(device) })
                    recyclerView.adapter = adapter
                }
                
                checkStatusAndSave(devices)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error loading devices", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun checkStatusAndSave(detailList: List<SupabaseDevice>) {
        withContext(Dispatchers.IO) {
            detailList.forEach { device ->
                val isActive = repository.isHostReachable(device.ipAddress, 500)
                val newStatus = if (isActive) "Online" else "Offline"
                
                var updatedDevice = device
                if (updatedDevice.macAddress.isNullOrBlank() && isActive) {
                    val mac = repository.getMacAddress(updatedDevice.ipAddress)
                    if (mac != null) {
                        updatedDevice = updatedDevice.copy(macAddress = mac)
                    }
                }

                if (updatedDevice.status != newStatus || updatedDevice.macAddress != device.macAddress) {
                    val finalDevice = updatedDevice.copy(status = newStatus)
                    supabaseRepository.upsertDevice(finalDevice)
                }
            }
        }
    }

    private fun performRescanForNetwork(network: SupabaseNetwork, btn: Button) {
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
            val scanResults = repository.scanSubnet(subnetPrefix)

            try {
                val existingDevices = supabaseRepository.getDevicesForNetwork(network.id!!)
                scanResults.forEach { found ->
                    val existing = existingDevices.find { it.macAddress == found.macAddress }
                    if (existing != null) {
                        val updated = existing.copy(
                            ipAddress = found.ipAddress,
                            status = "Online"
                        )
                        supabaseRepository.upsertDevice(updated)
                    } else {
                        val newDevice = found.copy(networkId = network.id!!, status = "Online")
                        supabaseRepository.upsertDevice(newDevice)
                    }
                }
                refreshNetworkDetails(network)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Rescan failed", Toast.LENGTH_SHORT).show()
            }

            btn.isEnabled = true
            btn.text = getString(R.string.search_new_devices)
        }
    }

    private fun showEditDeviceDialog(device: SupabaseDevice, onComplete: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtEditTitle)

        txtTitle?.text = "Edit Device"
        editName.setText(device.name)
        editIp.apply {
            setText(device.ipAddress)
            isEnabled = false
        }
        editMac.setText(device.macAddress ?: "")

        val typeList = DeviceType.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        val typeName = device.type ?: "OTHER"
        spinner.setSelection(typeList.indexOf(typeName.uppercase()))

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            lifecycleScope.launch {
                val updated = device.copy(
                    name = editName.text.toString(),
                    type = spinner.selectedItem.toString(),
                    macAddress = editMac.text.toString().trim().uppercase().ifBlank { null }
                )
                try {
                    supabaseRepository.updateDevice(updated)
                    Toast.makeText(this@MainActivity, getString(R.string.updated), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onComplete()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Update failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialogView.findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_device))
                .setMessage(getString(R.string.confirm_delete_device))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    lifecycleScope.launch {
                        try {
                            device.macAddress?.let { supabaseRepository.deleteDevice(it) }
                            Toast.makeText(this@MainActivity, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onComplete()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
        }
        dialog.show()
    }

    private fun showDeviceInfoDialog(device: SupabaseDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_info, null)
        val txtMac = dialogView.findViewById<TextView>(R.id.txtDeviceMacDialog)
        
        dialogView.apply {
            findViewById<TextView>(R.id.txtDeviceNameDialog).text = device.name
            findViewById<TextView>(R.id.txtDeviceIpDialog).text = device.ipAddress
            findViewById<TextView>(R.id.txtDeviceTypeDialog).text = device.type ?: "OTHER"
            findViewById<TextView>(R.id.txtDeviceStatusDialog).text = device.status ?: "Offline"
            
            val typeName = device.type?.uppercase() ?: "OTHER"
            if (typeName == "PC" || typeName == "LAPTOP") {
                txtMac.visibility = View.VISIBLE
                txtMac.text = "MAC: ${device.macAddress ?: "Unknown"}"
            } else {
                txtMac.visibility = View.GONE
            }

            val iconRes = when (typeName) {
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
                return@launch
            }

            progressContainer.visibility = View.VISIBLE
            btnScan.isEnabled = false

            val subnetPrefix = myIp.substringBeforeLast(".")
            
            val scanResults = withContext(Dispatchers.IO) {
                repository.scanSubnet(subnetPrefix)
            }
            
            currentScanResults.clear()
            currentScanResults.addAll(scanResults)
            currentDeviceAdapter.updateList(ArrayList(currentScanResults))
            
            progressContainer.visibility = View.GONE
            btnScan.isEnabled = true
            btnSave.isEnabled = currentScanResults.isNotEmpty()

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
