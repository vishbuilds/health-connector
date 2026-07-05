package com.vishaal.healthconnector.network.dto

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.InstantaneousRecord
import androidx.health.connect.client.records.IntervalRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps a Health Connect [Record] to the wire DTO POSTed to `/api/ingest`:
 * ```
 * {
 *   "id": "...",
 *   "recordType": "StepsRecord",
 *   "startTime": "...",
 *   "endTime": "... or null",
 *   "zoneOffset": "+10:00 or null",
 *   "sourceApp": "com.google.android.apps.fitness",
 *   "data": { ...type specific fields... }
 * }
 * ```
 *
 * Health Connect unit types (Mass, Length, Energy, Power, Velocity, Temperature, Pressure,
 * Volume, Percentage, BloodGlucose, TemperatureDelta) are always converted to plain numeric
 * values with an explicit unit suffix in the JSON key (e.g. "weightKg", "heightMeters")
 * rather than serialized as opaque SDK objects.
 *
 * The `data` object is built by exactly 3 grouped mapping functions, based on the *shape*
 * of the record rather than one function per type:
 *  1. [instantaneousDataFields] - point-in-time scalar records (implement [InstantaneousRecord],
 *     no inner sample list): Height, Weight, BodyFat, BasalMetabolicRate, ..., BloodPressure,
 *     BloodGlucose, Vo2Max, cycle-tracking point events, etc.
 *  2. [intervalAggregateDataFields] - interval records exposing one or more aggregate scalar
 *     fields directly (implement [IntervalRecord]): Steps, Distance, ActiveCaloriesBurned,
 *     Hydration, Nutrition, SleepSession (whose "aggregate" is its stage list), ExerciseSession,
 *     SkinTemperature (baseline + delta list), MenstruationPeriod.
 *  3. [seriesDataFields] - records exposing an inner list of time-stamped samples
 *     (`record.samples`): HeartRateRecord, CyclingPedalingCadenceRecord, PowerRecord, SpeedRecord.
 */
fun Record.toUpsertJson(): JsonObject {
    val metadata = this.metadata
    val startTime = when (this) {
        is InstantaneousRecord -> this.time
        is IntervalRecord -> this.startTime
        else -> null
    }
    val endTime = when (this) {
        is IntervalRecord -> this.endTime
        else -> null
    }
    val zoneOffset = when (this) {
        is InstantaneousRecord -> this.zoneOffset
        is IntervalRecord -> this.startZoneOffset
        else -> null
    }

    return buildJsonObject {
        put("id", metadata.id)
        put("recordType", this@toUpsertJson::class.simpleName ?: "UnknownRecord")
        put("startTime", startTime?.toString())
        put("endTime", endTime?.toString())
        put("zoneOffset", zoneOffset?.toString())
        put("sourceApp", metadata.dataOrigin.packageName)
        put("data", buildDataJson(this@toUpsertJson))
    }
}

private fun buildDataJson(record: Record): JsonObject =
    seriesDataFields(record)
        ?: intervalAggregateDataFields(record)
        ?: instantaneousDataFields(record)
        ?: buildJsonObject { }

