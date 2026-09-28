package io.github.akarimarisa.tumblrdownloader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.akarimarisa.tumblrdownloader.model.BlogJobState

/**
 * One row per blog full-profile download job.
 *
 * Acts as the durable cursor for [io.github.akarimarisa.tumblrdownloader.model.BlogDownloadManager]:
 * [resumeLink] stores the next absolute feed-API href to fetch, so a killed
 * app can resume mid-profile from v1 (a permanent "start over" is avoided).
 */
@Entity(tableName = "blog_download_jobs")
data class BlogDownloadJobEntity(
    @PrimaryKey val jobId: String,
    val blogName: String,
    val state: String,          // BlogJobState.name()
    /** Next absolute feed-API href not yet consumed. Null when fresh/unstarted. */
    val resumeLink: String?,
    /** Upper-bound post count reported by the API (imprecise — see parser doc). */
    val totalPosts: Int?,
    val pagesCompleted: Int,
    val postsDiscovered: Int,
    val postsSkipped: Int,
    val postsNoMedia: Int,
    val mediaEnqueued: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String?
) {
    fun state(): BlogJobState =
        runCatching { BlogJobState.valueOf(state) }.getOrDefault(BlogJobState.RUNNING)
}