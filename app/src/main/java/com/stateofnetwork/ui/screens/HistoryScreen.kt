package com.stateofnetwork.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stateofnetwork.data.SpeedAgg
import com.stateofnetwork.data.HistoryItem
import com.stateofnetwork.report.ShareHelper
import com.stateofnetwork.ui.components.GlassCard
import com.stateofnetwork.ui.components.GlassPrimaryButton
import com.stateofnetwork.ui.components.GlassTopBar
import com.stateofnetwork.ui.vm.AppViewModel

@Composable
fun HistoryScreen(
    vm: AppViewModel,
    ctx: Context,
    onBack: () -> Unit,
    onOpenSpeedDetails: (timestamp: Long) -> Unit
) {
    val items by vm.historyItems.collectAsState(initial = emptyList<HistoryItem>())
    val agg7d by vm.speedAgg7dFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            GlassTopBar(
                title = "История",
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
        LazyColumn(
            modifier = Modifier.padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPrimaryButton(
                        text = "Отчёт за 7 дней",
                        onClick = {
                            val now = System.currentTimeMillis()
                            val from = now - 7L * 24L * 60L * 60L * 1000L
                            vm.buildAndShareReport(from, now) { title, mime, bytes ->
                                ShareHelper.shareBytes(ctx, title, mime, bytes)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SpeedSummaryCard(agg7d = agg7d)
            }

            item { Spacer(Modifier.height(6.dp)) }

            items(items) { item ->
                HistoryCard(
                    type = item.type,
                    line = item.displayLine,
                    onClick = if (item.type == "speed") ({ onOpenSpeedDetails(item.sortTs) }) else null
                )
            }
        }
    }
}

@Composable
private fun SpeedSummaryCard(agg7d: List<SpeedAgg>) {
    if (agg7d.isEmpty()) return

    fun pick(net: String): SpeedAgg? = agg7d.firstOrNull { it.networkType == net }

    val wifi = pick("WIFI")
    val mobile = agg7d.firstOrNull { it.networkType != "WIFI" }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Сводка скорости за 7 дней", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MiniAggCard(
                    title = "Средняя за 7 дней Wi-Fi",
                    agg = wifi,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                MiniAggCard(
                    title = "Средняя за 7 дней Моб. Сеть",
                    agg = mobile,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun MiniAggCard(title: String, agg: SpeedAgg?, modifier: Modifier = Modifier) {
    fun fmtMbps(v: Double?): String = if (v == null) "—" else String.format("%.0f", v)
    fun fmtMs(v: Double?): String = if (v == null) "—" else String.format("%.0f", v)

    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)

            val down = fmtMbps(agg?.avgDown)
            val up = fmtMbps(agg?.avgUp)
            Text("$down↓ / $up↑ Мбит/с", style = MaterialTheme.typography.titleMedium)

            val ruPing = fmtMs(agg?.avgRuPing)
            Text("Пинг РФ: $ruPing мс", style = MaterialTheme.typography.bodySmall)

            val n = agg?.samples ?: 0
            Text("Замеров: $n", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryCard(type: String, line: String, onClick: (() -> Unit)?) {
    val title = when (type) {
        "speed" -> "Скорость"
        "domains" -> "Проверка сервисов"
        else -> "Событие"
    }

    val cleaned = line
        .replace("[СКОРОСТЬ] ", "")
        .replace("[СЕРВИСЫ] ", "")

    val cardModifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    GlassCard(modifier = cardModifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(cleaned, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
