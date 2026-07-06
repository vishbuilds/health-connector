package com.vishaal.healthconnector.network.dto

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import com.vishaal.healthconnector.network.PendingWrite
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Inverse of [toUpsertJson] for the writable subset: maps a [PendingWrite] (as delivered by
 * `GET /api/writes`) back into a concrete Health Connect [Record] so it can be inserted via
 * `HealthConnectClient.insertRecords`.
 *
 * The wire field names/units match exactly what [toUpsertJson] emits on read, so a value written
 * here round-trips identically when it later syncs back. The server has already validated the
 * type against the allowlist and bounded every value (see server `write-types.ts`), but this
 * mapper still fails loudly on anything it doesn't recognise so a malformed row is reported as a
 * failed write rather than silently dropped.
 *
 * IDEMPOTENCY: every record is stamped with [Metadata.manualEntry] carrying the server row id as
 * `clientRecordId`. Re-inserting the same id updates the same Health Connect record instead of
 * creating a duplicate, so a write that is redelivered (because its ack didn't reach the server)
 * is harmless.
 *
 * NOTE: [Metadata.manualEntry] and the unit factory methods below are from
 * connect-client:1.1.0; if the Gradle-resolved version differs, double-check these signatures
 * (the Metadata factory names in particular shifted across alpha/rc releases).
 */
object RecordWriter {

    fun toRecord(write: PendingWrite): Record {
        val start = Instant.parse(write.startTime)
        val end = write.endTime?.let { Instant.parse(it) }
        val offset = write.zoneOffset?.let { ZoneOffset.of(it) }
        val data = write.data
        val metadata = Metadata.manualEntry(clientRecordId = write.id)

        return when (write.recordType) {
            "WeightRecord" -> WeightRecord(
                time = start,
                zoneOffset = offset,
                weight = Mass.kilograms(data.reqDouble("weightKg")),
                metadata = metadata,
            )
            "HeightRecord" -> HeightRecord(
                time = start,
                zoneOffset = offset,
                height = Length.meters(data.reqDouble("heightMeters")),
                metadata = metadata,
            )
            "BodyFatRecord" -> BodyFatRecord(
                time = start,
                zoneOffset = offset,
                percentage = Percentage(data.reqDouble("bodyFatPercentage")),
                metadata = metadata,
            )
            "BasalMetabolicRateRecord" -> BasalMetabolicRateRecord(
                time = start,
                zoneOffset = offset,
                basalMetabolicRate = Power.watts(data.reqDouble("basalMetabolicRateKcalPerDay") * 4184.0 / 86400.0),
                metadata = metadata,
            )
            "BodyTemperatureRecord" -> BodyTemperatureRecord(
                time = start,
                zoneOffset = offset,
                temperature = Temperature.celsius(data.reqDouble("temperatureCelsius")),
                metadata = metadata,
            )
            "BloodPressureRecord" -> BloodPressureRecord(
                time = start,
                zoneOffset = offset,
                systolic = Pressure.millimetersOfMercury(data.reqDouble("systolicMmHg")),
                diastolic = Pressure.millimetersOfMercury(data.reqDouble("diastolicMmHg")),
                metadata = metadata,
            )
            "BloodGlucoseRecord" -> BloodGlucoseRecord(
                time = start,
                zoneOffset = offset,
                level = BloodGlucose.milligramsPerDeciliter(data.reqDouble("levelMgPerDl")),
                metadata = metadata,
            )
            "OxygenSaturationRecord" -> OxygenSaturationRecord(
                time = start,
                zoneOffset = offset,
                percentage = Percentage(data.reqDouble("oxygenSaturationPercentage")),
                metadata = metadata,
            )
            "RestingHeartRateRecord" -> RestingHeartRateRecord(
                time = start,
                zoneOffset = offset,
                beatsPerMinute = data.reqLong("restingHeartRateBpm"),
                metadata = metadata,
            )
            "RespiratoryRateRecord" -> RespiratoryRateRecord(
                time = start,
                zoneOffset = offset,
                rate = data.reqDouble("respiratoryRateBreathsPerMinute"),
                metadata = metadata,
            )
            "HydrationRecord" -> HydrationRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = requireInterval(end, write.recordType),
                endZoneOffset = offset,
                volume = Volume.liters(data.reqDouble("volumeLiters")),
                metadata = metadata,
            )
            "StepsRecord" -> StepsRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = requireInterval(end, write.recordType),
                endZoneOffset = offset,
                count = data.reqLong("count"),
                metadata = metadata,
            )
            "NutritionRecord" -> NutritionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = requireInterval(end, write.recordType),
                endZoneOffset = offset,
                name = data.optString("name"),
                mealType = data.optInt("mealType") ?: MealType.MEAL_TYPE_UNKNOWN,
                energy = data.optDouble("energyKcal")?.let { Energy.kilocalories(it) },
                protein = data.optDouble("proteinGrams")?.let { Mass.grams(it) },
                totalCarbohydrate = data.optDouble("totalCarbohydrateGrams")?.let { Mass.grams(it) },
                totalFat = data.optDouble("totalFatGrams")?.let { Mass.grams(it) },
                metadata = metadata,
            )
            else -> throw IllegalArgumentException("Unsupported writable record type: ${write.recordType}")
        }
    }

    private fun requireInterval(end: Instant?, type: String): Instant =
        end ?: throw IllegalArgumentException("$type is an interval record and requires endTime")

    // --- JsonObject accessors: required variants throw, optional variants return null ---

    private fun JsonObject.reqDouble(key: String): Double =
        optDouble(key) ?: throw IllegalArgumentException("missing/invalid numeric field '$key'")

    private fun JsonObject.reqLong(key: String): Long =
        optLong(key) ?: throw IllegalArgumentException("missing/invalid integer field '$key'")

    private fun JsonObject.optDouble(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.optLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.optInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
