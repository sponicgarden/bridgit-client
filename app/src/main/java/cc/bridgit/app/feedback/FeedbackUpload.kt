package cc.bridgit.app.feedback

import cc.bridgit.app.outbound.Outbound
import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.update.UpdateLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * FBK-14: sends each issue to the Bridgit team's backend (the `bridgit-feedback` Worker,
 * source in /feedback). Issues are saved on the phone first; this only copies them.
 *
 * The install signs each request with its Keystore key (InstallIdentity, docs/spec-client-identity.md).
 * Until it has registered, or with a backend that predates registration, it falls back to the older
 * random bearer key made here on first use; the backend keeps only that key's hash.
 */
object FeedbackUploader {
    private const val PREFS = "feedback_upload"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    enum class Outcome { SENT, REFUSED, RETRY, LATER }

    val configured: Boolean get() = BuildConfig.FEEDBACK_URL.startsWith("https://")

    /** The install's ID and key, created once and kept in private prefs (excluded from backup). */
    @Synchronized
    fun identity(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("install_id", null)
        val key = p.getString("install_key", null)
        if (id != null && key != null) return id to key
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val newId = UUID.randomUUID().toString()
        val newKey = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        p.edit().putString("install_id", newId).putString("install_key", newKey).commit()
        return newId to newKey
    }

    /** The install's ID and key if this phone has uploaded before; null otherwise (nothing to ask about). */
    fun existingIdentity(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("install_id", null) ?: return null
        val key = p.getString("install_key", null) ?: return null
        return id to key
    }

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal val client get() = http

    /** After the install registered its Keystore key, the older random key is dead on the backend. */
    internal fun forgetLegacy(ctx: Context) { prefs(ctx).edit().remove("install_id").remove("install_key").commit() }

    private val base get() = BuildConfig.FEEDBACK_URL.trimEnd('/')

    /** What the backend said when it refused a signed request, so the caller can react (CID-21). */
    internal fun errorCode(body: String): String = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()

    /**
     * Headers that prove who is calling: the install's signed headers once it has registered,
     * else the older bearer key (backend without registration, or registration refused today).
     * [register] false never makes anything new (status sync asks only about earlier uploads).
     */
    internal fun auth(app: Context, method: String, url: okhttp3.HttpUrl, body: ByteArray, register: Boolean): Map<String, String>? {
        val status = try {
            if (register) InstallIdentity.ensure(app, base) else InstallIdentity.status(app)
        } catch (e: IOException) { throw e // offline: retry later
        } catch (e: Exception) { null } // refused, or a Keystore that can't make the key: keep the older key
        if (status != null) return InstallIdentity.headers(app, method, url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""), body)
        val (id, key) = (if (register) identity(app) else existingIdentity(app)) ?: return null
        return mapOf("Authorization" to "Bearer $key", "X-Bridgit-Install" to id)
    }

    /** A signed request the backend no longer accepts from this install: start over or stop. */
    internal fun onSignedRefusal(app: Context, code: Int, body: String) {
        if (code == 401 && errorCode(body) == "unknown_install") InstallIdentity.forget(app)
    }

    /** What goes over the wire. The agent block lives in the context on the phone and travels separately. */
    fun payload(r: Issue, images: List<ByteArray>): JSONObject {
        val ctx = JSONObject(r.context.toString())
        val agent = ctx.opt("agent") as? JSONObject
        ctx.remove("agent")
        return JSONObject()
            .put("phone_issue_id", r.id).put("kind", r.kind.id).put("description", r.description)
            .put("no_autofix", r.noAutofix).put("status", r.status.id).put("created_at", r.createdAt)
            .put("capture_source", r.captureSource).put("context", ctx).put("agent", agent ?: JSONObject.NULL)
            .put("log", r.log)
            .put("images", JSONArray(images.map { JSONObject().put("data_base64", Base64.encodeToString(it, Base64.NO_WRAP)) }))
    }

    /** What goes over the wire for one history entry (FBK-14 history upload). */
    fun historyPayload(h: HistoryItem): JSONObject = JSONObject()
        .put("phone_history_id", h.id).put("at", h.at).put("kind", h.kind).put("status", h.status.id)
        .put("actor", (h.actor ?: Actor.OWNER).id).put("by", h.by).put("note", h.note ?: JSONObject.NULL)

    /** Sends one history entry of an issue the backend already has ([remoteId]). The store is updated here. */
    fun sendHistory(app: BridgeApp, remoteId: Long, h: HistoryItem): Outcome {
        val bytes = historyPayload(h).toString().toByteArray()
        val url = "$base/v1/issues/$remoteId/history".toHttpUrl()
        return try {
            // The issue went up, so this install already has its identity; never make a new one here.
            val headers = auth(app, "POST", url, bytes, register = false) ?: return Outcome.LATER
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .post(bytes.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                when {
                    res.code == 200 || res.code == 201 -> { app.feedback.markHistory(h.id, UploadState.SENT); Outcome.SENT }
                    res.code == 429 || res.code >= 500 -> Outcome.RETRY
                    res.code == 401 && errorCode(body) in setOf("clock_skew", "unknown_install") -> { onSignedRefusal(app, res.code, body); Outcome.RETRY }
                    // A backend from before history uploads: keep the entry queued for the next run.
                    res.code == 404 && body.contains("No such endpoint") -> Outcome.LATER
                    else -> { app.feedback.markHistory(h.id, UploadState.FAILED); Outcome.REFUSED }
                }
            }
        } catch (e: IOException) { Outcome.RETRY }
    }

    /** Sends one issue. Returns what to do next; the store is updated here. */
    fun send(app: BridgeApp, r: Issue): Outcome = Outbound.on(
        r.agentId?.let { Outbound.Actor.agent(it, r.agent ?: it) } ?: Outbound.Actor.OWNER) { sendNow(app, r) }

    private fun sendNow(app: BridgeApp, r: Issue): Outcome {
        val images = r.images.mapNotNull { app.feedback.imageFile(it)?.readBytes() }
        val bytes = payload(r, images).toString().toByteArray()
        val url = "$base/v1/issues".toHttpUrl()
        return try {
            val headers = auth(app, "POST", url, bytes, register = true) ?: error("no install identity")
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .post(bytes.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                when {
                    res.code == 200 || res.code == 201 -> {
                        val remote = runCatching { JSONObject(body).getLong("id") }.getOrNull() ?: -1L
                        app.feedback.markUploaded(r.id, remote); Outcome.SENT
                    }
                    res.code == 429 || res.code >= 500 -> { app.feedback.markUploadError(r.id, "HTTP ${res.code}", permanent = false); Outcome.RETRY }
                    // A wrong phone clock, or a backend that forgot this install: try again later (CID-21).
                    res.code == 401 && errorCode(body) in setOf("clock_skew", "unknown_install") -> {
                        onSignedRefusal(app, res.code, body)
                        app.feedback.markUploadError(r.id, "HTTP 401 ${errorCode(body)}", permanent = false); Outcome.RETRY
                    }
                    else -> {
                        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                        app.feedback.markUploadError(r.id, "HTTP ${res.code} $msg".trim(), permanent = true); Outcome.REFUSED
                    }
                }
            }
        } catch (e: IOException) {
            app.feedback.markUploadError(r.id, e.javaClass.simpleName, permanent = false); Outcome.RETRY
        }
    }
}

