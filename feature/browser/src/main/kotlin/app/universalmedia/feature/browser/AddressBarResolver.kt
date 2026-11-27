package app.universalmedia.feature.browser

import app.universalmedia.core.model.SearchEngine
import java.net.URLEncoder

/** Turns whatever the user typed into a URL to load. */
object AddressBarResolver {
    private val hostLike = Regex(
        "^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(:\\d{1,5})?([/?#].*)?$",
    )

    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            return text
        }
        // Any other scheme (javascript:, file:, intent:, content:) is treated as a search, never executed.
        if (!text.contains(' ') && hostLike.matches(text)) return "https://$text"
        return engine.queryTemplate.replace("%s", URLEncoder.encode(text, "UTF-8"))
    }

    /** Shorter address shown while the bar is not focused. */
    fun display(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .removeSuffix("/")
}

/** Extracts the first web link from text shared by another app. */
object SharedTextParser {
    private val urlRegex = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String?): String? =
        text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';') }
}
