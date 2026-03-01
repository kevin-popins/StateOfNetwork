package com.stateofnetwork.net

import com.stateofnetwork.data.SpeedEndpointMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class SpeedProgress(
    val phase: String, // latency, download, upload
    val mbps: Double,
    val elapsedMs: Long
)

data class SpeedTestOutcome(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Long,
    val jitterMs: Long?,
    val bytesDown: Long,
    val bytesUp: Long,
    val durationMs: Long,
    val status: String,
    val error: String?,
    val endpointHost: String,
    val endpointIp: String?
)

data class SpeedProbeOutcome(
    val ok: Boolean,
    val latencyMs: Long,
    val downloadMbps: Double,
    val bytesRead: Long,
    val error: String?,
    val endpointHost: String,
    val endpointIp: String?
)

data class UploadProbeOutcome(
    val ok: Boolean,
    val latencyMs: Long,
    val uploadMbps: Double,
    val bytesSent: Long,
    val error: String?,
    val endpointHost: String,
    val endpointIp: String?
)

class SpeedTester {

    /**
     * На части мобильных сетей в РФ IPv6 может быть «номинально» доступен,
     * но фактически не маршрутизироваться. OkHttp по умолчанию попробует IPv6 первым,
     * что приводит к таймаутам на otherwise рабочем endpoint'е.
     *
     * Решение: отдаём IPv4-адреса первыми, сохраняя IPv6 как fallback.
     */
    private val preferIpv4Dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isEmpty()) all else v4 + all.filterNot { it is Inet4Address }
        }
    }

    private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(25, TimeUnit.SECONDS)
    .dns(preferIpv4Dns)
    .build()

/**
 * Cloudflare speedtest endpoint historically works best via HTTP/1.1.
 * Некоторые сети/прокси ломают HTTP/2 для длительных потоков, что приводит к "залипаниям" и таймаутам.
 * Поэтому для Cloudflare используем отдельный клиент с принудительным HTTP/1.1 и без reuse соединений.
 */
private val cloudflareClient = client.newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .retryOnConnectionFailure(true)
    .build()

private fun httpClientFor(mode: SpeedEndpointMode): OkHttpClient =
    if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) cloudflareClient else client

