package com.example.fluxio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class NetworkRepository {

    private val printerPorts = intArrayOf(9100, 631, 515)
    private val computerPorts = intArrayOf(135, 445, 22)

    /**
     * Reads the ARP table at /proc/net/arp to find the MAC address for a given IP.
     */
    fun getMacAddress(ip: String): String? {
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?
            // Skip the header line
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun checkPort(host: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isHostReachable(host: String, timeout: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
            
            val commonPorts = intArrayOf(135, 445, 80, 22, 443, 8080)
            commonPorts.any { port -> checkPort(host, port, timeout / 2) }
        } catch (_: Exception) { false }
    }

    /**
     * Scans the subnet for active devices, filtering for Computers and Printers.
     */
    suspend fun scanSubnet(subnetPrefix: String, timeoutMs: Int = 1000): List<SupabaseDevice> = withContext(Dispatchers.IO) {
        (1..254).map { i ->
            async {
                val host = "$subnetPrefix.$i"
                try {
                    // First check if the host is even alive
                    if (isHostReachable(host, timeoutMs)) {
                        
                        // Check for Computer ports
                        val isComputer = computerPorts.any { checkPort(host, it, 200) }
                        // Check for Printer ports
                        val isPrinter = if (!isComputer) printerPorts.any { checkPort(host, it, 200) } else false

                        if (isComputer || isPrinter) {
                            val mac = getMacAddress(host)
                            val rawName = try {
                                val addr = InetAddress.getByName(host)
                                if (addr.canonicalHostName != host) addr.canonicalHostName else "unknown"
                            } catch (_: Exception) { "unknown" }

                            val formattedName = formatDeviceName(rawName, host)
                            val deviceCategory = if (isComputer) "Computer" else "Printer"

                            SupabaseDevice(
                                networkId = "", // Assigned when saving
                                ipAddress = host,
                                name = formattedName,
                                macAddress = mac,
                                status = "Online",
                                deviceType = deviceCategory,
                                type = if (isComputer) DeviceType.PC.name else DeviceType.PRINTER.name
                            )
                        } else null
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun formatDeviceName(hostname: String, ip: String): String {
        if (hostname == ip || hostname.equals("unknown", ignoreCase = true)) {
            return "Generic Device"
        }
        return hostname.lowercase()
            .removeSuffix(".local").removeSuffix(".home").removeSuffix(".lan")
            .replaceFirstChar { it.uppercase() }
    }
}
