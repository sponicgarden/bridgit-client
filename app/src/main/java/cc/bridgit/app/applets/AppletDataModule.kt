package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * An agent's (in practice an installed applet's) own small records on the phone (5 Nov 2026, for the
 * Insurance inventory applet): JSON values under short keys, kept in the platform store, never uploaded.
 * A card has no localStorage, so this is where an applet keeps what the owner built up. Removing the
 * applet deletes them with its agent (AppletInstances.tearDown deletes every platform record it had).
 */
object AppletData {
    const val KIND = "applet_record"
    val KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
    /** One value; fits a card reply (64 KB) with room to spare. */
    const val MAX_VALUE_CHARS = 40_000
    const val MAX_RECORDS = 1000
    const val MAX_TOTAL_CHARS = 16_000_000L
    /** A list page stops before this many UTF-8 bytes of records, so it stays under a card's 64 KB reply. */
    const val PAGE_BYTES = 40_000

    fun id(agent: String, key: String) = "rec:$agent:$key"
    fun checkKey(key: String?): String = key?.takeIf { KEY.matches(it) }
        ?: throw ErrorCatalog.error("invalid_parameter", "key must be 1-80 letters, digits, '.', '_' or '-', starting with a letter or digit.")

    /** {key, value, updated_at, chars} of a stored record. Pure. */
    fun view(r: JSONObject) = JSONObject().put("key", r.optString("key")).put("value", r.opt("value") ?: JSONObject.NULL)
        .put("updated_at", r.optLong("updated_at")).put("chars", r.optJSONObject("value")?.toString()?.length ?: 0)

    /** Takes records in order until [PAGE_BYTES] of their views (always at least one). Pure, unit-tested. */
    fun page(records: List<JSONObject>, budget: Int = PAGE_BYTES): List<JSONObject> {
        var used = 0
        val out = ArrayList<JSONObject>()
        for (r in records) {
            val n = view(r).toString().toByteArray(Charsets.UTF_8).size + 2
            if (out.isNotEmpty() && used + n > budget) break
            out += r; used += n
        }
        return out
    }

