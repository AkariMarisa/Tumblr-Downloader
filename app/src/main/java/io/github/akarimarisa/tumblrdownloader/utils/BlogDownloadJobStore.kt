package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import io.github.akarimarisa.tumblrdownloader.db.AppDatabase
import io.github.akarimarisa.tumblrdownloader.db.BlogDownloadJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistence for [BlogDownloadJobEntity] — the durable cursor of the batch downloader. */
object BlogDownloadJobStore {

    suspend fun loadAll(context: Context): List<BlogDownloadJobEntity> = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).blogDownloadJobDao().getAll()
    }

    suspend fun load(context: Context, jobId: String): BlogDownloadJobEntity? = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).blogDownloadJobDao().get(jobId)
    }

    suspend fun upsert(context: Context, job: BlogDownloadJobEntity) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).blogDownloadJobDao().upsert(job)
    }

    suspend fun delete(context: Context, jobId: String) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).blogDownloadJobDao().delete(jobId)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).blogDownloadJobDao().deleteAll()
    }
}