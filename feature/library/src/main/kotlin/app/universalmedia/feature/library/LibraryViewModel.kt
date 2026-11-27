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
) : ViewModel() {

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
        fun factory(app: Application, dao: MediaItemDao): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(app, dao) }
        }
    }
}
