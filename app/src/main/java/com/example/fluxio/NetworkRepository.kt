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
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class NetworkRepository {

    private val printerPorts = intArrayOf(9100, 631, 515)
    private val computerPorts = intArrayOf(135, 445, 22, 3389)

    /**
     * Reads the ARP table at /proc/net/arp to find the MAC address for a given IP.
     * NOTE: Restricted on Android 10+ (API 29+).
     */
    fun getMacAddress(ip: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return null // Standard ARP reading is blocked on Android 10+
        }
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?
            reader.readLine() // Skip header
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
     * Checks if a host is alive using multiple methods.
     * Tries TCP connection to common ports and standard reachability check.
     */
    private fun isHostAlive(host: String, timeout: Int): Boolean {
        // Method 1: Try common ports. 
        // A response (Success OR Refused) means the host is active.
        val portsToTry = intArrayOf(80, 445, 135, 22, 443, 8080, 5353, 5357)
        for (port in portsToTry) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 250)
                    return true 
                }
            } catch (e: ConnectException) {
                return true // Host rejected connection, so it IS alive
            } catch (_: SocketTimeoutException) {
                // Ignore and try next port
            } catch (_: Exception) {}
        }

        // Method 2: Standard isReachable (uses Port 7 echo or ICMP if rooted)
        try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
        } catch (_: Exception) {}
        
        // Method 3: Shell Ping as a last resort
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 1 $host")
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Real-time network discovery using channelFlow for high concurrency.
     * Emits devices as soon as they are found.
     */
    fun discoverDevices(subnetPrefix: String, timeoutMs: Int = 2000): Flow<SupabaseDevice> = channelFlow {
        Log.d("FluxioScan", "Starting scan on $subnetPrefix.0/24")
        (1..254).forEach { i ->
            launch {
                val host = "$subnetPrefix.$i"
                try {
                    if (isHostAlive(host, timeoutMs)) {
                        Log.d("FluxioScan", "Found host: $host")
                        
                        val isComputer = computerPorts.any { isPortOpen(host, it, 300) }
                        val isPrinter = if (!isComputer) printerPorts.any { isPortOpen(host, it, 300) } else false

                        val mac = getMacAddress(host)
                        val rawName = try {
                            val addr = InetAddress.getByName(host)
                            val canonical = addr.canonicalHostName
                            if (canonical != host && !canonical.isNullOrBlank()) canonical else null
                        } catch (_: Exception) { null }

                        val typeName = when {
                            isComputer -> DeviceType.PC.name
                            isPrinter -> DeviceType.PRINTER.name
                            else -> DeviceType.OTHER.name
                        }

                        val device = SupabaseDevice(
                            networkId = "", 
                            ipAddress = host,
                            name = rawName?.let { formatDeviceName(it, host) } ?: "Device $i",
                            originalName = rawName,
                            macAddress = mac,
                            status = "Online",
                            deviceType = typeName,
                            type = typeName
                        )
                        send(device)
                    }
                } catch (e: Exception) {
                    Log.e("FluxioScan", "Error scanning $host: ${e.message}")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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
