package app.universalmedia.media.detection

/**
 * Finds stream links that a page embeds in its own HTML or player configuration
 * (common on video sites, which list their HLS/DASH manifests in inline JSON
 * before playback starts). Only manifests are collected: whole-file links on a
 * page are often thumbnails or previews of other videos, and real files are
 * still caught when the player loads them.
 */
object PageSourceScanner {

    private const val MAX_RESULTS = 24

    private val manifestUrl = Regex(
        "https?://[^\\s\"'<>\\\\]+?\\.(?:m3u8|mpd)(?:\\?[^\\s\"'<>\\\\]*)?(?=[\\s\"'<>\\\\,)]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun findManifestUrls(html: String): List<String> {
        val text = unescape(html)
        return manifestUrl.findAll(text)
            .map { it.value.trimEnd('.', ',', ';') }
            .filter { UrlUtils.isHttp(it) }
            .distinct()
            .take(MAX_RESULTS)
            .toList()
    }

    /** Undoes the escaping used when links sit inside JSON or HTML attributes. */
    internal fun unescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/")
}
