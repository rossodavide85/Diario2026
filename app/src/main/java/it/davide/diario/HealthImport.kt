package it.davide.diario

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's worth of imported activity, aggregated from Health Connect. */
data class ImportedDay(val activity: String, val km: Double, val minutes: Int)

/**
 * Reads running/walking activities from Android Health Connect (written there by the
 * Garmin Connect app) and maps them to calendar days. Fully local: no network, no
 * Garmin credentials, no server.
 */
object HealthImport {

    // Permissions we request. History lets us read further back than the default 30 days.
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )

    // The two we actually need granted to read (history is best-effort).
    private val REQUIRED: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class)
    )

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(context: Context): Boolean =
        sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    suspend fun hasPermissions(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().containsAll(REQUIRED)

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

        val acc = HashMap<String, ImportedDay>()
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

            val km = runCatching {
                hc.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime)
                    )
                )[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
            }.getOrDefault(0.0)

            val minutes = Duration.between(s.startTime, s.endTime).toMinutes().toInt()

            val prev = acc[key]
            // If a day has both a run and a walk, label it a run; sum distance and time.
            val activity = if (prev?.activity == "run" || kind == "run") "run" else "walk"
            acc[key] = ImportedDay(
                activity = activity,
                km = (prev?.km ?: 0.0) + km,
                minutes = (prev?.minutes ?: 0) + minutes
            )
        }
        return acc
    }
}
