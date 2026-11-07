package cc.bridgit.app.remoteview

import cc.bridgit.app.applets.ChefHelper
import android.content.Context
import cc.bridgit.app.BridgeApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web portals for installed package applets (owner, 4 Nov 2026: "show the kitchen applet at bridgit.cc/<alias>/myapps").
 * Like the Pet monitor's personal backend, and off until the owner approves `applet.configure` for one applet:
 * then, while this device is linked to the portal account, the applet's first tally watch sends its summary
 * ([RemoteViewPackage]) to POST /v1/watch/applet/push about once a minute when it changes, plus the photo of its
 * latest answered check while photo sharing is on (≤ 640 px, ≤ 100 KB, at most one a minute). Turning it off,
 * or removing the applet, deletes this applet's server data (retried until confirmed). A pause from the portal
 * pauses the applet on the phone. State is kept per package id, so it outlives the instance for the deletion.
 */
class PackagePortals(
    private val app: BridgeApp,
    private val call: (String, JSONObject?) -> JSONObject,
    private val linked: () -> String?,
    private val small: (ByteArray) -> ByteArray?,
) {
    private val prefs = app.getSharedPreferences("package_portals", Context.MODE_PRIVATE)
    /** Per package id: records its card sent (PortalRecords) and not delivered yet; kept across restarts. */
    private val cardRecords = app.getSharedPreferences("package_portal_records", Context.MODE_PRIVATE)
    private val lock = Mutex()
    /** Per package id: the last summary digest sent, when, and the last photo check sent (memory only). */
    private val sent = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, Any?>>()
    private val ready: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private companion object { const val PEOPLE_ROUNDS = 12 }

    init {
        // An applet whose portal is its interface pushes soon after each change of its rows or its batch.
        cc.bridgit.app.applets.AppletTableStore.of(app).addListener { tableChanged(it) }
        app.platform.batches.addListener { tableChanged(it) }
    }
    /** Per package id: "left out" finds not sent yet, and the photo of the newest one (only with photo sharing on). */
    private val finds = HashMap<String, MutableList<JSONObject>>()
    private val findPhotos = HashMap<String, Pair<ByteArray, Long>>()
    /** Per package id: the People capture state last sent (memory only). */
    private val captureSent = HashMap<String, String>()

    private fun state(packageId: String): JSONObject? = prefs.getString(packageId, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun save(packageId: String, s: JSONObject?) { prefs.edit().apply { if (s == null) remove(packageId) else putString(packageId, s.toString()) }.commit() }
    private fun all(): Map<String, JSONObject> = prefs.all.keys.mapNotNull { k -> state(k)?.let { k to it } }.toMap()

    /** Any applet portal on, or a deletion still to confirm: the device keeps checking in. */
    val active: Boolean get() = all().values.any { it.optBoolean("enabled") || it.optBoolean("pending_delete") }
    val anyEnabled: Boolean get() = all().values.any { it.optBoolean("enabled") }
    /** Package ids whose portal is on here (not being deleted): Applets › Portals lists them. */
    fun enabledIds(): List<String> = all().filter { (_, s) -> s.optBoolean("enabled") && !s.optBoolean("pending_delete") }.keys.toList()

    fun status(packageId: String): JSONObject {
        val s = state(packageId)
        return JSONObject().put("applet", RemoteViewPackage.appletId(packageId)).put("personal_backend", s?.optBoolean("enabled") == true)
            .put("photo_sharing", s?.optBoolean("photo", true) ?: true).put("backend_deletion_pending", s?.optBoolean("pending_delete") == true)
            .put("portal_ready", packageId in ready)
    }

    /** A different (or no) portal account: every portal enables itself again with the server. */
    fun relinked() { ready.clear(); sent.clear(); captureSent.clear(); app.watches.people.queue.clear(); clearAllTables() }

    /** Faces seen for [packageId]'s applet wait for nothing more: its portal or photo sharing went off. */
    private fun dropFaces(packageId: String) {
        app.applets.byPackage(packageId)?.optString("agent_id")?.takeIf { it.isNotEmpty() }?.let { app.watches.people.queue.drop(it) }
    }

    /**
     * Why the applet of [agentId] can't capture faces now (see WatchPeople.blocker), or null when it can: its portal
     * must be on and linked, and photo sharing on (face crops are photos).
     */
    fun peopleBlocker(agentId: String): String? {
        val inst = app.applets.forAgent(agentId) ?: return cc.bridgit.app.watch.PeopleRules.NOT_APPLET
        val s = state(inst.optString("package_id"))
        return when {
            s == null || !s.optBoolean("enabled") || s.optBoolean("pending_delete") -> cc.bridgit.app.watch.PeopleRules.PORTAL_OFF
            linked() == null -> cc.bridgit.app.watch.PeopleRules.PORTAL_NOT_LINKED
            !s.optBoolean("photo", true) -> cc.bridgit.app.watch.PeopleRules.PHOTO_SHARING_OFF
            else -> null
        }
    }

    /** Sets one key of [packageId]'s state as it is now (under the same lock as [configure]), never undoing a change made meanwhile. */
    @Synchronized private fun putKey(packageId: String, key: String, value: Long) {
        val cur = state(packageId) ?: return
        if (!cur.optBoolean("enabled")) return
        save(packageId, cur.put(key, value))
    }

    /** The owner's approved change for [packageId] (an installed package applet). */
    @Synchronized fun configure(packageId: String, on: Boolean?, photo: Boolean?) {
        val s = state(packageId) ?: JSONObject()
        if (on == true && !s.optBoolean("enabled")) {
            if (s.optBoolean("pending_delete")) throw IllegalStateException("Finish deleting the previous portal for this applet first; try again when the device is online.")
            s.put("enabled", true).put("since", System.currentTimeMillis()).remove("control_generation"); s.remove("control_revision")
            clearTables(packageId)
            ready -= packageId; sent -= packageId
        }
        if (on == false && s.optBoolean("enabled")) { s.put("enabled", false).put("pending_delete", true); ready -= packageId; sent -= packageId; dropFaces(packageId)
            synchronized(cardRecords) { cardRecords.edit().remove(packageId).commit() } }
        if (photo != null) s.put("photo", photo)
        if (photo == false) dropFaces(packageId)
        save(packageId, s)
        if (on == false) app.applets.byPackage(packageId)?.takeIf(ChefHelper::usesKitchen)?.optString("agent_id")?.let { app.speaker.stop(it) }
        if (on != null) app.applets.portalChanged(packageId)
    }

    /** Whether the watch of [agentId] belongs to an applet whose portal shares photos (see Watches.photoWanted). */
    fun photoWanted(agentId: String): Boolean {
        val inst = app.applets.forAgent(agentId) ?: return false
        val s = state(inst.optString("package_id")) ?: return false
        return s.optBoolean("enabled") && s.optBoolean("photo", true)
    }

    /**
     * The applet's own card asked POST /v1/images/find (e.g. "each item left out"): keep the result as a left_out
     * record for its portal, with the photo while photo sharing is on. Nothing for agents that aren't an applet
     * with its portal on.
     */
    fun noteFind(agentId: String, items: List<cc.bridgit.app.vision.FindRules.Item>, photo: (() -> ByteArray?)?) {
        val inst = app.applets.forAgent(agentId) ?: return
        val pkg = inst.optString("package_id")
        val s = state(pkg) ?: return
        if (!s.optBoolean("enabled")) return
        val at = System.currentTimeMillis()
        val record = RemoteViewPackage.leftOut(items.map { Triple(it.label, listOf(it.box.x0, it.box.y0, it.box.x1, it.box.y1), it.score) }, at)
        val bytes = if (s.optBoolean("photo", true)) runCatching { photo?.invoke() }.getOrNull() else null
        synchronized(finds) {
            finds.getOrPut(pkg) { mutableListOf() }.apply { add(record); while (size > 20) removeAt(0) }
            if (bytes != null) { findPhotos[pkg] = bytes to at; record.put("photo_at", at) }
        }
        sent -= pkg
    }

    private fun pendingRecords(pkg: String): JSONArray = cardRecords.getString(pkg, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    /**
     * The applet's own card sent [record] (POST /v1/applet/portal/record): queue it for its portal. Returns "on"
     * (it goes with the next push), "not_linked" (queued until this device is linked) or "off" (dropped: the
     * owner hasn't turned the portal on).
     */
    fun noteRecord(agentId: String, record: JSONObject): String {
        val inst = app.applets.forAgent(agentId) ?: return "off"
        val pkg = inst.optString("package_id")
        val s = state(pkg)
        if (s == null || !s.optBoolean("enabled") || s.optBoolean("pending_delete")) return "off"
        synchronized(cardRecords) { cardRecords.edit().putString(pkg, PortalRecords.queued(pendingRecords(pkg), record).toString()).commit() }
        sent -= pkg
        return if (linked() == null) "not_linked" else "on"
    }

    /** Sends the card records of an applet that has no tally watch, with a minimal summary. */
    private suspend fun pushRecords(pkg: String, s: JSONObject, inst: JSONObject) {
        val kind = PortalRecords.kindOf(inst.optJSONObject("definition")) ?: return
        val pending = synchronized(cardRecords) { pendingRecords(pkg) }
        val now = System.currentTimeMillis()
        val previous = sent[pkg]
        val paused = inst.optString("status") == "paused"
        val digest = "records:$paused"
        // Nothing new: a heartbeat every 10 minutes keeps the portal's pause state and "connected" current.
        if (pending.length() == 0 && previous != null && previous.first == digest && now - previous.second < 10 * 60_000L) return
        val body = JSONObject().put("applet", RemoteViewPackage.appletId(pkg)).put("timezone", java.util.TimeZone.getDefault().id)
            .put("summary", PortalRecords.summary(inst.optString("name"), kind, paused, cc.bridgit.app.BuildConfig.VERSION_NAME))
            .put("control_supported", true).put("control_ack", JSONObject().put("generation", s.optString("control_generation")).put("revision", s.optLong("control_revision")))
        if (pending.length() > 0) body.put("records", pending)
        val r = try { call("/v1/watch/applet/push", body) } catch (e: RemoteView.Failed) {
            if (e.code == "portal_deleted" || e.code == "backend_off") {
                save(pkg, null); ready -= pkg; sent -= pkg; synchronized(cardRecords) { cardRecords.edit().remove(pkg).commit() }
                app.log.system("audit", "applets", "Applet portal for $pkg was deleted on the web")
            }
            throw e
        }
        val ats = (0 until pending.length()).map { pending.getJSONObject(it).optLong("at") }.toSet()
        synchronized(cardRecords) { cardRecords.edit().putString(pkg, PortalRecords.without(pendingRecords(pkg), ats).toString()).commit() }
        sent[pkg] = Triple(digest, now, null)
        r.optJSONObject("control")?.let { applyControl(pkg, s, inst, it) }
    }

    /** One pass: confirm deletions, enable new portals, send due summaries. Blocking (network). */
    suspend fun sync() = lock.withLock {
        val email = linked()
        for ((pkg, s) in all()) runCatching { syncOne(pkg, s, email) }.onFailure {
            app.log.system("diagnostic", "applets", "Applet portal ${pkg}: ${it.message ?: it.javaClass.simpleName}", level = "warn")
        }
    }

    private suspend fun syncOne(pkg: String, s: JSONObject, email: String?) {
        val applet = RemoteViewPackage.appletId(pkg)
        val inst = app.applets.byPackage(pkg)?.takeIf { it.optString("native") != "dog" }
        // Removed on the phone: the portal goes too.
        if (s.optBoolean("enabled") && inst == null) { s.put("enabled", false).put("pending_delete", true); save(pkg, s); ready -= pkg; synchronized(cardRecords) { cardRecords.edit().remove(pkg).commit() } }
        if (s.optBoolean("pending_delete")) {
            if (email == null) return
            call("/v1/watch/applets/backend", JSONObject().put("applet", applet).put("enabled", false))
            s.put("pending_delete", false); save(pkg, s)
            if (!s.optBoolean("enabled")) save(pkg, null)
            // A portal that is part of its applet (removed and installed again meanwhile) starts once the old one is gone.
            app.applets.byPackage(pkg)?.takeIf { it.optString("native") != "dog" }?.let { r ->
                r.optJSONObject("definition")?.takeIf { PortalRecords.always(it) }?.let { app.applets.portalAlwaysOn(pkg, r.optString("name"), it) } }
            app.applets.portalChanged(pkg)
            return
        }
        if (!s.optBoolean("enabled") || email == null || inst == null) return
        if (pkg !in ready) {
            val r = try { call("/v1/watch/applets/backend", JSONObject().put("applet", applet).put("enabled", true).put("since", s.optLong("since")).put("name", inst.optString("name").take(36))) }
            catch (e: RemoteView.Failed) {
                // Deleted on the web: off here too, until the owner turns it on again.
                if (e.code == "portal_deleted") { save(pkg, null); sent -= pkg; dropFaces(pkg); app.log.system("audit", "applets", "Applet portal for $pkg was deleted on the web") }
                throw e
            }
            r.optLong("enabled_at").takeIf { it > s.optLong("since") }?.let { s.put("since", it); save(pkg, s) }
            ready += pkg
        }
        // A push carries at most one burst of faces: keep going while more wait (bounded, the queue holds 48).
        var rounds = 0
        while (push(pkg, s, inst) && ++rounds < PEOPLE_ROUNDS) Unit
    }

    /** Sends the summary when due (or People data waits). Returns true when it sent faces and more of them wait. */
    private suspend fun push(pkg: String, s: JSONObject, inst: JSONObject): Boolean {
        val agentId = inst.optString("agent_id")
        if (PortalTables.tablesOf(inst.optJSONObject("definition")).isNotEmpty()) return pushTables(pkg, s, inst)
        val w = app.watches.list(agentId).firstOrNull { it.spec.tally != null } ?: run { pushRecords(pkg, s, inst); return false }
        val tally = w.spec.tally ?: return false
        val now = System.currentTimeMillis()
        val photoOn = s.optBoolean("photo", true)
        val last = synchronized(w.recent) { w.recent.lastOrNull { it.has("answers") } }
        val paused = inst.optString("status") == "paused" || w.userPaused
        val summary = RemoteViewPackage.summary(inst.optString("name"), tally, w.tallySummary(now), last, running = !paused && w.pausedUntil <= now,
            paused = paused, photoOn = photoOn, intervalS = w.spec.intervalS, rulesFired = w.rulesFired, lastCheckAt = w.lastCheckAt,
            appVersion = cc.bridgit.app.BuildConfig.VERSION_NAME)
        val digest = summary.toString().let { it.replace(Regex("\"last_check_at\":\\d+"), "") }
        val previous = sent[pkg]
        val photoCheck = w.lastPhotoCheck
        val newPhoto = photoOn && photoCheck != null && photoCheck !== previous?.third && w.lastPhoto != null
        val hasFinds = synchronized(finds) { finds[pkg]?.isNotEmpty() == true }
        val people = peopleOut(pkg, s, agentId, w, now)
        if (!newPhoto && !hasFinds && people?.urgent != true && !RemoteViewPackage.due(digest, previous?.first, previous?.second ?: 0L, now)) return false
        val body = JSONObject().put("applet", RemoteViewPackage.appletId(pkg)).put("timezone", java.util.TimeZone.getDefault().id).put("summary", summary)
            .put("control_supported", true).put("control_ack", JSONObject().put("generation", s.optString("control_generation")).put("revision", s.optLong("control_revision")))
        val photoAt = photoCheck?.opt("at")?.let { RemoteViewPackage.latest(tally, photoCheck)?.optLong("at") }
        if (newPhoto && photoAt != null && photoAt >= s.optLong("since")) w.lastPhoto?.let(small)?.let {
            body.put("photo", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).put("photo_at", photoAt)
        }
        // Every answered check since the last push as a state record, and the card's left-out finds.
        val recordsFrom = s.optLong("records_at", s.optLong("since"))
        val checks = synchronized(w.recent) { w.recent.filter { it.has("answers") }.toList() }
        val states = checks.mapNotNull { c -> RemoteViewPackage.stateRecord(tally, c)?.takeIf { it.getLong("at") > recordsFrom } }
        val pendingFinds = synchronized(finds) { finds[pkg]?.toList().orEmpty() }
        val findPhoto = synchronized(finds) { findPhotos[pkg] }
        val faceRecords = listOfNotNull(people?.burst?.record())
        if (states.isNotEmpty() || pendingFinds.isNotEmpty() || faceRecords.isNotEmpty()) body.put("records", org.json.JSONArray(states + pendingFinds + faceRecords))
        people?.let { body.put("people", it.body) }
        // A left-out photo goes before a routine one: it is the one with boxes on the portal.
        if (photoOn && findPhoto != null && !body.has("photo")) small(findPhoto.first)?.let {
            body.put("photo", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).put("photo_at", findPhoto.second)
        }
        // Face crops are images: the outbound log says so (its usual field-name check doesn't know "crop").
        val hint = if (people?.burst != null) cc.bridgit.app.outbound.Outbound.Hint(purpose = "Applet portal (${inst.optString("name")}): summary and ${people.burst.sightings.size} face photo(s) seen on the phone (People)",
            categories = listOf(cc.bridgit.app.outbound.Categories.IMAGE, cc.bridgit.app.outbound.Categories.BATTERY), noBodyPreview = true) else null
        val r = try { if (hint != null) cc.bridgit.app.outbound.Outbound.hinted(hint) { call("/v1/watch/applet/push", body) } else call("/v1/watch/applet/push", body) } catch (e: RemoteView.Failed) {
            if (e.code == "portal_deleted" || e.code == "backend_off") {
                save(pkg, null); ready -= pkg; sent -= pkg; captureSent -= pkg; app.watches.people.queue.drop(agentId)
                app.log.system("audit", "applets", "Applet portal for $pkg was deleted on the web")
            }
            throw e
        }
        sent[pkg] = Triple(digest, now, if (newPhoto) photoCheck else previous?.third)
        states.maxOfOrNull { it.getLong("at") }?.let { s.put("records_at", it); save(pkg, s) }
        synchronized(finds) {
            finds[pkg]?.removeAll(pendingFinds.toSet())
            if (body.optLong("photo_at") == findPhoto?.second) findPhotos.remove(pkg)
        }
        people?.let { p ->
            p.burst?.let { app.watches.people.queue.remove(it.id) }
            p.lastMessAt?.let { s.put("people_messy_at", it); putKey(pkg, "people_messy_at", it) }
            captureSent[pkg] = p.captureState
        }
        r.optJSONObject("control")?.let { applyControl(pkg, s, inst, it) }
        return people?.burst != null && app.watches.people.queue.next(agentId, System.currentTimeMillis()) != null
    }

    /** What a push carries for People: the body's `people` field, its burst (for the records) and whether it must go now. */
    private class PeopleOut(val body: JSONObject, val burst: cc.bridgit.app.watch.PeopleRules.Burst?, val lastMessAt: Long?, val captureState: String, val urgent: Boolean)

    /**
     * The applet's people watch (if it has one): the oldest waiting burst of faces (≤ 4), new mess windows from the
     * tally watch [tw] (each messy or dirty episode once, after the people watch began and the portal was turned
     * on) and the capture state. Urgent when any of them is new.
     */
    private fun peopleOut(pkg: String, s: JSONObject, agentId: String, tw: cc.bridgit.app.watch.Watches.Watch, now: Long): PeopleOut? {
        val pw = app.watches.list(agentId).firstOrNull { it.spec.people != null } ?: return null
        val people = app.watches.people
        val burst = people.queue.next(agentId, now)
        val after = maxOf(s.optLong("people_messy_at"), pw.createdAt, s.optLong("since"))
        val st = tw.tally
        val eps = if (st == null) emptyList() else synchronized(st) {
            cc.bridgit.app.watch.WatchTally.episodesOf(tw.spec.tally!!, st).map { cc.bridgit.app.watch.PeopleRules.Ep(it.label, it.start, it.last, it.before) }
        }
        val mess = cc.bridgit.app.watch.PeopleRules.messEvents(eps, after)
        val capture = people.capture(pw)
        val state = capture.optString("state")
        val body = JSONObject().put("sightings", org.json.JSONArray(burst?.sightings?.take(cc.bridgit.app.watch.PeopleRules.MAX_SIGHTINGS_PER_PUSH)?.map { it.json() } ?: emptyList<JSONObject>()))
            .put("mess_events", org.json.JSONArray(mess.map { it.json() })).put("capture", capture)
        return PeopleOut(body, burst, mess.maxOfOrNull { it.messyAt }, state, urgent = burst != null || mess.isNotEmpty() || captureSent[pkg] != state)
    }

    // ---- Applets whose portal is their interface (PortalTables): rows and photos of their own tables ----------

    /**
     * Per package id: {sent: {table: {rowId: digest}}, photos: [refs on the portal], bad: [refs that can't be read
     * here], reset: [tables reset on the portal], acks: [command ids]}. Cleared when the portal starts again or the
     * account changes; [tableEpoch] makes a push that was in flight then drop its (stale) save.
     */
    private val tableState = app.getSharedPreferences("package_portal_tables", Context.MODE_PRIVATE)
    private val tableEpoch = java.util.concurrent.atomic.AtomicLong()
    private fun tables(pkg: String): JSONObject = tableState.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private fun saveTables(pkg: String, t: JSONObject, epoch: Long) = synchronized(tableState) {
        if (tableEpoch.get() == epoch) tableState.edit().putString(pkg, t.toString()).commit()
    }
    private fun clearTables(pkg: String) = synchronized(tableState) { tableEpoch.incrementAndGet(); tableState.edit().remove(pkg).commit() }
    private fun clearAllTables() = synchronized(tableState) { tableEpoch.incrementAndGet(); tableState.edit().clear().commit() }

    /** Agents with a push already scheduled (a burst of row writes makes one push, a couple of seconds later). */
    private val kicks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A table applet changed (a row, a photo, its batch): push about 2 s after the burst starts. */
    fun tableChanged(agentId: String) {
        if (!kicks.add(agentId)) return
        main.postDelayed({
            kicks.remove(agentId)
            val inst = app.applets.forAgent(agentId) ?: return@postDelayed
            if (PortalTables.tablesOf(inst.optJSONObject("definition")).isEmpty()) return@postDelayed
            val pkg = inst.optString("package_id")
            if (state(pkg)?.optBoolean("enabled") != true) return@postDelayed
            sent -= pkg
            app.remoteView.syncPackagesSoon()
        }, 2_000L)
    }

    /** The applet's running batch as the portal shows it: {label, done, total, current, running}, or null. */
    fun batchOf(agentId: String): JSONObject? {
        val b = app.platform.batches.running(agentId) ?: return null
        val sum = app.platform.batches.summaryOf(b)
        return JSONObject().put("label", sum.optString("label")).put("done", sum.optInt("done")).put("total", sum.optInt("total")).put("running", true)
            .put("current", sum.opt("current") ?: JSONObject.NULL) // 1-based, the element being read now
    }

    /** Photos for the portal: at most 1600 px on the long side and 900 KB. */
    private fun tablePhoto(file: java.io.File): ByteArray? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1.0, 1600.0 / maxOf(bmp.width, bmp.height))
        val sized = if (scale < 1.0) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        var q = 85
        var out: ByteArray
        do { out = java.io.ByteArrayOutputStream().also { sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, it) }.toByteArray(); q -= 10 } while (out.size > 900_000 && q >= 45)
        out.takeIf { it.size <= 900_000 }
    }.getOrNull()

    private fun strings(a: JSONArray?): MutableSet<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) }.toMutableSet() } ?: mutableSetOf()

    /**
     * One push for an applet whose portal shows its tables: photos not on the portal yet (a few per pass; a failure
     * stops the uploads for this pass, never the push), then the rows that changed since the last confirmed push
     * (none while the applet is paused: the portal refuses them), the batch progress and the acks of portal
     * commands. Applies the commands the reply carries. Returns true when more waits (the caller pushes again).
     */
    private suspend fun pushTables(pkg: String, s: JSONObject, inst: JSONObject): Boolean {
        val def = inst.optJSONObject("definition") ?: return false
        val epoch = tableEpoch.get()
        val agentId = inst.optString("agent_id")
        val store = cc.bridgit.app.applets.AppletTableStore.of(app)
        val all = cc.bridgit.app.applets.AppletTables.parse(def.optJSONObject("processing") ?: JSONObject())
        val wanted = PortalTables.tablesOf(def).mapNotNull { id -> all.firstOrNull { it.id == id } }
        val t = tables(pkg)
        val sentAll = t.optJSONObject("sent") ?: JSONObject()
        val photos = strings(t.optJSONArray("photos"))
        val bad = strings(t.optJSONArray("bad"))
        val reset = strings(t.optJSONArray("reset"))
        val acks = t.optJSONArray("acks") ?: JSONArray()
        val paused = inst.optString("status") == "paused"
        val now = System.currentTimeMillis()
        val rowsOf = wanted.associate { it.id to store.rows(agentId, it.id, 1000, null, newestFirst = false) }
        val photoColsOf = wanted.associate { tb -> tb.id to tb.columns.values.filter { it.type == "photo" }.map { it.id } }
        val refsNow = wanted.flatMap { PortalTables.photoRefs(rowsOf.getValue(it.id), photoColsOf.getValue(it.id)) }.toSet()
        photos.retainAll(refsNow); bad.retainAll(refsNow)
        fun photoReady(v: String) = v.isEmpty() || v == "null" || v in photos || v in bad
        // Photos first, so a row never arrives before its picture: at most 6 a pass, none while paused.
        var uploads = 0
        var morePhotos = false
        if (!paused) for (ref in refsNow) {
            if (ref in photos || ref in bad) continue
            if (uploads >= 6) { morePhotos = true; break }
            val file = store.photoFile(agentId, ref)
            val bytes = file?.let { tablePhoto(it) }
            if (bytes == null) { bad += ref; continue } // gone or unreadable here: its row goes without it
            try {
                val r = call("/v1/watch/applet/table-photo", JSONObject().put("applet", RemoteViewPackage.appletId(pkg)).put("ref", ref)
                    .put("photo", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("taken_at", file.lastModified()))
                if (r.optBoolean("linked", true)) { photos += ref; uploads++ } else break
            } catch (e: RemoteView.Failed) {
                app.log.system("diagnostic", "applets", "Portal photo for ${inst.optString("name")}: ${e.code ?: e.message}", level = "warn")
                morePhotos = false; break // storage full, paused, offline…: rows and commands still go
            }
        }
        val out = JSONObject()
        val nextSent = JSONObject()
        var more = false
        var bytes = 0
        for (table in wanted) {
            val rows = rowsOf.getValue(table.id)
            val prev = sentAll.optJSONObject(table.id)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()
            if (paused) { nextSent.put(table.id, JSONObject(prev)); continue }
            val first = table.id !in reset
            val cols = photoColsOf.getValue(table.id)
            // A push stays well under the portal's 512 KB: rows past ~350 KB wait for the next one.
            val d = PortalTables.diff(rows, if (first) emptyMap() else prev) { r ->
                cols.all { c -> photoReady(r.optString(c)) } && (bytes < 350_000).also { ok -> if (ok) bytes += r.toString().length }
            }
            more = more || d.more
            nextSent.put(table.id, JSONObject(d.sent))
            if (first || d.upserts.isNotEmpty() || d.deletes.isNotEmpty()) out.put(table.id, JSONObject().apply {
                if (first) put("reset", true)
                put("upserts", JSONArray(d.upserts)).put("deletes", JSONArray(d.deletes)).put("count", rows.size).put("photo_columns", JSONArray(cols))
            })
        }
        val batch = batchOf(agentId)
        val digest = "tables:$paused:${batch?.toString()}"
        val previous = sent[pkg]
        // Nothing new: a check-in every minute brings commands from the portal.
        if (out.length() == 0 && acks.length() == 0 && previous != null && previous.first == digest && now - previous.second < 60_000L) {
            saveTables(pkg, t.put("photos", JSONArray(photos.toList())).put("bad", JSONArray(bad.toList())), epoch)
            return morePhotos
        }
        val body = JSONObject().put("applet", RemoteViewPackage.appletId(pkg)).put("timezone", java.util.TimeZone.getDefault().id)
            .put("summary", PortalRecords.summary(inst.optString("name"), "rows", paused, cc.bridgit.app.BuildConfig.VERSION_NAME))
            .put("control_supported", true).put("control_ack", JSONObject().put("generation", s.optString("control_generation")).put("revision", s.optLong("control_revision")))
            .put("tables_supported", true).put("batch", batch ?: JSONObject.NULL).put("tables", out).put("commands_done", acks)
        val r = try { call("/v1/watch/applet/push", body) } catch (e: RemoteView.Failed) {
            saveTables(pkg, t.put("photos", JSONArray(photos.toList())).put("bad", JSONArray(bad.toList())), epoch)
            if (e.code == "portal_deleted" || e.code == "backend_off") { save(pkg, null); ready -= pkg; sent -= pkg; clearTables(pkg)
                app.log.system("audit", "applets", "Applet portal for $pkg was deleted on the web") }
            throw e
        }
        if (!r.optBoolean("linked", true)) return false
        // Confirmed, except rows the portal refused (sent again next time) and photos it says it lacks.
        r.optJSONArray("refused")?.let { rf -> for (i in 0 until rf.length()) rf.optJSONObject(i)?.let { x ->
            nextSent.optJSONObject(x.optString("table"))?.remove(x.optString("id")) } }
        val missing = r.optJSONArray("missing_photos")?.let { m -> (0 until m.length()).map { m.optString(it) }.filter { it !in bad } } ?: emptyList()
        photos.removeAll(missing.toSet())
        val doneAcks = JSONArray()
        r.optJSONArray("commands")?.let { cmds -> for (i in 0 until cmds.length()) cmds.optJSONObject(i)?.let { c ->
            val id = c.optLong("id")
            if (id <= 0) return@let
            runCatching { applyCommand(inst, all, c) }.onFailure { app.log.system("diagnostic", "applets", "Portal command for ${inst.optString("name")}: ${it.message}", level = "warn") }
            doneAcks.put(id)
        } }
        saveTables(pkg, JSONObject().put("sent", nextSent).put("photos", JSONArray(photos.toList())).put("bad", JSONArray(bad.toList()))
            .put("reset", JSONArray((reset + if (paused) emptyList() else wanted.map { it.id }).distinct())).put("acks", doneAcks), epoch)
        sent[pkg] = Triple(digest, now, null)
        r.optJSONObject("control")?.let { applyControl(pkg, s, inst, it) }
        return more || morePhotos || doneAcks.length() > 0 || missing.isNotEmpty()
    }

    /** One command the owner queued on the portal from another computer: edit or delete a row, or press a flow button. */
    private suspend fun applyCommand(inst: JSONObject, all: List<cc.bridgit.app.applets.AppletTables.Table>, c: JSONObject) {
        val agentId = inst.optString("agent_id")
        val store = cc.bridgit.app.applets.AppletTableStore.of(app)
        val by = JSONObject().put("type", "owner").put("owner", true).put("via", "portal")
        when (c.optString("type")) {
            "update", "delete" -> {
                val table = all.firstOrNull { it.id == c.optString("table") && it.id in PortalTables.tablesOf(inst.optJSONObject("definition")) } ?: error("No table ${c.optString("table")}.")
                if (c.optString("type") == "delete") store.delete(agentId, table.id, c.optString("row"))
                else {
                    val set = PortalTables.cleanSet(c.optJSONObject("set") ?: JSONObject(), table.columns.keys, table.columns.values.filter { it.type == "photo" }.map { it.id })
                    if (set.length() > 0) store.update(agentId, table, c.optString("row"), set, by)
                    if(ChefHelper.usesKitchen(inst) && table.id=="activity" && (set.opt("watching")==false || set.opt("muted")==true)) app.speaker.stop(agentId)
                }
            }
            "press" -> PortalActions.pressHeadless(app, inst, c.optString("button"))
        }
    }

    /** A pause or resume from the portal, applied once per revision of this consent generation. */
    private suspend fun applyControl(pkg: String, s: JSONObject, inst: JSONObject, c: JSONObject) {
        val generation = c.optString("generation"); val revision = c.optLong("revision", -1)
        if (!RemoteViewDog.newControl(generation, revision, s.optString("control_generation").ifEmpty { null }, s.optLong("control_revision", -1))) return
        val paused = c.optBoolean("paused")
        val state = cc.bridgit.app.applets.AppletLifecycle.state(inst)
        if (state != if (paused) "paused" else "running") {
            val result = app.applets.setPaused(inst.getString("id"), paused)
            check(cc.bridgit.app.applets.AppletLifecycle.state(result) == if (paused) "paused" else "running") {
                "Waiting for applet device acknowledgments."
            }
            app.log.system("audit", "applets", "Applet ${inst.optString("name")} ${if (paused) "paused" else "resumed"} from the web portal")
        }
        s.put("control_generation", generation).put("control_revision", revision); save(pkg, s)
        sent -= pkg // report the new state at once
    }
}
