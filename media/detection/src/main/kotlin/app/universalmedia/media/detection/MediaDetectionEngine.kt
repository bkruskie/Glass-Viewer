package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.core.model.Protection

/** Input to the engine: something that might be, or might point to, media. */
data class ResolveRequest(
    val url: String,
    val origin: DetectionOrigin,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val mimeType: String? = null,
    /** Extra headers for the origin server. Referer is added automatically from [pageUrl]. */
    val headers: Map<String, String> = emptyMap(),
) {
    val effectiveHeaders: Map<String, String>
        get() = if (pageUrl != null && "Referer" !in headers) headers + ("Referer" to pageUrl) else headers
}

sealed interface ResolveResult {
    data class Found(val media: List<DetectedMedia>) : ResolveResult
    data object NotMedia : ResolveResult
    data class Unavailable(val availability: Availability, val reason: String) : ResolveResult
}

/**
 * A replaceable integration for one website or family of sites.
 * Adapters are registered at start-up; adding one never touches the browser,
 * download manager or UI.
 */
interface SiteAdapter {
    val id: String
    val displayName: String
    fun matches(url: String): Boolean
    suspend fun resolve(request: ResolveRequest): ResolveResult
}

/** Result of fetching a small text resource (manifest or API response). */
sealed interface FetchResult {
    /** [finalUrl] is the URL after redirects; relative manifest entries resolve against it. */
    data class Success(val body: String, val contentType: String?, val finalUrl: String) : FetchResult
    data class HttpError(val code: Int) : FetchResult
    data class Failure(val reason: String) : FetchResult
}

/** Headers-only probe of a direct file. */
data class ProbeResult(val code: Int, val contentLength: Long?, val contentType: String?)

/** Network seam. OkHttp implementation lives in :core:network; tests use fakes. */
interface ManifestFetcher {
    suspend fun fetch(url: String, headers: Map<String, String>): FetchResult

    /** HEAD request. Returns null when the server doesn't support it or on network error. */
    suspend fun probe(url: String, headers: Map<String, String>): ProbeResult? = null
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

    fun forHttpStatus(code: Int): Availability =
        if (code == 401 || code == 403) Availability.AUTH_REQUIRED else Availability.INACCESSIBLE

    fun explainHttpStatus(code: Int): String = when (code) {
        401, 403 -> "The site requires you to be signed in. Sign in on the site in this browser, then try again."
        404, 410 -> "The source no longer exists (HTTP $code)."
        else -> "The server refused the request (HTTP $code)."
    }
}

/** Fills [MediaVariant.estimatedBytes] from bitrate × duration where the size isn't known. */
fun List<MediaVariant>.withEstimatedSizes(durationMs: Long?): List<MediaVariant> {
    if (durationMs == null || durationMs <= 0) return this
    return map { v ->
        if (v.estimatedBytes != null || v.bitrate == null) v else v.copy(estimatedBytes = v.bitrate!! * durationMs / 8_000)
    }
}

