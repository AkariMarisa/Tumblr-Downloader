package io.github.akarimarisa.tumblrdownloader.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.model.DownloadItem
import io.github.akarimarisa.tumblrdownloader.model.DownloadStatus
import io.github.akarimarisa.tumblrdownloader.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Unit tests for [DownloadHistoryDao] via an in-memory Room database.
 *
 * Run with: `./gradlew testDebugUnitTest`
 */
@RunWith(RobolectricTestRunner::class)
class DownloadHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DownloadHistoryDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.downloadHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun empty_on_fresh_database() = runTest {
        val all = dao.getAll()
        assertTrue("fresh DB should be empty", all.isEmpty())
    }

    @Test
    fun insert_and_read_back() = runTest {
        val item = DownloadHistoryEntity.fromItem(
            DownloadItem(
                sourceUrl = "https://www.tumblr.com/test/123",
                mediaUrl = "https://media.tumblr.com/test.jpg",
                title = "Test Image",
                type = MediaType.IMAGE,
                status = DownloadStatus.COMPLETED,
                progress = 100
            )
        )

        dao.insertAll(listOf(item))
        val all = dao.getAll()
        assertEquals("should have 1 item", 1, all.size)
        assertEquals("id should match", item.id, all[0].id)
        assertEquals("status should match", DownloadStatus.COMPLETED.name, all[0].status)
    }

    @Test
    fun replaceAll_atomicity() = runTest {
        val a = entity("a", "https://media.tumblr.com/a.jpg")
        val b = entity("b", "https://media.tumblr.com/b.jpg")
        dao.insertAll(listOf(a, b))

        // Replace with a single item
        val c = entity("c", "https://media.tumblr.com/c.jpg")
        dao.replaceAll(listOf(c))

        val all = dao.getAll()
        assertEquals("should have 1 item after replace", 1, all.size)
        assertEquals("remaining item should be c", "c", all[0].id)
    }

    @Test
    fun deleteAll_clears_everything() = runTest {
        dao.insertAll(listOf(entity("x"), entity("y")))
        dao.deleteAll()

        assertTrue("should be empty after deleteAll", dao.getAll().isEmpty())
    }

    @Test
    fun multiple_items_sorted_by_createdAt_desc() = runTest {
        val old = entity("old", createdAt = 1000L)
        val mid = entity("mid", createdAt = 2000L)
        val new = entity("new", createdAt = 3000L)
        dao.insertAll(listOf(old, new, mid)) // intentionally jumbled

        val all = dao.getAll()
        assertEquals(3, all.size)
        assertEquals("new should be first", "new", all[0].id)
        assertEquals("mid should be second", "mid", all[1].id)
        assertEquals("old should be last", "old", all[2].id)
    }

    @Test
    fun toDownloadItem_roundtrip() = runTest {
        val original = DownloadItem(
            sourceUrl = "https://www.tumblr.com/blog/456",
            mediaUrl = "https://media.tumblr.com/photo.jpg",
            title = "My Photo",
            type = MediaType.IMAGE,
            status = DownloadStatus.DOWNLOADING,
            progress = 42,
            errorMessage = null,
            retryCount = 1,
            maxRetries = 3,
            createdAt = 5000L,
            downloadedBytes = 12345L,
            downloadFileUri = null
        )

        val entity = DownloadHistoryEntity.fromItem(original)
        val roundtripped = entity.toDownloadItem()

        assertEquals(original.id, roundtripped.id)
        assertEquals(original.sourceUrl, roundtripped.sourceUrl)
        assertEquals(original.mediaUrl, roundtripped.mediaUrl)
        assertEquals(original.title, roundtripped.title)
        assertEquals(original.type, roundtripped.type)
        assertEquals(original.status, roundtripped.status)
        assertEquals(original.progress, roundtripped.progress)
        assertEquals(original.retryCount, roundtripped.retryCount)
        assertEquals(original.createdAt, roundtripped.createdAt)
        assertEquals(original.downloadedBytes, roundtripped.downloadedBytes)
    }

    @Test
    fun errorMessage_and_fileUri_null_roundtrip() = runTest {
        val item = DownloadItem(
            sourceUrl = "https://tumblr.com/x",
            mediaUrl = "https://media.tumblr.com/x.jpg",
            title = "X",
            type = MediaType.VIDEO,
            status = DownloadStatus.FAILED,
            errorMessage = "Network error",
            downloadFileUri = "content://media/external/file/123"
        )

        val entity = DownloadHistoryEntity.fromItem(item)
        val rt = entity.toDownloadItem()

        assertEquals("Network error", rt.errorMessage)
        assertEquals("content://media/external/file/123", rt.downloadFileUri)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun entity(
        id: String,
        mediaUrl: String = "https://media.tumblr.com/$id.jpg",
        createdAt: Long = System.currentTimeMillis()
    ) = DownloadHistoryEntity(
        id = id,
        sourceUrl = "https://www.tumblr.com/blog/$id",
        mediaUrl = mediaUrl,
        title = "Item $id",
        type = MediaType.IMAGE.name,
        status = DownloadStatus.QUEUED.name,
        progress = 0,
        errorMessage = null,
        retryCount = 0,
        maxRetries = 3,
        createdAt = createdAt,
        downloadedBytes = 0L,
        downloadFileUri = null
    )
}
