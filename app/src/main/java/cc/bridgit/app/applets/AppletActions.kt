package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/**
 * An applet's actions (docs/spec-budgets.md section 6, phase 1): what it does over and over and how often. A budget
 * is built from them: the backend projects each action's cost from its interval, places and paid share, and an
 * owner or agent slows an action down (or speeds it up to its `min`) to fit a limit.
 *
 * A package may declare `actions` ({id: {every, min, max, per, priority}}) and each watch may name its `action`.
 * Without `actions`, each watch is its own action (id = the watch id), made from its `interval_s`, so existing
 * applets keep working unchanged.
 */
object AppletActions {
    data class Action(val id: String, val everyS: Int, val minS: Int, val maxS: Int, val per: String, val priority: Int,
                      /** Package watch ids that perform it. */
                      val watches: List<String> = emptyList(), val declared: Boolean = true) {
        /** As the budget sync's package_actions sends it. */
        fun json(): JSONObject = JSONObject().put("action", id).put("every_s", everyS).put("min_s", minS).put("max_s", maxS)
            .put("per", per).put("priority", priority)
    }

    private val ID = Regex("^[a-z][a-z0-9_-]{0,31}$")
    const val MIN_S = 10
    const val MAX_S = 7 * 86_400
    val PER = setOf("phone", "camera")
    /** Fields of the spec's actions that phase 1 reads (the rest are later phases': kept, not used yet). */
    private val FIELDS = setOf("every", "at_most_every", "min", "max", "per", "priority")
    private val LATER = setOf("capability", "cascade", "mode", "min_quality", "when", "clip_s", "bitrate_kbps", "keep_days", "only_with_finding")

    /** The package's own `actions`, or null when it has none. Throws [AppletPackage.Bad] when malformed. */
    fun parse(v: Any?): Map<String, Action>? {
        if (v == null || v == JSONObject.NULL) return null
        val o = v as? JSONObject ?: throw AppletPackage.Bad("actions must be {\"<id>\": {\"every\": 600, \"min\": 300, \"max\": 3600, \"per\": \"camera\", \"priority\": 1}}.")
        if (o.length() !in 1..16) throw AppletPackage.Bad("actions must name 1-16 actions.")
        val out = LinkedHashMap<String, Action>()
        for (id in o.keys()) {
            if (!ID.matches(id)) throw AppletPackage.Bad("actions.$id: an action id is a short name (lowercase letters, digits, - or _, starting with a letter).")
            val a = o.opt(id) as? JSONObject ?: throw AppletPackage.Bad("actions.$id must be {every, min, max, per, priority}.")
            a.keys().asSequence().firstOrNull { it !in FIELDS && it !in LATER }?.let { throw AppletPackage.Bad("actions.$id.$it isn't an action field (${FIELDS.joinToString()}).") }
            fun secs(k: String): Int? = a.opt(k)?.let { x ->
                val n = (x as? Number)?.toDouble() ?: throw AppletPackage.Bad("actions.$id.$k must be a number of seconds.")
                if (!n.isFinite() || n < MIN_S || n > MAX_S || n % 1.0 != 0.0) throw AppletPackage.Bad("actions.$id.$k must be whole seconds from $MIN_S to $MAX_S.")
                n.toInt()
            }
            val every = secs("every") ?: secs("at_most_every") ?: throw AppletPackage.Bad("actions.$id needs every (seconds between runs).")
            val min = secs("min") ?: every
            val max = secs("max") ?: maxOf(every, 86_400)
            if (min > every || every > max) throw AppletPackage.Bad("actions.$id: min ≤ every ≤ max (seconds).")
            val per = a.optString("per", "phone")
            if (per !in PER) throw AppletPackage.Bad("actions.$id.per must be phone or camera.")
            val priority = a.opt("priority")?.let { (it as? Number)?.toInt()?.takeIf { p -> p in 0..9 } ?: throw AppletPackage.Bad("actions.$id.priority must be 0-9 (lowest is slowed first).") } ?: 1
            out[id] = Action(id, every, min, max, per, priority)
        }
        return out
    }

