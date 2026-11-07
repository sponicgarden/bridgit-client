package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One runtime-generated data contract for old and new templates; no per-applet export code. */
class AppletDataDiscovery(private val app: BridgeApp) {
    companion object {
        const val CONTRACT = "bridgit-applet-data/2"
        const val MAX_EXPORT_BYTES = 20 * 1024 * 1024
        // An owner's paired control key is authority; applet execution principals are not control agents.
        fun controls(agent: Agent) = agent.connection != Connection.APPLET && agent.allows("applet_install")
        fun visible(agent: Agent, record: JSONObject) = controls(agent) || record.optString("requested_by") == agent.id
        fun canRead(agent: Agent, record: JSONObject, table: AppletTables.Table) =
            controls(agent) || (record.optString("requested_by") == agent.id && table.shared)
        fun base(record: JSONObject) = "/v1/applets/installed/${record.getString("id")}"
        fun rowPage(store: AppletTableStore, principal: String, table: String, limit: Int, since: String?): JSONObject {
            val rows = store.rows(principal, table, limit + 1, since?.takeIf { it.isNotEmpty() }, newestFirst = false)
            var bytes = 0
            val page = rows.take(limit).takeWhile { row ->
                val size = row.toString().toByteArray(Charsets.UTF_8).size + 2
                (bytes == 0 || bytes + size <= AppletData.PAGE_BYTES).also { if (it) bytes += size }
            }
            return JSONObject().put("items", JSONArray(page)).put("count", store.count(principal, table))
                .put("next", if (rows.size > page.size) page.last().getString("id") else JSONObject.NULL)
        }
        fun tables(record: JSONObject) = AppletTables.parse(record.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject())
    }

    private val store get() = AppletTableStore.of(app)

    fun record(agent: Agent, id: String): JSONObject = app.applets.get(id)?.takeIf { visible(agent, it) }
        ?: throw ErrorCatalog.error("not_found", "No installed applet $id accessible to this agent. Start with GET /v1/applets/installed.")

    fun table(agent: Agent, id: String, tableId: String): Pair<JSONObject, AppletTables.Table> {
        val r = record(agent, id)
        val t = tables(r).firstOrNull { it.id == tableId }
            ?: throw ErrorCatalog.error("not_found", "No table $tableId. Read ${base(r)}/data for table ids and schemas.")
        if (!canRead(agent, r, t)) throw ErrorCatalog.error("not_shared", "This applet principal cannot read $tableId. Owner control agents with applet_install can access it without another approval.")
        return r to t
    }

    fun records(agent: Agent, r: JSONObject, after: String? = null): JSONObject {
        if (!controls(agent)) throw ErrorCatalog.error("not_shared", "Saved free-form records require an owner control key with applet_install.")
        if (r.optString("agent_id").isBlank()) throw ErrorCatalog.error("invalid_parameter", "Native applets use native_sources from /data, not the package record store.")
        val principal = r.getString("agent_id")
        val cursor = after?.takeIf { it.isNotEmpty() }?.let(AppletData::checkKey)
        val rows = app.platform.store.keyed(principal, AppletData.KIND, AppletData.id(principal, ""), cursor?.let { AppletData.id(principal, it) } ?: "", 201)
        val page = AppletData.page(rows.take(200))
        return JSONObject().put("records", JSONArray(page.map(AppletData::view)))
            .put("count", app.platform.store.usage(principal, AppletData.KIND).first)
            .put("next", if (rows.size > page.size) page.last().getString("key") else JSONObject.NULL)
    }

