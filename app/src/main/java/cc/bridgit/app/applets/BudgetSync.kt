package cc.bridgit.app.applets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.feedback.FeedbackUploader
import cc.bridgit.app.granted
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.update.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The budget sync (docs/plans/budgets-phase1-contract.md §3): the phone's control channel with the backend for
 * every installed applet. The backend never pushes; every 10 minutes while an applet with paid parts is installed
 * (and right after an `applet_budget` refusal or a local change) the phone sends its package budgets, actions and
 * today's and yesterday's stats, and the reply brings the account's limits, use and forecast (all phones), the
 * actions' intervals, and new events and changes, which the phone hands to agents and the owner.
 *
 * Until the backend has the route (404) or while it fails (5xx), the state is "unknown": nothing is refused here,
 * the phone keeps working and tries again at the next interval.
 */
class BudgetSync(private val app: BridgeApp) {
    /** A refusal from a budget route (agents_may_not_change, over_ceiling, rate_limited, out_of_range, …). */
    class Refused(val code: String, val http: Int, message: String, val body: JSONObject) : Exception(message)

    private val prefs = app.getSharedPreferences("budget_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var soonJob: Job? = null
    @Volatile private var loggedUnknown = false
    /** Tests replace the transport: (method, path, body) → (HTTP code, reply). */
    @Volatile internal var transport: ((String, String, JSONObject?) -> Pair<Int, JSONObject>)? = null

    private val enabled: Boolean get() = transport != null || FeedbackUploader.configured

    fun start() {
        if (!enabled) return
        scope.launch {
            delay(45_000)
            while (isActive) {
                if (needed()) runCatching { syncNow() }
                delay(EVERY_MS)
            }
        }
    }

    /** A sync a few seconds from now (after a refusal or a local change); several in a row become one. */
    fun soon(why: String) {
        if (!enabled) return
        soonJob?.cancel()
        soonJob = scope.launch { delay(3_000); runCatching { syncNow() }.onFailure { app.log.system("diagnostic", "applets", "Budget sync after $why failed: ${it.javaClass.simpleName}", level = "warn") } }
    }

    @Volatile private var lastReadSync = 0L
    /** A sync soon, at most once a minute (an agent reading the budget often doesn't hammer the backend). */
    fun soonIfStale(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastReadSync < 60_000L) return
        lastReadSync = now; soon(why)
    }

    /** True while any installed applet can make paid calls (the Pet monitor always can). */
    fun needed(): Boolean = app.applets.all().any { paid(it) }

    private fun paid(r: JSONObject): Boolean = AppletBudget.appletOf(r) == AppletBudget.DOG || runCatching {
        val p = AppletPackage.check(r.getJSONObject("definition"))
        AppletPackage.paid(AppletPackage.compile(p, r.getJSONObject("config")), p.declared)
    }.getOrDefault(false)

    private fun plan(r: JSONObject): AppletPackage.Plan? = runCatching {
        AppletPackage.compile(AppletPackage.check(r.getJSONObject("definition")), r.getJSONObject("config"))
    }.getOrNull()

    /** The applet's actions as the phone runs them (for the Pet monitor: one main action at its sampling interval). */
    fun actionsOf(r: JSONObject): List<AppletActions.Action> =
        if (r.optString("native") == AppletBudget.DOG) listOf(AppletActions.Action(AppletBudget.MAIN_ACTION, DOG_EVERY_S, DOG_EVERY_S, DOG_EVERY_S, "camera", 1, declared = false))
        else AppletActions.of(r.getJSONObject("definition"), plan(r))

    /** Package watch id → running watch id of an instance. */
    private fun watchIds(r: JSONObject): Map<String, String> {
        val ws = r.optJSONObject("watches") ?: return emptyMap()
        return ws.keys().asSequence().associateWith { ws.optJSONObject(it)?.optString("watch_id").orEmpty() }.filterValues { it.isNotEmpty() }
    }

    /** The running watch ids that perform [action] of instance [r]. */
    fun watchesOf(r: JSONObject, action: String): List<String> {
        val def = r.optJSONObject("definition") ?: return emptyList()
        return watchIds(r).filter { (pkgId, _) -> AppletActions.actionOfWatch(def, pkgId) == action }.values.toList()
    }

    /** Places an action runs at on this phone: its watches' cameras (at least 1). */
    private fun places(r: JSONObject, action: String): Int {
        val ws = watchesOf(r, action).mapNotNull { app.watches.byId(it) }
        return ws.flatMap { it.spec.cameraSources.ifEmpty { listOf(if (it.spec.sound != null) "mic" else "phone") } }.distinct().size.coerceAtLeast(1)
    }

    private fun seen(applet: String, k: String) = prefs.getLong("$k:$applet", 0L)

