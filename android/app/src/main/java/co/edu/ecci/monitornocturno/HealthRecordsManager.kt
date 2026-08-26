package co.edu.ecci.monitornocturno

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HealthRecordsManager(private val context: Context) {
    data class Summary(
        val heartRate: String,
        val oxygen: String,
        val detail: String
    )

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean = client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun readLastSevenDays(): Summary {
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        val timeRange = TimeRangeFilter.between(start, end)
        val heartRecords = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, timeRange, pageSize = 1000)
        ).records
        val oxygenRecords = client.readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, timeRange, pageSize = 1000)
        ).records

        val latestHeart = heartRecords
            .flatMap { record -> record.samples.map { record.metadata.dataOrigin.packageName to it } }
            .maxByOrNull { it.second.time }
        val latestOxygen = oxygenRecords.maxByOrNull { it.time }
        val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

        val heartText = latestHeart?.let {
            "${it.second.beatsPerMinute} lpm\n${formatter.format(it.second.time)}"
        } ?: "Sin registros"
        val oxygenText = latestOxygen?.let {
            "${"%.1f".format(it.percentage.value)} %\n${formatter.format(it.time)}"
        } ?: "Sin registros"
        val heartSamples = heartRecords.sumOf { it.samples.size }
        val origins = (heartRecords.map { it.metadata.dataOrigin.packageName } +
            oxygenRecords.map { it.metadata.dataOrigin.packageName }).distinct()
        val source = if (origins.isEmpty()) "ninguna fuente" else origins.joinToString()
        return Summary(
            heartText,
            oxygenText,
            "Ultimos 7 dias: $heartSamples muestras cardiacas y ${oxygenRecords.size} mediciones SpO2. Fuente: $source"
        )
    }
}
