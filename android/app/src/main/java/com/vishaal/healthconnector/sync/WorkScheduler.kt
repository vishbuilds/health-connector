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
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
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

    /** Enqueues the 15-minute periodic write drain (server -> Health Connect), if not already enqueued. */
    fun enqueuePeriodicWriteDrain(context: Context) {
        val request = PeriodicWorkRequestBuilder<WriteWorker>(15, TimeUnit.MINUTES)
            .setConstraints(SYNC_CONSTRAINTS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WriteWorker.PERIODIC_UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Registers all workers; call once permissions are confirmed granted. */
    fun enqueueAll(context: Context) {
        enqueueInitialBackfill(context)
        enqueuePeriodicSync(context)
        enqueuePeriodicWriteDrain(context)
        enqueueDailyLogReminders(context)
    }

    /** Two local daily nudges: weight around 8am, food around 2pm. */
    fun enqueueDailyLogReminders(context: Context) {
        // Cancel the retired workout nudge so it stops firing on installs that scheduled it.
        WorkManager.getInstance(context).cancelUniqueWork(RETIRED_WORKOUT_REMINDER_WORK_NAME)
        LOG_REMINDER_SLOTS.forEach { slot ->
            val input = Data.Builder()
                .putString(LogReminderWorker.KEY_LOG_TARGET, slot.key)
                .build()
            val request = PeriodicWorkRequestBuilder<LogReminderWorker>(24, TimeUnit.HOURS)
                .setConstraints(SYNC_CONSTRAINTS)
                .setInitialDelay(delayUntil(slot.time), TimeUnit.MILLISECONDS)
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                slot.uniqueWorkName,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /**
     * "Sync now" button on the status screen: runs the incremental read sync AND the write drain
     * ASAP, so queued writes land without waiting for the next periodic window.
     */
    fun enqueueExpeditedSyncNow(context: Context) {
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(SYNC_CONSTRAINTS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.EXPEDITED_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest,
        )

        val writeRequest = OneTimeWorkRequestBuilder<WriteWorker>()
            .setConstraints(SYNC_CONSTRAINTS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WriteWorker.EXPEDITED_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            writeRequest,
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

private data class LogReminderSlot(
    val key: String,
    val time: LocalTime,
    val uniqueWorkName: String,
)

private val LOG_REMINDER_SLOTS = listOf(
    LogReminderSlot("weight", LocalTime.of(8, 0), "daily_log_weight_prompt"),
    LogReminderSlot("food", LocalTime.of(14, 0), "daily_log_food_prompt"),
)

/** Previously scheduled the 8pm workout nudge; kept only so we can cancel it on upgrade. */
private const val RETIRED_WORKOUT_REMINDER_WORK_NAME = "daily_log_workout_prompt"

private fun delayUntil(time: LocalTime): Long {
    val now = ZonedDateTime.now()
    var next = now.with(time)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(0L)
}

/** Convenience top-level wrapper so [SyncWorker] doesn't need to reference the object directly. */
internal fun enqueueBoundedBackfill(context: Context, recordTypeWireName: String) {
    WorkScheduler.enqueueBoundedBackfill(context, recordTypeWireName)
}
