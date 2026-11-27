package app.universalmedia.media.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Uses Google's free Default Media Receiver, so no receiver registration (and no fee) is needed. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast / Google TV support through the Cast SDK. Everything runs on the
 * main thread. If Google Play services is missing, this simply finds nothing.
 */
internal class GoogleCastClient(private val context: Context) {

    private var castContext: CastContext? = null
    private val router: MediaRouter? = runCatching { MediaRouter.getInstance(context) }.getOrNull()
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private val _routes = MutableStateFlow<List<MediaRouter.RouteInfo>>(emptyList())
    val routes: StateFlow<List<MediaRouter.RouteInfo>> = _routes.asStateFlow()

    private var pending: (() -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null
    var onSessionFailed: ((String) -> Unit)? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = runPending()
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = runPending()
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pending = null
            onSessionFailed?.invoke("Couldn't connect to the Chromecast (error $error).")
        }
        override fun onSessionEnded(session: CastSession, error: Int) { onSessionEnded?.invoke() }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun init() {
        if (castContext != null) return
        runCatching {
            CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                }
        }
    }

    fun startDiscovery() {
        init()
        router?.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    fun stopDiscovery() {
        router?.removeCallback(routerCallback)
    }

    private fun refresh() {
        val r = router ?: return
        _routes.value = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
    }

    private fun runPending() {
        val action = pending
        pending = null
        action?.invoke()
    }

    private val session: CastSession? get() = castContext?.sessionManager?.currentCastSession

    /** Connects to [routeId] (starting a session if needed) and plays [url]. */
    fun load(routeId: String, url: String, title: String, mimeType: String) {
        val r = router ?: throw CastException("Chromecast isn't available on this phone.")
        if (castContext == null) throw CastException("Google Cast isn't available. Is Google Play services installed?")
        val route = r.routes.firstOrNull { it.id == routeId } ?: throw CastException("That device is no longer available.")
        val start = {
            val media = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(
                    MediaMetadata(if (mimeType.startsWith("audio")) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE)
                        .apply { putString(MediaMetadata.KEY_TITLE, title) },
                )
                .build()
            session?.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build())
            Unit
        }
        if (session?.isConnected == true && r.selectedRoute.id == routeId) {
            start()
        } else {
            pending = start
            r.selectRoute(route)
        }
    }

    fun play() { session?.remoteMediaClient?.play() }
    fun pause() { session?.remoteMediaClient?.pause() }
    fun seek(ms: Long) { session?.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(ms).build()) }

    fun changeVolume(delta: Double) {
        val s = session ?: return
        runCatching { s.volume = (s.volume + delta).coerceIn(0.0, 1.0) }
    }

    fun stop() {
        session?.remoteMediaClient?.stop()
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun status(): RemoteStatus? {
        val client = session?.remoteMediaClient ?: return null
        return RemoteStatus(
            positionMs = client.approximateStreamPosition,
            durationMs = client.streamDuration.coerceAtLeast(0),
            playing = client.isPlaying,
            ended = client.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED,
        )
    }
}