    /** The request body (contract §3). */
    fun request(): JSONObject {
        val applets = JSONArray()
        for (r in app.applets.all().filter { paid(it) }) {
            val applet = AppletBudget.appletOf(r)
            applets.put(JSONObject().put("applet", applet).put("name", r.optString("name"))
                .put("package_budget", JSONObject().put("limits", AppletBudget.json(AppletBudget.packageLimits(r))))
                .put("package_actions", AppletActions.json(actionsOf(r)))
                .put("stats", AppletBudget.stats(app, applet) { a -> places(r, a) })
                .put("acked_revision", seen(applet, "revision")).put("seen_event_id", seen(applet, "event")).put("seen_change_id", seen(applet, "change")))
        }
        return JSONObject().put("timezone", ZoneId.systemDefault().id).put("applets", applets)
    }

    // One successful check per installed instance / edition per sync window. Each
    // paid request still carries the owner and revision for server-side admission.
    private val confirmedEditions = mutableMapOf<String, Pair<String,Long>>()
    @Synchronized internal fun confirmEditionBudget(instance: JSONObject, context: JSONObject) {
        val id = instance.getString("id")
        val edition = listOf("owner","key","revision").joinToString("/") { context.get(it).toString() }
        val now = android.os.SystemClock.elapsedRealtime()
        confirmedEditions[id]?.let { (pin,at) -> if (pin == edition && now-at in 0 until EVERY_MS) return }
        val applet = context.getString("applet")
        val entry = JSONObject().put("applet",applet).put("name",instance.getString("name"))
            .put("package_budget",JSONObject().put("limits",AppletBudget.json(AppletBudget.packageLimits(instance))))
        val body = JSONObject().put("library_owner",context.getString("owner")).put("timezone",ZoneId.systemDefault().id)
            .put("applets",JSONArray().put(entry))
        val (code,reply) = call("POST",SYNC_PATH,body)
        val entries = reply.optJSONArray("applets") ?: JSONArray()
        if (code != 200 || reply.optString("account") != "linked" || (0 until entries.length()).none {
                val row = entries.optJSONObject(it)
                row?.optString("applet") == applet && (row.optJSONArray("limits")?.length() ?: 0) > 0
            }) throw Refused(reply.optString("error","edition_budget_unavailable"),code,reply.optString("message","Could not verify the reviewed account and budget."),reply)
        confirmedEditions[id] = edition to now
    }

    /** One sync now (blocking). Returns the reply, or null while the backend's view is unknown (404, 5xx, offline). */
    fun syncNow(): JSONObject? {
        if (!enabled) return null
        val body = request()
        if (body.getJSONArray("applets").length() == 0) return null
        val (code, reply) = try { call("POST", SYNC_PATH, body) } catch (e: IOException) { unknown("offline"); return null }
        if (code == 404 || code >= 500 || code != 200) { unknown("HTTP $code"); return null }
        loggedUnknown = false
        apply(reply)
        return reply
    }

    private fun unknown(why: String) {
        if (loggedUnknown) return
        loggedUnknown = true
        app.log.system("diagnostic", "applets", "Budget sync: the backend's view is unknown ($why); nothing is refused on the phone, trying again in 10 minutes")
    }

