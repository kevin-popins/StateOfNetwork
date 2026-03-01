package com.stateofnetwork.data

import android.content.Context

/**
 * Хранит выбор пользователя по серверу для speedtest.
 * auto = автоматический выбор (с fallback по списку endpoint'ов).
 */
class SpeedEndpointStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("state_of_network_prefs", Context.MODE_PRIVATE)

    // Кэш последних успешных результатов по endpoint'ам.
    // Используется в Auto-выборе, чтобы не "терять" самый быстрый сервер из-за
    // флуктуаций коротких probe (slow-start/кэш/нагрузка в первые 200-500KB).
    private fun kDown(id: String) = "speed_last_down_" + id
    private fun kUp(id: String) = "speed_last_up_" + id
    private fun kTs(id: String) = "speed_last_ts_" + id

    fun load(): String {
        val raw = (prefs.getString("speed_endpoint", "auto") ?: "auto").trim().ifBlank { "auto" }
        if (raw == "auto") return "auto"

        val exists = Config.speedEndpoints.any { it.id == raw }
        if (exists) return raw

        // Если ранее был сохранен endpoint, которого больше нет в конфиге, откатываемся на auto.
        prefs.edit().putString("speed_endpoint", "auto").apply()
        return "auto"
    }

    fun save(id: String) {
        val v = id.trim().ifBlank { "auto" }
        prefs.edit().putString("speed_endpoint", v).apply()
    }

    fun recordLastResult(endpointId: String, downloadMbps: Double, uploadMbps: Double) {
        val id = endpointId.trim()
        if (id.isBlank()) return
        prefs.edit()
            .putFloat(kDown(id), downloadMbps.toFloat())
            .putFloat(kUp(id), uploadMbps.toFloat())
            .putLong(kTs(id), System.currentTimeMillis())
            .apply()
    }

    data class LastScore(val downloadMbps: Double, val uploadMbps: Double, val ts: Long)

    fun loadLastScores(ids: List<String>): Map<String, LastScore> {
        val out = LinkedHashMap<String, LastScore>()
        for (id in ids) {
            val ts = prefs.getLong(kTs(id), 0L)
            if (ts <= 0L) continue
            val d = prefs.getFloat(kDown(id), 0f).toDouble()
            val u = prefs.getFloat(kUp(id), 0f).toDouble()
            if (d <= 0.0 && u <= 0.0) continue
            out[id] = LastScore(d, u, ts)
        }
        return out
    }
}
