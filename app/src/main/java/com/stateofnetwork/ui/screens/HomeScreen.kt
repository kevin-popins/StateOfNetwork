package com.stateofnetwork.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stateofnetwork.ui.vm.AppViewModel
import com.stateofnetwork.ui.components.GlassCard
import com.stateofnetwork.ui.components.GlassChip
import com.stateofnetwork.ui.components.GlassPrimaryButton
import com.stateofnetwork.ui.components.GlassSecondaryButton
import com.stateofnetwork.ui.components.GlassTopBar
import com.stateofnetwork.ui.components.GlassChipButton
import com.stateofnetwork.data.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun HomeScreen(
    vm: AppViewModel,
    onSpeedStart: () -> Unit,
    onDomains: () -> Unit,
    onHistory: () -> Unit
) {
    val lastSpeed by vm.lastSpeed.collectAsState(initial = null)
    val lastRun by vm.lastDomainRun.collectAsState(initial = null)

    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(bottom = 18.dp)
    ) {
        GlassTopBar(
            title = "Состояние сети",
            showMark = true,
            trailing = {
                GlassChipButton(text = "История", onClick = onHistory)
            }
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSpeedStart() }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Последний тест скорости", style = MaterialTheme.typography.titleMedium)
                    if (lastSpeed == null) {
                        Text("Нет данных", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                            MetricMini(title = "Скачивание", value = fmt(lastSpeed!!.downloadMbps), unit = "Мбит/с")
                            MetricMini(title = "Отдача", value = fmt(lastSpeed!!.uploadMbps), unit = "Мбит/с")
                        }
                        Spacer(Modifier.height(2.dp))
	                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
	                            val ruPing = lastSpeed!!.ruLatencyMs ?: lastSpeed!!.latencyMs
	                            GlassChip(text = "Пинг РФ: ${ruPing} мс")
	                            GlassChip(text = if (lastSpeed!!.networkType == "WIFI") "Wi‑Fi" else "Мобильная")
	                        }
                        Text("Время: ${df.format(Date(lastSpeed!!.timestamp))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDomains() }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Последняя проверка сервисов", style = MaterialTheme.typography.titleMedium)
                    if (lastRun == null) {
                        Text("Нет данных", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val st = when (lastRun!!.summaryStatus) {
                            "available" -> "доступно"
                            "partial" -> "частично"
                            "unavailable" -> "недоступно"
                            "running" -> "выполняется"
                            "cancelled" -> "прервано"
                            else -> "-"
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassChip(text = "Статус: $st")
                            GlassChip(text = if (lastRun!!.networkType == "WIFI") "Wi‑Fi" else "Мобильная")
                        }
	                        val isWhitelist = lastRun!!.selectedServiceSets
	                            .split(",")
	                            .map { it.trim() }
	                            .any { it.equals("whitelist", ignoreCase = true) }
	                        if (isWhitelist) {
	                            val indicator = if (st == "доступно") {
	                                "белые списки не обнаружены"
	                            } else {
	                                "возможны белые списки"
	                            }
	                            Text("Индикатор: $indicator", style = MaterialTheme.typography.bodySmall)
	                        }
	                        Text("Наборы: ${mapSetIdsToTitles(lastRun!!.selectedServiceSets)}", style = MaterialTheme.typography.bodySmall)
                        Text("Время: ${df.format(Date(lastRun!!.timestamp))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            GlassPrimaryButton(
                text = "Запустить тест скорости",
                onClick = onSpeedStart,
                modifier = Modifier.fillMaxWidth()
            )

            GlassSecondaryButton(
                text = "Проверка сервисов",
                onClick = onDomains,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun mapSetIdsToTitles(raw: String): String {
    val ids = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
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

@Composable
private fun RowScope.MetricMini(title: String, value: String, unit: String) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            // На главном экране цифры должны читаться «с первого взгляда».
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChipLike(text: String) {
    // legacy: сохранено для совместимости, но в liquid glass используется GlassChip.
    GlassChip(text)
}