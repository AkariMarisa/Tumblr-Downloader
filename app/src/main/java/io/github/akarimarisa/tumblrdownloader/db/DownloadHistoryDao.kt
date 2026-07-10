package io.github.akarimarisa.tumblrdownloader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO for [DownloadHistoryEntity].
 */
@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DownloadHistoryEntity>)

    @Query("DELETE FROM download_history")
    suspend fun deleteAll()

    /** Atomically replace the entire history — no partial-write risk. */
    @Transaction
    suspend fun replaceAll(items: List<DownloadHistoryEntity>) {
        deleteAll()
        insertAll(items)
    }
}