/** Drains the upload queue when there's a network; WorkManager retries with backoff. */
class FeedbackUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BridgeApp
        if (!FeedbackUploader.configured || !app.settings.feedbackUpload) return@withContext Result.success()
        repeat(10) { // at most 200 issues per run; a new issue queues another run
            val batch = app.feedback.pendingUploads()
            if (batch.isEmpty()) return@repeat
            for (r in batch) {
                if (!app.settings.feedbackUpload) return@withContext Result.success()
                if (FeedbackUploader.send(app, r) == FeedbackUploader.Outcome.RETRY) return@withContext Result.retry()
            }
        }
        // Then what happened to uploaded issues since (FBK-14 history upload), oldest first.
        repeat(10) {
            val batch = app.feedback.pendingHistory()
            if (batch.isEmpty()) return@withContext Result.success()
            for (p in batch) {
                if (!app.settings.feedbackUpload) return@withContext Result.success()
                when {
                    p.issueUpload == UploadState.PENDING -> return@withContext Result.retry() // its issue goes first
                    p.issueUpload != UploadState.SENT || p.remoteId == null || p.remoteId < 1 ->
                        app.feedback.markHistory(p.item.id, UploadState.SKIPPED) // its issue never reached the team
                    else -> when (FeedbackUploader.sendHistory(app, p.remoteId, p.item)) {
                        FeedbackUploader.Outcome.RETRY -> return@withContext Result.retry()
                        FeedbackUploader.Outcome.LATER -> return@withContext Result.success()
                        else -> {}
                    }
                }
            }
        }
        Result.success()
    }

    companion object {
        private const val NAME = "bridgit-feedback-upload"

        fun enqueue(ctx: Context) {
            if (!FeedbackUploader.configured) return
            val req = OneTimeWorkRequestBuilder<FeedbackUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req) }
        }
    }
}

/**
 * FBK-14 status sync: asks the backend what the Bridgit team changed on this install's issues since
 * the last cursor, and moves them on the phone (Actor.TEAM, by "Bridgit team", with the team's note).
 * A move to fixed / won't fix / duplicate notifies the owner like a developer agent's would (FBK-11).
 */
