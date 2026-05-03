package com.example.fluxio

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabaseNetwork(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("device_count")
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
    @SerialName("ip_address")
    val ipAddress: String,
    @SerialName("mac_address")
    val macAddress: String? = null,
    @SerialName("status")
    val status: String? = "Offline",
    @SerialName("pending_command")
    val pendingCommand: String? = null,
    @SerialName("last_seen")
    val lastSeen: String? = null,
    @SerialName("type")
    val type: String? = "OTHER"
)

class SupabaseRepository(
    supabaseUrl: String,
    supabaseKey: String
) {
    val client = createSupabaseClient(supabaseUrl, supabaseKey) {
        install(Postgrest)
        install(Realtime)
        install(Auth)
    }

    suspend fun getNetworks(): List<SupabaseNetwork> {
        return client.from("networks").select().decodeList<SupabaseNetwork>()
    }

    suspend fun createNetwork(name: String): SupabaseNetwork {
        return client.from("networks").insert(SupabaseNetwork(name = name)) {
            select()
        }.decodeSingle<SupabaseNetwork>()
    }

    suspend fun deleteNetwork(networkId: String) {
        client.from("networks").delete {
            filter {
                eq("id", networkId)
            }
        }
    }

    suspend fun getDevicesForNetwork(networkId: String): List<SupabaseDevice> {
        return client.from("devices").select {
            filter {
                eq("network_id", networkId)
            }
        }.decodeList<SupabaseDevice>()
    }

    suspend fun saveDevices(devices: List<SupabaseDevice>) {
        if (devices.isEmpty()) return
        client.from("devices").upsert(devices, onConflict = "mac_address")
    }

    suspend fun upsertDevice(device: SupabaseDevice) {
        client.from("devices").upsert(device, onConflict = "mac_address")
    }

    suspend fun updateDevice(device: SupabaseDevice) {
        client.from("devices").update(device) {
            filter {
                if (device.id != null) eq("id", device.id)
                else if (device.macAddress != null) eq("mac_address", device.macAddress)
            }
        }
    }

    suspend fun deleteDevice(macAddress: String) {
        client.from("devices").delete {
            filter {
                eq("mac_address", macAddress)
            }
        }
    }

    suspend fun sendCommand(macAddress: String, command: String) {
        client.from("devices").update(mapOf("pending_command" to command)) {
            filter {
                eq("mac_address", macAddress)
            }
        }
    }

    fun observeDeviceChanges(): Flow<SupabaseDevice> {
        val channel = client.channel("device_updates")
        return channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "devices"
        }.mapNotNull { action ->
            action.decodeRecord<SupabaseDevice>()
        }
    }
}
