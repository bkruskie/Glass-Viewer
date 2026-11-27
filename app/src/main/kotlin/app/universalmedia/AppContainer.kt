package app.universalmedia

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import app.universalmedia.core.database.UniversalMediaDatabase
import app.universalmedia.core.datastore.SettingsRepository
import app.universalmedia.core.network.OkHttpManifestFetcher
import app.universalmedia.media.detection.GenericMediaAdapter
import app.universalmedia.media.detection.ManifestFetcher
import app.universalmedia.media.detection.MediaDetectionEngine
import app.universalmedia.media.detection.adapters.InternetArchiveAdapter
import app.universalmedia.media.detection.adapters.PeerTubeAdapter
import app.universalmedia.media.download.DownloadEnvironment
import app.universalmedia.media.download.DownloadEnvironmentProvider
import app.universalmedia.media.download.MediaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Manual dependency container (see ARCHITECTURE.md, ADR-004). Everything is
 * created lazily and lives for the process.
 */
class AppContainer(private val application: Application) {

    val database: UniversalMediaDatabase by lazy { UniversalMediaDatabase.create(application) }

    val settings: SettingsRepository by lazy { SettingsRepository(application) }

    val httpClient: OkHttpClient by lazy { OkHttpManifestFetcher.defaultClient() }

    /** Process-wide scope for work that outlives screens. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val browserUserAgent: String? by lazy {
        runCatching { WebSettings.getDefaultUserAgent(application) }.getOrNull()
    }

    private val browserCookies: (String) -> String? = { url ->
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    /** Requests manifests like the in-app browser would: same UA, cookies and referer. */
    val manifestFetcher: ManifestFetcher by lazy {
        OkHttpManifestFetcher(client = httpClient, userAgent = { browserUserAgent }, cookiesFor = browserCookies)
    }

    val downloadEnvironment: DownloadEnvironment by lazy {
        DownloadEnvironment(
            context = application,
            dao = database.downloadDao(),
            mediaItemDao = database.mediaItemDao(),
            settings = settings,
            client = httpClient,
            userAgent = { browserUserAgent },
            cookiesFor = browserCookies,
        )
    }

    val downloads: MediaDownloadManager by lazy { MediaDownloadManager(downloadEnvironment) }

    /** Site adapters are registered here, most specific first. The generic adapter is always the fallback. */
    val detectionEngine: MediaDetectionEngine by lazy {
        MediaDetectionEngine(
            adapters = listOf(
                InternetArchiveAdapter(manifestFetcher),
                PeerTubeAdapter(manifestFetcher),
            ),
            fallback = GenericMediaAdapter(manifestFetcher),
        )
    }
}

class UniversalMediaApp : Application(), DownloadEnvironmentProvider {
    lateinit var container: AppContainer
        private set

    override val downloadEnvironment: DownloadEnvironment
        get() = container.downloadEnvironment

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { runCatching { container.downloads.restoreQueue() } }
    }
}
