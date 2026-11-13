package cc.bridgit.app.core.session

import org.json.JSONArray
import org.json.JSONObject

/**
 * viewer.model/1 (Client Core architecture 5.2): what a viewer shows of another device, built on this side
 * from the unchanged replies of the /v1/portal routes. The same mapping as tools/client-core/viewer.mjs; proved against
 * contracts/client-core/viewer/ by ViewerModelTest. Pure.
 */
object ViewerModel {
    const val ID = "viewer.model/1"

    private fun str(o: JSONObject?, k: String): Any = o?.opt(k).let { if (it is String && it.isNotEmpty()) it else JSONObject.NULL }
    private fun num(o: JSONObject?, k: String): Any = o?.opt(k).let { if (it is Number && !(it is Double && (it.isNaN() || it.isInfinite()))) it else JSONObject.NULL }
    /** A time where 0 means "never": 0 and missing both become null. */
    private fun time(o: JSONObject?, k: String): Any = num(o, k).let { if (it is Number && it.toDouble() == 0.0) JSONObject.NULL else it }
    private fun orElse(a: Any, b: Any): Any = if (a == JSONObject.NULL) b else a
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    fun from(home: JSONObject?, agents: JSONObject? = null, applets: JSONObject? = null, logs: JSONObject? = null): JSONObject {
        val d = home?.optJSONObject("device")
        val b = home?.optJSONObject("battery")
        val osName = orElse(str(d, "os_name"), if (str(d, "android") != JSONObject.NULL) "android" else JSONObject.NULL)
        val battery = if (b != null && num(b, "percent") != JSONObject.NULL)
            JSONObject().put("percent", b.get("percent")).put("charging", b.opt("charging") == true) else JSONObject.NULL
        val connections = JSONObject()
        home?.optJSONObject("connections")?.let { c -> c.keys().asSequence().sorted().forEach { k -> (c.opt(k) as? String)?.let { connections.put(k, it) } } }
        return JSONObject().put("model", ID)
            .put("device", JSONObject().put("name", str(d, "name")).put("model", str(d, "model")).put("manufacturer", str(d, "manufacturer"))
                .put("device_type", orElse(str(d, "device_type"), str(d, "type")))
                .put("os", JSONObject().put("name", osName).put("version", orElse(str(d, "os_version"), str(d, "android"))))
                .put("app_version", str(d, "app_version")))
            .put("battery", battery)
            .put("paused", home?.opt("paused") == true)
            .put("connections", connections)
            .put("features", JSONArray(home?.optJSONArray("features").objects().map { f ->
                JSONObject().put("id", f.opt("id")).put("name", orElse(str(f, "name"), f.opt("id"))).put("group", str(f, "group"))
                    .put("state", orElse(str(f, "state"), "unknown")) }))
            .put("agents", JSONArray(agents?.optJSONArray("agents").objects().map { a ->
                JSONObject().put("id", a.opt("id")).put("name", orElse(str(a, "name"), a.opt("id")))
                    .put("transport", orElse(str(a, "transport"), str(a, "mode"))).put("paused", a.opt("paused") == true)
                    .put("last_used_at", time(a, "last_used_at")) }))
            .put("applets", JSONArray(applets?.optJSONArray("applets").objects().map { a ->
                JSONObject().put("id", a.opt("id")).put("name", orElse(str(a, "name"), a.opt("id"))).put("running", a.opt("running") == true)
                    .put("last_at", time(a, "last_at")) }))
            .put("log", JSONObject()
                .put("entries", JSONArray(logs?.optJSONArray("entries").objects().map { e ->
                    JSONObject().put("id", e.opt("id")).put("ts", num(e, "ts")).put("level", orElse(str(e, "level"), "info"))
                        .put("agent", str(e, "agent")).put("endpoint", str(e, "endpoint")).put("http", num(e, "http"))
                        .put("message", str(e, "message")) }))
                .put("has_older", logs?.opt("has_older") == true))
    }

    /** "Galaxy Tab A9 · Android 15 · Bridgit 1.8.1786" from a model; empty parts are left out. */
    fun details(model: JSONObject): String {
        val d = model.getJSONObject("device")
        val os = d.getJSONObject("os")
        return listOfNotNull(
            d.optString("model").takeIf { !d.isNull("model") },
            os.optString("version").takeIf { os.optString("name") == "android" && !os.isNull("version") }?.let { "Android $it" },
            d.optString("app_version").takeIf { !d.isNull("app_version") }?.let { "Bridgit $it" },
        ).joinToString(" · ")
    }

    /** Battery percent and whether it is charging, or null when the model has none. */
    fun battery(model: JSONObject): Pair<Int, Boolean>? =
        model.optJSONObject("battery")?.let { it.getDouble("percent").toInt() to it.getBoolean("charging") }
}
