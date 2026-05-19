package com.example.fluxio

import androidx.room.*

@Entity(tableName = "saved_networks")
data class SavedNetwork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val supabaseId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "saved_devices",
    foreignKeys = [
        ForeignKey(
            entity = SavedNetwork::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mac_address"], unique = true), // Requirement 1: Set mac_address as a Unique Index
        Index(value = ["networkId"])
    ]
)
data class SavedDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,
    val ip: String,
    @ColumnInfo(name = "mac_address") val macAddress: String? = null,
    val typeId: Int = 1,
    val statusId: Int = 1,
    val status: String = "Offline",
    val credentialId: String? = null,
    val deviceType: String? = null,
    val lastSeen: String? = null
)

@Entity(tableName = "saved_ssh_credentials")
data class SavedSshCredential(
    @PrimaryKey val id: String,
    val userId: String,
    val label: String,
    val sshUsername: String,
    val sshPassword: String,
    val isEnabled: Boolean = true
)
