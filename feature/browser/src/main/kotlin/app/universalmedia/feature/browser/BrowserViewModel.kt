package app.universalmedia.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.universalmedia.core.database.BookmarkDao
import app.universalmedia.core.database.BookmarkEntity
import app.universalmedia.core.database.HistoryDao
import app.universalmedia.core.database.HistoryEntity
import app.universalmedia.core.datastore.SettingsRepository
import app.universalmedia.core.model.AppSettings
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.media.detection.MediaDetectionEngine
import app.universalmedia.media.detection.ResolveRequest
import app.universalmedia.media.detection.ResolveResult
import app.universalmedia.media.detection.UrlUtils
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val detected: List<DetectedMedia> = emptyList(),
    /** URL the WebView should load next; consumed by the UI. */
    val pendingLoad: String? = null,
    /** Last page URL handed to site adapters, to avoid resolving the same page twice. */
    val resolvedPageUrl: String? = null,
) {
    val isStartPage: Boolean get() = url.isBlank() && pendingLoad == null
    val displayTitle: String get() = title.ifBlank { UrlUtils.hostOf(url) ?: "New tab" }
}

data class BrowserUiState(
    val tabs: List<BrowserTab>,
    val activeTabId: String,
    val settings: AppSettings = AppSettings(),
    val isBookmarked: Boolean = false,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
) {
    val activeTab: BrowserTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
}

/**
 * Owns browser tabs, history, bookmarks and in-page media detection.
 * Scoped to the Activity so tabs survive moving between app sections.
 * WebView instances live in [WebViewPool]; this class never touches a View.
 */
