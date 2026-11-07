package cc.bridgit.app.planned

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Private, quota-bounded transfer store. IDs never contain paths; caller cannot nominate a content URI. */
class Transfers(private val app: BridgeApp) {
    private val dir = File(app.filesDir, "transfers").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("transfers", Context.MODE_PRIVATE)
    @Synchronized fun list(agentId: String? = null, kind: String? = null): List<JSONObject> {
        prune()
        return prefs.all.values.mapNotNull { runCatching { JSONObject(it as String) }.getOrNull() }
            .filter { (agentId == null || it.optString("agent_id") == agentId) && (kind == null || it.optString("kind") == kind) }
            .sortedByDescending { it.optLong("created_at") }
    }
    @Synchronized fun save(agentId: String, name: String, mime: String, kind: String, input: InputStream): JSONObject = input.use {
        Rules.name(name); prune()
        if (kind == "files" && Rules.installable(name, mime)) throw ErrorCatalog.error("invalid_parameter", "App packages cannot be sent through file transfers. Use the verified updater.")
        if (!mime.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) throw ErrorCatalog.error("invalid_parameter", "mime must be a valid MIME type.")
        val items = list()
        val remoteItems = items.filter { it.optString("kind") == "files" }
        val mine = remoteItems.filter { it.optString("agent_id") == agentId }
        fun fits(size: Long) = Rules.transferFits(items.sumOf { it.optLong("size_bytes") }, items.size,
            remoteItems.sumOf { it.optLong("size_bytes") }, remoteItems.size, mine.sumOf { it.optLong("size_bytes") }, mine.size, size, kind == "files")
        if (!fits(0)) throw ErrorCatalog.error("quota_exceeded", "Remove old transfers. Agent uploads have 25 MB / 25 files each and 50 MB / 50 files together; owner shares reserve the remaining storage.")
        val id = UUID.randomUUID().toString()
        val file = File(dir, id)
        try {
            input.use { src -> file.outputStream().use { out ->
                val buffer = ByteArray(8192); var size = 0L
                while (true) { val n = src.read(buffer); if (n < 0) break; size += n
                    if (!fits(size)) throw ErrorCatalog.error("payload_too_large", "Transfers are limited to 25 MB each and 100 MB total.")
                    out.write(buffer, 0, n)
                }
            } }
            val o = JSONObject().put("id", id).put("agent_id", agentId).put("name", name).put("mime", mime.take(100))
                .put("kind", kind).put("size_bytes", file.length()).put("created_at", System.currentTimeMillis())
            if (!prefs.edit().putString(id, o.toString()).commit()) throw java.io.IOException("Could not save transfer")
            return o
        } catch (e: Exception) { file.delete(); throw e }
    }
    private fun listSize() = prefs.all.values.sumOf { runCatching { JSONObject(it as String).optLong("size_bytes") }.getOrDefault(0) }
    @Synchronized fun get(id: String, agentId: String? = null): JSONObject {
        prune()
        val o = prefs.getString(id, null)?.let(::JSONObject) ?: throw ErrorCatalog.error("not_found", "No such transfer.")
        if (agentId != null && o.getString("agent_id") != agentId) throw ErrorCatalog.error("not_found", "No such transfer of yours.")
        return o
    }
    fun file(o: JSONObject): File = File(dir, o.getString("id"))
    @Synchronized fun delete(id: String) { prefs.edit().remove(id).commit(); if (id.matches(Regex("[a-f0-9-]{36}"))) File(dir, id).delete() }
    @Synchronized private fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 86400_000
        prefs.all.forEach { (id, value) ->
            val o = runCatching { JSONObject(value as String) }.getOrNull()
            if (o == null || o.optLong("created_at") < cutoff || app.agents.get(o.optString("agent_id")) == null) delete(id)
        }
    }
}

class TransferModule(app: BridgeApp, private val kind: String) : Capability(app) {
    override val id = kind
    override val titleRes = when (kind) { "files" -> R.string.cap_files; "shares" -> R.string.cap_shares; else -> R.string.cap_downloads }
    override val descriptionRes = when (kind) { "files" -> R.string.cap_files_desc; "shares" -> R.string.cap_shares_desc; else -> R.string.cap_downloads_desc }
    override val group = "files"
    override val switchKey = kind
    override val endpoints = PlannedEndpoints.transfers.filter { it.capability == kind }
    override fun Route.install(api: ApiServer) = with(api) {
        endpoints.forEach { spec -> endpoint(spec, admit = { _, _ -> requireUsable() }) {
            requireUsable()
            val aid = agent!!.id
            when {
                spec.method == "POST" -> {
                    val name = Rules.name(body.optString("name", query("filename") ?: query("name") ?: "file"))
                    val mime = body.optString("mime", header("Content-Type") ?: "application/octet-stream").substringBefore(';')
                    val raw = takeRawFile()
                    val item = withContext(Dispatchers.IO) {
                        try {
                            val input = raw?.inputStream() ?: try { Base64.decode(body.getString("data_base64"), Base64.DEFAULT).inputStream() }
                                catch (e: Exception) { throw ErrorCatalog.error("invalid_parameter", "Provide a raw file body or data_base64.") }
                            app.transfers.save(aid, name, mime, kind, input)
                        } finally { raw?.delete() }
                    }
                    app.interactions.event(aid, "file.received", JSONObject().put("file_id", item.getString("id")).put("name", name))
                    OwnerToolsActivity.notify(app, "$agentName sent a file", "Review it in Files and Android access", 705)
                    Reply.Json(item, 201)
                }
                path("id") == null -> Reply.Json(JSONObject().put("items", JSONArray(app.transfers.list(aid, kind))))
                spec.method == "DELETE" -> { app.transfers.get(path("id")!!, aid).also { if (it.getString("kind") != kind) throw ErrorCatalog.error("not_found", "No transfer here.") }; app.transfers.delete(path("id")!!); Reply.Json(JSONObject().put("deleted", true)) }
                else -> {
                    val item = app.transfers.get(path("id")!!, aid)
                    if (item.getString("kind") != kind) throw ErrorCatalog.error("not_found", "No transfer here.")
                    val file = app.transfers.file(item)
                    Reply.Bytes(withContext(Dispatchers.IO) { file.readBytes() }, item.getString("mime"), mapOf("Content-Disposition" to "attachment"))
                }
            }
        } }
    }
}
