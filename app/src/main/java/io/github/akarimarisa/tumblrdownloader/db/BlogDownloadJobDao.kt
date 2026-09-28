package io.github.akarimarisa.tumblrdownloader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** DAO for [BlogDownloadJobEntity]. */
@Dao
interface BlogDownloadJobDao {

    @Query("SELECT * FROM blog_download_jobs ORDER BY createdAt DESC")
    suspend fun getAll(): List<BlogDownloadJobEntity>

    @Query("SELECT * FROM blog_download_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun get(jobId: String): BlogDownloadJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: BlogDownloadJobEntity)

    @Query("DELETE FROM blog_download_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM blog_download_jobs")
    suspend fun deleteAll()
}