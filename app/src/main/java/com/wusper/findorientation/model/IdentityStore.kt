package com.wusper.findorientation.model

import android.content.Context
import java.util.UUID

class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("find_orientation", Context.MODE_PRIVATE)

    val deviceId: UUID
        get() {
            val existing = prefs.getString(KEY_ID, null)
            if (existing != null) return UUID.fromString(existing)
            val created = UUID.randomUUID()
            prefs.edit().putString(KEY_ID, created.toString()).apply()
            return created
        }

    var displayName: String
        get() = prefs.getString(KEY_NAME, null) ?: "裝置-${deviceId.toString().take(4).uppercase()}"
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim().take(24).ifEmpty { displayName }).apply()
        }

    var resident: Boolean
        get() = prefs.getBoolean(KEY_RESIDENT, false)
        set(value) { prefs.edit().putBoolean(KEY_RESIDENT, value).apply() }

    var geoFallback: Boolean
        get() = prefs.getBoolean(KEY_GEO, true)
        set(value) { prefs.edit().putBoolean(KEY_GEO, value).apply() }

    fun idBytes(): ByteArray {
        val id = deviceId
        val out = ByteArray(16)
        val ms = id.mostSignificantBits
        val ls = id.leastSignificantBits
        for (i in 0 until 8) {
            out[i] = (ms ushr (56 - 8 * i)).toByte()
            out[8 + i] = (ls ushr (56 - 8 * i)).toByte()
        }
        return out
    }

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_NAME = "display_name"
        private const val KEY_RESIDENT = "resident"
        private const val KEY_GEO = "geo_fallback"
    }
}