    private val PREFIX = Param("prefix", "string", "Only keys that start with this (e.g. item.)")
    val LIST = EndpointSpec("GET", "/applet/records", "applet_data",
        "Your own records on the phone, in key order with their values, about 48 KB a page (pass next as after for the next page)",
        query = listOf(PREFIX, Param("after", "string", "Start after this key (the last page's next)"), Param("keys_only", "boolean", "true leaves values out")),
        example = "GET /v1/applet/records?prefix=item.", returns = "records [{key, value, updated_at, chars}], next (null at the end), count, total_chars, max_records, max_total_chars",
        errors = listOf("invalid_parameter"))
    val GET = EndpointSpec("GET", "/applet/records/{key}", "applet_data", "One of your records",
        example = "GET /v1/applet/records/item.k3j9", returns = "key, value, updated_at, chars", errors = listOf("not_found"))
    val PUT = EndpointSpec("POST", "/applet/records/{key}", "applet_data",
        "Save a control agent record (replaces it). Applet execution principals must use declared processing.tables, including described json columns; this legacy store refuses applet writes",
        body = listOf(Param("value", "object", "Any JSON object, 40,000 characters at most", required = true)),
        example = "POST /v1/applet/records/item.k3j9 {\"value\":{\"label\":\"stand mixer\"}}", returns = "key, updated_at, chars, count, total_chars",
        errors = listOf("invalid_parameter", "payload_too_large", "quota_exceeded"))
    val DELETE = EndpointSpec("DELETE", "/applet/records/{key}", "applet_data", "Delete one of your records (no error if it's already gone)",
        example = "DELETE /v1/applet/records/item.k3j9", returns = "deleted key, existed", errors = listOf("invalid_parameter"))
    val PORTAL_RECORD = EndpointSpec("POST", "/applet/portal/record", "applet_data",
        "From an installed applet's card: send one result to the applet's private web portal (the kind its package declares in ui.portal.records, e.g. a workout). Words and numbers only, never free text or pictures; it goes only while the owner has the applet's web portal on",
        body = listOf(Param("label", "string", "One lowercase word (a-z, 0-9, _ or -), e.g. moderate"),
            Param("metrics", "object", "Up to 16 numbers under lowercase keys, e.g. {\"score\": 72, \"calories\": 9.4}"),
            Param("items", "array", "Up to 20 {label, count?, seconds?}, e.g. [{\"label\":\"squats\",\"count\":12,\"seconds\":20}]"),
            Param("answers", "object", "Up to 8 {value (a word), confidence 0-100}")),
        example = "POST /v1/applet/portal/record {\"label\":\"moderate\",\"metrics\":{\"score\":72},\"items\":[{\"label\":\"squats\",\"count\":12}]}",
        returns = "portal on (sent with the next push) | not_linked (kept until this device is linked to the portal account) | off (not sent: the owner hasn't turned the portal on), queued, record",
        errors = listOf("invalid_parameter"))
    val TABLES = EndpointSpec("GET", "/applet/tables", "applet_data",
        "From an installed applet's own card: discover only its declared tables, typed columns and descriptions. Control agents inspect applets with /applets/installed/{id}/data",
        example = "GET /v1/applet/tables", query = listOf(Param("after", "string", "Previous schema page next cursor; omit first; columns are paginated near 32 KB")), returns = "tables[{id,label,columns,column_count,count,rows_url,cursor_parameter}], next (pass as after), storage:declared_tables_only", errors = listOf("invalid_parameter"))
    val TABLE_ROWS = EndpointSpec("GET", "/applet/tables/{table}/rows", "applet_data",
        "Read this applet principal's own declared table, oldest first. No instance id or installer sharing needed; other applets are inaccessible",
        query = listOf(Param("limit", "integer", "1-200, default 50; page also bounded to about 40 KB"), Param("since", "string", "Previous next row id; stop at next:null")),
        example = "GET /v1/applet/tables/results/rows?limit=10", returns = "items[], count, next (pass as since)", errors = listOf("invalid_parameter", "not_found"))
    val TABLE_ADD = EndpointSpec("POST", "/applet/tables/{table}/rows", "applet_data",
        "Save a row in this applet principal's own declared table. Use typed columns or a json column with a description; no undeclared keys or blobs",
        body = listOf(Param("row", "object", "Declared column ids to values; json values are objects/arrays, never encoded strings", required = true)),
        example = "POST /v1/applet/tables/results/rows {\"row\":{\"payload\":{\"score\":3}}}", returns = "saved row with id, at, by and columns", errors = listOf("invalid_parameter", "not_found", "quota_exceeded", "rate_limited"))
    val TABLE_EDIT = EndpointSpec("PATCH", "/applet/tables/{table}/rows/{row}", "applet_data",
        "Update declared columns of this applet principal's own row; preserves its id and other columns",
        body = listOf(Param("set", "object", "Declared column ids to replacement values; null clears a column", required = true)),
        example = "PATCH /v1/applet/tables/results/rows/abc {\"set\":{\"payload\":{\"score\":4}}}", returns = "updated row", errors = listOf("invalid_parameter", "not_found", "row_not_found", "rate_limited"))
    val TABLE_DELETE = EndpointSpec("DELETE", "/applet/tables/{table}/rows/{row}", "applet_data",
        "Delete one row in this applet principal's own declared table using the same table-store cleanup as the owner UI",
        example = "DELETE /v1/applet/tables/results/rows/abc", returns = "deleted, existed", errors = listOf("invalid_parameter", "not_found"))
    val ALL = listOf(LIST, GET, PUT, DELETE, PORTAL_RECORD, TABLES, TABLE_ROWS, TABLE_ADD, TABLE_EDIT, TABLE_DELETE)
}

class AppletDataModule(app: BridgeApp) : Capability(app) {
    override val id = "applet_data"
    override val titleRes = R.string.cap_applet_data
    override val descriptionRes = R.string.cap_applet_data_desc
    override val group = "apps"
    override val endpoints = AppletData.ALL

    private val store get() = app.platform.store

    private fun instance(agentId: String) = app.applets.forAgent(agentId)
        ?: throw ErrorCatalog.error("invalid_parameter", "Only an installed applet principal has self tables. Control agents use GET /v1/applets/installed, then data_url.")

    private fun declared(agentId: String, tableId: String): AppletTables.Table =
        AppletDataDiscovery.tables(instance(agentId)).firstOrNull { it.id == tableId }
            ?: throw ErrorCatalog.error("not_found", "No declared table $tableId. GET /v1/applet/tables lists your tables; declare storage in processing.tables first.")

