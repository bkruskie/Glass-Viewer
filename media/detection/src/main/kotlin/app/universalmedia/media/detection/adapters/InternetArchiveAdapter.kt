package app.universalmedia.media.detection.adapters

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.MediaKind
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.media.detection.FetchResult
import app.universalmedia.media.detection.GenericMediaAdapter
import app.universalmedia.media.detection.ManifestFetcher
import app.universalmedia.media.detection.MediaUrlClassifier
import app.universalmedia.media.detection.MiniJsonAccess
import app.universalmedia.media.detection.ResolveRequest
import app.universalmedia.media.detection.ResolveResult
import app.universalmedia.media.detection.SiteAdapter
import app.universalmedia.media.detection.UrlUtils

/**
 * Internet Archive (archive.org) items, via the public metadata API:
 * https://archive.org/metadata/{identifier}. Lending-library and restricted
 * items are reported as unavailable rather than fetched.
 */
class InternetArchiveAdapter(
    private val fetcher: ManifestFetcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SiteAdapter {
    override val id = "internet-archive"
    override val displayName = "Internet Archive"

    private val detailsPath = Regex("^/(?:details|embed)/([A-Za-z0-9._-]+)")

    override fun matches(url: String): Boolean = identifierOf(url) != null

    internal fun identifierOf(url: String): String? {
        val host = UrlUtils.hostOf(url) ?: return null
        if (host != "archive.org" && host != "www.archive.org") return null
        val path = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return detailsPath.find(path)?.groupValues?.get(1)
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val identifier = identifierOf(request.url) ?: return ResolveResult.NotMedia
        val response = fetcher.fetch("https://archive.org/metadata/$identifier", emptyMap())
        val body = when (response) {
            is FetchResult.Success -> response.body
            is FetchResult.HttpError -> return ResolveResult.Unavailable(Availability.INACCESSIBLE, "Archive returned HTTP ${response.code}")
            is FetchResult.Failure -> return ResolveResult.Unavailable(Availability.INACCESSIBLE, response.reason)
        }
        val root = MiniJsonAccess.parse(body) ?: return ResolveResult.NotMedia
        val metadata = MiniJsonAccess.obj(root, "metadata") ?: return ResolveResult.NotMedia

        if (MiniJsonAccess.str(metadata, "access-restricted-item") == "true") {
            return ResolveResult.Unavailable(
                Availability.AUTH_REQUIRED,
                "This item is in the lending library. Borrow it on archive.org to watch or read it there.",
            )
        }

        val title = MiniJsonAccess.str(metadata, "title") ?: identifier
        var durationMs: Long? = null
        val variants = MiniJsonAccess.arr(root, "files").mapNotNull { file ->
            val name = MiniJsonAccess.str(file, "name") ?: return@mapNotNull null
            if (name.contains("_thumb") || name.endsWith(".gif")) return@mapNotNull null
            val url = "https://archive.org/download/$identifier/" + encodePath(name)
            val result = MediaUrlClassifier.classify(url) as? MediaUrlClassifier.Result.Media ?: return@mapNotNull null
            val kind = result.format.kind
            if (kind != MediaKind.VIDEO && kind != MediaKind.AUDIO) return@mapNotNull null
            parseLength(MiniJsonAccess.str(file, "length"))?.let { if (durationMs == null || it > durationMs!!) durationMs = it }
            MediaVariant(
                url = url,
                format = result.format,
                width = MiniJsonAccess.num(file, "width")?.toInt(),
                height = MiniJsonAccess.num(file, "height")?.toInt(),
                estimatedBytes = MiniJsonAccess.num(file, "size")?.toLong(),
                audioOnly = kind == MediaKind.AUDIO,
            )
        }.sortedWith(
            compareBy<MediaVariant> { it.audioOnly }
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.estimatedBytes ?: 0 },
        ).take(MAX_FILES)

        if (variants.isEmpty()) return ResolveResult.NotMedia
        val best = variants.first()
        return ResolveResult.Found(
            listOf(
                DetectedMedia(
                    id = GenericMediaAdapter.idFor(request.url),
                    mediaUrl = best.url,
                    format = best.format,
                    origin = request.origin,
                    detectedAtMillis = clock(),
                    pageUrl = request.url,
                    title = title,
                    sourceHost = "archive.org",
                    durationMs = durationMs,
                    variants = variants,
                    note = if (variants.size > 1) "${variants.size} files available" else null,
                ),
            ),
        )
    }

    /** "length" is seconds ("123.4") or a clock ("1:02:03"). */
    internal fun parseLength(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val parts = value.split(':').map { it.toDoubleOrNull() ?: return null }
        return (parts.fold(0.0) { acc, p -> acc * 60 + p } * 1000).toLong()
    }

    private fun encodePath(name: String): String = name.split('/').joinToString("/") { segment ->
        buildString {
            for (ch in segment) {
                if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-._~") append(ch)
                else ch.toString().encodeToByteArray().forEach { b -> append('%').append("%02X".format(b.toInt() and 0xFF)) }
            }
        }
    }

    private companion object {
        const val MAX_FILES = 30
    }
}
