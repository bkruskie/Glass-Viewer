package app.universalmedia.feature.browser.otherapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Exists only so the user can grant "Notification access", which is Android's
 * permission for reading other apps' media sessions. It reads no notifications.
 */
class MediaWatchService : NotificationListenerService()

/** Something another app is playing, as that app describes it to Android. */
data class PlayingElsewhere(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    /** A web link to the media, when the app shares one (some players and browsers do). */
    val webUrl: String?,
    val playing: Boolean,
)

/**
 * "Playing in other apps": uses Android's MediaSession list, the approved way to
 * see what other apps are playing. Android gives the title and app, and sometimes
 * a link; never the video file or its network traffic. So for most apps the best
 * the app can do is search for the title.
 */
object OtherAppsMedia {

    fun isAllowed(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Opens the system screen where the user turns access on or off. */
    fun openSettings(context: Context) {
        val component = ComponentName(context, MediaWatchService::class.java)
        val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.recoverCatching { context.startActivity(list) }
    }

    fun observe(context: Context): Flow<List<PlayingElsewhere>> = callbackFlow {
        val app = context.applicationContext
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(app, MediaWatchService::class.java)
        if (manager == null || !isAllowed(app)) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

        fun publish(controllers: List<MediaController>) {
            trySend(controllers.filter { it.packageName != app.packageName }.mapNotNull { describe(app, it) })
        }

        fun watch(controllers: List<MediaController>?) {
            callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            callbacks.clear()
            val list = controllers.orEmpty()
            list.forEach { c ->
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish(list)
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publish(list)
                    override fun onSessionDestroyed() = publish(list)
                }
                runCatching { c.registerCallback(cb) }
                callbacks[c] = cb
            }
            publish(list)
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it) }
        try {
            manager.addOnActiveSessionsChangedListener(listener, component)
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            trySend(emptyList())
        }
        awaitClose {
            runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
            callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        }
    }

    private fun describe(context: Context, c: MediaController): PlayingElsewhere? {
        val m = c.metadata ?: return null
        val title = (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.trim()?.ifEmpty { null } ?: return null
        val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            ?.trim()?.ifEmpty { null }
        val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        val link = listOfNotNull(
            m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        ).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        val appName = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        val state = c.playbackState?.state
        return PlayingElsewhere(
            packageName = c.packageName,
            appName = appName,
            title = title,
            artist = artist,
            durationMs = duration,
            webUrl = link,
            playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
        )
    }
}
