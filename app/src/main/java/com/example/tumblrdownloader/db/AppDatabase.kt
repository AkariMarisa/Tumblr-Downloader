package com.example.tumblrdownloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Tumblr Downloader.
 *
 * Handles:
 * - Download history (atomic bulk-replace, replacing fragile JSON-in-SP)
 * - Cookie store (single-row, replacing JSON-in-SP)
 */
@Database(
    entities = [DownloadHistoryEntity::class, CookieEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun cookieDao(): CookieDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tumblr_downloader.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
