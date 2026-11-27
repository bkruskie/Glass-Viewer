package app.universalmedia.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        MediaItemEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 4,
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

        /** v2: download engine fields (Milestone 3). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN variantUrl TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN resumeState TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN audioOnly INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: requested quality for DASH downloads. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN targetHeight INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN targetBitrate INTEGER")
            }
        }

        /** v4: preview images. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailPath TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailPath TEXT")
            }
        }

        fun create(context: Context): UniversalMediaDatabase =
            Room.databaseBuilder(context.applicationContext, UniversalMediaDatabase::class.java, NAME)
                // Every schema change ships an explicit Migration; never destructive.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        fun inMemory(context: Context): UniversalMediaDatabase =
            Room.inMemoryDatabaseBuilder(context, UniversalMediaDatabase::class.java).build()
    }
}
