package com.stateofnetwork.net

import com.stateofnetwork.data.Config
import com.stateofnetwork.data.DomainDao
import com.stateofnetwork.data.DomainGroup
import com.stateofnetwork.data.DomainTarget
import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.data.model.DomainCheckRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class DomainRunResult(
    val runId: Long,
    val items: List<DomainCheckItemEntity>
)

private data class CallMetrics(
    var dnsOk: Boolean = false,
    var dnsTimeMs: Long? = null,
    var tcpOk: Boolean = false,
    var tcpTimeMs: Long? = null,
    var resolvedIp: String? = null
)

class DomainChecker {

    // Активные сетевые вызовы (OkHttp Call). Нужны, чтобы можно было принудительно остановить проверку.
    // OkHttp корректно прерывает execute() через Call.cancel().
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun cancelOngoingChecks() {
        // Параллельная итерация по set безопасна: это concurrent set.
        activeCalls.forEach { call ->
            try {
                call.cancel()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /**
     * Базовый клиент. Для каждого запроса создаем клон с EventListener, чтобы собрать метрики.
     * Важно: используем OkHttp как в браузере: он сам делает DNS, connect, TLS, редиректы.
     * Это радикально снижает ложные "TCP недоступно" при фактически открывающихся сайтах.
     */
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(Config.tcpTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(Config.httpsTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(Config.httpsTimeoutMs, TimeUnit.MILLISECONDS)
        // callTimeout покрывает и DNS/handshake: без него отдельные провайдеры/VPN/DNS могут подвешивать вызов навсегда
        .callTimeout(maxOf(10_000L, Config.httpsTimeoutMs + 2_000L), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * DoH (DNS-over-HTTPS) используется как диагностический и "browser-like" fallback.
     * В реальности многие браузеры могут обходить DNS-фильтрацию (Secure DNS/DoH).
     * Поэтому: если системный DNS/TCP не дает открыть сайт, делаем вторую попытку через DoH.
     */
    private val dohClientBase: OkHttpClient by lazy {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            // bootstrap IP, чтобы не зависеть от системного DNS при резолвинге DoH-хоста
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .includeIPv6(true)
            .build()

        baseClient.newBuilder()
            .dns(doh)
            .callTimeout(maxOf(10_000L, Config.httpsTimeoutMs + 2_000L), TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun runAndPersist(
        selectedSetIds: Set<String>,
        customDomains: List<String>,
        serviceOverrides: Map<String, List<String>> = emptyMap(),
        serviceRemoved: Map<String, List<String>> = emptyMap(),
        networkType: String,
        domainDao: DomainDao,
        onRunCreated: (runId: Long) -> Unit = {},
        onProgress: (done: Int, total: Int, currentDomain: String?) -> Unit,
        onItem: (DomainCheckItemEntity) -> Unit = {}
    ): DomainRunResult = coroutineScope {

        val sets = Config.serviceSets.filter { selectedSetIds.contains(it.id) }

        val customTargets = if (selectedSetIds.contains("custom")) {
            customDomains.map { DomainTarget(it.trim().lowercase(), 443, DomainGroup.OTHER) }
        } else emptyList()

        // Применяем локальные изменения наборов:
        // - serviceOverrides: домены, добавленные пользователем к набору
        // - serviceRemoved: домены, исключенные пользователем из предустановленного набора
        val setTargets = sets.flatMap { set ->
            val removed = serviceRemoved[set.id].orEmpty().map { it.trim().lowercase() }.toSet()
            val base = set.domains
                .map { DomainTarget(it.domain.trim().lowercase(), it.port, it.group) }
                .filter { it.domain.isNotBlank() }
                .filterNot { removed.contains(it.domain) }

            val added = serviceOverrides[set.id].orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .filterNot { removed.contains(it) }
                .map { DomainTarget(it, 443, DomainGroup.OTHER) }

            (base + added)
        }

        val targets = (setTargets + customTargets)
            .filter { it.domain.isNotBlank() }
            .distinctBy { it.domain + ":" + it.port }

        val run = DomainCheckRunEntity(
            timestamp = System.currentTimeMillis(),
            networkType = networkType,
            selectedServiceSets = buildString {
                append(sets.joinToString(",") { it.id })
                if (customTargets.isNotEmpty()) {
                    if (isNotEmpty()) append(",")
                    append("custom")
                }
            },
            summaryStatus = "running",
            notes = null
        )
        val runId = domainDao.insertRun(run)

        // Сообщаем UI/VM идентификатор проверки сразу, чтобы можно было корректно
        // связывать прогресс/экран "Последняя проверка" даже если пользователь уйдет со страницы.
        onRunCreated(runId)
        onRunCreated(runId)

        val total = targets.size
        var done = 0
        onProgress(done, total, null)

        val sem = kotlinx.coroutines.sync.Semaphore(Config.domainParallelism)

        val pending = targets.map { target ->
            async(Dispatchers.IO) {
                sem.acquire()
                try {
                    checkOne(runId, target)
                } finally {
                    sem.release()
                }
            }
        }.toMutableList()

        val items = mutableListOf<DomainCheckItemEntity>()
        var finalized = false
        var cancelled = false
        var failed = false

        try {
            // Собираем результаты по мере готовности, чтобы UI мог показывать прогресс "живым" списком.
            while (pending.isNotEmpty()) {
                val next = select<DomainCheckItemEntity> {
                    pending.forEach { d ->
                        d.onAwait { it }
                    }
                }
                // Удаляем уже завершившиеся deferred.
                pending.removeAll { it.isCompleted }

                items += next
                // Пишем по одному, чтобы при закрытии/повороте экрана прогресс не терялся.
                domainDao.insertItems(listOf(next))
                onItem(next)

                done += 1
                onProgress(done, total, next.domain)
            }

            val summary = summarize(items)
            // Важно: не используем INSERT(REPLACE) для обновления статуса, чтобы исключить
            // эффекты delete+insert и любые проблемы с наблюдением "последней" записи.
            domainDao.updateRunStatus(id = runId, status = summary, notes = null)
            finalized = true

            DomainRunResult(runId, items.sortedBy { it.domain })
        } catch (e: CancellationException) {
            // Пробрасываем отмену выше, но статус в БД фиксируем в finally.
            cancelled = true
            throw e
        } catch (e: Exception) {
            failed = true
            throw e
        } finally {
            if (!finalized) {
                // Если пользователь вышел с экрана/корутина была отменена, run мог остаться "running".
                // Фиксируем финальный статус в БД, чтобы главный экран не залипал на "выполняется".
                val status = when {
                    cancelled -> "cancelled"
                    failed -> "error"
                    else -> if (items.isEmpty()) "cancelled" else summarize(items)
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    domainDao.updateRunStatus(
                        id = runId,
                        status = status,
                        notes = when (status) {
                            "cancelled" -> "cancelled"
                            "error" -> "error"
                            else -> null
                        }
                    )
                }
            }
        }
    }

    private fun summarize(items: List<DomainCheckItemEntity>): String {
        val total = items.size.coerceAtLeast(1)
        val ok = items.count {
            it.dnsOk && it.tcpOk && it.httpsOk && (it.errorType == null || it.errorType == "http")
        }
        return if (ok.toDouble() / total.toDouble() >= 0.6) "available" else "unavailable"
    }

    private fun browserRequest(url: String): Request {
        // Стараемся быть максимально близкими к поведению браузера,
        // иначе некоторые сайты отдают 403/капчу чисто из-за "ботового" User-Agent.
        val ua = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
    }

    private fun extractSnippet(resp: Response): String? {
        val ct = (resp.header("Content-Type") ?: "").lowercase()
        if (!(ct.contains("text") || ct.contains("html") || ct.contains("json") || ct.contains("xml"))) return null
        return try {
            // peekBody не потребляет поток полностью и ограничивает объем.
            resp.peekBody(32_768).string().take(32_768)
        } catch (_: Exception) {
            null
        }
    }

    private fun looksBlocked(code: Int, snippet: String?): Boolean {
        if (code == 451) return true
        val s = (snippet ?: "").lowercase()
        if (s.isBlank()) return false

        // Страницы РКН/провайдеров, а также типовые тексты гео-ограничений.
        val patterns = listOf(
            "доступ к информационному ресурсу ограничен",
            "доступ к ресурсу ограничен",
            "роскомнадзор",
            "на основании федерального закона",
            "eais",
            "rkn.gov",
            "unavailable for legal reasons",
            "this content is not available in your country",
            "not available in your country",
            "not available in your region",
            "service is not available in your country",
            "unsupported country",
            "unsupported_country",
            "openai's services are not available",
            "available in your location",
            "we're sorry, but",
            "access to this site is blocked"
        )
        return patterns.any { p -> s.contains(p) }
    }

    private fun looksLikeBrowserChallenge(code: Int, snippet: String?): Boolean {
        val s = (snippet ?: "").lowercase()
        if (s.isBlank()) return false
        // Cloudflare / аналогичные антибот-страницы: пользователь может увидеть страницу,
        // но "сайт недоступен" это обычно не значит.
        val patterns = listOf(
            "just a moment",
            "checking your browser",
            "attention required",
            "cloudflare",
            "ddos-guard",
            "please enable cookies",
            "verify you are human"
        )
        return (code == 403 || code == 503) && patterns.any { s.contains(it) }
    }

    private data class AttemptResult(
        val dnsOk: Boolean,
        val dnsTimeMs: Long?,
        val tcpOk: Boolean,
        val tcpTimeMs: Long?,
        val resolvedIp: String?,
        val httpsOk: Boolean,
        val httpsTimeMs: Long?,
        val httpCode: Int?,
        val errorType: String?
    )

    private fun attemptOnce(clientBase: OkHttpClient, url: String): AttemptResult {
        val metrics = CallMetrics()
        val listener = object : EventListener() {
            private var dnsStartNs: Long? = null
            private var connectStartNs: Long? = null

            override fun dnsStart(call: okhttp3.Call, domainName: String) {
                dnsStartNs = System.nanoTime()
            }

            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<InetAddress>) {
                metrics.dnsOk = inetAddressList.isNotEmpty()
                val start = dnsStartNs
                if (start != null) metrics.dnsTimeMs = (System.nanoTime() - start) / 1_000_000L
                metrics.resolvedIp = inetAddressList.firstOrNull()?.hostAddress
            }

            override fun connectStart(call: okhttp3.Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connectStartNs = System.nanoTime()
            }

            override fun connectEnd(
                call: okhttp3.Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: okhttp3.Protocol?
            ) {
                metrics.tcpOk = true
                val start = connectStartNs
                if (start != null) metrics.tcpTimeMs = (System.nanoTime() - start) / 1_000_000L
                metrics.resolvedIp = inetSocketAddress.address.hostAddress
            }

            override fun connectFailed(
                call: okhttp3.Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: okhttp3.Protocol?,
                ioe: IOException
            ) {
                // tcpOk останется false
            }
        }

        val client = clientBase.newBuilder()
            .eventListener(listener)
            .build()

        val startNs = System.nanoTime()
        val req = browserRequest(url)

        val call = client.newCall(req)
        activeCalls.add(call)

        try {
            call.execute().use { resp ->
                val tookMs = (System.nanoTime() - startNs) / 1_000_000L
                val code = resp.code
                val snippet = extractSnippet(resp)

                val errorType = when {
                    looksBlocked(code, snippet) -> "blocked"
                    looksLikeBrowserChallenge(code, snippet) -> "challenge"
                    code in 200..399 -> null
                    code == 401 || code == 404 -> "http" // сайт отвечает, но требует авторизацию/страница не найдена
                    code == 403 -> "forbidden"
                    code in 500..599 -> "server"
                    else -> "client"
                }

                // httpsOk здесь означает: удалось получить HTTP-ответ (через https или http)
                return AttemptResult(
                    dnsOk = metrics.dnsOk,
                    dnsTimeMs = metrics.dnsTimeMs,
                    tcpOk = metrics.tcpOk,
                    tcpTimeMs = metrics.tcpTimeMs,
                    resolvedIp = metrics.resolvedIp,
                    httpsOk = true,
                    httpsTimeMs = tookMs,
                    httpCode = code,
                    errorType = errorType
                )
            }
        } catch (e: UnknownHostException) {
            return AttemptResult(
                dnsOk = false,
                dnsTimeMs = metrics.dnsTimeMs,
                tcpOk = false,
                tcpTimeMs = null,
                resolvedIp = null,
                httpsOk = false,
                httpsTimeMs = null,
                httpCode = null,
                errorType = "dns"
            )
        } catch (e: SSLException) {
            return AttemptResult(
                dnsOk = metrics.dnsOk,
                dnsTimeMs = metrics.dnsTimeMs,
                tcpOk = metrics.tcpOk,
                tcpTimeMs = metrics.tcpTimeMs,
                resolvedIp = metrics.resolvedIp,
                httpsOk = false,
                httpsTimeMs = null,
                httpCode = null,
                errorType = "https"
            )
        } catch (e: SocketTimeoutException) {
            // В зависимости от стадии это может быть и connect, и read. Для пользователя важнее "не открылось".
            val type = if (metrics.tcpOk) "https" else "tcp"
            return AttemptResult(
                dnsOk = metrics.dnsOk,
                dnsTimeMs = metrics.dnsTimeMs,
                tcpOk = metrics.tcpOk,
                tcpTimeMs = metrics.tcpTimeMs,
                resolvedIp = metrics.resolvedIp,
                httpsOk = false,
                httpsTimeMs = null,
                httpCode = null,
                errorType = type
            )
        } catch (e: ConnectException) {
            return AttemptResult(
                dnsOk = metrics.dnsOk,
                dnsTimeMs = metrics.dnsTimeMs,
                tcpOk = false,
                tcpTimeMs = metrics.tcpTimeMs,
                resolvedIp = metrics.resolvedIp,
                httpsOk = false,
                httpsTimeMs = null,
                httpCode = null,
                errorType = "tcp"
            )
        } catch (_: IOException) {
            val type = if (metrics.tcpOk) "https" else "tcp"
            return AttemptResult(
                dnsOk = metrics.dnsOk,
                dnsTimeMs = metrics.dnsTimeMs,
                tcpOk = metrics.tcpOk,
                tcpTimeMs = metrics.tcpTimeMs,
                resolvedIp = metrics.resolvedIp,
                httpsOk = false,
                httpsTimeMs = null,
                httpCode = null,
                errorType = type
            )
        } finally {
            activeCalls.remove(call)
        }
    }

    private suspend fun checkOne(runId: Long, target: DomainTarget): DomainCheckItemEntity {
        val domain = target.domain
        val port = target.port // порт в UI, фактически для веб-теста используем схему/URL

        // Порядок попыток:
        // 1) https://domain/
        // 2) https://www.domain/ (если применимо)
        // 3) http://domain/
        // 4) http://www.domain/
        // Важно: некоторые сайты (и особенно часть .ru) нормально работают только на www.

        val candidates = buildList {
            add("https://$domain/")
            if (!domain.startsWith("www.")) add("https://www.$domain/")
            add("http://$domain/")
            if (!domain.startsWith("www.")) add("http://www.$domain/")
        }

        fun runCandidates(clientBase: OkHttpClient): AttemptResult? {
            var best: AttemptResult? = null
            for (url in candidates) {
                val r = attemptOnce(clientBase, url)

                // Если DNS не ок, дальнейшие кандидаты бессмысленны.
                if (r.errorType == "dns") {
                    best = r
                    break
                }

                // Успех: получили HTTP-ответ.
                if (r.httpsOk) {
                    best = r
                    break
                }

                // Сохраняем "самую информативную" ошибку, если все попытки провалятся.
                if (best == null || (best!!.errorType == "tcp" && r.errorType == "https")) {
                    best = r
                }
            }
            return best
        }

        // 1) Пытаемся через системный DNS/стек.
        val systemBest = runCandidates(baseClient)

        // 2) Если системный DNS/TCP не дает открыть сайт, пробуем DoH.
        // Это повышает "пользовательскую точность" на сетях с DNS-фильтрацией,
        // а также приближает поведение к браузеру (Secure DNS).
        val res = if (systemBest != null && (systemBest.errorType == "dns" || systemBest.errorType == "tcp") && !systemBest.httpsOk) {
            val dohBest = runCandidates(dohClientBase)
            if (dohBest != null && dohBest.httpsOk) {
                // Маркируем, что сайт открылся только через DoH.
                val tag = when (dohBest.errorType) {
                    null -> "doh_ok"
                    else -> "doh_${dohBest.errorType}"
                }
                dohBest.copy(errorType = tag)
            } else {
                systemBest
            }
        } else {
            systemBest
        } ?: AttemptResult(
            dnsOk = false,
            dnsTimeMs = null,
            tcpOk = false,
            tcpTimeMs = null,
            resolvedIp = null,
            httpsOk = false,
            httpsTimeMs = null,
            httpCode = null,
            errorType = "dns"
        )

        return DomainCheckItemEntity(
            runId = runId,
            domain = domain,
            port = port,
            dnsOk = res.dnsOk,
            dnsTimeMs = res.dnsTimeMs,
            tcpOk = res.tcpOk,
            tcpTimeMs = res.tcpTimeMs,
            httpsOk = res.httpsOk,
            httpsTimeMs = res.httpsTimeMs,
            httpCode = res.httpCode,
            errorType = res.errorType,
            resolvedIp = res.resolvedIp
        )
    }
}
