package com.vishaal.healthconnector.sync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.RecordTypeInfo
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.data.ChangesTokenStore
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.network.IngestApi
import com.vishaal.healthconnector.network.IngestResult
import com.vishaal.healthconnector.network.dto.toUpsertJson
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * One-time (or, when scoped to a single type, "bounded re-backfill") worker that pages through
 * *all historical* records for every record type that hasn't completed backfill yet, uploads
 * them to the backend in batches, and then establishes a Health Connect changes token for that
 * type so [SyncWorker] can take over incrementally from this point forward.
 *
 * Pass [KEY_RECORD_TYPE_WIRE_NAME] in the input [Data] to restrict this run to a single record
 * type (used by [SyncWorker] to re-backfill just the one type whose changes token expired,
 * rather than redoing the whole history).
 */
class BackfillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsStore = SettingsStore(applicationContext)
        if (!settingsStore.isConfigured()) {
            Log.w(TAG, "Backend not configured yet; skipping backfill.")
            return Result.success()
        }

        val client = HealthConnectManager.getClient(applicationContext)
        val tokenStore = ChangesTokenStore(applicationContext)
        val ingestApi = IngestApi(settingsStore)
        val deviceId = settingsStore.deviceId

        val grantedPermissions = client.permissionController.getGrantedPermissions()

        val restrictToWireName = inputData.getString(KEY_RECORD_TYPE_WIRE_NAME)
        val targets: List<RecordTypeInfo> = if (restrictToWireName != null) {
            listOfNotNull(RecordTypes.ALL_BY_WIRE_NAME[restrictToWireName])
        } else {
            // NOTE: List.filter{} takes a non-suspend predicate, so isBackfillCompleted (a
            // suspend fun) can't be called from inside it directly; build the list with a
            // plain suspend-friendly loop instead.
            val notYetBackfilled = mutableListOf<RecordTypeInfo>()
            for (info in RecordTypes.ALL) {
                if (!tokenStore.isBackfillCompleted(info.wireName)) {
                    notYetBackfilled.add(info)
                }
            }
            notYetBackfilled
        }

        var anyFailure = false

        for (typeInfo in targets) {
            val readPermission = HealthPermission.getReadPermission(typeInfo.kClass)
            if (readPermission !in grantedPermissions) {
                Log.i(TAG, "Skipping ${typeInfo.wireName}: permission not granted.")
                continue
            }

            try {
                backfillOneType(client, tokenStore, ingestApi, deviceId, typeInfo)
            } catch (t: Throwable) {
                Log.e(TAG, "Backfill failed for ${typeInfo.wireName}", t)
                anyFailure = true
            }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    private suspend fun backfillOneType(
        client: androidx.health.connect.client.HealthConnectClient,
        tokenStore: ChangesTokenStore,
        ingestApi: IngestApi,
        deviceId: String,
        typeInfo: RecordTypeInfo,
    ) {
        val buffer = mutableListOf<JsonObject>()
        var pageToken: String? = null

        do {
            @Suppress("UNCHECKED_CAST")
            val request = ReadRecordsRequest(
                recordType = typeInfo.kClass as kotlin.reflect.KClass<Record>,
                timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                pageSize = PAGE_SIZE,
                pageToken = pageToken,
            )
            val response = client.readRecords(request)

            buffer.addAll(response.records.map { it.toUpsertJson() })

            if (buffer.size >= BATCH_SIZE) {
                flush(ingestApi, deviceId, buffer)
            }

            pageToken = response.pageToken
        } while (pageToken != null)

        flush(ingestApi, deviceId, buffer)

        val changesToken = client.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(typeInfo.kClass)),
        )
        tokenStore.setChangesToken(typeInfo.wireName, changesToken)
        tokenStore.setBackfillCompleted(typeInfo.wireName, true)
    }

    private suspend fun flush(ingestApi: IngestApi, deviceId: String, buffer: MutableList<JsonObject>) {
        if (buffer.isEmpty()) return
        val result = ingestApi.ingest(deviceId = deviceId, upserts = buffer.toList())
        if (result !is IngestResult.Success) {
            throw IllegalStateException("Ingest failed during backfill: $result")
        }
        buffer.clear()
    }

    companion object {
        private const val TAG = "BackfillWorker"
        const val UNIQUE_WORK_NAME = "backfill_all_record_types"
        const val KEY_RECORD_TYPE_WIRE_NAME = "record_type_wire_name"
        private const val PAGE_SIZE = 1000
        private const val BATCH_SIZE = 2000
    }
}
