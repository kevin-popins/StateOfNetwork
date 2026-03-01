package com.stateofnetwork.data

data class SpeedEndpoint(
    val id: String,
    val title: String,
    val downUrl: String,
    val upUrl: String,
    val mode: SpeedEndpointMode = SpeedEndpointMode.CLOUDFLARE_BYTES
)

enum class SpeedEndpointMode {
    /** Cloudflare совместимый endpoint: /__down?bytes= и /__up */
    CLOUDFLARE_BYTES,

    /** Обычный download-URL (файл) + upload-URL (POST). */
    GENERIC_FILE_POST,

    /** Обычный download-URL (файл) + upload-URL (PUT). */
    GENERIC_FILE_PUT
}

data class ServiceSet(
    val id: String,
    val title: String,
    val domains: List<DomainTarget>
)

enum class DomainGroup {
    /** Домен должен работать в режиме "белых списков" (если действительно включены ограничения). */
    WHITELIST,

    /** Контрольный домен: обычно доступен из РФ и не находится в перечнях РКН/самоблокировок. */
    CONTROL,

    /** Прочие домены (например, добавленные пользователем). */
    OTHER
}

data class DomainTarget(
    val domain: String,
    val port: Int = 443,
    val group: DomainGroup = DomainGroup.OTHER
)

