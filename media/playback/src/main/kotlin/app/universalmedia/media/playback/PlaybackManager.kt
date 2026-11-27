package app.universalmedia.media.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What to play. Web sources carry the page as Referer so sites serve them like in the browser. */
data class SubtitleFile(val uri: String, val mimeType: String, val label: String)

/** Media3 MIME type for a subtitle file name, or null when it isn't one. */
fun subtitleMime(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else -> null
}

data class PlayRequest(
    val uri: String,
    val title: String,
    val mimeType: String? = null,
    val referer: String? = null,
    val libraryItemId: Long? = null,
    val startPositionMs: Long = 0,
    /** Subtitle files added by the user (shown on top of any built into the video). */
    val subtitles: List<SubtitleFile> = emptyList(),
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

/** A list played in order (a playlist, or "play all"); [index] is the item playing now. */
data class PlayQueue(val name: String, val items: List<PlayRequest>, val index: Int) {
    val hasNext: Boolean get() = index < items.lastIndex
    val hasPrevious: Boolean get() = index > 0
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
    private var watchdog: Job? = null
    private var sessionConnection: ListenableFuture<MediaController>? = null

    /** Connecting to PlaybackService starts it, so playback keeps going in the background with controls. */
    private fun connectSession() {
        if (sessionConnection != null) return
        sessionConnection = runCatching {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.getOrNull()
    }
    private val _current = MutableStateFlow<PlayRequest?>(null)

    /** The item loaded in the player, or null when nothing is. */
    val current: StateFlow<PlayRequest?> = _current.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** A user-facing explanation when playback fails or can't start; null when all is well. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _speed = MutableStateFlow(1f)

    /** Current playback speed (0.1× super slow motion up to 4×). */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun setSpeed(value: Float) {
        _speed.value = value
        exo?.setPlaybackSpeed(value)
    }

    /** Pauses and moves one video frame forward (+1) or back (-1). */
    fun stepFrame(direction: Int) {
        val p = exo ?: return
        p.pause()
        val fps = p.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val step = (1000f / fps).toLong().coerceAtLeast(1L)
        p.seekTo((p.currentPosition + direction * step).coerceAtLeast(0L))
    }

    /** Set by the Player screen; picture-in-picture only starts from there. */
    @Volatile
    var isPlayerScreenVisible: Boolean = false

    val player: ExoPlayer
        get() = exo ?: create().also { exo = it }

    private val _queue = MutableStateFlow<PlayQueue?>(null)

    /** The list being played in order, or null for a single item. */
    val queue: StateFlow<PlayQueue?> = _queue.asStateFlow()

    /** Plays one item (ends any list). */
    fun play(request: PlayRequest) {
        _queue.value = null
        load(request)
    }

    /** Plays [items] in order, starting at [startIndex]; the next one starts when one ends. */
    fun playQueue(name: String, items: List<PlayRequest>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        _queue.value = PlayQueue(name, items, index)
        load(items[index])
    }

    fun next() {
        val q = _queue.value ?: return
        if (!q.hasNext) return
        jumpTo(q.index + 1)
    }

    /** Back to the start of this item, or to the previous one if near the start. */
    fun previous() {
        val q = _queue.value
        val p = exo
        if (q == null || !q.hasPrevious || (p != null && p.currentPosition > 3_000)) {
            p?.seekTo(0)
            return
        }
        jumpTo(q.index - 1)
    }

    fun jumpTo(index: Int) {
        val q = _queue.value ?: return
        if (index !in q.items.indices) return
        savePosition()
        _queue.value = q.copy(index = index)
        load(q.items[index].copy(startPositionMs = 0))
    }

    private fun load(request: PlayRequest) {
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
            .setSubtitleConfigurations(
                request.subtitles.mapIndexed { i, sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.uri))
                        .setMimeType(sub.mimeType)
                        .setLabel(sub.label)
                        .setId("user-$i")
                        // The newest file the user picked is shown straight away.
                        .setSelectionFlags(if (i == request.subtitles.lastIndex) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                },
            )
            .build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
        _speed.value = 1f
        player.apply {
            setPlaybackSpeed(1f)
            setMediaSource(source, request.startPositionMs)
            prepare()
            playWhenReady = true
        }
        _current.value = request
        startWatchdog()
        connectSession()
        request.libraryItemId?.let { id ->
            scope.launch { mediaItemDao.updatePlayback(id, request.startPositionMs, System.currentTimeMillis()) }
        }
    }

    /**
     * Adds a subtitle file (.srt, .vtt, .ass/.ssa, .ttml) to what's playing and keeps the position.
     * Returns false when the file type isn't a subtitle format.
     */
    fun addSubtitle(uri: String, fileName: String): Boolean {
        val request = _current.value ?: return false
        val mime = subtitleMime(fileName) ?: return false
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition
        val updated = request.copy(
            startPositionMs = position,
            subtitles = request.subtitles + SubtitleFile(uri, mime, fileName.substringBeforeLast('.')),
        )
        _queue.value?.let { q -> _queue.value = q.copy(items = q.items.toMutableList().also { it[q.index] = updated }) }
        load(updated)
        player.playWhenReady = wasPlaying
        return true
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

    /**
     * The player keeps time by the sound. If a file's sound can't be decoded, the clock
     * never moves and the picture freezes. When playback is stuck for a few seconds,
     * turn the sound off so the picture plays, and say so.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        val p = player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        watchdog = scope.launch(Dispatchers.Main) {
            var lastPosition = -1L
            var stuckSeconds = 0
            var audioDisabled = false
            while (isActive && exo === p) {
                delay(1_000)
                val wantsToPlay = p.playWhenReady &&
                    p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
                val position = p.currentPosition
                stuckSeconds = if (wantsToPlay && position == lastPosition) stuckSeconds + 1 else 0
                lastPosition = position
                if (stuckSeconds >= STUCK_SECONDS) {
                    stuckSeconds = 0
                    if (!audioDisabled && p.currentTracks.containsType(C.TRACK_TYPE_AUDIO)) {
                        audioDisabled = true
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                        p.seekTo(position)
                        _problem.value = "This file's sound couldn't be played, so it's playing without sound. " +
                            "If it was downloaded before version 0.7.3, download it again."
                    } else if (p.playbackState == Player.STATE_BUFFERING) {
                        _problem.value = "Still loading… If this doesn't start, the source may be blocking playback here."
                    } else {
                        _problem.value = "Playback is stuck. Try Retry, or open it in another app."
                    }
                }
            }
        }
    }

    /** Tries the current item again from where it stopped. */
    fun retry() {
        val request = _current.value ?: return
        val position = exo?.currentPosition ?: request.startPositionMs
        load(request.copy(startPositionMs = position))
    }

    fun stop() {
        watchdog?.cancel()
        _problem.value = null
        savePosition()
        exo?.stop()
        exo?.clearMediaItems()
        _current.value = null
        _queue.value = null
    }

    fun isPlayingVideo(): Boolean = exo?.let { it.isPlaying && it.videoSize.width > 0 } ?: false

    fun release() {
        sessionConnection?.let { MediaController.releaseFuture(it) }
        sessionConnection = null
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
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && _queue.value?.hasNext == true) next()
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

    private companion object {
        const val STUCK_SECONDS = 4
    }
}
