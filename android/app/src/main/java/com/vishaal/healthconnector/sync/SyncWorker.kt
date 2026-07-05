package com.vishaal.healthconnector.sync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.changes.ChangesTokenExpiredException
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.data.ChangesTokenStore
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.network.IngestApi
import com.vishaal.healthconnector.network.IngestResult
import com.vishaal.healthconnector.network.dto.toUpsertJson
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Periodic (every 15 minutes) incremental sync worker. For every record type that has
 * completed its initial backfill (i.e. has a stored changes token), pages through
 * [androidx.health.connect.client.HealthConnectClient.getChanges] and separates
 * [UpsertionChange]s (mapped to the same wire DTO as backfill) from [DeletionChange]s
 * (just the deleted record's id), then POSTs the accumulated batch to `/api/ingest`.
 *
 * If a changes token has expired, Health Connect throws [ChangesTokenExpiredException].
 * When that happens for a given record type, this worker clears that type's stored token
 * and backfill-completed flag, then enqueues a one-time [BackfillWorker] run scoped to just
 * that type (via [BackfillWorker.KEY_RECORD_TYPE_WIRE_NAME]) so it re-syncs from scratch and
 * re-establishes a fresh token, rather than failing the whole periodic sync.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsStore = SettingsStore(applicationContext)
        if (!settingsStore.isConfigured()) {
            Log.w(TAG, "Backend not configured yet; skipping sync.")
            return Result.success()
        }

        val client = HealthConnectManager.getClient(applicationContext)
        val tokenStore = ChangesTokenStore(applicationContext)
        val ingestApi = IngestApi(settingsStore)
        val deviceId = settingsStore.deviceId

        val grantedPermissions = client.permissionController.getGrantedPermissions()

        var anyFailure = false

        for (typeInfo in RecordTypes.ALL) {
            val readPermission = HealthPermission.getReadPermission(typeInfo.kClass)
            if (readPermission !in grantedPermissions) continue

            val storedToken = tokenStore.getChangesToken(typeInfo.wireName)
            if (storedToken == null) {
                // Backfill hasn't completed for this type yet; BackfillWorker owns it.
                continue
            }

            try {
                syncOneType(client, tokenStore, ingestApi, deviceId, typeInfo.wireName, storedToken)
            } catch (expired: ChangesTokenExpiredException) {
                Log.w(TAG, "Changes token expired for ${typeInfo.wireName}; re-backfilling.")
                tokenStore.clearForRetry(typeInfo.wireName)
                enqueueBoundedBackfill(applicationContext, typeInfo.wireName)
            } catch (t: Throwable) {
                Log.e(TAG, "Sync failed for ${typeInfo.wireName}", t)
                anyFailure = true
            }
        }

        tokenStore.setLastSyncTimeMillis(Instant.now().toEpochMilli())

        return if (anyFailure) Result.retry() else Result.success()
    }

    private suspend fun syncOneType(
        client: androidx.health.connect.client.HealthConnectClient,
        tokenStore: ChangesTokenStore,
        ingestApi: IngestApi,
        deviceId: String,
        wireName: String,
        initialToken: String,
    ) {
        var currentToken = initialToken
        val upserts = mutableListOf<JsonObject>()
        val deletions = mutableListOf<String>()

        do {
            val response = client.getChanges(currentToken)
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> upserts.add(change.record.toUpsertJson())
                    is DeletionChange -> deletions.add(change.recordId)
                }
            }
            currentToken = response.nextChangesToken
        } while (response.hasMore)

        if (upserts.isNotEmpty() || deletions.isNotEmpty()) {
            val result = ingestApi.ingest(deviceId = deviceId, upserts = upserts, deletions = deletions)
            if (result !is IngestResult.Success) {
                throw IllegalStateException("Ingest failed during sync of $wireName: $result")
            }
        }

        // Only persist the new token once the upload above has succeeded, so a failed
        // upload doesn't lose these changes (they'll be re-fetched from the old token next run).
        tokenStore.setChangesToken(wireName, currentToken)
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val PERIODIC_UNIQUE_WORK_NAME = "periodic_incremental_sync"
        const val EXPEDITED_UNIQUE_WORK_NAME = "manual_incremental_sync"
    }
}
