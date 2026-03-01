package com.stateofnetwork.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "speed_results", indices = [Index("timestamp")])
data class SpeedTestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val timestamp: Long,
    val networkType: String,
    val endpointId: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Long,
    val jitterMs: Long?,
	    // Диагностический пинг до ориентира в РФ (нужен, чтобы отличать «локально в РФ всё ок»
	    // от «маршрут уже вылетел за границу/уехал в VPN»)
	    val ruLatencyMs: Long?,
	    val ruLatencyTarget: String?,
    // Доп. сведения для истории (чтобы результаты можно было корректно интерпретировать)
    val transportLabel: String? = null,     // например: "VPN + Wi-Fi"
    val transportsDetail: String? = null,   // расширенное описание транспортов
    val radioSummary: String? = null,       // например: "5 ГГц · RSSI -56 dBm · Link 390 Мбит/с"
    val operatorName: String? = null,       // оператор/Carrier
    val providerName: String? = null,       // имя провайдера по публичному IP (если есть)
    val providerAsn: String? = null,        // ASN провайдера (если есть)
    val providerGeo: String? = null,        // страна/город по публичному IP
    val publicIp: String? = null,           // публичный IP
    val endpointHost: String? = null,       // фактический host теста
    val endpointIp: String? = null,         // IP host теста
    val iface: String? = null,
    val mtu: Int? = null,
    val defaultGateway: String? = null,
    val privateDns: String? = null,
    val isValidated: Boolean? = null,
    val isCaptivePortal: Boolean? = null,
    val isMetered: Boolean? = null,
    val estDownKbps: Int? = null,
    val estUpKbps: Int? = null,
    val localIpsCsv: String? = null,
    val dnsServersCsv: String? = null,
    val bytesDown: Long,
    val bytesUp: Long,
    val durationMs: Long,
    val status: String,
    val error: String?
)

@Entity(tableName = "domain_runs", indices = [Index("timestamp")])
data class DomainCheckRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val networkType: String,
    val selectedServiceSets: String,
    val summaryStatus: String,
    val notes: String?
)

@Entity(tableName = "domain_items", indices = [Index("runId"), Index("domain")])
data class DomainCheckItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val domain: String,
    val port: Int,
    val dnsOk: Boolean,
    val dnsTimeMs: Long?,
    val tcpOk: Boolean,
    val tcpTimeMs: Long?,
    val httpsOk: Boolean,
    val httpsTimeMs: Long?,
    val httpCode: Int?,
    val errorType: String?,
    val resolvedIp: String?
)
