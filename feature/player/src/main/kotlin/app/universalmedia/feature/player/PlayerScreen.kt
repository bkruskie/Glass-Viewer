package app.universalmedia.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.model.formatDuration
import app.universalmedia.core.ui.components.EmptyState
import app.universalmedia.core.ui.components.GlowIcon
import app.universalmedia.core.ui.components.SectionHeader

/**
 * Player hub. Milestone 1 shows recently played items; the Media3-based player
 * (subtitles, tracks, speed, PiP, background audio) lands in Milestone 4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(mediaItemDao: MediaItemDao, onOpenLibrary: () -> Unit) {
    val recent by remember(mediaItemDao) { mediaItemDao.observeRecentlyPlayed(20) }
        .collectAsStateWithLifecycle(emptyList())

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Player") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (recent.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PlayCircle,
                title = "Nothing playing yet",
                message = "Pick a video or song from your library. Subtitles, playback speed, picture-in-picture " +
                    "and background audio are coming to the built-in player.",
                modifier = Modifier.padding(padding),
                action = {
                    FilledTonalButton(onClick = onOpenLibrary) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Go to library")
                    }
                },
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
                item { SectionHeader("Continue watching") }
                items(recent, key = { it.id }) { RecentRow(it) }
            }
        }
    }
}

@Composable
private fun RecentRow(item: MediaItemEntity) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
