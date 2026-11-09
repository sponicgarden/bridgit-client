package cc.bridgit.app.api

import android.content.Context
import android.os.Build
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.feedback.TeamKeys
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.schedule.ScheduleRules
import cc.bridgit.app.update.UpdateLogic
import cc.bridgit.app.watch.WatchRules
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * `GET /v1/limits`: limits are per-account variables, not constants. The account part (Jev and Clef calls
 * an hour, the monthly budget, paid watch checks) comes from the team backend's GET /v1/limits, signed as
 * this install; the phone part is what this build enforces itself. Agents read this instead of the docs.
 */
object AccountLimits {
    private const val FRESH_MS = 10 * 60_000L
    @Volatile private var cached: JSONObject? = null
    @Volatile private var cachedAt = 0L
    private val client by lazy { FeedbackUploader.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    /** The account limits from the team backend, cached 10 minutes; {available: false, reason} when unknown. */
    fun account(ctx: Context, refresh: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        cached?.takeIf { !refresh && now - cachedAt < FRESH_MS }?.let { return JSONObject(it.toString()).put("cached", true) }
        val fresh = runCatching { fetch(ctx) }.getOrElse { e ->
            return JSONObject().put("available", false).put("reason", e.message ?: e.javaClass.simpleName)
                .put("defaults_apply", "Until the backend answers, the phone uses its default watch limit; Jev and Clef calls are still checked by the backend.")
        }
        cached = fresh; cachedAt = now
        return JSONObject(fresh.toString()).put("cached", false)
    }

    private fun fetch(ctx: Context): JSONObject {
        if (!FeedbackUploader.configured) throw IllegalStateException("This build has no team backend.")
        val path = "/v1/limits"
        InstallIdentity.ensure(ctx, TeamKeys.base) ?: throw IllegalStateException("The phone couldn't prove who it is to the team backend.")
        val headers = InstallIdentity.headers(ctx, "GET", path, ByteArray(0))
        val req = Request.Builder().url("${TeamKeys.base}$path".toHttpUrl()).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("User-Agent", UpdateLogic.userAgent(cc.bridgit.app.BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)).get().build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code != 200) throw IllegalStateException("The team backend answered ${res.code}: ${runCatching { JSONObject(text).optString("message") }.getOrNull() ?: ""}".trim())
            return JSONObject(text).put("available", true)
        }
    }

    /** Paid watch checks an hour for the whole phone: the account's value when known, else this build's default. */
    fun watchPaidChecksPerHour(trust: String?): Int =
        cached?.optJSONObject("limits")?.optJSONObject("watch_paid_checks_per_hour")?.optInt("value", -1)?.takeIf { it >= 0 }
            ?: WatchRules.maxChecksPerHour(trust)

    /** Starts a background refresh when the cached account limits are old, so watches pick up a changed limit. */
    fun refreshSoon(ctx: Context) {
        if (System.currentTimeMillis() - cachedAt < FRESH_MS) return
        cachedAt = System.currentTimeMillis() // one refresh at a time
        Thread({ runCatching { account(ctx, refresh = true) } }, "bridgit-limits").start()
    }

    /** What this build enforces on the phone itself. Pure. */
    fun phone(trust: String?): JSONObject = JSONObject()
        .put("captures_per_minute_per_agent", RateLimiter.CAPTURES_PER_AGENT)
        .put("lan_device_actions_per_minute_per_agent", RateLimiter.DEVICE_ACTIONS_PER_AGENT)
        .put("open_waits_per_agent", 4)
        .put("watches", JSONObject().put("per_agent", WatchRules.MAX_PER_AGENT)
            .put("default_paid_checks_per_hour", WatchRules.maxChecksPerHour(trust))
            .put("min_interval_s", WatchRules.MIN_INTERVAL_S).put("min_interval_s_camera_tally", WatchRules.MIN_INTERVAL_TALLY_S)
            .put("max_expires_s", WatchRules.MAX_EXPIRES_S).put("max_expires_s_with_tally", WatchRules.MAX_EXPIRES_TALLY_S))
        .put("cards", JSONObject().put("html_max_bytes", CardRules.HTML_MAX_BYTES).put("answer_max_bytes", CardRules.RESPOND_MAX_BYTES)
            .put("open_questions_per_agent", InteractionRules.OPEN_QUESTIONS_PER_AGENT).put("max_expires_s", InteractionRules.MAX_EXPIRES_S)
            .put("actions_in_flight", CardActions.MAX_IN_FLIGHT).put("action_reply_max_bytes", CardActions.PAGE_BODY_MAX_BYTES))
        .put("schedule", JSONObject().put("manifest_max_bytes", ScheduleRules.MAX_MANIFEST_BYTES).put("max_actions", ScheduleRules.MAX_ACTIONS))
}
