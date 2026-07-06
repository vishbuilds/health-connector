package com.vishaal.healthconnector.logic

import com.vishaal.healthconnector.network.HomeSummary
import kotlin.math.roundToInt

/**
 * The single most useful thing to *manually log* right now, derived from the day's Home summary.
 * Only the two manually-entered signals qualify — weight (a morning weigh-in) and food (protein).
 * Steps, resting HR, and workouts are captured passively, so they're never a log target.
 *
 * [prompt] is the text handed to Claude (pre-filled), shared by the Home "Log" button (Layer 1) and
 * the daily reminder notification (Layer 2) so both nudge toward the same action.
 */
data class LogTarget(
    val key: String, // "weight" | "food"
    val heading: String,
    val reason: String,
    val prompt: String,
)

object LogTargets {
    /**
     * Picks the most urgent unlogged item for *today* (lower lever score = more behind = more
     * urgent; a missing weigh-in outranks everything). Returns null when viewing a past day or when
     * nothing manual is outstanding — i.e. weighed in and protein met.
     */
    fun forSummary(summary: HomeSummary): LogTarget? {
        if (!summary.isToday) return null

        val candidates = mutableListOf<Pair<Int, LogTarget>>() // urgency (lower = more urgent)

        forKey(summary, "weight")?.let { candidates += 0 to it }
        forKey(summary, "food")?.let { target ->
            val score = summary.levers.firstOrNull { it.key == "protein" }?.score ?: 100
            candidates += score to target
        }

        return candidates.minByOrNull { it.first }?.second
    }

    fun forPriority(summary: HomeSummary, priorityKey: String): LogTarget? =
        when (priorityKey) {
            "weight" -> forKey(summary, "weight")
            "protein" -> forKey(summary, "food")
            else -> null
        } ?: forSummary(summary)

    fun forKey(summary: HomeSummary, key: String): LogTarget? {
        if (!summary.isToday) return null
        return when (key) {
            "weight" -> weight(summary)
            "food" -> food(summary)
            else -> null
        }
    }

    private fun weight(summary: HomeSummary): LogTarget? {
        val weighedInToday = summary.date != null &&
            summary.weight.series.lastOrNull()?.date == summary.date
        if (weighedInToday) return null
        return LogTarget(
            key = "weight",
            heading = "Log your weight",
            reason = "You haven't weighed in yet today.",
            prompt = "Log my weight: ",
        )
    }

    private fun food(summary: HomeSummary): LogTarget? {
        val protein = summary.levers.firstOrNull { it.key == "protein" } ?: return null
        val goal = protein.goal ?: return null
        if (protein.value >= goal) return null
        val gap = (goal - protein.value).roundToInt()
        return LogTarget(
            key = "food",
            heading = "Log your food",
            reason = "$gap g protein to go today — key to holding muscle.",
            prompt = "Log what I ate: ",
        )
    }
}
