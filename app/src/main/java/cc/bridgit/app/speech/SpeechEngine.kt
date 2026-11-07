package cc.bridgit.app.speech

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.feedback.TeamKeys
import cc.bridgit.app.judgment.Jev
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared turn contract: registry selection, explicit cloud consent, no hidden network fallback. */
object SpeechEngine {
    const val MAX_SECONDS = 30
    const val MAX_CHARS = 600
    data class Selection(val model: String, val cloud: Boolean, val json: JSONObject)
    private val client by lazy { FeedbackUploader.client.newBuilder().callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build() }

    suspend fun select(app: BridgeApp, operation: String, allowCloud: Boolean, taskKey:String? = null): Selection {
        val b = post(app, "resolve", JSONObject().put("operation", operation).apply { taskKey?.let { put("task_key",it) } })
        val selection = Selection(b.getString("model"), b.getString("processing") == "cloud", b)
        if (selection.cloud && !allowCloud) throw ErrorCatalog.error("speech_cloud_consent_required", "The registry selected cloud speech. Explicit allow_cloud=true is required; nothing was recorded or sent.")
        if (operation == "hear" && !selection.cloud && !NativeHearing.available(app))
            throw ErrorCatalog.error("speech_unavailable", "The selected offline recognizer is not installed. No cloud fallback was used.")
        return selection
    }

