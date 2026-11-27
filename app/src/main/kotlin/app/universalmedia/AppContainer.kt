package app.universalmedia

import android.app.Application
import app.universalmedia.core.database.UniversalMediaDatabase
import app.universalmedia.core.datastore.SettingsRepository
import app.universalmedia.media.detection.GenericMediaAdapter
import app.universalmedia.media.detection.MediaDetectionEngine

/**
 * Manual dependency container (see ARCHITECTURE.md, ADR-004). Everything is
 * created lazily and lives for the process. Swapping an implementation (e.g. a
 * real ManifestFetcher in Milestone 2) happens here only.
 */
class AppContainer(private val application: Application) {

    val database: UniversalMediaDatabase by lazy { UniversalMediaDatabase.create(application) }

    val settings: SettingsRepository by lazy { SettingsRepository(application) }

    /** Site adapters are registered here. The generic adapter is always the fallback. */
    val detectionEngine: MediaDetectionEngine by lazy {
        MediaDetectionEngine(
            adapters = emptyList(),
            fallback = GenericMediaAdapter(fetcher = null),
        )
    }
}

class UniversalMediaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
