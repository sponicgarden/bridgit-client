package cc.bridgit.app.lancamera

import org.json.JSONObject

/** Bounded portal metadata only. Never copy the raw report, addresses or credentials. */
object PortalCameraFeatures {
    fun json(camera: LanCamera, camerasOn: Boolean, microphoneOn: Boolean, speakerOn: Boolean): JSONObject {
        val report = camera.capabilityReport
        fun state(part: JSONObject?, on: Boolean): String = when {
            part == null -> "not_checked"
            part.optBoolean("implemented") -> if (on) "available" else "off"
            part.optString("state") == "not_advertised" || part.opt("advertised") == false -> "not_advertised"
            part.optString("state") in listOf("unverified", "authentication_or_connection_failed") -> "unverified"
            part.optString("state") == "advertised" || part.opt("advertised") == true -> "not_supported"
            else -> "not_checked"
        }
        val video = state(report?.optJSONObject("video"), camerasOn)
        val output = report?.optJSONObject("audio_out")
        val talk = report?.optJSONObject("tapo_talk")
        val speaker = when {
            output?.optBoolean("implemented") == true || talk?.optString("state") == "authenticated" ->
                if (camerasOn && speakerOn) "available" else "off"
            talk?.optString("state") == "credentials_required" -> "needs_setup"
            talk?.optString("state") == "authentication_or_connection_failed" -> "unverified"
            else -> state(output, camerasOn && speakerOn)
        }
        return JSONObject().put("photo", video).put("video", video)
            .put("microphone", state(report?.optJSONObject("audio_in"), camerasOn && microphoneOn))
            .put("speaker", speaker)
            .put("pan_tilt", state(report?.optJSONObject("pan_tilt"), camerasOn))
            .put("record_video", "not_supported")
    }
}
