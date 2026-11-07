package cc.bridgit.app.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.core.CallbackRules
import cc.bridgit.app.core.SafeHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Keeps captures where the agent and owner chose ([CaptureRules]): bytes in RAM for a while, a copy in
 * Downloads/Bridgit, and/or an upload. The index of file and upload copies survives a restart (app-private
 * prefs); memory copies don't. Fetching checks [CaptureRules.canFetch] every time.
 */
class CaptureStore(private val app: BridgeApp) {
    private val prefs = app.getSharedPreferences("captures", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val index = LinkedHashMap<String, CaptureRules.Entry>()
    private val bytes = HashMap<String, ByteArray>()

    init {
        runCatching { JSONArray(prefs.getString("index", "[]")) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) runCatching { CaptureRules.Entry.fromJson(a.getJSONObject(i)) }.getOrNull()?.let { index[it.id] = it }
        }
    }

    // ---- agent choices ----------------------------------------------------------------------------

    fun prefs(agentId: String): CaptureRules.AgentPrefs? =
        prefs.getString("agent:$agentId", null)?.let { runCatching { CaptureRules.AgentPrefs.fromJson(JSONObject(it)) }.getOrNull() }

    fun setPrefs(agentId: String, p: CaptureRules.AgentPrefs?) {
        if (p == null) prefs.edit().remove("agent:$agentId").apply() else prefs.edit().putString("agent:$agentId", p.toJson().toString()).apply()
    }

    /** Null when [url] is fine for an agent's upload (EVT-05 rules: tailnet always, public HTTPS with the owner's switch). */
    fun uploadUrlProblem(url: String): String? = when (val c = CallbackRules.check(url,
        CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks)) {
        is CallbackRules.Check.Ok -> null
        is CallbackRules.Check.Bad -> c.reason
    }

    /** The plan for a capture, or throws [CaptureRules.Refused] for a request the owner doesn't allow. */
    fun plan(agentId: String, kind: String, requested: Set<String>?): CaptureRules.Plan =
        CaptureRules.plan(kind, app.settings.capturePolicy(), prefs(agentId), requested)

    // ---- keeping -----------------------------------------------------------------------------------

    /**
     * Keeps [data] as [plan] says. Returns the capture's summary for the reply ({id, stored, url, ...}),
     * or null when the plan keeps nothing (today's behaviour). Uploads run in the background.
     */
    fun keep(agentId: String, kind: String, data: ByteArray, mime: String, at: Long, plan: CaptureRules.Plan,
             meta: JSONObject = JSONObject(), id: String = newId()): JSONObject? {
        if (!plan.kept) return null
        val agentName = app.agents.get(agentId)?.name ?: agentId
        var fileUri: String? = null
        var filePath: String? = null
        var fileError: String? = null
        if (plan.files) runCatching { saveFile(agentName, kind, data, mime, at, id) }
            .onSuccess { fileUri = it.first.toString(); filePath = it.second }
            .onFailure { fileError = it.message ?: "couldn't save" }
        val uploads = LinkedHashMap<String, String>()
        plan.agentUploadUrl?.let { uploads["agent"] = "pending" }
        plan.ownerUploadUrl?.let { uploads["owner"] = "pending" }
        val entry = CaptureRules.Entry(id, agentId, kind, mime, data.size, at, at + plan.keepMs, plan.memory, fileUri, filePath, uploads, meta)
        synchronized(this) {
            index[id] = entry
            if (plan.memory) bytes[id] = data
            pruneLocked()
            save()
        }
        plan.agentUploadUrl?.let { url -> upload(id, "agent", url, prefs(agentId)?.uploadHeaders.orEmpty(), data, mime, kind, agentId, agentName, at) }
        plan.ownerUploadUrl?.let { url -> upload(id, "owner", url, emptyMap(), data, mime, kind, agentId, agentName, at) }
        app.log.system("audit", "capture_stored", "Kept a $kind capture for $agentName: ${plan.destinations().joinToString(", ")}" +
            (fileError?.let { " (file failed: $it)" } ?: ""))
        return JSONObject().put("capture_id", id).put("stored", JSONArray(plan.destinations()))
            .put("url", "/v1/captures/$id")
            .put("memory_until", if (plan.memory) isoAt(entry.keepUntil) else JSONObject.NULL)
            .put("file", filePath ?: JSONObject.NULL)
            .apply { fileError?.let { put("file_error", it) } }
    }

