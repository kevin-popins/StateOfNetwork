package com.stateofnetwork.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.stateofnetwork.ui.components.LineChart
import com.stateofnetwork.ui.components.GlassCard
import com.stateofnetwork.ui.components.GlassChip
import com.stateofnetwork.ui.components.GlassChipButton
import com.stateofnetwork.ui.components.GlassPrimaryButton
import com.stateofnetwork.ui.components.GlassSecondaryButton
import com.stateofnetwork.ui.components.GlassTopBar
import com.stateofnetwork.ui.vm.AppViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.stateofnetwork.ui.theme.AppColors
import com.stateofnetwork.data.Config


// Вспомогательная функция для плавного перехода цвета.
// Используем androidx.compose.ui.graphics.lerp и ограничиваем коэффициент.
private fun lerpColor(a: Color, b: Color, t: Float): Color = lerp(a, b, t.coerceIn(0f, 1f))


private fun qualityColorForMbps(mbps: Double?): Color {
    if (mbps == null) return AppColors.OnSurfaceVariant
    return when {
        mbps < 20.0 -> AppColors.Error
        mbps < 80.0 -> AppColors.Warning
        else -> AppColors.Success
    }
}

// Цветовая индикация именно для скоростей (Мбит/с): 0–5 красный, 5–15 переход к оранжевому,
// 15–25 переход к зеленому, 25+ зеленый.
private fun speedColorForMbps(mbps: Double?): Color {
    if (mbps == null) return Color.Unspecified
    val v = mbps.coerceAtLeast(0.0)
    return when {
        v <= 5.0 -> AppColors.Error
        v <= 15.0 -> lerpColor(AppColors.Error, AppColors.Warning, ((v - 5.0) / 10.0).toFloat())
        v <= 25.0 -> lerpColor(AppColors.Warning, AppColors.Success, ((v - 15.0) / 10.0).toFloat())
        else -> AppColors.Success
    }
}

private data class InternetVerdict(
    val score: Int,
    val label: String,
    val video: String,
    val note: String
)

private fun internetVerdict(down: Double?, up: Double?, latency: Long?, jitter: Long?): InternetVerdict? {
    if (down == null && up == null && latency == null) return null
    val d = down ?: 0.0
    val u = up ?: 0.0
    val lat = latency ?: 999L
    val jit = jitter ?: 999L

    val video = when {
        d >= 25 -> "4K"
        d >= 12 -> "2K"
        d >= 6 -> "FullHD"
        d >= 3 -> "HD"
        d >= 1.5 -> "SD"
        else -> "—"
    }

    // Шкала 1..5 с более щадящими порогами (ориентир на мобильные сети).
    var score = 1
    if (d >= 3) score = 2
    if (d >= 8) score = 3
    if (d >= 20) score = 4
    if (d >= 60) score = 5

    // Стабильность важнее абсолютных чисел: штрафуем за высокий джиттер и очень большую задержку.
    if (jit > 80) score -= 1
    if (jit > 150) score -= 1
    if (lat > 200) score -= 1
    if (lat > 350) score -= 1
    score = score.coerceIn(1, 5)

    val label = when (score) {
        5 -> "отлично"
        4 -> "очень хорошо"
        3 -> "нормально"
        2 -> "слабо"
        else -> "плохо"
    }

    val stability = when {
        lat <= 80 && jit <= 40 -> "высокая"
        lat <= 140 && jit <= 80 -> "средняя"
        else -> "низкая"
    }

    val note = buildString {
        append("Стабильность: ")
        append(stability)
        if (u > 0) {
            append(". Отдача: ")
            append(when {
                u >= 10 -> "хорошая"
                u >= 3 -> "достаточная"
                u > 0 -> "слабая"
                else -> "—"
            })
        }
    }

    return InternetVerdict(score = score, label = label, video = video, note = note)
}

