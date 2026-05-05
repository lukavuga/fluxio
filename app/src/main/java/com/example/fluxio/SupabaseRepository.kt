package com.example.fluxio

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
    val deviceCount: Int = 0
)

@Serializable
data class SupabaseDevice(
    @SerialName("id")
    val id: String? = null,
    @SerialName("network_id")
    val networkId: String,
    @SerialName("name")
    val name: String,
    @SerialName("original_name")
    val originalName: String? = null,
    @SerialName("ip_address")
    val ipAddress: String,
    @SerialName("mac_address")
    val macAddress: String? = null,
    @SerialName("status")
    val status: String = "Offline",
    @SerialName("last_seen")
    val lastSeen: String? = null,
    @SerialName("pending_command")
    val pendingCommand: String? = null,
    @SerialName("ssh_username")
    val sshUsername: String? = null,
    @SerialName("ssh_password")
    val sshPassword: String? = null,
    @SerialName("device_type")
    val deviceType: String? = null,

    @Transient
    var type: String? = "OTHER"
)

class SupabaseRepository {
    private val client = SupabaseInstance.client

    suspend fun getNetworks(): List<SupabaseNetwork> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        return client.from("networks").select {
            filter {
                eq("user_id", userId)
            }
        }.decodeList<SupabaseNetwork>()
    }

    suspend fun createNetwork(name: String, userId: String): SupabaseNetwork {
        return client.from("networks").insert(SupabaseNetwork(name = name, userId = userId)) {
            select()
        }.decodeSingle<SupabaseNetwork>()
    }

    suspend fun getDevices(networkId: String): List<SupabaseDevice> {
        return client.from("devices").select {
            filter {
                eq("network_id", networkId)
            }
        }.decodeList<SupabaseDevice>()
    }

    suspend fun upsertDevice(device: SupabaseDevice) {
        client.from("devices").upsert(device)
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

    suspend fun saveDevices(devices: List<SupabaseDevice>) {
        if (devices.isNotEmpty()) {
            client.from("devices").upsert(devices)
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
