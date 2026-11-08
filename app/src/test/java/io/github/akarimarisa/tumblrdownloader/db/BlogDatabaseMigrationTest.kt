package io.github.akarimarisa.tumblrdownloader.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Exercises the real v1→v2 Room migration ([AppDatabase.MIGRATION_1_2]):
 * a v1 database file is created by hand, seeded with history rows, then
 * reopened through Room. Room re-validates the whole schema afterwards, so a
 * wrong `blog_download_jobs` DDL or a missed `ALTER TABLE` fails the test.
 */
@RunWith(RobolectricTestRunner::class)
class BlogDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration_v1_to_v2_test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    // The v1 download_history DDL — exactly what the original schema created.
    private val v1DownloadHistoryDDL =
        "CREATE TABLE `download_history` (" +
            "`id` TEXT NOT NULL, " +
            "`sourceUrl` TEXT NOT NULL, " +
            "`mediaUrl` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`progress` INTEGER NOT NULL, " +
            "`errorMessage` TEXT, " +
            "`retryCount` INTEGER NOT NULL, " +
            "`maxRetries` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`downloadedBytes` INTEGER NOT NULL, " +
            "`downloadFileUri` TEXT, " +
            "PRIMARY KEY(`id`))"

    private val v1CookiesDDL =
        "CREATE TABLE `tumblr_cookies` (" +
            "`id` INTEGER NOT NULL, " +
            "`cookiesJson` TEXT, " +
            "`savedAt` INTEGER NOT NULL, " +
            "`tipShown` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    @Test
    fun migration_1_to_2_preserves_history_and_creates_jobs_table() = runTest {
        // Create and seed a v1 database.
        val dbFile = File(context.getDatabasePath(dbName).path)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL(v1DownloadHistoryDDL)
            raw.execSQL(v1CookiesDDL)
            raw.execSQL(
                "INSERT INTO `download_history` " +
                    "(`id`,`sourceUrl`,`mediaUrl`,`title`,`type`,`status`,`progress`," +
                    "`errorMessage`,`retryCount`,`maxRetries`,`createdAt`,`downloadedBytes`,`downloadFileUri`) " +
                    "VALUES ('v1_item','https://www.tumblr.com/post/1','https://64.media.tumblr.com/x.jpg'," +
                    "'Item','IMAGE','COMPLETED',100,NULL,0,3,1000,0,NULL)"
            )
            raw.execSQL("PRAGMA user_version = 1")
        }

        // Open through Room — runs MIGRATION_1_2 and validates the schema.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        try {
            val rows = room.downloadHistoryDao().getAll()
            assertEquals(1, rows.size)
            assertEquals("v1_item", rows[0].id)
            assertEquals("COMPLETED", rows[0].status)
            // New columns exist and default to NULL for migrated history.
            assertNull("batchId should be null for migrated rows", rows[0].batchId)
            assertNull("blogName should be null for migrated rows", rows[0].blogName)

            // Room validates that the new table's shape matches the entity.
            val jobs = room.blogDownloadJobDao().getAll()
            assertNotNull(jobs)
            assertTrue(jobs.isEmpty())

            // Cookie table untouched.
            assertNull(room.cookieDao().get())
        } finally {
            room.close()
        }
    }

    @Test
    fun migration_runs_when_database_has_no_cookie_row() = runTest {
        // v1 apps could have an empty cookie table — must not break migration.
        val dbFile = File(context.getDatabasePath(dbName).path)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL(v1DownloadHistoryDDL)
            raw.execSQL(v1CookiesDDL)
            raw.execSQL("PRAGMA user_version = 1")
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        try {
            assertTrue(room.downloadHistoryDao().getAll().isEmpty())
            assertTrue(room.blogDownloadJobDao().getAll().isEmpty())
        } finally {
            room.close()
        }
    }
}
