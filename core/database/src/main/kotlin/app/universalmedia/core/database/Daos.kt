package app.universalmedia.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeIsBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE url = :url ORDER BY visitedAt DESC LIMIT 1")
    suspend fun latestFor(url: String): HistoryEntity?

    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    @Query("UPDATE history SET title = :title, visitedAt = :visitedAt WHERE id = :id")
    suspend fun touch(id: Long, title: String, visitedAt: Long)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE mediaUrl = :mediaUrl AND status != 'CANCELLED'")
    suspend fun countForUrl(mediaUrl: String): Int

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloaded, totalBytes = :total, " +
            "errorMessage = :error, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(id: String, status: String, downloaded: Long, total: Long?, error: String?, updatedAt: Long)

    @Query("UPDATE downloads SET status = :status, errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, updatedAt: Long)

    @Query("UPDATE downloads SET resumeState = :state, downloadedBytes = :downloaded, totalBytes = :total, updatedAt = :updatedAt WHERE id = :id")
    suspend fun saveResume(id: String, state: String?, downloaded: Long, total: Long?, updatedAt: Long)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', destinationUri = :uri, downloadedBytes = :bytes, totalBytes = :bytes, " +
            "fileName = :fileName, mimeType = :mimeType, errorMessage = NULL, resumeState = NULL, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun markCompleted(id: String, uri: String, fileName: String, mimeType: String, bytes: Long, updatedAt: Long)

    @Query("UPDATE downloads SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: String, path: String?)

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun active(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()
}

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query(
        "SELECT * FROM media_items WHERE title LIKE '%' || :query || '%' ORDER BY addedAt DESC",
    )
    fun search(query: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_items")
    fun observeTotalBytes(): Flow<Long>

    /** Ignores files that are already in the library (unique uri). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MediaItemEntity>): List<Long>

    @Query("UPDATE media_items SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: Long, path: String?)

    @Query("SELECT * FROM media_items WHERE thumbnailPath IS NULL LIMIT :limit")
    suspend fun withoutThumbnail(limit: Int): List<MediaItemEntity>

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE media_items SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE media_items SET playbackPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updatePlayback(id: Long, positionMs: Long, playedAt: Long)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(item: PlaylistItemEntity)

    @Query(
        "SELECT m.* FROM media_items m INNER JOIN playlist_items p ON p.mediaId = m.id " +
            "WHERE p.playlistId = :playlistId ORDER BY p.position",
    )
    fun observeItems(playlistId: Long): Flow<List<MediaItemEntity>>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}
