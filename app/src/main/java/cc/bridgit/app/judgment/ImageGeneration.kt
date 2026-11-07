package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.feedback.TeamKeys
import cc.bridgit.app.remoteview.AppletCalls
import cc.bridgit.app.applets.AppletBudget
import cc.bridgit.app.outbound.Outbound
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/** Azure credentials stay on the backend. Durable backend idempotency survives app restarts. */
object ImageGeneration {
    private val client by lazy { FeedbackUploader.client.newBuilder().readTimeout(190, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS).retryOnConnectionFailure(false).build() }
    suspend fun call(app: BridgeApp, body: JSONObject?, requestKey: String?, agentId: String): JSONObject {
        val paid = body != null
        if (paid && (requestKey == null || !Regex("[A-Za-z0-9_-]{8,128}").matches(requestKey)))
            throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key must be 8-128 letters, digits, dashes or underscores.")
        if (paid && app.settings.externalCallsPaused) throw ErrorCatalog.error("team_unavailable", "External calls are paused.")
        val applet = Jev.appletFor(app, null, agentId)
        if (paid) { AppletCalls.check(app, applet); AppletBudget.check(app, applet) }
        val identity = try { InstallIdentity.ensure(app, TeamKeys.base) }
            catch (e: Exception) { throw ErrorCatalog.error("images_unavailable", "This install could not authenticate image calls.") }
            ?: throw ErrorCatalog.error("team_unavailable", "This install cannot authenticate image calls.")
        val path = if (paid) "/v1/images/generations" else "/v1/images/usage"
        val method = if (paid) "POST" else "GET"
        val bytes = (body?.toString() ?: "").toByteArray()
        val key = requestKey?.let { MessageDigest.getInstance("SHA-256").digest("$agentId\n$it".toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        val request = Request.Builder().url("${TeamKeys.base}$path")
            .method(method, if (paid) bytes.toRequestBody("application/json".toMediaType()) else null)
            .apply {
                InstallIdentity.headers(app, method, path, bytes).forEach { (k,v) -> header(k,v) }
                Jev.budgetHeaders(applet, Outbound.hint()?.action).forEach { (k,v) -> header(k,v) }
                key?.let { header("Idempotency-Key", it) }
            }.build()
        val call = client.newCall(request)
        val response = suspendCancellableCoroutine<Pair<Int,String>> { cont ->
            AppletCalls.track(app, applet, call)
            cont.invokeOnCancellation { call.cancel(); AppletCalls.untrack(call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    AppletCalls.untrack(call)
                    if (cont.isActive) cont.resumeWithException(ErrorCatalog.error("image_outcome_unknown", "The image outcome is unknown. Retry only with the same Idempotency-Key."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try { val text = r.body?.string().orEmpty(); if (cont.isActive) cont.resume(r.code to text) }
                        catch (e: java.io.IOException) { if (cont.isActive) cont.resumeWithException(ErrorCatalog.error("image_outcome_unknown", "The image response was interrupted. Retry only with the same Idempotency-Key.")) }
                        finally { AppletCalls.untrack(call) }
                    }
                }
            })
        }
        if (response.first == 401) FeedbackUploader.onSignedRefusal(app, response.first, response.second)
        val result = runCatching { JSONObject(response.second) }.getOrNull()
            ?: throw ErrorCatalog.error("image_outcome_unknown", "Unreadable image result. Retry only with the same Idempotency-Key.")
        if (response.first !in 200..299) throw ApiError(response.first, result.optString("error", "team_unavailable"), result.optString("message", "Image generation failed."),
            mapOf("quota" to result.opt("quota"), "resets_at" to result.opt("resets_at")))
        if (paid && !result.optBoolean("replayed")) AppletBudget.charge(app, applet, result.optDouble("cost_usd", 0.0), Outbound.hint()?.action)
        return result
    }
}