// ---------------------------------------------------------------------------------------
// 1. Instantaneous scalar records (single `time`, no inner sample list)
// ---------------------------------------------------------------------------------------
private fun instantaneousDataFields(record: Record): JsonObject? = when (record) {
    is HeightRecord -> buildJsonObject {
        put("heightMeters", record.height.inMeters)
    }
    is WeightRecord -> buildJsonObject {
        put("weightKg", record.weight.inKilograms)
    }
    is BodyFatRecord -> buildJsonObject {
        put("bodyFatPercentage", record.percentage.value)
    }
    is BasalMetabolicRateRecord -> buildJsonObject {
        // NOTE: `Power.inKilocaloriesPerDay` is an extension specifically added for BMR;
        // double check it exists on the exact connect-client version in use, falling back
        // to computing kcal/day from `.inWatts` (1 W = 1 J/s; 1 kcal/day = 4184 J / 86400 s)
        // if it doesn't.
        put("basalMetabolicRateKcalPerDay", record.basalMetabolicRate.inKilocaloriesPerDay)
        put("basalMetabolicRateWatts", record.basalMetabolicRate.inWatts)
    }
    is BodyWaterMassRecord -> buildJsonObject {
        put("bodyWaterMassKg", record.bodyWaterMass.inKilograms)
    }
    is BoneMassRecord -> buildJsonObject {
        put("boneMassKg", record.mass.inKilograms)
    }
    is LeanBodyMassRecord -> buildJsonObject {
        put("leanBodyMassKg", record.mass.inKilograms)
    }
    is MenstruationFlowRecord -> buildJsonObject {
        put("flow", record.flow)
    }
    is OvulationTestRecord -> buildJsonObject {
        put("result", record.result)
    }
    is CervicalMucusRecord -> buildJsonObject {
        put("appearance", record.appearance)
        put("sensation", record.sensation)
    }
    is IntermenstrualBleedingRecord -> buildJsonObject { }
    is SexualActivityRecord -> buildJsonObject {
        put("protectionUsed", record.protectionUsed)
    }
    is BasalBodyTemperatureRecord -> buildJsonObject {
        put("temperatureCelsius", record.temperature.inCelsius)
        put("measurementLocation", record.measurementLocation)
    }
    is HeartRateVariabilityRmssdRecord -> buildJsonObject {
        put("heartRateVariabilityMillis", record.heartRateVariabilityMillis)
    }
    is BloodPressureRecord -> buildJsonObject {
        put("systolicMmHg", record.systolic.inMillimetersOfMercury)
        put("diastolicMmHg", record.diastolic.inMillimetersOfMercury)
        put("bodyPosition", record.bodyPosition)
        put("measurementLocation", record.measurementLocation)
    }
    is BloodGlucoseRecord -> buildJsonObject {
        put("levelMgPerDl", record.level.inMilligramsPerDeciliter)
        put("specimenSource", record.specimenSource)
        put("mealType", record.mealType)
        put("relationToMeal", record.relationToMeal)
    }
    is BodyTemperatureRecord -> buildJsonObject {
        put("temperatureCelsius", record.temperature.inCelsius)
        put("measurementLocation", record.measurementLocation)
    }
    is OxygenSaturationRecord -> buildJsonObject {
        put("oxygenSaturationPercentage", record.percentage.value)
    }
    is RespiratoryRateRecord -> buildJsonObject {
        put("respiratoryRateBreathsPerMinute", record.rate)
    }
    is RestingHeartRateRecord -> buildJsonObject {
        put("restingHeartRateBpm", record.beatsPerMinute)
    }
    is Vo2MaxRecord -> buildJsonObject {
        put("vo2MillilitersPerMinuteKilogram", record.vo2MillilitersPerMinuteKilogram)
        put("measurementMethod", record.measurementMethod)
    }
    else -> null
}