object Config {
    /*
     * Важно: приложение не использует собственные серверы.
     * Скорость измеряется публичными endpoint'ами.
     */
    val speedEndpoints: List<SpeedEndpoint> = listOf(
        SpeedEndpoint(
            id = "cloudflare",
            title = "Cloudflare Speed Test",
            downUrl = "https://speed.cloudflare.com/__down",
            upUrl = "https://speed.cloudflare.com/__up"
        ),
        // RU download от крупного хостинга. Upload используем тот же, что и для RastrNet (PUT),
        // чтобы не завязываться на зарубежные echo/diagnostic сервисы.
        SpeedEndpoint(
            id = "selectel",
            title = "Selectel (RU download)",
            downUrl = "https://speedtest.selectel.ru/100MB",
            upUrl = "https://speed.s-vfu.ru/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),
        // RU endpoint (скачивание + заливка). Полезен как fallback, если зарубежные CDN деградируют/блокируются.
        SpeedEndpoint(
            id = "rastrnet",
            title = "RastrNet (RU)",
            downUrl = "https://speedtest.rastrnet.ru/1GB.zip",
            upUrl = "https://speedtest.rastrnet.ru/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),

        // Дополнительные RU endpoint'ы формата speedtest mini (файл + upload.php).
        // Важно: используем HTTPS (443), чтобы не упираться в закрытые/фильтруемые HTTP-порты.
        SpeedEndpoint(
            id = "s_vfu",
            title = "S-VFU (RU)",
            downUrl = "https://speed.s-vfu.ru/speedtest/random4000x4000.jpg",
            upUrl = "https://speed.s-vfu.ru/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),
        SpeedEndpoint(
            id = "astsystems",
            title = "AST Systems (RU)",
            downUrl = "https://astsystems.ru/files/speedtest/random4000x4000.jpg",
            upUrl = "https://astsystems.ru/files/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),
        SpeedEndpoint(
            id = "3lan",
            title = "3LAN (RU)",
            downUrl = "https://speedtest1.3lan.ru/speedtest/random4000x4000.jpg",
            upUrl = "https://speedtest1.3lan.ru/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),

        // Зарубежные speedtest-mini сервера (download + upload.php) по HTTPS.
        // Примечание: доступность может зависеть от провайдера/маршрута. В режиме «Авто»
        // все варианты быстро отсеиваются probe-логикой.

        // Оставляем один BR-узел по вашей просьбе.
        SpeedEndpoint(
            id = "bmitelecom",
            title = "BMI Telecom (BR)",
            downUrl = "https://speedtest.bmitelecom.com.br/speedtest/random4000x4000.jpg",
            upUrl = "https://speedtest.bmitelecom.com.br/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),

        // Европа
        SpeedEndpoint(
            id = "epic_mt",
            title = "Epic (MT)",
            downUrl = "https://speed.epic.com.mt/speedtest/random4000x4000.jpg",
            upUrl = "https://speed.epic.com.mt/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        ),

        // Северная Америка
SpeedEndpoint(
            id = "start_ca_london",
            title = "Start.ca London (CA)",
            downUrl = "https://speedtest-london.start.ca/speedtest/random4000x4000.jpg",
            upUrl = "https://speedtest-london.start.ca/speedtest/upload.php",
            mode = SpeedEndpointMode.GENERIC_FILE_POST
        )
    )

    const val speedDurationMsPerDirection: Long = 12_000L
    const val speedParallelism: Int = 2

    const val dnsTimeoutMs: Long = 4_000L
    const val tcpTimeoutMs: Int = 6_000
    const val httpsTimeoutMs: Long = 9_000L
    const val domainParallelism: Int = 8

    fun estimatedSpeedTestTrafficMb(): Int {
        // Консервативная оценка для предупреждения пользователя (особенно в мобильной сети).
        return 80
    }

    /**
     * Упрощенный локальный список доменов, доступ к которым в РФ нередко ограничивается
     * (блокировка/замедление/частичная деградация функций).
     * Используется только для подсказки и не влияет на логику проверки.
     */
    val rknBlockedDomains: Set<String> = setOf(
        "archive.org",
        "archive.ec",
        "archive.is",
        "bodog.eu",
        "bovada.lv",
        "bwin.com",
        "dailymotion.com",
        "deviantart.com",
        "ej.ru",
        "flibusta.is",
        "heritagesports.eu",
        "ladbrokes.com",
        "line.me",
        "linkedin.com",
        "nicovideo.jp",
        "paddypower.com",
        "pornhubcasino.com",
        "pornhub.ru",
        "thebetwaygroup.com",
        "unibet-1.com",
        "williamhill.com",
        "xvideosru.com",
        "youtube.com",
        "zello.com",
        "discord.com",
        "whatsapp.com",

        // Часто упоминаемые ограничения/блокировки
        "speedtest.net",
        "ookla.com",
        "signal.org",
        "viber.com",
        "roblox.com",
        "facetime.apple.com",
        "snapchat.com",
        "patreon.com",
        "soundcloud.com",
        "facebook.com",
        "instagram.com",
        "twitter.com",
        "x.com",
        "telegram.org",
        "t.me",
        "godaddy.com",
        "aws.amazon.com",
        "ficbook.net"
    )

    fun isLikelyRknBlocked(domain: String): Boolean {
        val d = domain.trim().lowercase()
        if (d.isBlank()) return false
        return rknBlockedDomains.any { b -> d == b || d.endsWith(".$b") }
    }

    val serviceSets: List<ServiceSet> = listOf(
        ServiceSet(
            id = "whitelist",
            title = "Белые списки",
            domains = listOf(
                // "Белые" домены (сценарий: при региональных ограничениях они часто остаются доступны).
                DomainTarget("rutube.ru", group = DomainGroup.WHITELIST),
                DomainTarget("vk.com", group = DomainGroup.WHITELIST),
                DomainTarget("ok.ru", group = DomainGroup.WHITELIST),
                DomainTarget("avito.ru", group = DomainGroup.WHITELIST),
                DomainTarget("gosuslugi.ru", group = DomainGroup.WHITELIST),
                DomainTarget("pochta.ru", group = DomainGroup.WHITELIST),
                DomainTarget("2gis.ru", group = DomainGroup.WHITELIST),
                DomainTarget("ya.ru", group = DomainGroup.WHITELIST),
                DomainTarget("dzen.ru", group = DomainGroup.WHITELIST),
                DomainTarget("kinopoisk.ru", group = DomainGroup.WHITELIST),
                DomainTarget("mail.ru", group = DomainGroup.WHITELIST),
                DomainTarget("rustore.ru", group = DomainGroup.WHITELIST),
                DomainTarget("rzd.ru", group = DomainGroup.WHITELIST),
                DomainTarget("tutu.ru", group = DomainGroup.WHITELIST),

                // Контрольные домены: обычно доступны из РФ и не относятся к типичным блок-листам.
                DomainTarget("google.com", group = DomainGroup.CONTROL),
                DomainTarget("www.google.com", group = DomainGroup.CONTROL),
                DomainTarget("wikipedia.org", group = DomainGroup.CONTROL),
                DomainTarget("github.com", group = DomainGroup.CONTROL),
                DomainTarget("mozilla.org", group = DomainGroup.CONTROL),
                DomainTarget("cloudflare.com", group = DomainGroup.CONTROL),
                DomainTarget("speed.cloudflare.com", group = DomainGroup.CONTROL),
                DomainTarget("microsoft.com", group = DomainGroup.CONTROL),
                DomainTarget("apple.com", group = DomainGroup.CONTROL),
                DomainTarget("debian.org", group = DomainGroup.CONTROL),
                DomainTarget("ubuntu.com", group = DomainGroup.CONTROL),
                DomainTarget("npmjs.com", group = DomainGroup.CONTROL),
                DomainTarget("pypi.org", group = DomainGroup.CONTROL),
                DomainTarget("stackoverflow.com", group = DomainGroup.CONTROL),
                DomainTarget("example.com", group = DomainGroup.CONTROL),
                DomainTarget("iana.org", group = DomainGroup.CONTROL),
                DomainTarget("w3.org", group = DomainGroup.CONTROL),
                DomainTarget("python.org", group = DomainGroup.CONTROL),
                DomainTarget("letsencrypt.org", group = DomainGroup.CONTROL)
            )
        ),
        ServiceSet(
            id = "blocked",
            title = "Заблокированные ресурсы",
            domains = listOf(
                DomainTarget("discord.com"),
                DomainTarget("youtube.com"),
                DomainTarget("googlevideo.com"),
                DomainTarget("instagram.com"),
                DomainTarget("facebook.com"),
                DomainTarget("twitter.com"),
                DomainTarget("x.com"),
                DomainTarget("signal.org"),
                DomainTarget("viber.com"),
                DomainTarget("snapchat.com"),
                DomainTarget("patreon.com"),
                DomainTarget("soundcloud.com"),
                DomainTarget("roblox.com"),
                DomainTarget("speedtest.net"),
                DomainTarget("ookla.com"),

                // Самоблокировки/гео-ограничения (актуально для пользователей из РФ).
                DomainTarget("openai.com"),
                DomainTarget("chatgpt.com"),
                DomainTarget("platform.openai.com"),
                DomainTarget("api.openai.com")
            )
        )
    )
}
