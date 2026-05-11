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

    fun saveLastAdultSync(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_ADULT_SYNC, timestamp).apply()
    }

    fun getLastAdultSync(): Long = prefs.getLong(KEY_LAST_ADULT_SYNC, 0L)

    fun saveCloudflareFamilyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CF_FAMILY, enabled).apply()
    }

    fun getCloudflareFamilyEnabled(): Boolean = prefs.getBoolean(KEY_CF_FAMILY, true)

    companion object {
        private const val KEY_DESIRED_ACTIVE = "desired_vpn_active"
        private const val KEY_CATEGORIES = "enabled_categories"
        private const val KEY_LAST_ADULT_SYNC = "last_adult_sync"
        private const val KEY_CF_FAMILY = "cf_family_enabled"
    }
}
