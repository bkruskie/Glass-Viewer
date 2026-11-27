package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.Protection

/** Input to the engine: something that might be, or might point to, media. */
data class ResolveRequest(
    val url: String,
    val origin: DetectionOrigin,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val mimeType: String? = null,
    /** Headers the page itself would send (cookies, referer) for authorized access. */
    val headers: Map<String, String> = emptyMap(),
)

sealed interface ResolveResult {
    data class Found(val media: List<DetectedMedia>) : ResolveResult
    data object NotMedia : ResolveResult
    data class Unavailable(val availability: Availability, val reason: String) : ResolveResult
}

/**
 * A replaceable integration for one website or family of players.
 * Adapters are registered at start-up; adding one never requires touching the
 * browser, download manager or UI.
 */
interface SiteAdapter {
    val id: String
    val displayName: String
    fun matches(url: String): Boolean
    suspend fun resolve(request: ResolveRequest): ResolveResult
}

/** Network seam. Implemented with OkHttp in the app (Milestone 2); faked in tests. */
fun interface ManifestFetcher {
    /** Returns the body as text, or null if unreachable. Must respect [headers]. */
    suspend fun fetchText(url: String, headers: Map<String, String>): String?
}

/** Decides what the user may do with a source, based only on its technical protection. */
object AvailabilityPolicy {
    fun evaluate(protection: Protection): Availability = when (protection) {
        Protection.None -> Availability.SUPPORTED
        is Protection.StreamEncryption -> Availability.SUPPORTED
        is Protection.Drm -> Availability.PROTECTED
    }

    fun explain(protection: Protection): String? = when (protection) {
        Protection.None -> null
        is Protection.StreamEncryption ->
            "Encrypted stream (${protection.method}). Playable with keys the site sends to your session."
        is Protection.Drm ->
            "Protected by ${protection.systems.joinToString { it.displayName }}. It can be watched on the " +
                "site, but it can't be saved. Use the service's own offline or download option."
    }
}

/**
 * Fallback adapter: handles direct files and, when a fetcher is available,
 * inspects HLS / DASH manifests for qualities and protection.
 */
class GenericMediaAdapter(
    private val fetcher: ManifestFetcher? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SiteAdapter {
    override val id = "generic"
    override val displayName = "Generic media"

    override fun matches(url: String): Boolean = true

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val result = MediaUrlClassifier.classify(request.url, request.mimeType)
        if (!MediaUrlClassifier.isActionable(result)) return ResolveResult.NotMedia
        val format = (result as MediaUrlClassifier.Result.Media).format

        val base = DetectedMedia(
            id = idFor(request.url),
            mediaUrl = request.url,
            format = format,
            origin = request.origin,
            detectedAtMillis = clock(),
            pageUrl = request.pageUrl,
            title = request.pageTitle?.takeIf { it.isNotBlank() } ?: UrlUtils.fileNameOf(request.url),
            sourceHost = UrlUtils.hostOf(request.pageUrl ?: request.url),
            mimeType = request.mimeType,
        )

        val enriched = when (format) {
            MediaFormat.HLS -> enrichHls(base, request)
            MediaFormat.DASH -> enrichDash(base, request)
            else -> base
        }
        return ResolveResult.Found(listOf(enriched))
    }

    private suspend fun enrichHls(media: DetectedMedia, request: ResolveRequest): DetectedMedia {
        val body = fetcher?.fetchText(request.url, request.headers) ?: return media
        return when (val playlist = HlsPlaylistParser.parse(body, request.url)) {
            null -> media.copy(availability = Availability.UNSUPPORTED, note = "Unrecognised playlist")
            is HlsPlaylist.Master -> media.withProtection(playlist.protection).copy(variants = playlist.variants)
            is HlsPlaylist.Media -> media.withProtection(playlist.protection).copy(
                durationMs = if (playlist.isLive) null else (playlist.totalDurationSeconds * 1000).toLong(),
                note = if (playlist.isLive) "Live stream" else media.note,
            )
        }
    }

    private suspend fun enrichDash(media: DetectedMedia, request: ResolveRequest): DetectedMedia {
        val body = fetcher?.fetchText(request.url, request.headers) ?: return media
        val summary = DashManifestInspector.inspect(body, request.url)
            ?: return media.copy(availability = Availability.UNSUPPORTED, note = "Unrecognised manifest")
        return media.withProtection(summary.protection).copy(
            variants = summary.variants,
            durationMs = summary.durationMs,
            note = if (summary.isLive) "Live stream" else media.note,
        )
    }

    private fun DetectedMedia.withProtection(p: Protection) = copy(
        protection = p,
        availability = AvailabilityPolicy.evaluate(p),
        note = AvailabilityPolicy.explain(p) ?: note,
    )

    companion object {
        fun idFor(url: String): String = "m" + url.hashCode().toUInt().toString(16) + url.length.toString(16)
    }
}

/** Routes a request to the first matching site adapter, else the generic one. */
class MediaDetectionEngine(
    adapters: List<SiteAdapter> = emptyList(),
    private val fallback: SiteAdapter = GenericMediaAdapter(),
) {
    private val adapters: List<SiteAdapter> = adapters.toList()

    val registeredAdapters: List<SiteAdapter> get() = adapters + fallback

    suspend fun resolve(request: ResolveRequest): ResolveResult {
        val adapter = adapters.firstOrNull { it.matches(request.url) } ?: fallback
        return try {
            adapter.resolve(request)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            ResolveResult.Unavailable(Availability.INACCESSIBLE, e.message ?: "Could not analyse this source")
        }
    }

    /** Synchronous pre-filter, safe to call on the WebView network thread. */
    fun isCandidate(url: String, mimeType: String? = null): Boolean =
        MediaUrlClassifier.isActionable(MediaUrlClassifier.classify(url, mimeType))
}
