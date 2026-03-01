package com.stateofnetwork.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stateofnetwork.data.Config
import com.stateofnetwork.data.model.SpeedTestResultEntity
import com.stateofnetwork.ui.components.GlassCard
import com.stateofnetwork.ui.components.GlassChip
import com.stateofnetwork.ui.components.GlassTopBar
import com.stateofnetwork.ui.vm.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpeedHistoryDetailScreen(
    vm: AppViewModel,
    timestamp: Long,
    onBack: () -> Unit
) {
    val endpoints = remember { Config.speedEndpoints }

    val result by produceState<SpeedTestResultEntity?>(initialValue = null, timestamp) {
        value = withContext(Dispatchers.IO) { vm.getSpeedResultByTimestamp(timestamp) }
    }

    Scaffold(
        topBar = {
            GlassTopBar(
                title = "История скорости",
                leading = {
                    Text(
                        "Назад",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clickable { onBack() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            )
        },
        containerColor = Color.Transparent
    ) { inner ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val r = result
            if (r == null) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Запись не найдена", style = MaterialTheme.typography.titleMedium)
                        Text("Возможно, запись была удалена или база данных была очищена.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@Column
            }

            val server = endpoints.firstOrNull { it.id == r.endpointId }
            val serverTitle = server?.title ?: r.endpointId

            val dt = remember(r.timestamp) {
                val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                fmt.format(Date(r.timestamp))
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Дата и время", style = MaterialTheme.typography.titleMedium)
                    Text(dt, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(text = "Тип сети: ${r.transportLabel?.takeIf { it.isNotBlank() } ?: networkLabel(r.networkType)}")
                        GlassChip(text = "Сервер: $serverTitle")
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Результат", style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricBig(
                            title = "Скачивание",
                            value = fmtMbps(r.downloadMbps),
                            unit = "Мбит/с"
                        )
                        MetricBig(
                            title = "Отдача",
                            value = fmtMbps(r.uploadMbps),
                            unit = "Мбит/с"
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassChip(text = "Пинг: ${r.latencyMs} мс")
                        GlassChip(text = "Джиттер: ${r.jitterMs} мс")
                    }

                    val ruTarget = r.ruLatencyTarget
                    if (!ruTarget.isNullOrBlank()) {
                        GlassChip(text = "Пинг РФ ($ruTarget): ${r.ruLatencyMs ?: 0} мс")
                    } else {
                        GlassChip(text = "Пинг РФ: ${r.ruLatencyMs ?: 0} мс")
                    }


                    Spacer(Modifier.height(6.dp))

                    // Дополнительные сведения (как в экране теста скорости)
                    val netLabel = r.transportLabel?.takeIf { it.isNotBlank() } ?: networkLabel(r.networkType)
                    Text("Тип сети: $netLabel", style = MaterialTheme.typography.bodyMedium)
                    if (!r.radioSummary.isNullOrBlank()) {
                        Text("Радио-параметры: ${r.radioSummary}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!r.operatorName.isNullOrBlank()) {
                        Text("Оператор: ${r.operatorName}", style = MaterialTheme.typography.bodyMedium)
                    }
                    val providerLine = r.providerGeo?.takeIf { it.isNotBlank() } ?: r.providerName
                    if (!providerLine.isNullOrBlank()) {
                        Text("Провайдер: $providerLine", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!r.publicIp.isNullOrBlank()) {
                        Text("Публичный IP: ${r.publicIp}", style = MaterialTheme.typography.bodyMedium)
                    }
                    val host = r.endpointHost?.takeIf { it.isNotBlank() } ?: server?.let { runCatching { Uri.parse(it.downUrl).host }.getOrNull() }
                    val hostIp = r.endpointIp
                    if (!host.isNullOrBlank()) {
                        val hostLine = if (!hostIp.isNullOrBlank()) "Хост: $host (IP: $hostIp)" else "Хост: $host"
                        Text(hostLine, style = MaterialTheme.typography.bodyMedium)
                        Text("Пинг до хоста: ${r.latencyMs} мс", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Детали", style = MaterialTheme.typography.titleMedium)
                    Text("Статус: ${statusLabel(r.status)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Длительность: ${fmtSeconds(r.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Трафик: ${fmtBytes(r.bytesDown)}↓ / ${fmtBytes(r.bytesUp)}↑", style = MaterialTheme.typography.bodyMedium)
                    if (!r.error.isNullOrBlank()) {
                        Text("Ошибка: ${r.error}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MetricBig(
    title: String,
    value: String,
    unit: String
) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtMbps(v: Double): String {
    if (!v.isFinite()) return "0"
    return String.format(Locale.getDefault(), "%.0f", v)
}

private fun fmtSeconds(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    return "${sec} c"
}

private fun fmtBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val mb = b.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f МБ", mb)
}

private fun statusLabel(status: String): String {
    return when (status) {
        "ok" -> "Завершено"
        "partial" -> "Частично"
        "aborted" -> "Прервано"
        "error" -> "Ошибка"
        else -> status
    }
}

private fun networkLabel(networkType: String): String {
    return when (networkType.uppercase(Locale.getDefault())) {
        "WIFI" -> "Wi-Fi"
        "CELLULAR" -> "Мобильная сеть"
        "VPN" -> "VPN"
        else -> networkType
    }
}
