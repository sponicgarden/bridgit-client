package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/** Additive storage declaration migration for already installed pre-table portal-record applets. */
object AppletStorageMigration {
    fun chefCamera(definition: JSONObject): JSONObject? {
        if (definition.optString("id") != "chef-helper") return null
        val out = JSONObject(definition.toString())
        val tables = out.optJSONObject("processing")?.optJSONArray("tables") ?: return null
        val activity = (0 until tables.length()).map { tables.getJSONObject(it) }.firstOrNull { it.optString("id") == "activity" } ?: return null
        val columns = activity.optJSONObject("columns") ?: return null
        if (columns.has("camera")) return null
        columns.put("camera", JSONObject().put("type","choice").put("label","Camera").put("options",JSONArray(listOf("rear","front"))))
        return out
    }
    fun portalTable(definition: JSONObject): JSONObject? {
        val oldTarget = definition.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records") ?: return null
        if (oldTarget.has("table") || oldTarget.has("column")) return null
        val oldTables = definition.optJSONObject("processing")?.optJSONArray("tables") ?: JSONArray()
        if (oldTables.length() >= AppletTables.MAX_TABLES) return null // never replace or evict another table
        val out = JSONObject(definition.toString())
        val processing = out.optJSONObject("processing") ?: JSONObject().also { out.put("processing", it) }
        val tables = processing.optJSONArray("tables") ?: JSONArray().also { processing.put("tables", it) }
        val used = (0 until tables.length()).map { tables.getJSONObject(it).optString("id") }.toSet()
        val id = (listOf("portal_records") + (1..3).map { "portal_records_$it" }).first { it !in used }
        tables.put(JSONObject().put("id", id).put("label", oldTarget.optString("title", "Portal records"))
            .put("max_rows", 1000).put("keep_days", 365).put("columns", JSONObject().put("result", JSONObject()
                .put("type", "json").put("label", "Result")
                .put("description", "${oldTarget.optString("kind")} result: kind:string, at:milliseconds, optional label:string, metrics:{name:number}, items:[{label,count,seconds}], answers:{name:{value,confidence}}. No images or free text."))))
        out.getJSONObject("ui").getJSONObject("portal").getJSONObject("records").put("table", id).put("column", "result")
        return out
    }
}
