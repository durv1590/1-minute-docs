package com.oneminutedocs.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.drafts.DraftRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic temporary-file cleanup (requirement FR-FILE-5): removes inactive operation workspaces and share copies older
 * than six hours and drafts untouched for 30 days. Managed output documents are never deleted.
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val documents: DocumentRepository,
    private val drafts: DraftRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        documents.clearTemporaryFiles(maxAgeMs = TimeUnit.HOURS.toMillis(6))
        drafts.prune(TimeUnit.DAYS.toMillis(30))
        return Result.success()
    }

    companion object {
        private const val NAME = "temp-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
