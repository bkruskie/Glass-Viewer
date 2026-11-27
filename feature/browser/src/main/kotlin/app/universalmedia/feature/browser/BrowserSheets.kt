package app.universalmedia.feature.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.DownloadSupport
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.core.model.formatBytes
import app.universalmedia.core.model.formatDuration
import app.universalmedia.core.ui.components.Pill
import app.universalmedia.media.detection.MediaGrouping
import app.universalmedia.media.detection.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserSheets(
    sheet: BrowserSheet,
    state: BrowserUiState,
    onDismiss: () -> Unit,
    viewModel: BrowserViewModel,
    onSave: (DetectedMedia, MediaVariant?) -> Unit,
    onPlay: (DetectedMedia, MediaVariant?) -> Unit,
) {
    if (sheet == BrowserSheet.NONE) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == BrowserSheet.MEDIA)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (sheet) {
            BrowserSheet.TABS -> TabsSheet(
                state = state,
                onSelect = { viewModel.selectTab(it); onDismiss() },
                onClose = viewModel::closeTab,
                onNew = { viewModel.newTabAndSelect(); onDismiss() },
            )
            BrowserSheet.MEDIA -> MediaSheet(
                media = state.activeTab.detected,
                onDismissItem = { urls -> viewModel.dismissDetected(state.activeTabId, urls) },
                onSave = onSave,
                onPlay = onPlay,
            )
            BrowserSheet.BOOKMARKS -> LinkListSheet(
                title = "Bookmarks",
                empty = "Pages you bookmark appear here.",
                items = state.bookmarks.map { LinkRow(it.url, it.title) },
                onOpen = { viewModel.open(it); onDismiss() },
                onDelete = viewModel::removeBookmark,
            )
            BrowserSheet.HISTORY -> LinkListSheet(
                title = "History",
                empty = "Pages you visit appear here. History never leaves this device.",
                items = state.history.map { LinkRow(it.url, it.title) },
                onOpen = { viewModel.open(it); onDismiss() },
                headerAction = {
                    if (state.history.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearHistory) { Text("Clear all") }
                    }
                },
            )
            BrowserSheet.NONE -> Unit
        }
    }
}

@Composable
private fun SheetTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun TabsSheet(
    state: BrowserUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    SheetTitle("Tabs") {
        TextButton(onClick = onNew) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("New tab")
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp)) {
        items(state.tabs, key = { it.id }) { tab ->
            val selected = tab.id == state.activeTabId
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable { onSelect(tab.id) }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tab.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (tab.url.isBlank()) "Start page" else AddressBarResolver.display(tab.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tab.detected.isNotEmpty()) Pill("${tab.detected.size} media", MaterialTheme.colorScheme.secondary)
                IconButton(onClick = { onClose(tab.id) }) { Icon(Icons.Rounded.Close, contentDescription = "Close tab") }
            }
        }
    }
}

