package app.universalmedia.core.model

enum class SearchEngine(val displayName: String, val queryTemplate: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
}

/** All user preferences. Stored locally only; nothing is synced anywhere. */
data class AppSettings(
    // Detection
    val detectionEnabled: Boolean = true,
    val detectionNotifications: Boolean = true,
    // Browser
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val javascriptEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    // Downloads (consumed by the download engine, Milestone 3)
    val wifiOnlyDownloads: Boolean = false,
    val maxConcurrentDownloads: Int = 3,
    // Appearance
    val pureBlackTheme: Boolean = false,
)
