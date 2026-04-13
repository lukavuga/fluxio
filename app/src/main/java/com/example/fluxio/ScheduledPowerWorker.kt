package com.example.fluxio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduledPowerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val deviceId = inputData.getLong("DEVICE_ID", -1)
        val action = inputData.getString("ACTION") ?: "TOGGLE"

        if (deviceId == -1L) return@withContext Result.failure()

        val db = AppDatabase.getDatabase(applicationContext)
        val device = db.networkDao().getDeviceById(deviceId) ?: return@withContext Result.failure()

        try {
            when (device.type) {
                DeviceType.PC -> {
                    if (action == "ON") {
                        WakeOnLan.sendMagicPacket(device.macAddress)
                    } else {
                        // Remote shutdown logic placeholder
                    }
                }
                else -> {
                    // Generic action for TV, PRINTER, SMARTPHONE, etc.
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}