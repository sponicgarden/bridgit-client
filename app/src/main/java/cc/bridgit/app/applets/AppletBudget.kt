package cc.bridgit.app.applets

import android.content.Context
import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * One budget per installed applet (docs/spec-budgets.md, phase 1: docs/plans/budgets-phase1-contract.md).
 *
 * The applet is named by its package id ("kitchen-cleanliness", or "dog" for the Pet monitor), so the same applet
 * on every phone of an account shares one budget. The **backend** enforces the money limits: every paid call the
 * phone makes for an applet carries `X-Bridgit-Applet` ([Jev]), and over a limit it answers `applet_budget`.
 *
 * Here on the phone this object only:
 * - parses the package's `budget` (the old `{day_usd, month_usd}` or the new `{limits: [...]}`),
 * - counts this phone's spend and each action's runs for offline display and the budget sync's stats,
 * - refuses locally while the last sync (or refusal) said the applet is `out` and its reset hasn't passed,
 *   so a phone doesn't keep knocking on a closed door.
 */
object AppletBudget {
    /** One money limit: [usd] per [per] (hour, day, week or month), checked for a forecast at the [checkAt] fractions. */
    data class Limit(val id: String, val usd: Double, val per: String, val checkAt: List<Double> = emptyList()) {
        fun json(): JSONObject = JSONObject().put("id", id).put("usd", usd).put("per", per)
            .apply { if (checkAt.isNotEmpty()) put("check_at", JSONArray(checkAt)) }
    }

    val PERIODS = listOf("hour", "day", "week", "month")
    /** When a package gives no budget: $1 a day and $10 a month (as before phase 1). */
    val DEFAULT_LIMITS = listOf(Limit("l1", 1.0, "day"), Limit("l2", 10.0, "month"))
    /** The Pet monitor's own default (contract §7). */
    val DOG_LIMITS = listOf(Limit("l1", 3.0, "month", listOf(0.5)))
    const val DOG = "dog"
    const val MIN_USD = 0.01
    const val MAX_USD = 100.0
    const val MAX_LIMITS = 8
    /** The action a paid call names when it isn't a watch's (a card button, a flow, the Pet monitor). */
    const val MAIN_ACTION = "main"

    private fun prefs(app: BridgeApp) = app.getSharedPreferences("applet_budget", Context.MODE_PRIVATE)

    private val LIMIT_ID = Regex("^[a-z0-9][a-z0-9_-]{0,23}$")

