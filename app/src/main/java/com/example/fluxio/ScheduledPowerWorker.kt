package com.example.fluxio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduledPowerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val supabaseRepository = SupabaseRepository()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val deviceId = inputData.getString("DEVICE_ID")
        val action = inputData.getString("ACTION") // "ON" or "OFF"

        if (deviceId == null) return@withContext Result.failure()

        try {
            // 1. Fetch the device using deviceId
            val device = supabaseRepository.getDeviceById(deviceId) 
                ?: return@withContext Result.failure()

            when (action) {
                "ON" -> {
                    // Keep WakeOnLan logic
                    device.macAddress?.let {
                        WakeOnLan.sendMagicPacket(it)
                        Log.d("FluxioWorker", "Sent WoL to ${device.ipAddress}")
                    }
                }
                "OFF" -> {
                    // Direct SSH execution logic
                    if (device.credentialId != null) {
                        // 2. Retrieve the SshCredential using device.credential_id
                        val credential = supabaseRepository.getCredentialById(device.credentialId)
                        if (credential != null) {
                            // 3. Decrypt the password
                            val decryptedPassword = SecurityUtils.decrypt(credential.sshPassword)
                            if (decryptedPassword != null) {
                                // 4. Execute SSH command
                                supabaseRepository.executeSshCommand(
                                    ip = device.ipAddress,
                                    user = credential.sshUsername,
                                    pass = decryptedPassword,
                                    command = "shutdown /s /t 0"
                                )
                                Log.d("FluxioWorker", "Sent SSH shutdown to ${device.ipAddress}")
                            } else {
                                Log.e("FluxioWorker", "Failed to decrypt password for device $deviceId")
                            }
                        } else {
                            Log.e("FluxioWorker", "Credential not found: ${device.credentialId}")
                        }
                    } else {
                        Log.w("FluxioWorker", "No credential profile linked for device $deviceId")
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("FluxioWorker", "Worker execution failed", e)
            Result.retry()
        }
    }
}
