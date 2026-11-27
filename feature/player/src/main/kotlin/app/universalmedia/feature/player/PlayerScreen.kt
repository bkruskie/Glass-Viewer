package app.universalmedia.feature.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.model.formatDuration
import app.universalmedia.core.ui.components.EmptyState
import app.universalmedia.core.ui.components.MediaThumbnail
import androidx.compose.foundation.layout.size
import app.universalmedia.core.ui.components.SectionHeader
import app.universalmedia.media.playback.PlayRequest
import app.universalmedia.media.playback.PlaybackManager
import kotlinx.coroutines.delay

/**
 * Built-in player: every format Media3 supports (MP4, WebM, MKV, HLS, DASH, audio),
 * with the controller's speed, subtitle and audio-track menus, fullscreen and
 * picture-in-picture. Recently played items resume where they stopped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playback: PlaybackManager,
    mediaItemDao: MediaItemDao,
    fullscreen: Boolean,
    inPictureInPicture: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
    onCast: (PlayRequest) -> Unit = {},
) {
    val current by playback.current.collectAsStateWithLifecycle()
    val problem by playback.problem.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recent by remember(mediaItemDao) { mediaItemDao.observeRecentlyPlayed(20) }
        .collectAsStateWithLifecycle(emptyList())

    DisposableEffect(playback) {
        playback.isPlayerScreenVisible = true
        onDispose {
            VideoGestures.restoreBrightness(context.findActivity()?.window)
            playback.isPlayerScreenVisible = false
            playback.savePosition()
            onFullscreenChange(false)
        }
    }
    LaunchedEffect(current) {
        while (current != null) {
            delay(5_000)
            playback.savePosition()
        }
    }
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    // Follow the video's real shape (vertical videos are common).
    var videoAspect by remember { mutableFloatStateOf(playback.player.videoSize.aspect()) }
    DisposableEffect(playback) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspect = videoSize.aspect()
            }
        }
        playback.player.addListener(listener)
        onDispose { playback.player.removeListener(listener) }
    }

    // Turning the phone sideways while a wide video plays goes fullscreen.
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && current != null && videoAspect > 1.1f && !fullscreen) {
            onFullscreenChange(true)
        }
    }

    if (current != null && (fullscreen || inPictureInPicture)) {
        VideoSurface(
            playback = playback,
            showControls = !inPictureInPicture,
            fullscreen = true,
            onFullscreenChange = onFullscreenChange,
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (current == null) {
                TopAppBar(
                    title = { Text("Player") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
    ) { padding ->
        val playing = current
        if (playing == null && recent.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PlayCircle,
                title = "Nothing playing yet",
                message = "Play a video from the browser's media button, your downloads or your library.",
                modifier = Modifier.padding(padding),
                action = {
                    FilledTonalButton(onClick = onOpenLibrary) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Go to library")
                    }
                },
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (playing != null) {
                VideoSurface(
                    playback = playback,
                    showControls = true,
                    fullscreen = false,
                    onFullscreenChange = onFullscreenChange,
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                        .aspectRatio(videoAspect.coerceAtLeast(0.3f), matchHeightConstraintsFirst = videoAspect < 1f)
                        .background(Color.Black),
                )
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playing.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onCast(playing) }) { Icon(Icons.Rounded.Cast, contentDescription = "Play on a TV") }
                    IconButton(onClick = playback::stop) { Icon(Icons.Rounded.Close, contentDescription = "Close player") }
                }
                queue?.let { q ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = playback::previous) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                        }
                        Text(
                            "${q.name} · ${q.index + 1} of ${q.items.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = playback::next, enabled = q.hasNext) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                        }
                    }
                }
                problem?.let { message ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = playback::retry) { Text("Retry") }
                            TextButton(onClick = { openElsewhere(context, playing) }) { Text("Open in another app") }
                        }
                    }
                }
            }
            val upNext = queue?.let { q -> q.items.withIndex().drop(q.index + 1) }.orEmpty()
            if (recent.isNotEmpty() || upNext.isNotEmpty()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (upNext.isNotEmpty()) {
                        item { SectionHeader("Up next") }
                        items(upNext, key = { "q${it.index}" }) { (index, request) ->
                            Text(
                                request.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { playback.jumpTo(index) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (recent.isNotEmpty()) item { SectionHeader("Continue watching") }
                    items(recent, key = { it.id }) { item ->
                        RecentRow(item) {
                            playback.play(
                                PlayRequest(
                                    uri = item.uri,
                                    title = item.title,
                                    mimeType = item.mimeType,
                                    libraryItemId = item.id,
                                    startPositionMs = item.playbackPositionMs,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun VideoSurface(
    playback: PlaybackManager,
    showControls: Boolean,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var indicator by remember { mutableStateOf<String?>(null) }
    var indicatorTick by remember { mutableIntStateOf(0) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var gestures by remember { mutableStateOf<VideoGestures?>(null) }

    LaunchedEffect(indicatorTick) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.um_player_view, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE },
                    )
                    val g = VideoGestures(
                        view = this,
                        player = { playback.player },
                        window = { context.findActivity()?.window },
                        show = { text ->
                            indicator = text
                            indicatorTick++
                        },
                    )
                    setOnTouchListener(g)
                    gestures = g
                    playerView = this
                }
            },
            update = { view ->
                view.player = playback.player
                view.useController = showControls
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener { wantFullscreen -> onFullscreenChange(wantFullscreen) }
            },
            onRelease = { view ->
                view.player = null
                view.setOnTouchListener(null)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (showControls && controlsVisible) {
            VideoToolbar(playback, playerView, gestures, Modifier.align(Alignment.TopEnd))
        }
        indicator?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private fun openElsewhere(context: Context, request: PlayRequest) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(request.uri), request.mimeType ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, request.title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No other app can open this", Toast.LENGTH_SHORT).show()
    }
}

/** Display aspect ratio (width ÷ height, pixel shape included); 16:9 until the size is known. */
private fun VideoSize.aspect(): Float =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else 16f / 9f

@Composable
private fun RecentRow(item: MediaItemEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(
            item.thumbnailPath,
            isAudio = item.mimeType?.startsWith("audio") == true,
            modifier = Modifier.size(width = 80.dp, height = 45.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val duration = item.durationMs
            Text(
                if (duration != null) "${formatDuration(item.playbackPositionMs)} / ${formatDuration(duration)}"
                else formatDuration(item.playbackPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
