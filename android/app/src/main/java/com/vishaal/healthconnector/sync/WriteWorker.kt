package com.vishaal.healthconnector.sync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.WritableRecordTypes
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.network.FetchWritesResult
import com.vishaal.healthconnector.network.PendingWrite
import com.vishaal.healthconnector.network.PendingWritesApi
import com.vishaal.healthconnector.network.WriteApplied
import com.vishaal.healthconnector.network.WriteFailed
import com.vishaal.healthconnector.network.dto.RecordWriter

/**
 * Reverse-channel worker: drains records Claude has queued via the MCP `write_record` tool and
 * writes them into Health Connect. Runs on the same 15-minute cadence as [SyncWorker] (and on
 * demand via "Sync now").
 *
 * Flow per run:
 *  1. `GET /api/writes` for pending writes.
 *  2. For each write whose WRITE permission is granted, map it to a [androidx.health.connect.client.records.Record]
 *     (via [RecordWriter], idempotent by clientRecordId) and insert it, one at a time so a single
 *     bad record fails only itself.
 *  3. `POST /api/writes/ack` reporting applied (with the assigned Health Connect id) and failed rows.
 *
 * Writes whose permission isn't granted are left untouched (not acked), so they apply once the
 * user grants that permission rather than being permanently marked failed. Anything applied here
 * flows back through [SyncWorker]'s change feed, so it also lands in the backend's read store.
 */
class WriteWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsStore = SettingsStore(applicationContext)
        if (!settingsStore.isConfigured()) {
            Log.w(TAG, "Backend not configured yet; skipping write drain.")
            return Result.success()
        }

        val api = PendingWritesApi(settingsStore)
        val fetch = api.fetchPending()
        val pending = when (fetch) {
            is FetchWritesResult.Success -> fetch.writes
            is FetchWritesResult.NotConfigured -> return Result.success()
            is FetchWritesResult.NetworkError -> {
                Log.w(TAG, "Network error fetching writes; will retry.", fetch.cause)
                return Result.retry()
            }
            is FetchWritesResult.HttpError -> {
                Log.e(TAG, "HTTP ${fetch.httpCode} fetching writes: ${fetch.body}")
                return Result.retry()
            }
        }

        if (pending.isEmpty()) return Result.success()

        val client = HealthConnectManager.getClient(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()

        val applied = mutableListOf<WriteApplied>()
        val failed = mutableListOf<WriteFailed>()

        for (write in pending) {
            val typeInfo = WritableRecordTypes.ALL.firstOrNull { it.wireName == write.recordType }
            if (typeInfo == null) {
                // Server allowlist and this app's allowlist have drifted; report it rather than loop.
                failed.add(WriteFailed(write.id, "record type not writable on this device: ${write.recordType}"))
                continue
            }

            val writePermission = HealthPermission.getWritePermission(typeInfo.kClass)
            if (writePermission !in granted) {
                // Leave pending (don't ack) so it applies once the user grants this permission.
                Log.i(TAG, "Skipping ${write.recordType} write ${write.id}: WRITE permission not granted yet.")
                continue
            }

            try {
                val healthConnectId = insertOne(client, write)
                applied.add(WriteApplied(write.id, healthConnectId))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to apply write ${write.id} (${write.recordType})", t)
                failed.add(WriteFailed(write.id, t.message ?: t.javaClass.simpleName))
            }
        }

        api.ack(applied = applied, failed = failed)

        return Result.success()
    }

    private suspend fun insertOne(
        client: androidx.health.connect.client.HealthConnectClient,
        write: PendingWrite,
    ): String? {
        val record = RecordWriter.toRecord(write)
        val response = client.insertRecords(listOf(record))
        return response.recordIdsList.firstOrNull()
    }

    companion object {
        private const val TAG = "WriteWorker"
        const val PERIODIC_UNIQUE_WORK_NAME = "periodic_write_drain"
        const val EXPEDITED_UNIQUE_WORK_NAME = "manual_write_drain"
    }
}
