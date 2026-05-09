package com.orquestrador.security

import android.content.Context
import java.security.MessageDigest

class SecurityPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.getBoolean(KEY_PIN_SET, false)

    fun verifyPin(pin: String): Boolean {
        if (!isPinSet()) return false
        return hash(pin) == prefs.getString(KEY_PIN_HASH, null)
    }

    fun savePin(pin: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hash(pin))
            .putBoolean(KEY_PIN_SET, true)
            .commit()
    }

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SET = "is_pin_set"
    }
}
