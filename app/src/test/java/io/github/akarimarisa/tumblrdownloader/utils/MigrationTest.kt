package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.akarimarisa.tumblrdownloader.db.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for migration from old SharedPreferences to Room.
 *
 * These use the real [AppDatabase] singleton so [DownloadHistoryStore] and
 * [TumblrCookieStore] see the same database.
 *
 * Run with: `./gradlew testDebugUnitTest`
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            val db = AppDatabase.getInstance(context)
            db.downloadHistoryDao().deleteAll()
            db.cookieDao().deleteAll()
        }
    }

    @After
    fun tearDown() {
        // Clean up legacy prefs to avoid cross-test pollution.
        context.getSharedPreferences("download_history_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("tumblr_cookie_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ── DownloadHistoryStore migration ─────────────────────────────────

    @Test
    fun migrate_history_from_shared_prefs() = runTest {
        seedLegacyHistory(
            "a" to "COMPLETED",
            "b" to "FAILED"
        )

        DownloadHistoryStore.migrateFromSharedPrefs(context)

        val db = AppDatabase.getInstance(context)
        val all = db.downloadHistoryDao().getAll()
        assertEquals(2, all.size)
        val byId = all.associateBy { it.id }
        assertEquals("COMPLETED", byId["a"]?.status)
        assertEquals("FAILED", byId["b"]?.status)

        // Legacy prefs must be cleared.
        val raw = legacyHistoryPrefs().getString("download_items", null)
        assertNull("legacy history should be cleared", raw)
    }

    @Test
    fun migrate_history_skips_if_room_not_empty() = runTest {
        // Seed legacy.
        seedLegacyHistory("legacy_only" to "QUEUED")

        // Pre-populate Room with a different item.
        val db = AppDatabase.getInstance(context)
        val existing = io.github.akarimarisa.tumblrdownloader.db.DownloadHistoryEntity(
            id = "room_item",
            sourceUrl = "https://tumblr.com/r",
            mediaUrl = "https://media.tumblr.com/r.jpg",
            title = "Room item",
            type = "IMAGE",
            status = "COMPLETED",
            progress = 100,
            errorMessage = null,
            retryCount = 0,
            maxRetries = 3,
            createdAt = 1000L,
            downloadedBytes = 0L,
            downloadFileUri = null
        )
        db.downloadHistoryDao().insertAll(listOf(existing))

        // Run migration — should be no-op since Room already has data.
        DownloadHistoryStore.migrateFromSharedPrefs(context)

        val all = db.downloadHistoryDao().getAll()
        assertEquals(1, all.size)
        assertEquals("room_item", all[0].id)
    }

    @Test
    fun migrate_history_empty_legacy_is_noop() = runTest {
        legacyHistoryPrefs().edit().clear().commit()
        DownloadHistoryStore.migrateFromSharedPrefs(context)

        val db = AppDatabase.getInstance(context)
        assertTrue(db.downloadHistoryDao().getAll().isEmpty())
    }

    // ── TumblrCookieStore migration ────────────────────────────────────

    @Test
    fun migrate_cookies_from_shared_prefs() = runTest {
        legacyCookiePrefs().edit()
            .putString("saved_tumblr_cookies", """{"https://www.tumblr.com":"abc=123"}""")
            .putLong("saved_tumblr_cookies_at", 500L)
            .putBoolean("cookie_tip_shown", true)
            .commit()

        TumblrCookieStore.migrateFromSharedPrefs(context)

        val db = AppDatabase.getInstance(context)
        val row = db.cookieDao().get()
        assertNotNull("cookie row should exist after migration", row)
        assertEquals("""{"https://www.tumblr.com":"abc=123"}""", row!!.cookiesJson)
        assertEquals(500L, row.savedAt)
        assertTrue(row.tipShown)

        // Legacy cleared.
        val raw = legacyCookiePrefs().getString("saved_tumblr_cookies", null)
        assertNull("legacy cookies should be cleared", raw)
    }

    @Test
    fun migrate_cookies_skips_if_room_has_data() = runTest {
        legacyCookiePrefs().edit()
            .putString("saved_tumblr_cookies", """{"host":"old"}""")
            .commit()

        // Pre-populate Room.
        val db = AppDatabase.getInstance(context)
        db.cookieDao().upsert(
            io.github.akarimarisa.tumblrdownloader.db.CookieEntity(
                cookiesJson = """{"host":"new"}""",
                savedAt = 999L
            )
        )

        TumblrCookieStore.migrateFromSharedPrefs(context)

        val row = db.cookieDao().get()
        assertEquals("Room data should survive", """{"host":"new"}""", row!!.cookiesJson)
        assertEquals(999L, row.savedAt)
    }

    @Test
    fun migrate_cookies_empty_legacy_is_noop() = runTest {
        legacyCookiePrefs().edit().clear().commit()
        TumblrCookieStore.migrateFromSharedPrefs(context)

        val db = AppDatabase.getInstance(context)
        assertNull(db.cookieDao().get())
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun legacyHistoryPrefs() =
        context.getSharedPreferences("download_history_store", Context.MODE_PRIVATE)

    private fun legacyCookiePrefs() =
        context.getSharedPreferences("tumblr_cookie_store", Context.MODE_PRIVATE)

    private fun seedLegacyHistory(vararg pairs: Pair<String, String>) {
        val arr = JSONArray()
        for ((id, status) in pairs) {
            arr.put(encodeLegacyItem(id, status))
        }
        legacyHistoryPrefs().edit().putString("download_items", arr.toString()).commit()
    }

    private fun encodeLegacyItem(id: String, status: String): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("sourceUrl", "https://www.tumblr.com/blog/$id")
            put("mediaUrl", "https://media.tumblr.com/$id.jpg")
            put("title", "Item $id")
            put("type", "IMAGE")
            put("status", status)
            put("progress", 0)
            put("retryCount", 0)
            put("maxRetries", 3)
            put("createdAt", System.currentTimeMillis())
            put("downloadedBytes", 0L)
        }
    }
}
