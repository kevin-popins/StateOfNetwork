package com.stateofnetwork.data

import android.content.Context

class UserDomainsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("state_of_network_prefs", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString("user_domains", "") ?: ""
        return raw.split("\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun save(domains: List<String>) {
        val norm = domains.map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        prefs.edit().putString("user_domains", norm).apply()
    }
}
