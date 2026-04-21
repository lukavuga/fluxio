package com.example.fluxio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Properties

class PairingViewModel(private val db: AppDatabase) : ViewModel() {

    private val _pairingEvent = MutableSharedFlow<PairingEvent>()
    val pairingEvent = _pairingEvent.asSharedFlow()

    private var socket: DatagramSocket? = null

    /**
     * Starts listening for UDP broadcast messages on port 8888.
     * Payload expected: "FLUXIO_PAIR:MAC_ADDRESS"
     */
    fun startListening() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket(8888).apply {
                    reuseAddress = true
                }
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    
                    if (message.startsWith("FLUXIO_PAIR:")) {
                        val mac = message.substringAfter("FLUXIO_PAIR:").uppercase()
                        val ip = packet.address.hostAddress ?: ""
                        
                        // Emit event to UI to show discovery dialog
                        _pairingEvent.emit(PairingEvent.DeviceFound(ip, mac))
                    }
                }
            } catch (e: Exception) {
                if (socket?.isClosed == false) {
                    _pairingEvent.emit(PairingEvent.Error(e.message ?: "UDP Listener Error"))
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun stopListening() {
        socket?.close()
        socket = null
    }

    /**
     * Finalizes the pairing process by verifying SSH and saving the device to Room.
     */
    fun savePairedDevice(
        ip: String,
        mac: String?,
        customName: String,
        user: String?,
        pass: String?,
        networkId: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Verify SSH connection if credentials are provided
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    val verified = verifySshConnection(ip, user, pass)
                    if (!verified) {
                        _pairingEvent.emit(PairingEvent.Error("SSH Verification Failed! Please check credentials."))
                        return@launch
                    }
                }

                val typeId = db.networkDao().getTypeIdByName("PC")
                val statusId = db.networkDao().getStatusIdByName("Active")
                
                val device = SavedDevice(
                    networkId = networkId,
                    name = customName,
                    ip = ip,
                    macAddress = mac,
                    typeId = typeId,
                    statusId = statusId,
                    originalName = customName,
                    sshUsername = user,
                    sshPassword = pass
                )
                db.networkDao().upsertDevice(device)
                _pairingEvent.emit(PairingEvent.Success(customName))
            } catch (e: Exception) {
                _pairingEvent.emit(PairingEvent.Error("Failed to save device: ${e.message}"))
            }
        }
    }

    private suspend fun verifySshConnection(ip: String, user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            session.timeout = 5000
            session.connect()
            session.isConnected
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            session?.disconnect()
        }
    }

    sealed class PairingEvent {
        data class DeviceFound(val ip: String, val mac: String) : PairingEvent()
        data class Error(val message: String) : PairingEvent()
        data class Success(val name: String) : PairingEvent()
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
