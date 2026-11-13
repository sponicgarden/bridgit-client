package cc.bridgit.app.core.profile

import cc.bridgit.app.core.contract.ContractVocab
import org.json.JSONArray
import org.json.JSONObject

/**
 * The set-up steps and the state of each (Client Core architecture 4.8; contracts/client-core/vocab/setup-steps.json).
 * One list: GET /v1/device/profile carries it, /v1/health counts what is not done, and set-up screens read it.
 * Pure: the caller gathers [Facts] from the device.
 */
object SetupState {
    const val DONE = "done"
    const val WAITING = "waiting"
    const val NEEDS_OWNER = "needs_owner"
    const val BLOCKED = "blocked"

    data class Facts(
        val deviceNamed: Boolean,
        val listenerBound: Boolean,
        val relayOn: Boolean,
        val agents: Int,
        val emailVerified: Boolean,
        val emailPending: Boolean,
        val notificationsOn: Boolean,
        /** Started by boot, update or a sticky restart while camera or microphone permission is granted (Android withholds them). */
        val sensorsWithheld: Boolean,
        /** Permissions missing for features the owner switched on, as Android names them. */
        val missingPermissions: List<String>,
        val batteryRestricted: Boolean,
        val diagnosticDone: Boolean,
    )

    data class Step(val id: String, val state: String, val where: String? = null, val dependencyKind: String? = null,
                    val dependencyName: String? = null, val api: Pair<String, String>? = null) {
        fun json(): JSONObject = JSONObject().put("id", id).put("state", state).apply {
            if (state != DONE && where != null) put("where", where)
            if (state == BLOCKED && dependencyKind != null) put("dependency", JSONObject().put("kind", dependencyKind).put("name", dependencyName))
            api?.let { (m, p) -> put("api", JSONObject().put("method", m).put("path", p)) }
        }
    }

    private fun step(id: String, f: Facts): Step = when (id) {
        "device_name" -> Step(id, if (f.deviceNamed) DONE else NEEDS_OWNER, "anywhere", api = "POST" to "/v1/owner/configuration/name")
        "reach" -> Step(id, if (f.listenerBound || f.relayOn) DONE else NEEDS_OWNER, "this_device")
        "first_agent" -> Step(id, if (f.agents > 0) DONE else NEEDS_OWNER, "this_device")
        "owner_email" -> Step(id, when { f.emailVerified -> DONE; f.emailPending -> WAITING; else -> NEEDS_OWNER }, "anywhere",
            api = "POST" to "/v1/owner/email")
        "notifications" -> if (f.notificationsOn) Step(id, DONE) else Step(id, BLOCKED, "this_device", "os", "android.permission.POST_NOTIFICATIONS")
        "open_app" -> if (!f.sensorsWithheld) Step(id, DONE) else Step(id, BLOCKED, "this_device", "os", "foreground_service_start")
        "permissions" -> if (f.missingPermissions.isEmpty()) Step(id, DONE)
            else Step(id, BLOCKED, "this_device", "os", f.missingPermissions.distinct().sorted().joinToString(","))
        "battery" -> if (!f.batteryRestricted) Step(id, DONE)
            else Step(id, BLOCKED, "this_device", "os", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        "diagnostic" -> Step(id, if (f.diagnosticDone) DONE else NEEDS_OWNER, "this_device")
        else -> error("no rule for set-up step $id")
    }

    fun steps(f: Facts): List<Step> = ContractVocab.SETUP_STEPS.map { step(it, f) }

    /** The profile's `setup` block. */
    fun json(f: Facts): JSONObject {
        val list = steps(f)
        return JSONObject().put("not_done", list.count { it.state != DONE }).put("steps", JSONArray(list.map { it.json() }))
    }
}
