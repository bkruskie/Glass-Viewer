package app.universalmedia.feature.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
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
import app.universalmedia.media.detection.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserSheets(
    sheet: BrowserSheet,
    state: BrowserUiState,
    onDismiss: () -> Unit,
    viewModel: BrowserViewModel,
    onSave: (DetectedMedia, MediaVariant?) -> Unit,
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
                onDismissItem = { viewModel.dismissDetected(state.activeTabId, it) },
                onSave = onSave,
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
    onDismissItem: (String) -> Unit,
    onSave: (DetectedMedia, MediaVariant?) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    SheetTitle("Media on this page")
    Text(
        "Pick a quality and tap Save. Downloads keep going in the background and land in your library.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
    )
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(media, key = { it.id }) { item ->
            MediaCard(
                item = item,
                onCopy = { url ->
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                },
                onOpenWith = { url -> openWith(context, item, url) },
                onSave = { variant -> onSave(item, variant) },
                onDismiss = { onDismissItem(item.id) },
            )
        }
    }
}

@Composable
private fun MediaCard(
    item: DetectedMedia,
    onCopy: (String) -> Unit,
    onOpenWith: (String) -> Unit,
    onSave: (MediaVariant?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(item.id) { mutableIntStateOf(0) }
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
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(item.format.displayName, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Pill(item.availability.label, availabilityColor)
            if (item.origin == DetectionOrigin.SHARE_INTENT) {
                Spacer(Modifier.width(6.dp))
                Pill("Shared", MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Hide", modifier = Modifier.size(18.dp))
            }
        }
        Text(
            item.title ?: UrlUtils.fileNameOf(item.mediaUrl) ?: "Untitled media",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        val meta = listOfNotNull(
            item.sourceHost,
            item.durationMs?.let(::formatDuration),
            if (variants.size <= 1) chosen?.estimatedBytes?.let { formatBytes(it) } else null,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = availabilityColor, modifier = Modifier.padding(end = 12.dp))
        }
        if (saveBlocker != null && item.note == null) {
            Spacer(Modifier.height(4.dp))
            Text(saveBlocker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (variants.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text("Quality", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            variants.forEachIndexed { index, v ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = index == selected, role = Role.RadioButton, onClick = { selected = index })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = index == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        v.label + if (v.format != item.format) " · ${v.format.displayName}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    v.estimatedBytes?.let {
                        Text(
                            "≈ ${formatBytes(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onSave(chosen) }, enabled = saveBlocker == null) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onCopy(chosenUrl) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy link")
            }
            if (item.availability == Availability.SUPPORTED) {
                TextButton(onClick = { onOpenWith(chosenUrl) }) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open")
                }
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

private fun openWith(context: Context, item: DetectedMedia, url: String) {
    val format = item.variants.firstOrNull { it.url == url }?.format ?: item.format
    val mime = (if (format == item.format) item.mimeType?.substringBefore(';') else null)
        ?: format.mimeTypes.firstOrNull() ?: "video/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
    try {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this media", Toast.LENGTH_SHORT).show()
    }
}
