package app.universalmedia.media.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.model.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What to play. Web sources carry the page as Referer so sites serve them like in the browser. */
data class PlayRequest(
    val uri: String,
    val title: String,
    val mimeType: String? = null,
    val referer: String? = null,
    val libraryItemId: Long? = null,
    val startPositionMs: Long = 0,
) {
    companion object {
        /** Media3 MIME hint for stream manifests; null lets the player sniff files. */
        fun mimeFor(format: MediaFormat): String? = when (format) {
            MediaFormat.HLS -> MimeTypes.APPLICATION_M3U8
            MediaFormat.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }
}

/**
 * App-wide player (Media3 ExoPlayer). Lives for the process so playback
 * continues while moving between sections, and in picture-in-picture.
 * Must be used from the main thread.
 *
 * @param httpClient should add the in-app browser's cookies, so signed-in
 *   media plays with the user's own session.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val userAgent: () -> String?,
    private val mediaItemDao: MediaItemDao,
    private val scope: CoroutineScope,
) {
    private var exo: ExoPlayer? = null
    private val _current = MutableStateFlow<PlayRequest?>(null)

    /** The item loaded in the player, or null when nothing is. */
    val current: StateFlow<PlayRequest?> = _current.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** A user-facing explanation when playback fails or can't start; null when all is well. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Set by the Player screen; picture-in-picture only starts from there. */
    @Volatile
    var isPlayerScreenVisible: Boolean = false

    val player: ExoPlayer
        get() = exo ?: create().also { exo = it }

    fun play(request: PlayRequest) {
        savePosition()
        _problem.value = null
        val http = OkHttpDataSource.Factory(httpClient).apply {
            userAgent()?.let { setUserAgent(it) }
            request.referer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }
        val item = MediaItem.Builder()
            .setUri(Uri.parse(request.uri))
            .setMimeType(request.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
        player.apply {
            setMediaSource(source, request.startPositionMs)
            prepare()
            playWhenReady = true
        }
        _current.value = request
        request.libraryItemId?.let { id ->
            scope.launch { mediaItemDao.updatePlayback(id, request.startPositionMs, System.currentTimeMillis()) }
        }
    }

    /** Remembers where the user is in a library item (restarts from 0 when nearly finished). */
    fun savePosition() {
        val request = _current.value ?: return
        val id = request.libraryItemId ?: return
        val p = exo ?: return
        val position = p.currentPosition
        val duration = p.duration
        val stored = if (duration > 0 && position > duration - 5_000) 0L else position
        scope.launch { mediaItemDao.updatePlayback(id, stored, System.currentTimeMillis()) }
    }

    /** Tries the current item again from where it stopped. */
    fun retry() {
        val request = _current.value ?: return
        val position = exo?.currentPosition ?: request.startPositionMs
        play(request.copy(startPositionMs = position))
    }

    fun stop() {
        _problem.value = null
        savePosition()
        exo?.stop()
        exo?.clearMediaItems()
        _current.value = null
    }

    fun isPlayingVideo(): Boolean = exo?.let { it.isPlaying && it.videoSize.width > 0 } ?: false

    fun release() {
        savePosition()
        exo?.release()
        exo = null
        _current.value = null
    }

    private fun create(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) savePosition()
                    if (isPlaying) _problem.value = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.cause?.message?.take(160)
                    _problem.value = "Couldn't play this (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})" +
                        (detail?.let { ": $it" } ?: ".")
                }

                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    _problem.value = if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                        "Paused because another app is using audio (a call, another player or the browser). Tap play again."
                    } else {
                        null
                    }
                }
            })
        }
}
