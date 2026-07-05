package com.example.tumblrdownloader.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CookieDao] via an in-memory Room database.
 *
 * Run with: `./gradlew testDebugUnitTest`
 */
@RunWith(RobolectricTestRunner::class)
class CookieDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CookieDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.cookieDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fresh_database_returns_null() = runTest {
        assertNull("no cookie row initially", dao.get())
    }

    @Test
    fun upsert_and_read() = runTest {
        dao.upsert(CookieEntity(cookiesJson = """{"host":"value"}""", savedAt = 100L, tipShown = true))

        val row = dao.get()
        assertNotNull(row)
        assertEquals("""{"host":"value"}""", row!!.cookiesJson)
        assertEquals(100L, row.savedAt)
        assertTrue(row.tipShown)
    }

    @Test
    fun upsert_replaces_existing_row() = runTest {
        dao.upsert(CookieEntity(cookiesJson = """{"a":"1"}"""))
        dao.upsert(CookieEntity(cookiesJson = """{"b":"2"}""", tipShown = true))

        assertEquals(1, dao.count()) // only one row with id=0
        val row = dao.get()
        assertEquals("""{"b":"2"}""", row!!.cookiesJson)
        assertTrue(row.tipShown)
    }

    @Test
    fun deleteAll_clears_row() = runTest {
        dao.upsert(CookieEntity(cookiesJson = """{"x":"y"}"""))
        dao.deleteAll()
        assertNull(dao.get())
        assertEquals(0, dao.count())
    }

    @Test
    fun tipShown_defaults_to_false() = runTest {
        dao.upsert(CookieEntity(cookiesJson = null))
        val row = dao.get()
        assertFalse("tipShown defaults to false", row!!.tipShown)
        assertEquals(0L, row.savedAt)
    }

    @Test
    fun multiple_upserts_preserve_single_row() = runTest {
        repeat(5) { i ->
            dao.upsert(CookieEntity(cookiesJson = """{"i":$i}"""))
        }
        assertEquals("only one row", 1, dao.count())
        val row = dao.get()
        assertEquals("latest value", """{"i":4}""", row!!.cookiesJson)
    }
}
