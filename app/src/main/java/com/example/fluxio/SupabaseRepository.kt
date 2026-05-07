package com.example.fluxio

import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SupabaseNetwork(
    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("name")
    val name: String,
    @SerialName("timestamp")
    val timestamp: String? = null,
    @Transient
    var deviceCount: Int = 0
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SupabaseDevice(
    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    @SerialName("network_id") val networkId: String,
    @SerialName("name") val name: String,
    @SerialName("ip_address") val ipAddress: String,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("status") val status: String = "Offline",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("credential_id") val credentialId: String? = null,
    @SerialName("device_type") val deviceType: String? = null,

    @Transient var type: String? = "PC",
    @Transient var originalName: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SshCredential(
    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("label") val label: String,
    @SerialName("ssh_username") val sshUsername: String,
    @SerialName("ssh_password") val sshPassword: String // Encrypted in DB
)

class SupabaseRepository {
    private val client = SupabaseInstance.client

    // --- Credential Operations ---

    suspend fun getSshCredentials(): List<SshCredential> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        return try {
            client.from("ssh_credentials").select {
                filter {
                    eq("user_id", userId)
                }
            }.decodeList<SshCredential>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getSshCredentials error", e)
            emptyList()
        }
    }

    suspend fun getCredentialById(id: String): SshCredential? {
        return try {
            client.from("ssh_credentials").select {
                filter { eq("id", id) }
            }.decodeSingle<SshCredential>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getCredentialById error: $id", e)
            null
        }
    }

    suspend fun upsertSshCredential(credential: SshCredential) {
        client.from("ssh_credentials").upsert(credential)
    }

    suspend fun deleteSshCredential(id: String) {
        client.from("ssh_credentials").delete {
            filter { eq("id", id) }
        }
    }

    // --- SSH Command ---

    suspend fun executeSshCommand(ip: String, user: String, pass: String, command: String) {
        withContext(Dispatchers.IO) {
            Log.d("SSH", "Connecting to $ip...")
            var session: Session? = null
            try {
                val jsch = JSch()
                session = jsch.getSession(user, ip, 22)
                session.setPassword(pass)
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.timeout = 10000
                session.connect()
                
                val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                channel.setCommand(command)
                channel.connect()
                delay(1500)
                channel.disconnect()
            } catch (e: Exception) {
                Log.e("SSH", "SSH Command execution failed for $ip", e)
                throw e
            } finally {
                session?.disconnect()
            }
        }
    }

    // --- Network & Device Operations ---

    suspend fun getNetworks(): List<SupabaseNetwork> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        val networks = try {
            client.from("networks").select {
                filter {
                    eq("user_id", userId)
                }
            }.decodeList<SupabaseNetwork>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getNetworks error", e)
            emptyList()
        }

        return networks.map { network ->
            val count = if (network.id != null) {
                try {
                    val devices = client.from("devices").select {
                        filter {
                            eq("network_id", network.id)
                        }
                    }.decodeList<SupabaseDevice>()
                    devices.size
                } catch (e: Exception) { 0 }
            } else 0
            network.copy().apply { deviceCount = count }
        }
    }

    suspend fun upsertNetwork(name: String, userId: String): SupabaseNetwork {
        return try {
            client.from("networks").upsert(
                value = SupabaseNetwork(name = name, userId = userId),
                onConflict = "user_id,name"
            ) {
                select()
            }.decodeSingle<SupabaseNetwork>()
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertNetwork failed for '$name'. Full error: $e", e)
            throw e
        }
    }

    suspend fun updateNetworkName(id: String, newName: String) {
        try {
            client.from("networks").update(
                mapOf("name" to newName)
            ) {
                filter { eq("id", id) }
            }
        } catch (e: Exception) {
            Log.e("Fluxio", "updateNetworkName failed", e)
            throw e
        }
    }

    suspend fun getDevices(networkId: String): List<SupabaseDevice> {
        return try {
            client.from("devices").select {
                filter {
                    eq("network_id", networkId)
                }
            }.decodeList<SupabaseDevice>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getDevices error", e)
            emptyList()
        }
    }

    suspend fun getDeviceById(deviceId: String): SupabaseDevice? {
        return try {
            client.from("devices").select {
                filter { eq("id", deviceId) }
            }.decodeSingle<SupabaseDevice>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getDeviceById error: $deviceId", e)
            null
        }
    }

    suspend fun upsertDevice(device: SupabaseDevice) {
        try {
            val deviceToUpsert = if (device.id.isNullOrBlank()) device.copy(id = null) else device
            client.from("devices").upsert(
                value = deviceToUpsert,
                onConflict = "network_id,ip_address"
            )
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertDevice failed for ${device.ipAddress}. Full error: $e", e)
            throw e
        }
    }

    suspend fun deleteNetwork(networkId: String) {
        client.from("networks").delete {
            filter {
                eq("id", networkId)
            }
        }
    }

    suspend fun deleteDevice(deviceId: String) {
        client.from("devices").delete {
            filter {
                eq("id", deviceId)
            }
        }
    }

    suspend fun saveDevices(devices: List<SupabaseDevice>) {
        if (devices.isNotEmpty()) {
            try {
                val devicesToUpsert = devices.map { if (it.id.isNullOrBlank()) it.copy(id = null) else it }
                client.from("devices").upsert(
                    values = devicesToUpsert,
                    onConflict = "network_id,ip_address"
                )
            } catch (e: Exception) {
                Log.e("Fluxio", "saveDevices (bulk) failed. Full error: $e", e)
                throw e
            }
        }
    }

    fun observeDeviceChanges(): Flow<SupabaseDevice> {
        val channel = client.channel("public:devices")
        return channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "devices"
        }.mapNotNull { action ->
            action.decodeRecord<SupabaseDevice>()
        }
    }
}
