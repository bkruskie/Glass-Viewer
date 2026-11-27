package app.universalmedia.media.detection

/** In-memory [ManifestFetcher]: URL → body, URL → HTTP error, URL → probe. Records requests. */
class FakeFetcher(
    private val bodies: Map<String, String> = emptyMap(),
    private val errors: Map<String, Int> = emptyMap(),
    private val probes: Map<String, ProbeResult> = emptyMap(),
    private val redirects: Map<String, String> = emptyMap(),
) : ManifestFetcher {
    val requested = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun fetch(url: String, headers: Map<String, String>): FetchResult {
        requested += url to headers
        errors[url]?.let { return FetchResult.HttpError(it) }
        val final = redirects[url] ?: url
        val body = bodies[final] ?: return FetchResult.HttpError(404)
        return FetchResult.Success(body, null, final)
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ProbeResult? {
        requested += url to headers
        return probes[url]
    }
}
