package com.example.fluxio

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "networks")
data class SavedNetwork(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timestamp: Long,
    val deviceCount: Int
)

@Entity(tableName = "devices",
    indices = [
        Index(value = ["networkId", "originalName"], unique = true),
        Index(value = ["networkId", "ip"], unique = true)
    ],
    foreignKeys = [ForeignKey(
        entity = SavedNetwork::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SavedDevice(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val networkId: Int,
    val ip: String,
    val name: String,
    val type: String,
    val originalName: String, // Persistent identifier (hostname at first discovery)
    val macAddress: String? = null
) {
    @Ignore var status: String = "Offline"
}

@Dao
interface NetworkDao {
    /**
     * Upserts a device. If a conflict occurs on originalName or ip, the existing record is replaced.
     * This enforces the "One Device = One IP" rule and handles DHCP changes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: SavedDevice)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevices(devices: List<SavedDevice>)

    @Insert
    suspend fun insertNetwork(network: SavedNetwork): Long

    @Query("SELECT * FROM networks ORDER BY timestamp DESC")
    suspend fun getAllNetworks(): List<SavedNetwork>

    @Query("SELECT * FROM devices WHERE networkId = :netId")
    fun getDevicesFlow(netId: Int): Flow<List<SavedDevice>>

    @Query("SELECT * FROM devices WHERE networkId = :netId")
    suspend fun getDevicesForNetwork(netId: Int): List<SavedDevice>

    @Delete
    suspend fun deleteNetwork(network: SavedNetwork)

    @Query("SELECT * FROM networks WHERE name = :name")
    suspend fun getNetworkByName(name: String): SavedNetwork?

    @Update
    suspend fun updateDevice(device: SavedDevice)

    @Delete
    suspend fun deleteDevice(device: SavedDevice)

    @Update
    suspend fun updateNetwork(network: SavedNetwork)

    @Query("SELECT * FROM networks WHERE id = :id")
    suspend fun getNetworkById(id: Int): SavedNetwork?

    @Query("SELECT * FROM devices WHERE networkId = :netId AND originalName = :originalName LIMIT 1")
    suspend fun getDeviceByOriginalName(netId: Int, originalName: String): SavedDevice?

    @Query("SELECT * FROM devices WHERE networkId = :netId AND ip = :ip LIMIT 1")
    suspend fun getDeviceByIp(netId: Int, ip: String): SavedDevice?

    @Query("UPDATE devices SET ip = :newIp WHERE networkId = :netId AND originalName = :originalName")
    suspend fun updateDeviceIpByOriginalName(netId: Int, originalName: String, newIp: String)
}

@Database(entities = [SavedNetwork::class, SavedDevice::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fluxio_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}