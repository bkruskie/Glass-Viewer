package app.universalmedia.quicksave

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.universalmedia.MainActivity
import app.universalmedia.R
import app.universalmedia.UniversalMediaApp
import app.universalmedia.core.model.formatBytes
import app.universalmedia.feature.browser.SharedTextParser
import app.universalmedia.media.detection.SharedLinkAnalyzer
import app.universalmedia.media.download.MediaDownloadManager
import kotlinx.coroutines.launch

/**
 * "Save with Universal Media" in the share sheet: checks the link in the background
 * and answers with a notification (Save / Open), so you never leave the app you're in.
 * When notifications are off, it opens the app instead.
 */
class QuickSaveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val url = SharedTextParser.firstUrl(text)
        when {
            url == null -> android.widget.Toast.makeText(this, "No link found to save", android.widget.Toast.LENGTH_SHORT).show()
            !QuickSave.canNotify(this) -> startActivity(QuickSave.openInApp(this, url))
            else -> {
                android.widget.Toast.makeText(this, "Looking for the video…", android.widget.Toast.LENGTH_SHORT).show()
                QuickSave.analyze(applicationContext, url, intent?.getStringExtra(Intent.EXTRA_SUBJECT))
            }
        }
        finish()
    }
}

/** The notification's Save button. */
class QuickSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(QuickSave.EXTRA_URL) ?: return
        val pending = goAsync()
        QuickSave.save(context.applicationContext, url) { pending.finish() }
    }
}

internal object QuickSave {
    const val EXTRA_URL = "app.universalmedia.quicksave.URL"
    private const val CHANNEL = "quick_save"

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun openInApp(context: Context, url: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun container(context: Context) = (context.applicationContext as UniversalMediaApp).container

    private fun analyzer(context: Context): SharedLinkAnalyzer {
        val c = container(context)
        return SharedLinkAnalyzer(c.detectionEngine, c.manifestFetcher)
    }

    fun analyze(context: Context, url: String, subject: String?) {
        val id = url.hashCode()
        notify(context, id, base(context, url).setContentTitle("Looking for the video…").setContentText(host(url)).setOngoing(true).setProgress(0, 0, true))
        container(context).appScope.launch {
            when (val outcome = runCatching { analyzer(context).analyze(url) }.getOrElse { SharedLinkAnalyzer.Outcome.NeedsBrowser }) {
                is SharedLinkAnalyzer.Outcome.Found -> {
                    val media = outcome.media.first()
                    val best = media.bestVariant
                    val title = outcome.pageTitle ?: media.title ?: subject ?: host(url)
                    val details = listOfNotNull(
                        best?.label?.substringBefore(" · ")?.takeIf { it.endsWith("p") },
                        media.format.name.replace('_', ' '),
                        best?.estimatedBytes?.let { formatBytes(it) },
                        if (outcome.media.size > 1) "${outcome.media.size} found" else null,
                    ).joinToString(" · ")
                    val save = PendingIntent.getBroadcast(
                        context, id,
                        Intent(context, QuickSaveReceiver::class.java).putExtra(EXTRA_URL, url),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    notify(
                        context, id,
                        base(context, url)
                            .setContentTitle(title)
                            .setContentText("Ready to save · $details")
                            .addAction(0, "Save", save)
                            .addAction(0, "Choose quality", openPending(context, url)),
                    )
                }
                is SharedLinkAnalyzer.Outcome.Blocked -> notify(
                    context, id,
                    base(context, url).setContentTitle("Can't save this video").setContentText(outcome.reason)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(outcome.reason)),
                )
                SharedLinkAnalyzer.Outcome.NeedsBrowser -> notify(
                    context, id,
                    base(context, url).setContentTitle("Open to find the video").setContentText("${host(url)} shows its video only in a browser. Tap to look on the page.")
                        .setStyle(NotificationCompat.BigTextStyle().bigText("${host(url)} shows its video only in a browser. Tap to open the page in Universal Media; the media button shows what can be saved.")),
                )
            }
        }
    }

    fun save(context: Context, url: String, done: () -> Unit) {
        val id = url.hashCode()
        notify(context, id, base(context, url).setContentTitle("Starting download…").setOngoing(true).setProgress(0, 0, true))
        container(context).appScope.launch {
            try {
                val outcome = runCatching { analyzer(context).analyze(url) }.getOrNull()
                val media = (outcome as? SharedLinkAnalyzer.Outcome.Found)?.media?.firstOrNull()
                val result = media?.let { container(context).downloads.enqueue(it, it.bestVariant) }
                when (result) {
                    is MediaDownloadManager.EnqueueResult.Queued -> NotificationManagerCompat.from(context).cancel(id) // the download shows its own progress
                    MediaDownloadManager.EnqueueResult.Duplicate -> notify(context, id, base(context, url).setContentTitle("Already saved").setContentText("This video is already in your downloads."))
                    is MediaDownloadManager.EnqueueResult.NotSupported -> notify(context, id, base(context, url).setContentTitle("Can't save this video").setContentText(result.reason))
                    null -> notify(context, id, base(context, url).setContentTitle("Open to find the video").setContentText("Tap to look on the page in Universal Media."))
                }
            } finally {
                done()
            }
        }
    }

    private fun openPending(context: Context, url: String): PendingIntent =
        PendingIntent.getActivity(context, url.hashCode(), openInApp(context, url), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun base(context: Context, url: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentIntent(openPending(context, url))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canNotify before any analysis
    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Shared links", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when a link you shared has a video ready to save"
                },
            )
        }
    }

    private fun host(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