// ---------------------------------------------------------------------------------------
// 2. Interval records with one or more aggregate fields (startTime/endTime + scalar(s),
//    or a small embedded list that itself is the "aggregate", e.g. sleep stages).
// ---------------------------------------------------------------------------------------
private fun intervalAggregateDataFields(record: Record): JsonObject? = when (record) {
    is StepsRecord -> buildJsonObject {
        put("count", record.count)
    }
    is DistanceRecord -> buildJsonObject {
        put("distanceMeters", record.distance.inMeters)
    }
    is ActiveCaloriesBurnedRecord -> buildJsonObject {
        put("energyKcal", record.energy.inKilocalories)
    }
    is TotalCaloriesBurnedRecord -> buildJsonObject {
        put("energyKcal", record.energy.inKilocalories)
    }
    is FloorsClimbedRecord -> buildJsonObject {
        put("floors", record.floors)
    }
    is ElevationGainedRecord -> buildJsonObject {
        put("elevationMeters", record.elevation.inMeters)
    }
    is WheelchairPushesRecord -> buildJsonObject {
        put("count", record.count)
    }
    is HydrationRecord -> buildJsonObject {
        put("volumeLiters", record.volume.inLiters)
    }
    is NutritionRecord -> buildJsonObject {
        // NOTE: NutritionRecord has ~30 optional Mass/Energy micro/macro-nutrient fields;
        // this covers the most commonly used ones. Extend with the remaining fields
        // (biotin, chloride, chromium, copper, folate, folicAcid, iodine, magnesium,
        // manganese, molybdenum, niacin, pantothenicAcid, phosphorus, riboflavin,
        // selenium, thiamin, vitaminA, vitaminB12, vitaminB6, vitaminE, vitaminK, zinc)
        // if the backend needs them.
        put("name", record.name)
        put("mealType", record.mealType)
        record.energy?.let { put("energyKcal", it.inKilocalories) }
        record.protein?.let { put("proteinGrams", it.inGrams) }
        record.totalCarbohydrate?.let { put("totalCarbohydrateGrams", it.inGrams) }
        record.totalFat?.let { put("totalFatGrams", it.inGrams) }
        record.saturatedFat?.let { put("saturatedFatGrams", it.inGrams) }
        record.unsaturatedFat?.let { put("unsaturatedFatGrams", it.inGrams) }
        record.monounsaturatedFat?.let { put("monounsaturatedFatGrams", it.inGrams) }
        record.polyunsaturatedFat?.let { put("polyunsaturatedFatGrams", it.inGrams) }
        record.transFat?.let { put("transFatGrams", it.inGrams) }
        record.dietaryFiber?.let { put("dietaryFiberGrams", it.inGrams) }
        record.sugar?.let { put("sugarGrams", it.inGrams) }
        record.sodium?.let { put("sodiumGrams", it.inGrams) }
        record.cholesterol?.let { put("cholesterolGrams", it.inGrams) }
        record.potassium?.let { put("potassiumGrams", it.inGrams) }
        record.calcium?.let { put("calciumGrams", it.inGrams) }
        record.iron?.let { put("ironGrams", it.inGrams) }
        record.caffeine?.let { put("caffeineGrams", it.inGrams) }
        record.vitaminC?.let { put("vitaminCGrams", it.inGrams) }
        record.vitaminD?.let { put("vitaminDGrams", it.inGrams) }
    }
    is SleepSessionRecord -> buildJsonObject {
        put("title", record.title)
        put("notes", record.notes)
        put(
            "stages",
            buildJsonArray {
                record.stages.forEach { stage ->
                    add(
                        buildJsonObject {
                            put("startTime", stage.startTime.toString())
                            put("endTime", stage.endTime.toString())
                            put("stage", stage.stage)
                        },
                    )
                }
            },
        )
    }
    is ExerciseSessionRecord -> buildJsonObject {
        put("exerciseType", record.exerciseType)
        put("title", record.title)
        put("notes", record.notes)
        put("segmentCount", record.segments.size)
        put("lapCount", record.laps.size)
    }
    is MenstruationPeriodRecord -> buildJsonObject { }
    is SkinTemperatureRecord -> buildJsonObject {
        // NOTE: double check `SkinTemperatureRecord.Delta` field names (`time`/`delta`) and
        // `record.baseline` nullability against the SDK version in use; this is a newer,
        // less commonly referenced record type than the others in this file.
        record.baseline?.let { put("baselineCelsius", it.inCelsius) }
        put("measurementLocation", record.measurementLocation)
        put(
            "deltas",
            buildJsonArray {
                record.deltas.forEach { delta ->
                    add(
                        buildJsonObject {
                            put("time", delta.time.toString())
                            put("deltaCelsius", delta.delta.inCelsius)
                        },
                    )
                }
            },
        )
    }
    else -> null
}

// ---------------------------------------------------------------------------------------
// 3. Series records exposing an inner list of time-stamped samples.
// ---------------------------------------------------------------------------------------
private fun seriesDataFields(record: Record): JsonObject? = when (record) {
    is HeartRateRecord -> buildJsonObject {
        put(
            "samples",
            samplesArray(record.samples) { sample ->
                put("time", sample.time.toString())
                put("beatsPerMinute", sample.beatsPerMinute)
            },
        )
    }
    is CyclingPedalingCadenceRecord -> buildJsonObject {
        put(
            "samples",
            samplesArray(record.samples) { sample ->
                put("time", sample.time.toString())
                put("revolutionsPerMinute", sample.revolutionsPerMinute)
            },
        )
    }
    is PowerRecord -> buildJsonObject {
        put(
            "samples",
            samplesArray(record.samples) { sample ->
                put("time", sample.time.toString())
                put("watts", sample.power.inWatts)
            },
        )
    }
    is SpeedRecord -> buildJsonObject {
        put(
            "samples",
            samplesArray(record.samples) { sample ->
                put("time", sample.time.toString())
                put("metersPerSecond", sample.speed.inMetersPerSecond)
            },
        )
    }
    else -> null
}

private inline fun <T> samplesArray(
    samples: List<T>,
    crossinline toJson: JsonObjectBuilder.(T) -> Unit,
): JsonArray = buildJsonArray {
    samples.forEach { sample ->
        add(buildJsonObject { toJson(sample) })
    }
}
