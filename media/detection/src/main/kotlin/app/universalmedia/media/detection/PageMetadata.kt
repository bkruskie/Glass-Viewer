package app.universalmedia.media.detection

/**
 * Reads the video a page *declares* about itself, the standard ways sites tell
 * search engines and link previews what their video is:
 *  - Open Graph (`og:video`, `og:video:secure_url`, `og:video:url`) and `og:video:duration`
 *  - Twitter cards (`twitter:player:stream`)
 *  - schema.org JSON-LD `VideoObject` / `AudioObject` (`contentUrl`, `duration`)
 *  - `<video src>`, `<audio src>`, `<source src>`
 *  - common player setups: JW Player / Video.js / Plyr `file:` / `src:` / `data-src`
 *
 * Unlike arbitrary .mp4 links on a page (often previews of other videos), these
 * name the page's own media, so whole-file links are kept too.
 */
object PageMetadata {

    data class Declared(
        val urls: List<String>,
        val title: String?,
        val durationMs: Long?,
    )

    private const val MAX_URLS = 16

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("([a-zA-Z:_-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val mediaTag = Regex("<(?:video|audio|source)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val jsonLd = Regex(
        "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",
        RegexOption.IGNORE_CASE,
    )
    private val playerFile = Regex(
        "[\"']?(?:file|src|contentUrl|videoUrl|video_url|mp4|hls|dash)[\"']?\\s*[:=]\\s*[\"'](https?:[^\"'\\s]+|/[^\"'\\s]+)[\"']",
        RegexOption.IGNORE_CASE,
    )
    private val titleTag = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)

    private val videoMetaKeys = setOf(
        "og:video", "og:video:url", "og:video:secure_url", "og:audio", "og:audio:url", "og:audio:secure_url",
        "twitter:player:stream",
    )

    fun read(html: String, pageUrl: String?): Declared {
        val urls = LinkedHashSet<String>()
        var title: String? = null
        var duration: Long? = null

        fun add(raw: String?) {
            val u = raw?.let { resolve(PageSourceScanner.unescape(htmlDecode(it.trim())), pageUrl) } ?: return
            if (looksLikeMedia(u)) urls += u
        }

        for (m in metaTag.findAll(html)) {
            val a = attributes(m.value)
            val key = (a["property"] ?: a["name"] ?: a["itemprop"])?.lowercase() ?: continue
            val content = a["content"] ?: continue
            when {
                key in videoMetaKeys -> add(content)
                key == "og:title" || key == "twitter:title" -> if (title == null) title = htmlDecode(content).trim().ifEmpty { null }
                key == "og:video:duration" || key == "video:duration" -> duration = duration ?: content.trim().toLongOrNull()?.times(1000)
                key == "duration" -> duration = duration ?: isoDurationMs(content)
            }
        }
        for (m in mediaTag.findAll(html)) {
            val a = attributes(m.value)
            add(a["src"])
            add(a["data-src"])
        }
        for (m in jsonLd.findAll(html)) {
            val json = runCatching { MiniJson.parse(m.groupValues[1].trim()) }.getOrNull() ?: continue
            walkJsonLd(json) { url, iso ->
                add(url)
                if (duration == null && iso != null) duration = isoDurationMs(iso)
            }
        }
        for (m in playerFile.findAll(PageSourceScanner.unescape(html))) add(m.groupValues[1])

        if (title == null) {
            title = titleTag.find(html)?.groupValues?.get(1)?.let { htmlDecode(it).trim() }?.ifEmpty { null }
        }
        return Declared(urls.take(MAX_URLS), title, duration)
    }

    /** Finds contentUrl (+ duration) on VideoObject / AudioObject anywhere in JSON-LD, including @graph. */
    private fun walkJsonLd(node: Any?, found: (String?, String?) -> Unit) {
        when (node) {
            is Map<*, *> -> {
                val type = node["@type"].let { t -> if (t is List<*>) t.joinToString(",") else t?.toString() }.orEmpty()
                if ("VideoObject" in type || "AudioObject" in type || "MediaObject" in type) {
                    found(node["contentUrl"] as? String, node["duration"] as? String)
                }
                node.values.forEach { walkJsonLd(it, found) }
            }
            is List<*> -> node.forEach { walkJsonLd(it, found) }
        }
    }

    private fun attributes(tag: String): Map<String, String> =
        attr.findAll(tag).associate { m ->
            m.groupValues[1].lowercase() to m.groupValues[2].trim('"', '\'')
        }

    /** Real media only: streams, or files with a media extension. Not pages, images or players. */
    internal fun looksLikeMedia(url: String): Boolean {
        if (!UrlUtils.isHttp(url)) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
    }

    private val MEDIA_EXTENSIONS = listOf("m3u8", "mpd", "mp4", "m4v", "webm", "mov", "mkv", "ts", "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")

    internal fun resolve(url: String, base: String?): String? {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (base == null) return null
        return runCatching { java.net.URI(base).resolve(url.replace(" ", "%20")).toString() }.getOrNull()
    }

    /** ISO-8601 duration like PT1H2M3S or PT95.5S → milliseconds. */
    internal fun isoDurationMs(iso: String): Long? {
        val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$", RegexOption.IGNORE_CASE).find(iso.trim()) ?: return null
        val (d, h, min, s) = m.destructured
        if (d.isEmpty() && h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        val total = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60
        return total * 1000 + ((s.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    private fun htmlDecode(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#x2F;", "/").replace("&#47;", "/")
}
