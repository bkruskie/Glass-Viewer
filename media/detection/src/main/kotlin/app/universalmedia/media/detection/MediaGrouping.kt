package app.universalmedia.media.detection

import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.MediaKind
import app.universalmedia.core.model.MediaVariant

/**
 * Turns the raw list of finds on a page into one entry per video, each with its
 * qualities sorted best first:
 *  - quality playlists already listed by a stream's master are folded into it;
 *  - files that differ only by a resolution in their link ("…_720p.mp4",
 *    "…/1080/…") become one entry with several qualities;
 *  - resolutions are read from links when the source doesn't state them;
 *  - videos come first (highest quality first), then audio.
 */
object MediaGrouping {

    private val resolutionToken = Regex("(?<![0-9A-Za-z])(2160|1440|1080|720|540|480|360|240|144)[pP]?(?![0-9])")

    /** Resolution in a link, e.g. 720 for ".../720p/index.m3u8" or "clip_1080.mp4". */
    fun heightFromUrl(url: String): Int? {
        val path = url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
        return resolutionToken.findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    fun organize(items: List<DetectedMedia>): List<DetectedMedia> {
        if (items.isEmpty()) return items

        // 1. Fold entries that are just one quality of another entry's stream.
        val listed = items.flatMap { item -> item.variants.filter { it.url != item.mediaUrl }.map { it.url } }.toHashSet()
        val remaining = items.filter { it.variants.size > 1 || it.mediaUrl !in listed }

        // 2. Fill in resolutions from links.
        val withHeights = remaining.map { item ->
            val variants = item.variants.ifEmpty { listOf(MediaVariant(url = item.mediaUrl, format = item.format)) }
                .map { v -> if (v.height != null || v.audioOnly) v else v.copy(height = heightFromUrl(v.url)) }
            item.copy(variants = variants)
        }

        // 3. Merge single-quality entries that are the same video at different resolutions.
        val merged = mutableListOf<DetectedMedia>()
        val groups = LinkedHashMap<String, MutableList<DetectedMedia>>()
        for (item in withHeights) {
            val single = item.variants.singleOrNull()
            val key = if (single?.height != null && item.variants.size == 1) groupKey(item, single.url) else null
            if (key == null) merged += item else groups.getOrPut(key) { mutableListOf() } += item
        }
        for (group in groups.values) {
            if (group.size == 1) {
                merged += group.single()
                continue
            }
            val variants = group.map { it.variants.single() }
                .distinctBy { it.height }
                .sortedWith(byQuality)
            val best = group.first { it.variants.single().url == variants.first().url }
            merged += best.copy(
                mediaUrl = variants.first().url,
                variants = variants,
                durationMs = group.firstNotNullOfOrNull { it.durationMs },
                title = group.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) } ?: best.title,
            )
        }

        // 4. Order: videos by best quality, then audio.
        return merged
            .map { it.copy(variants = it.variants.sortedWith(byQuality)) }
            .sortedWith(
                compareBy<DetectedMedia> { if (isAudio(it)) 1 else 0 }
                    .thenByDescending { it.variants.firstOrNull()?.height ?: 0 }
                    .thenByDescending { it.variants.size },
            )
    }

    fun isAudio(media: DetectedMedia): Boolean =
        media.format.kind == MediaKind.AUDIO || (media.variants.isNotEmpty() && media.variants.all { it.audioOnly })

    /** Every URL an organized entry stands for (used to hide it as a whole). */
    fun urlsOf(media: DetectedMedia): Set<String> = (media.variants.map { it.url } + media.mediaUrl).toSet()

    private val byQuality = compareBy<MediaVariant> { it.audioOnly }
        .thenByDescending { it.height ?: 0 }
        .thenByDescending { it.bitrate ?: 0 }
        .thenByDescending { it.estimatedBytes ?: 0 }

    private fun groupKey(item: DetectedMedia, url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        if (resolutionToken.find(path) == null) return null
        return item.format.name + "|" + (item.pageUrl ?: "") + "|" + resolutionToken.replace(path, "{h}")
    }
}
