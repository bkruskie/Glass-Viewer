package app.universalmedia.media.detection

/**
 * Small, dependency-free URL helpers (kept free of java.net so the module can
 * become Kotlin Multiplatform later).
 */
object UrlUtils {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Host without port or credentials, lower-cased; null if not an http(s) URL. */
    fun hostOf(url: String): String? {
        if (!isHttp(url)) return null
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return host.ifEmpty { null }
    }

    /** Lower-cased file extension of the URL path, ignoring query and fragment. */
    fun extensionOf(url: String): String? {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        if (dot <= 0 || dot == last.length - 1) return null
        return last.substring(dot + 1).lowercase()
    }

    /** Last path segment without extension, useful as a fallback title. */
    fun fileNameOf(url: String): String? {
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        return last.ifEmpty { null }
    }

    /** Resolves [ref] against [base] (RFC 3986 subset sufficient for media manifests). */
    fun resolve(base: String, ref: String): String {
        if (schemeRegex.containsMatchIn(ref)) return ref
        val scheme = base.substringBefore("://")
        val afterScheme = base.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (ref.startsWith("//")) return "$scheme:$ref"

        val basePath = "/" + afterScheme.removePrefix(authority).removePrefix("/")
            .substringBefore('?').substringBefore('#')
        val rawPath = when {
            ref.startsWith("/") -> ref
            ref.startsWith("?") -> basePath + ref
            else -> basePath.substringBeforeLast('/') + "/" + ref
        }
        val pathPart = rawPath.substringBefore('?')
        val query = rawPath.substring(pathPart.length)

        val out = ArrayDeque<String>()
        for (segment in pathPart.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(segment)
            }
        }
        val trailingSlash = if (pathPart.endsWith("/") && out.isNotEmpty()) "/" else ""
        return "$scheme://$authority/" + out.joinToString("/") + trailingSlash + query
    }
}
