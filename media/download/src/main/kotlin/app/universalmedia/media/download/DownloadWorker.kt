package app.universalmedia.media.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.universalmedia.core.database.DownloadEntity
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.model.DownloadStatus
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.formatBytes
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Runs one download job. Created by WorkManager; dependencies come from [DownloadEnvironmentProvider]. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val env: DownloadEnvironment
        get() = (applicationContext as DownloadEnvironmentProvider).downloadEnvironment

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val dao = env.dao
        if (!isRunnable(dao.get(id))) return Result.success()

        DownloadGate.acquire { env.settings.settings.first().maxConcurrentDownloads }
        try {
            val job = dao.get(id)
            if (!isRunnable(job) || job == null) return Result.success()
            dao.setStatus(id, DownloadStatus.RUNNING.name, null, now())
            showProgress(job.title, job.downloadedBytes, job.totalBytes, null)
            run(job)
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped by the system (constraints lost, quota): WorkManager reschedules us, so go back to Queued.
            // User actions set their own status before cancelling, which this leaves untouched.
            withContext(NonCancellable) {
                if (dao.get(id)?.status == DownloadStatus.RUNNING.name) {
                    dao.setStatus(id, DownloadStatus.QUEUED.name, null, now())
                }
            }
            throw e
        } catch (e: DownloadException) {
            return fail(id, e.message ?: "Download failed", e.retryable)
        } catch (e: IOException) {
            return fail(id, "Network problem: ${e.message ?: "connection lost"}", retryable = true)
        } catch (e: SecurityException) {
            return fail(id, "Storage permission was denied.", retryable = false)
        } finally {
            withContext(NonCancellable) { DownloadGate.release() }
        }
    }

    private suspend fun run(job: DownloadEntity) {
        val ctx = applicationContext
        val http = Http(env, job.pageUrl)
        val format = MediaFormat.entries.firstOrNull { it.name == job.format } ?: MediaFormat.MP4
        val url = job.variantUrl ?: job.mediaUrl
        val dir = MediaDownloadManager.partDir(ctx)

        var lastSave = 0L
        val onProgress: suspend (Long, Long?, String?) -> Unit = { done, total, state ->
            val t = now()
            if (state != null || t - lastSave > 1_000) {
                lastSave = t
                env.dao.saveResume(job.id, state, done, total, t)
                showProgress(job.title, done, total, null)
            }
        }

        val finished: File
        val mime: String
        val extension: String
        if (format == MediaFormat.HLS) {
            val part = File(dir, "${job.id}.hls")
            val out = HlsDownloader(http).download(url, part, job.resumeState) { d, t, s -> onProgress(d, t, s) }
            if (out.fragmentedMp4) {
                finished = out.file
                mime = if (job.audioOnly) "audio/mp4" else "video/mp4"
                extension = if (job.audioOnly) "m4a" else "mp4"
            } else {
                showProgress(job.title, part.length(), part.length(), "Finishing…")
                val mp4 = File(dir, "${job.id}.mp4")
                if (remuxToMp4(ctx, part, mp4) && mp4.length() > 0) {
                    part.delete()
                    finished = mp4
                    mime = "video/mp4"
                    extension = "mp4"
                } else {
                    mp4.delete()
                    finished = part
                    mime = "video/mp2t"
                    extension = "ts"
                }
            }
        } else {
            val part = File(dir, "${job.id}.part")
            val contentType = ProgressiveDownloader(http).download(url, part) { d, t -> onProgress(d, t, null) }
            finished = part
            mime = contentType?.substringBefore(';')?.trim()?.lowercase()
                ?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                ?: format.mimeTypes.firstOrNull() ?: "application/octet-stream"
            extension = format.extensions.firstOrNull() ?: "bin"
        }

        val isAudio = mime.startsWith("audio/") || job.audioOnly
        val fileName = job.fileName.substringBeforeLast('.') + "." + extension
        val size = finished.length()
        val storage = DownloadStorage(ctx)
        val uri = storage.publish(finished, fileName, mime, isAudio, env.settings.settings.first().downloadTreeUri)
        finished.delete()

        val now = now()
        env.dao.markCompleted(job.id, uri.toString(), fileName, mime, size, now)
        env.mediaItemDao.insertAll(
            listOf(
                MediaItemEntity(
                    uri = uri.toString(),
                    title = job.title,
                    mimeType = mime,
                    sizeBytes = size,
                    durationMs = storage.readDurationMs(uri),
                    addedAt = now,
                    downloadId = job.id,
                ),
            ),
        )
        notifyFinished(job.id, job.title, "Saved to your library · ${formatBytes(size)}")
    }

    private suspend fun fail(id: String, message: String, retryable: Boolean): Result {
        if (retryable && runAttemptCount < MAX_ATTEMPTS) {
            env.dao.setStatus(id, DownloadStatus.QUEUED.name, message, now())
            return Result.retry()
        }
        env.dao.setStatus(id, DownloadStatus.FAILED.name, message, now())
        env.dao.get(id)?.let { notifyFinished(id, it.title, "Download failed: $message") }
        return Result.failure()
    }

    private fun isRunnable(job: DownloadEntity?): Boolean =
        job != null && (job.status == DownloadStatus.QUEUED.name || job.status == DownloadStatus.RUNNING.name)

    // ---- Notifications ----------------------------------------------------------

    private suspend fun showProgress(title: String, done: Long, total: Long?, text: String?) {
        ensureChannel()
        val percent = if (total != null && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        val body = text ?: if (total != null && total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else formatBytes(done)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
        val info = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId(id.toString()), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(id.toString()), notification)
        }
        // May be refused while the app is in the background on Android 12+; the download continues regardless.
        runCatching { setForeground(info) }
    }

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled + SecurityException.
    private fun notifyFinished(jobId: String, title: String, text: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            manager.notify(notificationId(jobId) + 1, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted.
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(applicationContext, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Download progress and results"
                },
            )
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_ID = "download_id"
        private const val CHANNEL_ID = "downloads"
        private const val MAX_ATTEMPTS = 5

        private fun notificationId(key: String) = 10_000 + (key.hashCode() and 0x0FFFFFFF)
    }
}
