package com.stateofnetwork.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stateofnetwork.data.Config
import com.stateofnetwork.data.DomainGroup
import com.stateofnetwork.data.ServiceSet
import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.ui.vm.AppViewModel
import com.stateofnetwork.ui.components.GlassTextField
import com.stateofnetwork.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DomainStatus { AVAILABLE, UNAVAILABLE }

private fun DomainCheckItemEntity.status(): DomainStatus {
    // Пользовательский смысл "доступно": страница реально открывается (как минимум отдаёт валидный HTTP-ответ,
    // который не равен явному запрету/блокировке). DNS/TCP остаются диагностикой.

    // HTTPS-соединение не установилось — страницу открыть нельзя.
    if (!httpsOk) return DomainStatus.UNAVAILABLE

    val raw = errorType ?: ""
    val et = if (raw.startsWith("doh_")) raw.removePrefix("doh_") else raw

    // Явные транспортные/протокольные ошибки.
    if (et == "blocked" || et == "dns" || et == "tcp" || et == "https") return DomainStatus.UNAVAILABLE

    val code = httpCode

    // Критично: 403/451/5xx для пользователя означают, что страница не открывается.
    if (code == 403 || code == 451) return DomainStatus.UNAVAILABLE
    if (code != null && code in 500..599) return DomainStatus.UNAVAILABLE

    return when {
        code == null -> DomainStatus.UNAVAILABLE
        code in 200..399 -> DomainStatus.AVAILABLE
        code == 401 || code == 404 -> DomainStatus.AVAILABLE
        // Антибот/"проверка браузера" не должна автоматически становиться "доступно".
        // Статус зависит от фактического HTTP-кода; если он не попал в допустимые — считаем недоступно.
        et == "challenge" -> DomainStatus.UNAVAILABLE
        else -> DomainStatus.UNAVAILABLE
    }
}

private fun mapSetIdsToTitles(ids: List<String>): String {
    if (ids.isEmpty()) return "-"
    val titles = ids.map { id ->
        when (id.lowercase()) {
            "custom" -> "Мои домены"
            "whitelist" -> "Белые списки"
            "blocked" -> "Заблокированные ресурсы"
            else -> Config.serviceSets.firstOrNull { it.id.equals(id, ignoreCase = true) }?.title ?: id
        }
    }
    return titles.joinToString(", ")
}

private fun statusLabel(s: DomainStatus): String = when (s) {
    DomainStatus.AVAILABLE -> "Доступно"
    DomainStatus.UNAVAILABLE -> "Недоступно"
}

private fun statusColor(s: DomainStatus): Color = when (s) {
    DomainStatus.AVAILABLE -> AppColors.Success
    DomainStatus.UNAVAILABLE -> AppColors.Error
}

private data class WhitelistDiagnosis(
    val title: String,
    val details: String,
    val color: Color
)

private fun computeWhitelistDiagnosis(items: List<DomainCheckItemEntity>): WhitelistDiagnosis? {
    val whitelistSet = Config.serviceSets.firstOrNull { it.id == "whitelist" } ?: return null
    val whitelist = whitelistSet.domains.filter { it.group == DomainGroup.WHITELIST }.map { it.domain }.toSet()
    val control = whitelistSet.domains.filter { it.group == DomainGroup.CONTROL }.map { it.domain }.toSet()

    if (whitelist.isEmpty() || control.isEmpty()) return null

    val byDomain = items.associateBy { it.domain }
    val wlItems = whitelist.mapNotNull { byDomain[it] }
    val ctlItems = control.mapNotNull { byDomain[it] }

    if (wlItems.isEmpty() || ctlItems.isEmpty()) return null

    // Для диагностики "белых списков" важна именно открываемость сайта.
    val wlOk = wlItems.count { it.status() == DomainStatus.AVAILABLE }
    val ctlOk = ctlItems.count { it.status() == DomainStatus.AVAILABLE }

    val wlOkRate = wlOk.toDouble() / wlItems.size.toDouble()
    val ctlOkRate = ctlOk.toDouble() / ctlItems.size.toDouble()

    return when {
        wlOkRate >= 0.75 && ctlOkRate <= 0.35 -> {
            WhitelistDiagnosis(
                title = "Высокая вероятность режима белых списков",
                details = "Доменов из белого списка доступно ${wlOk}/${wlItems.size}; контрольных доменов доступно ${ctlOk}/${ctlItems.size}.\n" +
                    "Если при этом сохраняются проблемы с другими сайтами, это похоже на ограничение доступа по whitelist.",
                color = AppColors.Error
            )
        }

        ctlOkRate >= 0.75 -> {
            WhitelistDiagnosis(
                title = "Признаков режима белых списков не наблюдается",
                details = "Контрольные домены доступны ${ctlOk}/${ctlItems.size}; белый список доступен ${wlOk}/${wlItems.size}.\n" +
                    "Это больше похоже на нормальную работу интернета или на точечные блокировки отдельных сервисов.",
                color = AppColors.Success
            )
        }

        wlOkRate <= 0.35 && ctlOkRate <= 0.35 -> {
            WhitelistDiagnosis(
                title = "Скорее общая проблема связи",
                details = "Плохо доступны и белый список (${wlOk}/${wlItems.size}), и контрольные домены (${ctlOk}/${ctlItems.size}).\n" +
                    "Вероятнее всего это сбой связи, DNS или маршрутизации, а не режим белых списков.",
                color = AppColors.Warning
            )
        }

        else -> {
            WhitelistDiagnosis(
                title = "Результат неоднозначный",
                details = "Белый список доступен ${wlOk}/${wlItems.size}; контрольные домены доступны ${ctlOk}/${ctlItems.size}.\n" +
                    "Наблюдается частичная деградация. Для уверенного вывода нужны повторные замеры и, желательно, другая сеть.",
                color = AppColors.Warning
            )
        }
    }
}

