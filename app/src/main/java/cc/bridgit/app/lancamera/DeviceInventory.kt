package cc.bridgit.app.lancamera

import org.json.JSONArray
import org.json.JSONObject

/** Inventory is fast and passive: configured does not mean currently online. Never performs a LAN scan. */
object DeviceInventory {
    fun json(hostModel: String, cameras: List<LanCamera>, canSeeCameras: Boolean, camerasEnabled: Boolean, audioEnabled: Boolean = false, talkEnabled: Boolean = false, lan: JSONObject? = null): JSONObject {
        val devices = JSONArray().put(JSONObject().put("id", "host").put("name", hostModel).put("kind", "android")
            .put("connection", "host").put("availability", "online").put("capabilities_url", "/v1/capabilities"))
        if (canSeeCameras) cameras.forEach { camera ->
            fun action(path: String) = JSONObject().put("method", "POST").put("path", path)
                .put("body", JSONObject().put("camera", camera.id)).put("enabled", camerasEnabled)
            val actions = JSONObject().put("reference_framing", JSONObject().put("method","POST").put("path","/v1/lan-cameras/framing")
                .put("body",JSONObject().put("cameras",JSONArray(listOf(camera.id)))).put("required_fields",JSONArray(listOf("reference")))
                .put("required_capabilities",JSONArray(listOf("lan_camera","judgment"))).put("enabled",camerasEnabled)).put("snapshot", action("/v1/lan-cameras/snapshot"))
                .put("pan_tilt", action("/v1/lan-cameras/move").put("advertised", camera.relativePtz ?: JSONObject.NULL))
                .put("stop", action("/v1/lan-cameras/move").put("body", JSONObject().put("camera", camera.id).put("stop", true))
                    .put("advertised", camera.relativePtz ?: JSONObject.NULL))
                .put("live_video", JSONObject().put("available_in", "More › Network device monitor").put("agent_endpoint", JSONObject.NULL).put("audio", false))
                .put("zoom", JSONObject().put("implemented", false))
                .put("record_video", JSONObject().put("implemented", false))
                .put("listen", action("/v1/lan-cameras/audio/record").put("body",JSONObject().put("camera",camera.id).put("duration_seconds",5))
                    .put("implemented",true).put("enabled",camerasEnabled && audioEnabled && camera.capabilityReport?.optJSONObject("audio_in")?.optBoolean("implemented")==true).put("required_capability","lan_camera_audio")
                    .put("device_state",camera.capabilityReport?.optJSONObject("audio_in") ?: JSONObject().put("state","not_checked")))
                .put("talk", action("/v1/lan-cameras/audio/play").put("implemented",true).put("enabled",camerasEnabled && talkEnabled && (camera.capabilityReport?.optJSONObject("audio_out")?.optBoolean("implemented")==true || camera.capabilityReport?.optJSONObject("tapo_talk")?.optString("state")=="authenticated"))
                    .put("required_capability","lan_camera_speaker").put("required_fields",JSONArray(listOf("pcm_s16le_base64","transport")))
                    .put("device_state",JSONObject().put("onvif_backchannel",camera.capabilityReport?.optJSONObject("audio_out") ?: JSONObject().put("state","not_checked"))
                        .put("tapo",camera.capabilityReport?.optJSONObject("tapo_talk") ?: JSONObject().put("state","not_checked"))))
                .put("probe",action("/v1/lan-cameras/probe"))
            devices.put(camera.publicJson().put("kind", "camera").put("connection", "lan").put("configured", true)
                .put("availability", "not_checked").put("required_capability", "lan_camera").put("enabled", camerasEnabled).put("actions", actions))
        }
        return JSONObject().put("devices", devices).put("external_devices_visible", canSeeCameras)
            .put("external_devices_blocked_reason", if (canSeeCameras) JSONObject.NULL else "agent_not_allowed")
            .put("discovery", "This list does not scan. Camera availability is checked when an action is requested. Speakers and other LAN devices: see lan_path for discovery, enrollment state and the owner steps.")
            .put("lan_path", lan ?: lanPath(false, false, false, false, false, false))
    }

    /** A0183: where an agent finds LAN devices beyond this list. Read-only; each step keeps its own owner switch and agent grant. */
    fun lanPath(discoveryGranted: Boolean, discoveryOn: Boolean, enrollGranted: Boolean, enrollOn: Boolean, controlGranted: Boolean, controlOn: Boolean, enrolledCount: Int = 0): JSONObject {
        fun step(granted: Boolean, on: Boolean, feature: String, vararg endpoints: String) = JSONObject().put("feature", feature)
            .put("agent_granted", granted).put("owner_switch_on", on).put("usable", granted && on)
            .put("endpoints", JSONArray(endpoints.toList()))
            .put("needs", if (granted && on) JSONObject.NULL else if (!granted) "The owner grants this agent $feature in Bridgit (agents may ask, not self-grant)." else "The owner turns $feature on in Bridgit.")
        return JSONObject()
            .put("discover", step(discoveryGranted, discoveryOn, "lan_discovery", "POST /v1/lan/discover", "GET /v1/lan/discovered", "GET /v1/lan/catalog", "GET /v1/lan/packs")
                .put("note", "Each scan needs owner approval on the phone and observations never enroll or verify a device."))
            .put("enroll", step(enrollGranted, enrollOn, "lan_enrollment", "POST /v1/lan/devices/enroll", "POST /v1/lan/devices/remove")
                .put("note", "Verification runs on enroll and the device is saved only if it succeeds. Speakers (WiiM, LinkPlay and other UPnP/DLNA) use adapter upnp_av with no credentials."))
            .put("control", step(controlGranted, controlOn, "lan_control", "GET /v1/lan/devices", "POST /v1/lan/devices/action")
                .put("enrolled_devices", if (controlGranted && controlOn) enrolledCount else JSONObject.NULL)
                .put("note", "GET /v1/lan/devices lists this agent's enrolled devices with verified_at; enrolled devices also appear in this list."))
    }
}