    private fun tableError(e: AppletTables.Refused): Nothing = throw ErrorCatalog.error(
        when (e.code) { "quota_exceeded", "rate_limited", "row_not_found" -> e.code; else -> "invalid_parameter" }, e.message ?: e.code)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(AppletData.TABLES) {
            val principal = agent!!.id
            val inst = instance(principal)
            val after = query("after")?.takeIf { it.isNotEmpty() }
            if (after != null && !Regex("^[a-z][a-z0-9_]{0,31}\\.[a-z][a-z0-9_]{0,31}$").matches(after))
                throw ErrorCatalog.error("invalid_parameter", "after must be the prior schema page's next cursor.")
            val tables = AppletDataDiscovery.tables(inst).sortedBy { it.id }
            val columns = tables.flatMap { t -> t.columns.values.map { c -> "${t.id}.${c.id}" to c } }.sortedBy { it.first }.filter { after == null || it.first > after }
            val page = linkedMapOf<String, JSONObject>(); var bytes = 0
            for ((key, c) in columns) {
                val value = JSONObject().put("type", c.type).put("label", c.label).apply {
                    c.description?.let { put("description", it) }
                    if (c.options.isNotEmpty()) put("options", JSONArray(c.options))
                }
                val size = value.toString().toByteArray(Charsets.UTF_8).size + key.length + 8
                if (page.isNotEmpty() && bytes + size > 32 * 1024) break
                page[key] = value; bytes += size
            }
            Reply.Json(JSONObject().put("storage", "declared_tables_only").put("next", if (page.size < columns.size) page.keys.last() else JSONObject.NULL)
                .put("tables", JSONArray(tables.map { t ->
                    JSONObject().put("id", t.id).put("label", t.label).put("count", AppletTableStore.of(app).count(principal, t.id))
                        .put("column_count", t.columns.size).put("columns", JSONObject().apply {
                            page.filterKeys { it.substringBefore('.') == t.id }.forEach { (key, value) -> put(key.substringAfter('.'), value) }
                        }).put("rows_url", "/v1/applet/tables/${t.id}/rows").put("cursor_parameter", "since")
                })))
        }
        endpoint(AppletData.TABLE_ROWS) {
            val principal = agent!!.id
            val table = declared(principal, path("table").orEmpty())
            Reply.Json(AppletDataDiscovery.rowPage(AppletTableStore.of(app), principal, table.id,
                query("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50, query("since")))
        }
        endpoint(AppletData.TABLE_ADD) {
            val principal = agent!!.id
            val table = declared(principal, path("table").orEmpty())
            val row = body.optJSONObject("row") ?: throw ErrorCatalog.error("invalid_parameter", "row must contain declared column values.")
            Reply.Json(try { AppletTableStore.of(app).add(principal, table, row, JSONObject().put("type", "card")) } catch (e: AppletTables.Refused) { tableError(e) })
        }
        endpoint(AppletData.TABLE_EDIT) {
            val principal = agent!!.id
            val table = declared(principal, path("table").orEmpty())
            val set = body.optJSONObject("set")?.takeIf { it.length() > 0 } ?: throw ErrorCatalog.error("invalid_parameter", "set must change at least one declared column.")
            Reply.Json(try { AppletTableStore.of(app).update(principal, table, path("row").orEmpty(), set, JSONObject().put("type", "card")) } catch (e: AppletTables.Refused) { tableError(e) })
        }

        endpoint(AppletData.TABLE_DELETE) {
            val principal = agent!!.id
            val table = declared(principal, path("table").orEmpty())
            val row = path("row").orEmpty()
            Reply.Json(JSONObject().put("deleted", row).put("existed", AppletTableStore.of(app).delete(principal, table.id, row)))
        }
        endpoint(AppletData.PORTAL_RECORD) {
            val a = agent!!.id
            val inst = app.applets.forAgent(a) ?: throw ErrorCatalog.error("invalid_parameter", "Only an installed applet's card can send portal records.")
            val kind = cc.bridgit.app.remoteview.PortalRecords.kindOf(inst.optJSONObject("definition"))
                ?: throw ErrorCatalog.error("invalid_parameter", "This applet's package doesn't declare ui.portal.records, so it has no records on its portal.")
            val rec = try { cc.bridgit.app.remoteview.PortalRecords.record(body, kind, System.currentTimeMillis()) }
                catch (e: cc.bridgit.app.remoteview.PortalRecords.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Not a valid record.") }
            val target = inst.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records")
            val tableId = target?.optString("table").orEmpty()
            val columnId = target?.optString("column").orEmpty()
            val table = AppletDataDiscovery.tables(inst).firstOrNull { it.id == tableId && it.columns[columnId]?.type == "json" }
                ?: throw ErrorCatalog.error("invalid_parameter", "Portal records must name a declared table and described json column in ui.portal.records.table/column. Update this template; no undeclared record was stored.")
            val saved = try { AppletTableStore.of(app).add(a, table, JSONObject().put(columnId, rec), JSONObject().put("type", "card")) }
                catch (e: AppletTables.Refused) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Invalid portal record.") }
            val st = app.remoteView.packages.noteRecord(a, rec)
            if (st != "off") app.remoteView.syncPackagesSoon()
            data = JSONObject().put("kind", kind).put("portal", st)
            Reply.Json(JSONObject().put("portal", st).put("queued", st != "off").put("record", rec).put("row_id", saved.getString("id")))
        }
        endpoint(AppletData.LIST) {
            val a = agent!!.id
            val prefix = query("prefix").orEmpty().also { if (it.length > 80) throw ErrorCatalog.error("invalid_parameter", "prefix is at most 80 characters.") }
            val after = query("after")?.takeIf { it.isNotEmpty() }?.let { AppletData.checkKey(it) }
            val keysOnly = query("keys_only") == "true" || query("keys_only") == "1"
            val base = AppletData.id(a, "")
            val rows = store.keyed(a, AppletData.KIND, base + prefix, after?.let { AppletData.id(a, it) } ?: "", if (keysOnly) 1000 else 200)
            val page = if (keysOnly) rows else AppletData.page(rows)
            val more = page.size < rows.size || rows.size == (if (keysOnly) 1000 else 200)
            val (count, chars) = store.usage(a, AppletData.KIND)
            Reply.Json(JSONObject().put("records", JSONArray(page.map { AppletData.view(it).apply { if (keysOnly) remove("value") } }))
                .put("next", if (more && page.isNotEmpty()) page.last().optString("key") else JSONObject.NULL)
                .put("count", count).put("total_chars", chars).put("max_records", AppletData.MAX_RECORDS).put("max_total_chars", AppletData.MAX_TOTAL_CHARS))
        }
        endpoint(AppletData.GET) {
            val a = agent!!.id
            val key = AppletData.checkKey(path("key"))
            val r = store.find(AppletData.id(a, key), a, AppletData.KIND) ?: throw ErrorCatalog.error("not_found", "No record $key.")
            Reply.Json(AppletData.view(r))
        }
        endpoint(AppletData.PUT) {
            if (agent!!.connection == cc.bridgit.app.core.Connection.APPLET)
                throw ErrorCatalog.error("invalid_parameter", "Applets may persist data only in processing.tables. Declare typed columns or a json column with a description, then write table rows. Existing legacy records remain readable for migration.")
            val a = agent!!.id
            val key = AppletData.checkKey(path("key"))
            val value = body.opt("value") as? JSONObject ?: throw ErrorCatalog.error("invalid_parameter", "value must be a JSON object.")
            val n = value.toString().length
            if (n > AppletData.MAX_VALUE_CHARS) throw ErrorCatalog.error("payload_too_large", "value is $n characters; at most ${AppletData.MAX_VALUE_CHARS}.")
            val id = AppletData.id(a, key)
            // One lock for check and write, so parallel saves can't pass the limits together.
            val (now, c2, t2) = synchronized(store) {
            val old = store.find(id, a, AppletData.KIND)
            val (count, chars) = store.usage(a, AppletData.KIND)
            if (old == null && count >= AppletData.MAX_RECORDS) throw ErrorCatalog.error("quota_exceeded", "At most ${AppletData.MAX_RECORDS} records; delete some first.")
            val after = chars - (old?.toString()?.length ?: 0) + n + 200
            if (after > AppletData.MAX_TOTAL_CHARS) throw ErrorCatalog.error("quota_exceeded", "Your records would pass ${AppletData.MAX_TOTAL_CHARS / 1_000_000} MB; delete some first.")
            val now = System.currentTimeMillis()
            store.save(a, AppletData.KIND, JSONObject().put("id", id).put("key", key).put("value", value).put("updated_at", now))
            val u = store.usage(a, AppletData.KIND)
            Triple(now, u.first, u.second)
            }
            data = JSONObject().put("chars", n).put("count", c2)
            Reply.Json(JSONObject().put("key", key).put("updated_at", now).put("chars", n).put("count", c2).put("total_chars", t2))
        }
        endpoint(AppletData.DELETE) {
            val a = agent!!.id
            val key = AppletData.checkKey(path("key"))
            val id = AppletData.id(a, key)
            val existed = store.find(id, a, AppletData.KIND) != null
            if (existed) store.delete(id, a)
            Reply.Json(JSONObject().put("deleted", key).put("existed", existed))
        }
    }
}
