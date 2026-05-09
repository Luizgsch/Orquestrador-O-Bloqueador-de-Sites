package com.orquestrador.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.orquestrador.overlay.OverlayManager

class VpnMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = VpnPreferences(context)
        if (!prefs.getDesiredActive()) return

        val needsPermission = VpnService.prepare(context) != null
        if (needsPermission) {
            OverlayManager.show(context)
            return
        }

        context.startForegroundService(
            Intent(context, OrquestradorVpnService::class.java)
                .setAction(OrquestradorVpnService.ACTION_START)
                .putExtra(
                    OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES,
                    prefs.getEnabledCategories().toTypedArray()
                )
        )
    }
}
