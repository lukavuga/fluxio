package com.example.fluxio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

class NetworkDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val supabaseRepository = SupabaseRepository(application)
    private val networkRepository = NetworkRepository()

    private val _devices = MutableStateFlow<List<SupabaseDevice>>(emptyList())
    val devices: StateFlow<List<SupabaseDevice>> = _devices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var networkId: String = ""

    fun init(id: String) {
        networkId = id
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = supabaseRepository.getDevices(networkId)
            withContext(Dispatchers.Main) {
                _devices.value = loaded
            }
            checkStatusInBackground(loaded)
        }
    }

    fun checkStatusInBackground(devicesList: List<SupabaseDevice>) {
        viewModelScope.launch(Dispatchers.IO) {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
            devicesList.forEach { device ->
                try {
                    val addr = InetAddress.getByName(device.ipAddress)
                    val isAlive = addr.isReachable(1000) || networkRepository.isHostAlive(device.ipAddress, 1000)
                    val newStatus = if (isAlive) "Online" else "Offline"

                    if (device.status != newStatus) {
                        val updated = device.copy(
                            status = newStatus,
                            lastSeen = if (isAlive) isoFormat.format(Date()) else device.lastSeen
                        )
                        supabaseRepository.upsertDevice(updated)
                        updateDeviceInList(updated)
                    }
                } catch (e: Exception) {
                    if (device.status != "Offline") {
                        val updated = device.copy(status = "Offline")
                        supabaseRepository.upsertDevice(updated)
                        updateDeviceInList(updated)
                    }
                }
            }
        }
    }

    private suspend fun updateDeviceInList(updated: SupabaseDevice) = withContext(Dispatchers.Main) {
        val currentList = _devices.value.toMutableList()
        val index = currentList.indexOfFirst {
            (updated.macAddress != null && it.macAddress == updated.macAddress) || it.ipAddress == updated.ipAddress
        }
        if (index != -1) currentList[index] = updated else currentList.add(updated)
        _devices.value = currentList
    }

    fun performRescan(localIp: String?) {
        if (localIp == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            withContext(Dispatchers.Main) { _devices.value = emptyList() }

            val subnetPrefix = localIp.substringBeforeLast(".")
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())

            networkRepository.discoverDevices(subnetPrefix).collect { discovered ->
                val currentList = _devices.value
                val existing = currentList.find {
                    (it.macAddress != null && it.macAddress == discovered.macAddress) || it.ipAddress == discovered.ipAddress
                }

                val deviceToUpsert = if (existing != null) {
                    existing.copy(
                        status = "Online",
                        lastSeen = isoFormat.format(Date()),
                        ipAddress = discovered.ipAddress,
                        macAddress = discovered.macAddress?.uppercase() ?: existing.macAddress
                    )
                } else {
                    discovered.copy(
                        networkId = networkId,
                        macAddress = discovered.macAddress?.uppercase(),
                        status = "Online",
                        lastSeen = isoFormat.format(Date())
                    )
                }
                supabaseRepository.upsertDevice(deviceToUpsert)
                updateDeviceInList(deviceToUpsert)
            }
            _isScanning.value = false
        }
    }

    fun upsertManualDevice(name: String, ip: String, mac: String?, type: String, credId: String?) {
        // Spremeni v Dispatchers.IO, da preprečiš "No registered input channel" crash
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = SupabaseDevice(
                    networkId = networkId,
                    label = name,
                    ipAddress = ip,
                    macAddress = mac?.uppercase()?.ifBlank { null },
                    deviceType = type,
                    credentialId = credId,
                    status = "Online"
                )

                // To zdaj teče v ozadju in ne bo blokiralo zaslona
                supabaseRepository.upsertDevice(device)

                // Ko končaš, osveži seznam na glavni niti
                withContext(Dispatchers.Main) {
                    loadDevices()
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Manual upsert failed", e)
            }
        }
    }
}