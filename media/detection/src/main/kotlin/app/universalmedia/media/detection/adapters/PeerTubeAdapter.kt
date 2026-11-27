package app.universalmedia.media.detection.adapters

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.media.detection.AvailabilityPolicy
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
 * PeerTube, the federated open-source video platform. Any instance works: watch
 * pages (/w/{id}, /videos/watch/{id}, /videos/embed/{id}) are resolved through the
 * instance's public REST API (/api/v1/videos/{id}). Non-PeerTube sites that happen
 * to share the path shape simply return [ResolveResult.NotMedia].
 */
class PeerTubeAdapter(
    private val fetcher: ManifestFetcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SiteAdapter {
    override val id = "peertube"
    override val displayName = "PeerTube"

    private val watchPath = Regex("^/(?:w|videos/watch|videos/embed)/([A-Za-z0-9-]{8,})/?$")

    override fun matches(url: String): Boolean = videoIdOf(url) != null

    internal fun videoIdOf(url: String): String? {
        if (!UrlUtils.isHttp(url)) return null
        val path = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return watchPath.find(path)?.groupValues?.get(1)
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val videoId = videoIdOf(request.url) ?: return ResolveResult.NotMedia
        val host = UrlUtils.hostOf(request.url) ?: return ResolveResult.NotMedia
        val scheme = request.url.substringBefore("://").lowercase()
        val port = request.url.substringAfter("://").substringBefore('/').substringAfterLast('@')
            .let { if (it.contains(':')) ":" + it.substringAfter(':') else "" }
        val api = "$scheme://$host$port/api/v1/videos/$videoId"

        val body = when (val r = fetcher.fetch(api, mapOf("Accept" to "application/json"))) {
            is FetchResult.Success -> r.body
            is FetchResult.HttpError ->
                return if (r.code == 401 || r.code == 403) {
                    ResolveResult.Unavailable(Availability.AUTH_REQUIRED, AvailabilityPolicy.explainHttpStatus(r.code))
                } else {
                    ResolveResult.NotMedia // most likely not a PeerTube site
                }
            is FetchResult.Failure -> return ResolveResult.NotMedia
        }
        val video = MiniJsonAccess.parse(body) ?: return ResolveResult.NotMedia
        val name = MiniJsonAccess.str(video, "name") ?: return ResolveResult.NotMedia
        val durationMs = MiniJsonAccess.num(video, "duration")?.let { (it * 1000).toLong() }
        val isLive = MiniJsonAccess.str(video, "isLive") == "true"

        val playlists = MiniJsonAccess.arr(video, "streamingPlaylists")
        val files = MiniJsonAccess.arr(video, "files") + playlists.flatMap { MiniJsonAccess.arr(it, "files") }
        val progressive = files.mapNotNull { file ->
            val url = MiniJsonAccess.str(file, "fileDownloadUrl") ?: MiniJsonAccess.str(file, "fileUrl") ?: return@mapNotNull null
            val format = (MediaUrlClassifier.classify(url) as? MediaUrlClassifier.Result.Media)?.format ?: MediaFormat.MP4
            val height = MiniJsonAccess.num(MiniJsonAccess.obj(file, "resolution"), "id")?.toInt()
            MediaVariant(
                url = url,
                format = format,
                height = height?.takeIf { it > 0 },
                frameRate = MiniJsonAccess.num(file, "fps")?.takeIf { it > 0 },
                estimatedBytes = MiniJsonAccess.num(file, "size")?.toLong(),
                audioOnly = height == 0,
            )
        }.distinctBy { it.height to it.audioOnly }
            .sortedWith(compareBy<MediaVariant> { it.audioOnly }.thenByDescending { it.height ?: 0 })

        val hlsUrl = playlists.firstNotNullOfOrNull { MiniJsonAccess.str(it, "playlistUrl") }
        val (mediaUrl, format) = when {
            progressive.isNotEmpty() -> progressive.first().url to progressive.first().format
            hlsUrl != null -> hlsUrl to MediaFormat.HLS
            else -> return ResolveResult.NotMedia
        }

        return ResolveResult.Found(
            listOf(
                DetectedMedia(
                    id = GenericMediaAdapter.idFor(request.url),
                    mediaUrl = mediaUrl,
                    format = format,
                    origin = request.origin,
                    detectedAtMillis = clock(),
                    pageUrl = request.url,
                    title = name,
                    sourceHost = host,
                    durationMs = if (isLive) null else durationMs,
                    variants = progressive,
                    note = if (isLive) "Live stream" else null,
                ),
            ),
        )
    }
}
