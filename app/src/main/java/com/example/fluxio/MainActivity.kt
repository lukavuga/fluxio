package com.example.fluxio

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        currentDeviceAdapter = DeviceAdapter(mutableListOf()) { device ->
            showDeviceInfoDialog(device)
        }

        recyclerCurrent.layoutManager = GridLayoutManager(this, 2)
        recyclerCurrent.adapter = currentDeviceAdapter

        recyclerSaved.layoutManager = LinearLayoutManager(this)

        // --- KONFIGURACIJA SWIPE (Standard + Custom) ---
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)
        navView.setCheckedItem(R.id.nav_current)

        // TUKAJ POKLIČEMO NOVO FUNKCIJO ZA KRETNJE ČEZ CEL ZASLON
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
            Toast.makeText(this, "Skeniram omrežje...", Toast.LENGTH_SHORT).show()
            startSmartScan()
        }

        btnSave.setOnClickListener {
            showSaveDialog()
        }

        checkWifiConnection()
    }

    // --- NOVA FUNKCIJA: Omogoča swipe čez cel zaslon ---
    private fun setupFullScreenSwipe() {
        val swipeListener = object : OnSwipeTouchListener(this) {
            override fun onSwipeRight() {
                // Če potegnemo v desno, odpri meni
                if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }
            override fun onSwipeLeft() {
                // Opcijsko: Če je meni odprt in potegnemo v levo, ga zapri (navadno to dela že sistem)
            }
        }

        // Kretnje dodamo na glavne elemente, da primejo kjerkoli
        recyclerCurrent.setOnTouchListener(swipeListener)
        recyclerSaved.setOnTouchListener(swipeListener)
        layoutCurrent.setOnTouchListener(swipeListener)
        layoutSaved.setOnTouchListener(swipeListener)

        // Tudi na prazen tekst, če ni omrežij
        findViewById<View>(R.id.txtNoSaved).setOnTouchListener(swipeListener)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_current -> {
                layoutCurrent.visibility = View.VISIBLE
                layoutSaved.visibility = View.GONE
                supportActionBar?.title = "Fluxio: Current"
            }
            R.id.nav_saved -> {
                layoutCurrent.visibility = View.GONE
                layoutSaved.visibility = View.VISIBLE
                supportActionBar?.title = "Fluxio: Saved"
                loadSavedNetworks()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    // ... (Ostala koda ostane ENAKA, kopirana iz prejšnjega odgovora) ...
    // Zaradi preglednosti in omejitve dolžine spodaj kopiram ključne dele,
    // ki morajo biti v datoteki (vse ostale funkcije za bazo, skeniranje itd. so iste).
    // Prepričajte se, da imate v spodnjem delu še vedno funkcije:
    // showSaveDialog, saveNetworkToDb, loadSavedNetworks, showDeleteNetworkDialog,
    // showNetworkDetails, performRescanForNetwork, itd.

    // --- BAZA IN DIALOGI ---

    private fun showSaveDialog() {
        if (currentScanResults.isEmpty()) {
            Toast.makeText(this, "Ni naprav za shranjevanje!", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this)
        input.hint = "Npr. Doma, Pisarna..."
        AlertDialog.Builder(this)
            .setTitle("Shrani omrežje")
            .setMessage("Vnesite ime za to omrežje:")
            .setView(input)
            .setPositiveButton("Shrani") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val existing = db.networkDao().getNetworkByName(name)
                        if (existing == null) {
                            saveNetworkToDb(name)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Ime že obstaja!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Prekliči", null)
            .show()
    }

    private suspend fun saveNetworkToDb(name: String) {
        val network = SavedNetwork(
            name = name,
            timestamp = System.currentTimeMillis(),
            deviceCount = currentScanResults.size
        )
        val netId = db.networkDao().insertNetwork(network).toInt()

        val devicesToSave = currentScanResults.map {
            it.copy(networkId = netId)
        }
        db.networkDao().insertDevices(devicesToSave)

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Shranjeno!", Toast.LENGTH_SHORT).show()
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
            .setTitle("Izbris omrežja")
            .setMessage("Želite izbrisati '${network.name}'?")
            .setPositiveButton("Izbriši") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.networkDao().deleteNetwork(network)
                    withContext(Dispatchers.Main) { loadSavedNetworks() }
                }
            }
            .setNegativeButton("Prekliči", null)
            .show()
    }

    // --- PRIKAZ PODROBNOSTI IN RESCAN ---

    private fun showNetworkDetails(network: SavedNetwork) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerDeviceList)
        val btnRescan = dialogView.findViewById<Button>(R.id.btnRescanNetwork)

        recyclerView.layoutManager = GridLayoutManager(this, 2)

        val detailsDialog = AlertDialog.Builder(this)
            .setTitle("Naprave: ${network.name}")
            .setView(dialogView)
            .setPositiveButton("Zapri", null)
            .create()

        detailsDialog.setOnDismissListener {
            loadSavedNetworks()
        }

        btnRescan.setOnClickListener {
            btnRescan.isEnabled = false
            btnRescan.text = "Iščem..."
            performRescanForNetwork(network, btnRescan, recyclerView)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dbDevices = db.networkDao().getDevicesForNetwork(network.id)
            val devices = dbDevices.map { it.apply { status = "Inactive" } }.toMutableList()

            withContext(Dispatchers.Main) {
                val adapter = DeviceAdapter(devices) { device ->
                    showEditDeviceDialog(device) {
                        refreshDeviceList(network.id, recyclerView)
                    }
                }
                recyclerView.adapter = adapter
                detailsDialog.show()
            }

            checkStatusOnly(devices, recyclerView)
        }
    }

    private suspend fun checkStatusOnly(devices: List<SavedDevice>, recyclerView: RecyclerView) {
        val updatedDevices = devices.map { device ->
            try {
                if (InetAddress.getByName(device.ip).isReachable(200)) {
                    device.copy().apply { status = "Active" }
                } else {
                    device
                }
            } catch (e: Exception) { device }
        }
        withContext(Dispatchers.Main) {
            (recyclerView.adapter as? DeviceAdapter)?.updateList(updatedDevices)
        }
    }

    private fun performRescanForNetwork(network: SavedNetwork, btn: Button, recyclerView: RecyclerView) {
        val executor = Executors.newFixedThreadPool(20)

        lifecycleScope.launch(Dispatchers.IO) {
            val myIp = getLocalIpAddress()
            if (myIp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Niste povezani na WiFi!", Toast.LENGTH_SHORT).show()
                    btn.isEnabled = true
                    btn.text = "Išči nove naprave"
                }
                return@launch
            }

            val foundDevices = mutableListOf<SavedDevice>()
            val subnet = myIp.substringBeforeLast(".")

            for (i in 1..254) {
                val host = "$subnet.$i"
                executor.execute {
                    try {
                        val addr = InetAddress.getByName(host)
                        if (addr.isReachable(300)) {
                            val name = addr.canonicalHostName ?: host
                            val type = determineDeviceType(name, i)
                            val device = SavedDevice(networkId = network.id, ip = host, name = name, type = type)
                            device.status = "Active"
                            synchronized(foundDevices) { foundDevices.add(device) }
                        }
                    } catch (_: Exception) {}
                }
            }

            executor.shutdown()
            try { executor.awaitTermination(15, TimeUnit.SECONDS) } catch (_: Exception) {}

            val newDevicesToAdd = mutableListOf<SavedDevice>()
            val existingDevices = db.networkDao().getDevicesForNetwork(network.id).toMutableList()
            val existingMap = existingDevices.associateBy { it.ip }

            for (found in foundDevices) {
                if (!existingMap.containsKey(found.ip)) {
                    newDevicesToAdd.add(found)
                }
            }

            if (newDevicesToAdd.isNotEmpty()) {
                db.networkDao().insertDevices(newDevicesToAdd)
                val newCount = existingDevices.size + newDevicesToAdd.size
                val updatedNetwork = network.copy(deviceCount = newCount, timestamp = System.currentTimeMillis())
                db.networkDao().updateNetwork(updatedNetwork)
            }

            refreshDeviceListWithStatus(network.id, recyclerView, foundDevices.map { it.ip })

            withContext(Dispatchers.Main) {
                val msg = if (newDevicesToAdd.isNotEmpty()) "Dodano: ${newDevicesToAdd.size} novih naprav." else "Ni novih naprav."
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                btn.isEnabled = true
                btn.text = "Išči nove naprave"
            }
        }
    }

    private suspend fun refreshDeviceListWithStatus(networkId: Int, recyclerView: RecyclerView, activeIps: List<String>) {
        val allDevices = db.networkDao().getDevicesForNetwork(networkId)
        val uiDevices = allDevices.map { device ->
            if (activeIps.contains(device.ip)) {
                device.apply { status = "Active" }
            } else {
                device.apply { status = "Inactive" }
            }
        }
        withContext(Dispatchers.Main) {
            (recyclerView.adapter as? DeviceAdapter)?.updateList(uiDevices)
        }
    }

    private fun refreshDeviceList(networkId: Int, recyclerView: RecyclerView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = db.networkDao().getDevicesForNetwork(networkId)
            devices.forEach { it.status = "Inactive" }
            withContext(Dispatchers.Main) {
                (recyclerView.adapter as? DeviceAdapter)?.updateList(devices)
            }
        }
    }

    // --- UREJANJE IN BRISANJE ---

    private fun showEditDeviceDialog(device: SavedDevice, onComplete: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_device, null)
        val editName = dialogView.findViewById<EditText>(R.id.editDeviceName)
        val editIp = dialogView.findViewById<EditText>(R.id.editDeviceIp)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerDeviceType)

        editName.setText(device.name)
        editIp.setText(device.ip)

        val types = arrayOf("PC", "LAPTOP", "PHONE", "TV", "ROUTER", "PRINTER")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val typeIndex = types.indexOf(device.type)
        if (typeIndex >= 0) spinner.setSelection(typeIndex)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            val updated = device.copy(
                name = editName.text.toString(),
                ip = editIp.text.toString(),
                type = spinner.selectedItem.toString()
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db.networkDao().updateDevice(updated)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Posodobljeno", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onComplete()
                }
            }
        }

        dialogView.findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Izbris naprave")
                .setMessage("Res želite izbrisati to napravo?")
                .setPositiveButton("Da") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.networkDao().deleteDevice(device)

                        val remainingDevices = db.networkDao().getDevicesForNetwork(device.networkId)
                        val network = db.networkDao().getNetworkById(device.networkId)
                        if (network != null) {
                            val updatedNetwork = network.copy(deviceCount = remainingDevices.size)
                            db.networkDao().updateNetwork(updatedNetwork)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Izbrisano", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onComplete()
                        }
                    }
                }
                .setNegativeButton("Ne", null)
                .show()
        }
        dialog.show()
    }

    private fun showDeviceInfoDialog(device: SavedDevice) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_info, null)
        dialogView.findViewById<TextView>(R.id.txtDeviceNameDialog).text = device.name
        dialogView.findViewById<TextView>(R.id.txtDeviceIpDialog).text = device.ip
        dialogView.findViewById<TextView>(R.id.txtDeviceTypeDialog).text = device.type
        dialogView.findViewById<TextView>(R.id.txtDeviceStatusDialog).text = device.status

        val iconRes = when (device.type) {
            "TV" -> R.drawable.tv
            "PHONE" -> R.drawable.phone
            "LAPTOP" -> R.drawable.laptop
            "ROUTER" -> R.drawable.router
            "PRINTER" -> R.drawable.printer
            else -> R.drawable.computer
        }
        dialogView.findViewById<ImageView>(R.id.imgDeviceIconDialog).setImageResource(iconRes)
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("OK", null).show()
    }

    // --- SKENIRANJE ---

    private fun checkWifiConnection() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Toast.makeText(this, "Povežite se na WiFi!", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSmartScan() {
        val executor = Executors.newFixedThreadPool(20)
        lifecycleScope.launch(Dispatchers.IO) {
            val myIp = getLocalIpAddress() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ni IP naslova!", Toast.LENGTH_SHORT).show()
                    btnSave.isEnabled = true
                }
                return@launch
            }

            val me = SavedDevice(0, 0, myIp, "Moja Naprava", "PHONE").apply { status = "Active" }
            withContext(Dispatchers.Main) { addDeviceToUI(me) }

            val subnet = myIp.substringBeforeLast(".")
            for (i in 1..254) {
                val host = "$subnet.$i"
                if (host == myIp) continue

                executor.execute {
                    try {
                        val addr = InetAddress.getByName(host)
                        if (addr.isReachable(300)) {
                            val name = addr.canonicalHostName ?: host
                            val type = determineDeviceType(name, i)
                            val device = SavedDevice(0, 0, host, name, type).apply { status = "Active" }
                            runOnUiThread { addDeviceToUI(device) }
                        }
                    } catch (_: Exception) {}
                }
            }

            executor.shutdown()
            try { executor.awaitTermination(10, TimeUnit.SECONDS) } catch (e: InterruptedException) { e.printStackTrace() }

            withContext(Dispatchers.Main) {
                if (currentScanResults.isNotEmpty()) {
                    btnSave.isEnabled = true
                    Toast.makeText(this@MainActivity, "Skeniranje končano.", Toast.LENGTH_SHORT).show()
                } else {
                    btnSave.isEnabled = true
                }
            }
        }
    }

    private fun addDeviceToUI(device: SavedDevice) {
        synchronized(currentScanResults) {
            currentScanResults.add(device)
            currentScanResults.sortBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
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

    private fun determineDeviceType(name: String, lastOctet: Int): String {
        val h = name.lowercase()
        return when {
            lastOctet == 1 -> "ROUTER"
            h.contains("tv") || h.contains("bravia") -> "TV"
            h.contains("phone") || h.contains("android") || h.contains("pixel") -> "PHONE"
            h.contains("print") -> "PRINTER"
            h.contains("laptop") -> "LAPTOP"
            else -> "PC"
        }
    }

    // --- POMOŽNI RAZRED ZA SWIPE KRETNJE ---
    open class OnSwipeTouchListener(context: Context) : View.OnTouchListener {
        private val gestureDetector = GestureDetector(context, GestureListener())

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return gestureDetector.onTouchEvent(event)
        }

        private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onDown(e: MotionEvent): Boolean {
                return false // Pomembno: vrnemo false, da click evente še vedno primejo gumbi/listi
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            onSwipeRight()
                        } else {
                            onSwipeLeft()
                        }
                        return true
                    }
                }
                return false
            }
        }

        open fun onSwipeRight() {}
        open fun onSwipeLeft() {}
    }
}