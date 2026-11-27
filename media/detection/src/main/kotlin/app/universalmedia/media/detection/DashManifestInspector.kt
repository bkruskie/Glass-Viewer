package app.universalmedia.media.detection

import app.universalmedia.core.model.DrmSystem
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.core.model.Protection

/**
 * Lightweight MPEG-DASH manifest inspection: qualities, duration, live/VOD and
 * protection. Full segment-level parsing is left to Media3's DashMediaSource.
 */
object DashManifestInspector {

    data class Summary(
        val durationMs: Long?,
        val isLive: Boolean,
        val variants: List<MediaVariant>,
        val protection: Protection,
    )

    private val mpdTag = Regex("<MPD\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val representationTag = Regex("<Representation\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val protectionTag = Regex("<ContentProtection\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"")
    private val isoDuration = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?$")

    private const val GENERIC_CENC = "urn:mpeg:dash:mp4protection:2011"

    fun inspect(xml: String, manifestUrl: String): Summary? {
        val mpd = mpdTag.find(xml) ?: return null
        val mpdAttrs = attributes(mpd.groupValues[1])

        val variants = representationTag.findAll(xml).mapNotNull { match ->
            val a = attributes(match.groupValues[1])
            val mime = a["mimeType"].orEmpty()
            val height = a["height"]?.toIntOrNull()
            val audioOnly = mime.startsWith("audio") || (height == null && a["audioSamplingRate"] != null)
            MediaVariant(
                url = manifestUrl,
                format = MediaFormat.DASH,
                width = a["width"]?.toIntOrNull(),
                height = height,
                bitrate = a["bandwidth"]?.toLongOrNull(),
                codecs = a["codecs"],
                frameRate = a["frameRate"]?.let(::parseFrameRate),
                audioOnly = audioOnly,
            )
        }.toList().sortedWith(
            compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 },
        )

        val systems = mutableSetOf<DrmSystem>()
        var genericCenc = false
        protectionTag.findAll(xml).forEach { match ->
            val scheme = attributes(match.groupValues[1])["schemeIdUri"].orEmpty()
            when {
                scheme.equals(GENERIC_CENC, ignoreCase = true) -> genericCenc = true
                scheme.startsWith("urn:uuid:", ignoreCase = true) ->
                    systems += DrmSystems.fromUuid(scheme) ?: DrmSystem.UNKNOWN
            }
        }
        if (systems.isEmpty() && genericCenc) systems += DrmSystem.UNKNOWN
        val protection = if (systems.isEmpty()) Protection.None else Protection.Drm(systems)

        return Summary(
            durationMs = mpdAttrs["mediaPresentationDuration"]?.let(::parseIsoDurationMs),
            isLive = mpdAttrs["type"].equals("dynamic", ignoreCase = true),
            variants = variants,
            protection = protection,
        )
    }

    private fun attributes(raw: String): Map<String, String> =
        attribute.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    private fun parseFrameRate(value: String): Double? {
        val parts = value.split('/')
        return if (parts.size == 2) {
            val n = parts[0].toDoubleOrNull()
            val d = parts[1].toDoubleOrNull()
            if (n != null && d != null && d != 0.0) n / d else null
        } else value.toDoubleOrNull()
    }

    internal fun parseIsoDurationMs(value: String): Long? {
        val m = isoDuration.matchEntire(value.trim()) ?: return null
        val days = m.groupValues[1].toLongOrNull() ?: 0
        val hours = m.groupValues[2].toLongOrNull() ?: 0
        val minutes = m.groupValues[3].toLongOrNull() ?: 0
        val seconds = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return ((days * 86_400 + hours * 3_600 + minutes * 60) * 1_000) + (seconds * 1_000).toLong()
    }
}
