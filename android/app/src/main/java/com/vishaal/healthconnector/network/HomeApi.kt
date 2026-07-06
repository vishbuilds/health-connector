package com.vishaal.healthconnector.network

import com.vishaal.healthconnector.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One headline metric on the Home screen: today's value against its goal, plus 0..1 progress. */
data class HomeMetric(
    val value: Double,
    val goal: Double,
    val progress: Float,
    val unit: String?,
)

/** The full Home payload from GET /api/home: the four rings + deterministic insight strings. */
data class HomeSummary(
    val date: String?,
    val steps: HomeMetric,
    val sleep: HomeMetric,
    val hydration: HomeMetric,
    val activeCalories: HomeMetric,
    val insights: List<String>,
)

sealed class HomeResult {
    data class Success(val summary: HomeSummary) : HomeResult()
    data class HttpError(val httpCode: Int, val body: String?) : HomeResult()
    data class NetworkError(val cause: IOException) : HomeResult()
    object NotConfigured : HomeResult()
}

/**
 * Read-only client for the Home screen data. Mirrors [PendingWritesApi]: same OkHttp setup, the
 * same backend URL + bearer secret from [SettingsStore], and manual kotlinx.serialization parsing.
 * All insight/goal/progress computation lives on the server; this just fetches and deserializes.
 */
class HomeApi(private val settingsStore: SettingsStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchHome(): HomeResult {
        val backendUrl = settingsStore.backendUrl
        val bearerToken = settingsStore.bearerToken
        if (backendUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) {
            return HomeResult.NotConfigured
        }

        val request = Request.Builder()
            .url("$backendUrl/api/home")
            .addHeader("Authorization", "Bearer $bearerToken")
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use HomeResult.HttpError(response.code, response.body?.string())
                    }
                    val body = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    HomeResult.Success(root.toHomeSummary())
                }
            } catch (e: IOException) {
                HomeResult.NetworkError(e)
            }
        }
    }

    private fun JsonObject.toHomeSummary(): HomeSummary {
        val metrics = this["metrics"]?.jsonObject ?: JsonObject(emptyMap())
        val insights = this["insights"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        return HomeSummary(
            date = this["date"]?.jsonPrimitive?.contentOrNull,
            steps = metrics["steps"]!!.jsonObject.toMetric(),
            sleep = metrics["sleep"]!!.jsonObject.toMetric(),
            hydration = metrics["hydration"]!!.jsonObject.toMetric(),
            activeCalories = metrics["activeCalories"]!!.jsonObject.toMetric(),
            insights = insights,
        )
    }

    private fun JsonObject.toMetric(): HomeMetric = HomeMetric(
        value = this["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        goal = this["goal"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        progress = (this["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toFloat().coerceIn(0f, 1f),
        unit = this["unit"]?.jsonPrimitive?.contentOrNull,
    )
}
