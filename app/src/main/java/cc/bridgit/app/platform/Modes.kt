package cc.bridgit.app.platform

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's one mode (docs/applet-reach.md D2): a short word like home, away, night or vacation. The owner may add
 * modes; flows read it in `only_if` `{"mode": true, "is": "away"}`, start on a change (`{"type": "mode", "to": "away"}`)
 * and set it with a `set_mode` step (scope `modes.set`). Cards read it as a `{"mode": true}` source. A short log (who
 * set it, when) is kept and shown in Activity. Per phone: satellites have their own.
 */
class Modes(private val app: BridgeApp) {
    companion object {
        val DEFAULTS = listOf("home", "away", "night", "vacation")
        val NAME = Regex("^[a-z][a-z0-9_]{0,23}$")
        const val MAX_MODES = 12
        const val LOG_KEEP = 30
        /** A flow or agent may change the mode at most this many times in [BURST_MS] (a flow on a mode trigger that sets the mode can't spin). */
        const val BURST = 20
        const val BURST_MS = 10 * 60_000L
        private const val CURRENT = "mode:current"
        private const val ADDED = "mode:added"
        private const val LOG = "mode:log"

        /** The stored name of what the owner typed: trimmed, lowercase, each run of spaces one _ ("Late shift" → late_shift). */
        fun normalize(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), "_")

        /** Why [name] can't be added to [existing]: empty, chars, letter, long, exists or max; null when it can. */
        fun problem(name: String, existing: List<String>): String? {
            val n = normalize(name)
            return when {
                n.isEmpty() -> "empty"
                !Regex("^[a-z0-9_]+$").matches(n) -> "chars"
                n.length > 24 -> "long"
                !n[0].isLetter() -> "letter"
                n in existing -> "exists"
                existing.size >= MAX_MODES -> "max"
                else -> null
            }
        }

        /** What a mode name reads as for the owner: "vacation" → "Vacation", "late_shift" → "Late shift". */
        fun label(mode: String): String = mode.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Who changed it: the owner, an applet (by name), an agent, or a flow. */
    data class By(val type: String, val name: String, val agentId: String? = null)

    private val store get() = app.platform.store
    private val listeners = mutableListOf<(from: String, to: String, by: By) -> Unit>()
    fun addListener(l: (String, String, By) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (String, String, By) -> Unit) = synchronized(listeners) { listeners -= l }

    /** The current mode; home until something sets it. */
    fun current(): String = state().optString("mode", "home").ifEmpty { "home" }
    /** {mode, since (ms, 0 when never set), by {type, name}}. */
    fun state(): JSONObject = runCatching { JSONObject(store.setting(CURRENT, "{}")) }.getOrDefault(JSONObject())

    /** Every mode the owner can pick: the four defaults, then the ones the owner added. */
    fun all(): List<String> = DEFAULTS + added()
    fun custom(): List<String> = added()
    private fun added(): List<String> = runCatching { JSONArray(store.setting(ADDED, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    /** The owner adds a mode (lowercase letters, digits and _, at most 24; at most [MAX_MODES] in all). Returns its name. */
    @Synchronized fun add(name: String): String {
        val n = normalize(name)
        if (n in all()) return n
        when (problem(name, all())) {
            null -> Unit
            "max" -> throw ApiError(422, "invalid_mode", "At most $MAX_MODES modes")
            else -> throw ApiError(422, "invalid_mode", "A mode is 1-24 letters, digits, spaces or _, starting with a letter")
        }
        store.setting(ADDED, JSONArray(added() + n).toString(), true)
        return n
    }

    /** Replace custom definitions atomically, using the same names and limits as Add mode. */
    @Synchronized fun replaceCustom(names: List<String>) {
        val checked = names.map(::normalize)
        if (checked.size > MAX_MODES - DEFAULTS.size || checked.distinct().size != checked.size ||
            checked.any { !NAME.matches(it) || it in DEFAULTS })
            throw ApiError(422, "invalid_parameter", "Custom modes must be unique names of 1-24 letters, digits or _, starting with a letter; at most ${MAX_MODES - DEFAULTS.size}; default names cannot be replaced")
        if (current() !in DEFAULTS && current() !in checked)
            throw ApiError(409, "conflict", "Change the current mode before removing its definition")
        if (checked == added()) return
        store.setting(ADDED, JSONArray(checked).toString(), true)
        runCatching { app.platform.changed() }
    }

    /** The owner removes a mode they added (not a default, not the current one). */
    @Synchronized fun remove(name: String): Boolean {
        if (name in DEFAULTS || name == current() || name !in added()) return false
        store.setting(ADDED, JSONArray(added() - name).toString(), true); return true
    }

    /**
     * Sets the mode to [to]. Setting the mode it already is changes nothing and starts nothing (returns false).
     * An unknown mode is refused (unknown_mode); a flow or agent changing it more than [BURST] times in 10 minutes is
     * refused (mode_busy). Mode-triggered flows start after the change is saved.
     */
    fun set(to: String, by: By, now: Long = System.currentTimeMillis()): Boolean {
        val from: String
        synchronized(this) {
            if (to !in all()) throw ApiError(422, "unknown_mode", "$to isn't one of this phone's modes (${all().joinToString()})")
            from = current()
            if (from == to) return false
            if (by.type != "owner") {
                val recent = log().count { it.optJSONObject("by")?.optString("type") != "owner" && now - it.optLong("at") < BURST_MS }
                if (recent >= BURST) throw ApiError(429, "mode_busy", "The mode was changed $BURST times in 10 minutes; try again later")
            }
            val byJson = JSONObject().put("type", by.type).put("name", by.name.take(80))
            store.setting(CURRENT, JSONObject().put("mode", to).put("since", now).put("by", byJson).toString(), true)
            val entry = JSONObject().put("at", now).put("from", from).put("to", to).put("by", byJson)
            store.setting(LOG, JSONArray((listOf(entry) + log()).take(LOG_KEEP)).toString(), true)
        }
        runCatching { app.log.system("audit", "mode", "Mode set to ${label(to)} by ${by.name.take(80)} (was ${label(from)})", data = JSONObject().put("from", from).put("to", to).put("by", by.type)) }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(from, to, by) } }
        runCatching { app.platform.workflows.onMode(from, to, by) }
        runCatching { app.platform.changed() }
        return true
    }

    /** The newest changes first: {at, from, to, by {type, name}}. */
    fun log(): List<JSONObject> = runCatching { JSONArray(store.setting(LOG, "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())

    /** What GET /v1/mode and GET /v2/mode return. */
    fun json(withLog: Boolean = true): JSONObject {
        val s = state()
        return JSONObject().put("mode", current()).put("since", s.optLong("since").takeIf { it > 0 }?.let { isoAt(it) } ?: JSONObject.NULL)
            .put("set_by", s.optJSONObject("by") ?: JSONObject.NULL).put("modes", JSONArray(all()))
            .apply { if (withLog) put("log", JSONArray(log().take(10).map { e -> JSONObject(e.toString()).put("at", isoAt(e.optLong("at"))) })) }
    }
}
