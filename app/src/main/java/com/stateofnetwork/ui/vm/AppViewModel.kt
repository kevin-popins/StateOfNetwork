package com.stateofnetwork.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stateofnetwork.data.AppDatabase
import com.stateofnetwork.data.Config
import com.stateofnetwork.data.UserDomainsStore
import com.stateofnetwork.data.ServiceSetDomainsStore
import com.stateofnetwork.data.SpeedEndpoint
import com.stateofnetwork.data.SpeedEndpointStore
import com.stateofnetwork.net.SpeedProbeOutcome
import com.stateofnetwork.net.UploadProbeOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.net.DomainChecker
import com.stateofnetwork.net.NetworkInfoProvider
import com.stateofnetwork.net.RuLatencyMeasurer
import com.stateofnetwork.net.SpeedProgress
import com.stateofnetwork.net.SpeedTester
import com.stateofnetwork.data.model.SpeedTestResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.exp

data class SpeedUiState(
    val isRunning: Boolean = false,
    val phase: String = "",
    val endpointTitle: String = "",
    val endpointHost: String = "",
    val endpointIp: String? = null,
    // Справка о текущем соединении (локально на телефоне).
    val transportLabel: String? = null,
    val radioSummary: String? = null,
    val operatorName: String? = null,
    // Внешняя справка по публичному IP/провайдеру (может быть недоступна).
    val publicIp: String? = null,
    val providerName: String? = null,
    val providerAsn: String? = null,
    val providerGeo: String? = null,
    // Задержка до "РФ" (подбирается автоматически по набору опорных RU-доменов).
    val ruLatencyMs: Long? = null,
    val ruLatencyTarget: String? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val latencyMs: Long? = null,
    val jitterMs: Long? = null,
    val downloadSeries: List<Double> = emptyList(),
    val uploadSeries: List<Double> = emptyList(),
    // Итоговые технические метрики измерения (не дублируем в «основной» карточке).
    val bytesDown: Long? = null,
    val bytesUp: Long? = null,
    val durationMs: Long? = null,

    // Расширенные параметры активной сети (DNS, локальные IP и т.п.).
    val iface: String? = null,
    val localIps: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val defaultGateway: String? = null,
    val mtu: Int? = null,
    val privateDns: String? = null,
    val isValidated: Boolean? = null,
    val isCaptivePortal: Boolean? = null,
    val isMetered: Boolean? = null,
    val estDownKbps: Int? = null,
    val estUpKbps: Int? = null,
    val transportsDetail: String? = null,
    // Предпочтение пользователя по выбору сервера speedtest: auto или конкретный id.
    val serverChoiceId: String = "auto",
    val statusText: String = ""
)

