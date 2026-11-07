package cc.bridgit.app.appdevices

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/** Applet assignments never grant agents direct device access or enroll a device. */
object AppDeviceRules {
    fun shouldStopMonitor(running: Boolean, auto: Boolean, active: List<String>, revoked: Set<String>, allowed: (String) -> Boolean): Boolean =
        running && (auto && revoked.any { it.startsWith("lan:") } || active.any { it.startsWith("lan:") && !allowed(it) })
    fun assignments(raw: JSONArray): Set<String> {
        require(raw.length() <= AppDeviceAccess.apps.size)
        val ids = (0 until raw.length()).map { raw.getString(it) }.toSet()
        require(ids.all { id -> AppDeviceAccess.apps.any { it.id == id } })
        return ids
    }
    fun visible(device: String, cameraIds: Set<String>, ownedIds: Set<String>, cameraGrant: Boolean, controlGrant: Boolean) =
        cameraGrant && device in cameraIds || controlGrant && device in ownedIds
    fun filter(devices: List<JSONObject>, applet: String?, membership: (String) -> Set<String>): JSONArray {
        require(applet == null || AppDeviceAccess.apps.any { it.id == applet })
        return JSONArray(devices.mapNotNull { device ->
            val apps = membership(device.getString("id"))
            if (applet != null && applet !in apps) null else device.put("applets", JSONArray(apps.sorted()))
        })
    }
}
class AppDeviceModule(app: BridgeApp) : Capability(app) {
    override val id = "app_device_access"
    override val titleRes = R.string.app_device_api_title
    override val descriptionRes = R.string.app_device_api_desc
    override val group = "capture"
    override val switchKey = id
    override val endpoints = ALL
    override fun details() = JSONObject().put("default", "New enrollments are assigned to no applets; existing owner choices and one-time legacy migration are preserved.")
        .put("scope", "Only cameras visible under your lan_camera grant and your own LAN enrollments under lan_control. Assignments do not grant direct capture/control.")
    private fun devices(req: Req): List<JSONObject> {
        val cameraGrant = req.agent?.allows("lan_camera") == true
        val controlGrant = req.agent?.allows("lan_control") == true
        // Do not even decrypt stores outside the caller's grants.
        val cameras = if (cameraGrant) app.lanCameras.all() else emptyList()
        val owned = if (controlGrant) app.lanDevices.all().filter { it.agentId == req.agent!!.id } else emptyList()
        val cameraIds = cameras.map { it.id }.toSet(); val ownedIds = owned.map { it.id }.toSet()
        return (cameras.map { JSONObject().put("id", it.id).put("name", it.name).put("kind", "camera") } +
            owned.map { JSONObject().put("id", it.id).put("name", it.name).put("kind", it.adapter) })
            .filter { AppDeviceRules.visible(it.getString("id"), cameraIds, ownedIds, cameraGrant, controlGrant) }
    }
    override fun Route.install(api: ApiServer) = with(api) {
        for (spec in listOf(LIST, LEGACY_LIST)) endpoint(spec) {
            requireUsable()
            val filter = query("applet")
            try {
                Reply.Json(JSONObject().put("applets", JSONArray(AppDeviceAccess.apps.map { JSONObject().put("id", it.id).put("name", app.getString(it.title))
                    .put("execution", if (it.id == AppDeviceAccess.DOG) "Assigned cameras only, including background photos and auto-selection" else "No external-device actions implemented") }))
                    .put("devices", AppDeviceRules.filter(devices(this), filter) { app.appDevices.appsFor(it) })
                    .put("note", "Names, ids and assignments only. No IPs, credentials or network probing. Non-camera assignments do not implement new applet actions."))
            } catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Unknown applet id.") }
        }
        for (spec in listOf(SET, LEGACY_SET)) endpoint(spec) {
            requireUsable()
            val device = body.optString("device")
            if (devices(this).none { it.optString("id") == device }) throw ErrorCatalog.error("not_found", "Device not found or not visible under your device grants.")
            val selected = try { AppDeviceRules.assignments(body.getJSONArray("applets")) }
                catch (_: Exception) { throw ErrorCatalog.error("invalid_parameter", "applets must be an array of installed applet ids; [] revokes all assignments for this device.") }
            app.appDevices.setApps(device, selected)
            Reply.Json(JSONObject().put("device", device).put("applets", JSONArray(selected.sorted())))
        }
    }
    companion object {
        val LIST = EndpointSpec("GET", "/applet-devices", "app_device_access", "Read selectively assigned external devices for installed applets",
            query = listOf(Param("applet", "string", "Optional applet id; returns only devices assigned to it", enum = listOf("dog_monitor", "city_tour"))),
            example = "GET /v1/applet-devices?applet=dog_monitor", returns = "applets[id,name,execution], devices[id,name,kind,applets]. Filtered by agent's device grants and enrollment ownership; no IPs or credentials.")
        val SET = EndpointSpec("POST", "/applet-devices", "app_device_access", "Replace one visible device's applet assignments; separate from enrollment", idempotent = true,
            body = listOf(Param("device", "string", "Visible enrolled external device id", true), Param("applets", "array", "Installed applet ids, dog_monitor or city_tour. [] revokes all; no automatic assignments.", true)),
            example = "POST /v1/applet-devices {\"device\":\"lan:example\",\"applets\":[\"dog_monitor\"]}", returns = "device, applets; revocation immediately stops Pet Monitor when its active cameras lose access. Unrelated devices' assignments stay unchanged.")
        val LEGACY_LIST = LIST.copy(path = "/app-devices", summary = "Legacy alias of GET /v1/applet-devices", example = "GET /v1/app-devices")
        val LEGACY_SET = SET.copy(path = "/app-devices", summary = "Legacy alias of POST /v1/applet-devices")
        val ALL = listOf(LIST, SET, LEGACY_LIST, LEGACY_SET)
    }
}