    fun catalog(agent: Agent, r: JSONObject, includeRows: Boolean = false): JSONObject {
        if (r.optString("agent_id").isBlank()) return JSONObject()
            .put("contract", CONTRACT).put("format", CONTRACT).put("instance_id", r.getString("id")).put("name", r.optString("name"))
            .put("access", if (controls(agent)) "owner_control" else "shared_tables")
            .put("complete", false).put("media_complete", false).put("tables", JSONArray()).put("photos", JSONArray())
            .put("export_url", JSONObject.NULL).put("archive_url", JSONObject.NULL)
            .put("native_sources", if (r.optString("native") == "dog") JSONObject()
                .put("status_url", "/v1/applets/dog-monitor").put("history_url", "/v1/applets/dog-monitor/history").put("photo_url", "/v1/applets/dog-monitor/frame") else JSONObject())
            .put("next", "This native applet has no package table store. Read native_sources with your existing key and the advertised capability grants; history/frame reads do not capture new media. Its data is not included in the generic export.")
        val principal = r.getString("agent_id")
        val all = tables(r)
        val allowed = all.filter { canRead(agent, r, it) }
        val photos = linkedSetOf<String>()
        val out = JSONObject().put("contract", CONTRACT).put("format", CONTRACT).put("instance_id", r.getString("id"))
            .put("name", r.optString("name")).put("package_id", r.optString("package_id"))
            .put("version", r.optString("version")).put("revision", r.optLong("revision"))
            .put("generated_at", System.currentTimeMillis()).put("consistency", "live; not an atomic snapshot")
            .put("access", if (controls(agent)) "owner_control" else "shared_tables")
            .put("complete", allowed.size == all.size)
            .put("excluded_tables", JSONArray(all.filter { it !in allowed }.map { it.id }))
            .put("export_url", "${base(r)}/data/export")
            .put("archive_url", "${base(r)}/data/export?format=zip")
            .put("tables", JSONArray(allowed.map { t ->
                val rows = AppletExport.readRows(store, principal, t.id)
                val choices = JSONObject()
                val columns = JSONObject()
                t.columns.values.forEach { c ->
                    columns.put(c.id, JSONObject().put("type", c.type).put("label", c.label).apply {
                        if (c.options.isNotEmpty()) put("options", JSONArray(c.options))
                        c.description?.let { put("description", it) }
                    })
                    if (c.type == "choice") choices.put(c.id, JSONObject().put("values", JSONObject().apply {
                        c.options.forEach { option -> put(option, rows.count { it.optString(c.id) == option }) }
                    }).put("null_count", rows.count { it.isNull(c.id) }))
                    if (c.type == "photo") rows.forEach { row -> row.optString(c.id).takeIf { AppletTables.PHOTO_REF.matches(it) }?.let(photos::add) }
                }
                JSONObject().put("id", t.id).put("label", t.label).put("columns", columns)
                    .put("count", rows.size).put("choice_counts", choices)
                    .put("keep_days", t.keepDays).put("max_rows", t.maxRows)
                    .put("rows_url", "${base(r)}/tables/${t.id}/rows").put("cursor_parameter", "since")
                    .apply { if (includeRows) put("rows", JSONArray(rows)) }
            }))
        // Control agents can also retrieve saved photos not currently linked by a row (no new capture).
        val kept = store.photos(principal, limit = AppletTables.MAX_KEPT_PHOTOS)
        if (controls(agent)) kept.forEach { photos += it.getString("ref") }
        val meta = kept.associateBy { it.getString("ref") }
        val recordCount = app.platform.store.usage(principal, AppletData.KIND).first
        out.put("records", JSONObject().put("count", if (controls(agent)) recordCount else JSONObject.NULL)
            .put("accessible", controls(agent)).put("schema", "{key:string,value:object,updated_at:milliseconds,chars:integer}").put("legacy_read_only", true)
            .put("rows_url", "${base(r)}/records").put("cursor_parameter", "after").apply {
                if (includeRows && controls(agent)) {
                    val values = JSONArray(); var after: String? = null; var recordBytes = 0L
                    do {
                        val page = records(agent, r, after)
                        val entries = page.getJSONArray("records")
                        for (i in 0 until entries.length()) {
                            val entry = entries.getJSONObject(i)
                            recordBytes += entry.toString().toByteArray(Charsets.UTF_8).size
                            if (recordBytes > MAX_EXPORT_BYTES) throw ErrorCatalog.error("export_too_large", "Saved records exceed 20 MB. Use records.rows_url and pass next as after.")
                            values.put(entry)
                        }
                        after = page.optString("next").takeIf { !page.isNull("next") }
                    } while (after != null)
                    put("rows", values)
                }
            })
        if (!controls(agent) && recordCount > 0) out.put("complete", false)
        out.put("row_metadata", JSONObject().put("id", "stable row id; pagination cursor").put("at", "creation time, milliseconds").put("by", "write provenance"))
        out.put("photos", JSONArray(photos.map { ref ->
            val file = store.photoFile(principal, ref)
            JSONObject().put("ref", ref).put("available", file != null)
                .put("size_bytes", file?.length() ?: JSONObject.NULL)
                .put("taken_at", meta[ref]?.optLong("taken_at") ?: JSONObject.NULL)
                .put("chunk_bytes", 8 * 1024 * 1024).put("chunk_parameters", "offset,length; use when a photo exceeds 20 MB")
                .put("url", "${base(r)}/photos/${ref.removePrefix("kept:")}")
                .put("archive_path", "photos/${ref.removePrefix("kept:")}.jpg")
        }))
        out.put("media_complete", photos.all { store.photoFile(principal, it) != null })
        // Batch sources and buttons are declarative; discovering them never executes a flow.
        val batches = linkedSetOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    (value.opt("batch") as? String)?.let(batches::add)
                    if (value.optJSONObject("do")?.has("for_each") == true)
                        value.optString("id").ifEmpty { value.optString("label") }.takeIf { it.isNotEmpty() }?.let(batches::add)
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        if (controls(agent)) walk(r.optJSONObject("definition")?.optJSONObject("ui"))
        out.put("batches", JSONObject().apply { batches.forEach { put(it, app.platform.batches.summary(principal, it)) } })
        out.put("next", "Read table rows_url with limit=200 and follow next as since until null; for free-form records pass next as after. Photos use the same Bearer key. No capture, processing, share sheet or new approval is needed. Choice counts and batches report saved state; do not infer completion from thumbnails or item counts.")
        return out
    }