@Composable
private fun MediaSheet(
    media: List<DetectedMedia>,
    onDismissItem: (Set<String>) -> Unit,
    onSave: (DetectedMedia, MediaVariant?) -> Unit,
    onPlay: (DetectedMedia, MediaVariant?) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val organized = remember(media) { MediaGrouping.organize(media) }
    val videos = organized.filterNot(MediaGrouping::isAudio)
    val audio = organized.filter(MediaGrouping::isAudio)

    SheetTitle("Media on this page")
    Text(
        listOfNotNull(
            videos.size.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
            audio.size.takeIf { it > 0 }?.let { if (it == 1) "1 audio track" else "$it audio tracks" },
        ).joinToString(" · ") + " · choose a quality, then Save or Play",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        fun section(title: String, list: List<DetectedMedia>) {
            if (list.isEmpty()) return
            item(key = "h-$title") {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
            items(list, key = { it.id + it.mediaUrl }) { item ->
                MediaCard(
                    item = item,
                    onCopy = { url ->
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    },
                    onPlay = { variant -> onPlay(item, variant) },
                    onSave = { variant -> onSave(item, variant) },
                    onDismiss = { onDismissItem(MediaGrouping.urlsOf(item)) },
                )
            }
        }
        section("Videos", videos)
        section("Audio", audio)
    }
}

/** "1080p60", "720p", "Audio · 128 kbps", or "Original" when the source doesn't say. */
private fun qualityLabel(v: MediaVariant): String = when {
    v.audioOnly -> "Audio" + (v.bitrate?.let { " · ${it / 1000} kbps" } ?: "")
    v.height != null -> "${v.height}p" + (v.frameRate?.takeIf { it >= 49 }?.let { it.toInt().toString() } ?: "")
    else -> "Original"
}

private fun qualityBadge(item: DetectedMedia): String {
    val best = item.variants.firstOrNull()
    val height = best?.height
    return when {
        MediaGrouping.isAudio(item) -> "AUDIO"
        height == null -> item.format.displayName.substringBefore(' ').uppercase()
        height >= 2160 -> "4K"
        else -> "${height}p"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaCard(
    item: DetectedMedia,
    onCopy: (String) -> Unit,
    onPlay: (MediaVariant?) -> Unit,
    onSave: (MediaVariant?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(item.id, item.variants.size) { mutableIntStateOf(0) }
    val variants = item.variants.take(MAX_VARIANTS_SHOWN)
    val chosen = variants.getOrNull(selected)
    val chosenUrl = chosen?.url ?: item.mediaUrl
    val saveBlocker = DownloadSupport.reasonNotSupported(item, chosen)
    val availabilityColor = when (item.availability) {
        Availability.SUPPORTED -> MaterialTheme.colorScheme.secondary
        Availability.PROTECTED, Availability.INACCESSIBLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        // Header: best quality badge, title, source.
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    qualityBadge(item),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title ?: UrlUtils.fileNameOf(item.mediaUrl) ?: "Untitled media",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.sourceHost,
                    item.durationMs?.let(::formatDuration),
                    item.format.displayName,
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 4.dp)) {
                    Pill(item.availability.label, availabilityColor)
                    if (item.origin == DetectionOrigin.SHARE_INTENT) {
                        Spacer(Modifier.width(6.dp))
                        Pill("Shared", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Hide", modifier = Modifier.size(18.dp))
            }
        }

        item.note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = availabilityColor, modifier = Modifier.padding(end = 12.dp))
        }

        // Quality picker, best first, with sizes when known.
        if (variants.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Quality", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                variants.forEachIndexed { index, v ->
                    FilterChip(
                        selected = index == selected,
                        onClick = { selected = index },
                        label = {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(qualityLabel(v), style = MaterialTheme.typography.labelLarge)
                                v.estimatedBytes?.let {
                                    Text(
                                        "≈ ${formatBytes(it)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        if (saveBlocker != null && item.note == null) {
            Text(saveBlocker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Actions.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FilledTonalButton(onClick = { onSave(chosen) }, enabled = saveBlocker == null) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append("Save")
                        chosen?.let { v -> if (v.height != null || v.audioOnly) append(" ").append(qualityLabel(v).substringBefore(" ·")) }
                    },
                )
            }
            Spacer(Modifier.width(4.dp))
            if (item.availability == Availability.SUPPORTED) {
                TextButton(onClick = { onPlay(chosen) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onCopy(chosenUrl) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy link", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private data class LinkRow(val url: String, val title: String)

@Composable
private fun LinkListSheet(
    title: String,
    empty: String,
    items: List<LinkRow>,
    onOpen: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
) {
    SheetTitle(title, headerAction)
    if (items.isEmpty()) {
        Text(
            empty,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        items(items) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row.url) }
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        AddressBarResolver.display(row.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = { onDelete(row.url) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val MAX_VARIANTS_SHOWN = 8
