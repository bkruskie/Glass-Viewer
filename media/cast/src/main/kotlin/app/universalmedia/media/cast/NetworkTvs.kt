package app.universalmedia.media.cast

import app.universalmedia.media.detection.UrlUtils

/** A TV (or TV stick/box) found on the Wi-Fi, with what it can do and what to tell the user. */
data class NetworkTv(
    val id: String,
    val name: String,
    val brand: String?,
    val model: String?,
    val host: String,
    val kind: Kind,
) {
    enum class Kind {
        /** Plays video from the app through DLNA; also listed under "Play on a TV". */
        DLNA_READY,
        /** A Roku: the app can try to play video on it. */
        ROKU,
        /** A known TV brand that doesn't accept video from apps as it is set up now. */
        TV_OTHER,
        /** Announces Apple AirPlay only. */
        AIRPLAY,
    }

    /** One line of advice for the Connect screen. */
    val advice: String
        get() = when (kind) {
            Kind.DLNA_READY -> "Ready: shown above under Play on a TV."
            Kind.ROKU -> "Tap to play on this Roku (experimental). For the whole screen, use Smart View."
            Kind.AIRPLAY -> "Accepts AirPlay only. Use Smart View for screen mirroring."
            Kind.TV_OTHER -> when (brand?.lowercase()) {
                "lg" -> "Turn on Settings › General › Devices › TV Management › Share (or Media sharing / DLNA) on the TV, or use Smart View."
                "samsung" -> "Turn on Settings › Connection › Device Connection Manager › Access Notification / media sharing on the TV, or use Smart View."
                "sony" -> "Use Chromecast built-in (shown above if on), or Smart View."
                else -> "This TV isn't accepting video from apps. Turn on media sharing (DLNA) in its settings, or use Smart View."
            }
        }
}

/** Turns raw network answers into a list of TVs. Pure logic, unit tested. */
object TvClassifier {
    private val tvBrands = listOf(
        "samsung", "lg", "sony", "panasonic", "philips", "tcl", "hisense", "vizio", "sharp", "toshiba",
        "roku", "amazon", "xiaomi", "insignia", "skyworth", "funai", "jvc", "grundig", "loewe", "nvidia", "google",
    )

    fun brandOf(vararg hints: String?): String? {
        val text = hints.filterNotNull().joinToString(" ").lowercase()
        val brand = tvBrands.firstOrNull { Regex("\\b$it").containsMatchIn(text) } ?: return null
        return when (brand) {
            "lg" -> "LG"
            "tcl" -> "TCL"
            "jvc" -> "JVC"
            else -> brand.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * @param answers SSDP answers grouped by device (same description URL).
     * @param identities parsed descriptions by description URL (may be missing).
     */
    fun classify(
        answers: List<DlnaProtocol.SsdpAnswer>,
        identities: Map<String, DlnaProtocol.DeviceIdentity>,
    ): List<NetworkTv> {
        val byHost = answers.groupBy { UrlUtils.hostOf(it.location) ?: it.location }
        return byHost.mapNotNull { (host, group) ->
            val sts = group.map { it.st.lowercase() }
            val server = group.firstOrNull()?.server.orEmpty()
            val identity = group.firstNotNullOfOrNull { identities[it.location] }
            val types = identity?.deviceTypes.orEmpty().map { it.lowercase() }
            val isRoku = sts.any { it.startsWith("roku:ecp") } || identity?.manufacturer?.contains("Roku", true) == true
            val isRenderer = identity?.hasAvTransport == true || types.any { "mediarenderer" in it }
            val tvHint = isRoku || isRenderer ||
                sts.any { "dial" in it || "remotecontrolreceiver" in it || "webos" in it || "tv" in it } ||
                types.any { "tv" in it || "dial" in it || "remotecontrolreceiver" in it }
            val brand = brandOf(identity?.manufacturer, identity?.modelName, identity?.friendlyName, server)
            if (!tvHint && brand == null) return@mapNotNull null // routers, printers, NAS…
            if (!tvHint && types.any { "internetgatewaydevice" in it || "printer" in it }) return@mapNotNull null
            NetworkTv(
                id = "tv:$host",
                name = identity?.friendlyName?.ifBlank { null } ?: listOfNotNull(brand, "TV").joinToString(" "),
                brand = brand,
                model = identity?.modelName,
                host = host,
                kind = when {
                    isRoku -> NetworkTv.Kind.ROKU
                    isRenderer -> NetworkTv.Kind.DLNA_READY
                    else -> NetworkTv.Kind.TV_OTHER
                },
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    }
}

/** Roku External Control answers. Pure logic, unit tested. */
object RokuProtocol {
    fun parsePlayer(xml: String): RemoteStatus? {
        val state = Regex("<player[^>]*state=\"([a-z]+)\"").find(xml)?.groupValues?.get(1) ?: return null
        fun ms(tag: String) = Regex("<$tag>\\s*(\\d+)\\s*ms").find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return RemoteStatus(
            positionMs = ms("position"),
            durationMs = ms("duration"),
            playing = state == "play" || state == "buffer" || state == "startup",
            ended = state == "close" || state == "finished" || state == "stop",
        )
    }
}
