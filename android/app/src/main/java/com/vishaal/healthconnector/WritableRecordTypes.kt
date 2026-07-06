package com.vishaal.healthconnector

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * The subset of Health Connect record types this app is allowed to WRITE (as opposed to
 * [RecordTypes.ALL], which it reads). This MUST stay in sync with the server-side allowlist in
 * `server/lib/write-types.ts`: a wire name present here but not there (or vice versa) means a
 * queued write can never be applied.
 *
 * Every entry's [RecordTypeInfo.kClass] is used to derive the correct WRITE permission at
 * runtime via `HealthPermission.getWritePermission(kClass)` (see [HealthConnectManager]), so the
 * write permission set never drifts from this list. The actual JSON-to-[Record] mapping lives in
 * `network.dto.RecordWriter`.
 */
object WritableRecordTypes {

    val ALL: List<RecordTypeInfo> = listOf(
        info(WeightRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        info(HeightRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        info(BodyFatRecord::class, HealthDataCategory.BODY_MEASUREMENT),
        info(BodyTemperatureRecord::class, HealthDataCategory.VITALS),
        info(BloodPressureRecord::class, HealthDataCategory.VITALS),
        info(BloodGlucoseRecord::class, HealthDataCategory.VITALS),
        info(OxygenSaturationRecord::class, HealthDataCategory.VITALS),
        info(RestingHeartRateRecord::class, HealthDataCategory.VITALS),
        info(RespiratoryRateRecord::class, HealthDataCategory.VITALS),
        info(HydrationRecord::class, HealthDataCategory.NUTRITION),
        info(StepsRecord::class, HealthDataCategory.ACTIVITY),
        info(NutritionRecord::class, HealthDataCategory.NUTRITION),
    )

    val ALL_KCLASSES: List<KClass<out Record>> = ALL.map { it.kClass }

    val WIRE_NAMES: Set<String> = ALL.map { it.wireName }.toSet()

    private fun info(kClass: KClass<out Record>, category: HealthDataCategory): RecordTypeInfo =
        RecordTypeInfo(kClass = kClass, wireName = kClass.simpleName!!, category = category)
}
