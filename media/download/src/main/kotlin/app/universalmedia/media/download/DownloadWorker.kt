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
import app.universalmedia.media.detection.DashSegmentPlan
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        } catch (e: Exception) {
            // Anything unexpected fails this download only; it must never take the app down.
            return fail(id, "Unexpected error: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}", retryable = false)
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

        // Resume state is "phase|inner": phase = which track of a multi-track download.
        val resumePhase = job.resumeState?.substringBefore('|', "")?.toIntOrNull() ?: 0
        val resumeInner = job.resumeState?.let { if ('|' in it) it.substringAfter('|') else it }
        var lastSave = 0L
        var bytesBeforePhase = 0L
        fun progressFor(phase: Int): suspend (Long, Long?, String?) -> Unit = { done, total, state ->
            val t = now()
            if (state != null || t - lastSave > 1_000) {
                lastSave = t
                val all = bytesBeforePhase + done
                val estimate = total?.let { bytesBeforePhase + it }
                env.dao.saveResume(job.id, state?.let { "$phase|$it" }, all, estimate, t)
                showProgress(job.title, all, estimate, null)
            }
        }
        /** Runs each track download in order, skipping tracks finished before a resume. */
        suspend fun phases(files: List<File>, block: suspend (index: Int, file: File, state: String?) -> Unit) {
            for ((i, file) in files.withIndex()) {
                if (i < resumePhase) {
                    bytesBeforePhase += file.length()
                    continue
                }
                block(i, file, if (i == resumePhase) resumeInner else null)
                bytesBeforePhase += file.length()
            }
        }

        val finished: File
        val mime: String
        val extension: String
        when (format) {
            MediaFormat.HLS -> {
                val (videoUrl, audioUrl) = if (job.audioOnly) url to null else resolveHlsTracks(http, url, job.mediaUrl, job.targetHeight, job.targetBitrate)
                if (audioUrl == null) {
                    val part = File(dir, "${job.id}.hls")
                    val out = HlsDownloader(http).download(videoUrl, part, resumeInner, progressFor(0))
                    if (out.fragmentedMp4) {
                        finished = out.file
                        mime = if (job.audioOnly) "audio/mp4" else "video/mp4"
                        extension = if (job.audioOnly) "m4a" else "mp4"
                    } else {
                        // Repackage MPEG-TS as MP4 (no re-encoding); keep the .ts if this phone can't.
                        showProgress(job.title, part.length(), part.length(), "Finishing…")
                        val mp4 = withContext(Dispatchers.IO) {
                            runCatching { TrackMuxer.mux(listOf(part), dir, "${job.id}.out") }.getOrNull()
                        }
                        if (mp4 != null && mp4.file.length() > 0) {
                            part.delete()
                            finished = mp4.file
                            mime = mp4.mimeType
                            extension = mp4.extension
                        } else {
                            File(dir, "${job.id}.out.mp4").delete()
                            finished = part
                            mime = "video/mp2t"
                            extension = "ts"
                        }
                    }
                } else {
                    // Video and audio come as separate playlists: fetch both, then combine.
                    val files = listOf(File(dir, "${job.id}.v.hls"), File(dir, "${job.id}.a.hls"))
                    val urls = listOf(videoUrl, audioUrl)
                    phases(files) { i, file, state -> HlsDownloader(http).download(urls[i], file, state, progressFor(i)) }
                    showProgress(job.title, bytesBeforePhase, bytesBeforePhase, "Finishing…")
                    val out = withContext(Dispatchers.IO) { TrackMuxer.mux(files, dir, "${job.id}.out") }
                    files.forEach { it.delete() }
                    finished = out.file
                    mime = out.mimeType
                    extension = out.extension
                }
            }
            MediaFormat.DASH -> {
                val (text, finalUrl) = retrying { http.text(url) }
                val plan = DashSegmentPlan.parse(text, finalUrl) ?: throw DownloadException("Unreadable DASH manifest.")
                if (plan.isProtected) throw DownloadException("This stream is protected by DRM and can't be saved.")
                if (plan.isLive) throw DownloadException("Live streams can't be saved yet.")
                val tracks = plan.select(job.targetHeight, job.targetBitrate, job.audioOnly)
                if (tracks.isEmpty()) throw DownloadException("The stream lists no downloadable tracks.")
                val files = tracks.indices.map { File(dir, "${job.id}.d$it") }
                phases(files) { i, file, state ->
                    val track = tracks[i]
                    val single = track.segments.singleOrNull()
                    if (track.initSegment == null && single != null && single.byteRange == null) {
                        ProgressiveDownloader(http).download(single.url, file) { d, t -> progressFor(i)(d, t, null) }
                    } else {
                        SegmentDownloader(http).download(
                            init = track.initSegment?.let { Piece(it.url, it.byteRange?.rangeHeader) },
                            pieces = track.segments.map { Piece(it.url, it.byteRange?.rangeHeader) },
                            part = file,
                            resumeState = state,
                            onProgress = progressFor(i),
                        )
                    }
                }
                showProgress(job.title, bytesBeforePhase, bytesBeforePhase, "Finishing…")
                val out = withContext(Dispatchers.IO) { TrackMuxer.mux(files, dir, "${job.id}.out") }
                files.forEach { it.delete() }
                finished = out.file
                mime = out.mimeType
                extension = out.extension
            }
            else -> {
                val part = File(dir, "${job.id}.part")
                val contentType = ProgressiveDownloader(http).download(url, part) { d, t -> progressFor(0)(d, t, null) }
                finished = part
                mime = contentType?.substringBefore(';')?.trim()?.lowercase()
                    ?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                    ?: format.mimeTypes.firstOrNull() ?: "application/octet-stream"
                extension = format.extensions.firstOrNull() ?: "bin"
            }
        }

        val silent = !mime.startsWith("audio/") && !job.audioOnly && TrackMuxer.hasNoAudio(finished)
        val isAudio = mime.startsWith("audio/") || job.audioOnly
        val fileName = job.fileName.substringBeforeLast('.') + "." + extension
        val size = finished.length()
        val storage = DownloadStorage(ctx)
        val uri = storage.publish(finished, fileName, mime, isAudio, env.settings.settings.first().downloadTreeUri)
        finished.delete()

        val now = now()
        val thumbnail = runCatching { Thumbnails.create(ctx, uri, job.id, isAudio) }.getOrNull()
        env.dao.markCompleted(job.id, uri.toString(), fileName, mime, size, now)
        env.dao.setThumbnail(job.id, thumbnail)
        if (silent) {
            env.dao.setStatus(
                job.id, DownloadStatus.COMPLETED.name,
                "Saved without sound: this site sends sound separately and it couldn't be found.", now,
            )
        }
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
                    thumbnailPath = thumbnail,
                ),
            ),
        )
        notifyFinished(job.id, job.title, "Saved · tap to play · ${formatBytes(size)}", openWithIntent(uri, mime, job.title))
    }

    /** Tapping the "saved" notification lets the user pick a player for the file. */
    private fun openWithIntent(uri: android.net.Uri, mime: String, title: String): PendingIntent {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = android.content.Intent.createChooser(view, title)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return PendingIntent.getActivity(
            applicationContext,
            uri.hashCode(),
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
    private fun notifyFinished(jobId: String, title: String, text: String, tap: PendingIntent? = null) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap ?: openAppIntent())
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
