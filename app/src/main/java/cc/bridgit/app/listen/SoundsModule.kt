package cc.bridgit.app.listen

import android.Manifest
import android.util.Base64
import io.ktor.server.routing.Route
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.capability.wantsRaw
import org.json.JSONObject

/**
 * 8.23 Listening, "listen for any sounds": the phone tells one agent when the room gets loud, with a short clip
 * if the owner allows it. Off by default; the owner picks the agent and how loud on the phone. Shares the
 * microphone loop with wake words.
 */
class SoundsModule(app: BridgeApp) : Capability(app) {
    override val id = "sounds"
    override val titleRes = R.string.cap_sounds
    override val descriptionRes = R.string.cap_sounds_desc
    override val group = "capture"
    override val switchKey = WakeListener.SOUND_SWITCH
    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)
    override val endpoints = listOf(Endpoints.SOUNDS, Endpoints.SOUNDS_CLIP)
    override fun blockedReason() = app.wake.soundsMissing()
    override fun details() = JSONObject().put("cooldown_seconds", WakeRules.SOUND_COOLDOWN_MS / 1000).put("max_seconds", WakeRules.SOUND_MAX_MS / 1000)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SOUNDS) { Reply.Json(app.wake.soundStatus(agent!!.id)) }
        endpoint(Endpoints.SOUNDS_CLIP) {
            val id = path("id") ?: ""
            val c = app.wake.clip(id, agent!!.id, "sounds")
                ?: throw ErrorCatalog.error("not_found", "No clip $id for you. Clips stay in memory 10 minutes unless you chose longer (POST /v1/captures/storage), and only when the owner lets sounds send audio.")
            bytesOut = c.wav.size.toLong()
            if (wantsRaw()) Reply.Bytes(c.wav, "audio/wav", mapOf("X-Bridgit-Heard-At" to isoAt(c.at)))
            else Reply.Json(JSONObject().put("heard_at", isoAt(c.at)).put("duration_seconds", Math.round(c.seconds * 10) / 10.0)
                .put("format", "wav").put("sample_rate_hz", WakeRules.SAMPLE_RATE).put("size_bytes", c.wav.size)
                .put("data_base64", Base64.encodeToString(c.wav, Base64.NO_WRAP)))
        }
    }
}
