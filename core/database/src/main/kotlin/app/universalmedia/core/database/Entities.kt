package app.universalmedia.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)

@Entity(tableName = "history", indices = [Index("visitedAt"), Index("url")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/** One download job. Status values are [app.universalmedia.core.model.DownloadStatus] names. */
@Entity(tableName = "downloads", indices = [Index("status"), Index("mediaUrl"), Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val mediaUrl: String,
    val pageUrl: String?,
    val title: String,
    val fileName: String,
    /** [app.universalmedia.core.model.MediaFormat] name. */
    val format: String,
    val variantLabel: String?,
    /** SAF document URI once the file exists. */
    val destinationUri: String?,
    val status: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    // ---- v2 ----
    /** URL actually fetched (the chosen quality); [mediaUrl] identifies the source for duplicate checks. */
    val variantUrl: String? = null,
    /** Strategy-specific resume point, e.g. "segment:bytes" for HLS. */
    val resumeState: String? = null,
    val mimeType: String? = null,
    @ColumnInfo(defaultValue = "0") val audioOnly: Boolean = false,
    // ---- v3 ----
    /** Requested quality for manifests that list several (DASH). */
    val targetHeight: Int? = null,
    val targetBitrate: Long? = null,
    // ---- v4 ----
    /** Preview image (JPEG in app storage). */
    val thumbnailPath: String? = null,
    // ---- v5 ----
    /** Technical notes from the download (sources found, conversion, tracks), for troubleshooting. */
    val info: String? = null,
)

/** Anything in the user's library: downloads and imported local files. */
@Entity(
    tableName = "media_items",
    indices = [Index(value = ["uri"], unique = true), Index("addedAt"), Index("lastPlayedAt")],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** content:// URI with a persisted read permission. */
    val uri: String,
    val title: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val addedAt: Long,
    val lastPlayedAt: Long? = null,
    val playbackPositionMs: Long = 0,
    val isFavorite: Boolean = false,
    val folder: String? = null,
    val downloadId: String? = null,
    // ---- v4 ----
    /** Preview image (JPEG in app storage): a frame of the video or the song's cover art. */
    val thumbnailPath: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mediaId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val mediaId: Long,
    val position: Int,
)
