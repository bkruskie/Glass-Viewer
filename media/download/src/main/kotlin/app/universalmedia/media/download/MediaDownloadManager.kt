package app.universalmedia.media.download

import android.content.Context
import app.universalmedia.core.database.DownloadDao
import app.universalmedia.core.database.DownloadEntity
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.datastore.SettingsRepository
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DownloadStatus
import app.universalmedia.core.model.DownloadSupport
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import okhttp3.OkHttpClient

/** Everything the background worker needs. Provided by the Application. */
class DownloadEnvironment(
    val context: Context,
    val dao: DownloadDao,
    val mediaItemDao: MediaItemDao,
    val settings: SettingsRepository,
    val client: OkHttpClient,
    val userAgent: () -> String?,
    val cookiesFor: (String) -> String?,
)

/** Implemented by the Application so WorkManager-created workers can reach dependencies. */
interface DownloadEnvironmentProvider {
    val downloadEnvironment: DownloadEnvironment
}

/**
 * Public API of the download engine: queue, pause, resume, retry, cancel.
 * Jobs persist in Room and run in [DownloadWorker] through WorkManager, so they
 * survive the app being closed and resume after reboots or network loss.
 */
class MediaDownloadManager(
    private val env: DownloadEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface EnqueueResult {
        data class Queued(val id: String) : EnqueueResult
        data object Duplicate : EnqueueResult
        data class NotSupported(val reason: String) : EnqueueResult
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(env.context)

    fun observe(): Flow<List<DownloadEntity>> = env.dao.observeAll()

    suspend fun enqueue(media: DetectedMedia, variant: MediaVariant?, allowDuplicate: Boolean = false): EnqueueResult {
        DownloadSupport.reasonNotSupported(media, variant)?.let { return EnqueueResult.NotSupported(it) }
        // For a quality picked out of a stream, the manifest that lists it identifies the source
        // (and lets the worker find a separate audio track).
        val sourceUrl = variant?.manifestUrl ?: media.mediaUrl
        if (!allowDuplicate && env.dao.countForUrl(sourceUrl) > 0) return EnqueueResult.Duplicate

        val format = variant?.format ?: media.format
        val quality = when {
            variant?.audioOnly == true -> "audio"
            variant?.height != null -> "${variant.height}p"
            else -> null
        }
        val extension = if (format == MediaFormat.HLS) "mp4" else format.extensions.firstOrNull() ?: "bin"
        val title = media.title?.takeIf { it.isNotBlank() } ?: "Video"
        val now = clock()
        val id = UUID.randomUUID().toString()
        env.dao.upsert(
            DownloadEntity(
                id = id,
                mediaUrl = sourceUrl,
                pageUrl = media.pageUrl,
                title = title,
                fileName = FileNames.build(title, quality, extension),
                format = format.name,
                variantLabel = variant?.label,
                destinationUri = null,
                status = DownloadStatus.QUEUED.name,
                totalBytes = variant?.estimatedBytes,
                downloadedBytes = 0,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                variantUrl = variant?.url ?: media.mediaUrl,
                resumeState = null,
                mimeType = null,
                audioOnly = variant?.audioOnly ?: false,
                targetHeight = variant?.height,
                targetBitrate = variant?.bitrate,
            ),
        )
        schedule(id, ExistingWorkPolicy.REPLACE)
        return EnqueueResult.Queued(id)
    }

    suspend fun pause(id: String) {
        env.dao.setStatus(id, DownloadStatus.PAUSED.name, null, clock())
        workManager.cancelUniqueWork(workName(id))
    }

    suspend fun resume(id: String) {
        env.dao.setStatus(id, DownloadStatus.QUEUED.name, null, clock())
        schedule(id, ExistingWorkPolicy.REPLACE)
    }

    suspend fun retry(id: String) = resume(id)

    suspend fun cancel(id: String) {
        env.dao.setStatus(id, DownloadStatus.CANCELLED.name, null, clock())
        workManager.cancelUniqueWork(workName(id))
        deletePartsLater(id)
    }

    /** Removes the entry from the list (the saved file, if any, is kept). */
    suspend fun remove(id: String) {
        workManager.cancelUniqueWork(workName(id))
        env.dao.delete(id)
        deletePartsLater(id)
    }

    suspend fun clearFinished() = env.dao.clearFinished()

    /** Makes sure every queued or running job has scheduled work, e.g. after an app update. */
    suspend fun restoreQueue() {
        env.dao.active().forEach { schedule(it.id, ExistingWorkPolicy.KEEP) }
    }

    private suspend fun schedule(id: String, policy: ExistingWorkPolicy) {
        val wifiOnly = env.settings.settings.first().wifiOnlyDownloads
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    /** Lets a cancelled worker stop writing before its partial files are removed. */
    private suspend fun deletePartsLater(id: String) {
        delay(1_500)
        partFiles(env.context, id).forEach { it.delete() }
    }

    companion object {
        const val WORK_TAG = "universal-media-download"

        fun workName(id: String) = "download-$id"

        internal fun partDir(context: Context): File = File(context.filesDir, "downloads").apply { mkdirs() }

        internal fun partFiles(context: Context, id: String): List<File> =
            partDir(context).listFiles { f -> f.name.startsWith(id) }?.toList().orEmpty()
    }
}

/**
 * Process-wide limit on simultaneous downloads. Workers wait here (still shown
 * as "Queued") until a slot frees up. The limit is re-read on every check, so
 * changing it in Settings applies immediately.
 */
internal object DownloadGate {
    private val mutex = Mutex()
    private var active = 0

    suspend fun acquire(limit: suspend () -> Int) {
        while (true) {
            val max = limit().coerceAtLeast(1)
            val acquired = mutex.withLock {
                if (active < max) {
                    active++
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            delay(500)
        }
    }

    suspend fun release() = mutex.withLock { active = (active - 1).coerceAtLeast(0) }
}