    /**
     * The package's own `budget`, as limits, or null when it has none. `{day_usd, month_usd}` becomes limits l1
     * (day) and l2 (month); `{limits: [{id?, usd, per, check_at?}]}` (or the list itself) is the new form.
     * Throws [AppletPackage.Bad] when malformed.
     */
    fun parse(v: Any?): List<Limit>? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is JSONArray) return parseLimits(v, "budget")
        val o = v as? JSONObject ?: throw AppletPackage.Bad("budget must be {\"limits\": [{\"usd\": 2, \"per\": \"month\"}]} (or {\"day_usd\": 0.5, \"month_usd\": 5}).")
        if (o.has("limits")) {
            o.keys().asSequence().firstOrNull { it != "limits" && it != "on_forecast" }?.let { throw AppletPackage.Bad("budget.$it isn't a budget field (limits, on_forecast).") }
            val list = o.opt("limits") as? JSONArray ?: throw AppletPackage.Bad("budget.limits must be a list of {usd, per}.")
            return parseLimits(list, "budget.limits")
        }
        o.keys().asSequence().firstOrNull { it != "day_usd" && it != "month_usd" }?.let { throw AppletPackage.Bad("budget.$it isn't a budget field (limits, or day_usd and month_usd).") }
        fun usd(k: String, d: Double): Double {
            val x = o.opt(k) ?: return d
            val n = (x as? Number)?.toDouble() ?: throw AppletPackage.Bad("budget.$k must be a number of US dollars.")
            if (n !in MIN_USD..MAX_USD) throw AppletPackage.Bad("budget.$k must be from $MIN_USD to $MAX_USD dollars.")
            return n
        }
        val day = usd("day_usd", 1.0); val month = usd("month_usd", maxOf(10.0, day))
        if (month < day) throw AppletPackage.Bad("budget.month_usd can't be less than budget.day_usd.")
        return listOf(Limit("l1", day, "day"), Limit("l2", month, "month"))
    }

    /** A `limits` list (from a package, the owner's screen or an agent). Ids default to l1, l2, … Throws [AppletPackage.Bad]. */
    fun parseLimits(list: JSONArray, at: String = "limits"): List<Limit> {
        if (list.length() !in 1..MAX_LIMITS) throw AppletPackage.Bad("$at must list 1-$MAX_LIMITS limits.")
        val out = mutableListOf<Limit>()
        for (i in 0 until list.length()) {
            val o = list.opt(i) as? JSONObject ?: throw AppletPackage.Bad("$at[$i] must be {usd, per}.")
            o.keys().asSequence().firstOrNull { it !in setOf("id", "usd", "per", "check_at", "unit") }?.let { throw AppletPackage.Bad("$at[$i].$it isn't a limit field (id, usd, per, check_at).") }
            if (o.has("unit") && o.optString("unit") != "usd") throw AppletPackage.Bad("$at[$i].unit: only usd limits exist so far.")
            val id = if (o.has("id")) o.opt("id") as? String ?: "" else "l${i + 1}"
            if (!LIMIT_ID.matches(id)) throw AppletPackage.Bad("$at[$i].id must be a short name (lowercase letters, digits, - or _).")
            if (out.any { it.id == id }) throw AppletPackage.Bad("$at[$i].id $id is used twice.")
            val usd = (o.opt("usd") as? Number)?.toDouble() ?: throw AppletPackage.Bad("$at[$i].usd must be a number of US dollars.")
            if (!usd.isFinite() || usd !in MIN_USD..MAX_USD) throw AppletPackage.Bad("$at[$i].usd must be from $MIN_USD to $MAX_USD dollars.")
            val per = o.optString("per")
            if (per !in PERIODS) throw AppletPackage.Bad("$at[$i].per must be one of ${PERIODS.joinToString()}.")
            val checkAt = when (val c = o.opt("check_at")) {
                null, JSONObject.NULL -> emptyList()
                is Number -> listOf(c.toDouble())
                is JSONArray -> (0 until c.length()).map { j -> (c.opt(j) as? Number)?.toDouble() ?: throw AppletPackage.Bad("$at[$i].check_at must be fractions of the period, like [0.5].") }
                else -> throw AppletPackage.Bad("$at[$i].check_at must be fractions of the period, like [0.5].")
            }
            if (checkAt.size > 4 || checkAt.any { !(it > 0.0 && it < 1.0) }) throw AppletPackage.Bad("$at[$i].check_at must be up to 4 fractions between 0 and 1.")
            out += Limit(id, usd, per, checkAt)
        }
        return out
    }

    fun json(limits: List<Limit>): JSONArray = JSONArray(limits.map { it.json() })

    /** An instance record's package id ("dog" for the built-in Pet monitor). */
    fun appletOf(r: JSONObject): String = if (r.optString("native") == DOG || r.optBoolean("converted_from_dog")) DOG else r.optString("package_id")

    /** The package's limits for an instance record: its own `budget`, else the default (the Pet monitor's own). */
    fun packageLimits(r: JSONObject): List<Limit> = runCatching { parse(r.optJSONObject("legacy_budget") ?: r.optJSONObject("definition")?.opt("budget")) }.getOrNull()
        ?: if (appletOf(r) == DOG) DOG_LIMITS else DEFAULT_LIMITS

    /** The limits in force as this phone last knew them: the last sync's, else the owner's, else the package's. */
    fun limitsOf(app: BridgeApp?, r: JSONObject): List<Limit> {
        app?.let { synced(it, appletOf(r))?.optJSONArray("limits") }?.let { l -> runCatching { parseLimits(stripLimits(l)) }.getOrNull()?.let { return it } }
        r.optJSONObject("budget_owner")?.let { o -> runCatching { parse(o) }.getOrNull()?.let { return it } }
        return packageLimits(r)
    }

    /** A sync's limits keep only the fields a limit has (used, forecast and the rest are dropped). */
    private fun stripLimits(l: JSONArray) = JSONArray((0 until l.length()).mapNotNull { l.optJSONObject(it) }.map { o ->
        JSONObject().put("id", o.optString("id")).put("usd", o.optDouble("usd")).put("per", o.optString("per")).apply { o.optJSONArray("check_at")?.let { put("check_at", it) } }
    })

    // ---- the last sync's view (the backend's, for every phone of the account) ------------------------------------

    /** The last budget sync's applet object for [applet] (state, limits with used and forecast, actions, …), or null. */
    fun synced(app: BridgeApp, applet: String): JSONObject? =
        prefs(app).getString("sync:$applet", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Keeps the sync's applet object; its state decides the local refusal ([check]). */
    @Synchronized fun saveSynced(app: BridgeApp, applet: String, o: JSONObject) {
        val out = (o.optString("state") == "out")
        val resets = if (out) earliestReset(o) else 0L
        prefs(app).edit().putString("sync:$applet", o.toString()).putLong("out_until:$applet", resets).apply()
    }

    /** When the soonest limit that is used up starts over (or, without that, the soonest reset of any limit). */
    private fun earliestReset(o: JSONObject): Long {
        val l = o.optJSONArray("limits") ?: return 0L
        val all = (0 until l.length()).mapNotNull { l.optJSONObject(it) }
        val over = all.filter { it.optDouble("used_usd", 0.0) >= it.optDouble("usd", Double.MAX_VALUE) + it.optDouble("top_up_usd", 0.0) }
        return (over.ifEmpty { all }).mapNotNull { parseTime(it.optString("resets_at")) }.minOrNull() ?: 0L
    }

    /** A backend refusal (`applet_budget`): this phone stops asking for [applet] until [resetsAt] (ms; 0 = an hour). */
    @Synchronized fun refused(app: BridgeApp, applet: String, resetsAt: Long, now: Long = System.currentTimeMillis()) {
        prefs(app).edit().putLong("out_until:$applet", if (resetsAt > now) resetsAt else now + 3_600_000L).apply()
    }

    /** Until when [applet] is out, as this phone knows it (0 = not out). */
    fun outUntil(app: BridgeApp, applet: String, now: Long = System.currentTimeMillis()): Long =
        prefs(app).getLong("out_until:$applet", 0L).takeIf { it > now } ?: 0L

    /** A sync said ok (or warning): the applet may spend again at once. */
    @Synchronized fun clearOut(app: BridgeApp, applet: String) { prefs(app).edit().remove("out_until:$applet").apply() }

    fun parseTime(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    /**
     * Refuses a paid call for [applet] while the last sync (or a refusal) said it's out and its reset hasn't passed.
     * Does nothing for calls that name no applet, or while the backend's view is unknown (it enforces the limits).
     */
    fun check(app: BridgeApp, applet: String?, now: Long = System.currentTimeMillis()) {
        if (applet.isNullOrEmpty()) return
        val until = outUntil(app, applet, now).takeIf { it > 0 } ?: return
        val name = app.applets.byApplet(applet)?.optString("name")?.ifBlank { null } ?: if (applet == DOG) "The Pet monitor" else applet
        throw cc.bridgit.app.judgment.Jev.Failure("applet_budget",
            "$name used its budget (all devices working for this applet). It starts over at ${java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)}, or the owner can raise the limit in Installed applets.",
            retryable = false, http = 429,
            extra = JSONObject().put("error", "applet_budget").put("applet", applet).put("resets_at", isoOffset(until)).put("local", true))
    }

    fun isoOffset(ms: Long): String = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).withNano(0))

    // ---- this phone's own counts --------------------------------------------------------------------------------

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    /** The next local midnight after [now]. */
    fun nextReset(now: Long): Long = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** What this phone spent for [applet] today and this month (its own share; the sync has all phones'). */
    @Synchronized fun spent(app: BridgeApp, applet: String): Pair<Double, Double> {
        val o = prefs(app).getString("spend:$applet", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return 0.0 to 0.0
        val d = today()
        val day = if (o.optString("day") == d.toString()) o.optDouble("day_usd", 0.0) else 0.0
        val month = if (o.optString("month") == d.toString().take(7)) o.optDouble("month_usd", 0.0) else 0.0
        return day to month
    }

    /** Adds [usd] (a reply's cost_usd) to [applet]'s spend and to [action]'s paid runs today. */
    @Synchronized fun charge(app: BridgeApp, applet: String?, usd: Double, action: String? = null, countRun: Boolean = false) {
        if (applet.isNullOrEmpty() || !usd.isFinite() || usd < 0.0) return
        if (usd > 0.0) {
            val (day, month) = spent(app, applet)
            val d = today()
            prefs(app).edit().putString("spend:$applet", JSONObject().put("day", d.toString()).put("day_usd", day + usd)
                .put("month", d.toString().take(7)).put("month_usd", month + usd).toString()).apply()
        }
        bump(app, applet, action ?: MAIN_ACTION, runs = if (countRun) 1 else 0, paid = 1, usd = usd)
    }

    /** One scheduled run of [action] happened (paid or answered on the phone): counted for the sync's paid share. */
    @Synchronized fun ran(app: BridgeApp, applet: String?, action: String?) {
        if (applet.isNullOrEmpty()) return
        bump(app, applet, action ?: MAIN_ACTION, runs = 1, paid = 0, usd = 0.0)
    }

    /** {day: {action: {runs, paid_runs, usd}}}, today and yesterday only. */
    private fun bump(app: BridgeApp, applet: String, action: String, runs: Int, paid: Int, usd: Double) {
        val key = "stats:$applet"
        val o = prefs(app).getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val d = today().toString()
        val keep = setOf(d, today().minusDays(1).toString())
        o.keys().asSequence().toList().filter { it !in keep }.forEach { o.remove(it) }
        val day = o.optJSONObject(d) ?: JSONObject().also { o.put(d, it) }
        val a = day.optJSONObject(action) ?: JSONObject().also { day.put(action, it) }
        a.put("runs", a.optInt("runs") + runs).put("paid_runs", a.optInt("paid_runs") + paid).put("usd", Math.round((a.optDouble("usd", 0.0) + usd) * 1e7) / 1e7)
        prefs(app).edit().putString(key, o.toString()).apply()
    }

    /** The sync's `stats`: [{action, day, runs, paid_runs, usd, places}] for today and yesterday. [places] per action. */
    fun stats(app: BridgeApp, applet: String, places: (String) -> Int = { 1 }): JSONArray {
        val o = prefs(app).getString("stats:$applet", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return JSONArray()
        val keep = setOf(today().toString(), today().minusDays(1).toString())
        val out = JSONArray()
        for (d in o.keys().asSequence().filter { it in keep }.sorted()) {
            val day = o.optJSONObject(d) ?: continue
            for (a in day.keys().asSequence().sorted()) {
                val s = day.getJSONObject(a)
                out.put(JSONObject().put("action", a).put("day", d).put("runs", s.optInt("runs")).put("paid_runs", s.optInt("paid_runs"))
                    .put("usd", s.optDouble("usd", 0.0)).put("places", places(a)))
            }
        }
        return out
    }

    /** Forgets [applet]'s counts and last sync (the applet was removed). */
    @Synchronized fun forget(app: BridgeApp, applet: String) {
        prefs(app).edit().remove("spend:$applet").remove("stats:$applet").remove("sync:$applet").remove("out_until:$applet").apply()
    }

    /**
     * What an agent and the owner's screen read without a sync: {limits, state, spent_today_usd, spent_month_usd
     * (this phone), set_by, out_until}. Kept with the old day_usd / month_usd when the limits have a day and a month.
     */
    fun status(app: BridgeApp, r: JSONObject): JSONObject {
        val applet = appletOf(r)
        val limits = limitsOf(app, r)
        val (day, month) = spent(app, applet)
        val sync = synced(app, applet)
        val setBy = sync?.optString("source")?.ifEmpty { null } ?: when {
            r.has("budget_owner") -> "owner"; r.optJSONObject("definition")?.has("budget") == true -> "package"; else -> "default" }
        return JSONObject().put("applet", applet).put("limits", json(limits))
            .apply { limits.firstOrNull { it.per == "day" }?.let { put("day_usd", it.usd) }; limits.firstOrNull { it.per == "month" }?.let { put("month_usd", it.usd) } }
            .put("spent_today_usd", round(day)).put("spent_month_usd", round(month))
            .put("state", sync?.optString("state")?.ifEmpty { null } ?: if (outUntil(app, applet) > 0) "out" else "unknown")
            .put("out_until", outUntil(app, applet).takeIf { it > 0 }?.let { isoOffset(it) } ?: JSONObject.NULL)
            .put("set_by", setBy)
    }

    private fun round(v: Double) = Math.round(v * 10000) / 10000.0

    /** "$0.50", "$10". */
    fun money(usd: Double): String = if (usd % 1.0 == 0.0) "$${usd.toLong()}" else "$" + String.format(java.util.Locale.US, "%.2f", usd)

    /** "a day", "a month". */
    fun perWords(per: String): String = when (per) { "hour" -> "an hour"; "week" -> "a week"; "month" -> "a month"; else -> "a day" }

    /**
     * The review sentence: "Stops paid cloud model calls once it has spent $0.50 in a day or $5 in a month (all
     * devices working for this applet together)." (A call already on its way when a limit is reached still completes.)
     */
    fun words(limits: List<Limit>, fromPackage: Boolean): String =
        "Paid cloud model calls stop once this applet has spent " + limits.joinToString(" or ") { "${money(it.usd)} in ${perWords(it.per)}" } +
            ", counting all devices working on it." + (if (fromPackage) "" else " This is Bridgit's default. You can change it in Installed applets.")
}
