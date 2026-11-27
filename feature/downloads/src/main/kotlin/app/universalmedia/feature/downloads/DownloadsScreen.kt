package app.universalmedia.feature.downloads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.DownloadEntity
import app.universalmedia.core.model.DownloadStatus
import app.universalmedia.core.model.formatBytes
import app.universalmedia.core.ui.components.EmptyState
import app.universalmedia.core.ui.components.GlowIcon
import app.universalmedia.core.ui.components.Pill
import app.universalmedia.media.download.MediaDownloadManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(manager: MediaDownloadManager, onOpenBrowser: () -> Unit) {
    val downloads by remember(manager) { manager.observe() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (downloads.any { !it.statusEnum.isActive && it.statusEnum != DownloadStatus.PAUSED }) {
                        TextButton(onClick = { scope.launch { manager.clearFinished() } }) { Text("Clear finished") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = "No downloads yet",
                message = "Find a video in the browser and tap Save. Downloads continue in the background, " +
                    "pause and resume, and land in your library.",
                modifier = Modifier.padding(padding),
                action = {
                    FilledTonalButton(onClick = onOpenBrowser) {
                        Icon(Icons.Rounded.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open browser")
                    }
                },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(downloads, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onPause = { scope.launch { manager.pause(item.id) } },
                        onResume = { scope.launch { manager.resume(item.id) } },
                        onCancel = { scope.launch { manager.cancel(item.id) } },
                        onRemove = { scope.launch { manager.remove(item.id) } },
                        onOpen = { openFile(context, item) },
                    )
                }
            }
        }
    }
}

private val DownloadEntity.statusEnum: DownloadStatus get() = DownloadStatus.parse(status)

@Composable
private fun DownloadRow(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val status = item.statusEnum
    var menu by remember { mutableStateOf(false) }
    val statusColor = when (status) {
        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadStatus.PAUSED, DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = status == DownloadStatus.COMPLETED, onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowIcon(
            when (status) {
                DownloadStatus.COMPLETED -> Icons.Rounded.DownloadDone
                DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.Download
            },
            size = 40,
            tint = statusColor,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(status.label, statusColor)
                item.variantLabel?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            val total = item.totalBytes
            Text(
                when {
                    status == DownloadStatus.COMPLETED -> formatBytes(item.downloadedBytes)
                    total != null && total > 0 -> "${formatBytes(item.downloadedBytes)} of ${formatBytes(total)}"
                    item.downloadedBytes > 0 -> formatBytes(item.downloadedBytes)
                    else -> "Waiting to start"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.isActive || status == DownloadStatus.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                } else if (status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
            item.errorMessage?.takeIf { status == DownloadStatus.FAILED || status == DownloadStatus.QUEUED }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (status == DownloadStatus.QUEUED) "Retrying: $it" else it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        when (status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> IconButton(onClick = onPause) {
                Icon(Icons.Rounded.Pause, contentDescription = "Pause")
            }
            DownloadStatus.PAUSED -> IconButton(onClick = onResume) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> IconButton(onClick = onResume) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
            }
            DownloadStatus.COMPLETED -> Unit
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (status == DownloadStatus.COMPLETED) {
                    DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; onOpen() })
                }
                if (status.isActive || status == DownloadStatus.PAUSED) {
                    DropdownMenuItem(
                        text = { Text("Cancel download") },
                        leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                        onClick = { menu = false; onCancel() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (status == DownloadStatus.COMPLETED) "Remove from list (keeps file)" else "Remove from list") },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

private fun openFile(context: Context, item: DownloadEntity) {
    val uri = item.destinationUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), item.mimeType ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, item.title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