@Composable
fun SpeedTestScreen(vm: AppViewModel, onBack: () -> Unit, autoStart: Boolean = false) {
    val state by vm.speedState.collectAsState()

    var estMb by remember { mutableStateOf(0) }
    var askMobile by remember { mutableStateOf(false) }

    var showServerPicker by remember { mutableStateOf(false) }

    var showFaq by remember { mutableStateOf(false) }

    // При каждом входе на экран теста скорости всегда выставляем режим "Авто (лучший)".
    // Это не мешает ручному выбору внутри экрана, но гарантирует предсказуемый дефолт
    // при повторном открытии раздела.
    LaunchedEffect(Unit) {
        if (!state.isRunning) {
            vm.setSpeedServerChoice("auto")
        }
    }

    // Если пришли со стартового экрана по кнопке "Начать" — запускаем тест автоматически.
    LaunchedEffect(autoStart) {
        if (autoStart && !state.isRunning && state.downloadMbps == null && state.uploadMbps == null) {
            // На авто-старте тоже принудительно фиксируем дефолтный выбор.
            vm.setSpeedServerChoice("auto")
            vm.startSpeedTest(forceMobileConfirm = false) { mb ->
                estMb = mb
                askMobile = true
            }
        }
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 18.dp)
    ) {
        GlassTopBar(
            title = "Тест скорости",
            leading = {
                Text(
                    text = "Назад",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            },
            trailing = {
                IconButton(onClick = { showFaq = true }) {
                    Icon(imageVector = Icons.Filled.Info, contentDescription = "FAQ")
                }
            }
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.Secondary,
                    trackColor = AppColors.GlassFill
                )
            }

            // Статус строкой, но без визуального шума.
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Статус:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = state.statusText.ifBlank { "Готово" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }


            val options = remember { vm.getSpeedServerOptions() }
            val choiceId = state.serverChoiceId.trim().ifBlank { "auto" }
            val choiceTitle = if (choiceId == "auto") {
                "Авто (лучший)"
            } else {
                options.firstOrNull { it.id == choiceId }?.title ?: choiceId
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Сервер:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassChipButton(
                        text = choiceTitle,
                        onClick = { if (!state.isRunning) showServerPicker = true },
                    )
                }
            }

            val down = state.downloadMbps
            val up = state.uploadMbps
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricBig(
                            title = "Скачивание",
                            value = down?.let { String.format("%.2f", it) } ?: "—",
                            unit = "Мбит/с",
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                        MetricBig(
                            title = "Отдача",
                            value = up?.let { String.format("%.2f", it) } ?: "—",
                            unit = "Мбит/с",
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    val lat = state.latencyMs
                    val jit = state.jitterMs
                    val ruLat = state.ruLatencyMs
                    val ruHost = state.ruLatencyTarget
                    val serverLabel = when {
                        state.endpointHost.isNotBlank() -> "Сервер: ${state.endpointHost}"
                        state.endpointTitle.isNotBlank() -> "Сервер: ${state.endpointTitle}"
                        else -> ""
                    }

                    if (lat != null || jit != null || ruLat != null || serverLabel.isNotBlank()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 1 строка: Пинг РФ + Ping
	                            Row(
	                                horizontalArrangement = Arrangement.spacedBy(10.dp),
	                                modifier = Modifier.fillMaxWidth()
	                            ) {
	                                val ruSuffix = if (!ruHost.isNullOrBlank() && ruHost != "—") " (${ruHost})" else ""
                                GlassChip(
	                                    text = if (ruLat != null && ruLat > 0) "Пинг РФ: ${ruLat} мс$ruSuffix" else "Пинг РФ: —",
	                                    modifier = Modifier.weight(1f),
	                                )
                                GlassChip(
	                                    text = if (jit != null && jit > 0) "Jitter: ${jit} мс" else "Jitter: —",
	                                    modifier = Modifier.weight(1f),
	                                )
	                            }
                        }
                    }

                    // Тип сети, оператор и (по возможности) публичный провайдер/IP.
                    if (!state.transportLabel.isNullOrBlank() || !state.radioSummary.isNullOrBlank() || !state.operatorName.isNullOrBlank() || !state.providerName.isNullOrBlank() || !state.publicIp.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        val lineStyle = MaterialTheme.typography.bodySmall
                        val lineColor = MaterialTheme.colorScheme.onSurfaceVariant

                        if (!state.transportLabel.isNullOrBlank()) {
                            Text("Тип сети: ${state.transportLabel}", style = lineStyle, color = lineColor)
                        }
                        if (!state.radioSummary.isNullOrBlank()) {
                            Text("Радио-параметры: ${state.radioSummary}", style = lineStyle, color = lineColor)
                        }
                        if (!state.operatorName.isNullOrBlank()) {
                            Text("Оператор: ${state.operatorName}", style = lineStyle, color = lineColor)
                        }
                        if (!state.providerName.isNullOrBlank() || !state.publicIp.isNullOrBlank()) {
                            val parts = buildString {
                                if (!state.providerName.isNullOrBlank()) append(state.providerName)
                                if (!state.providerAsn.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(state.providerAsn)
                                }
                                if (!state.providerGeo.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(state.providerGeo)
                                }
                            }
                            val ip = state.publicIp?.takeIf { it.isNotBlank() }
                            if (!parts.isBlank()) {
                                Text("Провайдер: $parts", style = lineStyle, color = lineColor)
                            }
                            if (!ip.isNullOrBlank()) {
                                Text("Публичный IP: $ip", style = lineStyle, color = lineColor)
                            }
                        }
                    }

                    if (state.endpointHost.isNotBlank()) {
                        val ip = state.endpointIp ?: "не определен"
                        Text(
                            "Хост: ${state.endpointHost} (IP: $ip)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
						val hostPing = state.latencyMs
						if (hostPing != null && hostPing > 0) {
							Text(
								"Пинг до хоста: ${hostPing} мс",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
                    }

                    val hasSpeedResult = !state.isRunning && (down != null || up != null)
                    if (hasSpeedResult) {
                        val verdict = internetVerdict(down, up, state.latencyMs, state.jitterMs)
                        if (verdict != null) {
                            Spacer(Modifier.height(2.dp))
                            val scoreColor = scoreColor(verdict.score)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                GlassChip(
                                    text = "Оценка: ${verdict.score}.0 · ${verdict.label}",
                                    textColor = scoreColor
                                )
                                GlassChip(text = "Видео: до ${verdict.video}")
                            }
                            Text(verdict.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Действия должны быть видны сразу после основной информации, до графиков.
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val mainText = if (state.isRunning) "Остановить" else if (state.downloadMbps != null || state.uploadMbps != null) "Повторить тест" else "Начать тест"

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassPrimaryButton(
                            text = mainText,
                            onClick = {
                                if (state.isRunning) {
                                    vm.stopSpeedTest()
                                } else {
                                    vm.startSpeedTest(forceMobileConfirm = false) { mb ->
                                        estMb = mb
                                        askMobile = true
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GlassSecondaryButton(
                            text = "Назад",
                            onClick = onBack,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // Графики компактно: в одну строку, меньше по высоте, без шкалы Y (иначе не влезает).
            val hasCharts = state.downloadSeries.isNotEmpty() || state.uploadSeries.isNotEmpty()
            if (hasCharts) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.downloadSeries.isNotEmpty()) {
                        GlassCard(modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LineChart(
                                    title = "Скачивание",
                                    values = state.downloadSeries,
                                    lineColor = AppColors.Primary,
                                    height = 120.dp,
                                    showYAxis = true,
                                    compact = true,
                                    showSummary = true
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    if (state.uploadSeries.isNotEmpty()) {
                        GlassCard(modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LineChart(
                                    title = "Отдача",
                                    values = state.uploadSeries,
                                    lineColor = AppColors.Secondary,
                                    height = 120.dp,
                                    showYAxis = true,
                                    compact = true,
                                    showSummary = true
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // Дополнительные данные (то, что не помещаем в основной карточке под цифрами).
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Детали измерения", style = MaterialTheme.typography.titleSmall)

                        @Composable
                        fun infoLine(label: String, value: String?) {
                            if (value.isNullOrBlank()) return
                            Text(
                                text = "$label: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        @Composable
                        fun statsBlock(label: String, values: List<Double>) {
                            if (values.isEmpty()) return
                            val minV = values.minOrNull() ?: return
                            val maxV = values.maxOrNull() ?: return
                            val avgV = values.average()
                            val lastV = values.lastOrNull() ?: return
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            infoLine("Сейчас", "${String.format("%.0f", lastV)} Мбит/с")
                            infoLine("Среднее", "${String.format("%.0f", avgV)} Мбит/с")
                            infoLine("Мин", "${String.format("%.0f", minV)} Мбит/с")
                            infoLine("Макс", "${String.format("%.0f", maxV)} Мбит/с")
                            infoLine("Точек", values.size.toString())
                            Spacer(Modifier.height(4.dp))
                        }

                        // 1) Ряды (чтобы было понятно, что именно рисуют графики)
                        statsBlock("Скачивание", state.downloadSeries)
                        statsBlock("Отдача", state.uploadSeries)

                        // 2) Трафик и длительность
                        fun bytesToMb(b: Long?): String? {
                            if (b == null || b <= 0) return null
                            val mb = b.toDouble() / (1024.0 * 1024.0)
                            return String.format("%.1f МБ", mb)
                        }
                        fun msToSec(ms: Long?): String? {
                            if (ms == null || ms <= 0) return null
                            return String.format("%.1f с", ms.toDouble() / 1000.0)
                        }

                        infoLine("Скачано", bytesToMb(state.bytesDown))
                        infoLine("Отправлено", bytesToMb(state.bytesUp))
                        infoLine("Длительность", msToSec(state.durationMs))

                        // 3) Параметры теста (внутренние настройки)
                        infoLine("Фаза", state.phase.takeIf { it.isNotBlank() })
                        infoLine("Endpoint", state.endpointTitle.takeIf { it.isNotBlank() })
                        infoLine("Параллельных потоков", Config.speedParallelism.toString())
                        infoLine("Длительность на направление", "${Config.speedDurationMsPerDirection} мс")

                        // 4) Доп. сведения по сети (без дублирования того, что уже показано выше)
                        Spacer(Modifier.height(6.dp))
                        Text("Сеть", style = MaterialTheme.typography.bodyMedium)
                        infoLine("Транспорты", state.transportsDetail)
                        infoLine("Интерфейс", state.iface)
                        infoLine("MTU", state.mtu?.toString())
                        infoLine("Шлюз", state.defaultGateway)
                        infoLine("Private DNS", state.privateDns)
                        infoLine("Validated", state.isValidated?.let { if (it) "да" else "нет" })
                        infoLine("Captive portal", state.isCaptivePortal?.let { if (it) "да" else "нет" })
                        infoLine("Metered", state.isMetered?.let { if (it) "да" else "нет" })
                        infoLine("Оценка канала (down)", state.estDownKbps?.let { "$it кбит/с" })
                        infoLine("Оценка канала (up)", state.estUpKbps?.let { "$it кбит/с" })
                        infoLine(
                            "Локальные IP",
                            state.localIps.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        )
                        infoLine(
                            "DNS",
                            state.dnsServers.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        )

                        // 5) Разница пингов (информативно как «цена маршрута до хоста»)
                        val hostPing = state.latencyMs
                        val ruPing = state.ruLatencyMs
                        if (hostPing != null && ruPing != null && hostPing > 0 && ruPing > 0) {
                            val delta = hostPing - ruPing
                            infoLine("Разница пингов (хост − РФ)", "${delta} мс")
                        }
                    }
                }
            }
        }
    }


    if (showServerPicker) {
        val options = vm.getSpeedServerOptions()
        val choiceId = state.serverChoiceId.trim().ifBlank { "auto" }

        AlertDialog(
            onDismissRequest = { showServerPicker = false },
            confirmButton = {},
            dismissButton = {},
            title = { Text("Сервер теста") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {

                    fun select(id: String) {
                        vm.setSpeedServerChoice(id)
                        showServerPicker = false
                    }

                    // Авто-режим
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isRunning) { select("auto") }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Авто (лучший)",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Перед тестом выполняется быстрый подбор по доступности и скорости.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(text = if (choiceId == "auto") "✓" else "", style = MaterialTheme.typography.titleSmall)
                    }

                    // Конкретные endpoint'ы
                    options.forEach { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isRunning) { select(ep.id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ep.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = ep.downUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(text = if (choiceId == ep.id) "✓" else "", style = MaterialTheme.typography.titleSmall)
                        }
                    }

                    if (state.isRunning) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Нельзя менять сервер во время выполнения теста.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
                "Тест измеряет скорость именно до выбранного сервера, а не «скорость интернета вообще». " +
                    "На разных серверах маршрут может отличаться: стыковки сетей (пиринг), расстояние по сети, " +
                    "загрузка каналов, задержки и потери пакетов.\n\n" +
                    "CDN-сети часто оказываются ближе и дают более высокие значения. " +
                    "У отдельных тестовых серверов могут быть ограничения по полосе или защита от злоупотреблений, " +
                    "поэтому цифры могут заметно различаться даже в одной и той же Wi‑Fi или мобильной сети."
            )
        }
    )
}

if (askMobile) {
        AlertDialog(
            onDismissRequest = { askMobile = false },
            // Делаем кнопки ровно в одну линию и одинаковой ширины, чтобы не было «чипов разной длины».
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassSecondaryButton(
                        text = "Отмена",
                        onClick = { askMobile = false },
                        modifier = Modifier.weight(1f),
                        minHeight = 44.dp
                    )
                    GlassPrimaryButton(
                        text = "Продолжить",
                        onClick = {
                            askMobile = false
                            vm.startSpeedTest(forceMobileConfirm = true) {}
                        },
                        modifier = Modifier.weight(1f),
                        minHeight = 44.dp
                    )
                }
            },
            dismissButton = {},
            title = { Text("Мобильная сеть") },
            text = { Text("Тест может израсходовать примерно до $estMb МБ. Продолжить?") }
        )
    }
}

@Composable
private fun RowScope.MetricBig(
    title: String,
    value: String,
    unit: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                color = valueColor,
                maxLines = 1
            )
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun scoreColor(score: Int?): Color {
    if (score == null) return Color.Unspecified
    // 5 ... лучший, 1 ... худший
    val red = AppColors.Error
    val orange = AppColors.Warning
    val green = AppColors.Success
    val s = score.coerceIn(1, 5)
    val t = (s - 1) / 4f
    return if (t < 0.5f) lerp(red, orange, t / 0.5f) else lerp(orange, green, (t - 0.5f) / 0.5f)
}
