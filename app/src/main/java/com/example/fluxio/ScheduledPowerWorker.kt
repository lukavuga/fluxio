package com.example.fluxio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduledPowerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val supabaseRepository = SupabaseRepository()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val deviceId = inputData.getString("DEVICE_ID")
        val action = inputData.getString("ACTION") ?: "TOGGLE"

        if (deviceId == null) return@withContext Result.failure()

        try {
            val networkId = inputData.getString("NETWORK_ID") ?: return@withContext Result.failure()
            val devices = supabaseRepository.getDevices(networkId)
            val device = devices.find { it.id == deviceId } ?: return@withContext Result.failure()

            val typeName = device.type?.uppercase() ?: "OTHER"
            when (typeName) {
                "PC", "LAPTOP" -> {
                    if (action == "ON") {
                        device.macAddress?.let { WakeOnLan.sendMagicPacket(it) }
                    } else {
                        supabaseRepository.updatePendingCommand(deviceId, "shutdown")
                    }
                }
                else -> {
                    // Generic action
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
