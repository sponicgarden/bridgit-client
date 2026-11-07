package cc.bridgit.app.lancamera

import org.json.JSONArray
import org.json.JSONObject

/** Inventory is fast and passive: configured does not mean currently online. Never performs a LAN scan. */
object DeviceInventory {
    fun json(hostModel: String, cameras: List<LanCamera>, canSeeCameras: Boolean, camerasEnabled: Boolean, audioEnabled: Boolean = false, talkEnabled: Boolean = false): JSONObject {
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
            .put("discovery", "Owner-configured devices only. No scan is performed. Camera availability is checked when an action is requested.")
    }
}
