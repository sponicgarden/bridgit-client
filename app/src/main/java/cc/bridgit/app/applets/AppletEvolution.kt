package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/** Local provenance and conservative merge. This object never reads sensors, calls models or applies packages. */
object AppletEvolution {
    const val GUIDE = "https://bridgit.cc/learn/builder/modifying-applets.md"
    private val missing = Any()
    fun copy(o: JSONObject) = JSONObject(o.toString())
    fun state(r: JSONObject): JSONObject = r.optJSONObject("evolution") ?: JSONObject()
        .put("state", if (linked(r.optJSONObject("source"))) "tracking" else "detached")
        .put("origin", r.optJSONObject("source") ?: JSONObject()).put("baseline_known", false)
        .put("journal", JSONArray()).put("policy_generation", 0)
    private fun linked(s: JSONObject?) = s != null && (s.optString("url").isNotEmpty() || s.optString("kind") == "bundled" || s.optString("template").isNotEmpty())
    fun initialize(r: JSONObject) {
        val e = copy(state(r))
        if (linked(r.optJSONObject("source"))) e.put("baseline", copy(r.getJSONObject("definition"))).put("baseline_known", true)
        r.put("evolution", e)
    }
    fun tracking(r: JSONObject) = state(r).optString("state") == "tracking"
    fun setPolicy(r: JSONObject, policy: String, reason: String) {
        require(policy in setOf("tracking", "paused", "detached")) { "state must be tracking, paused or detached" }
        val e=copy(state(r))
        require(!(e.optString("state") == "detached" && policy != "detached")) { "Reattachment requires an explicit compatibility review; it cannot be resumed automatically." }
        e.put("state",policy).put("policy_generation",e.optLong("policy_generation")+1).put("policy_reason",reason)
        e.optJSONObject("task")?.put("status","cancelled")
        r.put("evolution",e)
    }
    fun record(r: JSONObject, intent: String, acceptance: JSONArray) {
        val e=copy(state(r)); val journal=e.optJSONArray("journal") ?: JSONArray()
        // Bounded local record: never evict requirements silently. Export/archive explicitly before proceeding.
        require(journal.length() < 100) { "The customization journal is full. Export and review it before further edits." }
        journal.put(JSONObject().put("intent",intent).put("acceptance",JSONArray(acceptance.toString())).put("at",System.currentTimeMillis())
            .put("previous_definition",copy(r.getJSONObject("definition"))).put("previous_config",r.optJSONObject("config") ?: JSONObject()))
        e.put("journal",journal);r.put("evolution",e)
    }
    fun change(c: JSONObject?): JSONObject {
        require(c != null) { "change is required: expected_revision, intent, acceptance and evidence. Read $GUIDE" }
        require(c.opt("expected_revision") is Number && c.optLong("expected_revision") >= 0) { "change.expected_revision must be the snapshot revision" }
        require(c.opt("intent") is String && c.getString("intent").isNotBlank() && c.getString("intent").length <= 1000) { "change.intent must describe the owner's requirement (1-1000 characters)" }
        val a=c.optJSONArray("acceptance")
        require(a != null && a.length() in 1..20 && (0 until a.length()).all { a.opt(it) is String && a.getString(it).isNotBlank() && a.getString(it).length<=1000 }) { "change.acceptance needs 1-20 behavioral checks" }
        require(c.opt("evidence") is String && c.getString("evidence").isNotBlank() && c.getString("evidence").length<=4000) { "change.evidence must describe checks actually run (1-4000 characters)" }
        return copy(c)
    }
    fun same(a: Any?, b: Any?): Boolean = when {
        a === missing || b === missing -> a === b
        a is JSONObject && b is JSONObject -> a.keySet()==b.keySet() && a.keySet().all { same(a.opt(it),b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all { same(a.opt(it),b.opt(it)) }
        else -> a == b
    }
    private fun JSONObject.keySet() = keys().asSequence().toSet()
    fun merge(base: JSONObject, personal: JSONObject, incoming: JSONObject): JSONObject {
        val conflicts=JSONArray()
        fun keyed(a: JSONArray): List<String>? {
            val ids=(0 until a.length()).map { (a.opt(it) as? JSONObject)?.optString("id")?.takeIf(String::isNotEmpty) ?: return null }
            return ids.takeIf { it.distinct().size == it.size }
        }
        fun mergeAt(b: Any?, p: Any?, t: Any?, path: String): Any? {
            if (same(p,t)) return p
            if (same(p,b)) return t
            if (same(t,b)) return p
            // Package release numbers are assigned by the controller after merging, not a behavior conflict.
            if (path == "/version") return t
            if (b is JSONObject && p is JSONObject && t is JSONObject) {
                val out=JSONObject()
                (b.keySet()+p.keySet()+t.keySet()).sorted().forEach { k ->
                    val v=mergeAt(if(b.has(k)) b.opt(k) else missing,if(p.has(k)) p.opt(k) else missing,if(t.has(k)) t.opt(k) else missing,"$path/${k.replace("~","~0").replace("/","~1")}")
                    if(v !== missing) out.put(k,v)
                }
                return out
            }
            // Only unordered package component lists have identity semantics; steps and UI order are behavioral.
            if (path in setOf("/processing/watches","/processing/flows","/processing/tables") && b is JSONArray && p is JSONArray && t is JSONArray) {
                val bi=keyed(b);val pi=keyed(p);val ti=keyed(t)
                if (bi!=null && pi!=null && ti!=null) {
                    val out=JSONArray()
                    (pi+ti).distinct().forEach { id ->
                        fun value(a: JSONArray, ids: List<String>)=ids.indexOf(id).let { if(it<0) missing else a.opt(it) }
                        val v=mergeAt(value(b,bi),value(p,pi),value(t,ti),"$path/$id")
                        if(v !== missing) out.put(v)
                    }
                    return out
                }
            }
            conflicts.put(JSONObject().put("path",path).put("kind",if(p === missing || t === missing) "edit_delete" else "both_changed"))
            return p
        }
        val candidate=mergeAt(base,personal,incoming,"") as JSONObject
        return JSONObject().put("candidate",copy(candidate)).put("conflicts",conflicts)
    }
}
