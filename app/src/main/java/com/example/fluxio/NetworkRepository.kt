package com.example.fluxio

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class NetworkRepository {

    private val printerPorts = intArrayOf(9100, 631)
    private val routerPorts = intArrayOf(53) // DNS

    fun getMacAddress(ip: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return null
        }
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?
            reader.readLine()
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (parts.size >= 4 && parts[0] == ip) {
                    val mac = parts[3].uppercase()
                    if (mac != "00:00:00:00:00:00" && mac.contains(":") && mac.length >= 11) {
                        return mac
                    }
                }
            }
            reader.close()
        } catch (_: Exception) {}
        return null
    }

    /**
     * Requirement 2: Filter Out "Ghost" Devices.
     * Only return true if the host is definitely reachable.
     */
    fun isHostAlive(host: String, timeout: Int): Boolean {
        // Try common ports
        val portsToTry = intArrayOf(80, 443, 22, 445, 135, 3389)
        for (port in portsToTry) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 250)
                    return true
                }
            } catch (_: Exception) {
                // If connection refused or timeout, continue to next port or ping
            }
        }

        // ICMP Ping
        try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
        } catch (_: Exception) {}

        // Fallback to system ping
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 1 $host")
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Requirement 2: Remove logic that auto-generates generic names like "PC 46".
     */
    fun discoverDevices(subnetPrefix: String, timeoutMs: Int = 2000): Flow<SupabaseDevice> = channelFlow {
        Log.d("FluxioScan", "Starting streaming scan on $subnetPrefix.0/24")
        (1..254).forEach { i ->
            launch {
                val host = "$subnetPrefix.$i"
                try {
                    if (isHostAlive(host, timeoutMs)) {
                        val mac = getMacAddress(host)?.uppercase()
                        val deviceType = identifyDeviceType(host)

                        val rawName = try {
                            val addr = InetAddress.getByName(host)
                            val canonical = addr.canonicalHostName
                            if (canonical != host && !canonical.isNullOrBlank()) canonical else null
                        } catch (_: Exception) { null }

                        // Use a fixed placeholder or formatted hostname, no auto-generated sequence numbers
                        val device = SupabaseDevice(
                            networkId = "",
                            ipAddress = host,
                            name = rawName?.let { formatDeviceName(it, host) } ?: "New Device",
                            originalName = rawName,
                            macAddress = mac,
                            status = "Online",
                            deviceType = deviceType.name,
                            type = deviceType.name
                        )
                        send(device)
                    }
                } catch (e: Exception) {
                    Log.e("FluxioScan", "Error scanning $host: ${e.message}")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun identifyDeviceType(host: String): DeviceType {
        if (printerPorts.any { isPortOpen(host, it, 300) }) return DeviceType.PRINTER
        val isRouterIp = host.endsWith(".1") || host.endsWith(".254")
        if (isRouterIp || isPortOpen(host, 53, 300)) return DeviceType.ROUTER
        return DeviceType.PC
    }

    private fun isPortOpen(host: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (_: Exception) { false }
    }

    private fun formatDeviceName(hostname: String, ip: String): String {
        if (hostname == ip || hostname.equals("unknown", ignoreCase = true)) {
            return "Generic Device"
        }
        return hostname.lowercase()
            .removeSuffix(".local").removeSuffix(".home").removeSuffix(".lan")
            .replaceFirstChar { it.uppercase() }
    }

    fun isHostReachable(host: String, timeout: Int): Boolean = isHostAlive(host, timeout)
}
