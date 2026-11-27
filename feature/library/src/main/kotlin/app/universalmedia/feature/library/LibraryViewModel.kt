package app.universalmedia.feature.library

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.database.PlaylistDao
import app.universalmedia.core.database.PlaylistEntity
import app.universalmedia.core.database.PlaylistItemEntity
import app.universalmedia.core.database.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import app.universalmedia.media.download.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio"), FAVORITES("Favorites") }

enum class LibrarySort(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), LARGEST("Largest"), LONGEST("Longest"), RECENTLY_PLAYED("Recently played")
}

data class LibraryUiState(
    val items: List<MediaItemEntity> = emptyList(),
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.NEWEST,
    /** All library folder names, A–Z. */
    val folders: List<String> = emptyList(),
    /** The folder being shown, or null for everything. */
    val folder: String? = null,
)

class LibraryViewModel(
    private val app: Application,
    private val dao: MediaItemDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistSummary>> = playlistDao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playlistItems(id: Long): Flow<List<MediaItemEntity>> = playlistDao.observeItems(id)

    /** Creates a playlist (and adds [first] to it, if given). */
    fun createPlaylist(name: String, first: MediaItemEntity? = null) {
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            val id = playlistDao.insert(PlaylistEntity(name = clean, createdAt = System.currentTimeMillis()))
            if (first != null) addTo(id, clean, first) else _message.value = "Created “$clean”"
        }
    }

    fun addToPlaylist(playlist: PlaylistSummary, item: MediaItemEntity) {
        viewModelScope.launch { addTo(playlist.id, playlist.name, item) }
    }

    private suspend fun addTo(playlistId: Long, name: String, item: MediaItemEntity) {
        val added = playlistDao.insertItem(PlaylistItemEntity(playlistId, item.id, playlistDao.nextPosition(playlistId))) != -1L
        _message.value = if (added) "Added to “$name”" else "Already in “$name”"
    }

    fun removeFromPlaylist(playlistId: Long, item: MediaItemEntity) {
        viewModelScope.launch { playlistDao.removeItem(playlistId, item.id) }
    }

    /** Saves a new order after moving an item up or down. */
    fun reorder(playlistId: Long, ordered: List<MediaItemEntity>) {
        viewModelScope.launch { ordered.forEachIndexed { i, m -> playlistDao.setPosition(playlistId, m.id, i) } }
    }

    fun renamePlaylist(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch { playlistDao.rename(id, clean) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistDao.delete(id) }
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val sort = MutableStateFlow(LibrarySort.NEWEST)
    private val folder = MutableStateFlow<String?>(null)
    /** Folders made in this session that don't hold anything yet. */
    private val emptyFolders = MutableStateFlow<Set<String>>(emptySet())

    private val _busy = MutableStateFlow<String?>(null)
    /** A long file operation in progress ("Copying …"), for a progress bar. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Set when Android must confirm deleting a file the app didn't create. */
    private val _deleteRequest = MutableStateFlow<Pair<MediaItemEntity, android.content.IntentSender>?>(null)
    val deleteRequest: StateFlow<Pair<MediaItemEntity, android.content.IntentSender>?> = _deleteRequest.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private data class Controls(val q: String, val f: LibraryFilter, val order: LibrarySort, val folder: String?, val extraFolders: Set<String>)

    val uiState: StateFlow<LibraryUiState> = combine(
        dao.observeAll(),
        dao.observeTotalBytes(),
        combine(query, filter, sort, folder, emptyFolders) { q, f, o, fo, e -> Controls(q, f, o, fo, e) },
    ) { all, bytes, c ->
        val q = c.q
        val f = c.f
        val order = c.order
        val folders = (all.mapNotNull { it.folder } + c.extraFolders).distinct().sortedBy { it.lowercase() }
        val shownFolder = c.folder?.takeIf { it in folders }
        val filtered = all.filter { item ->
            val mime = item.mimeType.orEmpty()
            val matchesFilter = when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.VIDEO -> mime.startsWith("video")
                LibraryFilter.AUDIO -> mime.startsWith("audio")
                LibraryFilter.FAVORITES -> item.isFavorite
            }
            matchesFilter && (shownFolder == null || item.folder == shownFolder) &&
                (q.isBlank() || item.title.contains(q.trim(), ignoreCase = true))
        }
        val sorted = when (order) {
            LibrarySort.NEWEST -> filtered
            LibrarySort.OLDEST -> filtered.sortedBy { it.addedAt }
            LibrarySort.NAME -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes ?: 0 }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs ?: 0 }
            LibrarySort.RECENTLY_PLAYED -> filtered.sortedByDescending { it.lastPlayedAt ?: 0 }
        }
        LibraryUiState(sorted, all.size, bytes, q, f, order, folders, shownFolder)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        makeMissingThumbnails()
    }

    /** Creates preview images for items that don't have one yet (older items, imports). */
    private fun makeMissingThumbnails() {
        viewModelScope.launch {
            while (true) {
                val batch = dao.withoutThumbnail(20)
                if (batch.isEmpty()) break
                for (item in batch) {
                    val isAudio = item.mimeType?.startsWith("audio") == true
                    val path = runCatching {
                        Thumbnails.create(app, Uri.parse(item.uri), "lib-${item.id}", isAudio)
                    }.getOrNull()
                    dao.setThumbnail(item.id, path ?: "") // "" = tried, none available
                }
            }
        }
    }

    fun setQuery(value: String) { query.value = value }

    fun setFilter(value: LibraryFilter) { filter.value = value }

    /** Facts about a file for the Details view: format, tracks, size, dates, where it is. */
    suspend fun details(item: MediaItemEntity): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Pair<String, String>>()
        val uri = Uri.parse(item.uri)
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        rows += "Name" to item.title
        item.mimeType?.let { rows += "Type" to it }
        item.sizeBytes?.let { rows += "Size" to android.text.format.Formatter.formatFileSize(app, it) }
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
                    rows += "Length" to formatDuration(ms)
                }
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w != null && h != null) rows += "Resolution" to "$w × $h"
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { bps ->
                    rows += "Bitrate" to "%.1f Mbps".format(bps / 1_000_000.0)
                }
            } finally {
                r.release()
            }
        }
        runCatching {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(app, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    val text = when {
                        mime.startsWith("video") -> buildString {
                            append(codecName(mime))
                            if (f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                                append(" · ").append(runCatching { f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrNull() ?: "?").append(" fps")
                            }
                        }
                        mime.startsWith("audio") -> buildString {
                            append(codecName(mime))
                            if (f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) append(" · ").append(f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) / 1000.0).append(" kHz")
                            if (f.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                                val ch = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                                append(" · ").append(if (ch == 1) "mono" else if (ch == 2) "stereo" else "$ch channels")
                            }
                            f.getString(android.media.MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" }?.let { append(" · ").append(it) }
                        }
                        else -> codecName(mime)
                    }
                    rows += (if (mime.startsWith("video")) "Video" else if (mime.startsWith("audio")) "Audio" else "Track") to text
                }
            } finally {
                ex.release()
            }
        }
        rows += "Added" to date.format(java.util.Date(item.addedAt))
        item.lastPlayedAt?.let { rows += "Last played" to date.format(java.util.Date(it)) }
        if (item.playbackPositionMs > 0) rows += "Stopped at" to formatDuration(item.playbackPositionMs)
        val where = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        rows += "Location" to (where?.trimEnd('/')?.replace("/", " › ") ?: uri.authority ?: uri.toString())
        rows
    }

    private fun codecName(mime: String): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265 (HEVC)"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/flac" -> "FLAC"
        else -> mime
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun setSort(value: LibrarySort) { sort.value = value }

    // ---- library folders ----

    fun showFolder(name: String?) { folder.value = name }

    fun createFolder(name: String, first: MediaItemEntity? = null) {
        val clean = name.trim().ifEmpty { return }
        emptyFolders.value = emptyFolders.value + clean
        if (first != null) moveToFolder(first, clean) else folder.value = clean
    }

    fun moveToFolder(item: MediaItemEntity, name: String?) {
        viewModelScope.launch {
            dao.setFolder(item.id, name)
            _message.value = if (name == null) "Removed from folder" else "Moved to “$name”"
        }
    }

    fun renameFolder(old: String, new: String) {
        val clean = new.trim().ifEmpty { return }
        viewModelScope.launch {
            dao.renameFolder(old, clean)
            if (old in emptyFolders.value) emptyFolders.value = emptyFolders.value - old + clean
            if (folder.value == old) folder.value = clean
        }
    }

    /** Removes the folder label; its items stay in the library. */
    fun deleteFolder(name: String) {
        viewModelScope.launch {
            dao.renameFolder(name, null)
            emptyFolders.value = emptyFolders.value - name
            if (folder.value == name) folder.value = null
        }
    }

    // ---- copy / move files on the phone ----

    /**
     * Copies the file into a folder the user picked (phone storage or SD card).
     * With [move], the original is deleted afterwards and the library points at the new copy.
     */
    fun copyToFolder(item: MediaItemEntity, tree: Uri, move: Boolean) {
        viewModelScope.launch {
            _busy.value = (if (move) "Moving" else "Copying") + " “${item.title}”…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    val mime = item.mimeType ?: "application/octet-stream"
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    val name = if (ext != null && !item.title.endsWith(".$ext", true)) "${item.title}.$ext" else item.title
                    val target = android.provider.DocumentsContract.createDocument(app.contentResolver, parent, mime, name)
                        ?: error("Couldn't create the file there")
                    try {
                        val input = app.contentResolver.openInputStream(Uri.parse(item.uri)) ?: error("Couldn't read the file")
                        input.use { i ->
                            (app.contentResolver.openOutputStream(target, "w") ?: error("Couldn't write there")).use { o -> i.copyTo(o, 256 * 1024) }
                        }
                    } catch (e: Exception) {
                        runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, target) }
                        throw e
                    }
                    target
                }
            }
            _busy.value = null
            result.fold(
                onSuccess = { target ->
                    if (!move) {
                        _message.value = "Copied “${item.title}”"
                    } else {
                        deleteFile(item, movedTo = target)
                    }
                },
                onFailure = { e -> _message.value = "Couldn't ${if (move) "move" else "copy"} the file: ${e.message ?: "unknown error"}" },
            )
        }
    }

    private var pendingMoveTarget: Uri? = null

    fun consumeMessage() { _message.value = null }

    fun rename(item: MediaItemEntity, title: String) {
        val clean = title.trim()
        if (clean.isEmpty() || clean == item.title) return
        viewModelScope.launch { dao.rename(item.id, clean) }
    }

    /**
     * Deletes the file from the phone and the library. Files the app saved are deleted
     * straight away; for others Android shows its own confirmation ([deleteRequest]).
     */
    fun deleteFile(item: MediaItemEntity, movedTo: Uri? = null) {
        pendingMoveTarget = movedTo
        viewModelScope.launch {
            val uri = Uri.parse(item.uri)
            val result = withContext(Dispatchers.IO) {
                runCatching<android.content.IntentSender?> {
                    val deleted = if (android.provider.DocumentsContract.isDocumentUri(app, uri)) {
                        android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri)
                    } else {
                        app.contentResolver.delete(uri, null, null) > 0
                    }
                    if (deleted) null else error("not deleted")
                }.recoverCatching { e ->
                    if (e is SecurityException && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        uri.authority == android.provider.MediaStore.AUTHORITY
                    ) {
                        android.provider.MediaStore.createDeleteRequest(app.contentResolver, listOf(uri)).intentSender
                    } else if (e is SecurityException && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                        e is android.app.RecoverableSecurityException
                    ) {
                        e.userAction.actionIntent.intentSender
                    } else {
                        throw e
                    }
                }
            }
            result.fold(
                onSuccess = { sender ->
                    when {
                        sender != null -> _deleteRequest.value = item to sender
                        movedTo != null -> relink(item, movedTo)
                        else -> forget(item, "Deleted “${item.title}”")
                    }
                },
                onFailure = {
                    if (movedTo != null) {
                        // Couldn't remove the original: keep both, and say so.
                        pendingMoveTarget = null
                        _message.value = "Copied, but the original couldn't be deleted, so it was kept."
                    } else {
                        _message.value = "Couldn't delete this file. You can remove it from the library instead."
                    }
                },
            )
        }
    }

    /** Called with Android's answer to [deleteRequest]. */
    fun onDeleteConfirmed(confirmed: Boolean) {
        val item = _deleteRequest.value?.first ?: return
        _deleteRequest.value = null
        val moved = pendingMoveTarget
        pendingMoveTarget = null
        viewModelScope.launch {
            when {
                confirmed && moved != null -> relink(item, moved)
                confirmed -> forget(item, "Deleted “${item.title}”")
                moved != null -> _message.value = "Copied. The original was kept."
            }
        }
    }

    private suspend fun relink(item: MediaItemEntity, target: Uri) {
        pendingMoveTarget = null
        dao.setUri(item.id, target.toString())
        _message.value = "Moved “${item.title}”"
    }

    private suspend fun forget(item: MediaItemEntity, note: String) {
        item.thumbnailPath?.takeIf { it.isNotEmpty() }?.let { path -> withContext(Dispatchers.IO) { runCatching { java.io.File(path).delete() } } }
        dao.delete(item.id)
        _message.value = note
    }

    fun toggleFavorite(item: MediaItemEntity) {
        viewModelScope.launch { dao.setFavorite(item.id, !item.isFavorite) }
    }

    /** Removes the entry from the library. The file itself is never deleted here. */
    fun remove(item: MediaItemEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            dao.delete(item.id)
        }
    }

    /** Adds files picked with the system document picker (Storage Access Framework). */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { uris.mapNotNull(::describe) }
            val inserted = dao.insertAll(items).count { it != -1L }
            val skipped = items.size - inserted
            _message.value = buildString {
                append(if (inserted == 1) "Added 1 file" else "Added $inserted files")
                if (skipped > 0) append(" · $skipped already in library")
            }
            makeMissingThumbnails()
        }
    }

    private fun describe(uri: Uri): MediaItemEntity? {
        val resolver = app.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = resolver.getType(uri)
        if (mime != null && !mime.startsWith("video") && !mime.startsWith("audio")) return null

        return MediaItemEntity(
            uri = uri.toString(),
            title = name?.substringBeforeLast('.')?.ifBlank { null } ?: "Untitled",
            mimeType = mime,
            sizeBytes = size,
            durationMs = readDuration(uri),
            addedAt = System.currentTimeMillis(),
        )
    }

    private fun readDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        fun factory(app: Application, dao: MediaItemDao, playlistDao: PlaylistDao): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(app, dao, playlistDao) }
        }
    }
}
