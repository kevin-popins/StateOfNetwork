package com.stateofnetwork.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun LineChart(
    title: String,
    values: List<Double>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    showYAxis: Boolean = true,
    // Для компактных графиков: уменьшаем подписи и делаем шкалу менее шумной.
    compact: Boolean = false,
    // Показать численные подсказки (макс/последнее) рядом с заголовком.
    showSummary: Boolean = false
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier.fillMaxWidth()) {
        if (!showSummary || values.isEmpty()) {
            Text(title, style = MaterialTheme.typography.titleSmall)
        } else {
            // На узких карточках размещение «в одну строку» начинает наезжать.
            // Поэтому делаем подсказки второй строкой.
            val maxV = values.maxOrNull() ?: 0.0
            val lastV = values.lastOrNull() ?: 0.0
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "макс ${String.format("%.0f", maxV)} · сейчас ${String.format("%.0f", lastV)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (values.size < 2) return@Canvas

            val maxV = max(1.0, values.maxOrNull() ?: 1.0)
            val minV = min(0.0, values.minOrNull() ?: 0.0)
            val range = max(1e-6, maxV - minV)

            val w = size.width
            val h = size.height
            val leftPad = if (showYAxis) {
                if (compact) 54f else 72f
            } else 12f // место под шкалу
            // Небольшие внутренние отступы, чтобы верхняя подпись шкалы Y не «липла» к заголовку.
            val topPad = 16f
            val bottomPad = 18f
            val plotTop = topPad
            val plotBottom = h - bottomPad
            val plotH = max(1f, plotBottom - plotTop)
            val stepX = max(1f, (w - leftPad) / (values.size - 1).toFloat())

            // оси
            drawLine(color = outlineColor, start = Offset(leftPad, plotBottom), end = Offset(w, plotBottom), strokeWidth = 2f)
            if (showYAxis) {
                drawLine(color = outlineColor, start = Offset(leftPad, plotTop), end = Offset(leftPad, plotBottom), strokeWidth = 2f)
            }

            // шкала Y (3 деления: max, mid, min)
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = if (compact) 22f else 28f
                color = textColor.toArgb()
            }
            val maxText = String.format("%.0f", maxV)
            val midText = String.format("%.0f", (maxV + minV) / 2.0)
            val minText = String.format("%.0f", minV)

            // линии делений
            fun yFor(v: Double): Float {
                val norm = (v - minV) / range
                return (plotBottom - (norm.toFloat() * plotH))
            }

            val yMax = yFor(maxV)
            val yMid = yFor((maxV + minV) / 2.0)
            val yMin = yFor(minV)

            // горизонтальные линии сетки
            drawLine(color = outlineColor.copy(alpha = 0.35f), start = Offset(leftPad, yMax), end = Offset(w, yMax), strokeWidth = 1f)
            if (!compact) {
                drawLine(color = outlineColor.copy(alpha = 0.35f), start = Offset(leftPad, yMid), end = Offset(w, yMid), strokeWidth = 1f)
            }
            drawLine(color = outlineColor.copy(alpha = 0.35f), start = Offset(leftPad, yMin), end = Offset(w, yMin), strokeWidth = 1f)

            if (showYAxis) {
                drawContext.canvas.nativeCanvas.apply {
                    val maxYText = (yMax + 10f).coerceAtLeast(plotTop + 14f)
                    val midYText = (yMid + 10f).coerceIn(plotTop + 14f, plotBottom - 6f)
                    val minYText = (yMin - 4f).coerceAtMost(plotBottom - 6f)
                    drawText(maxText, 6f, maxYText, paint)
                    if (!compact) {
                        drawText(midText, 6f, midYText, paint)
                    }
                    drawText(minText, 6f, minYText, paint)
                }
            }

            // график
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = leftPad + stepX * i.toFloat()
                val y = yFor(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 5f)
            )
        }
    }
}
