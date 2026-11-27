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

    /** Set when Android must confirm deleting a file the app didn't create. */
    private val _deleteRequest = MutableStateFlow<Pair<MediaItemEntity, android.content.IntentSender>?>(null)
    val deleteRequest: StateFlow<Pair<MediaItemEntity, android.content.IntentSender>?> = _deleteRequest.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        dao.observeAll(),
        dao.observeTotalBytes(),
        query,
        filter,
        sort,
    ) { all, bytes, q, f, order ->
        val filtered = all.filter { item ->
            val mime = item.mimeType.orEmpty()
            val matchesFilter = when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.VIDEO -> mime.startsWith("video")
                LibraryFilter.AUDIO -> mime.startsWith("audio")
                LibraryFilter.FAVORITES -> item.isFavorite
            }
            matchesFilter && (q.isBlank() || item.title.contains(q.trim(), ignoreCase = true))
        }
        val sorted = when (order) {
            LibrarySort.NEWEST -> filtered
            LibrarySort.OLDEST -> filtered.sortedBy { it.addedAt }
            LibrarySort.NAME -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes ?: 0 }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs ?: 0 }
            LibrarySort.RECENTLY_PLAYED -> filtered.sortedByDescending { it.lastPlayedAt ?: 0 }
        }
        LibraryUiState(sorted, all.size, bytes, q, f, order)
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
    fun deleteFile(item: MediaItemEntity) {
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
                    if (sender == null) forget(item, "Deleted “${item.title}”") else _deleteRequest.value = item to sender
                },
                onFailure = { _message.value = "Couldn't delete this file. You can remove it from the library instead." },
            )
        }
    }

    /** Called with Android's answer to [deleteRequest]. */
    fun onDeleteConfirmed(confirmed: Boolean) {
        val item = _deleteRequest.value?.first ?: return
        _deleteRequest.value = null
        if (confirmed) viewModelScope.launch { forget(item, "Deleted “${item.title}”") }
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
