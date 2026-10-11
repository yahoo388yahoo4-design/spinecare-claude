package com.spinecare.app

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/** Health Connect: read-only import of steps and sleep (graceful when unavailable).
 *  Imported values are context for the user's own trends, never diagnostic. */
class HealthConnectHelper(private val context: Context) {
    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )

        fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()
    }

    fun isAvailable(): Boolean = try {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (e: Exception) { false }

    suspend fun hasPermissions(): Boolean = try {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    } catch (e: Exception) { false }

    data class DayStats(val steps: Long, val sleepMinutes: Long)

    /** Steps and sleep over the last 24 hours, or null when unavailable/denied. */
    suspend fun readLastDay(): DayStats? = try {
        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val range = TimeRangeFilter.between(end.minus(Duration.ofHours(24)), end)
        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.sumOf { it.count }
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        DayStats(steps, sleep)
    } catch (e: Exception) { null }
}
