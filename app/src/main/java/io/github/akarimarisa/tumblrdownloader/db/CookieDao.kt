package io.github.akarimarisa.tumblrdownloader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [CookieEntity].
 */
@Dao
interface CookieDao {

    @Query("SELECT * FROM tumblr_cookies WHERE id = 0")
    suspend fun get(): CookieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cookie: CookieEntity)

    @Query("SELECT COUNT(*) FROM tumblr_cookies")
    suspend fun count(): Int

    @Query("DELETE FROM tumblr_cookies")
    suspend fun deleteAll()
}
