package com.vishaal.healthconnector.network

import com.vishaal.healthconnector.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

sealed class IngestResult {
    data class Success(val httpCode: Int) : IngestResult()
    data class HttpError(val httpCode: Int, val body: String?) : IngestResult()
    data class NetworkError(val cause: IOException) : IngestResult()
    object NotConfigured : IngestResult()
}

/**
 * POSTs batches of upserted/deleted records to `$backendUrl/api/ingest`.
 *
 * Request body shape:
 * ```
 * {
 *   "deviceId": "...",
 *   "upserts": [ {"id": ..., "recordType": ..., "startTime": ..., ..., "data": {...}}, ... ],
 *   "deletions": ["id1", "id2", ...]
 * }
 * ```
 */
class IngestApi(private val settingsStore: SettingsStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ingest(
        deviceId: String,
        upserts: List<JsonObject> = emptyList(),
        deletions: List<String> = emptyList(),
    ): IngestResult {
        if (upserts.isEmpty() && deletions.isEmpty()) {
            return IngestResult.Success(httpCode = 204)
        }

        val backendUrl = settingsStore.backendUrl
        val bearerToken = settingsStore.bearerToken
        if (backendUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) {
            return IngestResult.NotConfigured
        }

        val body = buildJsonObject {
            put("deviceId", JsonPrimitive(deviceId))
            put("upserts", JsonArray(upserts))
            put("deletions", JsonArray(deletions.map { JsonPrimitive(it) }))
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$backendUrl/api/ingest")
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        IngestResult.Success(response.code)
                    } else {
                        IngestResult.HttpError(response.code, response.body?.string())
                    }
                }
            } catch (e: IOException) {
                IngestResult.NetworkError(e)
            }
        }
    }
}
