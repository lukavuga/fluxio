package com.example.fluxio

import android.content.Context
import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties
import java.util.UUID

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
    @SerialName("name") val label: String,
    @SerialName("ip_address") val ipAddress: String,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("status")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "Offline",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("credential_id") val credentialId: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    @Transient var type: String? = null,
    @Transient var originalName: String? = null
)

@OptIn(InternalSerializationApi::class)
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
    @SerialName("ssh_password") val sshPassword: String,
    @SerialName("is_enabled")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val isEnabled: Boolean = true
)

class SupabaseRepository(private val context: Context) {
    private val client = SupabaseInstance.client
    private val db = AppDatabase.getDatabase(context)
    private val networkDao = db.networkDao()
    private val sshDao = db.sshCredentialDao()

    // --- SSH Credentials ---

    suspend fun getSshCredentials(): List<SshCredential> = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext emptyList()
        if (isRealInternetAvailable()) {
            try {
                val cloud = client.from("ssh_credentials").select {
                    filter { eq("user_id", userId) }
                }.decodeList<SshCredential>()
                cloud.forEach { sshDao.upsertCredential(mapSshToLocal(it)) }
                return@withContext cloud
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud fetch error", e)
            }
        }
        sshDao.getCredentialsForUser(userId).map { mapSshToSupabase(it) }
    }

    suspend fun getCredentialById(id: String): SshCredential? = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                return@withContext client.from("ssh_credentials").select {
                    filter { eq("id", id) }
                }.decodeSingle<SshCredential>()
            } catch (e: Exception) {}
        }
        sshDao.getCredentialById(id)?.let { mapSshToSupabase(it) }
    }

    suspend fun upsertSshCredential(credential: SshCredential) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable()) {
            try {
                val result = client.from("ssh_credentials").upsert(credential) { select() }.decodeSingle<SshCredential>()
                sshDao.upsertCredential(mapSshToLocal(result))
                return@withContext
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud upsert error", e)
            }
        }
        val toSave = if (credential.id == null) credential.copy(id = "local_${UUID.randomUUID()}") else credential
        sshDao.upsertCredential(mapSshToLocal(toSave))
    }

    suspend fun deleteSshCredential(id: String) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                client.from("ssh_credentials").delete { filter { eq("id", id) } }
            } catch (e: Exception) {}
        }
        sshDao.deleteById(id)
    }

    suspend fun ensureDefaultCredentialExists(): Boolean = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext false
        if (isRealInternetAvailable()) {
            try {
                val existing = client.from("ssh_credentials").select {
                    filter {
                        eq("user_id", userId)
                        eq("label", "Fluxio Default")
                    }
                }.decodeList<SshCredential>()

                if (existing.isEmpty()) {
                    val defaultValue = "fluxio_user"
                    val encryptedPass = SecurityUtils.encrypt(defaultValue) ?: defaultValue
                    val defaultCred = SshCredential(
                        userId = userId,
                        label = "Fluxio Default",
                        sshUsername = defaultValue,
                        sshPassword = encryptedPass
                    )
                    val result = client.from("ssh_credentials").upsert(defaultCred) { select() }.decodeSingle<SshCredential>()
                    sshDao.upsertCredential(mapSshToLocal(result))
                    return@withContext true
                }
            } catch (e: Exception) {}
        }
        false
    }

    suspend fun executeSshCommand(ip: String, user: String, pass: String, command: String) = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            session.timeout = 5000
            session.connect()
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            channel.connect()
            delay(100)
            channel.disconnect()
        } finally {
            session?.disconnect()
        }
    }

    // --- Network & Device Operations ---

    suspend fun getNetworks(): List<SupabaseNetwork> = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable()) {
            try {
                val userId = client.auth.currentUserOrNull()?.id ?: return@withContext emptyList()
                val networks = client.from("networks").select {
                    filter { eq("user_id", userId) }
                }.decodeList<SupabaseNetwork>()

                networks.forEach { net ->
                    networkDao.upsertNetwork(SavedNetwork(name = net.name, supabaseId = net.id))
                }

                val networkIds = networks.mapNotNull { it.id }
                if (networkIds.isNotEmpty()) {
                    val counts = try {
                        client.from("devices").select(columns = Columns.list("network_id")) {
                            filter { isIn("network_id", networkIds) }
                        }.decodeList<DeviceNetworkId>()
                    } catch (e: Exception) { emptyList() }
                    val countsMap = counts.groupBy { it.networkId }.mapValues { it.value.size }
                    networks.forEach { it.deviceCount = countsMap[it.id] ?: 0 }
                }
                return@withContext networks
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud fetch networks failed", e)
            }
        }

        // Fetch from Room - Derived directly from SQL COUNT query
        networkDao.getAllNetworks().map {
            val net = SupabaseNetwork(id = it.supabaseId ?: "local_${it.id}", name = it.name)
            net.deviceCount = networkDao.getDeviceCountForNetwork(it.id)
            net
        }
    }

    suspend fun getNetwork(id: String): SupabaseNetwork? = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                return@withContext client.from("networks").select {
                    filter { eq("id", id) }
                }.decodeSingle<SupabaseNetwork>()
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud fetch network failed", e)
            }
        }

        val local = if (id.startsWith("local_")) {
            val lid = id.removePrefix("local_").toLongOrNull()
            lid?.let { networkDao.getNetworkById(it) }
        } else {
            networkDao.getNetworkBySupabaseId(id)
        }

        local?.let {
            val net = SupabaseNetwork(id = it.supabaseId ?: "local_${it.id}", name = it.name)
            net.deviceCount = networkDao.getDeviceCountForNetwork(it.id)
            net
        }
    }

    suspend fun saveFullNetwork(name: String, devices: List<SupabaseDevice>) = withContext(Dispatchers.IO) {
        // Preverimo internet preden sploh pipnemo Supabase Auth
        val online = isRealInternetAvailable()

        if (online) {
            try {
                // Ta del povzroča crash, če ni interneta, zato je zdaj varen znotraj 'online' bloka
                val userId = client.auth.currentUserOrNull()?.id
                if (userId != null) {
                    val net = client.from("networks").upsert(
                        SupabaseNetwork(name = name, userId = userId),
                        onConflict = "user_id,name"
                    ) { select() }.decodeSingle<SupabaseNetwork>()

                    val supabaseNetId = net.id!!
                    val devicesToSave = devices.map {
                        mapOf(
                            "network_id" to supabaseNetId,
                            "name" to it.label,
                            "ip_address" to it.ipAddress,
                            "mac_address" to it.macAddress,
                            "status" to it.status,
                            "device_type" to it.deviceType,
                            "last_seen" to it.lastSeen
                        )
                    }

                    if (devicesToSave.isNotEmpty()) {
                        client.from("devices").upsert(devicesToSave, onConflict = "mac_address")
                    }

                    networkDao.upsertNetwork(SavedNetwork(name = name, supabaseId = supabaseNetId))
                    devices.forEach { upsertDevice(it.copy(networkId = supabaseNetId)) }
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud save failed, falling back to local", e)
            }
        }

        // OFFLINE LOGIKA (Fallback)
        val existingNet = networkDao.getNetworkByName(name)
        val localNetId = existingNet?.id ?: networkDao.upsertNetwork(SavedNetwork(name = name))
        devices.forEach {
            upsertDevice(it.copy(networkId = existingNet?.supabaseId ?: "local_$localNetId"))
        }
    }

    suspend fun getDevices(networkId: String): List<SupabaseDevice> = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !networkId.startsWith("local_")) {
            try {
                val devices = client.from("devices").select {
                    filter { eq("network_id", networkId) }
                }.decodeList<SupabaseDevice>()

                val localNetId = networkDao.getLocalIdBySupabaseId(networkId)
                if (localNetId != null) {
                    devices.forEach { upsertDevice(it) }
                }
                return@withContext devices
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud fetch devices failed", e)
            }
        }

        val localNetId = if (networkId.startsWith("local_")) {
            networkId.removePrefix("local_").toLongOrNull()
        } else {
            networkDao.getLocalIdBySupabaseId(networkId)
        }

        if (localNetId != null) {
            return@withContext networkDao.getDevicesForNetwork(localNetId).map { mapDeviceToSupabase(it, networkId) }
        }
        emptyList()
    }

    suspend fun upsertDevice(device: SupabaseDevice) = withContext(Dispatchers.IO) {
        // Ponovno: Supabase klic samo, če smo online
        if (isRealInternetAvailable() && !device.networkId.startsWith("local_")) {
            try {
                val data = mapOf(
                    "network_id" to device.networkId,
                    "name" to device.label,
                    "ip_address" to device.ipAddress,
                    "mac_address" to device.macAddress,
                    "status" to device.status,
                    "last_seen" to device.lastSeen
                )
                client.from("devices").upsert(data, onConflict = "mac_address")
            } catch (e: Exception) {
                Log.e("Fluxio", "Cloud device upsert failed", e)
            }
        }

        // Lokalno shranjevanje (Room)
        val localNetId = if (device.networkId.startsWith("local_")) {
            device.networkId.removePrefix("local_").toLongOrNull()
        } else {
            networkDao.getLocalIdBySupabaseId(device.networkId)
        }

        if (localNetId != null) {
            // KLJUČNO: Preveri MAC v bazi, da dobiš obstoječi ID in preprečiš dvojnike
            val existing = if (device.macAddress != null) networkDao.getDeviceByMac(device.macAddress) else null
            val local = mapDeviceToLocal(device, localNetId).copy(id = existing?.id ?: 0)
            networkDao.upsertDevice(local)
        }
    }

    suspend fun syncDevice(device: SavedDevice) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable()) {
            try {
                val network = networkDao.getNetworkById(device.networkId)
                val supabaseNetId = network?.supabaseId
                if (supabaseNetId != null) {
                    val data = mapOf(
                        "mac_address" to device.macAddress,
                        "ip_address" to device.ip,
                        "name" to device.name,
                        "network_id" to supabaseNetId,
                        "status" to device.status,
                        "device_type" to device.deviceType,
                        "credential_id" to device.credentialId,
                        "last_seen" to device.lastSeen
                    )
                    client.from("devices").upsert(data, onConflict = "mac_address")
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e("Fluxio", "syncDevice failed", e)
            }
        }
        // Fallback to Room - also check for MAC conflict
        val existing = if (device.macAddress != null) networkDao.getDeviceByMac(device.macAddress) else null
        networkDao.upsertDevice(device.copy(id = existing?.id ?: device.id))
    }

    suspend fun deleteNetwork(id: String) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                client.from("networks").delete { filter { eq("id", id) } }
            } catch (e: Exception) {}
        }
        val local = if (id.startsWith("local_")) {
            val lid = id.removePrefix("local_").toLongOrNull()
            lid?.let { networkDao.getNetworkById(it) }
        } else {
            networkDao.getNetworkBySupabaseId(id)
        }
        local?.let { networkDao.deleteNetwork(it) }
    }

    suspend fun updateNetworkName(id: String, newName: String) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                client.from("networks").update(mapOf("name" to newName)) {
                    filter { eq("id", id) }
                }
            } catch (e: Exception) {}
        }
        val local = if (id.startsWith("local_")) {
            val lid = id.removePrefix("local_").toLongOrNull()
            lid?.let { networkDao.getNetworkById(it) }
        } else {
            networkDao.getNetworkBySupabaseId(id)
        }
        local?.let { networkDao.upsertNetwork(it.copy(name = newName)) }
    }

    suspend fun deleteDevice(id: String) = withContext(Dispatchers.IO) {
        if (isRealInternetAvailable() && !id.startsWith("local_")) {
            try {
                client.from("devices").delete { filter { eq("id", id) } }
            } catch (e: Exception) {}
        }
    }

    private fun mapDeviceToLocal(s: SupabaseDevice, localNetId: Long): SavedDevice {
        return SavedDevice(
            networkId = localNetId,
            name = s.label,
            ip = s.ipAddress,
            macAddress = s.macAddress,
            status = s.status,
            credentialId = s.credentialId,
            deviceType = s.deviceType,
            lastSeen = s.lastSeen
        )
    }

    private fun mapDeviceToSupabase(l: SavedDevice, netId: String): SupabaseDevice {
        return SupabaseDevice(
            networkId = netId,
            label = l.name,
            ipAddress = l.ip,
            macAddress = l.macAddress,
            status = l.status,
            credentialId = l.credentialId,
            deviceType = l.deviceType,
            lastSeen = l.lastSeen
        )
    }

    private fun mapSshToLocal(s: SshCredential): SavedSshCredential {
        return SavedSshCredential(
            id = s.id ?: "local_${UUID.randomUUID()}",
            userId = s.userId,
            label = s.label,
            sshUsername = s.sshUsername,
            sshPassword = s.sshPassword,
            isEnabled = s.isEnabled
        )
    }

    private fun mapSshToSupabase(l: SavedSshCredential): SshCredential {
        return SshCredential(
            id = if (l.id.startsWith("local_")) null else l.id,
            userId = l.userId,
            label = l.label,
            sshUsername = l.sshUsername,
            sshPassword = l.sshPassword,
            isEnabled = l.isEnabled
        )
    }

    fun observeDeviceChanges(networkId: String? = null): Flow<SupabaseDevice> {
        val channel = client.channel(networkId?.let { "devices_$it" } ?: "devices_all")
        return channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "devices"
            if (networkId != null && !networkId.startsWith("local_")) {
                filter("network_id", FilterOperator.EQ, networkId)
            }
        }.mapNotNull { action ->
            when (action) {
                is PostgresAction.Update -> action.decodeRecord<SupabaseDevice>()
                is PostgresAction.Insert -> action.decodeRecord<SupabaseDevice>()
                else -> null
            }
        }
    }

    // Funkcija za pravi ping (preveri, če internet dejansko dela)
    suspend fun isRealInternetAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 1500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Varna funkcija, ki preveri obstoj omrežja brez crashanja
    suspend fun safeCheckNetworkExists(ssid: String): SavedNetwork? = withContext(Dispatchers.IO) {
        // 1. Takojšen ping preverba
        val online = isRealInternetAvailable()

        if (online) {
            try {
                // 2. Uporabi kratek timeout (2-3 sekunde), da ne blokiraš UI
                return@withContext withTimeoutOrNull(2500) {
                    val response = client.from("networks")
                        .select { filter { eq("name", ssid) } }

                    try {
                        // Poskusi dekodirati v Supabase model
                        val cloud = response.decodeSingleOrNull<SupabaseNetwork>()
                        if (cloud != null) {
                            SavedNetwork(name = cloud.name, supabaseId = cloud.id)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                } ?: networkDao.getNetworkBySsid(ssid) // Če Supabase vrne null po timeoutu
            } catch (e: Exception) {
                Log.e("Fluxio", "Supabase check failed, falling back to Room", e)
                return@withContext networkDao.getNetworkBySsid(ssid)
            }
        } else {
            // 3. Če ni interneta, sploh ne poskušaj klicati Supabase
            return@withContext networkDao.getNetworkBySsid(ssid)
        }
    }
}
