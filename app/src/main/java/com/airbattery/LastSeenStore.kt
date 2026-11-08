package com.airbattery

import android.content.Context

/** AirPods を最後に検出した時刻とスマホの位置を端末内に保存する */
class LastSeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("last_seen", Context.MODE_PRIVATE)

    data class LastSeen(val timeMillis: Long, val lat: Double?, val lon: Double?, val accuracyM: Float?)

    fun save(seen: LastSeen) {
        prefs.edit().apply {
            putLong("time", seen.timeMillis)
            if (seen.lat != null && seen.lon != null) {
                putString("lat", seen.lat.toString())
                putString("lon", seen.lon.toString())
                putFloat("acc", seen.accuracyM ?: -1f)
            }
        }.apply()
    }

    fun load(): LastSeen? {
        val time = prefs.getLong("time", 0L)
        if (time == 0L) return null
        val acc = prefs.getFloat("acc", -1f)
        return LastSeen(
            timeMillis = time,
            lat = prefs.getString("lat", null)?.toDoubleOrNull(),
            lon = prefs.getString("lon", null)?.toDoubleOrNull(),
            accuracyM = if (acc >= 0f) acc else null,
        )
    }
}
