package it.davide.diario

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One day's worth of imported activity, aggregated from Health Connect. */
data class ImportedDay(
    val activity: String,
    val km: Double,
    val minutes: Int,
    val calories: Int,
    val avgHr: Int?,
    val elevationM: Int
)

/** One lap/split of an activity. */
data class ActivitySplit(
    val label: String,
    val seconds: Int,
    val km: Double,
    val paceText: String,
    val avgHr: Int?
)

/** Full detail of a single run/walk activity, read from Health Connect. */
data class ActivityDetail(
    val activity: String,
    val km: Double,
    val durationSec: Int,
    val calories: Int,
    val elevationM: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val minHr: Int?,
    val maxSpeedKmh: Double?,
    val bestPaceSecPerKm: Int?,
    val zonesSec: List<Int>,
    val hrSeries: List<Int>,
    val splits: List<ActivitySplit>
)

/**
 * Reads running/walking activities from Android Health Connect (written there by the
 * Garmin Connect app) and maps them to calendar days. Fully local: no network, no
 * Garmin credentials, no server.
 */
object HealthImport {

    private val P_EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val P_DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
    private val P_CALORIES = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    private val P_HEART = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val P_ELEVATION = HealthPermission.getReadPermission(ElevationGainedRecord::class)
    private val P_SPEED = HealthPermission.getReadPermission(SpeedRecord::class)

    // Everything we ask for (history lets us read further back than the default 30 days).
    val PERMISSIONS: Set<String> = setOf(
        P_EXERCISE, P_DISTANCE, P_CALORIES, P_HEART, P_ELEVATION, P_SPEED,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )

