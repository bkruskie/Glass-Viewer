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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.PlaylistPlay
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
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSettings: () -> Unit,
    onPlay: (MediaItemEntity) -> Unit = {},
    onPlayList: (name: String, items: List<MediaItemEntity>, startIndex: Int) -> Unit = { _, _, _ -> },
) {
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
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var openPlaylist by remember { mutableStateOf<app.universalmedia.core.database.PlaylistSummary?>(null) }
    var addingToPlaylist by remember { mutableStateOf<MediaItemEntity?>(null) }
    var detailsFor by remember { mutableStateOf<MediaItemEntity?>(null) }
    var foldering by remember { mutableStateOf<MediaItemEntity?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    // Copy / move to a folder on the phone: remember what and how until the folder is picked.
    var exporting by remember { mutableStateOf<Pair<MediaItemEntity, Boolean>?>(null) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val job = exporting
        exporting = null
        if (tree != null && job != null) viewModel.copyToFolder(job.first, tree, move = job.second)
    }
    var newPlaylist by remember { mutableStateOf(false) }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
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
                FilterChip(selected = showPlaylists, onClick = { showPlaylists = !showPlaylists }, label = { Text("Playlists") })
                if (!showPlaylists) {
                    SortChip(state.sort, viewModel::setSort)
                    LibraryFilter.entries.forEach { f ->
                        FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
                    }
                }
            }

            busy?.let { text ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (!showPlaylists && state.folders.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.folder == null,
                        onClick = { viewModel.showFolder(null) },
                        label = { Text("All folders") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    state.folders.forEach { name ->
                        Box {
                            FilterChip(
                                selected = state.folder == name,
                                onClick = { if (state.folder == name) folderMenu = name else viewModel.showFolder(name) },
                                label = { Text(name) },
                            )
                            DropdownMenu(expanded = folderMenu == name, onDismissRequest = { folderMenu = null }) {
                                DropdownMenuItem(text = { Text("Rename folder") }, onClick = { folderMenu = null; renamingFolder = name })
                                DropdownMenuItem(text = { Text("Remove folder") }, onClick = { folderMenu = null; viewModel.deleteFolder(name) })
                            }
                        }
                    }
                }
            }
            if (showPlaylists) {
                PlaylistList(playlists, onNew = { newPlaylist = true }, onOpen = { openPlaylist = it })
                return@Column
            }
            if (state.items.size > 1) {
                androidx.compose.material3.TextButton(
                    onClick = { onPlayList(if (state.filter == LibraryFilter.ALL) "Library" else state.filter.label, state.items, 0) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Rounded.PlaylistPlay, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play all ${state.items.size}")
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
                            onAddToPlaylist = { addingToPlaylist = item },
                            onDetails = { detailsFor = item },
                            onFolder = { foldering = item },
                            onCopy = { exporting = item to false; treePicker.launch(null) },
                            onMove = { exporting = item to true; treePicker.launch(null) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { item -> RenameDialog(item, onRename = { viewModel.rename(item, it) }, onDismiss = { renaming = null }) }
    deleting?.let { item -> DeleteDialog(item, onDelete = { viewModel.deleteFile(item) }, onDismiss = { deleting = null }) }
    foldering?.let { item ->
        FolderSheet(
            item = item,
            folders = state.folders,
            onPick = { viewModel.moveToFolder(item, it); foldering = null },
            onNew = { newFolder = true },
            onDismiss = { foldering = null },
        )
    }
    if (newFolder) {
        NameDialog("New folder", "Create", initial = "", onDone = { name ->
            viewModel.createFolder(name, foldering)
            foldering = null
        }, onDismiss = { newFolder = false })
    }
    renamingFolder?.let { old ->
        NameDialog("Rename folder", "Rename", initial = old, onDone = { viewModel.renameFolder(old, it) }, onDismiss = { renamingFolder = null })
    }
    detailsFor?.let { item -> DetailsDialog(item, viewModel, onDismiss = { detailsFor = null }) }
    addingToPlaylist?.let { item -> AddToPlaylistSheet(item, viewModel, onDismiss = { addingToPlaylist = null }) }
    openPlaylist?.let { p ->
        PlaylistSheet(
            playlist = p,
            viewModel = viewModel,
            onPlay = { items, index -> openPlaylist = null; onPlayList(p.name, items, index) },
            onDismiss = { openPlaylist = null },
        )
    }
    if (newPlaylist) {
        NameDialog("New playlist", "Create", initial = "", onDone = { viewModel.createPlaylist(it) }, onDismiss = { newPlaylist = false })
    }
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
    onAddToPlaylist: () -> Unit,
    onDetails: () -> Unit,
    onFolder: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
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
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; onAddToPlaylist() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetails() })
                DropdownMenuItem(text = { Text(if (item.folder == null) "Put in folder…" else "Change folder…") }, onClick = { menu = false; onFolder() })
                DropdownMenuItem(text = { Text("Copy to phone folder…") }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text("Move to phone folder…") }, onClick = { menu = false; onMove() })
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

@Composable
private fun DetailsDialog(item: MediaItemEntity, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val rows by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, item.id) { value = viewModel.details(item) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            val list = rows
            if (list == null) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Column(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    list.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FolderSheet(
    item: MediaItemEntity,
    folders: List<String>,
    onPick: (String?) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Folder", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Folders organise your library. The file stays where it is on the phone; use Move to phone folder for that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            @Composable
            fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, modifier = Modifier.weight(1f))
                }
            }
            Option("New folder", false, onNew)
            folders.forEach { name -> Option(name, item.folder == name) { onPick(name) } }
            if (item.folder != null) Option("No folder", false) { onPick(null) }
        }
    }
}