    suspend fun hear(app: BridgeApp, selection: Selection, bytes: ByteArray, language: String?, seconds: Int = MAX_SECONDS): JSONObject {
        if (!selection.cloud) return JSONObject().put("transcript", cc.bridgit.app.planned.Transcriber.transcribe(app, bytes, language))
            .put("model", selection.model).put("processing", "device").put("cost_usd", 0)
        val wav = cc.bridgit.app.planned.Transcriber.cloudWav(app, bytes, seconds)
        return post(app, "hear", JSONObject().put("expected_model", selection.model).put("allow_cloud", true)
            .put("audio_base64", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)).apply { language?.let { put("language", it) } })
    }

    suspend fun synth(app: BridgeApp, selection: Selection, text: String, out: File, language: String?): JSONObject {
        if (text.isBlank() || text.length > MAX_CHARS) throw ErrorCatalog.error("invalid_parameter", "Speech turns take 1 to 600 characters.")
        if (!selection.cloud) {
            if (!app.speaker.tts.synth(text, out, language, 1f, 1f)) throw ErrorCatalog.error("speech_unavailable", "No installed offline voice produced audio.")
            return JSONObject().put("model", selection.model).put("processing", "device").put("cost_usd", 0)
        }
        val b = post(app, "speak", JSONObject().put("expected_model", selection.model).put("allow_cloud", true).put("text", text).apply { if(selection.json.optString("task_key")=="fairytale_narration") put("task_key","fairytale_narration"); language?.let { put("language", it) } })
        val audio = android.util.Base64.decode(b.getString("audio_base64"), android.util.Base64.DEFAULT)
        if (audio.isEmpty() || audio.size > 2_000_000) throw ErrorCatalog.error("speech_unavailable", "Invalid synthesized audio.")
        withContext(Dispatchers.IO) { out.writeBytes(audio) }
        return JSONObject(b.toString()).apply { remove("audio_base64") }
    }

    suspend fun readiness(app: BridgeApp): JSONObject = JSONObject().put("mode", "turn").put("max_seconds", MAX_SECONDS).put("max_chars", MAX_CHARS)
        .put("hearing_on_device", NativeHearing.available(app)).put("speaking", app.speaker.tts.readiness())
        .put("hearing", post(app, "resolve", JSONObject().put("operation", "hear")))
        .put("speaking_model", post(app, "resolve", JSONObject().put("operation", "speak")))

    private suspend fun post(app: BridgeApp, operation: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val applet = Jev.appletFor(app, null, cc.bridgit.app.outbound.Outbound.actor().agentId)
        if (operation != "resolve") cc.bridgit.app.remoteview.AppletCalls.check(app, applet)
        if (operation != "resolve") cc.bridgit.app.applets.AppletBudget.check(app, applet)
        val identity = try { InstallIdentity.ensure(app, TeamKeys.base) }
        catch (e: IOException) { throw ErrorCatalog.error("speech_unavailable", "Speech registration could not be reached. Try again later.") }
        catch (e: InstallIdentity.Refused) { throw ErrorCatalog.error("speech_unavailable", "This install cannot authenticate speech calls.") }
        if (identity == null) throw ErrorCatalog.error("speech_unavailable", "This install cannot authenticate speech calls.")
        val path = "/v1/speech/$operation"
        val bytes = body.toString().toByteArray()
        val req = Request.Builder().url("${TeamKeys.base}$path").post(bytes.toRequestBody("application/json".toMediaType()))
            .apply { InstallIdentity.headers(app, "POST", path, bytes).forEach { (k,v) -> header(k,v) }
                Jev.budgetHeaders(applet, cc.bridgit.app.outbound.Outbound.hint()?.action).forEach { (k,v) -> header(k,v) } }.build()
        val response = suspendCancellableCoroutine<Pair<Int,String>> { cont ->
            val call = client.newCall(req)
            cc.bridgit.app.remoteview.AppletCalls.track(app, if (operation == "resolve") null else applet, call)
            cont.invokeOnCancellation { call.cancel(); cc.bridgit.app.remoteview.AppletCalls.untrack(call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cc.bridgit.app.remoteview.AppletCalls.untrack(call); if (cont.isActive) cont.resumeWithException(ErrorCatalog.error("speech_unavailable", "Speech backend could not be reached.")) }
                override fun onResponse(call: Call, response: Response) { response.use { r ->
                    try { val s = r.body?.string().orEmpty(); if (cont.isActive) cont.resume(r.code to s) }
                    catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                    finally { cc.bridgit.app.remoteview.AppletCalls.untrack(call) }
                } }
            })
        }
        val b = runCatching { JSONObject(response.second) }.getOrNull() ?: throw ErrorCatalog.error("speech_unavailable", "Unreadable speech response.")
        if (response.first != 200) {
            if (response.first == 401) FeedbackUploader.onSignedRefusal(app, response.first, response.second)
            if (b.optString("error") == "applet_budget" && applet != null) {
                cc.bridgit.app.applets.AppletBudget.refused(app, applet, cc.bridgit.app.applets.AppletBudget.parseTime(b.optString("resets_at")) ?: 0L)
                app.budgetSync.soon("applet_budget")
            }
            if (b.optString("error") in setOf("applet_budget", "spend_cap", "rate_limited")) throw cc.bridgit.app.api.ApiError(response.first, b.getString("error"), b.optString("message"), mapOf("resets_at" to b.optString("resets_at")))
            val code = b.optString("error").takeIf { it in setOf("speech_cloud_consent_required", "speech_model_changed", "speech_unavailable") } ?: "speech_unavailable"
            throw ErrorCatalog.error(code, b.optString("message", "Speech turn failed."))
        }
        if (b.has("cost_usd")) cc.bridgit.app.applets.AppletBudget.charge(app, applet, b.optDouble("cost_usd"), cc.bridgit.app.outbound.Outbound.hint()?.action)
        b
    }

    /** Bounded linear resampling shared by local recognition and cloud WAV encoding. */
    fun pcm16k(pcm: ByteArray, rate: Int, channels: Int, maxSeconds: Int = MAX_SECONDS): ByteArray {
        require(rate in 8000..192000 && channels in 1..8 && pcm.size % (channels * 2) == 0)
        val frames = pcm.size / (channels * 2)
        require(frames.toLong() <= rate.toLong() * maxSeconds) { "Speech clip exceeds the authorized duration" }
        val count = (frames.toLong() * 16000 / rate).toInt()
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun sample(frame: Int): Int {
            var sum = 0; repeat(channels) { c -> sum += input.getShort((frame * channels + c) * 2).toInt() }; return sum / channels
        }
        repeat(count) { n ->
            val position = n.toDouble() * rate / 16000
            val frame = position.toInt().coerceAtMost(frames - 1)
            val next = (frame + 1).coerceAtMost(frames - 1)
            val a = sample(frame); val b = sample(next)
            output.putShort((a + (b-a) * (position-frame)).toInt().coerceIn(-32768,32767).toShort())
        }
        return output.array()
    }
    /** Canonical WAV lets the backend verify duration itself. */
    fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val mono = pcm16k(pcm,rate,channels)
        val out = ByteBuffer.allocate(44 + mono.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + mono.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(mono.size).put(mono)
        return out.array()
    }
}
