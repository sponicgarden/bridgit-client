package cc.bridgit.app.feedback

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.core.Agent
import cc.bridgit.app.update.UpdateLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * FBK-15: each paired agent's own key for the Bridgit team's feedback backend (`bgk_...`), so it can
 * file issues, follow them and verify fixes there directly, even when this phone is down. The phone
 * mints it with its install identity (POST /v1/agent-keys, no owner work) and keeps it in private prefs
 * (no backup) per phone agent id. Minting again replaces the key on the backend, so it only mints when it
 * has none or the agent asks to rotate. Removing an agent or rotating its phone key revokes it.
 */
object TeamKeys {
    const val KEY_ENDPOINT = "/v1/issues/team-key"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private fun pref(agentId: String) = "team_key:$agentId"

    /** Why there's no key; [retryable] = try again later, the backend or network was down. */
    class Failure(val reason: String, message: String, val retryable: Boolean = false, val http: Int = 0) : Exception(message)

    val base: String get() = BuildConfig.FEEDBACK_URL.trimEnd('/')

    /** The team backend's public address, even in builds without uploads configured. */
    val publicBase: String get() = base.ifBlank { "https://bridgit-feedback.bridgit-app.workers.dev" }

    /** One line any agent can act on when the phone itself is the problem: no phone, no key, no owner needed. */
    fun noPhoneReport(): String = "File bug reports and feature requests directly with Bridgit servers (Issues Tracking): " +
        "POST $publicBase/v1/agents/register {\"name\",\"model\",\"harness\"} for a key, then POST $publicBase/v1/agent/issues. How: ${cc.bridgit.app.api.Learn.BUGS_URL}"

    /** The no-phone fallback agents can use when this phone can't mint (FBK-15). */
    fun fallbackHint(): String = if (FeedbackUploader.configured)
        "Without the phone: POST $base/v1/agents/register {\"name\",\"model\",\"harness\"} gets you a key; GET $base/v1/agent explains the rest."
    else "Without the phone: POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agents/register {\"name\",\"model\",\"harness\"}."

    /** Where agents read about the backend; also in POST /v1/feedback replies. */
    fun teamApi(): JSONObject = JSONObject().put("base_url", base).put("key_endpoint", KEY_ENDPOINT).put("docs", "$base/v1/agent")

    /** The short loop agents follow with their key (FBK-15); the backend's GET /v1/agent has the details. */
    fun howTo(baseUrl: String): List<String> {
        val b = baseUrl.ifBlank { base }
        return listOf(
            "Send Authorization: Bearer <key> to $b. Keep the key secret: out of output, logs and command lines.",
            "File every bug and every feature request, however small, even if you doubt we can or will do it: POST $b/v1/agent/issues {kind, description}. We'd rather triage too many than miss one.",
            "Follow your issues: GET $b/v1/agent/issues (status=, waiting=true, since=) and GET $b/v1/agent/issues/{id}.",
            "When waiting_for_retest is true, install fixed_in or later, re-run the repro, then POST $b/v1/agent/issues/{id}/verify {verdict: works|still_broken|unverified, tested_version, note}.",
            "Never tell anyone something is fixed until a re-test says works. unverified means you couldn't test it, not a guess at still_broken.",
            "GET $b/v1/agent explains everything else.",
        )
    }

    fun cached(ctx: Context, agentId: String): JSONObject? =
        FeedbackUploader.prefs(ctx).getString(pref(agentId), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("key").startsWith("bgk_") }

    fun forget(ctx: Context, agentId: String): Boolean {
        val p = FeedbackUploader.prefs(ctx)
        if (!p.contains(pref(agentId))) return false
        p.edit().remove(pref(agentId)).apply() // in memory at once; off the caller's thread on disk
        return true
    }

    /** What the phone sends to mint: who the agent is on this phone. Pure, unit-tested. */
    fun mintBody(a: Agent): JSONObject = JSONObject().put("phone_agent_id", a.id).put("name", a.name)
        .put("model", a.model ?: JSONObject.NULL).put("harness", a.harness ?: JSONObject.NULL).put("developer", a.fixer) // the backend reads this as "may read this phone's issues": Fixer Mode

    /** The cached key, or a new one from the backend (blocking network; call off the main thread). */
    @Throws(Failure::class)
    fun get(app: BridgeApp, a: Agent, rotate: Boolean): JSONObject {
        if (!FeedbackUploader.configured) throw Failure("not_configured", "This build of Bridgit has no team backend.")
        if (!app.settings.feedbackUpload) throw Failure("uploads_off", "The owner turned off Settings → Privacy → Send bug reports.")
        if (!rotate) cached(app, a.id)?.let { return it }
        return mint(app, a)
    }

