package com.stateofnetwork.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.RouteInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LocalRadioInfo(
    val networkLabel: String,
    val transportLabel: String,
    val radioSummary: String?,
    val operatorName: String?
)

data class PublicProviderInfo(
    val ip: String,
    val isp: String?,
    val asn: String?,
    val country: String?,
    val city: String?
)

// Максимум полезной информации, которую можно получить без "опасных" разрешений.
// Часть полей может быть null из-за ограничений прошивки или версии Android.
data class NetworkDetails(
    val interfaceName: String? = null,
    val localAddresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val defaultGateway: String? = null,
    val mtu: Int? = null,
    val privateDns: String? = null,
    val isValidated: Boolean? = null,
    val isCaptivePortal: Boolean? = null,
    val isMetered: Boolean? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val transports: String? = null
)

class NetworkInfoProvider(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Упрощённый тип сети для логики приложения.
     */
    fun getNetworkType(): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(network) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            else -> "OTHER"
        }
    }

    /**
     * Локальная справка о транспорте и (частично) о радиопараметрах. Выполняется прямо на устройстве.
     * Важно: часть значений может быть недоступна на некоторых прошивках/версиях Android.
     */
    fun getLocalRadioInfo(): LocalRadioInfo {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val hasEth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        val transportLabel = buildString {
            if (hasVpn) append("VPN")
            if (hasWifi) {
                if (isNotEmpty()) append(" + ")
                append("Wi‑Fi")
            }
            if (hasCell) {
                if (isNotEmpty()) append(" + ")
                append("Мобильная сеть")
            }
            if (hasEth) {
                if (isNotEmpty()) append(" + ")
                append("Ethernet")
            }
            if (isEmpty()) append("Другое")
        }

        val networkLabel = when {
            hasWifi -> "Wi‑Fi"
            hasCell -> "Мобильная сеть"
            hasEth -> "Ethernet"
            hasVpn -> "VPN"
            else -> "Нет сети"
        }

        val operator = try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val a = tm.networkOperatorName?.trim().orEmpty()
            val b = tm.simOperatorName?.trim().orEmpty()
            (if (a.isNotBlank()) a else b).ifBlank { null }
        } catch (_: Exception) {
            null
        }

        val radioSummary = when {
            hasWifi -> buildWifiSummary()
            hasCell -> buildCellularSummary()
            else -> null
        }

        return LocalRadioInfo(
            networkLabel = networkLabel,
            transportLabel = transportLabel,
            radioSummary = radioSummary,
            operatorName = operator
        )
    }

    /**
     * Подробности о текущей активной сети: LinkProperties + NetworkCapabilities.
     * Не требует Location/Phone permissions.
     */
    fun getNetworkDetails(): NetworkDetails {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return NetworkDetails(transports = "Нет сети")

            val caps = cm.getNetworkCapabilities(network)
            val lp: LinkProperties? = cm.getLinkProperties(network)

            val ifName = lp?.interfaceName
            val localIps = lp?.linkAddresses
                ?.mapNotNull { it.address?.hostAddress }
                ?.distinct()
                .orEmpty()

            val dns = lp?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.distinct()
                .orEmpty()

            val gw = lp?.routes
                ?.firstOrNull { r: RouteInfo -> r.isDefaultRoute && r.hasGateway() }
                ?.gateway
                ?.hostAddress

            val mtu = lp?.mtu?.takeIf { it > 0 }

            val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp != null) {
                if (lp.isPrivateDnsActive) {
                    lp.privateDnsServerName?.takeIf { it.isNotBlank() } ?: "активен"
                } else {
                    "выкл"
                }
            } else null

            val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isCaptive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not()

            val dKbps = caps?.linkDownstreamBandwidthKbps
            val uKbps = caps?.linkUpstreamBandwidthKbps

            val transports = buildString {
                if (caps == null) {
                    append("Неизвестно")
                } else {
                    fun add(x: String) {
                        if (isNotEmpty()) append(" + ")
                        append(x)
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Мобильная")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
                    if (isEmpty()) append("Другое")
                }
            }

            NetworkDetails(
                interfaceName = ifName,
                localAddresses = localIps,
                dnsServers = dns,
                defaultGateway = gw,
                mtu = mtu,
                privateDns = privateDns,
                isValidated = isValidated,
                isCaptivePortal = isCaptive,
                isMetered = isMetered,
                downstreamKbps = dKbps,
                upstreamKbps = uKbps,
                transports = transports
            )
        } catch (_: Exception) {
            NetworkDetails(transports = "Неизвестно")
        }
    }

    private fun buildWifiSummary(): String? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null

            val rssi = info.rssi
            val link = info.linkSpeed
            val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else 0
            val band = when {
                freq in 2400..2500 -> "2.4 ГГц"
                freq in 4900..5900 -> "5 ГГц"
                freq in 5925..7125 -> "6 ГГц"
                else -> null
            }

            buildString {
                if (!band.isNullOrBlank()) {
                    append(band)
                    append(" · ")
                }
                if (rssi != 0) {
                    append("RSSI ")
                    append(rssi)
                    append(" dBm")
                }
                if (link > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("Link ")
                    append(link)
                    append(" Мбит/с")
                }
            }.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCellularSummary(): String? {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType else tm.networkType
            val tech = networkTypeToLabel(type)
            if (tech.isBlank()) null else tech
        } catch (_: Exception) {
            null
        }
    }

    private fun networkTypeToLabel(type: Int): String {
        // Не пытаемся "угадывать" лишнее: показываем технологию, чтобы пользователь понимал контекст.
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "4G (LTE)"

            // 5G: доступно с API 29
            20 /* TelephonyManager.NETWORK_TYPE_NR */ -> "5G (NR)"

            else -> ""
        }
    }

    /**
     * Внешняя справка по публичному IP. Это не тест скорости; только метаданные.
     */
    suspend fun fetchPublicProviderInfo(): PublicProviderInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.isBlank()) return@withContext null

                val json = JSONObject(body)
                val ok = json.optBoolean("success", true)
                if (!ok) return@withContext null

                val ip = json.optString("ip", "").trim()
                if (ip.isBlank()) return@withContext null
                val isp = json.optString("isp", null)
                val asn = json.optString("asn", null)
                val country = json.optString("country", null)
                val city = json.optString("city", null)

                PublicProviderInfo(
                    ip = ip,
                    isp = isp,
                    asn = asn,
                    country = country,
                    city = city
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