private fun cfUrl(base: String, bytes: Int, sessionId: String): String {
    val sep = if (base.contains("?")) "&" else "?"
    return base + sep + "bytes=" + bytes + "&sessionId=" + sessionId + "&measId=" + System.nanoTime()
}
/**
     * Короткий бенчмарк для выбора сервера в режиме "Авто".
     *
     * Простые probe'ы по фиксированному объёму могут ранжировать серверы нестабильно
     * из-за стартового "рывка" или краткосрочной перегрузки. Здесь мы делаем короткий
     * реальный download+upload таймбоксом и выбираем сервер по максимальной сумме скоростей.
     */
    suspend fun quickBenchmark(
        endpointDown: String,
        endpointUp: String,
        mode: SpeedEndpointMode,
        durationMsPerDir: Long,
        parallelism: Int,
        cancelSignal: () -> Boolean
    ): SpeedTestOutcome = withContext(Dispatchers.IO) {
        val host = try { URI(endpointDown).host ?: "unknown" } catch (_: Exception) { "unknown" }
        val ip = try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        } catch (_: Exception) {
            null
        }

        val errors = mutableListOf<String>()

        // Короткая оценка latency нужна только для полноты, но не должна доминировать в выборе.
        val latencySamples = runCatching {
            measureLatency(endpointDown, mode = mode, samples = 3, cancelSignal = cancelSignal)
        }.getOrElse {
            listOf(0L)
        }
        val latency = latencySamples.sorted()[latencySamples.size / 2]
        val jitter = computeJitter(latencySamples)

        val down = runCatching {
            measureDownloadStreaming(
                endpointDown = endpointDown,
                mode = mode,
                durationMs = durationMsPerDir,
                parallelism = parallelism,
                cancelSignal = cancelSignal,
                onProgress = { _, _ -> }
            )
        }.getOrElse { e ->
            errors += (e.javaClass.simpleName + ": " + (e.message ?: ""))
            DirResult(bytes = 0L, mbps = 0.0)
        }

        // Если download не удалось, upload в рамках бенчмарка смысла не имеет.
        if (down.bytes <= 0L) {
            return@withContext SpeedTestOutcome(
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                latencyMs = latency,
                jitterMs = jitter,
                bytesDown = 0L,
                bytesUp = 0L,
                durationMs = durationMsPerDir,
                status = "error",
                error = (errors + "Download failed").distinct().joinToString(" | "),
                endpointHost = host,
                endpointIp = ip
            )
        }

        val up = runCatching {
            measureUploadStreaming(
                endpointUp = endpointUp,
                mode = mode,
                durationMs = durationMsPerDir,
                parallelism = parallelism,
                cancelSignal = cancelSignal,
                onProgress = { _, _ -> }
            )
        }.getOrElse { e ->
            errors += (e.javaClass.simpleName + ": " + (e.message ?: ""))
            DirResult(bytes = 0L, mbps = 0.0)
        }

        val status = when {
            cancelSignal() -> "aborted"
            errors.isEmpty() -> "ok"
            (down.bytes > 0L || up.bytes > 0L) -> "partial"
            else -> "error"
        }

        SpeedTestOutcome(
            downloadMbps = down.mbps,
            uploadMbps = up.mbps,
            latencyMs = latency,
            jitterMs = jitter,
            bytesDown = down.bytes,
            bytesUp = up.bytes,
            durationMs = durationMsPerDir * 2,
            status = status,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" | "),
            endpointHost = host,
            endpointIp = ip
        )
    }


    /**
     * Быстрый "probe" для автоматического выбора сервера.
     *
     * Цель: за короткое время (обычно 1–3 секунды) понять:
     * 1) доступен ли endpoint,
     * 2) примерную задержку до ответа (latency до headers),
     * 3) примерную скорость на небольшом кусочке download.
     *
     * Используется только для подбора сервера в режиме "Авто" и не заменяет полноценный тест.
     */
    suspend fun probeDownload(
        endpointDown: String,
        mode: SpeedEndpointMode,
        bytesToRead: Int = 256 * 1024,
        timeoutMs: Long = 2500L,
        cancelSignal: (() -> Boolean)? = null
    ): SpeedProbeOutcome = withContext(Dispatchers.IO) {
        val host = try { URI(endpointDown).host ?: "unknown" } catch (_: Exception) { "unknown" }
        val ip = try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        } catch (_: Exception) {
            null
        }

        val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
            System.currentTimeMillis().toString() + "_" + System.nanoTime()
        } else ""

        val req = when (mode) {
            SpeedEndpointMode.CLOUDFLARE_BYTES -> {
                Request.Builder()
                    .url(cfUrl(endpointDown, bytesToRead, sessionId))
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("Connection", "close")
                    .get()
                    .build()
            }
            SpeedEndpointMode.GENERIC_FILE_POST,
            SpeedEndpointMode.GENERIC_FILE_PUT -> {
                Request.Builder()
                    .url(endpointDown.addAntiCacheParam())
                    .get()
                    .header("Cache-Control", "no-cache")
                    .header("Range", "bytes=0-${bytesToRead - 1}")
                    .build()
            }
        }

        val call = httpClientFor(mode).newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

        val t0 = System.nanoTime()
        try {
            call.execute().use { resp ->
                val tHeaders = System.nanoTime()
                if (!resp.isSuccessful) {
                    return@withContext SpeedProbeOutcome(
                        ok = false,
                        latencyMs = (tHeaders - t0) / 1_000_000L,
                        downloadMbps = 0.0,
                        bytesRead = 0L,
                        error = "HTTP ${resp.code}",
                        endpointHost = host,
                        endpointIp = ip
                    )
                }

                val body = resp.body ?: return@withContext SpeedProbeOutcome(
                    ok = false,
                    latencyMs = (tHeaders - t0) / 1_000_000L,
                    downloadMbps = 0.0,
                    bytesRead = 0L,
                    error = "Empty body",
                    endpointHost = host,
                    endpointIp = ip
                )

                val buf = ByteArray(32 * 1024)
                var readTotal = 0L
                val stream = body.byteStream()

                // Небольшой "прогрев" для более устойчивой оценки скорости:
                // первые байты могут прийти рывком (кеш/slow-start), а нас интересует ближе к устойчивому значению.
                val warmupBytes = minOf(64 * 1024, bytesToRead / 4)
                var warmupReachedAt: Long? = null
                var warmupRead = 0L

                while (readTotal < bytesToRead && (cancelSignal?.invoke() != true)) {
                    val need = minOf(buf.size.toLong(), (bytesToRead - readTotal).toLong()).toInt()
                    val r = stream.read(buf, 0, need)
                    if (r <= 0) break
                    readTotal += r.toLong()

                    if (warmupReachedAt == null) {
                        warmupRead += r.toLong()
                        if (warmupRead >= warmupBytes) {
                            warmupReachedAt = System.nanoTime()
                        }
                    }
                }

                val tEnd = System.nanoTime()
                val latencyMs = (tHeaders - t0) / 1_000_000L

                val tDataStart = warmupReachedAt ?: tHeaders
                val dataMs = ((tEnd - tDataStart) / 1_000_000L).coerceAtLeast(1L)

                val effectiveBytes = (readTotal - warmupBytes.toLong()).coerceAtLeast(0L).toDouble()
                val bps = (effectiveBytes * 8.0) / (dataMs / 1000.0)
                val mbps = bps / 1_000_000.0

                // Считаем probe успешным, если прочитали "достаточно" данных.
                // Для коротких probe (например 96KB в авто-проверке) требуем прочитать весь запрошенный объём,
                // чтобы не получать ложный "Too few bytes" из-за слишком высокого порога.
                val minOkBytes = minOf(bytesToRead, 128 * 1024)
                val ok = readTotal >= minOkBytes

                SpeedProbeOutcome(
                    ok = ok,
                    latencyMs = latencyMs,
                    downloadMbps = if (ok) mbps else 0.0,
                    bytesRead = readTotal,
                    error = if (ok) null else "Too few bytes ($readTotal)",
                    endpointHost = host,
                    endpointIp = ip
                )
            }
        } catch (e: Exception) {
            SpeedProbeOutcome(
                ok = false,
                latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L),
                downloadMbps = 0.0,
                bytesRead = 0L,
                error = e.javaClass.simpleName + ": " + (e.message ?: ""),
                endpointHost = host,
                endpointIp = ip
            )
        } finally {
            if (cancelSignal?.invoke() == true) call.cancel()
        }
    }

    /**
     * Быстрый probe upload-части для режима "Авто".
     *
     * Цель: убедиться, что endpoint реально принимает upload-запросы и не "молчит".
     * Отправляем небольшой payload (по умолчанию 64KB) и измеряем:
     * 1) latency до ответа (headers),
     * 2) примерную скорость на этом маленьком куске.
     */
    suspend fun probeUpload(
        endpointUp: String,
        mode: SpeedEndpointMode,
        bytesToSend: Int = 64 * 1024,
        timeoutMs: Long = 2500L,
        cancelSignal: (() -> Boolean)? = null
    ): UploadProbeOutcome = withContext(Dispatchers.IO) {
        val host = try { URI(endpointUp).host ?: "unknown" } catch (_: Exception) { "unknown" }
        val ip = try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        } catch (_: Exception) {
            null
        }

        val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
            System.currentTimeMillis().toString() + "_" + System.nanoTime()
        } else ""

        val media = "application/octet-stream".toMediaType()
        val payload = ByteArray(bytesToSend) { 0x5A.toByte() }

        val (method, url) = when (mode) {
            SpeedEndpointMode.CLOUDFLARE_BYTES -> "POST" to cfUrl(endpointUp, bytesToSend, sessionId)
            SpeedEndpointMode.GENERIC_FILE_POST -> "POST" to endpointUp.addAntiCacheParam()
            SpeedEndpointMode.GENERIC_FILE_PUT -> "PUT" to endpointUp.addAntiCacheParam()
        }

        val body = RequestBody.create(media, payload)
        val req = Request.Builder().url(url).apply {
            header("Cache-Control", "no-cache")
            if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) header("Connection", "close")
            when (method) {
                "POST" -> post(body)
                "PUT" -> put(body)
            }
        }.build()

        val call = httpClientFor(mode).newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

        val t0 = System.nanoTime()
        try {
            call.execute().use { resp ->
                val tHeaders = System.nanoTime()
                if (!resp.isSuccessful) {
                    return@withContext UploadProbeOutcome(
                        ok = false,
                        latencyMs = (tHeaders - t0) / 1_000_000L,
                        uploadMbps = 0.0,
                        bytesSent = 0L,
                        error = "HTTP ${resp.code}",
                        endpointHost = host,
                        endpointIp = ip
                    )
                }

                // Небольшой upload обычно возвращает ответ очень быстро, поэтому меряем "данные" как время до конца.
                val tEnd = System.nanoTime()
                val latencyMs = (tHeaders - t0) / 1_000_000L
                val dataMs = ((tEnd - tHeaders) / 1_000_000L).coerceAtLeast(1L)

                val bps = (payload.size * 8.0) / (dataMs / 1000.0)
                val mbps = bps / 1_000_000.0

                // Успех: получили 2xx и не отменили.
                UploadProbeOutcome(
                    ok = (cancelSignal?.invoke() != true),
                    latencyMs = latencyMs,
                    uploadMbps = mbps,
                    bytesSent = payload.size.toLong(),
                    error = null,
                    endpointHost = host,
                    endpointIp = ip
                )
            }
        } catch (e: Exception) {
            UploadProbeOutcome(
                ok = false,
                latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L),
                uploadMbps = 0.0,
                bytesSent = 0L,
                error = e.javaClass.simpleName + ": " + (e.message ?: ""),
                endpointHost = host,
                endpointIp = ip
            )
        } finally {
            if (cancelSignal?.invoke() == true) call.cancel()
        }
    }

    suspend fun runWithProgress(
        endpointDown: String,
        endpointUp: String,
        mode: SpeedEndpointMode,
        durationMsPerDir: Long,
        parallelism: Int,
        cancelSignal: () -> Boolean,
        onProgress: (SpeedProgress) -> Unit
    ): SpeedTestOutcome = withContext(Dispatchers.IO) {
        val host = try { URI(endpointDown).host ?: "unknown" } catch (_: Exception) { "unknown" }
        val ip = try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        } catch (_: Exception) {
            null
        }

        val errors = mutableListOf<String>()

        val latencySamples = runCatching {
            measureLatency(endpointDown, mode = mode, samples = 10, cancelSignal = cancelSignal)
        }.getOrElse { e ->
            errors += (e.javaClass.simpleName + ": " + (e.message ?: ""))
            listOf(0L)
        }

        val latency = latencySamples.sorted()[latencySamples.size / 2]
        val jitter = computeJitter(latencySamples)

        val down = runCatching {
            measureDownloadStreaming(
                endpointDown = endpointDown,
                mode = mode,
                durationMs = durationMsPerDir,
                parallelism = parallelism,
                cancelSignal = cancelSignal,
                onProgress = { mbps, ms -> onProgress(SpeedProgress("download", mbps, ms)) }
            )
        }.getOrElse { e ->
            errors += (e.javaClass.simpleName + ": " + (e.message ?: ""))
            DirResult(bytes = 0L, mbps = 0.0)
        }

        // Если Cloudflare не смог дать download (0 байт/ошибка), не запускаем upload.
        // В авто-режиме это позволит немедленно перейти на резервный endpoint.
        if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES && down.bytes <= 0L) {
            val status = when {
                cancelSignal() -> "aborted"
                else -> "error"
            }
            return@withContext SpeedTestOutcome(
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                latencyMs = latency,
                jitterMs = jitter,
                bytesDown = 0L,
                bytesUp = 0L,
                durationMs = durationMsPerDir,
                status = status,
                error = (errors + "Cloudflare download failed").distinct().joinToString(" | "),
                endpointHost = host,
                endpointIp = ip
            )
        }

        val up = runCatching {
            measureUploadStreaming(
                endpointUp = endpointUp,
                mode = mode,
                durationMs = durationMsPerDir,
                parallelism = parallelism,
                cancelSignal = cancelSignal,
                onProgress = { mbps, ms -> onProgress(SpeedProgress("upload", mbps, ms)) }
            )
        }.getOrElse { e ->
            errors += (e.javaClass.simpleName + ": " + (e.message ?: ""))
            DirResult(bytes = 0L, mbps = 0.0)
        }

        val status = when {
            cancelSignal() -> "aborted"
            errors.isEmpty() -> "ok"
            (down.bytes > 0L || up.bytes > 0L) -> "partial"
            else -> "error"
        }

        SpeedTestOutcome(
            downloadMbps = down.mbps,
            uploadMbps = up.mbps,
            latencyMs = latency,
            jitterMs = jitter,
            bytesDown = down.bytes,
            bytesUp = up.bytes,
            durationMs = durationMsPerDir * 2,
            status = status,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" | "),
            endpointHost = host,
            endpointIp = ip
        )
    }

    private suspend fun measureLatency(
        endpointDown: String,
        mode: SpeedEndpointMode,
        samples: Int,
        cancelSignal: () -> Boolean
    ): List<Long> {
        val results = mutableListOf<Long>()
        val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
            System.currentTimeMillis().toString() + "_" + System.nanoTime()
        } else ""
        repeat(samples) {
            if (cancelSignal()) return@repeat
            val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
            System.currentTimeMillis().toString() + "_" + System.nanoTime()
        } else ""

        val req = when (mode) {
                SpeedEndpointMode.CLOUDFLARE_BYTES -> {
                    Request.Builder().url(cfUrl(endpointDown, 1, sessionId)).get()
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Connection", "close")
                        .build()
                }
                SpeedEndpointMode.GENERIC_FILE_POST,
                SpeedEndpointMode.GENERIC_FILE_PUT -> {
                    // HEAD может быть запрещен, поэтому есть fallback.
                    val base = endpointDown.addAntiCacheParam()
                    Request.Builder()
                        .url(base)
                        .head()
                        .build()
                }
            }
            val start = System.nanoTime()
            try {
                httpClientFor(mode).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Latency HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                if (mode != SpeedEndpointMode.CLOUDFLARE_BYTES) {
                    // fallback: минимальный GET
                    val fallback = Request.Builder()
                        .url(endpointDown.addAntiCacheParam())
                        .get()
                        .header("Range", "bytes=0-0")
                        .build()
                    httpClientFor(mode).newCall(fallback).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Latency HTTP ${resp.code}")
                        resp.body?.byteStream()?.read() // читаем 1 байт
                    }
                } else {
                    throw e
                }
            }
            val end = System.nanoTime()
            results += (end - start) / 1_000_000L
        }
        if (results.isEmpty()) results += 0L
        return results
    }

    private fun computeJitter(samplesMs: List<Long>): Long? {
        if (samplesMs.size < 2) return null
        val mean = samplesMs.average()
        val variance = samplesMs.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toLong()
    }

    private data class DirResult(val bytes: Long, val mbps: Double)

