package app.universalmedia.feature.player

import android.view.ViewGroup
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
import app.universalmedia.core.ui.components.GlowIcon
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
) {
    val current by playback.current.collectAsStateWithLifecycle()
    val recent by remember(mediaItemDao) { mediaItemDao.observeRecentlyPlayed(20) }
        .collectAsStateWithLifecycle(emptyList())

    DisposableEffect(playback) {
        playback.isPlayerScreenVisible = true
        onDispose {
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
                        .aspectRatio(16f / 9f)
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
                    IconButton(onClick = playback::stop) { Icon(Icons.Rounded.Close, contentDescription = "Close player") }
                }
            }
            if (recent.isNotEmpty()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { SectionHeader("Continue watching") }
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
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                }
            },
            update = { view ->
                view.player = playback.player
                view.useController = showControls
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener { wantFullscreen -> onFullscreenChange(wantFullscreen) }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

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
        GlowIcon(Icons.Rounded.PlayCircle, size = 40)
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
