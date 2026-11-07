package cc.bridgit.app.watch

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Photos the owner takes on purpose (the Camera tab, or the Portal's camera), handed to the watches that check
 * photos, so they count just like the watches' own photos: a kitchen photo taken by hand feeds the kitchen
 * applet's cleanliness tally and its people spotting. A target is one agent (an installed applet, usually) with
 * all of its photo watches.
 */
class OwnerPhotos(private val app: BridgeApp) {
    private val prefs = app.getSharedPreferences("owner_photos", Context.MODE_PRIVATE)
    /** The model calls outlive a caller that stops waiting (a Portal request has a deadline). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [hub] set = a photo applet on this satellite's hub ([remoteId] is its id there); [watches] is then empty. */
    class Target(val id: String, val name: String, val watches: List<Watches.Watch>,
                 val hub: cc.bridgit.app.satellite.HubLinks.HubInfo? = null, val remoteId: String? = null)

    /** Agents with at least one photo watch, by name. */
    fun targets(): List<Target> = app.watches.photoWatches().groupBy { it.agentId }.map { (agentId, ws) ->
        Target(agentId, app.applets.forAgent(agentId)?.optString("name")?.takeIf { it.isNotBlank() } ?: ws.first().agentName,
            ws.sortedBy { it.createdAt })
    }.sortedBy { it.name.lowercase() }

    /**
     * What the Camera tab offers (owner, 5 Nov 2026): this phone's photo applets, plus, on a satellite, its hub's,
     * named "Kitchen cleanliness (on Pixel 2 XL)", so a satellite is connected to them with no setup.
     */
    fun cameraTargets(): List<Target> {
        fun of(h: cc.bridgit.app.satellite.HubLinks.HubInfo) =
            h.targets.map { t -> Target(hubTargetId(h.linkId, t.id), "${t.name} (on ${h.name})", emptyList(), h, t.id) }
        // Home switched to the hub: the Camera tab sends to that phone's applets only.
        cc.bridgit.app.portal.PortalLinks(app).selectedHub()?.let { return of(it) }
        return targets() + app.hubLinks.hubs().flatMap { of(it) }
    }

    /** What the owner picked last (ids from [cameraTargets]); empty = all. Ids that no longer check photos are dropped. */
    fun chosen(): List<String> {
        val ids = cameraTargets().map { it.id }.toSet()
        return (prefs.getString(KEY_CHOSEN, null)?.split(',') ?: emptyList()).filter { it in ids }
    }

    /**
     * The Camera tab's shutter: [original] goes to this phone's picked applets ([submit]) and to each hub's picked
     * applets over the satellite's return link, all at once; the answers come back as one list. A hub that can't be
     * reached gets one line saying so. [targetIds] empty = every target.
     */
    suspend fun submitFromCamera(original: ByteArray, camera: String, targetIds: List<String>, via: String): JSONObject {
        val all = cameraTargets()
        val picked = if (targetIds.isEmpty()) all else all.filter { it.id in targetIds }
        if (picked.isEmpty()) throw NoTargets(if (all.isEmpty()) "No applet or watch checks photos yet." else "None of those applets checks photos.")
        val local = picked.filter { it.hub == null }
        val remote = picked.filter { it.hub != null }.groupBy { it.hub!!.linkId }
        val out = JSONArray()
        val localJob = local.takeIf { it.isNotEmpty() }?.let { l -> scope.async { runCatching { submit(original, camera, l.map { it.id }, via) } } }
        val hubJobs = remote.values.map { ts ->
            val h = ts.first().hub!!
            h to scope.async(Dispatchers.IO) {
                runCatching {
                    val small = ImageUtil.prepare(original, "image/jpeg", FACES, forceJpeg = true)
                    // All of a hub's applets = send no ids, so ones added there since the last refresh check it too.
                    val ids = if (ts.size == h.targets.size) emptyList() else ts.mapNotNull { it.remoteId }
                    app.hubLinks.sendPhoto(h, small.bytes, camera, ids)
                }
            }
        }
        localJob?.await()?.getOrNull()?.optJSONArray("targets")?.let { a -> for (i in 0 until a.length()) out.put(a.get(i)) }
        for ((h, job) in hubJobs) job.await().fold(
            { r -> r.optJSONArray("targets")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { t ->
                out.put(t.put("id", hubTargetId(h.linkId, t.optString("id"))).put("name", "${t.optString("name")} (on ${h.name})")) } } },
            { e -> out.put(JSONObject().put("id", "hub:${h.linkId}").put("name", h.name).put("checks", JSONArray().put(JSONObject()
                .put("status", "error").put("kind", "check").put("summary", "Couldn't reach it. Check that Tailscale is on on both phones.")))) })
        localJob?.await()?.exceptionOrNull()?.let { e -> if (remote.isEmpty()) throw e
            out.put(JSONObject().put("id", "local").put("name", "This phone").put("checks", JSONArray().put(JSONObject()
                .put("status", "error").put("kind", "check").put("summary", "Couldn't check it: ${e.message ?: e.javaClass.simpleName}")))) }
        return JSONObject().put("camera", camera).put("targets", out)
    }

    fun choose(ids: List<String>) { prefs.edit().putString(KEY_CHOSEN, ids.joinToString(",")).apply() }

    fun targetsJson(): JSONObject = JSONObject()
        .put("targets", JSONArray(targets().map { t ->
            JSONObject().put("id", t.id).put("name", t.name)
                .put("watches", JSONArray(t.watches.map { JSONObject().put("watch_id", it.id).put("label", it.spec.label).put("model", it.spec.model).put("kind", kind(it)) }))
        }))
        .put("chosen", JSONArray(chosen().filter { id -> targets().any { it.id == id } }))

    /**
     * Checks [original] (a JPEG from either camera) with every photo watch of [targetIds] (null or empty = all
     * targets). Waits up to [waitMs] for the answers; checks still running then finish on their own and show up
     * in the watches' history. [camera] is "rear" or "front"; [via] says where it came from, for the log.
     */
    suspend fun submit(original: ByteArray, camera: String, targetIds: List<String>?, via: String, waitMs: Long = 40_000): JSONObject {
        val all = targets()
        val picked = if (targetIds.isNullOrEmpty()) all else all.filter { it.id in targetIds }
        if (picked.isEmpty()) throw NoTargets(if (all.isEmpty()) "No applet or watch checks photos yet." else "None of those applets checks photos.")
        val small = ImageUtil.prepare(original, "image/jpeg", WatchSampler.PHOTO, forceJpeg = true)
        val faces = ImageUtil.prepare(original, "image/jpeg", FACES, forceJpeg = true)
        app.log.system("audit", "watch", "Owner photo ($camera camera, $via) sent to ${picked.joinToString { it.name }}")
        val jobs = picked.associateWith { t ->
            t.watches.map { w -> w to scope.async { runCatching { app.watches.ownerPhoto(w, small.bytes, faces.bytes, camera, small.width, small.height) } } }
        }
        withTimeoutOrNull(waitMs) { jobs.values.flatten().map { it.second }.awaitAll() }
        app.remoteView.syncPackagesSoon()
        return JSONObject().put("camera", camera).put("targets", JSONArray(jobs.map { (t, ws) ->
            JSONObject().put("id", t.id).put("name", t.name).put("checks", JSONArray(ws.map { (w, job) ->
                val o = JSONObject().put("watch_id", w.id).put("label", w.spec.label).put("kind", kind(w))
                if (!job.isCompleted) o.put("status", "checking").put("summary", "Still checking; the answer will show in the applet.")
                else job.getCompleted().fold(
                    { c -> o.put("status", status(c)).put("summary", summary(c)).apply { c.optJSONObject("answers")?.let { put("answers", it) } } },
                    { e -> o.put("status", "error").put("summary", "Couldn't check it: ${e.message ?: e.javaClass.simpleName}") })
            }))
        }))
    }

    class NoTargets(message: String) : Exception(message)

    private fun kind(w: Watches.Watch) = if (w.spec.people != null) "people" else if (w.spec.model == "none") "change" else "check"

    companion object {
        private const val KEY_CHOSEN = "chosen"
        fun hubTargetId(linkId: String, remoteId: String) = "hub:$linkId:$remoteId"
        /** People watches look for faces in a larger copy than the 640 px one the models see. */
        val FACES = Sizing.Spec(1280, null, 85)

        fun status(c: JSONObject): String = when {
            c.has("answers") -> "answered"
            c.has("people") -> "people_checked"
            c.has("skipped") -> "skipped"
            else -> "error"
        }

        /** One line for the owner: "state: messy · visible: yes", or why nothing was asked. */
        fun summary(c: JSONObject): String {
            c.optJSONObject("answers")?.let { a ->
                val parts = a.keys().asSequence().mapNotNull { k ->
                    val v = a.opt(k)
                    val shown = when (v) {
                        // Model answers: {type: choice, choice}, {type: noul, noul: 0-1}, or a plain value.
                        is JSONObject -> when {
                            v.has("choice") -> v.opt("choice")
                            v.has("noul") -> v.optDouble("noul") >= 0.5
                            else -> v.opt("answer") ?: v.opt("value")
                        }?.takeIf { it != JSONObject.NULL }
                        JSONObject.NULL, null -> null
                        else -> v
                    }
                    shown?.let { "$k: ${if (it is Boolean) (if (it) "yes" else "no") else it}" }
                }.toList()
                return if (parts.isEmpty()) "Checked." else parts.joinToString(" · ")
            }
            if (c.has("people")) return "Looked for people."
            return c.optString("message").ifBlank { "Couldn't check it${c.optString("error").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}." }
        }
    }
}