/**
 * Fallback adapter: handles direct files and stream manifests. With a fetcher it
 * reads HLS/DASH qualities, duration, protection and sizes; without one it only
 * classifies.
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
            else -> enrichDirect(base, request)
        }
        return ResolveResult.Found(listOf(enriched))
    }

    private suspend fun enrichDirect(media: DetectedMedia, request: ResolveRequest): DetectedMedia {
        val probe = fetcher?.probe(request.url, request.effectiveHeaders) ?: return media
        if (probe.code >= 400 && probe.code != 405) {
            return media.copy(
                availability = AvailabilityPolicy.forHttpStatus(probe.code),
                note = AvailabilityPolicy.explainHttpStatus(probe.code),
            )
        }
        val variant = MediaVariant(url = request.url, format = media.format, estimatedBytes = probe.contentLength)
        return media.copy(variants = listOf(variant), mimeType = media.mimeType ?: probe.contentType)
    }

    private suspend fun enrichHls(media: DetectedMedia, request: ResolveRequest): DetectedMedia {
        val fetcher = fetcher ?: return media
        val headers = request.effectiveHeaders
        val body = when (val r = fetcher.fetch(request.url, headers)) {
            is FetchResult.Success -> r
            is FetchResult.HttpError -> return media.unavailable(r.code)
            is FetchResult.Failure -> return media.copy(availability = Availability.INACCESSIBLE, note = r.reason)
        }
        return when (val playlist = HlsPlaylistParser.parse(body.body, body.finalUrl)) {
            null -> media.copy(availability = Availability.UNSUPPORTED, note = "Unrecognised playlist")
            is HlsPlaylist.Media -> {
                val withProt = media.withProtection(playlist.protection)
                withProt.copy(
                    durationMs = if (playlist.isLive) null else (playlist.totalDurationSeconds * 1000).toLong(),
                    note = if (playlist.isLive) "Live stream" else withProt.note,
                )
            }
            is HlsPlaylist.Master -> {
                // The duration lives in the media playlists; read the best one (one extra request).
                var protection = playlist.protection
                var durationMs: Long? = null
                var live = false
                val best = playlist.variants.firstOrNull { !it.audioOnly } ?: playlist.variants.firstOrNull()
                if (best != null) {
                    val sub = fetcher.fetch(best.url, headers)
                    if (sub is FetchResult.Success) {
                        val child = HlsPlaylistParser.parse(sub.body, sub.finalUrl)
                        if (child is HlsPlaylist.Media) {
                            live = child.isLive
                            if (!live) durationMs = (child.totalDurationSeconds * 1000).toLong()
                            protection = DrmSystems.merge(listOf(protection, child.protection))
                        }
                    }
                }
                val withProt = media.withProtection(protection)
                withProt.copy(
                    variants = playlist.variants.withEstimatedSizes(durationMs).map { it.copy(manifestUrl = body.finalUrl) },
                    durationMs = durationMs,
                    note = if (live) "Live stream" else withProt.note,
                )
            }
        }
    }

    private suspend fun enrichDash(media: DetectedMedia, request: ResolveRequest): DetectedMedia {
        val fetcher = fetcher ?: return media
        val body = when (val r = fetcher.fetch(request.url, request.effectiveHeaders)) {
            is FetchResult.Success -> r
            is FetchResult.HttpError -> return media.unavailable(r.code)
            is FetchResult.Failure -> return media.copy(availability = Availability.INACCESSIBLE, note = r.reason)
        }
        val summary = DashManifestInspector.inspect(body.body, body.finalUrl)
            ?: return media.copy(availability = Availability.UNSUPPORTED, note = "Unrecognised manifest")
        val withProt = media.withProtection(summary.protection)
        return withProt.copy(
            variants = summary.variants.withEstimatedSizes(summary.durationMs),
            durationMs = summary.durationMs,
            note = if (summary.isLive) "Live stream" else withProt.note,
        )
    }

    private fun DetectedMedia.withProtection(p: Protection) = copy(
        protection = p,
        availability = AvailabilityPolicy.evaluate(p),
        note = AvailabilityPolicy.explain(p) ?: note,
    )

    private fun DetectedMedia.unavailable(code: Int) = copy(
        availability = AvailabilityPolicy.forHttpStatus(code),
        note = AvailabilityPolicy.explainHttpStatus(code),
    )

    companion object {
        fun idFor(url: String): String = "m" + url.hashCode().toUInt().toString(16) + url.length.toString(16)
    }
}

/** Routes requests to site adapters, falling back to [GenericMediaAdapter] for media URLs. */
class MediaDetectionEngine(
    adapters: List<SiteAdapter> = emptyList(),
    private val fallback: SiteAdapter = GenericMediaAdapter(),
) {
    private val adapters: List<SiteAdapter> = adapters.toList()

    val registeredAdapters: List<SiteAdapter> get() = adapters + fallback

    /** For URLs that already look like media (requests, element sources, shared file links). */
    suspend fun resolve(request: ResolveRequest): ResolveResult {
        val adapter = adapters.firstOrNull { it.matches(request.url) } ?: fallback
        return safely { adapter.resolve(request) }
    }

    /**
     * For web pages (navigation, shared page links). Only dedicated site adapters
     * handle pages; returns [ResolveResult.NotMedia] when none matches.
     */
    suspend fun resolvePage(request: ResolveRequest): ResolveResult {
        val adapter = adapters.firstOrNull { it.matches(request.url) } ?: return ResolveResult.NotMedia
        return safely { adapter.resolve(request) }
    }

    /** Synchronous pre-filter, safe to call on the WebView network thread. */
    fun isCandidate(url: String, mimeType: String? = null): Boolean =
        MediaUrlClassifier.isActionable(MediaUrlClassifier.classify(url, mimeType))

    /** True when a dedicated adapter claims this page URL. Cheap. */
    fun hasPageAdapter(url: String): Boolean = adapters.any { it.matches(url) }

    private inline fun safely(block: () -> ResolveResult): ResolveResult = try {
        block()
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        ResolveResult.Unavailable(Availability.INACCESSIBLE, e.message ?: "Could not analyse this source")
    }
}
