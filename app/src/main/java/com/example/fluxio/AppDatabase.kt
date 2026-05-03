package com.example.fluxio

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class DeviceType { PC, PHONE, PRINTER, TV, ROUTER, LAPTOP, OTHER }

@Entity(tableName = "networks")
data class SavedNetwork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceCount: Int = 0
)

@Entity(tableName = "types")
data class DeviceTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val typeName: String
)

@Entity(tableName = "statuses")
data class DeviceStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statusLabel: String
)

@Entity(
    tableName = "devices",
    indices = [Index(value = ["networkId", "macAddress"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = SavedNetwork::class, parentColumns = ["id"], childColumns = ["networkId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class SavedDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,
    val ip: String,
    val macAddress: String?,
    val typeId: Long,
    val statusId: Long,
    val originalName: String,
    val sshUsername: String? = null,
    val sshPassword: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val pendingCommand: String? = null,
    val supabaseId: String? = null
)

data class DeviceView(
    @Embedded val device: SavedDevice,
    @Relation(parentColumn = "typeId", entityColumn = "id") val typeEntity: DeviceTypeEntity?,
    @Relation(parentColumn = "statusId", entityColumn = "id") val statusEntity: DeviceStatusEntity?
)

@Dao
interface NetworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: SavedDevice)

    @Insert
    suspend fun insertNetwork(network: SavedNetwork): Long

    @Query("SELECT * FROM networks ORDER BY timestamp DESC")
    suspend fun getAllNetworks(): List<SavedNetwork>

    @Transaction
    @Query("SELECT * FROM devices WHERE networkId = :netId")
    fun getDevicesWithDetails(netId: Long): Flow<List<DeviceView>>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getDeviceById(id: Long): SavedDevice?

    @Transaction
    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getDeviceViewByDeviceId(id: Long): DeviceView?

    @Query("SELECT * FROM devices WHERE networkId = :netId AND originalName = :name LIMIT 1")
    suspend fun getDeviceByOriginalName(netId: Long, name: String): SavedDevice?

    @Query("SELECT macAddress FROM devices WHERE (originalName = :name OR ip = :ip) AND macAddress IS NOT NULL AND macAddress != '' LIMIT 1")
    suspend fun getMacForDevice(name: String, ip: String): String?

    @Update
    suspend fun updateDevice(device: SavedDevice)

    @Delete
    suspend fun deleteDevice(device: SavedDevice)

    @Delete
    suspend fun deleteNetwork(network: SavedNetwork)

    @Update
    suspend fun updateNetwork(network: SavedNetwork)

    @Query("SELECT * FROM networks WHERE name = :name")
    suspend fun getNetworkByName(name: String): SavedNetwork?

    @Query("SELECT * FROM networks WHERE id = :id")
    suspend fun getNetworkById(id: Long): SavedNetwork?

    @Query("SELECT * FROM devices WHERE networkId = :netId")
    suspend fun getDevicesForNetwork(netId: Long): List<SavedDevice>

    @Query("SELECT id FROM types WHERE typeName = :name")
    suspend fun getTypeIdByName(name: String): Long

    @Query("SELECT id FROM statuses WHERE statusLabel = :name")
    suspend fun getStatusIdByName(name: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertType(type: DeviceTypeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatus(status: DeviceStatusEntity): Long
    
    @Query("UPDATE devices SET pendingCommand = :command WHERE id = :deviceId")
    suspend fun updatePendingCommand(deviceId: Long, command: String?)

    @Query("UPDATE devices SET statusId = :statusId WHERE macAddress = :mac")
    suspend fun updateStatusByMac(mac: String, statusId: Long)
}

@Database(entities = [SavedNetwork::class, SavedDevice::class, DeviceTypeEntity::class, DeviceStatusEntity::class], version = 19)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fluxio_database")
                    .fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromDeviceType(value: DeviceType) = value.name
    @TypeConverter
    fun toDeviceType(value: String) = try { DeviceType.valueOf(value) } catch (e: Exception) { DeviceType.OTHER }
}