private suspend fun measureDownloadStreaming(
    endpointDown: String,
    mode: SpeedEndpointMode,
    durationMs: Long,
    parallelism: Int,
    cancelSignal: () -> Boolean,
    onProgress: (mbps: Double, elapsedMs: Long) -> Unit
): DirResult = coroutineScope {
    // Cloudflare может "подвисать" или отказывать по политике (403/таймауты).
    // Чтобы не тратить десятки секунд на заведомо неработающий endpoint,
    // сначала делаем короткий probe на небольшой кусок download.
    if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
        val probe = probeDownload(
            endpointDown = endpointDown,
            mode = mode,
            bytesToRead = 192 * 1024,
            timeoutMs = 1500L,
            cancelSignal = cancelSignal
        )
        if (!probe.ok) {
            throw IOException("Cloudflare download probe failed: ${probe.error ?: "unknown"}")
        }
    }

    val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
        System.currentTimeMillis().toString() + "_" + System.nanoTime()
    } else ""

    // Cloudflare: ramp-up по размерам, чтобы избежать "одного огромного запроса" и уменьшить шанс залипания.
    val cfSizes = intArrayOf(200_000, 1_000_000, 5_000_000, 15_000_000, 25_000_000)
    val cfIndex = AtomicLong(0L)

    val bytesTotal = AtomicLong(0L)
    val startNs = System.nanoTime()
    val deadlineNs = startNs + durationMs * 1_000_000L

    val ticker = launch(Dispatchers.IO) {
        while (System.nanoTime() < deadlineNs && !cancelSignal()) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val b = bytesTotal.get()
            val bps = if (elapsedMs <= 0) 0.0 else (b * 8.0) / (elapsedMs / 1000.0)
            onProgress(bps / 1_000_000.0, elapsedMs)
            delay(500)
        }
    }

    val jobs = (0 until parallelism).map {
        launch(Dispatchers.IO) {
            val buf = ByteArray(32 * 1024)
            while (System.nanoTime() < deadlineNs && !cancelSignal()) {
                val req = when (mode) {
                    SpeedEndpointMode.CLOUDFLARE_BYTES -> {
                        val size = cfSizes[(cfIndex.getAndIncrement() % cfSizes.size).toInt()]
                        Request.Builder()
                            .url(cfUrl(endpointDown, size, sessionId))
                            .get()
                            .header("Cache-Control", "no-cache")
                            .header("Pragma", "no-cache")
                            .header("Connection", "close")
                            .build()
                    }
                    SpeedEndpointMode.GENERIC_FILE_POST,
                    SpeedEndpointMode.GENERIC_FILE_PUT -> {
                        Request.Builder()
                            .url(endpointDown.addAntiCacheParam())
                            .get()
                            .header("Cache-Control", "no-cache")
                            .build()
                    }
                }

                val call = httpClientFor(mode).newCall(req)
                if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
                    call.timeout().timeout(6000L, TimeUnit.MILLISECONDS)
                }

                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Download HTTP ${resp.code}")
                        val body = resp.body ?: return@use
                        val stream = body.byteStream()
                        while (System.nanoTime() < deadlineNs && !cancelSignal()) {
                            val r = stream.read(buf)
                            if (r <= 0) break
                            bytesTotal.addAndGet(r.toLong())
                        }
                    }
                } finally {
                    if (cancelSignal()) call.cancel()
                }
            }
        }
    }

    jobs.forEach { it.join() }
    ticker.cancel()

    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
    val b = bytesTotal.get()
    val bps = if (elapsedMs <= 0) 0.0 else (b * 8.0) / (elapsedMs / 1000.0)
    DirResult(bytes = b, mbps = bps / 1_000_000.0)
}



