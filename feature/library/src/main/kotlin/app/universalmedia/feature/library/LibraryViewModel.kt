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

data class LibraryUiState(
    val items: List<MediaItemEntity> = emptyList(),
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
)

class LibraryViewModel(
    private val app: Application,
    private val dao: MediaItemDao,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        dao.observeAll(),
        dao.observeTotalBytes(),
        query,
        filter,
    ) { all, bytes, q, f ->
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
        LibraryUiState(filtered, all.size, bytes, q, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) { query.value = value }

    fun setFilter(value: LibraryFilter) { filter.value = value }

    fun consumeMessage() { _message.value = null }

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
