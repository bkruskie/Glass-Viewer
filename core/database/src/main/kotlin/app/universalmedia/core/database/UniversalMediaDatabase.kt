package app.universalmedia.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        MediaItemEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UniversalMediaDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val NAME = "universal_media.db"

        fun create(context: Context): UniversalMediaDatabase =
            Room.databaseBuilder(context.applicationContext, UniversalMediaDatabase::class.java, NAME)
                // Future schema changes ship explicit Migration objects; never destructive.
                .build()

        fun inMemory(context: Context): UniversalMediaDatabase =
            Room.inMemoryDatabaseBuilder(context, UniversalMediaDatabase::class.java).build()
    }
}