data class DomainUiState(
    val isRunning: Boolean = false,
    val statusText: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val currentDomain: String? = null,
    val lastRunId: Long? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val speedDao = db.speedDao()
    private val domainDao = db.domainDao()

    private val networkInfo = NetworkInfoProvider(app)

    /**
     * Снимок состояния VPN/Private DNS. Делаем максимально так же, как на экране теста скорости:
     * используем NetworkInfoProvider (activeNetwork + LinkProperties).
     */
    data class VpnDnsSnapshot(
        val transportLabel: String,
        val privateDns: String?,
        val hasVpn: Boolean,
        val hasPrivateDns: Boolean
    )

    fun getVpnDnsSnapshot(): VpnDnsSnapshot {
        val local = networkInfo.getLocalRadioInfo()
        val details = networkInfo.getNetworkDetails()

        val hasVpn = local.transportLabel.contains("VPN", ignoreCase = true) ||
            (details.transports?.contains("VPN", ignoreCase = true) == true)

        val pd = details.privateDns
        val hasPrivateDns = pd != null && pd.lowercase() != "выкл" && pd.lowercase() != "off" && pd.lowercase() != "disabled"

        return VpnDnsSnapshot(
            transportLabel = local.transportLabel,
            privateDns = pd,
            hasVpn = hasVpn,
            hasPrivateDns = hasPrivateDns
        )
    }

    private val speedTester = SpeedTester()
    private val ruLatencyMeasurer = RuLatencyMeasurer()
    private val domainChecker = DomainChecker()

    private var domainCheckJob: Job? = null

    private val userDomainsStore = UserDomainsStore(app)
    private val serviceSetDomainsStore = ServiceSetDomainsStore(app)

    suspend fun getSpeedResultByTimestamp(ts: Long): SpeedTestResultEntity? {
        return speedDao.getByTimestamp(ts)
    }

    private fun loadServiceOverrides(): Map<String, List<String>> {
        return Config.serviceSets.associate { set ->
            set.id to serviceSetDomainsStore.loadForSet(set.id)
        }
    }

    private fun loadServiceRemoved(): Map<String, List<String>> {
        return Config.serviceSets.associate { set ->
            set.id to serviceSetDomainsStore.loadRemovedForSet(set.id)
        }
    }


    private val _userDomains = MutableStateFlow(userDomainsStore.load())
    val userDomains: StateFlow<List<String>> = _userDomains.asStateFlow()

    private val _serviceOverrides = MutableStateFlow(loadServiceOverrides())
    val serviceOverrides: StateFlow<Map<String, List<String>>> = _serviceOverrides.asStateFlow()

    private val _serviceRemoved = MutableStateFlow(loadServiceRemoved())
    val serviceRemoved: StateFlow<Map<String, List<String>>> = _serviceRemoved.asStateFlow()

        private val speedEndpointStore = SpeedEndpointStore(app)

    private val _speedState = MutableStateFlow(SpeedUiState(serverChoiceId = speedEndpointStore.load()))
    val speedState: StateFlow<SpeedUiState> = _speedState.asStateFlow()

    private val _domainState = MutableStateFlow(DomainUiState())
    val domainState: StateFlow<DomainUiState> = _domainState.asStateFlow()

    private val _domainItems = MutableStateFlow<List<DomainCheckItemEntity>>(emptyList())
    val domainItems: StateFlow<List<DomainCheckItemEntity>> = _domainItems.asStateFlow()

    val lastSpeed = speedDao.observeLast()
    val lastDomainRun = domainDao.observeLastRun()
    val downloadSeriesFlow = speedDao.observeLastNDownload(20)
    val uploadSeriesFlow = speedDao.observeLastNUpload(20)
    private val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
    val speedAgg7dFlow = speedDao.observeAggSince(System.currentTimeMillis() - sevenDaysMs)


    val historyItems = db.historyDao().observeCombinedLatest(200)

    fun addUserDomain(raw: String) {
        val d = raw.trim().lowercase()
        if (d.isBlank()) return
        // грубая валидация домена: без протокола и слешей
        if (d.contains("://") || d.contains("/") || d.contains(" ")) return

        _userDomains.update { (it + d).distinct() }
        userDomainsStore.save(_userDomains.value)
    }

    fun addDomainToServiceSet(setId: String, raw: String) {
        val d = raw.trim().lowercase()
        if (d.isBlank()) return
        if (d.contains("://") || d.contains("/") || d.contains(" ")) return

        // Если домен уже есть в базовом наборе, то "добавление" по сути означает
        // только снятие исключения (если оно было).
        val baseContains = Config.serviceSets.firstOrNull { it.id == setId }
            ?.domains
            ?.any { it.domain.equals(d, ignoreCase = true) } == true

        if (baseContains) {
            val removed = _serviceRemoved.value[setId].orEmpty().filterNot { it.equals(d, ignoreCase = true) }
            _serviceRemoved.update { it + (setId to removed) }
            serviceSetDomainsStore.saveRemovedForSet(setId, removed)
            return
        }

        // Если домен ранее был исключён из предустановленного набора, то при добавлении
        // логично вернуть его обратно (убрать из списка исключений).
        val removed = _serviceRemoved.value[setId].orEmpty()
        if (removed.contains(d)) {
            val newRemoved = removed.filterNot { it == d }
            _serviceRemoved.update { it + (setId to newRemoved) }
            serviceSetDomainsStore.saveRemovedForSet(setId, newRemoved)
        }

        val current = _serviceOverrides.value[setId].orEmpty()
        val updated = (current + d).distinct()
        _serviceOverrides.update { it + (setId to updated) }
        serviceSetDomainsStore.saveForSet(setId, updated)
    }

    fun removeDomainFromServiceSet(setId: String, domain: String) {
        val d = domain.trim().lowercase()

        // 1) Если это пользовательский домен (added override) - просто убираем его из overrides.
        val currentAdded = _serviceOverrides.value[setId].orEmpty()
        if (currentAdded.contains(d)) {
            val updatedAdded = currentAdded.filterNot { it == d }
            _serviceOverrides.update { it + (setId to updatedAdded) }
            serviceSetDomainsStore.saveForSet(setId, updatedAdded)
            return
        }

        // 2) Иначе считаем, что это домен из предустановленного набора.
        // Помечаем как исключенный (не удаляем из Config, а сохраняем локальное исключение).
        val currentRemoved = _serviceRemoved.value[setId].orEmpty()
        if (!currentRemoved.contains(d)) {
            val updatedRemoved = (currentRemoved + d).distinct()
            _serviceRemoved.update { it + (setId to updatedRemoved) }
            serviceSetDomainsStore.saveRemovedForSet(setId, updatedRemoved)
        }
    }

    fun removeUserDomain(domain: String) {
        _userDomains.update { it.filterNot { x -> x == domain } }
        userDomainsStore.save(_userDomains.value)
    }


    private suspend fun chooseBestSpeedEndpoint(
        endpoints: List<SpeedEndpoint>,
        cancelSignal: () -> Boolean
    ): SpeedEndpoint = coroutineScope {
        // Идея подбора "лучшего" сервера:
        // 1) Быстро и параллельно делаем download-probe по всем endpoint'ам.
        // 2) Берём TOP-K по эвристическому скору.
        // 3) Для TOP-K дополнительно делаем upload-probe, чтобы отсеять "download-only" и глючные.
        // 4) Выбираем лучший по комбинированному скору.

        val sem = Semaphore(2) // не шумим, особенно на мобильных сетях

        // Используем результаты предыдущих успешных запусков как "подсказку":
        // самый быстрый сервер иногда проигрывает коротким probe из-за jitter/slow-start,
        // но в полном тесте стабильно побеждает. Поэтому мы:
        // 1) сохраняем последние скорости,
        // 2) и в Auto-выборе принудительно включаем top-историю в список кандидатов на бенчмарк,
        //    если сервер сейчас хотя бы доступен по download-probe.
        val nowMs = System.currentTimeMillis()
        val lastScores = speedEndpointStore.loadLastScores(endpoints.map { it.id })
        fun historyScore(id: String): Double {
            val ls = lastScores[id] ?: return 0.0
            val ageMs = (nowMs - ls.ts).coerceAtLeast(0L)
            // Half-life ~ 20 минут: свежие результаты важнее, старые быстро теряют вес.
            val halfLifeMs = 20.0 * 60_000.0
            val w = exp(-(ageMs.toDouble()) / halfLifeMs)
            return (ls.downloadMbps + ls.uploadMbps) * w
        }

        val downPairs: List<Pair<SpeedEndpoint, SpeedProbeOutcome?>> = endpoints.map { ep ->
            async(Dispatchers.IO) {
                if (cancelSignal()) return@async ep to null
                sem.withPermit {
                    val probe = withTimeoutOrNull(3_200L) {
                        speedTester.probeDownload(
                            endpointDown = ep.downUrl,
                            mode = ep.mode,
                            bytesToRead = 512 * 1024,
                            timeoutMs = 2_600L,
                            cancelSignal = cancelSignal
                        )
                    }
                    ep to probe
                }
            }
        }.awaitAll()

        val downOk = downPairs.mapNotNull { (ep, p) -> if (p != null && p.ok) ep to p else null }
        if (downOk.isEmpty()) return@coroutineScope endpoints.first()

        fun downScore(p: SpeedProbeOutcome): Double {
            // В режиме Авто выбираем по максимально возможной пропускной способности.
            // Пинг НЕ используем как критерий (иначе можно выбрать "близкий", но узкий сервер).
            return p.downloadMbps
        }

        val topK = downOk.sortedByDescending { (_, p) -> downScore(p) }.take(4)

        // Принудительно включаем 1-2 сервера с лучшей историей в кандидаты на upload-probe,
        // но только если текущий download-probe прошёл (то есть сервер не "мертв").
        val downOkById = downOk.associateBy { it.first.id }
        val historyTopIds = endpoints
            .map { it.id }
            .distinct()
            .sortedByDescending { historyScore(it) }
            .take(2)
        val forcedFromHistory = historyTopIds
            .mapNotNull { downOkById[it] }

        val upCandidateList = (topK + forcedFromHistory)
            .distinctBy { it.first.id }
            .take(6)

        val upPairs: List<Triple<SpeedEndpoint, SpeedProbeOutcome, UploadProbeOutcome?>> = upCandidateList.map { (ep, dp) ->
            async(Dispatchers.IO) {
                if (cancelSignal()) return@async Triple(ep, dp, null)
                sem.withPermit {
                    val up = withTimeoutOrNull(3_200L) {
                        speedTester.probeUpload(
                            endpointUp = ep.upUrl,
                            mode = ep.mode,
                            bytesToSend = 512 * 1024,
                            timeoutMs = 2_600L,
                            cancelSignal = cancelSignal
                        )
                    }
                    Triple(ep, dp, up)
                }
            }
        }.awaitAll()

        fun combinedScore(dp: SpeedProbeOutcome, up: UploadProbeOutcome): Double {
            // Приоритет: максимальные значения download+upload.
            // Latency не штрафуем, чтобы не выбирать быстрый, но ограниченный по полосе сервер.
            return (dp.downloadMbps * 1.0) + (up.uploadMbps * 1.0)
        }

        val bothOk = upPairs.mapNotNull { (ep, dp, up) ->
            if (up != null && up.ok) Triple(ep, dp, up) else null
        }

        // 5) Дополнительный короткий бенчмарк для TOP-кандидатов.
        // На практике один сервер может давать лучший результат в полном тесте,
        // но проигрывать на коротком probe из-за стартового "..." или всплесков RTT.
        // Поэтому делаем таймбоксированный mini download+upload на 1–2 серверах и выбираем максимум.

        val candidates: List<SpeedEndpoint> = if (bothOk.isNotEmpty()) {
            bothOk
                .sortedByDescending { (_, dp, up) -> combinedScore(dp, up) }
                .map { it.first }
                .take(4)
        } else {
            downOk
                .sortedByDescending { (_, p) -> downScore(p) }
                .map { it.first }
                .take(4)
        }

        // Если есть явный лидер по истории (например, ast-systems),
        // обязательно включаем его в mini-бенчмарк, если он доступен сейчас.
        val historyBestId = historyTopIds.firstOrNull()
        val historyBestEp = historyBestId?.let { id -> downOkById[id]?.first }
        val benchCandidateList = (candidates + listOfNotNull(historyBestEp))
            .distinctBy { it.id }
            .take(5)

        suspend fun bench(ep: SpeedEndpoint): Pair<SpeedEndpoint, Double>? {
            if (cancelSignal()) return null
            val out = withTimeoutOrNull(9_000L) {
                speedTester.quickBenchmark(
                    endpointDown = ep.downUrl,
                    endpointUp = ep.upUrl,
                    mode = ep.mode,
                    // Дольше и чуть более параллельно, чтобы измерение было ближе к реальному тесту.
                    durationMsPerDir = 2_200L,
                    parallelism = 3,
                    cancelSignal = cancelSignal
                )
            } ?: return null

            val score = out.downloadMbps + out.uploadMbps
            // Если бенчмарк полностью не смог передать данные, не считаем кандидата.
            if (out.bytesDown <= 0L && out.bytesUp <= 0L) return null
            return ep to score
        }

        val benchResults = benchCandidateList.map { ep ->
            async(Dispatchers.IO) { bench(ep) }
        }.awaitAll().filterNotNull()

        val benchBest: SpeedEndpoint? = benchResults
            .maxByOrNull { it.second }
            ?.first

        val best = benchBest ?: if (bothOk.isNotEmpty()) {
            bothOk.maxByOrNull { (_, dp, up) -> combinedScore(dp, up) }?.first
        } else {
            // Если upload-probe ни у кого не прошёл, берём лучший по download.
            downOk.maxByOrNull { (_, p) -> downScore(p) }?.first
        }

        best ?: endpoints.first()
    }

    fun startSpeedTest(forceMobileConfirm: Boolean, onNeedConfirmMobile: (estimatedMb: Int) -> Unit) {
        viewModelScope.launch {
            val type = networkInfo.getNetworkType()
            if (type == "MOBILE" && !forceMobileConfirm) {
                val estMb = Config.estimatedSpeedTestTrafficMb()
                onNeedConfirmMobile(estMb)
                return@launch
            }

            val localRadio = networkInfo.getLocalRadioInfo()
            val netDetails = networkInfo.getNetworkDetails()
            val endpointsAll = Config.speedEndpoints
            val prefId = speedEndpointStore.load()
            val manual = endpointsAll.firstOrNull { it.id == prefId }
            val isAuto = prefId == "auto" || manual == null

            _speedState.value = SpeedUiState(
                serverChoiceId = prefId,

                isRunning = true,
                phase = "running",
                endpointTitle = if (isAuto) "Авто" else manual!!.title,
                statusText = "Выполняется",
                transportLabel = localRadio.transportLabel,
                radioSummary = localRadio.radioSummary,
                operatorName = localRadio.operatorName,
                iface = netDetails.interfaceName,
                localIps = netDetails.localAddresses,
                dnsServers = netDetails.dnsServers,
                defaultGateway = netDetails.defaultGateway,
                mtu = netDetails.mtu,
                privateDns = netDetails.privateDns,
                isValidated = netDetails.isValidated,
                isCaptivePortal = netDetails.isCaptivePortal,
                isMetered = netDetails.isMetered,
                estDownKbps = netDetails.downstreamKbps,
                estUpKbps = netDetails.upstreamKbps,
                transportsDetail = netDetails.transports,
                ruLatencyMs = null,
                ruLatencyTarget = null,
                downloadSeries = emptyList(),
                uploadSeries = emptyList()
            )

            // Параллельно пробуем получить публичный IP и провайдера.
            launch(Dispatchers.IO) {
                try {
                    val p = networkInfo.fetchPublicProviderInfo() ?: return@launch
                    val geo = listOfNotNull(p.country?.trim()?.takeIf { it.isNotBlank() }, p.city?.trim()?.takeIf { it.isNotBlank() })
                        .joinToString(", ")
                        .ifBlank { null }
                    _speedState.update {
                        it.copy(
                            publicIp = p.ip,
                            providerName = p.isp?.trim()?.takeIf { x -> x.isNotBlank() },
                            providerAsn = p.asn?.trim()?.takeIf { x -> x.isNotBlank() },
                            providerGeo = geo
                        )
                    }
                } catch (_: Exception) {
                    // игнорируем
                }
            }

            // Параллельно считаем "пинг до РФ" по набору RU-доменов.
            // Это не влияет на измерение скорости и не требует пользовательского выбора серверов.
            launch(Dispatchers.IO) {
                try {
                    // RuLatencyMeasurer уже использует cancelSignal из конструктора.
                    val ru = ruLatencyMeasurer.measure()
                    _speedState.update { it.copy(ruLatencyMs = ru.ms, ruLatencyTarget = ru.label) }
                } catch (_: Exception) {
                    // Не шумим: если не удалось, просто оставляем пусто.
                }
            }

            // Пытаемся несколько endpoint'ов подряд.
            // В режиме "Авто" сначала оцениваем кандидатов короткими probe (download+upload),
            // выбираем лучший и ставим его первым. Cloudflare не имеет приоритета по умолчанию.
            // В ручном режиме тестируем строго выбранный сервер.
            val endpointsToTry: List<SpeedEndpoint> = if (isAuto) {
                val best = chooseBestSpeedEndpoint(
                    endpoints = endpointsAll,
                    cancelSignal = { !_speedState.value.isRunning }
                )
                val ordered = listOf(best) + endpointsAll.filterNot { it.id == best.id }
                _speedState.update { it.copy(endpointTitle = best.title, statusText = "Выполняется") }
                ordered
            } else {
                _speedState.update { it.copy(endpointTitle = manual!!.title, statusText = "Выполняется") }
                listOf(manual!!)
            }

            var usedEndpoint = endpointsToTry.first()
            var result = com.stateofnetwork.net.SpeedTestOutcome(
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                latencyMs = 0L,
                jitterMs = null,
                bytesDown = 0L,
                bytesUp = 0L,
                durationMs = 0L,
                status = "error",
                error = "No endpoints",
                endpointHost = "unknown",
                endpointIp = null
            )

            for ((idx, ep) in endpointsToTry.withIndex()) {
                if (!_speedState.value.isRunning) break
                usedEndpoint = ep
                _speedState.update { it.copy(endpointTitle = ep.title, statusText = "Выполняется") }

                // Авто: прежде чем запускать полноценный тест (который может зависнуть на таймаутах),
                // делаем короткие probes. Если сервер не доступен/не принимает upload,
                // переключаемся сразу.
                if (isAuto) {
                    val preDown = withTimeoutOrNull(1_200L) {
                        speedTester.probeDownload(
                            endpointDown = ep.downUrl,
                            mode = ep.mode,
                            bytesToRead = 96 * 1024,
                            timeoutMs = 900L,
                            cancelSignal = { !_speedState.value.isRunning }
                        )
                    }
                    if (preDown == null || !preDown.ok) {
                        result = com.stateofnetwork.net.SpeedTestOutcome(
                            downloadMbps = 0.0,
                            uploadMbps = 0.0,
                            latencyMs = 0L,
                            jitterMs = null,
                            bytesDown = 0L,
                            bytesUp = 0L,
                            durationMs = 0L,
                            status = "error",
                            error = preDown?.error ?: "Download probe failed",
                            endpointHost = preDown?.endpointHost ?: "unknown",
                            endpointIp = preDown?.endpointIp
                        )
                        _speedState.update { it.copy(statusText = "Сервер недоступен. Пробуем другой…") }
                        continue
                    }

                    val preUp = withTimeoutOrNull(1_200L) {
                        speedTester.probeUpload(
                            endpointUp = ep.upUrl,
                            mode = ep.mode,
                            bytesToSend = 32 * 1024,
                            timeoutMs = 900L,
                            cancelSignal = { !_speedState.value.isRunning }
                        )
                    }
                    if (preUp == null || !preUp.ok) {
                        result = com.stateofnetwork.net.SpeedTestOutcome(
                            downloadMbps = 0.0,
                            uploadMbps = 0.0,
                            latencyMs = 0L,
                            jitterMs = null,
                            bytesDown = 0L,
                            bytesUp = 0L,
                            durationMs = 0L,
                            status = "error",
                            error = preUp?.error ?: "Upload probe failed",
                            endpointHost = preUp?.endpointHost ?: "unknown",
                            endpointIp = preUp?.endpointIp
                        )
                        _speedState.update { it.copy(statusText = "Upload не работает. Пробуем другой…") }
                        continue
                    }
                }

                val attempt = withContext(Dispatchers.IO) {
                    speedTester.runWithProgress(
                        endpointDown = ep.downUrl,
                        endpointUp = ep.upUrl,
                        mode = ep.mode,
                        durationMsPerDir = Config.speedDurationMsPerDirection,
                        parallelism = Config.speedParallelism,
                        cancelSignal = { !_speedState.value.isRunning },
                        onProgress = { p: SpeedProgress ->
                            _speedState.update { s ->
                                when (p.phase) {
                                    "download" -> s.copy(
                                        downloadMbps = p.mbps,
                                        downloadSeries = (s.downloadSeries + p.mbps).takeLast(120)
                                    )
                                    "upload" -> s.copy(
                                        uploadMbps = p.mbps,
                                        uploadSeries = (s.uploadSeries + p.mbps).takeLast(120)
                                    )
                                    else -> s
                                }
                            }
                        }
                    )
                }

                result = attempt
                // В ручном режиме не переключаем сервера.
                if (!isAuto) break

                // Авто: принимаем результат только если сервер реально отработал обе стороны.
                val okBothDirections = (attempt.status == "ok" || attempt.status == "partial") &&
                    (attempt.bytesDown > 0L) && (attempt.bytesUp > 0L)
                if (okBothDirections) break

                _speedState.update { it.copy(statusText = "Сервер дал неполный результат. Пробуем другой…") }
            }

            val s = _speedState.value
            val netType = networkInfo.getNetworkType()
            val ruPingMs = s.ruLatencyMs
            val ruPingTarget = s.ruLatencyTarget

            val entity = com.stateofnetwork.data.model.SpeedTestResultEntity(
                uuid = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                networkType = netType,
                endpointId = usedEndpoint.id,
                downloadMbps = result.downloadMbps,
                uploadMbps = result.uploadMbps,
                latencyMs = result.latencyMs,
                jitterMs = result.jitterMs,
                ruLatencyMs = ruPingMs,
                ruLatencyTarget = ruPingTarget,

                transportLabel = s.transportLabel,
                transportsDetail = s.transportsDetail,
                radioSummary = s.radioSummary,
                operatorName = s.operatorName,
                providerName = s.providerName,
                providerAsn = s.providerAsn,
                providerGeo = s.providerGeo,
                publicIp = s.publicIp,
                endpointHost = result.endpointHost,
                endpointIp = result.endpointIp,
                iface = s.iface,
                mtu = s.mtu,
                defaultGateway = s.defaultGateway,
                privateDns = s.privateDns,
                isValidated = s.isValidated,
                isCaptivePortal = s.isCaptivePortal,
                isMetered = s.isMetered,
                estDownKbps = s.estDownKbps,
                estUpKbps = s.estUpKbps,
                localIpsCsv = s.localIps.takeIf { it.isNotEmpty() }?.joinToString(", "),
                dnsServersCsv = s.dnsServers.takeIf { it.isNotEmpty() }?.joinToString(", "),

                bytesDown = result.bytesDown,
                bytesUp = result.bytesUp,
                durationMs = result.durationMs,
                status = result.status,
                error = result.error
            )

            if (result.status != "aborted") {
                speedDao.insert(entity)
            }

            // Запоминаем последние успешные скорости по endpoint'у.
            // Это помогает Auto-выбору не промахиваться из-за кратких провалов probe.
            val okForCache = (result.status == "ok" || result.status == "partial") &&
                result.bytesDown > 0L && result.bytesUp > 0L
            if (okForCache) {
                speedEndpointStore.recordLastResult(usedEndpoint.id, result.downloadMbps, result.uploadMbps)
            }

            val netDetails2 = networkInfo.getNetworkDetails()
            _speedState.value = _speedState.value.copy(
                isRunning = false,
                phase = "done",
                endpointHost = result.endpointHost,
                endpointIp = result.endpointIp,
                latencyMs = result.latencyMs,
                jitterMs = result.jitterMs,
                downloadMbps = result.downloadMbps,
                uploadMbps = result.uploadMbps,
                bytesDown = result.bytesDown,
                bytesUp = result.bytesUp,
                durationMs = result.durationMs,
                iface = netDetails2.interfaceName,
                localIps = netDetails2.localAddresses,
                dnsServers = netDetails2.dnsServers,
                defaultGateway = netDetails2.defaultGateway,
                mtu = netDetails2.mtu,
                privateDns = netDetails2.privateDns,
                isValidated = netDetails2.isValidated,
                isCaptivePortal = netDetails2.isCaptivePortal,
                isMetered = netDetails2.isMetered,
                estDownKbps = netDetails2.downstreamKbps,
                estUpKbps = netDetails2.upstreamKbps,
                transportsDetail = netDetails2.transports,
                statusText = when (result.status) {
                    "ok" -> "Завершено"
                    "partial" -> "Частично"
                    "aborted" -> "Прервано"
                    else -> "Ошибка"
                }
            )
        }
    }

    fun stopSpeedTest() {
        _speedState.update { it.copy(isRunning = false, statusText = "Остановка") }
    }


    fun setSpeedServerChoice(id: String) {
        val v = id.trim().ifBlank { "auto" }
        speedEndpointStore.save(v)
        _speedState.update { it.copy(serverChoiceId = v) }
    }

    fun getSpeedServerChoice(): String = _speedState.value.serverChoiceId

    fun getSpeedServerOptions(): List<SpeedEndpoint> = Config.speedEndpoints

    fun startDomainCheck(selectedSetIds: Set<String>) {
        // Если предыдущая проверка еще не завершилась, останавливаем ее.
        if (domainCheckJob?.isActive == true) {
            domainChecker.cancelOngoingChecks()
            domainCheckJob?.cancel()
        }

        domainCheckJob = viewModelScope.launch {
            val netType = networkInfo.getNetworkType()
            _domainItems.value = emptyList()
            _domainState.value = DomainUiState(isRunning = true, statusText = "Проверка выполняется", done = 0, total = 0, currentDomain = null)

            try {
                val run = withContext(Dispatchers.IO) {
                    domainChecker.runAndPersist(
                        selectedSetIds = selectedSetIds,
                        customDomains = _userDomains.value,
                        serviceOverrides = _serviceOverrides.value,
                        serviceRemoved = _serviceRemoved.value,
                        networkType = netType,
                        domainDao = domainDao,
                        onRunCreated = { id ->
                            // Фиксируем id текущей проверки сразу, чтобы при возврате на экран
                            // или переходе через "Последняя проверка" состояние не подменялось на "завершено".
                            _domainState.update { s -> s.copy(lastRunId = id) }
                        },
                        onProgress = { done, total, current ->
                            _domainState.update { s ->
                                s.copy(
                                    done = done,
                                    total = total,
                                    currentDomain = current,
                                    statusText = if (total > 0) "Проверено $done из $total" else "Проверка выполняется"
                                )
                            }
                        },
                        onItem = { item ->
                            // Показываем список доменов «живым»: как только домен проверен, он появляется в UI.
                            _domainItems.update { prev -> prev + item }
                        }
                    )
                }

                _domainItems.value = run.items

                // Итоговый статус фиксируется в БД (DomainCheckRunEntity.summaryStatus),
                // а DomainRunResult содержит только runId + items.
                val finalStatus = withContext(Dispatchers.IO) {
                    domainDao.getRunById(run.runId)?.summaryStatus
                } ?: "done"

                _domainState.value = _domainState.value.copy(
                    isRunning = false,
                    statusText = when (finalStatus) {
                        "done" -> "Завершено"
                        "cancelled" -> "Прервано"
                        "error" -> "Ошибка"
                        else -> "Завершено"
                    },
                    lastRunId = run.runId
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _domainState.update { it.copy(isRunning = false, statusText = "Прервано") }
                throw e
            }
        }
    }

    fun cancelDomainCheck() {
        // Сначала прерываем сетевые вызовы, затем отменяем корутину.
        domainChecker.cancelOngoingChecks()
        domainCheckJob?.cancel()
        _domainState.update { s -> s.copy(isRunning = false, statusText = "Прервано") }
    }

    fun loadDomainRun(runId: Long) {
        viewModelScope.launch {
            val (run, items) = withContext(Dispatchers.IO) {
                val r = domainDao.getRunById(runId)
                val it = domainDao.getItemsForRuns(listOf(runId)).sortedBy { x -> x.domain }
                r to it
            }

            _domainItems.value = items

            val summary = run?.summaryStatus ?: "done"
            val stillRunning = (summary == "running")
            _domainState.update { s ->
                // Важно: если run еще выполняется, не подменяем статус на "завершено".
                // Иначе при переходе через "Последняя проверка" пользователь увидит завершение,
                // хотя фоновые запросы еще идут.
                s.copy(
                    isRunning = stillRunning,
                    statusText = if (stillRunning) {
                        if (s.total > 0) "Проверено ${items.size} из ${s.total}" else "Проверка выполняется"
                    } else {
                        when (summary) {
                            "done" -> "Завершено"
                            "cancelled" -> "Прервано"
                            "error" -> "Ошибка"
                            else -> "Завершено"
                        }
                    },
                    lastRunId = runId,
                    done = items.size,
                    total = if (stillRunning) maxOf(s.total, items.size) else items.size,
                    currentDomain = null
                )
            }
        }
    }

    fun buildAndShareReport(periodFrom: Long, periodTo: Long, share: (title: String, mime: String, bytes: ByteArray) -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val reportBytes = withContext(Dispatchers.IO) {
                val speeds = speedDao.getBetween(periodFrom, periodTo)
                val runs = domainDao.getRunsBetween(periodFrom, periodTo)
                val items = domainDao.getItemsForRuns(runs.map { it.id })
                com.stateofnetwork.report.ReportBuilder.buildTxt(ctx, periodFrom, periodTo, speeds, runs, items)
            }
            share("state_of_network_report.txt", "text/plain", reportBytes)
        }
    }
}
