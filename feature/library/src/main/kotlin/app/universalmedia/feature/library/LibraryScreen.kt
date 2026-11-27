package app.universalmedia.feature.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.model.formatBytes
import app.universalmedia.core.model.formatDuration
import app.universalmedia.core.ui.components.EmptyState
import app.universalmedia.core.ui.components.MediaThumbnail
import androidx.compose.foundation.layout.size

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpenSettings: () -> Unit, onPlay: (MediaItemEntity) -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onDeleteConfirmed(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { (_, sender) ->
            systemDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        }
    }
    var renaming by remember { mutableStateOf<MediaItemEntity?>(null) }
    var deleting by remember { mutableStateOf<MediaItemEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library")
                        if (state.totalCount > 0) {
                            Text(
                                "${state.totalCount} items · ${formatBytes(state.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("video/*", "audio/*")) },
                icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                text = { Text("Add files") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (state.totalCount == 0) {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = "Your library is empty",
                    message = "Downloads land here automatically. You can also add videos and music already on your phone. " +
                        "Nothing is uploaded anywhere.",
                )
                return@Column
            }

            TextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                placeholder = { Text("Search your library") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortChip(state.sort, viewModel::setSort)
                LibraryFilter.entries.forEach { f ->
                    FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
                }
            }

            if (state.items.isEmpty()) {
                Text(
                    "Nothing matches.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        MediaRow(
                            item = item,
                            onPlay = { onPlay(item) },
                            onOpen = { openExternally(context, item) },
                            onFavorite = { viewModel.toggleFavorite(item) },
                            onRemove = { viewModel.remove(item) },
                            onRename = { renaming = item },
                            onShare = { shareFile(context, item) },
                            onDelete = { deleting = item },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { item -> RenameDialog(item, onRename = { viewModel.rename(item, it) }, onDismiss = { renaming = null }) }
    deleting?.let { item -> DeleteDialog(item, onDelete = { viewModel.deleteFile(item) }, onDismiss = { deleting = null }) }
}

@Composable
private fun SortChip(current: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != LibrarySort.NEWEST,
            onClick = { open = true },
            label = { Text("Sort: ${current.label}") },
            leadingIcon = { Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; onSort(s) })
            }
        }
    }
}

@Composable
private fun RenameDialog(item: MediaItemEntity, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.title) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onRename(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(item: MediaItemEntity, onDelete: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete from phone?") },
        text = { Text("“${item.title}” will be deleted from this phone. This can't be undone.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDelete(); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shareFile(context: Context, item: MediaItemEntity) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(item.mimeType ?: "video/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Share “${item.title}”"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can share this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MediaRow(
    item: MediaItemEntity,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val isAudio = item.mimeType?.startsWith("audio") == true
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onPlay)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(item.thumbnailPath, isAudio, Modifier.size(width = 96.dp, height = 54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.durationMs?.let(::formatDuration), item.sizeBytes?.let(::formatBytes))
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (item.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (item.isFavorite) "Remove favorite" else "Add favorite",
                tint = if (item.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Remove from library") }, onClick = { menu = false; onRemove() })
                DropdownMenuItem(
                    text = { Text("Delete from phone", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** Hands the file to another app (e.g. to cast or edit it). */
private fun openExternally(context: Context, item: MediaItemEntity) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(item.uri), item.mimeType ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, item.title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
