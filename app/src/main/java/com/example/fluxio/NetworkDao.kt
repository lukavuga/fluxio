package com.example.fluxio

import androidx.room.*

@Dao
interface NetworkDao {
    // --- POIZVEDBE ZA OMREŽJA (saved_networks) ---

    @Query("SELECT * FROM saved_networks")
    suspend fun getAllNetworks(): List<SavedNetwork>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetwork(network: SavedNetwork): Long

    @Query("SELECT id FROM saved_networks WHERE supabaseId = :supabaseId LIMIT 1")
    suspend fun getLocalIdBySupabaseId(supabaseId: String): Long?

    @Query("SELECT * FROM saved_networks WHERE name = :name LIMIT 1")
    suspend fun getNetworkByName(name: String): SavedNetwork?

    @Query("SELECT * FROM saved_networks WHERE id = :id")
    suspend fun getNetworkById(id: Long): SavedNetwork?

    @Query("SELECT * FROM saved_networks WHERE supabaseId = :supabaseId LIMIT 1")
    suspend fun getNetworkBySupabaseId(supabaseId: String): SavedNetwork?

    // Popravljeno ime tabele iz 'networks' v 'saved_networks'
    @Query("SELECT * FROM saved_networks WHERE name = :ssid LIMIT 1")
    suspend fun getNetworkBySsid(ssid: String): SavedNetwork?

    @Delete
    suspend fun deleteNetwork(network: SavedNetwork)


    // --- POIZVEDBE ZA NAPRAVE (saved_devices) ---

    @Query("SELECT * FROM saved_devices WHERE networkId = :networkId")
    suspend fun getDevicesForNetwork(networkId: Long): List<SavedDevice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: SavedDevice)

    @Query("SELECT * FROM saved_devices WHERE mac_address = :mac LIMIT 1")
    suspend fun getDeviceByMac(mac: String): SavedDevice?

    @Query("SELECT COUNT(*) FROM saved_devices WHERE networkId = :networkId")
    suspend fun getDeviceCountForNetwork(networkId: Long): Int

    @Delete
    suspend fun deleteDevice(device: SavedDevice)

    @Query("DELETE FROM saved_devices WHERE networkId = :networkId")
    suspend fun deleteDevicesForNetwork(networkId: Long)
}