@Composable
fun DomainCheckScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.domainState.collectAsState()
    val items by vm.domainItems.collectAsState()
    val userDomains by vm.userDomains.collectAsState()
    val overrides by vm.serviceOverrides.collectAsState()
    val removed by vm.serviceRemoved.collectAsState()
    val lastRun by vm.lastDomainRun.collectAsState(initial = null)

    var selectedSetIds by remember { mutableStateOf(setOf("whitelist")) }
    var includeCustom by remember { mutableStateOf(false) }

    var showFaq by remember { mutableStateOf(false) }
    var showWhitelistWarn by remember { mutableStateOf(false) }
    var whitelistWarnText by remember { mutableStateOf("") }
    var pendingStartSets by remember { mutableStateOf<Set<String>?>(null) }

    var showRunScreen by rememberSaveable { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }

    var domainToAddCustom by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") } // ALL / OK / DOWN
    var query by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // Индикация «живости» процесса: отдельные домены могут зависать на таймаутах.
    // Время/оценку оставшегося не показываем (слишком неточно и раздражает),
    // поэтому оставляем только анимацию и текущий домен.
    val animDots by produceState(initialValue = 0, key1 = state.isRunning) {
        if (!state.isRunning) {
            value = 0
            return@produceState
        }
        var i = 0
        while (true) {
            value = i % 4
            i++
            delay(350)
        }
    }
    val progressDots = remember(animDots) { if (animDots == 0) "" else ".".repeat(animDots) }

    val stats = remember(items) {
        val ok = items.count { it.status() == DomainStatus.AVAILABLE }
        val down = items.size - ok
        Pair(ok, down)
    }

    val filtered = remember(items, filter, query) {
        val q = query.trim().lowercase()
        items
            .asSequence()
            .filter {
                when (filter) {
                    "OK" -> it.status() == DomainStatus.AVAILABLE
                    "DOWN" -> it.status() == DomainStatus.UNAVAILABLE
                    else -> true
                }
            }
            .filter { q.isBlank() || it.domain.contains(q) }
            .sortedWith(compareBy<DomainCheckItemEntity>({ it.status() }, { it.domain }))
            .toList()
    }

    // После завершения — поднимаем пользователя к результатам.
    LaunchedEffect(state.isRunning, items.size, showRunScreen) {
        if (showRunScreen && !state.isRunning && items.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            com.stateofnetwork.ui.components.GlassTopBar(
                title = "Проверка сервисов",
                leading = {
                    Text(
                        text = "Назад",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .clickable { if (showRunScreen) showRunScreen = false else onBack() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                },
                trailing = {
                    IconButton(onClick = { showFaq = true }) {
                        Icon(imageVector = Icons.Filled.Info, contentDescription = "FAQ")
                    }
                }
            )
        },
        bottomBar = {
            com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (showRunScreen) {
                    if (state.isRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            com.stateofnetwork.ui.components.GlassSecondaryButton(
                                text = "Остановить",
                                onClick = { showStopConfirm = true },
                                modifier = Modifier.weight(1f),
                                minHeight = 54.dp
                            )
                            com.stateofnetwork.ui.components.GlassSecondaryButton(
                                text = "Свернуть",
                                onClick = { showRunScreen = false },
                                modifier = Modifier.weight(1f),
                                minHeight = 54.dp
                            )
                        }
                    } else {
                        com.stateofnetwork.ui.components.GlassSecondaryButton(
                            text = "К настройкам",
                            onClick = { showRunScreen = false },
                            modifier = Modifier.fillMaxWidth(),
                            minHeight = 54.dp
                        )
                    }
                } else {
                    val buttonText = if (state.isRunning) "Проверка выполняется" else "Начать проверку"
                    com.stateofnetwork.ui.components.GlassPrimaryButton(
                        text = buttonText,
                        onClick = click@{
                            if (!state.isRunning) {
                                val final = buildSet {
                                    addAll(selectedSetIds)
                                    if (includeCustom) add("custom")
                                }
                                // Предупреждение: для режима "Белые списки" лучше проверять без VPN/Private DNS.
                                // Делаем детект тем же путём, что и на экране теста скорости: через NetworkInfoProvider.
                                if (final.contains("whitelist")) {
                                    val snap = vm.getVpnDnsSnapshot()
                                    if (snap.hasVpn || snap.hasPrivateDns) {
                                        pendingStartSets = final
                                        whitelistWarnText = buildString {
                                            append("Для проверки белых списков лучше выключить VPN и Private DNS.\n\n")
                                            append("Сейчас: ")
                                            append(snap.transportLabel)
                                            if (snap.hasPrivateDns) {
                                                append("\nPrivate DNS: ")
                                                append(snap.privateDns ?: "активен")
                                            }
                                        }
                                        showWhitelistWarn = true
                                        return@click
                                    }
                                }
                                showRunScreen = true
                                vm.startDomainCheck(final)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        containerColor = Color.Transparent
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (showWhitelistWarn) {
                AlertDialog(
                    onDismissRequest = {
                        showWhitelistWarn = false
                        pendingStartSets = null
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val sets = pendingStartSets
                                showWhitelistWarn = false
                                pendingStartSets = null
                                if (sets != null) {
                                    showRunScreen = true
                                    vm.startDomainCheck(sets)
                                }
                            }
                        ) { Text("Продолжить") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showWhitelistWarn = false
                                pendingStartSets = null
                            }
                        ) { Text("Отмена") }
                    },
                    title = { Text("VPN/Private DNS") },
                    text = { Text(whitelistWarnText) }
                )
            }
            if (showFaq) {
                AlertDialog(
                    onDismissRequest = { showFaq = false },
                    confirmButton = {
                        TextButton(onClick = { showFaq = false }) { Text("Понятно") }
                    },
                    title = { Text("Справка") },
                    text = {
                        Text(
                            "Проверка выполняется прямо на телефоне. Для каждого домена приложение делает DNS-резолвинг, пытается установить TCP/HTTPS-соединение и выполняет HTTPS-запрос.\n\n" +
                                "Режим \"Белые списки\": помогает предположить, включены ли региональные ограничения. Логика такая: домены из белого списка обычно остаются доступны, а часть обычных доменов может перестать открываться.\n\n" +
                                "Режим \"Заблокированные ресурсы\": показывает, открывается ли сайт без VPN/DNS-обхода. Если сервер отвечает 403/451 или соединение обрывается на DNS/TCP/TLS, это трактуется как недоступность для пользователя."
                        )
                    }
                )
            }
            if (showStopConfirm) {
                AlertDialog(
                    onDismissRequest = { showStopConfirm = false },
                    title = { Text("Остановить проверку") },
                    text = { Text("Прервать текущую проверку доменов?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showStopConfirm = false
                                vm.cancelDomainCheck()
                            }
                        ) { Text("Да") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStopConfirm = false }) { Text("Нет") }
                    },
                    // Диалог подтверждения остановки должен быть полностью читаемым,
                    // поэтому убираем прозрачность и используем непрозрачную поверхность.
                    containerColor = AppColors.Surface,
                    titleContentColor = AppColors.OnSurface,
                    textContentColor = AppColors.OnSurfaceVariant
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (showRunScreen) {
                    item {
                        com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val total = state.total
                                val done = state.done
                                val current = state.currentDomain
                                val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f

                                Text(
                                    text = if (state.isRunning) "Проверка выполняется" + progressDots else "Проверка завершена",
                                    style = MaterialTheme.typography.titleLarge
                                )

                                LinearProgressIndicator(
                                    progress = if (state.isRunning) progress else 1f,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = AppColors.Primary,
                                    trackColor = AppColors.GlassFill
                                )

                                Text(
                                    text = when {
                                        total > 0 && state.isRunning -> "Проверено $done из $total"
                                        total > 0 && !state.isRunning -> "Завершено: $done из $total"
                                        state.isRunning -> "Подготовка списка доменов"
                                        else -> "Готово"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                if (!current.isNullOrBlank()) {
                                    Text("Текущий домен: $current", style = MaterialTheme.typography.bodySmall)
                                }

                                if (items.isNotEmpty() && !state.isRunning) {
                                    val (ok, down) = stats
                                    Text("Итог: доступно $ok, недоступно $down", style = MaterialTheme.typography.bodyMedium)

                                    if (selectedSetIds.contains("whitelist")) {
                                        val diag = computeWhitelistDiagnosis(items)
                                        if (diag != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.dp)
                                                        .background(diag.color.copy(alpha = 0.75f))
                                                )
                                                Text(
                                                    "Белые списки",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = diag.color
                                                )
                                                Text(
                                                    diag.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = diag.color
                                                )
                                                Text(diag.details, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        "Некоторые сайты могут отвечать долго. Это нормально, проверка продолжается.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.OnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (items.isNotEmpty()) {
                        item {
                            com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Результаты", style = MaterialTheme.typography.titleMedium)

                                    GlassTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = "Поиск по домену",
                                        singleLine = true
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(text = "Все", selected = filter == "ALL") { filter = "ALL" }
                                        FilterChip(text = "Доступно", selected = filter == "OK") { filter = "OK" }
                                        FilterChip(text = "Недоступно", selected = filter == "DOWN") { filter = "DOWN" }
                                    }

                                    Divider()

                                    Text(
                                        "Показано: ${filtered.size} из ${items.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.OnSurfaceVariant
                                    )
                                }
                            }
                        }

                        items(filtered, key = { it.domain + ":" + it.port }) { item ->
                            DomainResultRow(item)
                        }
                    }

                    item { Spacer(Modifier.height(60.dp)) }

                } else {

                    if (lastRun != null) {
                        val run = lastRun!!
                        item {
                            com.stateofnetwork.ui.components.GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Если проверка ещё выполняется, НЕ подменяем состояние на "завершено".
                                        // Просто открываем экран прогресса. Если VM уже ведёт проверку — он и так
                                        // будет обновлять state/items. Если VM не ведёт (например, процесс был убит),
                                        // подгружаем run и items из БД, сохраняя статус "running".
                                        showRunScreen = true
                                        if (run.summaryStatus == "running") {
                                            // Подстрахуемся: если по какой-то причине в VM нет текущих items,
                                            // подтянем их. loadDomainRun теперь уважает summaryStatus и не ставит "Завершено".
                                            if (state.lastRunId != run.id || items.isEmpty()) {
                                                vm.loadDomainRun(run.id)
                                            }
                                        } else {
                                            vm.loadDomainRun(run.id)
                                        }
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Последняя проверка", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Открыть",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = AppColors.Secondary
                                        )
                                    }

                                    Text(
                                        "Время: ${df.format(Date(run.timestamp))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.OnSurfaceVariant
                                    )

                                    val selected = run.selectedServiceSets
                                        .split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }

                                    if (selected.isNotEmpty()) {
                                        Text(
                                            "Наборы: ${mapSetIdsToTitles(selected)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppColors.OnSurfaceVariant
                                        )
                                    }

                                    val isLastLoaded = (state.lastRunId == run.id && items.isNotEmpty())
                                    if (isLastLoaded) {
                                        val av = items.count { it.status() == DomainStatus.AVAILABLE }
                                        val un = items.count { it.status() == DomainStatus.UNAVAILABLE }
                                        val countsText = "Доступно: $av, недоступно: $un"

                                        if (selected.any { it.equals("whitelist", ignoreCase = true) }) {
                                            val diag = computeWhitelistDiagnosis(items)
                                            val diagText = diag?.title ?: "неопределено"
                                            Text("Белые списки: $diagText", style = MaterialTheme.typography.bodyMedium)
                                            Text(countsText, style = MaterialTheme.typography.bodySmall, color = AppColors.OnSurfaceVariant)
                                        } else {
                                            Text(countsText, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    } else {
                                        val statusText = when (run.summaryStatus) {
                                            "available" -> "Вывод: похоже, доступ есть"
                                            "unavailable" -> "Вывод: похоже, есть ограничения"
                                            "running" -> "Вывод: проверка выполняется"
                                            else -> "Вывод: -"
                                        }
                                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                                    }

                                    // Диагностика (whitelist) выводится выше вместе с счетчиками.
                                }
                            }
                        }
                    }

                    item {
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                        if (selectedSetIds.contains("whitelist")) {
                            com.stateofnetwork.ui.components.GlassCard(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Белые списки", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Идея простая: если доступны домены из белого списка, но многие обычные сайты не открываются, это похоже на режим ограниченного доступа. Для проверки выберите набор \"whitelist\" и запустите тест.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Подробности и примеры есть в FAQ (значок в правом верхнем углу).",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Text(
                            "Режим проверки",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.OnSurface
                        )
                    }

                    items(Config.serviceSets, key = { it.id }) { set ->
                        // Overrides для набора храним как "добавленные вручную" домены (extras), а не как замену базового списка.
                        val baseDomains = set.domains.map { it.domain }
                        val extraDomains = overrides[set.id].orEmpty()
                        val removedDomains = removed[set.id].orEmpty()

                        // Эффективный список = базовый + добавленные - исключённые.
                        val effectiveDomains = (baseDomains + extraDomains)
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .filterNot { removedDomains.contains(it) }
                            .distinct()

                        ServiceSetCard(
                            set = set,
                            selected = selectedSetIds.contains(set.id),
                            effectiveDomains = effectiveDomains,
                            extraDomains = extraDomains.toSet(),
                            onToggle = {
                                selectedSetIds = if (selectedSetIds.contains(set.id)) {
                                    selectedSetIds - set.id
                                } else {
                                    selectedSetIds + set.id
                                }
                            },
                            onAddDomains = { raws -> raws.forEach { vm.addDomainToServiceSet(set.id, it) } },
                            onRemoveDomain = { d -> vm.removeDomainFromServiceSet(set.id, d) }
                        )
                    }

                    item {
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                        Text("Мои домены (вручную)", style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
                        Spacer(Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeCustom, onCheckedChange = { includeCustom = it })
                            Spacer(Modifier.width(6.dp))
                            Text("Включать мои домены в проверку", color = AppColors.OnSurface)
                        }

                        GlassTextField(
                            value = domainToAddCustom,
                            onValueChange = { domainToAddCustom = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "Добавить домены (вставьте список: по одному в строке или через пробел/запятую)",
                            singleLine = false
                        )
                        Spacer(Modifier.height(8.dp))
                        com.stateofnetwork.ui.components.GlassPrimaryButton(
                            text = "Добавить",
                            onClick = {
                                val parts = domainToAddCustom
                                    .split(',', '\n', '\t', ' ')
                                    .map { it.trim().lowercase() }
                                    .filter { it.isNotBlank() }
                                    .filterNot { it.contains("://") || it.contains("/") }
                                parts.forEach { vm.addUserDomain(it) }
                                domainToAddCustom = ""
                            },
                            minHeight = 44.dp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (userDomains.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Ваш список", style = MaterialTheme.typography.titleSmall)
                                    userDomains.sorted().forEach { d ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            com.stateofnetwork.ui.components.GlassSecondaryButton(
                                                text = "Удалить",
                                                onClick = { vm.removeUserDomain(d) },
                                                minHeight = 34.dp,
                                                modifier = Modifier.wrapContentWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(60.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    // Внутри стеклянных карточек чипы не должны создавать ощущение "вложенных прямоугольников".
    // Поэтому у невыбранного состояния убираем заливку полностью: остаётся только тонкий контур.
    val fill = if (selected) com.stateofnetwork.ui.theme.AppColors.Primary.copy(alpha = 0.18f) else Color.Transparent
    val border = if (selected) {
        com.stateofnetwork.ui.theme.AppColors.Primary.copy(alpha = 0.55f)
    } else {
        com.stateofnetwork.ui.theme.AppColors.GlassBorder.copy(alpha = 0.85f)
    }
    val fg = com.stateofnetwork.ui.theme.AppColors.OnSurface

    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(fill)
            .border(1.dp, border, androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = fg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DomainResultRow(item: DomainCheckItemEntity) {
    val s = item.status()
    val c = statusColor(s)
    val label = statusLabel(s)

    val raw = item.errorType
    val isDoh = raw?.startsWith("doh_") == true
    val et = if (isDoh) raw!!.removePrefix("doh_") else raw

    val code = item.httpCode
    val reason = when {
        et == null && code != null -> "HTTP $code"
        et == null -> "-"
        et == "dns" -> "DNS"
        et == "tcp" -> "TCP"
        et == "https" -> "HTTPS"
        et == "blocked" -> "Блокировка"
        et == "challenge" -> "Проверка браузера"
        else -> if (code != null) "HTTP $code" else et
    } + if (isDoh) " (DoH)" else ""

    val explanation = when {
        et == "dns" -> "DNS: не удалось получить IP-адрес (сбой резолвинга, DNS-фильтрация или проблемы сети)."
        et == "tcp" -> "TCP: не удалось установить соединение (обычно 443/80). Возможна фильтрация, блокировка или отсутствие маршрута."
        et == "https" -> "HTTPS: не удалось установить TLS-соединение. Частые причины: DPI/SNI-фильтрация, подмена сертификата, неверное время на устройстве."
        et == "blocked" || code == 451 -> "Доступ ограничен (типичный признак блокировки провайдером/РКН или юридических ограничений)."
        code == 403 -> "HTTP 403: сервер отказал в доступе. Для пользователя это означает, что страница не открывается (блокировка/гео-ограничение/WAF)."
        code in 500..599 -> "HTTP ${code}: ошибка на стороне сервера."
        code in 400..499 && code != 401 && code != 404 -> "HTTP ${code}: запрос отклонен."
        et == "challenge" -> "Сайт требует подтверждения (антибот-защита). В браузере может открыться страница проверки."
        code == 401 -> "HTTP 401: требуется авторизация (сайт доступен, но нужен вход)."
        code == 404 -> "HTTP 404: страница не найдена (сайт доступен, но по этому URL нет ресурса)."
        isDoh -> "Системный DNS/маршрут не дал открыть сайт, но через DoH получилось. Это похоже на DNS-фильтрацию."
        else -> null
    }

    val hasIssue = (raw != null) || (code != null && code !in 200..399)

    com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.domain, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.titleMedium, color = c)
            }

            Text("Причина: $reason", style = MaterialTheme.typography.bodySmall)

            // По требованию: даже если статус "Доступно", но есть ошибка HTTPS/HTTP,
            // показываем ее как справочную информацию.
            if (hasIssue && !explanation.isNullOrBlank()) {
                Text(explanation, style = MaterialTheme.typography.bodySmall)
            }

            if (s == DomainStatus.UNAVAILABLE && hasIssue && Config.isLikelyRknBlocked(item.domain)) {
                Text(
                    "В РФ доступ может быть ограничен (РКН/локальная фильтрация)",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Warning
                )
            }
        }
    }
}

@Composable
private fun ServiceSetCard(
    set: ServiceSet,
    selected: Boolean,
    effectiveDomains: List<String>,
    extraDomains: Set<String>,
    onToggle: () -> Unit,
    onAddDomains: (List<String>) -> Unit,
    onRemoveDomain: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var toAdd by remember { mutableStateOf("") }

    com.stateofnetwork.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() })
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(set.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Доменов: ${effectiveDomains.size}", style = MaterialTheme.typography.bodySmall)
                        if (set.id == "whitelist") {
                            Text("Диагностика режима белых списков", style = MaterialTheme.typography.bodySmall)
                        } else if (set.id == "blocked") {
                            Text("Проверка доступа к блокируемым ресурсам (полезно с VPN/DNS)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                com.stateofnetwork.ui.components.GlassSecondaryButton(
                    text = if (expanded) "Скрыть" else "Список (${effectiveDomains.size})",
                    onClick = { expanded = !expanded },
                    minHeight = 36.dp,
                    modifier = Modifier.wrapContentWidth()
                )
            }

            if (expanded) {
                Divider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    effectiveDomains.sorted().forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.stateofnetwork.ui.components.GlassSecondaryButton(
                                text = "Удалить",
                                onClick = { onRemoveDomain(d) },
                                minHeight = 34.dp,
                                modifier = Modifier.wrapContentWidth()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = toAdd,
                    onValueChange = { toAdd = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Добавить домены (по одному в строке или через пробел/запятую)",
                    singleLine = false
                )
                Spacer(Modifier.height(8.dp))
                com.stateofnetwork.ui.components.GlassPrimaryButton(
                    text = "Добавить",
                    onClick = {
                        val parts = toAdd
                            .split(',', '\n', '\t', ' ')
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .filterNot { it.contains("://") || it.contains("/") }
                        if (parts.isNotEmpty()) onAddDomains(parts)
                        toAdd = ""
                    },
                    minHeight = 44.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


