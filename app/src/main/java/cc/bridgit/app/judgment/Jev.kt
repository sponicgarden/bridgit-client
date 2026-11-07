package cc.bridgit.app.judgment

import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.feedback.TeamKeys
import cc.bridgit.app.update.UpdateLogic
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Typed judgments from TypeSafe's Jev (System One) through the team backend's POST /v1/jev.
 * The phone signs with its install identity; the TypeSafe key stays on the backend, never in the
 * app. Send a state (text or JSON) and typed questions; get typed answers with probabilities.
 * Questions follow https://docs.typesafe.ai/primitives, for example
 * `{"safe": {"type": "choice", "instructions": "...", "criteria": {"yes": "...", "no": "..."}}}`.
 * Model choice belongs to the calling applet: it may use Jev, Clef, or call both for the same input.
 * Selecting a route for one call does not disable the other route.
 * Images (since 2 Nov 2026): up to 2 JPEG/PNG [image] assets per call, each referenced in the state or a
 * question (https://docs.typesafe.ai/images). Blocking network; call off the main thread.
 */
object Jev {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Why there's no answer; [retryable] = network, busy or a passing backend problem. */
    class Failure(val reason: String, message: String, val retryable: Boolean = false, val http: Int = 0,
                  /** The backend's whole error body when it says more (applet_budget: applet, limit, resets_at). */
                  val extra: JSONObject? = null) : Exception(message) {
        /** applet_budget: when the applet's budget starts over (ms), or null. */
        val resetsAt: Long? get() = extra?.optString("resets_at")?.let { cc.bridgit.app.applets.AppletBudget.parseTime(it) }
        val applet: String? get() = extra?.optString("applet")?.ifEmpty { null }
    }

    /**
     * Jev's reply: [answers] exactly as TypeSafe sent them, keyed by question id. [actions] are the
     * conditional actions whose conditions held (each with its reason), [skipped] the rest; the
     * backend evaluated them ("bridgit-actions/1", see [JevActions]).
     */
    data class Answer(val model: String, val answers: JSONObject, val inputTokens: Long, val costUsd: Double, val elapsedMs: Long,
                      val actionsSchema: String = "", val actions: JSONArray = JSONArray(), val skipped: JSONArray = JSONArray(),
                      /** Which key paid: "team", or the owner's own provider ("typesafe", "cloudflare", "openrouter"). */
                      val key: String = "team") {
        /** A choice answer and its confidence, or null if missing, the wrong type or not one of [allowed]. */
        fun choice(id: String, allowed: Collection<String>? = null): Pair<String, Double>? {
            val a = answers.optJSONObject(id) ?: return null
            if (a.optString("type") != "choice") return null
            val c = a.opt("choice") as? String ?: return null
            if (allowed != null && c !in allowed) return null
            val conf = (a.opt("confidence") as? Number)?.toDouble() ?: return null
            return if (conf in 0.0..1.0) c to conf else null
        }

        /** A box answer as [left, top, right, bottom, confidence] fractions, or null if missing or malformed. */
        fun box(id: String): List<Double>? {
            val a = answers.optJSONObject(id) ?: return null
            if (a.optString("type") != "box") return null
            val b = a.optJSONObject("box") ?: return null
            val edges = listOf("left", "top", "right", "bottom").map { (b.opt(it) as? Number)?.toDouble() ?: return null }
            val conf = (a.opt("confidence") as? Number)?.toDouble() ?: return null
            return (edges + conf).takeIf { v -> v.all { it.isFinite() && it in 0.0..1.0 } && v[0] < v[2] && v[1] < v[3] }
        }

        /** A noul probability in [0, 1], or null. */
        fun noul(id: String): Double? {
            val a = answers.optJSONObject(id) ?: return null
            if (a.optString("type") != "noul") return null
            return (a.opt("noul") as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }
        }
    }

    /** One image for [requestBody]'s assets: a JPEG (or PNG) file's bytes. Pure. */
    fun image(bytes: ByteArray, format: String = "jpg"): JSONObject {
        require(format in setOf("jpg", "jpeg", "png")) { "format must be jpg or png" }
        return JSONObject().put("type", "image").put("format", format)
            .put("data_base64", java.util.Base64.getEncoder().encodeToString(bytes))
    }

    /** One image for Clef's `images` list: a base64 data URL. Pure. */
    fun dataUrl(bytes: ByteArray, mime: String = "image/jpeg"): String =
        "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * The request body. [state] is a String, JSONObject or JSONArray; [actions] optional rules; [assets]
     * Jev's images keyed by a reference string that appears in the state or a question; [images] Clef's
     * images (data URLs, placed before the state). Pure, unit-tested.
     */
    fun requestBody(state: Any, questions: JSONObject, actions: JSONArray? = null, assets: JSONObject? = null,
                    images: List<String>? = null, audio:List<String>?=null): ByteArray {
        require(state is String || state is JSONObject || state is JSONArray) { "state must be text, a JSON object or a JSON array" }
        require(questions.length() > 0) { "at least one question" }
        return JSONObject().put("state", state).put("questions", questions)
            .apply { if (actions != null && actions.length() > 0) put("actions", actions) }
            .apply { if (assets != null && assets.length() > 0) put("assets", assets) }
            .apply { if (!images.isNullOrEmpty()) put("images", JSONArray(images)) }
            .apply { if (!audio.isNullOrEmpty()) put("audio",JSONArray(audio)) }.toString().toByteArray()
    }

    /** What the backend's reply means. Pure, unit-tested. */
    fun parse(code: Int, text: String): Answer {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (code == 200 && o != null && o.optJSONObject("answers") != null) {
            val usage = o.optJSONObject("usage")
            return Answer(o.optString("model"), o.getJSONObject("answers"), usage?.optLong("input_tokens") ?: 0L,
                o.optDouble("cost_usd", 0.0), o.optLong("elapsed_ms"), o.optString("actions_schema"),
                o.optJSONArray("actions") ?: JSONArray(), o.optJSONArray("skipped") ?: JSONArray(), o.optString("key").ifBlank { "team" })
        }
        throw failure(code, o)
    }

    /** The [Failure] for a reply that isn't an answer. Pure. */
    private fun failure(code: Int, o: JSONObject?): Failure {
        val err = o?.optString("error").orEmpty()
        val msg = o?.optString("message").orEmpty().ifBlank { "HTTP $code" }
        return when {
            code == 200 -> Failure("malformed", "The team backend's answer wasn't readable.", retryable = true, http = code)
            code == 429 && (err == "spend_cap" || err == "clef_quota") -> Failure(err, msg, http = code)
            // An applet's own budget (all the account's phones): not retryable; it starts over at resets_at.
            err == "applet_budget" || err == "service_budget" -> Failure(err, msg, retryable = false, http = code, extra = o)
            code == 429 || (code == 503 && err != "not_configured") -> Failure(err.ifBlank { "busy" }, msg, retryable = true, http = code)
            code >= 500 -> Failure(err.ifBlank { "team_unreachable" }, msg, retryable = code != 503, http = code)
            else -> Failure(err.ifBlank { "refused" }, msg, http = code)
        }
    }

    /** The team backend's routes for decision models with Jev's request and answer shape. */
    const val JEV_ROUTE = "/v1/jev"
    const val GLM_ROUTE = "/v1/glm"
    /** Cloudflare's Clef-omni on Workers AI: the same {state, questions} and answers; shares Jev's limits. */
    const val CLEF_ROUTE = "/v1/clef"

    /**
     * Asks Jev (or Clef, with [route] = [CLEF_ROUTE]). Throws [Failure]. When Bridgit's shared allowance
     * is used up and the owner saved a key that can answer this route ([OwnKeys]), retries once on it.
     */
    @Throws(Failure::class)
    fun ask(app: BridgeApp, state: Any, questions: JSONObject, actions: JSONArray? = null, assets: JSONObject? = null, route: String = JEV_ROUTE,
            images: List<String>? = null, audio: List<String>? = null, applet: String? = null, authorized: () -> Unit = {}): Answer {
        if (!FeedbackUploader.configured) throw Failure("not_configured", "This build of Bridgit has no team backend.")
        cc.bridgit.app.remoteview.AppletCalls.check(app, applet)
        val bytes = requestBody(state, questions, actions, assets, images, audio)
        try {
            return send(app, route, bytes, null, applet, authorized)
        } catch (f: Failure) {
            if (f.reason !in OwnKeys.FALLBACK_REASONS) throw f
            val own = runCatching { OwnKeys(app).headerFor(OwnKeys.routeName(route)) }.getOrNull() ?: throw f
            val provider = own.substringBefore('=')
            try {
                return send(app, route, bytes, own, applet, authorized).also {
                    // The owner's own key paid for this one; the log names the provider, never the key.
                    runCatching { app.log.system("diagnostic", "judgment", "Bridgit's shared allowance is used up: answered on your own $provider key") }
                }
            } catch (g: Failure) {
                throw Failure(g.reason, "${f.message} Your own $provider key didn't work either: ${g.message}", g.retryable, g.http, g.extra)
            }
        }
    }

    /** One signed call to the team backend; [ownKey] is the X-Bridgit-Own-Key value, if any. */
    private fun send(app: BridgeApp, route: String, bytes: ByteArray, ownKey: String?, applet: String?, authorized: () -> Unit): Answer {
        val (code, text) = post(app, route, bytes, ownKey, applet, authorized = authorized)
        return parse(code, text)
    }

    /**
     * The applet a paid call is for, by package id: "dog" for the Pet monitor (its calls pass applet = "dog"), else
     * the installed applet whose own agent is the current actor, else null (an ordinary agent, the owner, the phone).
     */
    fun appletFor(app: BridgeApp, explicit: String?, agentId: String?): String? {
        if (explicit == cc.bridgit.app.applets.AppletBudget.DOG) return explicit
        val id = agentId ?: return null
        return runCatching { app.applets.forAgent(id)?.let(cc.bridgit.app.applets.AppletBudget::appletOf)?.ifEmpty { null } }.getOrNull()
    }

    /** The budget headers of a paid call (contract §1): none when it names no applet; the action defaults to main. Pure. */
    fun budgetHeaders(applet: String?, action: String?): Map<String, String> =
        if (applet.isNullOrEmpty()) emptyMap()
        else mapOf("X-Bridgit-Applet" to applet, "X-Bridgit-Action" to (action?.takeIf { ACTION.matches(it) } ?: cc.bridgit.app.applets.AppletBudget.MAIN_ACTION))

    private val storyCalls=java.util.concurrent.ConcurrentHashMap<String,okhttp3.Call>()
    fun stopStory(agent: String) { synchronized(storyCalls) { storyCalls.remove(agent)?.cancel() } }

    private val ACTION = Regex("^[A-Za-z0-9_-]{1,40}$")

    /** One signed POST to the team backend: (HTTP code, body). Throws [Failure] when it can't be sent. */
    private fun post(app: BridgeApp, route: String, bytes: ByteArray, ownKey: String?, applet: String?, client: okhttp3.OkHttpClient = FeedbackUploader.client, authorized: () -> Unit): Pair<Int, String> {
        cc.bridgit.app.remoteview.AppletCalls.check(app, applet)
        // The applet this call is for (package id): its budget is the backend's to enforce; the phone only refuses
        // while the last sync said it's out, and counts the spend for offline display.
        val budgetApplet = appletFor(app, applet, cc.bridgit.app.outbound.Outbound.actor().agentId)
        val action = cc.bridgit.app.outbound.Outbound.hint()?.action
        cc.bridgit.app.applets.AppletBudget.check(app, budgetApplet)
        val url = "${TeamKeys.base}$route".toHttpUrl()
        val (code, text) = try {
            // Jev needs the signed install identity; the older bearer key isn't enough.
            try { InstallIdentity.ensure(app, TeamKeys.base) } catch (e: InstallIdentity.Refused) { null }
                ?: throw Failure("no_install_identity", "The phone couldn't prove who it is to the team backend.", retryable = true)
            val headers = InstallIdentity.headers(app, "POST", url.encodedPath, bytes)
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .apply { if (ownKey != null) header("X-Bridgit-Own-Key", ownKey) }
                // Not covered by the install signature (method, path and body only), so adding them is safe.
                .apply { budgetHeaders(budgetApplet, action).forEach { (k, v) -> header(k, v) } }
                .post(bytes.toRequestBody(JSON)).build()
            val call=client.newCall(req)
            val actor=cc.bridgit.app.outbound.Outbound.actor().agentId
            if(route=="/v1/story" && actor!=null) synchronized(storyCalls) { authorized(); storyCalls.put(actor,call)?.cancel() }
            try { cc.bridgit.app.remoteview.AppletCalls.execute(app, applet, call, authorized).use { res -> res.code to res.body?.string().orEmpty() } }
            finally { if(route=="/v1/story" && actor!=null) storyCalls.remove(actor,call) }
        } catch (e: cc.bridgit.app.outbound.Outbound.Paused) {
            throw Failure("external_paused", e.message ?: "External calls are paused on this phone.")
        } catch (e: IOException) {
            throw Failure("team_unreachable", "The team backend can't be reached right now (${e.javaClass.simpleName}).", retryable = true)
        }
        if (code == 401) FeedbackUploader.onSignedRefusal(app, code, text)
        if (code == 200) runCatching { JSONObject(text).optDouble("cost_usd", 0.0) }.getOrNull()?.let {
            cc.bridgit.app.applets.AppletBudget.charge(app, budgetApplet, it, action, countRun = applet == cc.bridgit.app.applets.AppletBudget.DOG)
        }
        if (budgetApplet != null && code == 429) runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("error") == "applet_budget" }?.let { o ->
            onAppletBudget(app, o.optString("applet").ifEmpty { budgetApplet }, cc.bridgit.app.applets.AppletBudget.parseTime(o.optString("resets_at")) ?: 0L)
        }
        return code to text
    }

    /** Signed, budgeted service call with write-only provider credentials and no implicit retry. */
    fun external(app: BridgeApp, route: String, body: JSONObject, ownKey: String?, authorized: () -> Unit): JSONObject {
        require(route in setOf("/v1/services/context", "/v1/story"))
        val client = FeedbackUploader.client.newBuilder().apply {
            if (route == "/v1/story") {
                // The response starts only after both scene observation and story writing complete.
                readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                callTimeout(210, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                callTimeout(110, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.build()
        val (code,text)=post(app,route,body.toString().toByteArray(),ownKey,null,client,authorized)
        val reply=runCatching { JSONObject(text) }.getOrNull() ?: throw cc.bridgit.app.api.ApiError(502,"upstream_failed","Unreadable service reply")
        if(code !in 200..299) throw cc.bridgit.app.api.ApiError(code,reply.optString("error","upstream_failed"),reply.optString("message","Service unavailable"))
        authorized()
        return reply
    }

    /** The backend refused for the applet's budget: stop asking until it resets, and sync now (the watches pause themselves). */
    private fun onAppletBudget(app: BridgeApp, applet: String, resetsAt: Long) {
        cc.bridgit.app.applets.AppletBudget.refused(app, applet, resetsAt)
        runCatching { app.budgetSync.soon("applet_budget") }
    }

    /** The backend route that boxes things in a photo; shares Jev's limits and budget. */
    const val LOCATE_ROUTE = "/v1/locate"

    /** One thing found by [locate]: its [label] and box as fractions (0-1) of the photo, origin top left. */
    data class Spot(val label: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double)
    data class Located(val items: List<Spot>, val model: String, val costUsd: Double, val key: String)

    /** /v1/locate's reply. Pure, unit-tested. */
    fun parseLocated(code: Int, text: String): Located {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val list = o?.optJSONArray("items")
        if (code != 200 || o == null || list == null) throw if (code == 200) Failure("malformed", "The team backend's answer wasn't readable.", retryable = true, http = code) else failure(code, o)
        val items = (0 until list.length()).mapNotNull { i ->
            val it = list.optJSONObject(i) ?: return@mapNotNull null
            val b = it.optJSONObject("box") ?: return@mapNotNull null
            val v = listOf("x0", "y0", "x1", "y1").map { k -> (b.opt(k) as? Number)?.toDouble() ?: return@mapNotNull null }
            if (v.any { !it.isFinite() || it < 0 || it > 1 } || v[0] >= v[2] || v[1] >= v[3]) return@mapNotNull null
            Spot(it.optString("label"), v[0], v[1], v[2], v[3])
        }
        return Located(items, o.optString("model"), o.optDouble("cost_usd", 0.0), o.optString("key").ifBlank { "team" })
    }

    /**
     * Where [query] ("the dog", "each dirty dish") is in [jpeg], from a cheap vision model on the team
     * backend. Retries on the owner's own OpenRouter key when the shared allowance is used up. Blocking
     * network; throws [Failure].
     */
    @Throws(Failure::class)
    fun locate(app: BridgeApp, jpeg: ByteArray, query: String, maxItems: Int = 20, applet: String? = null): Located {
        if (!FeedbackUploader.configured) throw Failure("not_configured", "This build of Bridgit has no team backend.")
        cc.bridgit.app.remoteview.AppletCalls.check(app, applet)
        val bytes = JSONObject().put("image", dataUrl(jpeg)).put("query", query).put("max_items", maxItems).toString().toByteArray()
        try {
            return post(app, LOCATE_ROUTE, bytes, null, applet) {}.let { (c, t) -> parseLocated(c, t) }
        } catch (f: Failure) {
            if (f.reason !in OwnKeys.FALLBACK_REASONS) throw f
            val own = runCatching { OwnKeys(app).headerFor(OwnKeys.routeName(LOCATE_ROUTE)) }.getOrNull() ?: throw f
            try {
                return post(app, LOCATE_ROUTE, bytes, own, applet) {}.let { (c, t) -> parseLocated(c, t) }
            } catch (g: Failure) {
                throw Failure(g.reason, "${f.message} Your own OpenRouter key didn't work either: ${g.message}", g.retryable, g.http, g.extra)
            }
        }
    }

    /** The backend route that inventories a photo (items, brands, prices, insurable); shares Jev's limits and budget. */
    const val APPRAISE_ROUTE = "/v1/appraise"
    /** A busy photo takes the model 10-40 s, longer than the backend client's 30 s read timeout. */
    private val slow by lazy { FeedbackUploader.client.newBuilder().readTimeout(65, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** /v1/appraise's reply: the backend's typed items, kept as JSON, each with a checked box. Pure, unit-tested. */
    data class Appraised(val room: String?, val items: List<JSONObject>, val model: String, val costUsd: Double, val key: String)

    fun parseAppraised(code: Int, text: String): Appraised {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val list = o?.optJSONArray("items")
        if (code != 200 || o == null || list == null) throw if (code == 200) Failure("malformed", "The team backend's answer wasn't readable.", retryable = true, http = code) else failure(code, o)
        val items = (0 until list.length()).mapNotNull { i ->
            val it = list.optJSONObject(i) ?: return@mapNotNull null
            val b = it.optJSONObject("box") ?: return@mapNotNull null
            val v = listOf("x0", "y0", "x1", "y1").map { k -> (b.opt(k) as? Number)?.toDouble() ?: return@mapNotNull null }
            if (v.any { !it.isFinite() || it < 0 || it > 1 } || v[0] >= v[2] || v[1] >= v[3] || it.optString("label").isBlank()) return@mapNotNull null
            it
        }
        return Appraised(o.optString("room").takeIf { o.has("room") && !o.isNull("room") && it.isNotBlank() }, items,
            o.optString("model"), o.optDouble("cost_usd", 0.0), o.optString("key").ifBlank { "team" })
    }

    /**
     * A home-inventory reading of [jpeg]: each belonging with category, brand, model, condition, new and used
     * prices (USD), insurable or not, and a box. [room] is the owner's hint. Retries on the owner's own
     * OpenRouter key when the shared allowance is used up. Blocking network; throws [Failure].
     */
    @Throws(Failure::class)
    fun appraise(app: BridgeApp, jpeg: ByteArray, room: String?, maxItems: Int = 20, applet: String? = null, library: JSONObject? = null, authorized: () -> Unit = {}): Appraised {
        if (!FeedbackUploader.configured) throw Failure("not_configured", "This build of Bridgit has no team backend.")
        val bytes = JSONObject().put("image", dataUrl(jpeg)).put("max_items", maxItems).apply { if (!room.isNullOrBlank()) put("room", room); library?.let { put("library",it) } }.toString().toByteArray()
        if (library != null) {
            val (code,body) = post(app,"$APPRAISE_ROUTE/library",bytes,null,applet,slow,authorized)
            return parseAppraised(code,body)
        }
        try {
            return post(app, APPRAISE_ROUTE, bytes, null, applet, slow) {}.let { (c, t) -> parseAppraised(c, t) }
        } catch (f: Failure) {
            if (f.reason !in OwnKeys.FALLBACK_REASONS) throw f
            val own = runCatching { OwnKeys(app).headerFor(OwnKeys.routeName(APPRAISE_ROUTE)) }.getOrNull() ?: throw f
            try {
                return post(app, APPRAISE_ROUTE, bytes, own, applet, slow) {}.let { (c, t) -> parseAppraised(c, t) }
            } catch (g: Failure) {
                throw Failure(g.reason, "${f.message} Your own OpenRouter key didn't work either: ${g.message}", g.retryable, g.http, g.extra)
            }
        }
    }
    /** Signed, budgeted kitchen vision. Provider prompts remain on the private backend. */
    fun kitchen(app: BridgeApp, body: JSONObject, authorized: () -> Unit): JSONObject {
        val route = if (body.has("library")) "/v1/chef/library" else "/v1/chef"
        val (code, text) = post(app, route, body.toString().toByteArray(), null, null, slow, authorized)
        val out = runCatching { JSONObject(text) }.getOrNull()
        if(code != 200 || out == null) throw failure(code, out)
        return out
    }

}