    /** Applies a sync reply: states, pauses and resumes, intervals, events and changes. */
    @Synchronized fun apply(reply: JSONObject) {
        reply.optString("account").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("account", it).putString("timezone", reply.optString("timezone")).apply() }
        val list = reply.optJSONArray("applets") ?: return
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val applet = o.optString("applet").takeIf { it.isNotEmpty() } ?: continue
            val r = app.applets.byApplet(applet) ?: continue
            applyOne(r, applet, o)
        }
    }

    private fun applyOne(r: JSONObject, applet: String, o: JSONObject) {
        // Keep the last changes the owner's screen lists: the reply brings only new ones.
        val recent = JSONArray(recentChanges(applet).toList().let { old -> (old + (o.optJSONArray("changes")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) } } ?: emptyList())) }
            .distinctBy { it.optLong("id") }.sortedBy { it.optLong("id") }.takeLast(MAX_CHANGES))
        AppletBudget.saveSynced(app, applet, JSONObject(o.toString()).put("synced_at", System.currentTimeMillis()).put("recent_changes", recent))
        val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() }
        when (o.optString("state")) {
            "out" -> agentId?.let { id -> app.watches.budgetPause(id, AppletBudget.outUntil(app, applet).takeIf { it > 0 } ?: (System.currentTimeMillis() + 3_600_000L),
                o.optJSONArray("events")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) }.lastOrNull { it.optString("type") == "budget_out" }?.optString("message") }?.ifEmpty { null }
                    ?: "${r.optString("name")} used its budget (all devices working for this applet).") }
            "ok", "warning" -> { AppletBudget.clearOut(app, applet); agentId?.let { app.watches.budgetResume(it) } }
        }
        applyActions(r, o.optJSONArray("actions") ?: JSONArray())
        val revision = o.optLong("revision", 0L)
        if (revision > seen(applet, "revision")) prefs.edit().putLong("revision:$applet", revision).apply()
        deliverEvents(r, applet, o.optJSONArray("events") ?: JSONArray())
        noteChanges(r, applet, o.optJSONArray("changes") ?: JSONArray())
    }

    /**
     * Each action's every_s goes to the watches that perform it, in place (their counts and tallies are kept).
     * Applied on every reply, not only a new revision: setting the same interval again changes nothing, and a
     * watch an update replaced gets its action's interval back.
     */
    private fun applyActions(r: JSONObject, actions: JSONArray) {
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val every = a.optInt("every_s", 0).takeIf { it > 0 } ?: continue
            for (w in watchesOf(r, a.optString("action"))) app.watches.setInterval(w, every)
        }
    }

    /** Who hears an applet's budget events: its own agent and the agent that installed it, else every agent allowed Budgets. */
    fun receivers(r: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        r.optString("agent_id").takeIf { it.isNotEmpty() && app.agents.get(it) != null }?.let { out += it }
        val by = r.optString("requested_by").takeIf { !r.isNull("requested_by") && it.isNotEmpty() && app.agents.get(it) != null }
        if (by != null) out += by
        else app.agents.all().filter { it.connection != cc.bridgit.app.core.Connection.APPLET && it.allows(BudgetModule.ID) && !it.suspended }.forEach { out += it.id }
        return out.toList()
    }

    /** New events (id > seen) become agent events once each, and an Android notification for the owner. */
    private fun deliverEvents(r: JSONObject, applet: String, events: JSONArray) {
        var seen = seen(applet, "event")
        val start = seen
        for (e in (0 until events.length()).mapNotNull { events.optJSONObject(it) }.sortedBy { it.optLong("id") }) {
            val id = e.optLong("id"); if (id <= start) continue
            val type = e.optString("type").takeIf { it in EVENT_TYPES } ?: continue
            val data = JSONObject(e.toString()).put("applet", applet).put("instance_id", r.optString("id")).put("name", r.optString("name"))
            for (agent in receivers(r)) runCatching { app.interactions.event(agent, type, data) }
            notifyOwner(r, applet, "${r.optString("name")}: ${if (type == "budget_out") "budget used up" else "may run out of budget"}",
                e.optString("message").ifEmpty { if (type == "budget_out") "The applet is paused until the limit resets, or raise the limit." else "At this rate it runs out before the period ends." }, id)
            seen = maxOf(seen, id)
        }
        if (seen != start) prefs.edit().putLong("event:$applet", seen).apply()
    }

    /** Changes by agents: the first per applet each day is a notification; later ones go into one daily summary. */
    private fun noteChanges(r: JSONObject, applet: String, changes: JSONArray) {
        var seen = seen(applet, "change")
        val start = seen
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        for (c in (0 until changes.length()).mapNotNull { changes.optJSONObject(it) }.sortedBy { it.optLong("id") }) {
            val id = c.optLong("id"); if (id <= start) continue
            seen = maxOf(seen, id)
            if (c.optString("by_kind") != "agent") continue
            val who = c.optString("by_name").ifEmpty { "An agent" }
            val what = c.optString("summary").ifEmpty { "changed its budget" }
            if (prefs.getString("first_change:$applet", null) != today) {
                prefs.edit().putString("first_change:$applet", today).apply()
                notifyOwner(r, applet, "$who changed ${r.optString("name")}'s budget", what, id + 1_000_000)
            } else {
                val key = "summary:$today"
                val n = prefs.getInt(key, 0) + 1
                prefs.edit().putInt(key, n).putString("summary_last", "$who: $what (${r.optString("name")})").apply()
                notifySummary(n, "$who: $what (${r.optString("name")})")
            }
        }
        if (seen != start) prefs.edit().putLong("change:$applet", seen).apply()
    }

    /** The last changes kept for the owner's screen, oldest first. */
    fun recentChanges(applet: String): List<JSONObject> =
        AppletBudget.synced(app, applet)?.optJSONArray("recent_changes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun openIntent(r: JSONObject, code: Int) = PendingIntent.getActivity(app, code, Intent(app, AppletInstallActivity::class.java)
        .putExtra(AppletInstallActivity.EXTRA_INSTANCE, r.optString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyOwner(r: JSONObject, applet: String, title: String, text: String, id: Long) {
        if (!app.granted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        runCatching {
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + ((applet.hashCode() * 31 + id.hashCode()) and 0xfff),
                NotificationCompat.Builder(app, cc.bridgit.app.interact.InteractionNotifier.CHANNEL_NOTICES).setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openIntent(r, applet.hashCode())).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        }
    }

    private fun notifySummary(n: Int, last: String) {
        if (!app.granted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        runCatching {
            val open = PendingIntent.getActivity(app, SUMMARY_ID, Intent(app, AppletInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // One notification a day, updated in place: "Agents changed budgets 3 more times today".
            app.getSystemService(NotificationManager::class.java).notify(SUMMARY_ID, NotificationCompat.Builder(app, cc.bridgit.app.interact.InteractionNotifier.CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle("Agents changed applet budgets ${if (n == 1) "once more" else "$n more times"} today")
                .setContentText("Last: $last").setOnlyAlertOnce(true).setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        }
    }

    // ---- changes sent to the backend (contract §5) ---------------------------------------------------------------

    /** {kind, id, name}: who asks for a change. */
    fun by(agent: cc.bridgit.app.core.Agent?): JSONObject =
        if (agent == null) JSONObject().put("kind", "owner").put("id", "owner").put("name", "Owner")
        else JSONObject().put("kind", "agent").put("id", agent.id).put("name", agent.name)

    /** PUT /v1/budgets/{applet}/limits. Returns the backend's reply; throws [Refused] or IOException. */
    fun setLimits(applet: String, limits: List<AppletBudget.Limit>, by: JSONObject): JSONObject =
        change("PUT", "/v1/budgets/$applet/limits", JSONObject().put("limits", AppletBudget.json(limits)).put("by", by))

    fun topUp(applet: String, limitId: String, usd: Double, by: JSONObject): JSONObject =
        change("POST", "/v1/budgets/$applet/top-up", JSONObject().put("limit_id", limitId).put("usd", usd).put("by", by))

    /** Owner only: the ceiling agents can't pass, and whether agents may change the budget at all. */
    fun setCeiling(applet: String, ceiling: JSONObject?, agentsMayChange: Boolean): JSONObject =
        change("PUT", "/v1/budgets/$applet/ceiling", JSONObject().put("ceiling", ceiling ?: JSONObject.NULL).put("agents_may_change", agentsMayChange).put("by", by(null)))

    fun setAction(applet: String, action: String, everyS: Int, by: JSONObject, dryRun: Boolean = false): JSONObject =
        change("PUT", "/v1/budgets/$applet/actions/$action", JSONObject().put("every_s", everyS).put("by", by).apply { if (dryRun) put("dry_run", true) }, sync = !dryRun)

    /** The account's time zone (the owner, from the phone). */
    fun setTimezone(zone: String): JSONObject = change("PATCH", "/v1/budgets/settings", JSONObject().put("timezone", zone))

    private fun change(method: String, path: String, body: JSONObject, sync: Boolean = true): JSONObject {
        if (!enabled) throw Refused("team_unavailable", 503, "This build of Bridgit has no team backend.", JSONObject())
        val (code, reply) = call(method, path, body)
        if (code !in 200..299) {
            val err = reply.optString("error").ifEmpty { if (code == 404) "not_supported" else "team_unavailable" }
            throw Refused(err, code, reply.optString("message").ifEmpty {
                if (code == 404) "The Bridgit backend doesn't have budgets yet. Try again later." else "The Bridgit backend answered HTTP $code." }, reply)
        }
        if (sync) soon("a budget change")
        return reply
    }

    /** One signed call to the backend: (HTTP code, JSON reply). Throws IOException when offline. */
    internal fun call(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        transport?.let { return it(method, path, body) }
        return Outbound.on(Outbound.Actor.PHONE, Outbound.Hint("Bridgit team backend", "Budget sync: applet budgets, actions and spend counts")) {
            val bytes = (body?.toString() ?: "").toByteArray()
            val url = (BuildConfig.FEEDBACK_URL.trimEnd('/') + path).toHttpUrl()
            val headers = FeedbackUploader.auth(app, method, url, bytes, register = true) ?: throw IOException("no install key yet")
            val rb = bytes.toRequestBody(JSON)
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE))
                .method(method, if (method == "GET") null else rb).build()
            FeedbackUploader.client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 401) FeedbackUploader.onSignedRefusal(app, res.code, text)
                res.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
            }
        }
    }

    companion object {
        const val SYNC_PATH = "/v1/budgets/sync"
        const val EVERY_MS = 10L * 60_000
        const val MAX_CHANGES = 10
        const val DOG_EVERY_S = 20
        val EVENT_TYPES = setOf("budget_forecast", "budget_out")
        private const val NOTIF_BASE = 48_000
        private const val SUMMARY_ID = 48_999 + 4096
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
