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
 * 8.23 Listening, wake words only (CAP-46/47/48). Off by default; the owner sets the wake words on the phone.
 * Agents read their own wake words and fetch clips; hits reach them as `listen.heard` events.
 */
class WakeWordsModule(app: BridgeApp) : Capability(app) {
    override val id = "wakewords"
    override val titleRes = R.string.cap_wakewords
    override val descriptionRes = R.string.cap_wakewords_desc
    override val group = "capture"
    override val switchKey = WakeListener.SWITCH
    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)
    override val endpoints = listOf(Endpoints.LISTEN, Endpoints.LISTEN_CLIP)
    override fun blockedReason() = app.wake.missing()
    override fun details() = JSONObject().put("wake_words", app.wake.activeWords().size).put("max_wake_words", WakeRules.MAX_WORDS)
        .put("model_ready", app.wake.model.ready).put("on_device_transcription", cc.bridgit.app.planned.Transcriber.available(app))

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.LISTEN) {
            Reply.Json(app.wake.status(agent!!.id))
        }
        endpoint(Endpoints.LISTEN_CLIP) {
            val id = path("id") ?: ""
            val c = app.wake.clip(id, agent!!.id, "wakewords")
                ?: throw ErrorCatalog.error("not_found", "No clip $id for you. Clips stay in memory 10 minutes unless you chose longer (POST /v1/captures/storage), and only for wake words the owner set to send audio.")
            bytesOut = c.wav.size.toLong()
            if (wantsRaw()) Reply.Bytes(c.wav, "audio/wav", mapOf("X-Bridgit-Heard-At" to isoAt(c.at)))
            else Reply.Json(JSONObject().put("heard_at", isoAt(c.at)).put("duration_seconds", Math.round(c.seconds * 10) / 10.0)
                .put("format", "wav").put("sample_rate_hz", WakeRules.SAMPLE_RATE).put("size_bytes", c.wav.size)
                .put("data_base64", Base64.encodeToString(c.wav, Base64.NO_WRAP)))
        }
    }
}
