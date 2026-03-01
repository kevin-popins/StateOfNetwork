package com.stateofnetwork.data

import android.content.Context

class ServiceSetDomainsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("state_of_network_prefs", Context.MODE_PRIVATE)

    // Пользовательские домены, добавленные в набор
    private fun keyAdded(setId: String) = "service_set_domains_$setId"

    // Домены, исключенные пользователем из набора (из предустановленного списка)
    private fun keyRemoved(setId: String) = "service_set_removed_$setId"

    fun loadForSet(setId: String): List<String> {
        val raw = prefs.getString(keyAdded(setId), "") ?: ""
        return raw.split("\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun loadRemovedForSet(setId: String): List<String> {
        val raw = prefs.getString(keyRemoved(setId), "") ?: ""
        return raw.split("\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun saveForSet(setId: String, domains: List<String>) {
        val norm = domains.map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        prefs.edit().putString(keyAdded(setId), norm).apply()
    }

    fun saveRemovedForSet(setId: String, domains: List<String>) {
        val norm = domains.map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        prefs.edit().putString(keyRemoved(setId), norm).apply()
    }
}
