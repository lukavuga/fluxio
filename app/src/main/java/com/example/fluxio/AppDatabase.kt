package com.example.fluxio

import android.content.Context
import androidx.room.*

@Entity(tableName = "networks")
data class SavedNetwork(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timestamp: Long,
    val deviceCount: Int
)

@Entity(tableName = "devices",
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
    val type: String
) {
    @Ignore var status: String = "Inactive"
}

@Dao
interface NetworkDao {
    @Insert
    suspend fun insertNetwork(network: SavedNetwork): Long

    @Insert
    suspend fun insertDevices(devices: List<SavedDevice>)

    @Query("SELECT * FROM networks ORDER BY timestamp DESC")
    suspend fun getAllNetworks(): List<SavedNetwork>

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

    @Query("SELECT * FROM devices WHERE networkId = :netId AND name = :deviceName LIMIT 1")
    suspend fun getDeviceByName(netId: Int, deviceName: String): SavedDevice?
}

@Database(entities = [SavedNetwork::class, SavedDevice::class], version = 1)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}