    // Core permissions needed to import at all; the rest are a bonus.
    private val CORE: Set<String> = setOf(P_EXERCISE, P_DISTANCE)
    private val ALL_READ: Set<String> = setOf(P_EXERCISE, P_DISTANCE, P_CALORIES, P_HEART, P_ELEVATION, P_SPEED)

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(context: Context): Boolean =
        sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    suspend fun hasPermissions(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().containsAll(CORE)

    suspend fun hasAllPermissions(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().containsAll(ALL_READ)

    private class Acc {
        var activity = "walk"
        var km = 0.0
        var minutes = 0
        var kcal = 0.0
        var hrWeighted = 0.0
        var hrMinutes = 0
        var elev = 0.0
    }

    /** Reads all run/walk sessions of [YEAR] and aggregates them per calendar day. */
    suspend fun readActivities(context: Context): Map<String, ImportedDay> {
        val hc = client(context)
        val start = LocalDate.of(YEAR, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = Instant.now()

        val sessions = hc.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        val byDay = HashMap<String, Acc>()
        for (s in sessions) {
            val kind = when (s.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walk"
                else -> null
            } ?: continue

            val offset = s.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(s.startTime)
            val day = s.startTime.atOffset(offset).toLocalDate()
            val key = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
            val window = TimeRangeFilter.between(s.startTime, s.endTime)

            val km = runCatching {
                hc.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), window))[
                    DistanceRecord.DISTANCE_TOTAL
                ]?.inKilometers
            }.getOrNull() ?: 0.0

            val kcal = runCatching {
                hc.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), window))[
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                ]?.inKilocalories
            }.getOrNull() ?: 0.0

            val hr: Long? = runCatching {
                hc.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), window))[HeartRateRecord.BPM_AVG]
            }.getOrNull()

            val elev = runCatching {
                hc.aggregate(AggregateRequest(setOf(ElevationGainedRecord.ELEVATION_GAINED_TOTAL), window))[
                    ElevationGainedRecord.ELEVATION_GAINED_TOTAL
                ]?.inMeters
            }.getOrNull() ?: 0.0

            val minutes = Duration.between(s.startTime, s.endTime).toMinutes().toInt()

            val acc = byDay.getOrPut(key) { Acc() }
            if (kind == "run" || acc.activity == "run") acc.activity = "run"
            acc.km += km
            acc.minutes += minutes
            acc.kcal += kcal
            acc.elev += elev
            if (hr != null && minutes > 0) {
                acc.hrWeighted += hr.toDouble() * minutes
                acc.hrMinutes += minutes
            }
        }

        return byDay.mapValues { (_, a) ->
            ImportedDay(
                activity = a.activity,
                km = a.km,
                minutes = a.minutes,
                calories = a.kcal.roundToInt(),
                avgHr = if (a.hrMinutes > 0) (a.hrWeighted / a.hrMinutes).roundToInt() else null,
                elevationM = a.elev.roundToInt()
            )
        }
    }

    private val RUN_WALK_TYPES = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    )

    /** Full detail for the main (longest) run/walk activity of [dayKey], or null if none. */
    suspend fun readActivityDetail(context: Context, dayKey: String, maxHr: Int): ActivityDetail? {
        val hc = client(context)
        val d = LocalDate.parse(dayKey)
        val zone = ZoneId.systemDefault()
        val dayStart = d.atStartOfDay(zone).toInstant()
        val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant()

        val sessions = runCatching {
            hc.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(dayStart, dayEnd))
            ).records.filter { it.exerciseType in RUN_WALK_TYPES }
        }.getOrDefault(emptyList())
        val s = sessions.maxByOrNull { Duration.between(it.startTime, it.endTime).seconds } ?: return null

        val window = TimeRangeFilter.between(s.startTime, s.endTime)
        val durationSec = Duration.between(s.startTime, s.endTime).seconds.toInt()

        val km = runCatching {
            hc.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), window))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
        }.getOrNull() ?: 0.0
        val kcal = runCatching {
            hc.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), window))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        }.getOrNull() ?: 0.0
        val elev = runCatching {
            hc.aggregate(AggregateRequest(setOf(ElevationGainedRecord.ELEVATION_GAINED_TOTAL), window))[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters
        }.getOrNull() ?: 0.0
        val avgHrVal = runCatching {
            hc.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), window))[HeartRateRecord.BPM_AVG]
        }.getOrNull()?.toInt()
        val maxHrVal = runCatching {
            hc.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_MAX), window))[HeartRateRecord.BPM_MAX]
        }.getOrNull()?.toInt()
        val minHrVal = runCatching {
            hc.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_MIN), window))[HeartRateRecord.BPM_MIN]
        }.getOrNull()?.toInt()
        val maxSpeedKmh = runCatching {
            hc.aggregate(AggregateRequest(setOf(SpeedRecord.SPEED_MAX), window))[SpeedRecord.SPEED_MAX]?.inMetersPerSecond?.times(3.6)
        }.getOrNull()

        val samples = runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, window)).records
        }.getOrDefault(emptyList()).flatMap { it.samples }.sortedBy { it.time }

        val bounds = intArrayOf(
            (0.6 * maxHr).toInt(), (0.7 * maxHr).toInt(), (0.8 * maxHr).toInt(), (0.9 * maxHr).toInt()
        )
        val zonesSec = IntArray(5)
        for (i in samples.indices) {
            val bpm = samples[i].beatsPerMinute.toInt()
            val z = when {
                bpm < bounds[0] -> 0
                bpm < bounds[1] -> 1
                bpm < bounds[2] -> 2
                bpm < bounds[3] -> 3
                else -> 4
            }
            val dt = if (i < samples.size - 1)
                Duration.between(samples[i].time, samples[i + 1].time).seconds.toInt().coerceIn(0, 60)
            else 0
            zonesSec[z] += dt
        }

        val hrSeries = if (samples.isEmpty()) emptyList() else {
            val step = (samples.size / 48).coerceAtLeast(1)
            samples.filterIndexed { idx, _ -> idx % step == 0 }.map { it.beatsPerMinute.toInt() }
        }

        val splits = if (s.laps.isNotEmpty()) {
            s.laps.mapIndexed { idx, lap ->
                val lw = TimeRangeFilter.between(lap.startTime, lap.endTime)
                val lapKm = lap.length?.inKilometers
                    ?: runCatching {
                        hc.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), lw))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                    }.getOrNull() ?: 0.0
                val lapSec = Duration.between(lap.startTime, lap.endTime).seconds.toInt()
                val lapHr = runCatching {
                    hc.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), lw))[HeartRateRecord.BPM_AVG]
                }.getOrNull()?.toInt()
                val paceSec = if (lapKm > 0) (lapSec / lapKm).roundToInt() else 0
                ActivitySplit("${idx + 1}", lapSec, lapKm, if (paceSec > 0) paceText(paceSec) else "—", lapHr)
            }
        } else {
            // Garmin often doesn't write laps to Health Connect — compute per-km splits ourselves.
            computeKmSplits(hc, s.startTime, window, samples)
        }

        val bestPace = splits.filter { it.km > 0 }.minOfOrNull { (it.seconds / it.km).roundToInt() }

        return ActivityDetail(
            activity = if (s.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING ||
                s.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            ) "walk" else "run",
            km = km,
            durationSec = durationSec,
            calories = kcal.roundToInt(),
            elevationM = elev.roundToInt(),
            avgHr = avgHrVal,
            maxHr = maxHrVal,
            minHr = minHrVal,
            maxSpeedKmh = maxSpeedKmh,
            bestPaceSecPerKm = bestPace,
            zonesSec = zonesSec.toList(),
            hrSeries = hrSeries,
            splits = splits
        )
    }

    private fun paceText(secPerKm: Int): String =
        "%d'%02d\"".format(secPerKm / 60, secPerKm % 60)

    /**
     * Computes per-kilometre splits when Garmin didn't write laps: builds a cumulative
     * distance-over-time timeline from DistanceRecord (or integrates SpeedRecord as a
     * fallback), then finds the elapsed time at every 1 km boundary.
     */
    private suspend fun computeKmSplits(
        hc: HealthConnectClient,
        start: Instant,
        window: TimeRangeFilter,
        hrSamples: List<HeartRateRecord.Sample>
    ): List<ActivitySplit> {
        val points = ArrayList<Pair<Instant, Double>>() // (time, cumulative metres)

        val distRecs = runCatching {
            hc.readRecords(ReadRecordsRequest(DistanceRecord::class, window)).records.sortedBy { it.startTime }
        }.getOrDefault(emptyList())

        if (distRecs.size >= 2) {
            var cum = 0.0
            points.add(start to 0.0)
            for (r in distRecs) {
                cum += r.distance.inMeters
                points.add(r.endTime to cum)
            }
        } else {
            val spd = runCatching {
                hc.readRecords(ReadRecordsRequest(SpeedRecord::class, window)).records
            }.getOrDefault(emptyList()).flatMap { it.samples }.sortedBy { it.time }
            if (spd.size >= 2) {
                var cum = 0.0
                points.add(spd.first().time to 0.0)
                for (i in 1 until spd.size) {
                    val dt = Duration.between(spd[i - 1].time, spd[i].time).toMillis() / 1000.0
                    cum += spd[i - 1].speed.inMetersPerSecond * dt
                    points.add(spd[i].time to cum)
                }
            }
        }
        if (points.size < 2) return emptyList()

        val splits = ArrayList<ActivitySplit>()
        var kmIndex = 1
        var prevTime = points.first().first
        var target = 1000.0
        for (i in 1 until points.size) {
            val (t0, c0) = points[i - 1]
            val (t1, c1) = points[i]
            while (c1 >= target && c1 > c0) {
                val frac = (target - c0) / (c1 - c0)
                val tKm = t0.plusMillis((Duration.between(t0, t1).toMillis() * frac).toLong())
                val segSec = Duration.between(prevTime, tKm).seconds.toInt()
                splits.add(
                    ActivitySplit("Km $kmIndex", segSec, 1.0, if (segSec > 0) paceText(segSec) else "—", avgHrBetween(hrSamples, prevTime, tKm))
                )
                prevTime = tKm; kmIndex++; target += 1000.0
            }
        }
        val totalM = points.last().second
        val remainder = (totalM - (kmIndex - 1) * 1000.0) / 1000.0
        if (remainder > 0.05) {
            val segSec = Duration.between(prevTime, points.last().first).seconds.toInt()
            val paceSec = if (remainder > 0) (segSec / remainder).roundToInt() else 0
            splits.add(
                ActivitySplit("Km $kmIndex", segSec, remainder, if (paceSec > 0) paceText(paceSec) else "—", avgHrBetween(hrSamples, prevTime, points.last().first))
            )
        }
        return splits
    }

    private fun avgHrBetween(samples: List<HeartRateRecord.Sample>, a: Instant, b: Instant): Int? {
        val inWin = samples.filter { !it.time.isBefore(a) && !it.time.isAfter(b) }
        if (inWin.isEmpty()) return null
        return (inWin.sumOf { it.beatsPerMinute } / inWin.size).toInt()
    }
}