object FeedbackStatusSync {
    const val BY = TEAM_BY
    private const val CURSOR = "team_cursor"
    private const val CURSOR_ID = "team_cursor_id" // the last issue id at the cursor time, so ties aren't skipped

    enum class Outcome { DONE, RETRY }

    private const val FRESH_MS = 60_000L
    @Volatile private var lastFresh = 0L
    /** For checks someone is waiting on: gives up after a few seconds and answers from what the phone has. */
    private val quick by lazy { FeedbackUploader.client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    /** One sync at a time, so a stale cursor never replays a team move over a newer one. */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Runs the sync now, at most once a minute, when an agent lists or reads its issues or the owner
     * opens My issues, so a status check is fresh. Blocking; call it off the main thread.
     */
    fun freshen(app: BridgeApp) {
        if (!FeedbackUploader.configured || !app.settings.feedbackUpload) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastFresh < FRESH_MS) return
            lastFresh = now
        }
        // If the background sync is already running, its answer is about to land; don't wait for it.
        if (!lock.tryLock()) return
        try { runCatching { pull(app, quick, maxPages = 1) } } finally { lock.unlock() }
    }

    fun run(app: BridgeApp): Outcome { lock.lock(); try { return pull(app, FeedbackUploader.client, 5) } finally { lock.unlock() } }

    private fun pull(app: BridgeApp, client: OkHttpClient, maxPages: Int): Outcome {
        val prefs = FeedbackUploader.prefs(app)
        repeat(maxPages) { // the backend returns up to 200 changes per call
            val since = prefs.getLong(CURSOR, 0L)
            val sinceId = prefs.getLong(CURSOR_ID, -1L).takeIf { it >= 0 }?.let { "&since_id=$it" }.orEmpty()
            val url = (BuildConfig.FEEDBACK_URL.trimEnd('/') + "/v1/issues/updates?since=$since$sinceId").toHttpUrl()
            val body = try {
                // Only an install that has uploaded before has anything to ask about.
                val headers = FeedbackUploader.auth(app, "GET", url, ByteArray(0), register = false) ?: return Outcome.DONE
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                    .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE)).get().build()
                client.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    when {
                        res.code == 200 -> text
                        res.code == 429 || res.code >= 500 -> return Outcome.RETRY
                        else -> { FeedbackUploader.onSignedRefusal(app, res.code, text); return Outcome.DONE } // 401: unknown install
                    }
                }
            } catch (e: IOException) { return Outcome.RETRY }
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return Outcome.RETRY
            val updates = o.optJSONArray("updates") ?: JSONArray()
            for (i in 0 until updates.length()) apply(app, updates.getJSONObject(i))
            prefs.edit().putLong(CURSOR, o.optLong("cursor", since)).apply {
                if (o.has("cursor_id") && !o.isNull("cursor_id")) putLong(CURSOR_ID, o.getLong("cursor_id")) else remove(CURSOR_ID)
            }.apply()
            if (!o.optBoolean("more", false)) return Outcome.DONE
        }
        return Outcome.DONE
    }

    private fun apply(app: BridgeApp, u: JSONObject) {
        val r = app.feedback.get(u.optLong("phone_issue_id", -1)) ?: return
        val to = StatusMachine.fromTeam(r.status, u.optString("status")) ?: return
        val note = u.optString("team_note", "").takeIf { it.isNotBlank() && !u.isNull("team_note") }
        runCatching { app.feedback.move(r.id, to, Actor.TEAM, BY, note) }
            .onSuccess { app.log.system("audit", "feedback", "The Bridgit team marked report ${r.number} ${to.id}") }
    }
}

/** Runs [FeedbackStatusSync] every few hours, and once at app start. Only while uploads are on. */
class FeedbackStatusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BridgeApp
        if (!FeedbackUploader.configured || !app.settings.feedbackUpload) return@withContext Result.success()
        if (FeedbackStatusSync.run(app) == FeedbackStatusSync.Outcome.RETRY) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "bridgit-feedback-status-periodic"
        private const val NOW = "bridgit-feedback-status-now"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 6 hours, plus one run now. */
        fun schedule(ctx: Context) {
            if (!FeedbackUploader.configured) return
            runCatching {
                val wm = WorkManager.getInstance(ctx)
                wm.enqueueUniquePeriodicWork(PERIODIC, androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<FeedbackStatusSyncWorker>(6, TimeUnit.HOURS)
                        .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
                runNow(ctx)
            }
        }

        fun runNow(ctx: Context) {
            if (!FeedbackUploader.configured) return
            runCatching {
                WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FeedbackStatusSyncWorker>().setConstraints(network)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
            }
        }
    }
}
