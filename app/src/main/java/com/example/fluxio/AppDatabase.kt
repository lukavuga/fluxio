package com.example.fluxio

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "networks")
data class SavedNetwork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long,
    val deviceCount: Int
)

@Entity(
    tableName = "devices",
    indices = [
        Index(value = ["networkId", "macAddress"], unique = true),
        Index(value = ["networkId", "originalName"], unique = true)
    ],
    foreignKeys = [ForeignKey(
        entity = SavedNetwork::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SavedDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val ip: String,
    val name: String,
    val type: String,
    val originalName: String, // Persistent identifier (hostname at first discovery)
    val macAddress: String?, // Nullable to handle discovery failures
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false
) {
    @Ignore var status: String = if (isOnline) "Active" else "Inactive"
}

@Dao
interface NetworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: SavedDevice)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevices(devices: List<SavedDevice>)

    @Insert
    suspend fun insertNetwork(network: SavedNetwork): Long

    @Query("SELECT * FROM networks ORDER BY timestamp DESC")
    suspend fun getAllNetworks(): List<SavedNetwork>

    @Query("SELECT * FROM devices WHERE networkId = :netId ORDER BY ip ASC")
    fun getDevicesFlow(netId: Long): Flow<List<SavedDevice>>

    @Query("SELECT * FROM devices WHERE networkId = :netId")
    suspend fun getDevicesForNetwork(netId: Long): List<SavedDevice>

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
    suspend fun getNetworkById(id: Long): SavedNetwork?

    @Query("SELECT * FROM devices WHERE networkId = :netId AND originalName = :originalName LIMIT 1")
    suspend fun getDeviceByOriginalName(netId: Long, originalName: String): SavedDevice?

    @Query("SELECT * FROM devices WHERE networkId = :netId AND macAddress = :mac LIMIT 1")
    suspend fun getDeviceByMac(netId: Long, mac: String): SavedDevice?

    @Query("UPDATE devices SET isOnline = :online, lastSeen = :timestamp WHERE networkId = :netId")
    suspend fun markAllOffline(netId: Long, online: Boolean = false, timestamp: Long = System.currentTimeMillis())
}

@Database(entities = [SavedNetwork::class, SavedDevice::class], version = 7) // Incremented for nullable mac
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