private suspend fun measureUploadStreaming(
    endpointUp: String,
    mode: SpeedEndpointMode,
    durationMs: Long,
    parallelism: Int,
    cancelSignal: () -> Boolean,
    onProgress: (mbps: Double, elapsedMs: Long) -> Unit
): DirResult = coroutineScope {
    val bytesTotal = AtomicLong(0L)
    val startNs = System.nanoTime()
    val deadlineNs = startNs + durationMs * 1_000_000L

    val sessionId = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
        System.currentTimeMillis().toString() + "_" + System.nanoTime()
    } else ""

    val media = "application/octet-stream".toMediaType()

    // Cloudflare: используем последовательность размеров и фиксированные payload'ы (Content-Length), без chunked.
    val cfUploadSizes = intArrayOf(200_000, 1_000_000, 5_000_000)
    val cfUploadIndex = AtomicLong(0L)
    val cfPayloads: Map<Int, ByteArray> = if (mode == SpeedEndpointMode.CLOUDFLARE_BYTES) {
        cfUploadSizes.associateWith { sz -> ByteArray(sz) { 0x5A.toByte() } }
    } else emptyMap()

    val ticker = launch(Dispatchers.IO) {
        while (System.nanoTime() < deadlineNs && !cancelSignal()) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val b = bytesTotal.get()
            val bps = if (elapsedMs <= 0) 0.0 else (b * 8.0) / (elapsedMs / 1000.0)
            onProgress(bps / 1_000_000.0, elapsedMs)
            delay(500)
        }
    }

    val jobs = (0 until parallelism).map {
        launch(Dispatchers.IO) {
            when (mode) {
                SpeedEndpointMode.CLOUDFLARE_BYTES -> {
                    while (System.nanoTime() < deadlineNs && !cancelSignal()) {
                        val size = cfUploadSizes[(cfUploadIndex.getAndIncrement() % cfUploadSizes.size).toInt()]
                        val payload = cfPayloads[size] ?: ByteArray(size) { 0x5A.toByte() }

                        val body = RequestBody.create(media, payload)
                        val req = Request.Builder()
                            .url(cfUrl(endpointUp, size, sessionId))
                            .post(body)
                            .header("Cache-Control", "no-cache")
                            .header("Pragma", "no-cache")
                            .header("Connection", "close")
                            .build()

                        val call = httpClientFor(mode).newCall(req)
                        call.timeout().timeout(6000L, TimeUnit.MILLISECONDS)

                        try {
                            call.execute().use { resp ->
                                if (!resp.isSuccessful) throw IOException("Upload HTTP ${resp.code}")
                            }
                            bytesTotal.addAndGet(payload.size.toLong())
                        } finally {
                            if (cancelSignal()) call.cancel()
                        }
                    }
                }

                SpeedEndpointMode.GENERIC_FILE_POST -> {
                    // Серия коротких POST фиксированного размера.
                    val payload = ByteArray(1024 * 1024) { 0x5A.toByte() }
                    while (System.nanoTime() < deadlineNs && !cancelSignal()) {
                        val body = RequestBody.create(media, payload)
                        val req = Request.Builder()
                            .url(endpointUp.addAntiCacheParam())
                            .post(body)
                            .header("Cache-Control", "no-cache")
                            .build()
                        val call = httpClientFor(mode).newCall(req)
                        try {
                            call.execute().use { resp ->
                                if (!resp.isSuccessful) throw IOException("Upload HTTP ${resp.code}")
                            }
                            bytesTotal.addAndGet(payload.size.toLong())
                        } finally {
                            if (cancelSignal()) call.cancel()
                        }
                    }
                }

                SpeedEndpointMode.GENERIC_FILE_PUT -> {
                    // Режим для upload-эндпоинтов, которые принимают PUT.
                    val payload = ByteArray(1024 * 1024) { 0x5A.toByte() }
                    while (System.nanoTime() < deadlineNs && !cancelSignal()) {
                        val body = RequestBody.create(media, payload)
                        val req = Request.Builder()
                            .url(endpointUp.addAntiCacheParam())
                            .put(body)
                            .header("Cache-Control", "no-cache")
                            .build()
                        val call = httpClientFor(mode).newCall(req)
                        try {
                            call.execute().use { resp ->
                                if (!resp.isSuccessful) throw IOException("Upload HTTP ${resp.code}")
                            }
                            bytesTotal.addAndGet(payload.size.toLong())
                        } finally {
                            if (cancelSignal()) call.cancel()
                        }
                    }
                }
            }
        }
    }

    jobs.forEach { it.join() }
    ticker.cancel()

    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
    val b = bytesTotal.get()
    val bps = if (elapsedMs <= 0) 0.0 else (b * 8.0) / (elapsedMs / 1000.0)
    DirResult(bytes = b, mbps = bps / 1_000_000.0)
}

    private fun String.addAntiCacheParam(): String {
        val sep = if (contains("?")) "&" else "?"
        return this + sep + "r=" + System.nanoTime().toString()
    }
}