    private fun saveFile(agentName: String, kind: String, data: ByteArray, mime: String, at: Long, id: String): Pair<Uri, String> {
        val folder = CaptureRules.folder(agentName)
        val name = CaptureRules.fileName(kind, at, id, mime)
        val cr = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, folder.trimEnd('/'))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android refused the file")
        try {
            cr.openOutputStream(uri)?.use { it.write(data) } ?: error("Couldn't open the file")
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; throw e }
        return uri to folder + name
    }

    private fun upload(id: String, who: String, url: String, extra: Map<String, String>, data: ByteArray, mime: String,
                       kind: String, agentId: String, agentName: String, at: Long) = scope.launch {
        val headers = LinkedHashMap(extra)
        headers["Content-Type"] = mime
        headers["User-Agent"] = "Bridgit (Android) captures"
        headers["X-Bridgit-Capture-Id"] = id
        headers["X-Bridgit-Kind"] = kind
        headers["X-Bridgit-Agent"] = agentName.filter { it.code in 0x20..0x7e }
        headers["X-Bridgit-Captured-At"] = isoAt(at)
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        // The owner's own URL may be any public HTTPS or tailnet address; an agent's follows EVT-05.
        val check = CallbackRules.check(url, CallbackRules.tailnetDomain(app.listener.value.magicDns), who == "owner" || app.settings.allowInternetCallbacks)
        var result = "failed: ${(check as? CallbackRules.Check.Bad)?.reason ?: ""}"
        if (check is CallbackRules.Check.Ok) {
            for ((attempt, wait) in listOf(0L, 10_000L, 60_000L).withIndex()) {
                if (wait > 0) delay(wait)
                result = try {
                    // The agent's capture caused it, whichever URL it goes to.
                    val actor = cc.bridgit.app.outbound.Outbound.Actor.agent(agentId, agentName)
                    val status = cc.bridgit.app.outbound.Outbound.on(actor, cc.bridgit.app.outbound.Outbound.Hint(
                        if (who == "owner") "Owner's capture upload URL" else "Agent's capture upload URL", "Upload a $kind capture")) {
                        SafeHttp.post(url, check.policy, headers, data, 120_000, check.extraPort) }
                    if (status in 200..299) "sent" else "failed: HTTP $status"
                } catch (e: SafeHttp.Failure) { "failed: ${e.message}" }
                if (result == "sent" || attempt == 2) break
            }
        }
        synchronized(this@CaptureStore) {
            index[id]?.let { index[id] = it.copy(upload = it.upload + (who to result)) }
            save()
        }
        app.log.system("audit", "capture_upload", "Upload of a $kind capture to $host ($who): $result")
    }

    // ---- reading -----------------------------------------------------------------------------------

    class Found(val entry: CaptureRules.Entry, val data: ByteArray)

    /** The capture [id] with its bytes, if [callerId] may fetch it (see [CaptureRules.canFetch]) and it's still kept. */
    fun get(id: String, callerId: String, kind: String? = null): Found? {
        val e = synchronized(this) { pruneLocked(); index[id] } ?: return null
        if (kind != null && e.kind != kind) return null
        if (!mayFetch(e, callerId)) return null
        val data = synchronized(this) { bytes[id] } ?: e.fileUri?.let { u ->
            runCatching { app.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } }.getOrNull()
        } ?: return null
        return Found(e, data)
    }

    private fun mayFetch(e: CaptureRules.Entry, callerId: String): Boolean {
        val caller = app.agents.get(callerId) ?: return false
        return CaptureRules.canFetch(e.agentId, e.kind, callerId, { caller.allows(it) }, app.settings.capturesShareAll)
    }

    /** Captures [callerId] may fetch, newest first. */
    fun list(callerId: String, kind: String? = null, limit: Int = 50): List<CaptureRules.Entry> = synchronized(this) {
        pruneLocked()
        index.values.toList().asReversed().filter { CaptureRules.listed(it) && (kind == null || it.kind == kind) }
    }.filter { mayFetch(it, callerId) }.take(limit)

    /** Drops the memory copy and the phone file of [id] (uploads can't be recalled). False if not the caller's. */
    fun delete(id: String, callerId: String): Boolean {
        val e = synchronized(this) { index[id] } ?: return false
        if (e.agentId != callerId && !(app.settings.capturesShareAll && mayFetch(e, callerId))) return false
        e.fileUri?.let { u -> runCatching { app.contentResolver.delete(Uri.parse(u), null, null) } }
        synchronized(this) { index.remove(id); bytes.remove(id); save() }
        return true
    }

    /** Forgets everything of an agent that was removed (files stay in Downloads for the owner). */
    fun forgetAgent(agentId: String) = synchronized(this) {
        index.values.filter { it.agentId == agentId }.forEach { bytes.remove(it.id) }
        index.entries.removeAll { it.value.agentId == agentId && it.value.fileUri == null }
        prefs.edit().remove("agent:$agentId").apply()
        save()
    }

    fun prune() = synchronized(this) { pruneLocked(); save() }

    private fun pruneLocked() {
        val drop = CaptureRules.memoryToDrop(index.values.toList(), System.currentTimeMillis())
        for (id in drop) { bytes.remove(id); index[id]?.let { index[id] = it.copy(memory = false) } }
        index.entries.removeAll { !CaptureRules.listed(it.value) && it.value.upload.values.none { v -> v == "pending" } }
        while (index.size > CaptureRules.MAX_INDEX) { val first = index.keys.first(); index.remove(first); bytes.remove(first) }
    }

    /** Only file and upload entries are written; memory-only ones die with the process anyway. */
    private fun save() {
        val a = JSONArray()
        index.values.filter { it.fileUri != null || it.upload.isNotEmpty() }.forEach { a.put(it.copy(memory = false).toJson()) }
        prefs.edit().putString("index", a.toString()).apply()
    }

    fun view(e: CaptureRules.Entry): JSONObject = CaptureRules.view(e) { isoAt(it) }

    companion object {
        fun newId(): String { val b = ByteArray(9); SecureRandom().nextBytes(b); return "c" + b.joinToString("") { "%02x".format(it) } }
    }
}
