package com.orquestrador.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orquestrador.overlay.OverlayManager
import java.util.concurrent.TimeUnit

class VpnGuardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = VpnPreferences(applicationContext)
        if (!prefs.getDesiredActive()) return Result.success()
        if (OrquestradorVpnService.isRunning) return Result.success()

        val needsPermission = VpnService.prepare(applicationContext) != null
        if (needsPermission) {
            OverlayManager.show(applicationContext)
            return Result.success()
        }

        applicationContext.startForegroundService(
            Intent(applicationContext, OrquestradorVpnService::class.java)
                .setAction(OrquestradorVpnService.ACTION_START)
                .putExtra(
                    OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES,
                    prefs.getEnabledCategories().toTypedArray()
                )
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "vpn_guard"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VpnGuardWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
