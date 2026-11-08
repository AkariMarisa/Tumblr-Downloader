package io.github.akarimarisa.tumblrdownloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for Tumblr Downloader.
 *
 * Handles:
 * - Download history (atomic bulk-replace, replacing fragile JSON-in-SP)
 * - Blog download jobs (durable cursor for the batch downloader)
 * - Cookie store (single-row, replacing JSON-in-SP)
 */
@Database(
    entities = [
        DownloadHistoryEntity::class,
        BlogDownloadJobEntity::class,
        CookieEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun blogDownloadJobDao(): BlogDownloadJobDao
    abstract fun cookieDao(): CookieDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2 (batch download): (see MIGRATION_1_2 below)
         * Replaces the old destructive fallback: real users' history must survive.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `batchId` TEXT")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `blogName` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blog_download_jobs` (" +
                        "`jobId` TEXT NOT NULL, " +
                        "`blogName` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`resumeLink` TEXT, " +
                        "`totalPosts` INTEGER, " +
                        "`pagesCompleted` INTEGER NOT NULL, " +
                        "`postsDiscovered` INTEGER NOT NULL, " +
                        "`postsSkipped` INTEGER NOT NULL, " +
                        "`postsNoMedia` INTEGER NOT NULL, " +
                        "`mediaEnqueued` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`errorCode` TEXT, " +
                        "PRIMARY KEY(`jobId`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tumblr_downloader.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
