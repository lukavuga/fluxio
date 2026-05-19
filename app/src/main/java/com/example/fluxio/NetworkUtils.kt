package com.example.fluxio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Robust check for actual internet reachability using a socket ping to Google DNS.
 * This is a top-level function accessible throughout the package.
 */
suspend fun isRealInternetAvailable(): Boolean = withContext(Dispatchers.IO) {
    try {
        val socket = Socket()
        val socketAddress = InetSocketAddress("8.8.8.8", 53)
        socket.connect(socketAddress, 1500) // 1.5s timeout
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}
