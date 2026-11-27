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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
private fun MediaSheet(media: List<DetectedMedia>, onDismissItem: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    SheetTitle("Media on this page")
    Text(
        "Saving straight to your library arrives with the download engine. For now you can copy a link or open it in another app.",
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
                onCopy = {
                    clipboard.setText(AnnotatedString(item.mediaUrl))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                },
                onOpenWith = { openWith(context, item) },
                onDismiss = { onDismissItem(item.id) },
            )
        }
    }
}

@Composable
private fun MediaCard(item: DetectedMedia, onCopy: () -> Unit, onOpenWith: () -> Unit, onDismiss: () -> Unit) {
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
            item.bestVariant?.let {
                Spacer(Modifier.width(6.dp))
                Pill(it.label, MaterialTheme.colorScheme.onSurfaceVariant)
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
        val meta = listOfNotNull(item.sourceHost, item.durationMs?.let(::formatDuration)).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = availabilityColor, modifier = Modifier.padding(end = 12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = {}, enabled = false) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy link")
            }
            if (item.availability == Availability.SUPPORTED) {
                TextButton(onClick = onOpenWith) {
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

private fun openWith(context: Context, item: DetectedMedia) {
    val mime = item.mimeType?.substringBefore(';') ?: item.format.mimeTypes.firstOrNull() ?: "video/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.mediaUrl), mime)
    try {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this media", Toast.LENGTH_SHORT).show()
    }
}
