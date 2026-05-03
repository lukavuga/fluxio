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

    fun isHostReachable(host: String, timeout: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            if (addr.isReachable(timeout)) return true
            
            val commonPorts = intArrayOf(135, 445, 80, 22, 443, 8080)
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

    /**
     * Scans the subnet for active devices.
     */
    suspend fun scanSubnet(subnetPrefix: String, timeoutMs: Int = 1000): List<SupabaseDevice> = withContext(Dispatchers.IO) {
        (1..254).map { i ->
            async {
                val host = "$subnetPrefix.$i"
                try {
                    if (isHostReachable(host, timeoutMs)) {
                        val mac = getMacAddress(host)
                        
                        val rawName = try {
                            val addr = InetAddress.getByName(host)
                            if (addr.canonicalHostName != host) addr.canonicalHostName else "unknown"
                        } catch (_: Exception) { "unknown" }
                        
                        val formattedName = formatDeviceName(rawName, host)
                        val type = identifyDeviceType(formattedName, host)
                        
                        SupabaseDevice(
                            networkId = "", // Assigned when saving
                            ipAddress = host,
                            name = formattedName,
                            macAddress = mac,
                            status = "Online",
                            type = type.name
                        )
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

    fun identifyDeviceType(hostname: String, ip: String): DeviceType {
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
}
