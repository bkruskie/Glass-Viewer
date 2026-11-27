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
import app.universalmedia.media.playback.PlaybackManager
import app.universalmedia.media.playback.PlaybackHost
import app.universalmedia.media.cast.CastManager
import app.universalmedia.media.mirror.ReceiverDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    /** Player HTTP: adds the in-app browser's cookies so signed-in videos play. */
    private val playerHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val cookie = browserCookies(request.url.toString())
                chain.proceed(
                    if (cookie.isNullOrBlank() || request.header("Cookie") != null) request
                    else request.newBuilder().header("Cookie", cookie).build(),
                )
            }
            .build()
    }

    /** TV casting (Google Cast, DLNA, TV browser); create and use on the main thread. */
    val cast: CastManager by lazy { CastManager(application, httpClient, appScope) }

    /** Wi-Fi transfer: other devices open a web address to swap files with the library. */
    val transfer: app.universalmedia.media.transfer.TransferServer by lazy {
        val dao = database.mediaItemDao()
        app.universalmedia.media.transfer.TransferServer(
            context = application,
            scope = appScope,
            files = {
                dao.observeAll().first().map { item ->
                    val ext = item.mimeType?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    val name = if (ext != null && !item.title.endsWith(".$ext", ignoreCase = true)) "${item.title}.$ext" else item.title
                    app.universalmedia.media.transfer.SharedFile(item.id.toString(), name, item.uri, item.mimeType, item.sizeBytes)
                }
            },
            onReceived = { file ->
                if (file.mimeType.startsWith("video") || file.mimeType.startsWith("audio")) {
                    dao.insertAll(
                        listOf(
                            app.universalmedia.core.database.MediaItemEntity(
                                uri = file.uri.toString(),
                                title = file.name.substringBeforeLast('.'),
                                mimeType = file.mimeType,
                                sizeBytes = file.sizeBytes,
                                durationMs = null,
                                addedAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
            },
        )
    }

    /** Finds TV receiver apps for screen mirroring. */
    val mirrorDiscovery: ReceiverDiscovery by lazy { ReceiverDiscovery(application) }

    /** App-wide player; create and use on the main thread. */
    val playback: PlaybackManager by lazy {
        PlaybackManager(application, playerHttpClient, { browserUserAgent }, database.mediaItemDao(), appScope)
    }

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

class UniversalMediaApp : Application(), DownloadEnvironmentProvider, PlaybackHost {
    lateinit var container: AppContainer
        private set

    override val downloadEnvironment: DownloadEnvironment
        get() = container.downloadEnvironment

    override val playback: PlaybackManager
        get() = container.playback

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        container.appScope.launch { runCatching { container.downloads.restoreQueue() } }
    }
}
