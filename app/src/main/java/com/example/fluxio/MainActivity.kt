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
        
        // Initialize default SSH profile if it doesn't exist
        lifecycleScope.launch {
            if (supabaseRepository.ensureDefaultCredentialExists()) {
                showFeedback("Default SSH profile initialized.")
            }
        }

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

        if (!handleNavigationIntent(intent)) {
            val prefs = getSharedPreferences("fluxio_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("first_run", true)) {
                showSetupGuide()
            } else {
                showCurrentNetwork()
            }
        }

        checkWifiConnection()
        checkConnectivity()
        observeSupabaseChanges()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateCheckedItem()
    }

    private fun handleNavigationIntent(intent: Intent?): Boolean {
        val show = intent?.getStringExtra("SHOW_LAYOUT")
        return when (show) {
            "CURRENT" -> { showCurrentNetwork(); true }
            "SAVED" -> { showSavedNetworks(); true }
            "SETUP" -> { showSetupGuide(); true }
            else -> false
        }
    }

    private fun updateCheckedItem() {
        val checkedId = when {
            layoutCurrent.visibility == View.VISIBLE -> R.id.nav_current
            layoutSaved.visibility == View.VISIBLE -> R.id.nav_saved
            layoutSetup.visibility == View.VISIBLE -> R.id.nav_setup
            else -> null
        }
        checkedId?.let { 
            navView.setCheckedItem(it)
            navView.menu.findItem(it)?.isChecked = true
        }
    }

    private fun showCurrentNetwork() {
        layoutCurrent.visibility = View.VISIBLE
        layoutSaved.visibility = View.GONE
        layoutSetup.visibility = View.GONE
        supportActionBar?.title = getString(R.string.title_current)
        updateCheckedItem()
    }

    private fun showSavedNetworks() {
        layoutCurrent.visibility = View.GONE
        layoutSaved.visibility = View.VISIBLE
        layoutSetup.visibility = View.GONE
        supportActionBar?.title = getString(R.string.title_saved)
        updateCheckedItem()
        loadSavedNetworks()
    }

    private fun showSetupGuide() {
        layoutCurrent.visibility = View.GONE
        layoutSaved.visibility = View.GONE
        layoutSetup.visibility = View.VISIBLE
        supportActionBar?.title = "How to Setup"
        updateCheckedItem()
    }

    private fun setupSetupGuideHandlers() {
        val winScript = "powershell -Command \"Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12; iex ((New-Object System.Net.WebClient).DownloadString('https://raw.githubusercontent.com/lukavuga/fluxio/refs/heads/main/setup_fluxio.ps1'))\""
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
        showFeedback(label)
    }

    private fun checkConnectivity() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isConnected) {
            showFeedback("No internet connection")
        }
    }

    private fun checkWifiConnection() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            showFeedback(getString(R.string.no_wifi))
        }
    }

    private fun handlePowerControl(device: SupabaseDevice) {
        lifecycleScope.launch {
            try {
                val typeName = (device.deviceType ?: device.type)?.uppercase() ?: "PC"
                if (typeName != "PC") {
                    showFeedback("Power management only for PCs")
                    return@launch
                }

                val statusLabel = device.status ?: "Offline"
                if (statusLabel.lowercase() == "active" || statusLabel.lowercase() == "online") {
                    if (device.credentialId.isNullOrBlank()) {
                        showFeedback("No SSH profile linked! Edit device to link one.")
                    } else {
                        showFeedback("Sending Shutdown command...")
                        val cred = supabaseRepository.getCredentialById(device.credentialId)
                        if (cred != null) {
                            val decryptedPass = try {
                                SecurityUtils.decrypt(cred.sshPassword) ?: cred.sshPassword
                            } catch (e: Exception) {
                                Log.e("Fluxio", "Decryption failed, using raw string")
                                cred.sshPassword
                            }
                            
                            withContext(Dispatchers.IO) {
                                supabaseRepository.executeSshCommand(device.ipAddress, cred.sshUsername, decryptedPass, "shutdown /s /t 0")
                            }
                            showFeedback("Shutdown command sent via SSH")
                        } else {
                            showFeedback("Linked SSH profile not found!")
                        }
                    }
                } else {
                    if (device.macAddress.isNullOrBlank()) {
                        showFeedback("Missing MAC address! Run setup script on the PC.")
                    } else {
                        showFeedback("Sending Magic Packet...")
                        WakeOnLan.sendMagicPacket(device.macAddress!!)
                        delay(3000)
                        startSmartScan() 
                    }
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
        val itemId = item.itemId
        
        when (itemId) {
            R.id.nav_current -> showCurrentNetwork()
            R.id.nav_saved -> showSavedNetworks()
            R.id.nav_setup -> showSetupGuide()
            R.id.nav_ssh_profiles -> {
                drawerLayout.closeDrawer(GravityCompat.START)
                // Start activity with a slight delay for better transition
                lifecycleScope.launch {
                    delay(200)
                    startActivity(Intent(this@MainActivity, SshProfilesActivity::class.java))
                }
                return false // Don't check item here
            }
            R.id.nav_logout -> {
                drawerLayout.closeDrawer(GravityCompat.START)
                lifecycleScope.launch {
                    delay(200)
                    authRepository.signOut()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                return false
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showSaveDialog() {
        if (currentScanResults.isEmpty()) {
            showFeedback("No devices discovered!")
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
                                showFeedback("Name already exists!")
                            } else {
                                saveNetworkToSupabase(name)
                            }
                        } catch (e: Exception) {
                            showFeedback("Error checking networks")
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
            
            val network = supabaseRepository.upsertNetwork(name, user.id)
            createdNetworkId = network.id ?: throw Exception("Failed to retrieve Network ID")

            val devicesToSave = currentScanResults.map { 
                it.copy(
                    networkId = createdNetworkId!!, 
                    originalName = null,
                    macAddress = it.macAddress?.uppercase()
                ) 
            }
            supabaseRepository.saveDevices(devicesToSave)

            withContext(Dispatchers.Main) {
                showFeedback("Network '$name' saved successfully!")
                btnSave.isEnabled = false
                currentScanResults.clear()
                currentDeviceAdapter.updateList(emptyList())
            }
        } catch (e: Exception) {
            try {
                createdNetworkId?.let { supabaseRepository.deleteNetwork(it) }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                Log.e("Fluxio", "Critical Save Error: ${e.stackTraceToString()}")
                showFeedback("Error saving: ${e.message}")
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
                            onEdit = { network -> showRenameNetworkDialog(network) },
                            onDelete = { network -> showDeleteNetworkDialog(network) }
                        )
                    }
                }
            } catch (e: Exception) {
                showFeedback("Error loading networks")
            }
        }
    }

    private fun showRenameNetworkDialog(network: SupabaseNetwork) {
        val input = EditText(this).apply {
            hint = "New name"
            setText(network.name)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Network")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != network.name) {
                    lifecycleScope.launch {
                        try {
                            network.id?.let { supabaseRepository.updateNetworkName(it, newName) }
                            loadSavedNetworks()
                            showFeedback("Network renamed")
                        } catch (e: Exception) {
                            showFeedback("Rename failed")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                        showFeedback("Error deleting network")
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
                        showFeedback(getString(R.string.no_ip))
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
                    showFeedback(getString(R.string.scan_finished))
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Main scan crash prevented", e)
                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.GONE
                    swipeRefreshMain.isRefreshing = false
                    btnScan.isEnabled = true
                    showFeedback("Scan failed: ${e.message}")
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
        val typeSpinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)
        val sshSpinner = dialogView.findViewById<Spinner>(R.id.spinnerSshCredentials)
        val txtNoCreds = dialogView.findViewById<TextView>(R.id.txtNoCredentialsHint)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteDevice)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveChanges)
        
        editName.setText(device.name)
        editIp.setText(device.ipAddress)
        editIp.isEnabled = false
        editMac.setText(device.macAddress ?: "")

        val typeList = DeviceType.entries.map { it.name }
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentType = (device.deviceType ?: device.type) ?: "PC"
        typeSpinner.setSelection(typeList.indexOf(currentType.uppercase()).coerceAtLeast(0))

        lifecycleScope.launch {
            val allCreds = supabaseRepository.getSshCredentials()
            val creds = allCreds.filter { it.isEnabled }
            withContext(Dispatchers.Main) {
                if (creds.isEmpty()) {
                    txtNoCreds.visibility = View.VISIBLE
                    sshSpinner.visibility = View.GONE
                } else {
                    txtNoCreds.visibility = View.GONE
                    sshSpinner.visibility = View.VISIBLE
                    val profileNames = mutableListOf("None")
                    profileNames.addAll(creds.map { it.label })
                    sshSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, profileNames).apply {
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
                val allCreds = supabaseRepository.getSshCredentials()
                val creds = allCreds.filter { it.isEnabled }
                val selectedProfileIdx = sshSpinner.selectedItemPosition
                val credId = if (selectedProfileIdx > 0) creds[selectedProfileIdx - 1].id else null
                
                val updated = device.copy(
                    name = editName.text.toString().trim(),
                    macAddress = editMac.text.toString().trim().uppercase().ifBlank { null },
                    credentialId = credId,
                    deviceType = typeSpinner.selectedItem.toString(),
                    type = typeSpinner.selectedItem.toString()
                )
                val index = currentScanResults.indexOfFirst { it.ipAddress == device.ipAddress }
                if (index != -1) {
                    currentScanResults[index] = updated
                    currentDeviceAdapter.addOrUpdateDevice(updated)
                }
                dialog.dismiss()
                showFeedback("Changes saved locally")
            }
        }

        btnDelete.setOnClickListener {
            currentScanResults.removeAll { it.ipAddress == device.ipAddress }
            currentDeviceAdapter.updateList(ArrayList(currentScanResults))
            dialog.dismiss()
            showFeedback("Device removed from scan")
        }
        dialog.show()
    }
}
