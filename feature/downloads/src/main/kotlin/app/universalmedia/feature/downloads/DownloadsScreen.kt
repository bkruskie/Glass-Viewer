package app.universalmedia.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.DownloadDao
import app.universalmedia.core.database.DownloadEntity
import app.universalmedia.core.model.DownloadStatus
import app.universalmedia.core.model.formatBytes
import app.universalmedia.core.ui.components.EmptyState
import app.universalmedia.core.ui.components.GlowIcon
import app.universalmedia.core.ui.components.Pill
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(downloadDao: DownloadDao, onOpenBrowser: () -> Unit) {
    val downloads by remember(downloadDao) { downloadDao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (downloads.any { !it.statusEnum.isActive }) {
                        TextButton(onClick = { scope.launch { downloadDao.clearFinished() } }) { Text("Clear finished") }
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
                message = "Find a video in the browser and it will be queued here, with progress, pause and resume.",
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
                items(downloads, key = { it.id }) { DownloadRow(it) }
            }
        }
    }
}

private val DownloadEntity.statusEnum: DownloadStatus get() = DownloadStatus.parse(status)

@Composable
private fun DownloadRow(item: DownloadEntity) {
    val status = item.statusEnum
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowIcon(if (status == DownloadStatus.COMPLETED) Icons.Rounded.DownloadDone else Icons.Rounded.Download, size = 40)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    status.label,
                    when (status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(8.dp))
                val total = item.totalBytes
                Text(
                    if (total != null && total > 0) "${formatBytes(item.downloadedBytes)} / ${formatBytes(total)}"
                    else formatBytes(item.downloadedBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.isActive) {
                Spacer(Modifier.height(8.dp))
                val total = item.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