    fun photo(agent: Agent, r: JSONObject, key: String): java.io.File {
        if (r.optString("agent_id").isBlank()) throw ErrorCatalog.error("not_found", "Use this native applet's photo URL from /data.")
        val ref = "kept:$key"
        if (!AppletTables.PHOTO_REF.matches(ref)) throw ErrorCatalog.error("not_found", "No saved photo $key.")
        if (!controls(agent)) {
            val linked = tables(r).filter { canRead(agent, r, it) }.any { t ->
                AppletExport.readRows(store, r.getString("agent_id"), t.id).any { row ->
                    t.columns.values.any { it.type == "photo" && row.optString(it.id) == ref }
                }
            }
            if (!linked) throw ErrorCatalog.error("not_found", "No accessible saved photo $key.")
        }
        return store.photoFile(r.getString("agent_id"), ref)
            ?: throw ErrorCatalog.error("not_found", "This saved photo is missing or expired; no replacement was captured.")
    }

    fun export(agent: Agent, r: JSONObject, zip: Boolean): ByteArray {
        if (r.optString("agent_id").isBlank()) throw ErrorCatalog.error("invalid_parameter", "Native applets use the native_sources URLs from /data, not the generic package export.")
        val manifest = catalog(agent, r, includeRows = true)
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        fun tooLarge(): Nothing = throw ErrorCatalog.error("export_too_large", "Export exceeds 20 MB. Use /data, paginated rows_url and individual photo URLs instead.")
        if (bytes.size > MAX_EXPORT_BYTES) tooLarge()
        if (!zip) return bytes
        val files = (0 until manifest.getJSONArray("photos").length()).mapNotNull { i ->
            val p = manifest.getJSONArray("photos").getJSONObject(i)
            store.photoFile(r.getString("agent_id"), p.getString("ref"))?.let { p.getString("archive_path") to it }
        }
        if (bytes.size.toLong() + files.sumOf { it.second.length() } > MAX_EXPORT_BYTES) tooLarge()
        val output = ByteArrayOutputStream()
        var sourceBytes = bytes.size.toLong()
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("manifest.json")); archive.write(bytes); archive.closeEntry()
            files.forEach { (name, file) ->
                archive.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var n = input.read(buffer)
                    while (n >= 0) {
                        sourceBytes += n
                        if (sourceBytes > MAX_EXPORT_BYTES || output.size() > MAX_EXPORT_BYTES) tooLarge()
                        archive.write(buffer, 0, n)
                        n = input.read(buffer)
                    }
                }
                archive.closeEntry()
            }
        }
        if (output.size() > MAX_EXPORT_BYTES) tooLarge()
        return output.toByteArray()
    }
}
