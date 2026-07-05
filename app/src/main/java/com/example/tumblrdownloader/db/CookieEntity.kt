package com.example.tumblrdownloader.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row Room entity for Tumblr cookie store.
 *
 * All cookie data fits in one row: a JSON map of host→cookieValue,
 * plus metadata flags.
 */
@Entity(tableName = "tumblr_cookies")
data class CookieEntity(
    @PrimaryKey val id: Int = 0,   // always 0 — single row
    /** JSON object mapping host → cookieValue, e.g. {"https://www.tumblr.com":"...","https://tumblr.com":"..."} */
    val cookiesJson: String? = null,
    val savedAt: Long = 0L,
    val tipShown: Boolean = false
)
