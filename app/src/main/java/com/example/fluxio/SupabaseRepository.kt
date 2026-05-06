package com.example.fluxio

import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SupabaseNetwork(
    @SerialName("id")
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

@Serializable
data class SupabaseDevice(
    @SerialName("id") val id: String? = null,
    @SerialName("network_id") val networkId: String,
    @SerialName("name") val name: String,
    @SerialName("ip_address") val ipAddress: String,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("status") val status: String = "Offline",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("pending_command") val pendingCommand: String? = null,
    @SerialName("ssh_username") val sshUsername: String? = null,
    @SerialName("ssh_password") val sshPassword: String? = null,
    @SerialName("device_type") val deviceType: String? = null,

    @Transient var type: String? = "PC",
    @Transient var originalName: String? = null
)

class SupabaseRepository {
    private val client = SupabaseInstance.client

    suspend fun getNetworks(): List<SupabaseNetwork> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        val networks = client.from("networks").select {
            filter {
                eq("user_id", userId)
            }
        }.decodeList<SupabaseNetwork>()

        return networks.map { network ->
            val count = if (network.id != null) {
                val devices = client.from("devices").select {
                    filter {
                        eq("network_id", network.id)
                    }
                }.decodeList<SupabaseDevice>()
                devices.size
            } else 0
            network.copy().apply { deviceCount = count }
        }
    }

    suspend fun upsertNetwork(name: String, userId: String): SupabaseNetwork {
        return try {
            client.from("networks").upsert(SupabaseNetwork(name = name, userId = userId)) {
                onConflict = "user_id,name"
                select()
            }.decodeSingle<SupabaseNetwork>()
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertNetwork failed for '$name'", e)
            throw e
        }
    }

    suspend fun getDevices(networkId: String): List<SupabaseDevice> {
        return client.from("devices").select {
            filter {
                eq("network_id", networkId)
            }
        }.decodeList<SupabaseDevice>()
    }

    /**
     * Requirement: Resolve "no unique or exclusion constraint matching" by using the explicit constraint name 'unique_device_per_network'.
     * We also ensure ID is null for new devices to avoid primary key interference.
     */
    suspend fun upsertDevice(device: SupabaseDevice) {
        try {
            val deviceToUpsert = if (device.id.isNullOrBlank()) device.copy(id = null) else device
            client.from("devices").upsert(deviceToUpsert) {
                onConflict = "unique_device_per_network"
            }
        } catch (e: Exception) {
            Log.e("Fluxio", "upsertDevice failed for ${device.ipAddress}", e)
            throw e
        }
    }

    suspend fun updatePendingCommand(deviceId: String, command: String?) {
        client.from("devices").update(mapOf("pending_command" to command)) {
            filter {
                eq("id", deviceId)
            }
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

    /**
     * Requirement: Use the explicit constraint name "unique_device_per_network" for bulk operations.
     */
    suspend fun saveDevices(devices: List<SupabaseDevice>) {
        if (devices.isNotEmpty()) {
            try {
                val devicesToUpsert = devices.map { if (it.id.isNullOrBlank()) it.copy(id = null) else it }
                client.from("devices").upsert(devicesToUpsert) {
                    onConflict = "unique_device_per_network"
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "saveDevices (bulk) failed", e)
                throw e
            }
        }
    }

    /**
     * Transactional Save: Network FIRST, then Devices. Rollback on failure.
     */
    suspend fun saveNetworkWithDevices(networkName: String, userId: String, devices: List<SupabaseDevice>) {
        var createdNetworkId: String? = null
        try {
            val network = upsertNetwork(networkName, userId)
            createdNetworkId = network.id ?: throw Exception("Failed to retrieve Network ID")

            val devicesToSave = devices.map {
                it.copy(
                    id = null,
                    networkId = createdNetworkId!!,
                    originalName = null,
                    macAddress = it.macAddress?.uppercase()
                )
            }
            saveDevices(devicesToSave)
        } catch (e: Exception) {
            Log.e("Fluxio", "Critical Save Error in saveNetworkWithDevices", e)
            createdNetworkId?.let { id ->
                try {
                    deleteNetwork(id)
                } catch (_: Exception) {}
            }
            throw e
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
