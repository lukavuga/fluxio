package com.example.fluxio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    private lateinit var swipeRefreshMain: SwipeRefreshLayout

    private lateinit var repository: NetworkRepository
    private val supabaseRepository = SupabaseRepository()
    private val authRepository = AuthRepository(SupabaseInstance.client)
    
    private val currentScanResults = mutableListOf<SupabaseDevice>()
    private lateinit var currentDeviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
        swipeRefreshMain = findViewById(R.id.swipeRefreshMain)

        btnScan = findViewById(R.id.btnScan)
        btnSave = findViewById(R.id.btnSave)
        recyclerCurrent = findViewById(R.id.recyclerCurrent)
        recyclerSaved = findViewById(R.id.recyclerSaved)

        currentDeviceAdapter = DeviceAdapter(mutableListOf(), { device ->
            showEditDeviceDialog(device)
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

        btnScan.setOnClickListener { startSmartScan() }
        swipeRefreshMain.setOnRefreshListener { startSmartScan() }
        btnSave.setOnClickListener { showSaveDialog() }

        setupSetupGuideHandlers()

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

        findViewById<Button>(R.id.btnCopyWindowsScript).setOnClickListener { copyToClipboard(winScript, "Windows command copied") }
        findViewById<Button>(R.id.btnCopyLinuxScript).setOnClickListener { copyToClipboard(linuxScript, "Linux command copied") }
        findViewById<Button>(R.id.btnCopyMacScript).setOnClickListener { copyToClipboard(macScript, "macOS command copied") }

        findViewById<Button>(R.id.btnFinishSetup).setOnClickListener {
            val prefs = getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("first_run", false).apply()
            showCurrentNetwork()
        }
    }

    private fun observeSupabaseChanges() {
        lifecycleScope.launch {
            try {
                supabaseRepository.observeDeviceChanges().collect { supabaseDevice ->
                    val scanIndex = currentScanResults.indexOfFirst { it.ipAddress == supabaseDevice.ipAddress }
                    if (scanIndex != -1) {
                        currentScanResults[scanIndex] = supabaseDevice
                        withContext(Dispatchers.Main) {
                            currentDeviceAdapter.addOrUpdateDevice(supabaseDevice)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Real-time observer error", e)
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

    private fun checkWifiConnection() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.no_wifi), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handlePowerControl(device: SupabaseDevice) {
        lifecycleScope.launch {
            try {
                val typeName = (device.deviceType ?: device.type)?.uppercase() ?: "PC"
                if (typeName != "PC") {
                    Toast.makeText(this@MainActivity, "Power management only for PCs", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val statusLabel = device.status ?: "Offline"
                if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                    if (device.id.isNullOrBlank()) {
                        Toast.makeText(this@MainActivity, "Device not saved yet!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Sending Shutdown command...", Toast.LENGTH_SHORT).show()
                        withContext(Dispatchers.IO) {
                            supabaseRepository.updatePendingCommand(device.id!!, "shutdown")
                        }
                    }
                } else {
                    if (device.macAddress.isNullOrBlank()) {
                        Toast.makeText(this@MainActivity, "Missing MAC address! Run setup_fluxio.ps1 on the PC first.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Sending Magic Packet...", Toast.LENGTH_SHORT).show()
                        WakeOnLan.sendMagicPacket(device.macAddress!!)
                        delay(3000)
                        startSmartScan() 
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Power control failed", Toast.LENGTH_SHORT).show()
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
        layoutCurrent.setOnTouchListener(swipeListener)
        layoutSaved.setOnTouchListener(swipeListener)
        layoutSetup.setOnTouchListener(swipeListener)
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
            Toast.makeText(this, "No devices discovered!", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.hint_network_name) }
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
        var createdNetworkId: String? = null
        try {
            val user = authRepository.getCurrentUser() ?: return
            
            // Transactional Saving Flow: Save/Upsert the Network and retrieve its id.
            val network = supabaseRepository.upsertNetwork(name, user.id)
            createdNetworkId = network.id ?: throw Exception("Failed to retrieve Network ID")

            // Map that id to all discovered Devices.
            val devicesToSave = currentScanResults.map { 
                it.copy(
                    networkId = createdNetworkId!!, 
                    originalName = null,
                    macAddress = it.macAddress?.uppercase()
                ) 
            }
            // Perform a bulk upsert for all devices with conflict handling.
            supabaseRepository.saveDevices(devicesToSave)

            withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(android.R.id.content), "Network '$name' saved successfully!", Snackbar.LENGTH_LONG).show()
                btnSave.isEnabled = false
                currentScanResults.clear()
                currentDeviceAdapter.updateList(emptyList())
            }
        } catch (e: Exception) {
            // Cleanup: if saving devices fails, delete the newly created/touched network to prevent 'empty' networks.
            try {
                createdNetworkId?.let { supabaseRepository.deleteNetwork(it) }
            } catch (cleanupError: Exception) {
                Log.e("Fluxio", "Cleanup failed after original error", cleanupError)
            }

            withContext(Dispatchers.Main) {
                // Print full error message to Logcat for debugging.
                Log.e("Fluxio", "Critical Save Error: ${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
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
                            onClick = { network -> 
                                val intent = Intent(this@MainActivity, NetworkDetailActivity::class.java).apply {
                                    putExtra("NETWORK_ID", network.id)
                                    putExtra("NETWORK_NAME", network.name)
                                }
                                startActivity(intent)
                            },
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

    private fun startSmartScan() {
        lifecycleScope.launch {
            try {
                val myIp = withContext(Dispatchers.IO) { getLocalIpAddress() } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.no_ip), Toast.LENGTH_SHORT).show()
                        swipeRefreshMain.isRefreshing = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.VISIBLE
                    btnScan.isEnabled = false
                    btnSave.isEnabled = false
                }

                val subnetPrefix = myIp.substringBeforeLast(".")
                currentScanResults.clear()
                withContext(Dispatchers.Main) {
                    currentDeviceAdapter.updateList(emptyList())
                }
                
                repository.discoverDevices(subnetPrefix).collect { device ->
                    currentScanResults.add(device)
                    withContext(Dispatchers.Main) {
                        currentDeviceAdapter.addOrUpdateDevice(device)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.GONE
                    swipeRefreshMain.isRefreshing = false
                    btnScan.isEnabled = true
                    btnSave.isEnabled = currentScanResults.isNotEmpty()
                    Toast.makeText(this@MainActivity, getString(R.string.scan_finished), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Main scan crash prevented", e)
                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.GONE
                    swipeRefreshMain.isRefreshing = false
                    btnScan.isEnabled = true
                    Toast.makeText(this@MainActivity, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return try {
                val handled = gestureDetector.onTouchEvent(event)
                if (!handled && event.action == MotionEvent.ACTION_UP) v.performClick()
                handled
            } catch (e: Exception) { false }
        }
        open fun onSwipeRight() {}
        open fun onSwipeLeft() {}
    }

    private fun showEditDeviceDialog(device: SupabaseDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val editMac = dialogView.findViewById<EditText>(R.id.editDeviceMac)
        val editUser = dialogView.findViewById<EditText>(R.id.editSshUsername)
        val editPass = dialogView.findViewById<EditText>(R.id.editSshPassword)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        
        editName.setText(device.name)
        editIp.setText(device.ipAddress)
        editIp.isEnabled = false
        editMac.setText(device.macAddress ?: "")
        editUser.setText(device.sshUsername ?: "")
        editPass.setText(device.sshPassword ?: "")

        val typeList = DeviceType.entries.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentType = (device.deviceType ?: device.type) ?: "PC"
        spinner.setSelection(typeList.indexOf(currentType.uppercase()).coerceAtLeast(0))

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            val updated = device.copy(
                name = editName.text.toString().trim(),
                macAddress = editMac.text.toString().trim().uppercase().ifBlank { null },
                sshUsername = editUser.text.toString().trim().ifBlank { null },
                sshPassword = editPass.text.toString().ifBlank { null },
                deviceType = spinner.selectedItem.toString(),
                type = spinner.selectedItem.toString()
            )
            val index = currentScanResults.indexOfFirst { it.ipAddress == device.ipAddress }
            if (index != -1) {
                currentScanResults[index] = updated
                currentDeviceAdapter.addOrUpdateDevice(updated)
            }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            currentScanResults.removeAll { it.ipAddress == device.ipAddress }
            currentDeviceAdapter.updateList(ArrayList(currentScanResults))
            dialog.dismiss()
        }
        dialog.show()
    }
}