    /** Checks the watches' `action` names against the package's actions. Throws [AppletPackage.Bad]. */
    fun checkWatches(d: JSONObject) {
        val declared = parse(d.opt("actions"))
        val ws = d.optJSONObject("processing")?.optJSONArray("watches") ?: return
        for (i in 0 until ws.length()) {
            val w = ws.optJSONObject(i) ?: continue
            if (!w.has("action")) continue
            val name = w.opt("action") as? String ?: throw AppletPackage.Bad("processing.watches[$i].action must name an action.")
            if (declared == null) throw AppletPackage.Bad("processing.watches[$i].action names $name, but the package has no actions.")
            if (name !in declared) throw AppletPackage.Bad("processing.watches[$i].action: $name isn't in actions (${declared.keys.joinToString()}).")
        }
    }

    /** The action package watch [watchId] performs: its own `action`, else its id when that is an action, else main. */
    fun actionOfWatch(definition: JSONObject, watchId: String): String {
        val ws = definition.optJSONObject("processing")?.optJSONArray("watches")
        val entry = ws?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("id") == watchId } }
        (entry?.opt("action") as? String)?.let { return it }
        val declared = runCatching { parse(definition.opt("actions")) }.getOrNull() ?: return watchId
        return if (watchId in declared) watchId else if (AppletBudget.MAIN_ACTION in declared) AppletBudget.MAIN_ACTION else declared.keys.first()
    }

    /**
     * Every action of an installed applet: the declared ones (with the watches naming them), or one per watch made
     * from its compiled request ([plan]). An applet with no watches has one action, main.
     */
    fun of(definition: JSONObject, plan: AppletPackage.Plan?): List<Action> {
        val declared = runCatching { parse(definition.opt("actions")) }.getOrNull()
        val watches = plan?.watches ?: emptyList()
        if (declared != null) return declared.values.map { a -> a.copy(watches = watches.map { it.first }.filter { actionOfWatch(definition, it) == a.id }) }
        if (watches.isEmpty()) return if (plan != null && userDriven(plan)) emptyList()
            else listOf(Action(AppletBudget.MAIN_ACTION, 600, 300, 86_400, "phone", 1, declared = false))
        return watches.map { (id, req) -> derived(id, req) }
    }

    /** Trigger types that start a flow only when someone uses the applet (a card button or another flow). */
    private val ON_DEMAND = setOf("called", "button")

    /**
     * An applet that does nothing on its own (owner, 5 Nov 2026: the Insurance inventory showed "Every 10 minutes"):
     * no watches, no schedule, and only flows a card button or another flow starts. It has no actions to schedule.
     */
    fun userDriven(plan: AppletPackage.Plan): Boolean = plan.watches.isEmpty() && plan.schedule == null && plan.native == null &&
        plan.flows.all { (_, f) -> f.optJSONObject("trigger")?.optString("type") in ON_DEMAND }

    /** A watch's own action when the package declares none: its interval, down to half of it, up to a day. */
    fun derived(id: String, req: JSONObject): Action {
        val data = req.optJSONArray("data")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList()
        val sound = "sound" in data || req.has("sound")
        val every = if (sound) cc.bridgit.app.sound.SoundRules.MIN_GAP_S.toInt()
            else (req.opt("interval_s") as? Number)?.toInt() ?: cc.bridgit.app.watch.WatchRules.DEFAULT_INTERVAL_S
        val fastOk = req.has("tally") && (req.has("camera") || "camera" in data) && req.optJSONObject("local")?.optBoolean("change_check", true) != false
        val floor = minOf(every, if (fastOk) cc.bridgit.app.watch.WatchRules.MIN_INTERVAL_TALLY_S else cc.bridgit.app.watch.WatchRules.MIN_INTERVAL_S)
        val min = if (sound) every else maxOf(floor, every / 2)
        val max = if (sound) every else maxOf(every, 86_400)
        return Action(id, every, min, max, if ("camera" in data || req.has("camera") || req.has("situation")) "camera" else "phone", 1, listOf(id), declared = false)
    }

    fun json(actions: List<Action>) = JSONArray(actions.map { it.json() })
}
