package cc.bridgit.app.planned

import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.*
import android.os.OutcomeReceiver
import android.health.connect.HealthConnectException
import io.ktor.server.routing.Route
import kotlinx.coroutines.suspendCancellableCoroutine
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.annotation.SuppressLint
import android.os.Build

/** Android 14 platform Health Connect; read-only and never requests write or background permissions. Blocked below Android 14. */
@SuppressLint("NewApi")
class HealthModule(app: BridgeApp) : Capability(app) {
    override val id = "health_connect"
    override val titleRes = R.string.cap_health
    override val descriptionRes = R.string.cap_health_desc
    override val group = "private context"
    override val switchKey = "health_connect"
    override val endpoints = listOf(PlannedEndpoints.HEALTH_DATA)
    override fun blockedReason() = if (Build.VERSION.SDK_INT < 34) "android_version" else null
    override fun details() = JSONObject().put("read_types", JSONArray(listOf("steps", "sleep", "heart_rate")))
    override fun Route.install(api: ApiServer) = with(api) { endpoint(PlannedEndpoints.HEALTH_DATA) {
        requireUsable()
        val type = query("type") ?: "steps"
        val permission = when (type) { "steps" -> "READ_STEPS"; "sleep" -> "READ_SLEEP"; "heart_rate" -> "READ_HEART_RATE"; else -> throw ErrorCatalog.error("invalid_parameter", "type must be steps, sleep or heart_rate.") }
        if (!app.granted("android.permission.health.$permission")) throw ErrorCatalog.error("permission_denied", "Grant Health Connect $type access on the phone.")
        val until = UsageModule.time(query("until"), System.currentTimeMillis())
        val since = UsageModule.time(query("since"), until - 86400_000)
        if (since >= until || until - since > 7L * 86400_000) throw ErrorCatalog.error("invalid_parameter", "Request at most seven days.")
        val filter = TimeInstantRangeFilter.Builder().setStartTime(Instant.ofEpochMilli(since)).setEndTime(Instant.ofEpochMilli(until)).build()
        val result = when (type) {
            "steps" -> read(StepsRecord::class.java, filter).map { JSONObject().put("start", it.startTime.toString()).put("end", it.endTime.toString()).put("count", it.count) }
            "sleep" -> read(SleepSessionRecord::class.java, filter).map { JSONObject().put("start", it.startTime.toString()).put("end", it.endTime.toString()).put("duration_minutes", java.time.Duration.between(it.startTime, it.endTime).toMinutes()) }
            else -> read(HeartRateRecord::class.java, filter).map { JSONObject().put("start", it.startTime.toString()).put("end", it.endTime.toString()).put("samples", JSONArray(it.samples.map { s -> JSONObject().put("time", s.time.toString()).put("bpm", s.beatsPerMinute) })) }
        }
        Reply.Json(JSONObject().put("type", type).put("records", JSONArray(result)).put("note", "Source records may overlap; steps are not deduplicated totals. Maximum 100 records; narrow the interval if truncated."))
    } }
    private suspend fun <T: Record> read(type: Class<T>, filter: TimeInstantRangeFilter): List<T> = suspendCancellableCoroutine { cont ->
        val manager = app.getSystemService(HealthConnectManager::class.java)
        if (manager == null) { cont.resumeWithException(ErrorCatalog.error("not_supported", "Health Connect is unavailable.")); return@suspendCancellableCoroutine }
        manager.readRecords(ReadRecordsRequestUsingFilters.Builder(type).setTimeRangeFilter(filter).setPageSize(100).build(), app.mainExecutor,
            object : OutcomeReceiver<android.health.connect.ReadRecordsResponse<T>, HealthConnectException> {
                override fun onResult(r: android.health.connect.ReadRecordsResponse<T>) { if (cont.isActive) cont.resume(r.records) }
                override fun onError(e: HealthConnectException) { if (cont.isActive) cont.resumeWithException(ErrorCatalog.error("permission_denied", "Health Connect refused the read. Review permissions on the phone.")) }
            })
    }
}