    @Synchronized
    private fun mint(app: BridgeApp, a: Agent): JSONObject {
        val bytes = mintBody(a).toString().toByteArray()
        val url = "$base/v1/agent-keys".toHttpUrl()
        val (code, text) = try {
            val headers = FeedbackUploader.auth(app, "POST", url, bytes, register = true)
                ?: throw Failure("no_install_identity", "The phone couldn't prove who it is to the team backend.", retryable = true)
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .post(bytes.toRequestBody(JSON)).build()
            FeedbackUploader.client.newCall(req).execute().use { res -> res.code to res.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw Failure("team_unreachable", "The team backend can't be reached right now (${e.javaClass.simpleName}).", retryable = true)
        }
        val o = runCatching { JSONObject(text) }.getOrNull()
        if ((code == 200 || code == 201) && o != null && o.optString("key").startsWith("bgk_")) {
            val saved = JSONObject().put("key", o.getString("key")).put("agent_id", o.optString("agent_id"))
                .put("base_url", o.optString("base_url").ifBlank { base }).put("docs", o.optString("docs").ifBlank { "$base/v1/agent" })
                .put("role", o.optString("role", "reporter")).put("minted_at", System.currentTimeMillis())
            FeedbackUploader.prefs(app).edit().putString(pref(a.id), saved.toString()).commit()
            return saved
        }
        if (code == 401) FeedbackUploader.onSignedRefusal(app, code, text)
        val err = o?.optString("error").orEmpty()
        val msg = o?.optString("message").orEmpty().ifBlank { "HTTP $code" }
        throw when {
            code == 429 -> Failure("rate_limited", msg, retryable = true, http = code)
            code == 403 && err == "agent_blocked" -> Failure("agent_blocked", msg, http = code)
            code >= 500 || code == 401 -> Failure("team_unreachable", "The team backend refused for now: $msg", retryable = true, http = code)
            else -> Failure(err.ifBlank { "team_refused" }, msg, http = code)
        }
    }

    /** The agent was removed or its phone key changed: drop the backend key and revoke it in the background. */
    fun retire(ctx: Context, agentId: String) {
        if (!forget(ctx, agentId)) return // never minted here: nothing to revoke
        TeamKeyRevokeWorker.enqueue(ctx, agentId)
    }

    enum class Outcome { DONE, RETRY }

    /** POST /v1/agent-keys/revoke {phone_agent_id}. Best effort: gives up on anything but network or server trouble. */
    fun revoke(app: BridgeApp, agentId: String): Outcome {
        if (!FeedbackUploader.configured) return Outcome.DONE
        val bytes = JSONObject().put("phone_agent_id", agentId).toString().toByteArray()
        val url = "$base/v1/agent-keys/revoke".toHttpUrl()
        return try {
            val headers = FeedbackUploader.auth(app, "POST", url, bytes, register = false) ?: return Outcome.DONE
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .post(bytes.toRequestBody(JSON)).build()
            FeedbackUploader.client.newCall(req).execute().use { res ->
                if (res.code == 429 || res.code >= 500) Outcome.RETRY
                else { if (res.code == 401) FeedbackUploader.onSignedRefusal(app, res.code, res.body?.string().orEmpty()); Outcome.DONE }
            }
        } catch (e: IOException) { Outcome.RETRY }
    }
}

/** Revokes one agent's backend key when there's a network; WorkManager retries with backoff (FBK-15). */
class TeamKeyRevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BridgeApp
        val id = inputData.getString(AGENT) ?: return@withContext Result.success()
        // A rotate or re-mint after this was queued made a new key that must stay alive.
        if (TeamKeys.cached(app, id) != null) return@withContext Result.success()
        if (runAttemptCount > 20) return@withContext Result.success()
        if (TeamKeys.revoke(app, id) == TeamKeys.Outcome.RETRY) Result.retry() else Result.success()
    }

    companion object {
        private const val AGENT = "phone_agent_id"

        fun enqueue(ctx: Context, agentId: String) {
            if (!FeedbackUploader.configured) return
            val req = OneTimeWorkRequestBuilder<TeamKeyRevokeWorker>()
                .setInputData(Data.Builder().putString(AGENT, agentId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("bridgit-team-key-revoke-$agentId", ExistingWorkPolicy.REPLACE, req) }
        }
    }
}
