package com.example.fluxio

import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.upsert
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
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
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

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Serializable
data class SupabaseDevice(
    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    @SerialName("network_id") val networkId: String,
    @SerialName("name") val name: String,
    @SerialName("ip_address") val ipAddress: String,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("status") 
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "Offline",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("credential_id") val credentialId: String? = null,
    @SerialName("device_type") val deviceType: String? = null,

    @Transient var type: String? = "PC",
    @Transient var originalName: String? = null
)

@Serializable
private data class DeviceNetworkId(@SerialName("network_id") val networkId: String)

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Serializable
data class SshCredential(
    @SerialName("id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("label") val label: String,
    @SerialName("ssh_username") val sshUsername: String,
    @SerialName("ssh_password") val sshPassword: String, // Encrypted in DB
    @SerialName("is_enabled") 
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val isEnabled: Boolean = true
)

class SupabaseRepository {
    private val client = SupabaseInstance.client

    companion object {
        private var networksCache: List<SupabaseNetwork>? = null
        private val devicesCache = mutableMapOf<String, List<SupabaseDevice>>()
        private var sshCredentialsCache: List<SshCredential>? = null
    }

    // --- Credential Operations ---

    suspend fun getSshCredentials(forceRefresh: Boolean = false): List<SshCredential> = withContext(Dispatchers.IO) {
        if (!forceRefresh && sshCredentialsCache != null) return@withContext sshCredentialsCache!!
        
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext emptyList()
        return@withContext try {
            val result = client.from("ssh_credentials").select {
                filter {
                    eq("user_id", userId)
                }
            }.decodeList<SshCredential>()
            sshCredentialsCache = result
            result
        } catch (e: Exception) {
            Log.e("Fluxio", "getSshCredentials error", e)
            emptyList()
        }
    }

    suspend fun getCredentialById(id: String): SshCredential? = withContext(Dispatchers.IO) {
        sshCredentialsCache?.find { it.id == id }?.let { return@withContext it }
        
        return@withContext try {
            client.from("ssh_credentials").select {
                filter { eq("id", id) }
            }.decodeSingle<SshCredential>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getCredentialById error: $id", e)
            null
        }
    }

    suspend fun upsertSshCredential(credential: SshCredential) = withContext(Dispatchers.IO) {
        client.from("ssh_credentials").upsert(credential)
        sshCredentialsCache = null
    }

    suspend fun deleteSshCredential(id: String) = withContext(Dispatchers.IO) {
        client.from("ssh_credentials").delete {
            filter { eq("id", id) }
        }
        sshCredentialsCache = null
    }

    suspend fun ensureDefaultCredentialExists(): Boolean = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext false
        try {
            val existing = client.from("ssh_credentials").select {
                filter {
                    eq("user_id", userId)
                    eq("label", "Fluxio Default")
                }
            }.decodeList<SshCredential>()

            if (existing.isEmpty()) {
                val defaultValue = "fluxio_user"
                // Standard: Username "fluxio_user" and Password "fluxio_user", encrypted.
                val encryptedPass = SecurityUtils.encrypt(defaultValue) ?: defaultValue
                
                val defaultCred = SshCredential(
                    userId = userId,
                    label = "Fluxio Default",
                    sshUsername = defaultValue,
                    sshPassword = encryptedPass,
                    isEnabled = true
                )
                
                client.from("ssh_credentials").upsert(defaultCred)
                sshCredentialsCache = null
                Log.d("Fluxio", "Default SSH profile created.")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("Fluxio", "Error initializing default credential", e)
        }
        return@withContext false
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
                session.timeout = 3000
                session.connect()
                
                val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                channel.setCommand(command)
                channel.connect()
                delay(50) 
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

    suspend fun getNetworks(forceRefresh: Boolean = false): List<SupabaseNetwork> = withContext(Dispatchers.IO) {
        if (!forceRefresh && networksCache != null) return@withContext networksCache!!

        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext emptyList()
        
        val networks = try {
            client.from("networks").select {
                filter {
                    eq("user_id", userId)
                }
            }.decodeList<SupabaseNetwork>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getNetworks error", e)
            return@withContext emptyList()
        }

        if (networks.isEmpty()) {
            networksCache = emptyList()
            return@withContext emptyList()
        }

        val networkIds = networks.mapNotNull { it.id }
        val countsMap = try {
            val deviceNetworkIds = client.from("devices").select(columns = Columns.list("network_id")) {
                filter {
                    isIn("network_id", networkIds)
                }
            }.decodeList<DeviceNetworkId>()
            
            deviceNetworkIds.groupBy { it.networkId }.mapValues { it.value.size }
        } catch (e: Exception) {
            Log.e("Fluxio", "getNetworks batch count error", e)
            emptyMap()
        }

        val result = networks.map { network ->
            network.copy().apply { 
                deviceCount = countsMap[network.id] ?: 0 
            }
        }
        networksCache = result
        result
    }

    suspend fun upsertNetwork(name: String, userId: String): SupabaseNetwork = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = client.from("networks").upsert(
                value = SupabaseNetwork(name = name, userId = userId),
                onConflict = "user_id,name"
            ).decodeSingle<SupabaseNetwork>()
            networksCache = null
            result
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertNetwork failed", e)
            throw e
        }
    }

    suspend fun updateNetworkName(id: String, newName: String) = withContext(Dispatchers.IO) {
        try {
            client.from("networks").update(
                mapOf("name" to newName)
            ) {
                filter { eq("id", id) }
            }
            networksCache = null
        } catch (e: Exception) {
            Log.e("Fluxio", "updateNetworkName failed", e)
            throw e
        }
    }

    suspend fun getDevices(networkId: String, forceRefresh: Boolean = false): List<SupabaseDevice> = withContext(Dispatchers.IO) {
        if (!forceRefresh && devicesCache.containsKey(networkId)) return@withContext devicesCache[networkId]!!

        return@withContext try {
            val result = client.from("devices").select {
                filter {
                    eq("network_id", networkId)
                }
            }.decodeList<SupabaseDevice>()
            devicesCache[networkId] = result
            result
        } catch (e: Exception) {
            Log.e("Fluxio", "getDevices error", e)
            emptyList()
        }
    }

    suspend fun getDeviceById(deviceId: String): SupabaseDevice? = withContext(Dispatchers.IO) {
        return@withContext try {
            client.from("devices").select {
                filter { eq("id", deviceId) }
            }.decodeSingle<SupabaseDevice>()
        } catch (e: Exception) {
            Log.e("Fluxio", "getDeviceById error: $deviceId", e)
            null
        }
    }

    suspend fun upsertDevice(device: SupabaseDevice) = withContext(Dispatchers.IO) {
        try {
            val deviceToUpsert = if (device.id.isNullOrBlank()) device.copy(id = null) else device
            client.from("devices").upsert(
                value = deviceToUpsert,
                onConflict = "network_id,ip_address"
            )
            devicesCache.remove(device.networkId)
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertDevice failed", e)
            throw e
        }
    }

    suspend fun deleteNetwork(networkId: String) = withContext(Dispatchers.IO) {
        client.from("networks").delete {
            filter {
                eq("id", networkId)
            }
        }
        networksCache = null
        devicesCache.remove(networkId)
    }

    suspend fun deleteDevice(deviceId: String) = withContext(Dispatchers.IO) {
        client.from("devices").delete {
            filter {
                eq("id", deviceId)
            }
        }
        devicesCache.clear() 
    }

    suspend fun saveDevices(devices: List<SupabaseDevice>) = withContext(Dispatchers.IO) {
        if (devices.isNotEmpty()) {
            try {
                val devicesToUpsert = devices.map { if (it.id.isNullOrBlank()) it.copy(id = null) else it }
                client.from("devices").upsert(
                    values = devicesToUpsert,
                    onConflict = "network_id,ip_address"
                )
                devices.forEach { devicesCache.remove(it.networkId) }
                networksCache = null
            } catch (e: Exception) {
                Log.e("Fluxio", "saveDevices (bulk) failed", e)
                throw e
            }
        }
    }

    fun observeDeviceChanges(channelName: String = "devices"): Flow<SupabaseDevice> {
        val myChannel = client.channel(channelName)
        val changes = myChannel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "devices"
        }
        return changes.mapNotNull { it.decodeRecord<SupabaseDevice>() }
    }
}
