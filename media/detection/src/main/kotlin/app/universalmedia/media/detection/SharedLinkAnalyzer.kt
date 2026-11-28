package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin

/**
 * Looks at a link shared from another app without opening the browser:
 * a site adapter, a direct media link, or else the page's HTML (what it
 * declares about its video, and stream links in its code).
 *
 * Pages that build their player with script only show their video in the
 * browser; for those the answer is [Outcome.NeedsBrowser].
 */
class SharedLinkAnalyzer(
    private val engine: MediaDetectionEngine,
    private val fetcher: ManifestFetcher,
) {
    sealed interface Outcome {
        data class Found(val media: List<DetectedMedia>, val pageTitle: String?) : Outcome
        data class Blocked(val reason: String) : Outcome
        data object NeedsBrowser : Outcome
    }

    suspend fun analyze(url: String): Outcome {
        val request = ResolveRequest(url = url, origin = DetectionOrigin.SHARE_INTENT)
        val direct = when {
            engine.hasPageAdapter(url) -> engine.resolvePage(request)
            engine.isCandidate(url) -> engine.resolve(request)
            else -> null
        }
        when (direct) {
            is ResolveResult.Found -> return Outcome.Found(direct.media, null)
            is ResolveResult.Unavailable -> return Outcome.Blocked(direct.reason)
            else -> Unit
        }

        val page = fetcher.fetch(url, mapOf("Accept" to "text/html,application/xhtml+xml")) as? FetchResult.Success
            ?: return Outcome.NeedsBrowser
        if (page.contentType?.contains("html", ignoreCase = true) == false) return Outcome.NeedsBrowser
        val declared = PageMetadata.read(page.body, page.finalUrl)
        val candidates = (declared.urls + PageSourceScanner.findManifestUrls(page.body)).distinct().take(MAX_CANDIDATES)
        val found = mutableListOf<DetectedMedia>()
        var blocked: String? = null
        for (candidate in candidates) {
            val result = engine.resolve(
                ResolveRequest(
                    url = candidate,
                    origin = DetectionOrigin.SHARE_INTENT,
                    pageUrl = page.finalUrl,
                    pageTitle = declared.title,
                ),
            )
            when (result) {
                is ResolveResult.Found -> found += result.media.map { m ->
                    if (m.durationMs == null && declared.durationMs != null) m.copy(durationMs = declared.durationMs) else m
                }
                is ResolveResult.Unavailable -> blocked = blocked ?: result.reason
                ResolveResult.NotMedia -> Unit
            }
        }
        val usable = found.filter { it.availability == Availability.SUPPORTED }
        return when {
            usable.isNotEmpty() -> Outcome.Found(usable.distinctBy { it.mediaUrl }, declared.title)
            found.isNotEmpty() -> Outcome.Blocked(found.first().note ?: "This video is protected and can't be saved.")
            blocked != null -> Outcome.Blocked(blocked)
            else -> Outcome.NeedsBrowser
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 6
    }
}
