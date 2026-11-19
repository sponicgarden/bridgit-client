package cc.bridgit.app.core.profile

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * GET /v1/device/profile (Client Core architecture 4.5): the same shape as Apple's shipped DeviceProfile
 * (schema_version 1) plus the set-up block, checked by contracts/client-core/schemas/device-profile.schema.json.
 * A caller-filtered observation and a UI hint, never an authorization. Pure.
 */
object ProfileBuilder {
    const val SCHEMA_VERSION = 1
    const val TTL_S = 60L

    /** Contexts an Android sensor capability can run in (architecture 4.6); not after a boot or update start until opened. */
    val SENSOR_EXECUTION = listOf("owner_present", "while_open", "background")
    /** Blocked reasons that mean the device can't do it at all, rather than "not now". */
    private val UNSUPPORTED = setOf("no_hardware", "not_supported", "platform_unsupported")

    data class Device(val installId: String?, val relayDeviceId: String?, val name: String, val deviceType: String, val osVersion: String,
                      val appVersion: String, val apiRevision: Int, val services: List<String>, val hardware: Map<String, String>)

    data class Capability(val id: String, val enabled: Boolean, val ready: Boolean, val agentAllowed: Boolean, val blockedReason: String?,
                          val execution: List<String>, val operations: List<Operation>)

    /**
     * One operation of a capability (architecture 4.5, A0333): what it asks beyond its module (a control [scope], a
     * [power]) and, for this caller, whether that is held ([agentAllowed]) and what else blocks it ([blockedReason]),
     * worked out from state without running the operation.
     */
    data class Operation(val method: String, val path: String, val scope: String? = null, val power: String? = null,
                         val agentAllowed: Boolean = true, val blockedReason: String? = null)

    fun capability(c: Capability): JSONObject {
        val supported = c.blockedReason !in UNSUPPORTED
        val blocked = if (!c.agentAllowed) "agent_not_allowed" else c.blockedReason
        val callable = supported && c.enabled && c.ready && c.agentAllowed && blocked == null
        return JSONObject().put("runtime_id", c.id).put("implemented", true).put("supported", supported).put("enabled", c.enabled)
            .put("ready", c.ready).put("agent_allowed", c.agentAllowed).put("callable", callable)
            .put("blocked_reason", blocked ?: JSONObject.NULL)
            .put("constraints", JSONObject().put("execution", JSONArray(c.execution)))
            .put("operations", JSONArray(c.operations.map { operation(it, callable, blocked) }))
    }

    /** An operation is callable only when its capability is; the capability's blocker comes first, then the operation's own. */
    fun operation(o: Operation, capabilityCallable: Boolean, capabilityBlocked: String?): JSONObject {
        val blocked = capabilityBlocked ?: (if (!o.agentAllowed) "agent_not_allowed" else o.blockedReason)
        return JSONObject().put("method", o.method).put("path", o.path)
            .apply { o.scope?.let { put("scope", it) }; o.power?.let { put("power", it) } }
            .put("agent_allowed", o.agentAllowed && capabilityBlocked != "agent_not_allowed")
            .put("callable", capabilityCallable && o.agentAllowed && o.blockedReason == null)
            .put("blocked_reason", blocked ?: JSONObject.NULL)
    }

    fun build(revision: Int, nowMs: Long, device: Device, online: Boolean, inForeground: Boolean,
              capabilities: List<Capability>, setup: JSONObject): JSONObject {
        val now = Instant.ofEpochMilli(nowMs).truncatedTo(ChronoUnit.SECONDS)
        val caps = JSONObject().apply { capabilities.sortedBy { it.id }.forEach { put("v1.${it.id}", capability(it)) } }
        return JSONObject().put("schema_version", SCHEMA_VERSION).put("profile_revision", revision)
            .put("observed_at", now.toString()).put("expires_at", now.plusSeconds(TTL_S).toString())
            .put("device", JSONObject().put("install_id", device.installId ?: JSONObject.NULL)
                .put("relay_device_id", device.relayDeviceId ?: JSONObject.NULL).put("name", device.name)
                .put("device_type", device.deviceType).put("platform", "android").put("os_version", device.osVersion)
                .put("app_version", device.appVersion).put("api_revision", device.apiRevision)
                .put("services", JSONArray(device.services)).put("hardware", JSONObject(device.hardware.toSortedMap() as Map<*, *>)))
            .put("link", JSONObject.NULL)
            .put("availability", JSONObject().put("state", if (online) "online" else "paused").put("in_foreground", inForeground))
            .put("capabilities", caps)
            .put("setup", setup)
    }
}
