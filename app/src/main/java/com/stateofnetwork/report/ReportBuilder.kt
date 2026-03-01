package com.stateofnetwork.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.stateofnetwork.data.Config
import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.data.model.DomainCheckRunEntity
import com.stateofnetwork.data.model.SpeedTestResultEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportBuilder {

    fun buildTxt(
        ctx: Context,
        from: Long,
        to: Long,
        speeds: List<SpeedTestResultEntity>,
        runs: List<DomainCheckRunEntity>,
        items: List<DomainCheckItemEntity>
    ): ByteArray {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.appendLine("ОТЧЕТ: СОСТОЯНИЕ СЕТИ")
        sb.appendLine("Период: ${df.format(Date(from))} .. ${df.format(Date(to))}")
        sb.appendLine()

        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        val pInfo = try {
            // getPackageInfo signature differs by SDK; this is OK for Android 10+ and older toolchains.
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (_: Exception) { null }

        sb.appendLine("Приложение: ${(pInfo?.versionName ?: "-")} (${pInfo?.longVersionCode ?: -1})")
        sb.appendLine()

        sb.appendLine("ТЕСТЫ СКОРОСТИ")
        if (speeds.isEmpty()) {
            sb.appendLine("Нет данных.")
            sb.appendLine()
        } else {
            val endpointTitles = Config.speedEndpoints.associateBy({ it.id }, { it.title })
            speeds.forEach { s ->
                val epTitle = endpointTitles[s.endpointId] ?: s.endpointId
                sb.append(df.format(Date(s.timestamp)))
                sb.append(" сеть=").append(if (s.networkType == "WIFI") "Wi-Fi" else "Мобильная")
                sb.append(" скач=").append(String.format(Locale.US, "%.2f", s.downloadMbps)).append(" Мбит/с")
                sb.append(" отдача=").append(String.format(Locale.US, "%.2f", s.uploadMbps)).append(" Мбит/с")
                sb.append(" задержка=").append(s.latencyMs).append(" мс")
                sb.append(" джиттер=").append(s.jitterMs ?: -1).append(" мс")
                sb.append(" endpoint=").append(epTitle)
                sb.append(" статус=").append(s.status)
                if (!s.error.isNullOrBlank()) sb.append(" ошибка=").append(s.error)
                sb.appendLine()
            }
            sb.appendLine()
        }

        sb.appendLine("ПРОВЕРКИ СЕРВИСОВ")
        if (runs.isEmpty()) {
            sb.appendLine("Нет данных.")
            sb.appendLine()
        } else {
            val itemsByRun = items.groupBy { it.runId }
            runs.forEachIndexed { idx, r ->
                val st = when (r.summaryStatus) {
                    "available" -> "доступно"
                    "partial" -> "частично"
                    "unavailable" -> "недоступно"
                    else -> r.summaryStatus
                }
                sb.appendLine("Запуск ${idx + 1} ${df.format(Date(r.timestamp))} сеть=${if (r.networkType == "WIFI") "Wi-Fi" else "Мобильная"} наборы=${r.selectedServiceSets} итог=$st")

                val list = itemsByRun[r.id].orEmpty().sortedBy { it.domain }
                list.forEach { it ->
                    sb.append("  ").append(it.domain).append(": ")
                    sb.append("dns=").append(if (it.dnsOk) "ok" else "fail").append("@").append(it.dnsTimeMs ?: -1).append("мс ")
                    sb.append("tcp=").append(if (it.tcpOk) "ok" else "fail").append("@").append(it.tcpTimeMs ?: -1).append("мс ")
                    sb.append("https=").append(if (it.httpsOk) "ok" else "fail").append("@").append(it.httpsTimeMs ?: -1).append("мс ")
                    sb.append("code=").append(it.httpCode ?: -1).append(" ")
                    sb.append("err=").append(it.errorType ?: "-").append(" ")
                    sb.append("ip=").append(it.resolvedIp ?: "-")
                    sb.appendLine()
                }
                sb.appendLine()
            }
        }

        sb.appendLine("Примечание: отчет диагностический. Значения зависят от нагрузки сети и выбранных ресурсов. Приложение не является средством обхода ограничений.")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
