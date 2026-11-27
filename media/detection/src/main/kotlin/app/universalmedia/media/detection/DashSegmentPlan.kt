package app.universalmedia.media.detection

import kotlin.math.ceil

/** One downloadable piece of a DASH track. */
data class DashSegment(val url: String, val byteRange: ByteRange? = null)

/** A fully expanded DASH representation (one quality of one track). */
data class DashTrack(
    val id: String,
    val isAudio: Boolean,
    val height: Int?,
    val bandwidth: Long?,
    val codecs: String?,
    val mimeType: String?,
    val language: String?,
    val initSegment: DashSegment?,
    val segments: List<DashSegment>,
)

/**
 * Turns an MPD into concrete segment URLs for downloading (first period only).
 * Supports SegmentTemplate ($Number$ / $Time$, with or without SegmentTimeline),
 * SegmentList, and single-file representations (BaseURL / SegmentBase).
 */
data class DashSegmentPlan(
    val tracks: List<DashTrack>,
    val durationMs: Long?,
    val isLive: Boolean,
    val isProtected: Boolean,
) {
    val videoTracks: List<DashTrack> get() = tracks.filter { !it.isAudio }
    val audioTracks: List<DashTrack> get() = tracks.filter { it.isAudio }

    /** Video track closest to the requested quality (or the best one), plus the best audio track. */
    fun select(targetHeight: Int?, targetBitrate: Long?, audioOnly: Boolean): List<DashTrack> {
        val audio = audioTracks.maxByOrNull { it.bandwidth ?: 0 }
        if (audioOnly) return listOfNotNull(audio ?: videoTracks.minByOrNull { it.bandwidth ?: 0 })
        val video = videoTracks.minWithOrNull(
            compareBy<DashTrack>(
                { if (targetHeight == null) -(it.height ?: 0) else kotlin.math.abs((it.height ?: 0) - targetHeight) },
                { if (targetBitrate == null) -(it.bandwidth ?: 0) else kotlin.math.abs((it.bandwidth ?: 0) - targetBitrate) },
            ),
        )
        return listOfNotNull(video, audio)
    }

    companion object {
        private const val MAX_SEGMENTS = 100_000

        fun parse(xml: String, mpdUrl: String): DashSegmentPlan? {
            val mpd = MiniXml.parse(xml)?.takeIf { it.name == "MPD" } ?: return null
            val live = mpd["type"].equals("dynamic", ignoreCase = true)
            val mpdBase = baseOf(mpd, mpdUrl)
            val period = mpd.all("Period").firstOrNull() ?: return null
            val periodBase = baseOf(period, mpdBase)
            val durationSec = (period["duration"] ?: mpd["mediaPresentationDuration"])
                ?.let(DashManifestInspector::parseIsoDurationMs)?.div(1000.0)

            var protected = false
            val tracks = mutableListOf<DashTrack>()
            for (set in period.all("AdaptationSet")) {
                if (set.child("ContentProtection") != null) protected = true
                val setBase = baseOf(set, periodBase)
                for (rep in set.all("Representation")) {
                    if (rep.child("ContentProtection") != null) protected = true
                    val mime = rep["mimeType"] ?: set["mimeType"]
                    val contentType = set["contentType"] ?: mime?.substringBefore('/')
                    if (contentType != "video" && contentType != "audio") continue // skip text, images
                    val repBase = baseOf(rep, setBase)
                    val id = rep["id"] ?: tracks.size.toString()
                    val bandwidth = rep["bandwidth"]?.toLongOrNull()
                    val (init, segments) = expand(rep, set, period, repBase, id, bandwidth, durationSec) ?: continue
                    tracks += DashTrack(
                        id = id,
                        isAudio = contentType == "audio",
                        height = (rep["height"] ?: set["height"])?.toIntOrNull(),
                        bandwidth = bandwidth,
                        codecs = rep["codecs"] ?: set["codecs"],
                        mimeType = mime,
                        language = set["lang"],
                        initSegment = init,
                        segments = segments,
                    )
                }
            }
            return DashSegmentPlan(
                tracks = tracks,
                durationMs = durationSec?.let { (it * 1000).toLong() },
                isLive = live,
                isProtected = protected,
            )
        }

        private fun baseOf(element: XmlElement, parentBase: String): String =
            element.child("BaseURL")?.text?.takeIf { it.isNotBlank() }?.let { UrlUtils.resolve(parentBase, it) } ?: parentBase

        private fun expand(
            rep: XmlElement,
            set: XmlElement,
            period: XmlElement,
            base: String,
            id: String,
            bandwidth: Long?,
            durationSec: Double?,
        ): Pair<DashSegment?, List<DashSegment>>? {
            // Inheritance: Representation > AdaptationSet > Period.
            val templates = listOfNotNull(rep.child("SegmentTemplate"), set.child("SegmentTemplate"), period.child("SegmentTemplate"))
            if (templates.isNotEmpty()) {
                fun attr(name: String): String? = templates.firstNotNullOfOrNull { it[name] }
                val timeline = templates.firstNotNullOfOrNull { it.child("SegmentTimeline") }
                val media = attr("media") ?: return null
                val startNumber = attr("startNumber")?.toLongOrNull() ?: 1L
                val timescale = attr("timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
                val init = attr("initialization")?.let {
                    DashSegment(UrlUtils.resolve(base, fill(it, id, bandwidth, null, null)))
                }
                val segments = mutableListOf<DashSegment>()
                if (timeline != null) {
                    var time = 0L
                    var number = startNumber
                    val end = durationSec?.let { (it * timescale).toLong() + (attr("presentationTimeOffset")?.toLongOrNull() ?: 0L) }
                    for (s in timeline.all("S")) {
                        s["t"]?.toLongOrNull()?.let { time = it }
                        val d = s["d"]?.toLongOrNull()?.takeIf { it > 0 } ?: continue
                        var repeat = s["r"]?.toLongOrNull() ?: 0L
                        if (repeat < 0) repeat = if (end != null) ceil((end - time).toDouble() / d).toLong() - 1 else 0
                        for (k in 0..repeat) {
                            segments += DashSegment(UrlUtils.resolve(base, fill(media, id, bandwidth, number, time)))
                            time += d
                            number++
                            if (segments.size > MAX_SEGMENTS) return null
                        }
                    }
                } else {
                    val segDuration = attr("duration")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
                    val total = durationSec ?: return null
                    val count = ceil(total * timescale / segDuration).toLong().coerceIn(1, MAX_SEGMENTS.toLong())
                    for (k in 0 until count) {
                        val number = startNumber + k
                        segments += DashSegment(UrlUtils.resolve(base, fill(media, id, bandwidth, number, k * segDuration)))
                    }
                }
                return init to segments
            }

            val list = rep.child("SegmentList") ?: set.child("SegmentList")
            if (list != null) {
                val init = list.child("Initialization")?.let { i ->
                    DashSegment(UrlUtils.resolve(base, i["sourceURL"] ?: ""), i["range"]?.let(::range))
                }
                val segments = list.all("SegmentURL").map { s ->
                    DashSegment(UrlUtils.resolve(base, s["media"] ?: ""), s["mediaRange"]?.let(::range))
                }
                return init to segments
            }

            // Single file (BaseURL, optionally with SegmentBase index): download it whole.
            return null to listOf(DashSegment(base))
        }

        /** Expands $RepresentationID$, $Number%05d$, $Time$, $Bandwidth$ and $$. */
        internal fun fill(template: String, id: String, bandwidth: Long?, number: Long?, time: Long?): String {
            val out = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c != '$') { out.append(c); i++; continue }
                val end = template.indexOf('$', i + 1)
                if (end < 0) { out.append(template, i, template.length); break }
                val token = template.substring(i + 1, end)
                val name = token.substringBefore('%')
                val format = token.substringAfter('%', "").takeIf { it.isNotEmpty() }
                val value: Long? = when (name) {
                    "Number" -> number
                    "Time" -> time
                    "Bandwidth" -> bandwidth
                    else -> null
                }
                when {
                    token.isEmpty() -> out.append('$')
                    name == "RepresentationID" -> out.append(id)
                    value != null -> out.append(if (format != null) pad(value, format) else value.toString())
                    else -> out.append('$').append(token).append('$')
                }
                i = end + 1
            }
            return out.toString()
        }

        private fun pad(value: Long, format: String): String {
            val width = format.removeSuffix("d").trimStart('0').toIntOrNull() ?: 0
            return value.toString().padStart(width, '0')
        }

        private fun range(spec: String): ByteRange? {
            val start = spec.substringBefore('-').toLongOrNull() ?: return null
            val end = spec.substringAfter('-').toLongOrNull() ?: return null
            return ByteRange(start, end - start + 1)
        }
    }
}