class BrowserViewModel(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    settingsRepository: SettingsRepository,
    private val detectionEngine: MediaDetectionEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel(), BrowserEvents {

    private data class Session(val tabs: List<BrowserTab>, val activeTabId: String) {
        val activeTab: BrowserTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    }

    private val session = MutableStateFlow(newTab().let { Session(listOf(it), it.id) })

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off user-facing notices (e.g. "Sign-in required"), shown as toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val isBookmarked: Flow<Boolean> = session
        .map { it.activeTab.url }
        .distinctUntilChanged()
        .flatMapLatest { url -> if (url.isBlank()) flowOf(false) else bookmarkDao.observeIsBookmarked(url) }

    val uiState: StateFlow<BrowserUiState> = combine(
        session,
        settings,
        isBookmarked,
        bookmarkDao.observeAll(),
        historyDao.observeRecent(HISTORY_LIMIT),
    ) { s, st, bookmarked, bookmarks, history ->
        BrowserUiState(s.tabs, s.activeTabId, st, bookmarked, bookmarks, history)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        session.value.let { BrowserUiState(it.tabs, it.activeTabId) },
    )

    // ---- Commands from the UI -------------------------------------------------

    /** Handles text typed into the address bar. */
    fun submit(input: String) {
        val url = AddressBarResolver.resolve(input, settings.value.searchEngine)
        if (url.isNotEmpty()) load(session.value.activeTabId, url)
    }

    /** Opens a URL, e.g. from a bookmark, history entry or another app. */
    fun open(url: String, inNewTab: Boolean = false) {
        if (inNewTab) {
            val tab = newTab(pendingLoad = url)
            session.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
        } else {
            load(session.value.activeTabId, url)
        }
    }

    /**
     * A link shared from another app: open it in a new tab and analyse it right
     * away, so a direct file or a supported page shows its media immediately.
     */
    fun openShared(url: String) {
        val tab = newTab(pendingLoad = url).copy(resolvedPageUrl = url)
        session.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
        viewModelScope.launch {
            val request = ResolveRequest(url = url, origin = DetectionOrigin.SHARE_INTENT)
            val result = when {
                detectionEngine.hasPageAdapter(url) -> detectionEngine.resolvePage(request)
                detectionEngine.isCandidate(url) -> detectionEngine.resolve(request)
                else -> ResolveResult.NotMedia // an ordinary page: in-page detection takes over as it loads
            }
            when (result) {
                is ResolveResult.Found -> addDetected(tab.id, result.media)
                is ResolveResult.Unavailable -> _messages.tryEmit(result.reason)
                ResolveResult.NotMedia -> Unit
            }
        }
    }

    fun newTabAndSelect() {
        val tab = newTab()
        session.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
    }

    fun selectTab(id: String) = session.update { s -> if (s.tabs.any { it.id == id }) s.copy(activeTabId = id) else s }

    fun closeTab(id: String) = session.update { s ->
        val index = s.tabs.indexOfFirst { it.id == id }
        if (index < 0) return@update s
        val remaining = s.tabs.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val fresh = newTab()
            Session(listOf(fresh), fresh.id)
        } else {
            val active = if (s.activeTabId == id) remaining[(index - 1).coerceAtLeast(0)].id else s.activeTabId
            Session(remaining, active)
        }
    }

    fun goHome() = updateTab(session.value.activeTabId) {
        it.copy(url = "", title = "", detected = emptyList(), isLoading = false, pendingLoad = null)
    }

    fun consumePendingLoad(tabId: String) = updateTab(tabId) { it.copy(pendingLoad = null) }

    fun toggleBookmark() {
        val tab = session.value.activeTab
        if (tab.url.isBlank()) return
        viewModelScope.launch {
            if (uiState.value.isBookmarked) {
                bookmarkDao.deleteByUrl(tab.url)
            } else {
                bookmarkDao.insert(BookmarkEntity(url = tab.url, title = tab.displayTitle, createdAt = clock()))
            }
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch { bookmarkDao.deleteByUrl(url) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyDao.clear() }
    }

    fun dismissDetected(tabId: String, mediaId: String) = updateTab(tabId) { tab ->
        tab.copy(detected = tab.detected.filterNot { it.id == mediaId })
    }

    // ---- Events from WebView (see WebViewPool) -------------------------------

    override fun onPageStarted(tabId: String, url: String) {
        if (url == BLANK) return
        updateTab(tabId) {
            // Our own load() already cleared the list; a new page from a link click clears
            // in-page finds but keeps what came from a shared link.
            val kept = if (url == it.url) it.detected else it.detected.filter { d -> d.origin == DetectionOrigin.SHARE_INTENT }
            it.copy(url = url, isLoading = true, progress = 5, detected = kept)
        }
    }

    override fun onPageFinished(tabId: String, url: String, title: String) {
        if (url == BLANK) return
        updateTab(tabId) { it.copy(url = url, isLoading = false, progress = 100, title = title.ifBlank { it.title }) }
        recordHistory(url, title)
        resolvePageIfSupported(tabId, url, title)
    }

    override fun onUrlChanged(tabId: String, url: String) {
        if (url == BLANK) return
        updateTab(tabId) { it.copy(url = url) }
        resolvePageIfSupported(tabId, url, null)
    }

    override fun onTitleChanged(tabId: String, title: String) = updateTab(tabId) { it.copy(title = title) }

    override fun onProgress(tabId: String, progress: Int) = updateTab(tabId) {
        it.copy(progress = progress, isLoading = progress < 100)
    }

    override fun onNavigationState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) =
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }

    /** Called on the WebView's network thread for every sub-resource. Must stay cheap. */
    override fun onResourceRequest(tabId: String, url: String, mimeHint: String?) {
        if (!settings.value.detectionEnabled) return
        if (!detectionEngine.isCandidate(url, mimeHint)) return
        val tab = session.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.detected.any { it.mediaUrl == url }) return
        viewModelScope.launch {
            val result = detectionEngine.resolve(
                ResolveRequest(
                    url = url,
                    origin = DetectionOrigin.IN_APP_BROWSER,
                    pageUrl = tab.url.ifBlank { null },
                    pageTitle = tab.title,
                    mimeType = mimeHint,
                ),
            )
            if (result is ResolveResult.Found) addDetected(tabId, result.media)
        }
    }

    override fun onPageMedia(tabId: String, urls: List<String>) {
        urls.forEach { onResourceRequest(tabId, it, null) }
    }

    // ---- Internals ------------------------------------------------------------

    private fun load(tabId: String, url: String) = updateTab(tabId) {
        it.copy(url = url, pendingLoad = url, detected = emptyList(), isLoading = true, progress = 5, resolvedPageUrl = null)
    }

    /** Lets a dedicated site adapter describe the page itself (title, all qualities). */
    private fun resolvePageIfSupported(tabId: String, url: String, title: String?) {
        if (!settings.value.detectionEnabled || !detectionEngine.hasPageAdapter(url)) return
        val tab = session.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.resolvedPageUrl == url) return
        updateTab(tabId) { it.copy(resolvedPageUrl = url) }
        viewModelScope.launch {
            val result = detectionEngine.resolvePage(
                ResolveRequest(url = url, origin = DetectionOrigin.IN_APP_BROWSER, pageTitle = title),
            )
            if (result is ResolveResult.Found) addDetected(tabId, result.media)
        }
    }

    /** Adds new finds; when the same file is found twice, keeps the richer description. */
    private fun addDetected(tabId: String, media: List<DetectedMedia>) = updateTab(tabId) { tab ->
        var list = tab.detected
        for (item in media) {
            val index = list.indexOfFirst { it.mediaUrl == item.mediaUrl || it.id == item.id }
            list = when {
                index < 0 -> listOf(item) + list
                item.variants.size > list[index].variants.size -> list.toMutableList().also { it[index] = item }
                else -> list
            }
        }
        if (list === tab.detected) tab else tab.copy(detected = list.take(MAX_DETECTED_PER_TAB))
    }

    private fun recordHistory(url: String, title: String) {
        if (!UrlUtils.isHttp(url)) return
        viewModelScope.launch {
            val now = clock()
            val last = historyDao.latestFor(url)
            if (last != null && now - last.visitedAt < HISTORY_MERGE_WINDOW_MS) {
                historyDao.touch(last.id, title.ifBlank { last.title }, now)
            } else {
                historyDao.insert(HistoryEntity(url = url, title = title.ifBlank { url }, visitedAt = now))
            }
        }
    }

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) = session.update { s ->
        s.copy(tabs = s.tabs.map { if (it.id == tabId) transform(it) else it })
    }

    private fun newTab(pendingLoad: String? = null) = BrowserTab(
        id = UUID.randomUUID().toString(),
        url = pendingLoad.orEmpty(),
        pendingLoad = pendingLoad,
    )

    companion object {
        private const val BLANK = "about:blank"
        private const val HISTORY_LIMIT = 300
        private const val MAX_DETECTED_PER_TAB = 50
        private const val HISTORY_MERGE_WINDOW_MS = 30 * 60 * 1000L

        fun factory(
            bookmarkDao: BookmarkDao,
            historyDao: HistoryDao,
            settingsRepository: SettingsRepository,
            detectionEngine: MediaDetectionEngine,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(bookmarkDao, historyDao, settingsRepository, detectionEngine) }
        }
    }
}
