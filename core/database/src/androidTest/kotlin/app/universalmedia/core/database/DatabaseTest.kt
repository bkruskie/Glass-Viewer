package app.universalmedia.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: UniversalMediaDatabase

    @Before
    fun setUp() {
        db = UniversalMediaDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun bookmarkRoundTrip() = runBlocking {
        val dao = db.bookmarkDao()
        dao.insert(BookmarkEntity(url = "https://a.com", title = "A", createdAt = 1))
        dao.insert(BookmarkEntity(url = "https://a.com", title = "A2", createdAt = 2)) // replaces
        assertEquals(1, dao.observeAll().first().size)
        assertTrue(dao.observeIsBookmarked("https://a.com").first())
        dao.deleteByUrl("https://a.com")
        assertFalse(dao.observeIsBookmarked("https://a.com").first())
    }

    @Test
    fun libraryIgnoresDuplicateUris() = runBlocking {
        val dao = db.mediaItemDao()
        val item = MediaItemEntity(uri = "content://x/1", title = "Clip", mimeType = "video/mp4", sizeBytes = 10, durationMs = null, addedAt = 1)
        dao.insertAll(listOf(item))
        val ids = dao.insertAll(listOf(item.copy(title = "Dup")))
        assertEquals(-1L, ids.single())
        assertEquals("Clip", dao.observeAll().first().single().title)
        assertEquals(10L, dao.observeTotalBytes().first())
    }

    @Test
    fun deletingMediaCascadesToPlaylists() = runBlocking {
        val media = db.mediaItemDao()
        val playlists = db.playlistDao()
        media.insertAll(listOf(MediaItemEntity(uri = "content://x/2", title = "Song", mimeType = "audio/mpeg", sizeBytes = null, durationMs = null, addedAt = 1)))
        val mediaId = media.observeAll().first().single().id
        val playlistId = playlists.insert(PlaylistEntity(name = "Mix", createdAt = 1))
        playlists.addItem(PlaylistItemEntity(playlistId, mediaId, 0))
        assertEquals(1, playlists.observeItems(playlistId).first().size)
        media.delete(mediaId)
        assertEquals(0, playlists.observeItems(playlistId).first().size)
    }
}
