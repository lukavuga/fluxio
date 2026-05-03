package com.example.fluxio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    private const val WOL_PORT = 9

    /**
     * Sends a Magic Packet to wake up a device.
     */
    suspend fun sendMagicPacket(macAddress: String?) {
        if (macAddress.isNullOrBlank()) return
        
        withContext(Dispatchers.IO) {
            try {
                val macBytes = getMacBytes(macAddress)
                val bytes = ByteArray(6 + 16 * macBytes.size)
                for (i in 0..5) {
                    bytes[i] = 0xff.toByte()
                }
                var i = 6
                while (i < bytes.size) {
                    System.arraycopy(macBytes, 0, bytes, i, macBytes.size)
                    i += macBytes.size
                }

                // Broadcast to the entire local network
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(bytes, bytes.size, address, WOL_PORT)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(packet)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getMacBytes(macStr: String): ByteArray {
        val bytes = ByteArray(6)
        val hex = macStr.split(":", "-")
        if (hex.size != 6) throw IllegalArgumentException("Invalid MAC address: $macStr")
        try {
            for (i in 0..5) {
                bytes[i] = hex[i].toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid hex digit in MAC address.")
        }
        return bytes
    }
}
