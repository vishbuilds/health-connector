package com.vishaal.healthconnector.network

import com.vishaal.healthconnector.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Whole-day headline status: a 0..100 score with a short label and status key (drives color). */
data class HomeDayStatus(
    val score: Int,
    val label: String,
    val status: String,
)

/** One daily bodyweight point for the trend sparkline. */
data class WeightPoint(
    val date: String,
    val value: Double,
)

/**
 * The bodyweight-trend hero — the recomposition scoreboard. [current] is the trailing 7-day average
 * (null when there's no weigh-in yet); [changePerWeek] is the fitted slope in kg/week (negative =
 * losing). [series] is oldest → newest for the sparkline.
 */
data class HomeWeight(
    val score: Int,
    val label: String,
    val status: String,
    val current: Double?,
    val unit: String,
    val target: Double?,
    val toGo: Double?,
    val changePerWeek: Double?,
    val bodyFatPct: Double?,
    val series: List<WeightPoint>,
    val detail: String,
    val action: String,
)

/**
 * A supporting lever tile (calorie balance / protein / training / steps). [goal] can be negative for
 * calorie deficit targets. [period] is "day" or "week"; a week tile reads "this week".
 */
data class HomeLever(
    val key: String,
    val title: String,
    val score: Int,
    val label: String,
    val status: String,
    val value: Double,
    val goal: Double?,
    val unit: String?,
    val period: String,
    val detail: String,
    val action: String,
    val forecastValue: Double? = null,
    val forecastScore: Int? = null,
    val forecastLabel: String? = null,
    val forecastStatus: String? = null,
    val forecastDetail: String? = null,
    val lineItems: List<HomeLeverLineItem>,
)

/** One label/value row shown when a Home lever is expanded. */
data class HomeLeverLineItem(
    val label: String,
    val value: String,
    val time: String? = null,
)

/** The full Home payload from GET /api/home: the weight hero, four levers, and insight strings. */
data class HomeSummary(
    val date: String?,
    val isToday: Boolean,
    val objective: String,
    val overall: HomeDayStatus,
    val weight: HomeWeight,
    val levers: List<HomeLever>,
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
 * All scoring/goal/trend computation lives on the server; this just fetches and deserializes.
 */
class HomeApi(private val settingsStore: SettingsStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** [date] (YYYY-MM-DD) pages back to a previous day; null / today fetches the current day. */
    suspend fun fetchHome(date: String? = null): HomeResult {
        val backendUrl = settingsStore.backendUrl
        val bearerToken = settingsStore.bearerToken
        if (backendUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) {
            return HomeResult.NotConfigured
        }

        val url = if (date.isNullOrBlank()) "$backendUrl/api/home" else "$backendUrl/api/home?date=$date"
        val request = Request.Builder()
            .url(url)
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
        val insights = this["insights"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val levers = this["levers"]?.jsonArray
            ?.map { it.jsonObject.toLever() }
            ?: emptyList()
        return HomeSummary(
            date = this["date"]?.jsonPrimitive?.contentOrNull,
            isToday = this["isToday"]?.jsonPrimitive?.booleanOrNull ?: true,
            objective = this["objective"]?.jsonPrimitive?.contentOrNull ?: "Fat loss",
            overall = this["overall"]?.jsonObject?.toDayStatus() ?: HomeDayStatus(0, "—", "attention"),
            weight = this["weight"]?.jsonObject?.toWeight() ?: emptyWeight(),
            levers = levers,
            insights = insights,
        )
    }

    private fun emptyWeight(): HomeWeight = HomeWeight(
        score = 0,
        label = "No data",
        status = "attention",
        current = null,
        unit = "kg",
        target = null,
        toGo = null,
        changePerWeek = null,
        bodyFatPct = null,
        series = emptyList(),
        detail = "Weight trend unavailable until the backend is updated.",
        action = "",
    )

    private fun JsonObject.toDayStatus(): HomeDayStatus = HomeDayStatus(
        score = this["score"]?.jsonPrimitive?.intOrNull ?: 0,
        label = this["label"]?.jsonPrimitive?.contentOrNull ?: "—",
        status = this["status"]?.jsonPrimitive?.contentOrNull ?: "attention",
    )

    private fun JsonObject.toWeight(): HomeWeight = HomeWeight(
        score = this["score"]?.jsonPrimitive?.intOrNull ?: 0,
        label = this["label"]?.jsonPrimitive?.contentOrNull ?: "—",
        status = this["status"]?.jsonPrimitive?.contentOrNull ?: "attention",
        current = this["current"]?.jsonPrimitive?.doubleOrNull,
        unit = this["unit"]?.jsonPrimitive?.contentOrNull ?: "kg",
        target = this["target"]?.jsonPrimitive?.doubleOrNull,
        toGo = this["toGo"]?.jsonPrimitive?.doubleOrNull,
        changePerWeek = this["changePerWeek"]?.jsonPrimitive?.doubleOrNull,
        bodyFatPct = this["bodyFatPct"]?.jsonPrimitive?.doubleOrNull,
        series = this["series"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val date = o["date"]?.jsonPrimitive?.contentOrNull
            val value = o["value"]?.jsonPrimitive?.doubleOrNull
            if (date != null && value != null) WeightPoint(date, value) else null
        } ?: emptyList(),
        detail = this["detail"]?.jsonPrimitive?.contentOrNull ?: "",
        action = this["action"]?.jsonPrimitive?.contentOrNull ?: "",
    )

    private fun JsonObject.toLever(): HomeLever = HomeLever(
        key = this["key"]?.jsonPrimitive?.contentOrNull ?: "",
        title = this["title"]?.jsonPrimitive?.contentOrNull ?: "",
        score = this["score"]?.jsonPrimitive?.intOrNull ?: 0,
        label = this["label"]?.jsonPrimitive?.contentOrNull ?: "—",
        status = this["status"]?.jsonPrimitive?.contentOrNull ?: "attention",
        value = this["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        goal = this["goal"]?.jsonPrimitive?.doubleOrNull,
        unit = this["unit"]?.jsonPrimitive?.contentOrNull,
        period = this["period"]?.jsonPrimitive?.contentOrNull ?: "day",
        detail = this["detail"]?.jsonPrimitive?.contentOrNull ?: "",
        action = this["action"]?.jsonPrimitive?.contentOrNull ?: "",
        forecastValue = this["forecastValue"]?.jsonPrimitive?.doubleOrNull,
        forecastScore = this["forecastScore"]?.jsonPrimitive?.intOrNull,
        forecastLabel = this["forecastLabel"]?.jsonPrimitive?.contentOrNull,
        forecastStatus = this["forecastStatus"]?.jsonPrimitive?.contentOrNull,
        forecastDetail = this["forecastDetail"]?.jsonPrimitive?.contentOrNull,
        lineItems = this["lineItems"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val label = o["label"]?.jsonPrimitive?.contentOrNull
            val value = o["value"]?.jsonPrimitive?.contentOrNull
            val time = o["time"]?.jsonPrimitive?.contentOrNull
            if (label != null && value != null) HomeLeverLineItem(label, value, time) else null
        } ?: emptyList(),
    )
}
