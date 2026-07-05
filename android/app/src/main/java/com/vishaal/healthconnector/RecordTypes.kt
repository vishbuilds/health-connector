package com.vishaal.healthconnector

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
import kotlin.reflect.KClass

/**
 * Single source of truth for every Health Connect record type this app reads.
 *
 * Each entry pairs the real `androidx.health.connect.client.records` [KClass] with:
 *  - [RecordTypeInfo.wireName]: the simple class name, used verbatim as the "recordType"
 *    field in the JSON we POST to the backend (e.g. "StepsRecord").
 *  - [RecordTypeInfo.category]: one of the 6 Health Connect data categories, used only
 *    for grouping in the UI (status screen, permission screen).
 *
 * NOTE: the actual permission *string* used to request/grant access to a given record
 * type is intentionally NOT hardcoded here. We derive it at runtime via
 * `androidx.health.connect.client.permission.HealthPermission.getReadPermission(KClass)`
 * (see HealthConnectManager.kt) since that is the SDK's own source of truth and avoids
 * this file getting out of sync with the platform (e.g. some record types share a single
 * permission, such as MenstruationFlowRecord + MenstruationPeriodRecord -> READ_MENSTRUATION).
 * The AndroidManifest, however, has to declare permission strings statically -- those were
 * derived by hand from the same mapping and should be spot-checked against the SDK docs.
 */
enum class HealthDataCategory {
    ACTIVITY,
    BODY_MEASUREMENT,
    CYCLE_TRACKING,
    NUTRITION,
    SLEEP,
    VITALS,
}

data class RecordTypeInfo(
    val kClass: KClass<out Record>,
    val wireName: String,
    val category: HealthDataCategory,
)

object RecordTypes {

    private fun entry(kClass: KClass<out Record>, category: HealthDataCategory): RecordTypeInfo =
        RecordTypeInfo(kClass = kClass, wireName = kClass.simpleName!!, category = category)

    val ACTIVITY: List<RecordTypeInfo> = listOf(
        entry(StepsRecord::class, HealthDataCategory.ACTIVITY),
        entry(DistanceRecord::class, HealthDataCategory.ACTIVITY),
        entry(ActiveCaloriesBurnedRecord::class, HealthDataCategory.ACTIVITY),
        entry(TotalCaloriesBurnedRecord::class, HealthDataCategory.ACTIVITY),
        entry(ExerciseSessionRecord::class, HealthDataCategory.ACTIVITY),
        entry(FloorsClimbedRecord::class, HealthDataCategory.ACTIVITY),
        entry(PowerRecord::class, HealthDataCategory.ACTIVITY),
        entry(SpeedRecord::class, HealthDataCategory.ACTIVITY),
        entry(Vo2MaxRecord::class, HealthDataCategory.ACTIVITY),
        entry(WheelchairPushesRecord::class, HealthDataCategory.ACTIVITY),
        entry(ElevationGainedRecord::class, HealthDataCategory.ACTIVITY),
        entry(CyclingPedalingCadenceRecord::class, HealthDataCategory.ACTIVITY),
    )

    val BODY_MEASUREMENT: List<RecordTypeInfo> = listOf(
        entry(HeightRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(WeightRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(BodyFatRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(BasalMetabolicRateRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(BodyWaterMassRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(BoneMassRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        entry(LeanBodyMassRecord::class, HealthDataCategory.BODY_MEASUREMENT),
    )

    val CYCLE_TRACKING: List<RecordTypeInfo> = listOf(
        entry(MenstruationFlowRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(MenstruationPeriodRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(OvulationTestRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(CervicalMucusRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(IntermenstrualBleedingRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(SexualActivityRecord::class, HealthDataCategory.CYCLE_TRACKING),
        entry(BasalBodyTemperatureRecord::class, HealthDataCategory.CYCLE_TRACKING),
    )

    val NUTRITION: List<RecordTypeInfo> = listOf(
        entry(HydrationRecord::class, HealthDataCategory.NUTRITION),
        entry(NutritionRecord::class, HealthDataCategory.NUTRITION),
    )

    val SLEEP: List<RecordTypeInfo> = listOf(
        entry(SleepSessionRecord::class, HealthDataCategory.SLEEP),
    )

    val VITALS: List<RecordTypeInfo> = listOf(
        entry(HeartRateRecord::class, HealthDataCategory.VITALS),
        entry(HeartRateVariabilityRmssdRecord::class, HealthDataCategory.VITALS),
        entry(BloodPressureRecord::class, HealthDataCategory.VITALS),
        entry(BloodGlucoseRecord::class, HealthDataCategory.VITALS),
        entry(BodyTemperatureRecord::class, HealthDataCategory.VITALS),
        entry(OxygenSaturationRecord::class, HealthDataCategory.VITALS),
        entry(RespiratoryRateRecord::class, HealthDataCategory.VITALS),
        entry(RestingHeartRateRecord::class, HealthDataCategory.VITALS),
        entry(SkinTemperatureRecord::class, HealthDataCategory.VITALS),
    )

    /** All record types this app reads, across all 6 categories. */
    val ALL: List<RecordTypeInfo> =
        ACTIVITY + BODY_MEASUREMENT + CYCLE_TRACKING + NUTRITION + SLEEP + VITALS

    val ALL_BY_WIRE_NAME: Map<String, RecordTypeInfo> = ALL.associateBy { it.wireName }

    val ALL_BY_KCLASS: Map<KClass<out Record>, RecordTypeInfo> = ALL.associateBy { it.kClass }

    fun wireNameOf(kClass: KClass<out Record>): String =
        ALL_BY_KCLASS[kClass]?.wireName ?: kClass.simpleName!!
}
