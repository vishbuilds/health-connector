package com.vishaal.healthconnector.network

import com.vishaal.healthconnector.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** One record Claude has asked to write into Health Connect, as delivered by GET /api/writes. */
data class PendingWrite(
    val id: String,
    val recordType: String,
    val startTime: String,
    val endTime: String?,
    val zoneOffset: String?,
    val data: JsonObject,
)

/** Result of a successful write (its server row id + the assigned Health Connect metadata id). */
data class WriteApplied(val id: String, val healthConnectId: String?)

/** A write that could not be applied on the phone, with a short reason for the server log. */
data class WriteFailed(val id: String, val error: String)

sealed class FetchWritesResult {
    data class Success(val writes: List<PendingWrite>) : FetchWritesResult()
    data class HttpError(val httpCode: Int, val body: String?) : FetchWritesResult()
    data class NetworkError(val cause: IOException) : FetchWritesResult()
    object NotConfigured : FetchWritesResult()
}

/**
 * Client for the reverse (server -> phone) write channel:
 *  - [fetchPending] drains queued writes from `GET /api/writes`.
 *  - [ack] reports applied/failed outcomes to `POST /api/writes/ack`.
 *
 * Uses the same backend URL + bearer secret as [IngestApi] (from [SettingsStore]).
 */
class PendingWritesApi(private val settingsStore: SettingsStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchPending(): FetchWritesResult {
        val backendUrl = settingsStore.backendUrl
        val bearerToken = settingsStore.bearerToken
        if (backendUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) {
            return FetchWritesResult.NotConfigured
        }

        val request = Request.Builder()
            .url("$backendUrl/api/writes")
            .addHeader("Authorization", "Bearer $bearerToken")
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use FetchWritesResult.HttpError(response.code, response.body?.string())
                    }
                    val body = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    val writes = root["writes"]?.jsonArray.orEmptyArray().map { it.jsonObject.toPendingWrite() }
                    FetchWritesResult.Success(writes)
                }
            } catch (e: IOException) {
                FetchWritesResult.NetworkError(e)
            }
        }
    }

    /** Returns true if the ack POST succeeded (HTTP 2xx). Errors are swallowed intentionally: an
     *  ack that fails simply means the same rows are re-fetched and re-applied next run. */
    suspend fun ack(applied: List<WriteApplied>, failed: List<WriteFailed>): Boolean {
        if (applied.isEmpty() && failed.isEmpty()) return true

        val backendUrl = settingsStore.backendUrl
        val bearerToken = settingsStore.bearerToken
        if (backendUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) return false

        val body = buildJsonObject {
            put(
                "applied",
                buildJsonArray {
                    applied.forEach { a ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(a.id))
                                a.healthConnectId?.let { put("healthConnectId", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
            put(
                "failed",
                buildJsonArray {
                    failed.forEach { f ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(f.id))
                                put("error", JsonPrimitive(f.error.take(1000)))
                            },
                        )
                    }
                },
            )
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$backendUrl/api/writes/ack")
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: IOException) {
                false
            }
        }
    }

    private fun JsonObject.toPendingWrite(): PendingWrite = PendingWrite(
        id = this["id"]!!.jsonPrimitive.content,
        recordType = this["recordType"]!!.jsonPrimitive.content,
        startTime = this["startTime"]!!.jsonPrimitive.content,
        endTime = this["endTime"]?.jsonPrimitive?.contentOrNull,
        zoneOffset = this["zoneOffset"]?.jsonPrimitive?.contentOrNull,
        data = this["data"]?.jsonObject ?: JsonObject(emptyMap()),
    )

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
}
