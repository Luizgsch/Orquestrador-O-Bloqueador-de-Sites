package com.orquestrador.vpn

import android.content.Context

class VpnPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

    fun save(desiredActive: Boolean, categories: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_DESIRED_ACTIVE, desiredActive)
            .putStringSet(KEY_CATEGORIES, categories)
            .apply()
    }

    fun getDesiredActive(): Boolean = prefs.getBoolean(KEY_DESIRED_ACTIVE, false)

    fun getEnabledCategories(): Set<String> =
        prefs.getStringSet(KEY_CATEGORIES, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_DESIRED_ACTIVE = "desired_vpn_active"
        private const val KEY_CATEGORIES = "enabled_categories"
    }
}
