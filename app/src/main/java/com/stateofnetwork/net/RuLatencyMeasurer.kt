package com.stateofnetwork.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

/**
 * Измеряет "пинг до РФ" максимально приближенно к RTT.
 *
 * Важный момент: HTTP/TLS-запросы измеряют не только задержку сети, но и работу сервера
 * (очереди, защита, гео-ограничения и т.д.). Для "пинга" это даёт ложные выбросы.
 *
 * Поэтому здесь измеряется время TCP connect до 443 порта (если не удаётся - 80).
 * Это ближе к тому, что обычно называют ping в прикладных спидтестах.
 */
class RuLatencyMeasurer(
    private val cancelSignal: () -> Boolean = { false },
    private val targets: List<Target> = listOf(
        Target("ok.ru", 443),
        Target("ya.ru", 443),
        Target("vk.com", 443),
        Target("gosuslugi.ru", 443)
    )
) {
    data class Target(val host: String, val port: Int)

    data class Result(
        val ms: Long?,
        val label: String,
        val debug: String? = null
    )

    suspend fun measure(): Result = withContext(Dispatchers.IO) {
        val perTargetSamples = 4
        val connectTimeoutMs = 1500

        var best: Pair<Long, Target>? = null
        val debugLines = mutableListOf<String>()

        for (t in targets) {
            if (cancelSignal()) break

            val samples = mutableListOf<Long>()
            repeat(perTargetSamples) {
                if (cancelSignal()) return@withContext Result(null, "—", "cancel")
                val r = tcpConnectRttMs(t.host, t.port, connectTimeoutMs)
                    ?: tcpConnectRttMs(t.host, 80, connectTimeoutMs)
                if (r != null) samples += r
            }

            if (samples.isNotEmpty()) {
                val median = median(samples)
                debugLines += "${t.host}: ${samples.joinToString()} (median=$median)"
                if (best == null || median < best!!.first) {
                    best = median to t
                }
            } else {
                debugLines += "${t.host}: fail"
            }
        }

        val chosen = best
        if (chosen == null) {
            Result(null, "—", debugLines.joinToString("; "))
        } else {
            val (ms, t) = chosen
            // защитимся от нулей/аномалий
            Result(max(1, ms), t.host, debugLines.joinToString("; "))
        }
    }

    private fun tcpConnectRttMs(host: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            if (cancelSignal()) return null

            val address = resolveBestIpv4(host) ?: return null
            if (cancelSignal()) return null

            Socket().use { sock ->
                sock.tcpNoDelay = true
                val start = System.nanoTime()
                sock.connect(InetSocketAddress(address, port), timeoutMs)
                val end = System.nanoTime()
                ((end - start) / 1_000_000L).coerceAtLeast(1)
            }
        }.getOrNull()
    }

    private fun resolveBestIpv4(host: String): InetAddress? {
        return runCatching {
            val all = InetAddress.getAllByName(host)
            // приоритет IPv4: в мобильных сетях/роутерах IPv6 часто даёт странные результаты
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }.getOrNull()
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            ((sorted[mid - 1] + sorted[mid]) / 2)
        }
    }
}
