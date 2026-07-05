/**
 * Local dev helper: seeds a handful of fake health_records rows across
 * several types/days (including one spanning the AEST/AEDT boundary, which
 * in 2026 falls on the Australia/Sydney clock change April 5 and Oct 4) so
 * get_daily_summary and get_records can be exercised against real-shaped
 * data without a phone.
 */
import { db } from "@/db/client";
import { healthRecords } from "@/db/schema";

async function main() {
  const rows: (typeof healthRecords.$inferInsert)[] = [
    {
      id: "seed-steps-1",
      recordType: "StepsRecord",
      startTime: new Date("2026-07-04T22:00:00Z"), // 2026-07-05 08:00 AEST
      endTime: new Date("2026-07-04T23:00:00Z"),
      zoneOffset: "+10:00",
      data: { count: 1200 },
      sourceApp: "com.google.android.apps.fitness",
      deviceId: "seed-device",
    },
    {
      id: "seed-steps-2",
      recordType: "StepsRecord",
      startTime: new Date("2026-07-05T05:00:00Z"), // 2026-07-05 15:00 AEST
      endTime: new Date("2026-07-05T06:00:00Z"),
      zoneOffset: "+10:00",
      data: { count: 3400 },
      sourceApp: "com.google.android.apps.fitness",
      deviceId: "seed-device",
    },
    {
      id: "seed-distance-1",
      recordType: "DistanceRecord",
      startTime: new Date("2026-07-04T22:00:00Z"),
      endTime: new Date("2026-07-04T23:00:00Z"),
      zoneOffset: "+10:00",
      data: { distanceMeters: 3200.5 },
      deviceId: "seed-device",
    },
    {
      id: "seed-sleep-1",
      recordType: "SleepSessionRecord",
      startTime: new Date("2026-07-04T20:30:00Z"), // 2026-07-05 06:30 AEST (session ends the morning of the summary day)
      endTime: new Date("2026-07-04T22:30:00Z"),
      zoneOffset: "+10:00",
      data: { title: "Sleep", stages: [] },
      deviceId: "seed-device",
    },
    {
      id: "seed-heartrate-1",
      recordType: "RestingHeartRateRecord",
      startTime: new Date("2026-07-04T22:15:00Z"),
      endTime: null,
      zoneOffset: "+10:00",
      data: { restingHeartRateBpm: 58 },
      deviceId: "seed-device",
    },
    {
      id: "seed-exercise-1",
      recordType: "ExerciseSessionRecord",
      startTime: new Date("2026-07-04T23:30:00Z"), // 2026-07-05 09:30 AEST
      endTime: new Date("2026-07-05T00:15:00Z"),
      zoneOffset: "+10:00",
      data: { exerciseType: "RUNNING", title: "Morning run", segmentCount: 0, lapCount: 0 },
      deviceId: "seed-device",
    },
    {
      id: "seed-weight-1",
      recordType: "WeightRecord",
      startTime: new Date("2026-07-03T20:00:00Z"),
      endTime: null,
      zoneOffset: "+10:00",
      data: { weightKg: 74.2 },
      deviceId: "seed-device",
    },
    // A record on the other side of the AEDT->AEST transition (2026-04-05 03:00 AEDT -> 02:00 AEST)
    // to sanity check dayRangeUtc doesn't mishandle the offset change.
    {
      id: "seed-steps-dst",
      recordType: "StepsRecord",
      startTime: new Date("2026-04-04T14:00:00Z"), // 2026-04-05 01:00 AEDT (still +11:00, before the 3am rollback)
      endTime: new Date("2026-04-04T15:00:00Z"),
      zoneOffset: "+11:00",
      data: { count: 500 },
      deviceId: "seed-device",
    },
  ];

  await db.insert(healthRecords).values(rows).onConflictDoNothing();
  console.log(`Seeded ${rows.length} rows`);
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
