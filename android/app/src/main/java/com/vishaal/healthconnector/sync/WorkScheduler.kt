package com.vishaal.healthconnector.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Central place for enqueueing [BackfillWorker] / [SyncWorker] work requests so
 * MainActivity/Application and the workers themselves (for bounded re-backfills) all go
 * through the same unique-work names and policies.
 */
object WorkScheduler {

    private val SYNC_CONSTRAINTS = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueues the one-time full backfill, if not already enqueued/running. */
    fun enqueueInitialBackfill(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setConstraints(SYNC_CONSTRAINTS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BackfillWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Enqueues the 15-minute periodic incremental sync, if not already enqueued. */
    fun enqueuePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(SYNC_CONSTRAINTS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Registers both workers; call once permissions are confirmed granted. */
    fun enqueueAll(context: Context) {
        enqueueInitialBackfill(context)
        enqueuePeriodicSync(context)
    }

    /** "Sync now" button on the status screen: runs the same incremental sync logic ASAP. */
    fun enqueueExpeditedSyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(SYNC_CONSTRAINTS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.EXPEDITED_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Bounded re-backfill for a single record type, used by [SyncWorker] when that type's
     * changes token has expired. Re-uses [BackfillWorker] scoped to one type via input data.
     */
    fun enqueueBoundedBackfill(context: Context, recordTypeWireName: String) {
        val input = Data.Builder()
            .putString(BackfillWorker.KEY_RECORD_TYPE_WIRE_NAME, recordTypeWireName)
            .build()
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setConstraints(SYNC_CONSTRAINTS)
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${BackfillWorker.UNIQUE_WORK_NAME}__$recordTypeWireName",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/** Convenience top-level wrapper so [SyncWorker] doesn't need to reference the object directly. */
internal fun enqueueBoundedBackfill(context: Context, recordTypeWireName: String) {
    WorkScheduler.enqueueBoundedBackfill(context, recordTypeWireName)
}
