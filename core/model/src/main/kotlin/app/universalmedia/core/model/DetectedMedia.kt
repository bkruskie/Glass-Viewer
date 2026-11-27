package app.universalmedia.core.model

/** Where a piece of media was discovered. */
enum class DetectionOrigin {
    IN_APP_BROWSER,
    SHARE_INTENT,
    EXTERNAL_LINK,
    CLIPBOARD,
    MEDIA_SESSION,
    MANUAL,
}

/** One selectable rendition (quality / bitrate / track) of a detected source. */
data class MediaVariant(
    val url: String,
    val format: MediaFormat,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val codecs: String? = null,
    val frameRate: Double? = null,
    val estimatedBytes: Long? = null,
    val audioOnly: Boolean = format.kind == MediaKind.AUDIO,
    /** The stream manifest (HLS master) that lists this quality; needed to find separate audio. */
    val manifestUrl: String? = null,
) {
    /** Human label such as "1080p · 4.2 Mbps". */
    val label: String
        get() = buildList {
            height?.let { add("${it}p") }
            if (audioOnly) add("Audio")
            bitrate?.let { add(formatBitrate(it)) }
        }.ifEmpty { listOf(format.displayName) }.joinToString(" · ")
}

/** A media source the detection engine found and classified. */
data class DetectedMedia(
    val id: String,
    val mediaUrl: String,
    val format: MediaFormat,
    val origin: DetectionOrigin,
    val detectedAtMillis: Long,
    val pageUrl: String? = null,
    val title: String? = null,
    val sourceHost: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val variants: List<MediaVariant> = emptyList(),
    val protection: Protection = Protection.None,
    val availability: Availability = Availability.SUPPORTED,
    val note: String? = null,
) {
    val bestVariant: MediaVariant?
        get() = variants.maxWithOrNull(compareBy<MediaVariant>({ it.height ?: 0 }, { it.bitrate ?: 0 }))
}

fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
    bps >= 1_000 -> "${bps / 1_000} kbps"
    else -> "$bps bps"
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return "%.1f %s".format(value, units[i])
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
