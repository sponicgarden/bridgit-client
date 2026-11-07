package cc.bridgit.app.applets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.granted
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.watch.WatchRules
import cc.bridgit.app.watch.Watches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Installed applets (4 Nov 2026): one [AppletPackage] becomes one instance, approved once by the owner.
 *
 * Each instance gets its own agent (connection [Connection.APPLET], no usable key) holding exactly the
 * permissions the package declared, so the per-agent permissions, phone switches and Agents screen keep
 * applying, and everything it creates (watches, schedule, workflow, card) is owned by that one principal.
 * Removing the instance removes them all and the agent; nothing runs before the owner approves.
 *
 * Watches and the card are renewed while the instance is active (their own lifetimes are bounded), so an
 * installed applet keeps running with no agent calling.
 */
class AppletInstances(private val app: BridgeApp) {
    class Refused(val code: String, message: String, val extra: Map<String, Any?> = emptyMap()) : Exception(message)

    private val records = InstalledAppletStore(app).apply { open() }
    private val pendingPrefs = app.getSharedPreferences("applet_pending", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    private val checking = mutableSetOf<String>()
    /** Launch never waits for this. Throttle failed checks too; no model is invoked by a launch. */
    fun checkTemplateSoon(id: String) {
        val r=get(id) ?: return
        if (!AppletEvolution.tracking(r)) return
        val source=AppletEvolution.state(r).optJSONObject("origin") ?: return
        if (source.optString("url").isEmpty() && source.optString("kind") != "bundled" && source.optString("template").isEmpty()) return
        val times=app.getSharedPreferences("applet_template_checks",Context.MODE_PRIVATE)
        synchronized(checking) {
            if (id in checking || System.currentTimeMillis()-times.getLong(id,0)<6*3600_000L) return
            checking.add(id);times.edit().putLong(id,System.currentTimeMillis()).apply()
        }
        scope.launch(Dispatchers.IO) {
            try { kotlinx.coroutines.withTimeout(70_000) { checkTemplate(id) } }
            catch(e: Exception) { app.log.system("diagnostic","applets","Template check for ${r.optString("name")} deferred: ${e.message}",level="info") }
            finally { synchronized(checking) { checking.remove(id) } }
        }
    }

    suspend fun checkTemplate(id: String): JSONObject {
        val r=get(id) ?: throw Refused("not_found","No installed applet $id.")
        if (!AppletEvolution.tracking(r)) throw Refused("conflict","Template syncing is paused or detached; no fetch attempted.")
        val e=AppletEvolution.state(r)
        val source=e.optJSONObject("upstream_source") ?: e.optJSONObject("origin") ?: throw Refused("conflict","The template origin is unknown.")
        val latest=AppletFetch.latest(app,source,cc.bridgit.app.outbound.Outbound.Actor.OWNER)
        return prepareTemplate(id,latest.first,latest.second,r.optLong("revision"))
    }

    /** Current, actionable upgrade only; stale proposals must never look ready to apply. */
    fun templateUpdate(r: JSONObject): JSONObject? {
        if (!AppletEvolution.tracking(r)) return null
        pending().firstOrNull { it.optString("replaces") == r.optString("id") &&
            it.optLong("expected_revision") == r.optLong("revision") &&
            it.optJSONObject("change") == null &&
            it.optJSONObject("source")?.optString("kind") !in setOf("agent_inline", "file")
        }?.let { return AppletEvolution.copy(it).put("status", "awaiting_owner") }
        return AppletEvolution.state(r).optJSONObject("task")?.takeIf {
            it.optLong("expected_revision") == r.optLong("revision") &&
                it.optString("status") in setOf("needs_agent", "needs_verification")
        }
    }

    private fun authorizeDeveloper(r: JSONObject, actorId: String?) {
        if (actorId != null && !AppletEditAccess(app).canEdit(r, actorId)) throw Refused("not_found", "This agent is no longer the applet's developer.")
    }

    private fun expect(r: JSONObject, revision: Long) {
        if (revision != r.optLong("revision")) throw Refused("conflict", "This applet changed. Read its development snapshot and prepare again.")
    }

    private fun newLibraryOwner(instance: JSONObject, source: JSONObject): Boolean {
        val current = instance.optJSONObject("edition_source") ?: instance.optJSONObject("source")
        return source.has("library_account") && source.optString("library_account") != current?.optString("library_account")
    }

    private fun sameOrigin(origin: JSONObject, source: JSONObject): Boolean = when {
        origin.optString("template").isNotEmpty() -> origin.optString("template") == source.optString("template")
        origin.optString("kind") == "bundled" -> origin.optString("bundled").isNotEmpty() && (source.optString("template") == origin.optString("bundled") ||
            source.optString("kind") == "bundled" && source.optString("bundled") == origin.optString("bundled"))
        else -> origin.optString("url").isNotEmpty() && origin.optString("url") == source.optString("url")
    }

    private fun convertsDog(r: JSONObject, p: AppletPackage.Package): Boolean =
        r.optString("native") == "dog" && r.optString("package_id") == "dog-monitor" && p.id == "dog-monitor" &&
            p.adapter == AppletPackage.PORTABLE_ADAPTER &&
            p.definition.optJSONObject("migration")?.optString("strategy") == "explicit_owner_opt_in"

    /** Seed the review with the owner's explicit native sampling and lens choices. */
    internal fun filledConfig(p: AppletPackage.Package, given: JSONObject?, instance: JSONObject?): JSONObject {
        val previous = instance?.optJSONObject("config")
        if (instance == null || previous == null || !convertsDog(instance, p)) {
            return AppletPackage.fill(p, given, previous)
        }
        val carried = JSONObject(previous.toString())
        // Round up so a saved interval never results in more frequent paid checks.
        if (previous.has("interval_ms")) carried.put("interval_s", (previous.getLong("interval_ms") + 999L) / 1000L)
        val state = app.dog.state.value
        if (p.configSchema.optJSONObject("properties")?.has("microphones") == true)
            carried.put("microphones",JSONArray(state.microphones))
        if (p.configSchema.optJSONObject("properties")?.has("additional_cameras") == true &&
            state.cameras.isNotEmpty()) {
            val primary = state.cameras.first()
            if (primary in WatchRules.CAMERAS) carried.put("camera", primary)
            carried.put("monitor_camera", primary)
            carried.put("additional_cameras", JSONArray(state.cameras.drop(1)))
        }
        if (!carried.has("monitor_camera") && carried.has("camera")) carried.put("monitor_camera", carried.get("camera"))
        if (p.configSchema.optJSONObject("properties")?.has("bowl_camera") == true) {
            val bowl = app.dog.feeding.transferableView()?.takeIf { app.dog.feeding.enabled }
            carried.put("bowl_camera", bowl?.getString("camera") ?: carried.optString("camera","rear"))
        }
        return AppletPackage.fill(p, given, carried)
    }

    /** A source fetch cannot overwrite a personal fork; the candidate must have been prepared first. */
    private fun guardTemplate(r: JSONObject, source: JSONObject, p: AppletPackage.Package) {
        if (r.optString("package_id") != p.id || !convertsDog(r,p) && AppletPackage.adapterFamily(p.adapter) != AppletPackage.adapterFamily(r.getJSONObject("definition").getJSONObject("runtime").optString("adapter")))
            throw Refused("conflict", "An update must keep the same package identity and runtime adapter.")
        if (source.optString("kind") in setOf("agent_inline", "file")) return
        val e = AppletEvolution.state(r)
        if (!AppletEvolution.tracking(r)) throw Refused("conflict", "Template updates are ${e.optString("state")}; personal edits remain available.")
        if (!sameOrigin(e.optJSONObject("origin") ?: JSONObject(), source)) throw Refused("conflict", "This update comes from a different template. Use an explicit personal integration.")
        val base = e.optJSONObject("baseline") ?: throw Refused("conflict", "The exact template baseline is unknown. Use a reviewed personal edit; never guess ancestry.")
        if (!AppletEvolution.same(base, r.getJSONObject("definition")))
            throw Refused("conflict", "This applet is customized. Prepare a template upgrade and verify the personal requirements first (${AppletEvolution.GUIDE}).")
    }

    suspend fun setTemplatePolicy(id: String, state: String, revision: Long, reason: String, actorId: String? = null): JSONObject = lock.withLock {
        val r = get(id) ?: throw Refused("not_found", "No installed applet $id.")
        authorizeDeveloper(r, actorId)
        expect(r, revision)
        if (reason.isBlank() || reason.length > 1000) throw Refused("invalid_parameter", "Give a short reason for this policy change.")
        try { AppletEvolution.setPolicy(r, state, reason) } catch(e: IllegalArgumentException) { throw Refused("conflict", e.message ?: "Invalid policy") }
        put(r)
        pending().filter { it.optString("replaces") == id }.forEach { pendingDrop(it.getString("id")) }
        changed()
        app.log.system("audit", "applets", "Template updates for ${r.optString("name")}: $state")
        r
    }

    /** Called only from the authenticated owner UI; assignment does not grant agent capabilities. */
    suspend fun assignDeveloper(id: String, agentId: String, revision: Long): JSONObject = lock.withLock {
        val r=get(id) ?: throw Refused("not_found","No installed applet $id.")
        expect(r,revision)
        val agent=app.agents.get(agentId)?.takeIf { it.connection != Connection.APPLET && it.allows("applet_install") }
            ?: throw Refused("conflict","Choose an existing control agent with Install applets permission.")
        AppletEditAccess(app).clear(id)
        r.put("requested_by",agent.id)
        pending().filter { it.optString("replaces")==id }.forEach { pendingDrop(it.getString("id")) }
        val e=AppletEvolution.copy(AppletEvolution.state(r));e.optJSONObject("task")?.put("status","cancelled");r.put("evolution",e)
        put(r);changed()
        app.log.system("audit","applets","Owner assigned ${agent.name} as developer of ${r.optString("name")}")
        r
    }

    /** Pure preparation plus a local durable task; no model call, data upload or runtime mutation. */
    suspend fun prepareTemplate(id: String, incoming: AppletPackage.Package, source: JSONObject, revision: Long, actorId: String? = null): JSONObject = lock.withLock {
        val r=get(id) ?: throw Refused("not_found", "No installed applet $id.")
        authorizeDeveloper(r,actorId)
        expect(r,revision)
        if (!AppletEvolution.tracking(r)) throw Refused("conflict", "Template syncing is ${AppletEvolution.state(r).optString("state")}.")
        if (incoming.id != r.optString("package_id")) throw Refused("invalid_parameter", "The template is for a different applet.")
        val e=AppletEvolution.copy(AppletEvolution.state(r))
        val origin=e.optJSONObject("origin") ?: JSONObject()
        val matches = sameOrigin(origin,source)
        if (!matches) throw Refused("conflict", "This is a different template origin; use an explicit personal integration instead.")
        val base=e.optJSONObject("baseline") ?: throw Refused("conflict", "The original baseline is unknown; automatic merging is unavailable.")
        val sameDefinition = AppletEvolution.same(base,incoming.definition)
        if (sameDefinition && !(newLibraryOwner(r,source) && AppletEvolution.same(base,r.getJSONObject("definition"))))
            return JSONObject().put("status","up_to_date")
        if (!sameDefinition && !newer(incoming.version, base.optString("version"))) throw Refused("conflict","This release does not advance the integrated template version.")
        // No personal definition edits: use the ordinary owner review, preserving config and runtime data.
        // This needs no model, merge task or control-agent assignment.
        if (AppletEvolution.same(base, r.getJSONObject("definition"))) {
            pending().firstOrNull { it.optString("replaces") == id && it.optString("sha256") == incoming.sha256 &&
                it.optLong("expected_revision") == revision && it.optJSONObject("change") == null &&
                it.optJSONObject("source")?.let { pendingSource -> AppletEvolution.same(pendingSource,source) } == true
            }?.let {
                return@withLock AppletEvolution.copy(it).put("status", "awaiting_owner")
                    .put("review", review(incoming, it.getJSONObject("config"), id))
            }
            return@withLock propose(incoming, null, source, null, replaces = id, notify = false, expectedRevision = revision)
        }
        val old=e.optJSONObject("task")
        if (old != null && old.optString("incoming_sha256") == incoming.sha256 && old.optLong("expected_revision") == revision && old.optString("status") !in setOf("cancelled","applied")) return@withLock AppletEvolution.copy(old)
        val merged=AppletEvolution.merge(base,r.getJSONObject("definition"),incoming.definition)
        val validation=runCatching {
            val candidate=AppletPackage.check(merged.getJSONObject("candidate"))
            review(candidate,filledConfig(candidate,null,r),id)
        }
        val task=JSONObject().put("id","upgrade_"+UUID.randomUUID().toString()).put("type","applet.template_upgrade")
            .put("status",if(merged.getJSONArray("conflicts").length()>0 || validation.isFailure) "needs_agent" else "needs_verification")
            .put("validation",JSONObject().put("valid",validation.isSuccess).put("error",validation.exceptionOrNull()?.message ?: JSONObject.NULL).put("review",validation.getOrNull() ?: JSONObject.NULL))
            .put("incoming",incoming.definition).put("incoming_sha256",incoming.sha256).put("source",source)
            .put("candidate",merged.getJSONObject("candidate")).put("conflicts",merged.getJSONArray("conflicts"))
            .put("created_at",System.currentTimeMillis()).put("expected_revision",revision+1)
            .put("guide",AppletEvolution.GUIDE).put("billing",JSONObject.NULL)
        e.put("task",task);r.put("evolution",e);put(r);changed()
        r.optString("requested_by").takeIf { it.isNotBlank() && !r.isNull("requested_by") }?.let {
            app.interactions.event(it,"applet.template_upgrade",JSONObject().put("instance_id",id).put("task_id",task.getString("id")).put("status",task.getString("status")).put("guide",AppletEvolution.GUIDE))
        }
        AppletEvolution.copy(task)
    }

    private fun proposeChange(p: AppletPackage.Package, given: JSONObject?, source: JSONObject, by: Agent?, id: String, change: JSONObject?, revision: Long): JSONObject {
        return propose(p,given,source,by,replaces=id,notify=true,change=change,expectedRevision=revision)
    }

    private fun finishEvolution(r: JSONObject, source: JSONObject, change: JSONObject?) {
        val e=AppletEvolution.copy(AppletEvolution.state(r))
        change?.let { c ->
            val journal=e.optJSONArray("journal")
            if (journal != null && journal.length()>0) journal.getJSONObject(journal.length()-1)
                .put("intent",c.optString("intent")).put("acceptance",c.optJSONArray("acceptance") ?: JSONArray())
                .put("evidence",c.optString("evidence"))
            val task=e.optJSONObject("task")
            if (task != null && c.optString("task_id")==task.optString("id")) {
                e.put("baseline",task.getJSONObject("incoming")).put("baseline_known",true).put("upstream_source",task.getJSONObject("source"))
                task.put("status","applied")
            }
        }
        // Direct upstream updates are permitted only for an unchanged baseline (guardTemplate).
        if (source.optString("kind") !in setOf("agent_inline", "file")) e.put("baseline",AppletEvolution.copy(r.getJSONObject("definition"))).put("baseline_known",true).put("upstream_source",source)
        r.put("evolution",e);put(r);changed()
    }

    // Run once at construction, before any resume/reconcile validates persisted definitions.
    init { migrateLegacyPortalStorage(); AppletDeviceStops.restore(app) }

    @Synchronized internal fun migrateLegacyPortalStorage() {
        for (record in records.portalMigrationCandidates()) {
            val definition = record.optJSONObject("definition") ?: continue
            val migrated = AppletStorageMigration.chefCamera(definition) ?: AppletStorageMigration.portalTable(definition) ?: continue
            if (runCatching { AppletPackage.check(migrated) }.isFailure) continue
            record.put("definition", migrated).put("sha256", AppletPackage.digest(migrated))
                .put("revision", record.optLong("revision") + 1)
                .put("storage_migration", JSONObject().put("kind", if (record.optString("package_id")=="chef-helper") "chef_camera" else "declared_portal_records").put("at", System.currentTimeMillis())
                    .put("note", "Added missing storage columns for existing applet controls; no records, photos, grants, template origin or customization history were removed."))
            records.save(record, advanceRevision = false)
        }
    }

    // ---- records ----------------------------------------------------------------------------------------------

    @Synchronized fun all(): List<JSONObject> = records.all()
    @Synchronized fun get(id: String): JSONObject? = records.get(id)
    @Synchronized private fun put(r: JSONObject) = records.save(r)
    @Synchronized private fun drop(id: String) { records.remove(id); AppletEditAccess(app).clear(id) }
    @Synchronized fun byPackage(packageId: String) = records.byPackage(packageId)
    @Synchronized fun forAgent(agentId: String) = records.forAgent(agentId)
    @Synchronized fun byApplet(applet: String) = records.byBudget(applet)

    @Synchronized fun pending(): List<JSONObject> = pendingPrefs.all.values.mapNotNull { runCatching { JSONObject(it as String) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.optLong("created_at") < PENDING_MS }.sortedBy { it.optLong("created_at") }
    @Synchronized fun pendingGet(id: String): JSONObject? = pendingPrefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    @Synchronized fun pendingDrop(id: String) { pendingPrefs.edit().remove(id).commit(); cancelNotice(id); changed() }

    /**
     * Stores a package for the owner to review: nothing is created and nothing is granted. [by] is the agent that
     * asked (null for a link or file the owner opened). Returns the pending record with its review.
     */
    fun propose(p: AppletPackage.Package, given: JSONObject?, source: JSONObject, by: Agent?, replaces: String? = null, notify: Boolean = by != null, change: JSONObject? = null, expectedRevision: Long? = null): JSONObject {
        val previous = replaces?.let { get(it) ?: throw Refused("not_found", "That applet was removed.") }
        if (by != null && source.optString("kind") != "template" || p.definition.optJSONObject("ui")?.has("practices") == true || previous?.optJSONObject("definition")?.optJSONObject("ui")?.has("practices") == true) AppletPractices.requireChange(p.definition, previous?.optJSONObject("definition"))
        previous?.let { authorizeDeveloper(it,by?.id); guardTemplate(it, source, p) }
        val config = filledConfig(p, given, replaces?.let { get(it) })
        val review = review(p, config, replaces)
        val r = JSONObject().put("id", "ap" + UUID.randomUUID().toString().replace("-", "").take(14)).put("definition", p.definition).put("sha256", p.sha256)
            .put("config", config).put("source", source).put("created_at", System.currentTimeMillis())
            .put("requested_by", by?.id ?: JSONObject.NULL).put("requested_by_name", by?.name ?: JSONObject.NULL)
            .put("replaces", replaces ?: JSONObject.NULL)
            .put("expected_revision", expectedRevision ?: previous?.optLong("revision") ?: 0L)
            .apply { change?.let { put("change", AppletEvolution.copy(it)) } }
            // What the update said it deletes when proposed: the owner approves losing that, not more (see approve).
            .put("deletes", review.optJSONObject("update")?.optJSONArray("deletes") ?: JSONArray())
        synchronized(this) {
            // One review per package per asker: a newer request replaces an older one still waiting.
            pending().filter { it.getJSONObject("definition").optString("id") == p.id && it.optString("requested_by") == r.optString("requested_by") }
                .forEach { pendingPrefs.edit().remove(it.getString("id")).commit(); cancelNotice(it.getString("id")) }
            if (pending().size >= MAX_PENDING) throw Refused("too_many_open", "$MAX_PENDING applets are already waiting for the owner. Wait for a decision first.")
            pendingPrefs.edit().putString(r.getString("id"), r.toString()).commit()
        }
        if (notify) notice(r, p)
        app.log.system("audit", "applets", "${by?.name ?: "A link"} asked to ${if (replaces != null) "update" else "install"} applet ${p.name} ${p.version}")
        changed()
        return JSONObject(r.toString()).put("review", review).put("status", "awaiting_owner")
    }

    // ---- what the owner reviews ---------------------------------------------------------------------------------

    /** Module ids of this build: the permissions an agent's caps hold (the rest are platform scopes). */
    private fun moduleIds(): Set<String> = app.registry.modules.map { it.id }.toSet()

    private fun callCap(call: String): String? = runCatching {
        CardActions.match(app.registry.modules.flatMap { it.endpoints }, CardActions.parse(JSONObject().put("method", call.substringBefore(' ')).put("path", call.substringAfter(' ')).toString())).first.capability
    }.getOrNull()

    /** What a card button's call does, in the endpoint's own words ("record an audio clip"), for the review. */
    private fun callWords(call: String): String? = runCatching {
        CardActions.match(app.registry.modules.flatMap { it.endpoints }, CardActions.parse(JSONObject().put("method", call.substringBefore(' ')).put("path", call.substringAfter(' ')).toString())).first.summary
    }.getOrNull()?.substringBefore(';')?.substringBefore(" (")?.trim()?.replaceFirstChar { it.lowercase() }?.takeIf { it.isNotEmpty() }

    fun derived(plan: AppletPackage.Plan): Set<String> = AppletPackage.derived(plan, { d, c -> WatchRules.grantsFor(d, c) }, ::callCap) { f ->
        val v = app.platform.workflows.validate(AppletPackage.resolve(f, null, placeholders(plan)) as JSONObject); AppletPackage.strings(v.getJSONArray("required_scopes")).toSet()
    }.let { if ("context" in it) it + "context.read" else it }

    private fun placeholders(plan: AppletPackage.Plan) = plan.watches.associate { it.first to "w000000000000" }

    /** The plan's flows with their `$watch` refs filled: [ids] maps package watch id → real watch_id (placeholders when checking). */
    private fun flowsFor(plan: AppletPackage.Plan, ids: Map<String, String>): List<Pair<String, JSONObject>> =
        plan.flows.map { (fid, f) -> fid to (AppletPackage.resolve(f, null, ids) as JSONObject) }
    private fun checkFlows(plan: AppletPackage.Plan) = flowsFor(plan, placeholders(plan))

    /** Flow name → {workflow_id, digest} of an instance; an instance from before several flows kept one workflow, as flow main. */
    private fun flowIds(r: JSONObject): JSONObject = r.optJSONObject("flows") ?: JSONObject().apply {
        r.optString("workflow_id").takeIf { it.isNotEmpty() }?.let { put(AppletPackage.MAIN_FLOW, JSONObject().put("workflow_id", it).put("digest", r.optString("workflow_digest"))) }
    }
    private fun workflowIds(r: JSONObject): List<String> = flowIds(r).let { f -> f.keys().asSequence().mapNotNull { f.optJSONObject(it)?.optString("workflow_id")?.takeIf { id -> id.isNotEmpty() } }.toList() }

    /** A permission in plain words for the review screen. */
    fun permissionWords(id: String): String {
        WORDS[id]?.let { return it }
        val m = app.registry.byId(id)
        if (m != null) return m.title + " — " + app.getString(m.descriptionRes)
        return cc.bridgit.app.platform.Capabilities.all.firstOrNull { it.id == id }?.label ?: id
    }

    /** Typed setup actions derived from the template, shared by review UI and API clients. */
    data class Requirement(val message: String, val action: String, val target: String = "", val label: String) {
        fun json() = JSONObject().put("message", message).put("action", action).put("target", target).put("label", label)
    }

    fun requirements(declared: Set<String>, plan: AppletPackage.Plan): List<Requirement> = buildList {
        if (!app.settings.listenerEnabled) add(Requirement("Resume Bridgit to run this applet.", "resume", label = "Resume Bridgit"))
        for (id in declared intersect moduleIds()) {
            val m = app.registry.byId(id) ?: continue
            if (!m.enabled) add(Requirement("Turn on ${m.title} for this applet.", "module", id, "Turn on ${m.title}"))
            // Capture permissions are also checked below for native applets. Gallery uses its own
            // readiness check so Android's selected-photos grant remains sufficient.
            val reason = m.blockedReason()
            if (reason?.startsWith("permission:") == true && id !in setOf("camera", "mic"))
                add(Requirement("Allow ${m.title.lowercase()} access for this applet.", "module_permission", id, "Allow ${m.title.lowercase()}"))
            else if (reason != null && reason in setOf("accessibility_off", "android_version", "no_hardware", "notifications_off", "location_off")) add(setupRequirement(m.title, reason))
        }
        fun permission(value: String, name: String) {
            if (!app.granted(value)) add(Requirement("Allow $name access for this applet.", "permission", value, "Allow $name"))
        }
        if (plan.native != null || "camera" in declared) permission(android.Manifest.permission.CAMERA, "camera")
        if ("sounds" in declared || "mic" in declared) permission(android.Manifest.permission.RECORD_AUDIO, "microphone")
        val scopes = checkFlows(plan).flatMap { (_, wf) -> AppletPackage.strings(app.platform.workflows.validate(wf).getJSONArray("required_scopes")) }.toSet() + (declared - moduleIds())
        for (id in scopes) {
            val cap = cc.bridgit.app.platform.Capabilities.all.firstOrNull { it.id == id } ?: continue
            val reasons = cc.bridgit.app.platform.Capabilities.blockers(app, cap)
            if ("feature_disabled" in reasons) add(Requirement("Turn on ${cap.label} for this applet.", "capability", id, if (id == "decisions") "Enable Jev and Clef workflows" else "Turn on ${cap.label}"))
            if ("permission_required" in reasons) cap.permission?.let { permission(it, cap.label.lowercase()) }
            for (reason in reasons - setOf("feature_disabled", "permission_required")) add(setupRequirement(cap.label, reason))
        }
    }.distinctBy { it.action to it.target }

    private fun setupRequirement(label: String, reason: String): Requirement = when (reason) {
        "private_build_required", "android_version", "no_hardware" -> Requirement("$label isn't supported by this device or app edition.", "unsupported", reason, "See alternatives")
        "notifications_required", "notifications_off" -> Requirement("Allow notifications so $label can run visibly.", "notifications", label = "Open notification settings")
        "accessibility_required", "accessibility_off" -> Requirement("$label needs Android accessibility access.", "accessibility", label = "Open accessibility settings")
        "notification_access_required" -> Requirement("$label needs Android notification access.", "notification_access", label = "Open notification access")
        "location_off" -> Requirement("Turn on device location for $label.", "location_settings", label = "Open location settings")
        "screen_share_required" -> Requirement("$label needs your consent to share the screen.", "screen_share", label = "Share screen")
        "open_app_for_camera", "open_app_for_microphone", "open_app_for_location", "open_app_once" -> Requirement("Start $label from this visible screen.", "start_capture", reason, "Start $label")
        else -> Requirement("$label needs additional device setup.", "device_setup", reason, "Open device setup")
    }

    /** Retained for existing API clients and enforcement; actions never bypass this check. */
    fun blockers(declared: Set<String>, plan: AppletPackage.Plan): List<String> = requirements(declared, plan).map { it.message }

    fun requirements(p: AppletPackage.Package, plan: AppletPackage.Plan, grants: Set<String> = p.grantsFor(derived(plan))): List<Requirement> =
        requirements(grants, plan) + engineBlockers(p).map { Requirement(it, "unsupported", label = "What can I do?") }

    /** Everything the review screen and the agent's dry run show: parts, permissions, how often, cost, blockers. */
    fun review(p: AppletPackage.Package, config: JSONObject, replaces: String? = null): JSONObject {
        val plan = AppletPackage.compile(p, config)
        val derived = derived(plan)
        val grants = p.grantsFor(derived)
        AppletPackage.undeclared(p, derived).takeIf { it.isNotEmpty() }?.let {
            throw AppletPackage.Bad("The applet uses ${it.joinToString()} but must declare ${if (it.size == 1) "it" else "them"} in permissions.required or permissions.optional.")
        }
        // Each watch must pass the phone's own watch rules now, not half-way through an install.
        for ((id, req) in plan.watches) try { WatchRules.parse(forever(req)) } catch (e: WatchRules.Bad) { throw AppletPackage.Bad("Watch $id: ${e.message}") }
        plan.card?.let { c ->
            val placeholders = plan.watches.associate { it.first to "w000000000000" }
            try { cardDraft(p.definition, c, placeholders) } catch (e: Exception) { throw AppletPackage.Bad("Card: ${e.message}") }
        }
        for ((fid, wf) in checkFlows(plan)) try { app.platform.workflows.validate(wf) } catch (e: Exception) {
            throw AppletPackage.Bad("${if (fid == AppletPackage.MAIN_FLOW && plan.flows.size == 1) "Workflow" else "Flow $fid"}: ${e.message}")
        }
        val old = replaces?.let { get(it) }
        val added = old?.let { AppletPackage.added(AppletPackage.strings(it.getJSONArray("declared")).toSet(), grants) }
        val deletes = old?.let { updateLosses(it, plan) } ?: emptyList()
        val shares = old?.let { tableShares(it, plan) } ?: emptyList()
        val migration = if (old != null && convertsDog(old,p)) listOf(AppletPackage.Part("Move Dog Monitor history", listOf(
            "Keep this installation, its spending history and a private snapshot of the old tracker. Monitoring stops during the move. Resume camera and sound only when you are ready.",
            "The new watch rotates through the reviewed camera list and uses Clef. It can move the bowl camera to its saved position. Automatic best-camera selection, roaming pan search and the old tracker’s alternative model choices belong to the previous edition.",
            "Existing recordings and gallery viewers stay in the original gallery. The portable edition records new observations under this installation."
        ))) else emptyList()
        val exceptions=p.definition.optJSONObject("ui")?.optJSONObject("practices")?.optJSONArray("exceptions")
        val differences=if(exceptions!=null && exceptions.length()>0) listOf(AppletPackage.Part("Intentional differences", (0 until exceptions.length()).map { exceptions.getJSONObject(it).optString("reason") + " " + exceptions.getJSONObject(it).optString("alternative") })) else emptyList()
        val items = migration + differences + AppletPackage.parts(plan, ::callWords) +
            listOfNotNull(AppletPackage.portalWords(p.definition)?.let { AppletPackage.Part("Web portal", listOf(it.removePrefix("Web portal: ").replaceFirstChar { c -> c.uppercase() })) }) +
            AppletPackage.engineWords(p, ::engineInfo).map { AppletPackage.Part("On-device engine", listOf(it)) } +
            (if (AppletPackage.paid(plan, grants)) listOf(AppletPackage.Part("Spending limit", listOf(AppletBudget.words(AppletBudget.parse(p.definition.opt("budget")) ?: if (p.adapter == AppletPackage.DOG_ADAPTER) AppletBudget.DOG_LIMITS else AppletBudget.DEFAULT_LIMITS, p.definition.has("budget"))))) else emptyList())
        return JSONObject().put("package_id", p.id).put("name", p.name).put("run_mode", AppletPackage.runMode(p.definition)).put("version", p.version).put("summary", p.summary).put("sha256", p.sha256)
            .put("media", JSONArray(AppletPackage.detachedMedia(p.definition).map { (name, descriptor) ->
                JSONObject().put("name", name).put("size", descriptor.size).put("sha256", descriptor.digest)
            }))
            .put("parts", JSONArray(items.map { it.text }))
            .put("items", JSONArray(items.map { i -> JSONObject().put("title", i.title).put("lines", JSONArray(i.lines))
                .put("subs", JSONArray(i.subs.map { (l, e) -> JSONObject().put("label", l ?: JSONObject.NULL).put("text", e) })) }))
            .put("practice_compliance", AppletPractices.assess(p.definition, old?.optJSONObject("definition")))
            .put("warnings", AppletWarnings.json(AppletWarnings.check(p, plan, derived) { path -> callCap(if (' ' in path) path else "GET $path") ?: callCap("POST $path") }))
            .put("engines", JSONArray(p.engines))
            .put("permissions", JSONArray(grants.sorted().map { JSONObject().put("id", it).put("words", permissionWords(it)) }))
            .put("blockers", JSONArray(requirements(p, plan, grants).map { it.message }))
            .put("requirements", JSONArray(requirements(p, plan, grants).map { it.json() }))
            .put("config", config)
            .put("update", old?.let { JSONObject().put("instance_id", it.getString("id")).put("from_version", it.optString("version")).put("added_permissions", JSONArray(added!!.sorted())).put("deletes", JSONArray(deletes)).put("shares", JSONArray(shares)) } ?: JSONObject.NULL)
    }

    /** A portal that is part of the applet (ui.portal.include "always") is on from install, and from the update that adds it. */
    private fun portalAlwaysOn(p: AppletPackage.Package) {
        if (p.adapter != AppletPackage.DOG_ADAPTER) portalAlwaysOn(p.id, p.name, p.definition)
    }

    /** The same for an installed applet whose earlier portal had to finish deleting first (PackagePortals.syncOne). */
    fun portalAlwaysOn(packageId: String, name: String, definition: JSONObject) {
        if (!cc.bridgit.app.remoteview.PortalRecords.always(definition)) return
        if (app.remoteView.packages.status(packageId).optBoolean("personal_backend")) return
        runCatching {
            app.remoteView.packages.configure(packageId, true, cc.bridgit.app.remoteview.PortalRecords.kindOf(definition) == null)
            app.remoteView.syncPackagesSoon()
            app.log.system("audit", "applets", "Web portal of $name turned on: it is part of the applet")
        }.onFailure { app.log.system("diagnostic", "applets", "Couldn't turn on the web portal of $name: ${it.message}", level = "warn") }
    }

    /** Whether version [a] is newer than [b] (dotted numbers; anything else compares as 0). */
    internal fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    // ---- install, pause, update, remove -------------------------------------------------------------------------

    /**
     * The owner approved [pendingId] (with [config] possibly edited on the review screen). Creates everything or nothing.
     * [seenDeletes] are the "this update deletes" lines the review screen showed (default: those at proposal); an
     * update that would now delete data beyond them isn't applied and stays waiting, with the new lines.
     */
    suspend fun approve(pendingId: String, config: JSONObject?, seenDeletes: List<String>? = null): JSONObject = lock.withLock {
        approveLocked(pendingId, config, seenDeletes)
    }

    /** Exact pending request, checked under the same lock as native approval. */
    suspend fun decideByControl(pendingId: String, approve: Boolean, check: (JSONObject) -> Unit): JSONObject = lock.withLock {
        val pending = pendingGet(pendingId) ?: throw Refused("not_found", "That request is gone.")
        check(pending)
        if (approve) approveLocked(pendingId, null, null) {
            val current = pendingGet(pendingId) ?: throw Refused("not_found", "That request is gone.")
            check(current)
        } else {
            declineLocked(pendingId)
            JSONObject().put("pending_id", pendingId).put("status", "declined")
        }
    }

    private suspend fun approveLocked(pendingId: String, config: JSONObject?, seenDeletes: List<String>?, beforeActivate: (() -> Unit)? = null): JSONObject {
        val pend = pendingGet(pendingId) ?: throw Refused("not_found", "That request is gone. Open the link again.")
        val p = AppletPackage.check(pend.getJSONObject("definition"))
        if (p.definition.optJSONObject("ui")?.has("practices") == true) AppletPractices.requireChange(p.definition, pend.optString("replaces").let { get(it)?.optJSONObject("definition") })
        if (p.sha256 != pend.optString("sha256")) throw Refused("invalid_parameter", "The applet changed since it was reviewed.")
        val replaces = pend.optString("replaces").takeIf { it.isNotEmpty() && !pend.isNull("replaces") }
        if (replaces != null) {
            val cur = get(replaces) ?: throw Refused("not_found", "That applet was removed; this update cannot reinstall it.")
            authorizeDeveloper(cur,pend.optString("requested_by").takeIf { !pend.isNull("requested_by") && it.isNotEmpty() })
            if (cur.optString("conversion_review") == pendingId && cur.optString("sha256") == p.sha256) {
                recoverDogConversion(replaces)
                pendingDrop(pendingId)
                return cur
            }
            expect(cur, pend.optLong("expected_revision", -1))
            guardTemplate(cur, pend.optJSONObject("source") ?: JSONObject(), p)
        }
        val cfg = filledConfig(p, config ?: pend.getJSONObject("config"), replaces?.let { get(it) })
        val approvedLoss = seenDeletes ?: pend.optJSONArray("deletes")?.let(AppletPackage::strings) ?: emptyList()
        val result = if (replaces != null && get(replaces) != null) applyUpdate(get(replaces)!!, p, cfg, approvedLoss, pendingId, pend.optJSONObject("source") ?: JSONObject(), beforeActivate)
            else create(p, cfg, pend.optJSONObject("source") ?: JSONObject(), pend.optString("requested_by").takeIf { !pend.isNull("requested_by") }, beforeActivate)
        if (replaces != null && result.optString("conversion_review") != pendingId) finishEvolution(result, pend.optJSONObject("source") ?: JSONObject(), pend.optJSONObject("change"))
        pendingDrop(pendingId)
        // Engines it named start downloading now (Wi-Fi only), so its first find doesn't wait.
        p.engines.mapNotNull { app.onDeviceVision.models.engine(it) }.forEach { app.onDeviceVision.models.ensure(it) }
        pend.optString("requested_by").takeIf { !pend.isNull("requested_by") && app.agents.get(it) != null }?.let {
            grantBudgets(it)
            app.interactions.event(it, "applet.installed", JSONObject().put("instance_id", result.getString("id")).put("package_id", p.id).put("version", p.version))
        }
        return result
    }

    /** The owner declined. The asking agent hears so; nothing was created. */
    suspend fun decline(pendingId: String) = lock.withLock { declineLocked(pendingId) }

    private fun declineLocked(pendingId: String) {
        val pend = pendingGet(pendingId) ?: return
        pendingDrop(pendingId)
        pend.optString("requested_by").takeIf { !pend.isNull("requested_by") && app.agents.get(it) != null }?.let {
            app.interactions.event(it, "applet.declined", JSONObject().put("pending_id", pendingId).put("package_id", pend.getJSONObject("definition").optString("id")))
        }
    }

    @androidx.annotation.VisibleForTesting
    internal var packageMedia = AppletMedia(app)
    private var mediaRecovered = false

    private suspend fun withMedia(r: JSONObject, p: AppletPackage.Package, source: JSONObject, activate: suspend () -> JSONObject): JSONObject {
        if (!mediaRecovered) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { packageMedia.recover { id -> get(id)?.optJSONObject("definition") } }
            mediaRecovered = true
        }
        val instance = r.getString("id")
        if (AppletPackage.detachedMedia(p.definition).isEmpty() &&
            r.optJSONObject("definition")?.let(AppletPackage::detachedMedia).orEmpty().isEmpty()) return activate()
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { packageMedia.prepare(instance, p.definition, source) }
            return activate()
        } finally {
            packageMedia.prune(instance, get(instance)?.optJSONObject("definition"))
        }
    }

    private suspend fun create(p: AppletPackage.Package, cfg: JSONObject, source: JSONObject, by: String?, beforeActivate: (() -> Unit)? = null): JSONObject {
        val plan = AppletPackage.compile(p, cfg)
        val grants = checkReady(p, plan)
        byPackage(p.id)?.let { throw Refused("conflict", "${p.name} is already installed. Update it instead.") }
        if (p.adapter == AppletPackage.DOG_ADAPTER && all().any { it.optString("native") == "dog" || it.optBoolean("converted_from_dog") })
            throw Refused("conflict", "This device has one legacy Pet monitor tracker. Update its installed edition or install a portable Pet activity package for a separate history.")
        if (all().size >= MAX_INSTANCES) throw Refused("too_many_open", "$MAX_INSTANCES applets are installed. Remove one first.")
        val now = System.currentTimeMillis()
        val r = JSONObject().put("id", "ai" + UUID.randomUUID().toString().replace("-", "").take(14)).put("package_id", p.id).put("name", p.name)
            .put("version", p.version).put("sha256", p.sha256).put("definition", p.definition).put("config", cfg).put("source", source)
            .put("declared", JSONArray(grants.sorted())).put("status", "active").put("installed_at", now).put("updated_at", now)
            .put("requested_by", by ?: JSONObject.NULL).put("watches", JSONObject())
        AppletEvolution.initialize(r)
        return withMedia(r, p, source) {
            beforeActivate?.invoke()
            if (p.adapter == AppletPackage.DOG_ADAPTER) {
                r.put("native", "dog"); put(r)
                check(dogConversions.edit().remove("retired").commit()) { "Could not activate the legacy tracker." }
                startDog(plan.native!!)
                changed()
                app.log.system("audit", "applets", "Owner installed applet ${p.name} ${p.version} (built-in Pet monitor)")
                return@withMedia r
            }
            val agent = newAgent(p, grants)
            r.put("agent_id", agent.id).put("agent_name", agent.name)
            put(r) // first, so a crash half-way leaves a record that Remove can clean up
            portalAlwaysOn(p) // before the card: a portal that is the applet's screen keeps the card off the inbox
            try {
                build(r, agent, plan, null, grants, p.definition)
            } catch (e: Exception) {
                runCatching { tearDown(r) }
                throw if (e is Refused) e else Refused(codeOf(e), "Nothing was installed: ${e.message ?: e.javaClass.simpleName}", (e as? Watches.Refused)?.extra ?: emptyMap())
            }
            put(r); changed()
            app.log.system("audit", "applets", "Owner installed applet ${p.name} ${p.version} as ${agent.name} (${grants.sorted().joinToString()})")
            r
        }
    }

    private fun codeOf(e: Exception) = when (e) { is Watches.Refused -> e.code; is cc.bridgit.app.api.ApiError -> e.code; else -> "conflict" }

    /** An engine as the review screen describes it, from the software manifest, or null when this phone can't run it. */
    private fun engineInfo(name: String): AppletPackage.EngineInfo? = app.onDeviceVision.models.engine(name)?.let { AppletPackage.EngineInfo(it.kind, it.model, it.sizeMb, it.speed) }

    private fun engineBlockers(p: AppletPackage.Package): List<String> = p.engines.mapNotNull { n ->
        app.onDeviceVision.models.whyNot(n)?.let { "The on-phone engine $n isn't available: $it." }
    }

    /** Every engine an installed, running applet asked for (runtime.engines): kept up to date by the daily check. */
    fun engines(): Set<String> = all().filter { it.optString("status") == "active" }
        .flatMap { r -> r.optJSONObject("definition")?.optJSONObject("runtime")?.optJSONArray("engines")?.let(AppletPackage::strings) ?: emptyList() }.toSet()

    private fun checkReady(p: AppletPackage.Package, plan: AppletPackage.Plan): Set<String> {
        val derived = derived(plan)
        val grants = p.grantsFor(derived)
        AppletPackage.undeclared(p, derived).takeIf { it.isNotEmpty() }?.let { throw Refused("invalid_parameter", "The applet uses ${it.joinToString()} without declaring it.") }
        (blockers(grants, plan) + engineBlockers(p)).firstOrNull()?.let { throw Refused("capability_disabled", it) }
        val positions=AppletPackage.lanPhotos(plan).filter { it.has("view") }.groupBy { it.getString("view") }
        if (positions.size > 8 || positions.values.any { steps -> steps.map { it.getString("camera") }.distinct().size != 1 })
            throw Refused("invalid_parameter","Use up to eight named positions, each bound to a single camera.")
        for (step in AppletPackage.lanPhotos(plan)) {
            if (app.lanCameras.all().none { it.id == step.getString("camera") })
                throw Refused("camera_unavailable", "Choose an enrolled local camera for the photo step.")
        }
        for ((id, request) in plan.watches) {
            val cameras = listOf(request.optString("camera")) + request.optJSONArray("additional_cameras")?.let(AppletPackage::strings).orEmpty() + WatchRules.soundSources(request.optJSONObject("sound"))
            cameras.firstOrNull { it.startsWith("lan:") && app.lanCameras.all().none { camera -> camera.id == it } }?.let {
                throw Refused("camera_unavailable", "Watch $id requires enrolled camera $it. Choose an available camera.")
            }
        }
        return grants
    }

    private fun newAgent(p: AppletPackage.Package, grants: Set<String>, beforeSave: (Agent) -> Unit = {}): Agent {
        val base = p.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().ifEmpty { "Applet" }.take(24).trim()
        val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else base.take(24 - " $n".length).trim() + " $n" }
            .first { n -> app.agents.all().none { it.name.equals(n, ignoreCase = true) } }
        val a = app.agents.add(name, Connection.APPLET, beforeSave = beforeSave).first
        val caps = grants intersect moduleIds()
        return app.agents.update(a.id) { it.copy(caps = caps, fixer = false, superPowers = false, notes = "Installed applet ${p.id} ${p.version}") } ?: a
    }

    /** Applet watches live as long as the rules allow, and are renewed while the instance is active. */
    private fun forever(req: JSONObject): JSONObject = JSONObject(req.toString()).put("expires_in_s",
        if (req.has("tally") && !req.isNull("tally")) WatchRules.MAX_EXPIRES_TALLY_S else WatchRules.MAX_EXPIRES_S)

    private fun cardBody(c: AppletPackage.Card, html: String) = JSONObject().put("kind", "html").put("title", c.title.take(InteractionRules.TITLE_MAX))
        .put("html", html).put("no_takeover", true).put("expires_in_seconds", InteractionRules.MAX_EXPIRES_S)

    /**
     * The card an install posts, checked: custom HTML as before, or (phase 2) a pieces card whose sources name
     * the real watch ids ([watchIds]) and whose calls this applet declared.
     */
    private fun cardDraft(definition: JSONObject, c: AppletPackage.Card, watchIds: Map<String, String>): cc.bridgit.app.interact.Draft {
        if (!c.isPieces) return InteractionRules.parse(cardBody(c, c.html(watchIds)))
        val card = c.pieces(watchIds)
        // Every call the card makes must be an endpoint a card can call; the review lists what each one does.
        val base = AppletPackage.cardScope(definition, callCap = { m, path -> callCap("$m $path") ?: throw cc.bridgit.app.interact.CardPieces.Bad("$m $path isn't an endpoint a card can call (GET /v1/guide lists them).") })
        val ids = watchIds.values.toSet()
        val scope = cc.bridgit.app.interact.CardPieces.Scope(watch = { it is String && it in ids }, tables = base.tables, steps = base.steps,
            buttonWorkflow = base.buttonWorkflow, callCap = base.callCap, packaged = true, flows = base.flows, columnTypes = base.columnTypes)
        val body = JSONObject(card.toString()).put("kind", "pieces").put("title", c.title.take(InteractionRules.TITLE_MAX))
            .put("no_takeover", true).put("expires_in_seconds", InteractionRules.MAX_EXPIRES_S)
        return InteractionRules.parse(body, scope)
    }

    /** Deletes the rows an approved update drops: removed tables, and removed or retyped columns. */
    private fun dropLostTables(r: JSONObject, plan: AppletPackage.Plan) {
        val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() } ?: return
        val old = runCatching { AppletTables.parse(r.getJSONObject("definition").optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
        val store = AppletTableStore.of(app)
        for (t in old) {
            val n = plan.tables.firstOrNull { it.id == t.id }
            if (n == null) { store.dropTable(agentId, t.id); continue }
            for ((cid, col) in t.columns) { val nc = n.columns[cid]; if (nc == null || nc.type != col.type) store.dropColumn(agentId, t.id, cid) }
        }
    }

    /** What an update would delete from the applet's tables and watches, in the owner's words (empty: nothing). */
    private fun updateLosses(r: JSONObject, plan: AppletPackage.Plan): List<String> {
        val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() } ?: return emptyList()
        val old = runCatching { AppletTables.parse(r.getJSONObject("definition").optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
        val store = AppletTableStore.of(app)
        val next = plan.watches.toMap()
        val watchLosses = mutableListOf<String>()
        val watches = r.optJSONObject("watches") ?: JSONObject()
        for (id in watches.keys()) {
            val record = watches.getJSONObject(id)
            val watch = app.watches.get(agentId,record.getString("watch_id")) ?: continue
            if (watch.spec.tally == null) continue
            val request = next[id]
            if (request == null || (record.optString("digest") != AppletPackage.digest(request) && !app.watches.retainsTally(watch,forever(request))))
                watchLosses.add("Discard the saved counts and episode history of watch $id.")
        }
        return watchLosses + AppletTables.changes(old, plan.tables, { table, column -> if (column == null) store.count(agentId, table) else store.countWith(agentId, table, column) },
            { table, before -> store.countOlder(agentId, table, before) })
    }

    /** What an update newly lets the installing agent read (tables turning shared, or new shared tables); like new permissions. */
    private fun tableShares(r: JSONObject, plan: AppletPackage.Plan): List<String> {
        val old = runCatching { AppletTables.parse(r.getJSONObject("definition").optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
        val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() }
        val store = AppletTableStore.of(app)
        return AppletTables.shares(old, plan.tables) { table -> agentId?.let { store.count(it, table) } ?: 0 }
    }

    /**
     * Creates (or, with [old], updates) the parts of [r] for [plan] as [agent]. A part whose content didn't change
     * is kept, so an update keeps a watch's tally. Writes the ids into [r].
     */
    private suspend fun build(r: JSONObject, agent: Agent, plan: AppletPackage.Plan, old: AppletPackage.Plan?, declared: Set<String>, definition: JSONObject) {
        val specs = app.registry.modules.flatMap { it.endpoints }
        if ("context.read" in declared) app.platform.grant(agent.id, "context.read", System.currentTimeMillis() + GRANT_MS)
        else app.platform.store.revoke(agent.id, "context.read")
        val ws = r.optJSONObject("watches") ?: JSONObject()
        // Watches: keep unchanged ones, replace changed ones, end removed ones.
        for (id in ws.keys().asSequence().toList()) if (plan.watches.none { it.first == id }) {
            app.watches.delete(agent.id, ws.getJSONObject(id).getString("watch_id")); ws.remove(id)
        }
        for ((id, req) in plan.watches) {
            val digest = AppletPackage.digest(req)
            val cur = ws.optJSONObject(id)
            if (cur != null && cur.optString("digest") == digest && app.watches.get(agent.id, cur.getString("watch_id")) != null) continue
            val body = forever(req)
            val spec = WatchRules.parse(body) { raw, at ->
                val c = try { CardActions.match(specs, CardActions.parse(raw.toString())) } catch (e: CardActions.Refused) { throw WatchRules.Bad("$at: ${e.message}") }
                if (c.first.privileged || c.first.capability == "watches") throw WatchRules.Bad("$at: ${c.first.method} ${c.first.fullPath} can't run from a watch rule.")
                if (!agent.allows(c.first.capability)) throw WatchRules.Bad("$at: the applet didn't declare ${c.first.capability}.")
            }
            if (spec.paid && !cc.bridgit.app.feedback.FeedbackUploader.configured) throw Refused("team_unavailable", "This build can't ask Jev or Clef, so watch $id can't run here.")
            if (cur != null) {
                val oldId = cur.getString("watch_id")
                if (app.watches.reviseTally(agent.id,oldId,body,spec)) {
                    cur.put("digest",digest)
                    continue
                }
                app.watches.delete(agent.id,oldId)
            }
            // Faces: only from this path, and only with people_seen among the permissions the owner approved.
            val w = app.watches.create(agent.id, agent.name, body, spec, peopleApproved = AppletPackage.PEOPLE_SEEN in declared,
                initiallyPaused = r.optString("status") == "paused").first
            ws.put(id, JSONObject().put("watch_id", w.id).put("digest", digest))
        }
        r.put("watches", ws)
        // Schedule: one manifest per agent, approved here because the owner approved the applet.
        val schedDigest = plan.schedule?.let(AppletPackage::digest)
        if (schedDigest != r.optString("schedule_digest").takeIf { it.isNotEmpty() }) {
            if (plan.schedule == null) { app.schedules.remove(agent.id); r.remove("schedule_digest") }
            else {
                app.schedules.submit(agent, plan.schedule, null)
                app.schedules.approve(agent.id)
                if (app.schedules.record(agent.id)?.optJSONObject("active") == null) throw Refused("conflict", "The schedule wasn't accepted.")
                if (r.optString("status") == "paused") app.schedules.setPaused(agent.id, true, byOwner = true)
                r.put("schedule_digest", schedDigest)
            }
        }
        // Flows: grants for exactly their scopes, then propose and activate (the owner's approval is this install).
        // Their $watch refs name the watches just made, so a replaced watch re-proposes its flows too. Rules, schedule
        // actions and buttons name a flow by its package name (package_flow), looked up when they fire, so a changed
        // flow never changes the watches or schedule that start it (docs/applet-vocabulary.md).
        val flows = flowsFor(plan, ws.keys().asSequence().associateWith { ws.getJSONObject(it).getString("watch_id") })
        val have = flowIds(r)
        for (fid in have.keys().asSequence().toList()) if (flows.none { it.first == fid }) {
            have.optJSONObject(fid)?.optString("workflow_id")?.takeIf { it.isNotEmpty() }?.let { removeWorkflow(agent.id, it) }
            have.remove(fid)
        }
        for ((fid, wf) in flows) {
            val digest = AppletPackage.digest(wf)
            val cur = have.optJSONObject(fid)
            val kept = if (cur != null && cur.optString("digest") == digest) app.platform.store.find(cur.optString("workflow_id"), agent.id, "workflow") else null
            if (kept != null) {
                // A workflow from before several flows has no package name yet: give it one, so rules can start it by name.
                if (kept.optString("package_flow") != fid) app.platform.store.save(agent.id, "workflow", kept.put("package_flow", fid))
                continue
            }
            // The new flow is proposed and running before the old one goes, so a failure here leaves the old flow working.
            val scopes = AppletPackage.strings(app.platform.workflows.validate(wf).getJSONArray("required_scopes"))
            for (s in scopes) app.platform.grant(agent.id, s, System.currentTimeMillis() + GRANT_MS)
            val rec = app.platform.workflows.propose(agent.id, wf, packageFlow = fid)
            val id = rec.getString("id")
            app.getSystemService(NotificationManager::class.java).cancel(id.hashCode()) // the applet review was the approval
            try {
                app.platform.workflows.activate(id)
                if (r.optString("status") == "paused") app.platform.workflows.pause(agent.id, id)
            } catch (e: Exception) { runCatching { removeWorkflow(agent.id, id) }; throw e }
            cur?.optString("workflow_id")?.takeIf { it.isNotEmpty() && it != id }?.let { removeWorkflow(agent.id, it) }
            have.put(fid, JSONObject().put("workflow_id", id).put("digest", digest))
        }
        r.put("flows", have); r.remove("workflow_digest")
        // workflow_id stays for readers from before several flows: the main (or only) flow.
        (have.optJSONObject(AppletPackage.MAIN_FLOW) ?: have.keys().asSequence().firstOrNull()?.let { have.optJSONObject(it) })?.optString("workflow_id")
            ?.let { r.put("workflow_id", it) } ?: r.remove("workflow_id")
        syncCard(r, agent.id, agent.name, plan, definition)
    }

    /** Card: replaced when its HTML or pieces (with the watch ids) changed, or posted again when it was closed. */
    private fun syncCard(r: JSONObject, agentId: String, agentName: String, plan: AppletPackage.Plan, definition: JSONObject) {
        val ws = r.optJSONObject("watches") ?: JSONObject()
        val watchIds = ws.keys().asSequence().associateWith { ws.getJSONObject(it).getString("watch_id") }
        // An applet whose web portal is its interface keeps its card off the Cards inbox while that portal is on: the
        // portal presses its buttons. With the portal off (or deleted on the web) the card is its screen again.
        val draft = plan.card?.takeUnless { portalIsScreen(r, definition) }?.let { cardDraft(definition, it, watchIds) }
        val html = draft?.html
        // HTML cards keep the old fingerprint, so an upgrade doesn't re-post (and reset) a card already on the phone.
        val cardDigest = html?.let { AppletPackage.sha256(plan.card!!.title + "\n" + (if (draft.kind == cc.bridgit.app.interact.IKind.PIECES) "pieces\n" else "") + it) }
        if (cardDigest != r.optString("card_digest").takeIf { it.isNotEmpty() } || (html != null && !cardOpen(r))) {
            closeCard(r)
            if (html != null) {
                val i = app.interactions.create(agentId, agentName, draft, null)
                r.put("card_id", i.id).put("card_digest", cardDigest)
            } else r.remove("card_digest")
        }
    }

    /**
     * The applet's card to open from Installed applets (owner, 5 Nov 2026: "how do I run the insurance applet?"):
     * its id, posted again first when it was closed. Null for an applet without a card.
     */
    suspend fun openCard(instanceId: String): Long? = lock.withLock {
        checkTemplateSoon(instanceId)
        val r = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
        if (r.optString("native") == "dog") return@withLock null
        if (!cardOpen(r)) {
            val definition = r.getJSONObject("definition")
            val plan = AppletPackage.compile(AppletPackage.check(definition), r.getJSONObject("config"))
            if (plan.card == null) return@withLock null
            r.remove("card_digest") // forces a fresh post
            syncCard(r, r.getString("agent_id"), r.optString("agent_name").ifEmpty { r.optString("name") }, plan, definition)
            put(r); changed()
        }
        r.optLong("card_id", -1).takeIf { it > 0 }
    }

    /** Whether [r]'s web portal is its screen now: the package says so and the portal is on. */
    fun portalIsScreen(r: JSONObject, definition: JSONObject? = r.optJSONObject("definition")): Boolean =
        cc.bridgit.app.remoteview.PortalTables.portalIsInterface(definition) &&
            app.remoteView.packages.status(r.optString("package_id")).optBoolean("personal_backend")

    /** [packageId]'s portal went on or off: its card is posted or closed to match (see [portalIsScreen]). */
    fun portalChanged(packageId: String) {
        scope.launch {
            runCatching { lock.withLock {
                val r = byPackage(packageId)?.takeIf { it.optString("native") != "dog" && it.optString("status") == "active" } ?: return@withLock
                val definition = r.getJSONObject("definition")
                if (!cc.bridgit.app.remoteview.PortalTables.portalIsInterface(definition)) return@withLock
                val plan = AppletPackage.compile(AppletPackage.check(definition), r.getJSONObject("config"))
                syncCard(r, r.getString("agent_id"), r.optString("agent_name").ifEmpty { r.optString("name") }, plan, definition)
                put(r); changed()
            } }
        }
    }

    /** Whether [r] has a card in its package. */
    fun hasCard(r: JSONObject): Boolean = r.optString("native") != "dog" && r.optJSONObject("definition")?.optJSONObject("ui")?.has("card") == true

    private fun cardOpen(r: JSONObject): Boolean {
        val id = r.optLong("card_id", -1).takeIf { it > 0 } ?: return false
        return app.interactions.getFor(r.optString("agent_id"), id)?.status?.open == true
    }

    private fun closeCard(r: JSONObject) {
        val id = r.optLong("card_id", -1).takeIf { it > 0 } ?: return
        runCatching { app.interactions.cancel(r.getString("agent_id"), id) }
        r.remove("card_id")
    }

    private fun removeWorkflow(agentId: String, id: String) {
        runCatching { app.platform.workflows.pause(agentId, id) }
        app.platform.store.delete(id, agentId)
    }

    /**
     * Updates [instanceId] to [p] (from an agent, a link or the owner). Applies at once when it needs no
     * permission the instance doesn't already have; otherwise it waits for the owner like a new install.
     */
    suspend fun update(instanceId: String, p: AppletPackage.Package, given: JSONObject?, source: JSONObject, by: Agent?, change: JSONObject? = null): JSONObject = lock.withLock {
        updateLocked(instanceId,p,given,source,by,change)
    }

    private suspend fun updateLocked(instanceId: String, p: AppletPackage.Package, given: JSONObject?, source: JSONObject, by: Agent?, change: JSONObject?): JSONObject {
        val r = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
        authorizeDeveloper(r,by?.id)
        if (by != null && source.optString("kind") != "template" || p.definition.optJSONObject("ui")?.has("practices") == true || r.getJSONObject("definition").optJSONObject("ui")?.has("practices") == true) AppletPractices.requireChange(p.definition,r.getJSONObject("definition"))
        change?.let {
            expect(r, it.optLong("expected_revision", -1))
            if (it.has("task_id")) {
                val task=AppletEvolution.state(r).optJSONObject("task")
                if (!AppletEvolution.tracking(r) || task == null || task.optString("id") != it.optString("task_id") || task.optLong("expected_revision") != r.optLong("revision") || task.optString("status") !in setOf("needs_agent","needs_verification"))
                    throw Refused("conflict", "This upgrade task is stale or cancelled; prepare again.")
            }
        }
        guardTemplate(r, source, p)
        if (r.optString("package_id") != p.id) throw Refused("invalid_parameter", "That's a different applet (${p.id}); ${r.optString("name")} is ${r.optString("package_id")}.")
        if (convertsDog(r,p)) return proposeChange(p,given,source,by,instanceId,change,r.optLong("revision"))
        val cfg = filledConfig(p, given, r)
        val review = review(p, cfg, instanceId)
        val added = AppletPackage.strings(review.getJSONObject("update").getJSONArray("added_permissions"))
        if (newLibraryOwner(r,source))
            return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision"))
        if (change != null) return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision"))
        if (p.adapter == AppletPackage.DOG_ADAPTER) {
            val previous = r.optJSONObject("definition")?.optJSONObject("processing")?.optJSONObject("classifier")
            val next = p.definition.optJSONObject("processing")?.optJSONObject("classifier")
            if (previous?.let(AppletPackage::digest) != next?.let(AppletPackage::digest))
                return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).put("classifier_changed", true)
        }
        if (added.isNotEmpty()) return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).put("added_permissions", JSONArray(added.sorted()))
        // A table newly shared with the installing agent is new access too: the owner approves it like a permission.
        val shares = review.optJSONObject("update")?.optJSONArray("shares")?.let(AppletPackage::strings) ?: emptyList()
        if (shares.isNotEmpty()) return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).put("shares", JSONArray(shares))
        // An update that deletes rows (a table or column it drops, or a column's type it changes) waits for the owner too.
        val deletes = review.optJSONObject("update")?.optJSONArray("deletes")?.let(AppletPackage::strings) ?: emptyList()
        if (deletes.isNotEmpty()) return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).put("deletes", JSONArray(deletes))
        // A web portal that now comes with the applet sends data off the phone: the owner reads what and approves it.
        if (cc.bridgit.app.remoteview.PortalRecords.always(p.definition) && !cc.bridgit.app.remoteview.PortalRecords.always(r.optJSONObject("definition")) &&
            !app.remoteView.packages.status(p.id).optBoolean("personal_backend"))
            return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).put("portal_always", true)
        val currentMedia = AppletPackage.detachedMedia(r.getJSONObject("definition")).values.toSet()
        if (AppletPackage.detachedMedia(p.definition).values.any { it !in currentMedia })
            return proposeChange(p, given, source, by, instanceId, change, r.optLong("revision"))
        val applied = run {
            // Counted again under the lock: rows written since the review may now be lost, and then the owner decides.
            val cur = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
            authorizeDeveloper(cur,by?.id)
            expect(cur, r.optLong("revision"))
            guardTemplate(cur, source, p)
            if (updateLosses(cur, AppletPackage.compile(p, cfg)).isNotEmpty()) null
            else {
                val updated = applyUpdate(cur, p, cfg, emptyList(), null, source)
                finishEvolution(updated, source, change)
                JSONObject().put("status", "updated").put("instance", updated).put("review", review)
            }
        }
        return applied ?: proposeChange(p, given, source, by, instanceId, change, r.optLong("revision")).let { it.put("deletes", it.getJSONObject("review").getJSONObject("update").getJSONArray("deletes")) }
    }

    /** A loss line without its counts, so "120 rows" and "125 rows" of the same column compare equal. */
    private fun lossKind(line: String) = if (line.startsWith("Discard the saved counts and episode history of watch ")) line else line.replace(Regex("\\d+"), "#")

    /**
     * Applies an update. [approvedLoss] is the data loss the owner approved: the losses are counted again here,
     * and one the owner didn't see (a table or column that now has rows) stops the update before anything changes;
     * [pendingId]'s request then keeps waiting with the new lines.
     */
    private suspend fun applyUpdate(r: JSONObject, p: AppletPackage.Package, cfg: JSONObject, approvedLoss: List<String>, pendingId: String?, source: JSONObject, beforeActivate: (() -> Unit)? = null): JSONObject {
        if (p.definition.optJSONObject("ui")?.has("practices") == true || r.getJSONObject("definition").optJSONObject("ui")?.has("practices") == true)
            AppletPractices.requireChange(p.definition, r.getJSONObject("definition"))
        val plan = AppletPackage.compile(p, cfg)
        val losses = updateLosses(r, plan)
        val seen = approvedLoss.map(::lossKind).toSet()
        if (losses.any { lossKind(it) !in seen }) {
            pendingId?.let { id -> pendingGet(id)?.let { synchronized(this) { pendingPrefs.edit().putString(id, it.put("deletes", JSONArray(losses)).toString()).commit() } } }
            changed()
            throw Refused("data_changed", "Since you looked, this update would delete more saved data: ${losses.joinToString(" ")} Check the update again.")
        }
        val grants = checkReady(p, plan)
        return withMedia(r, p, source) {
            beforeActivate?.invoke()
            if (convertsDog(r,p)) {
                check(pendingId != null) { "Dog Monitor conversion requires owner approval." }
                return@withMedia convertDog(r,p,cfg,source,plan,grants,pendingId)
            }
            // Checkpoint intent/source before any destructive runtime operation. Not a transactional data rollback.
            try { AppletEvolution.record(r, "Update to ${p.version}", JSONArray()) }
            catch (e: IllegalArgumentException) { throw Refused("conflict", e.message ?: "Journal full") }
            put(r)
            val fromVersion = r.optString("version")
            if (p.adapter == AppletPackage.DOG_ADAPTER) {
                if (r.optString("status") == "active") startDog(plan.native!!) else applyDogConfig(plan.native!!)
            } else {
                val agentId = r.optString("agent_id")
                val agent = app.agents.update(agentId) { it.copy(caps = grants intersect moduleIds(), notes = "Installed applet ${p.id} ${p.version}") }
                    ?: throw Refused("not_found", "This applet's agent was removed. Remove the applet and install it again.")
                // Columns and tables this revision drops (the owner approved the update, which said so).
                dropLostTables(r, plan)
                portalAlwaysOn(p) // the owner approved it (an update that makes the portal "always" waits for them)
                try { build(r, agent, plan, null, grants, p.definition) } catch (e: Exception) {
                    r.put("problem", "Update to ${p.version} stopped half-way: ${e.message}"); put(r); changed()
                    throw if (e is Refused) e else Refused(codeOf(e), "The update stopped half-way: ${e.message}. The applet keeps what was already changed; update again or remove it.")
                }
                // Platform grants no workflow needs any more are taken back.
                val keep = workflowIds(r).flatMap { id -> app.platform.store.find(id)?.let { AppletPackage.strings(it.getJSONArray("required_scopes")) } ?: emptyList() }.toSet()
                AppletPackage.strings(app.platform.store.grants(agentId).names() ?: JSONArray()).filter { it !in keep }.forEach { app.platform.store.revoke(agentId, it) }
            }
            r.put("edition_source", source).put("version", p.version).put("sha256", p.sha256).put("definition", p.definition).put("config", cfg)
                .put("declared", JSONArray(grants.sorted())).put("updated_at", System.currentTimeMillis()).remove("problem")
            put(r); changed()
            app.log.system("audit", "applets", "Applet ${p.name} updated from $fromVersion to ${p.version}")
            r
        }
    }

    /**
     * The owner's limits for an applet ([limits] null: back to the package's), kept on the instance for offline
     * display. The backend holds the real ones for every phone of the account: the change is sent there right away
     * ([BudgetSync.setLimits]) and its refusal, if any, comes back to the owner's screen.
     */
    suspend fun setBudget(instanceId: String, limits: List<AppletBudget.Limit>?): JSONObject = lock.withLock {
        val r = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
        if (limits == null) r.remove("budget_owner")
        else {
            if (limits.isEmpty() || limits.any { it.usd !in AppletBudget.MIN_USD..AppletBudget.MAX_USD }) throw Refused("invalid_parameter", "Each limit is from \$0.01 to \$100.")
            r.put("budget_owner", JSONObject().put("limits", AppletBudget.json(limits)))
        }
        r.put("updated_at", System.currentTimeMillis()); put(r); changed()
        app.log.system("audit", "applets", "Applet ${r.optString("name")} spending limits: ${AppletBudget.limitsOf(null, r).joinToString { "${AppletBudget.money(it.usd)} ${AppletBudget.perWords(it.per)}" }}")
        r
    }

    /** Old screens' call: a day and a month limit. */
    suspend fun setBudget(instanceId: String, dayUsd: Double?, monthUsd: Double?): JSONObject =
        if (dayUsd == null && monthUsd == null) setBudget(instanceId, null)
        else setBudget(instanceId, try { AppletBudget.parse(JSONObject().apply { dayUsd?.let { put("day_usd", it) }; monthUsd?.let { put("month_usd", it) } })!! }
            catch (e: AppletPackage.Bad) { throw Refused("invalid_parameter", e.message ?: "Bad limit.") })

    /** Turns the Budgets permission on for [agentId] (the agent that asked to install an applet). */
    fun grantBudgets(agentId: String) {
        val a = app.agents.get(agentId) ?: return
        if (a.allows(BudgetModule.ID)) return
        app.agents.update(agentId) { it.copy(caps = it.caps?.plus(BudgetModule.ID)) }
        app.log.system("audit", "applets", "${a.name} may now change the budgets of the applets it installed (Budgets)")
    }

    /** Once after the update that brought budgets: the agents that installed applets get Budgets, like new installs. */
    fun grantBudgetsOnce() {
        val p = app.getSharedPreferences("applet_budget", Context.MODE_PRIVATE)
        if (p.getBoolean("granted_requesters", false)) return
        for (r in all()) r.optString("requested_by").takeIf { !r.isNull("requested_by") && it.isNotEmpty() }?.let { runCatching { grantBudgets(it) } }
        p.edit().putBoolean("granted_requesters", true).apply()
    }

    /** Each resource acknowledges only after its task-owned work has stopped. */
    suspend fun setPaused(instanceId: String, paused: Boolean): JSONObject = lock.withLock {
        val r = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
        val agentId = r.optString("agent_id")
        val ws = r.optJSONObject("watches") ?: JSONObject()
        val watches = ws.keys().asSequence().map { ws.getJSONObject(it).getString("watch_id") }.toList()
        val workflows = workflowIds(r)
        val participants = if (r.optString("native") == "dog") listOf("native:dog", "devices") else
            watches.map { "watch:$it" } + workflows.map { "workflow:$it" } +
                (if (r.has("schedule_digest")) listOf("schedule") else emptyList()) + listOf("speaker", "devices")
        var task = AppletLifecycle.read(r, participants)
        if (!paused && task.phase == AppletTaskState.Phase.PAUSING)
            throw Refused("conflict", "Still waiting for this task to stop.")
        if (!paused) blockers(AppletPackage.strings(r.getJSONArray("declared")).toSet(),
            AppletPackage.compile(AppletPackage.check(r.getJSONObject("definition")), r.getJSONObject("config")))
            .firstOrNull()?.let { throw Refused("capability_disabled", it) }
        if (paused && task.phase == AppletTaskState.Phase.RUNNING && r.optBoolean("converted_from_dog")) {
            r.put("resume_watches", JSONArray(watches.filter { app.watches.get(agentId, it)?.userPaused == false }))
        }
        task = if (paused) task.pause() else task.resume()
        fun save() {
            r.put("lifecycle", AppletLifecycle.json(task))
                .put("status", if (task.phase == AppletTaskState.Phase.RUNNING) "active" else "paused")
                .put("updated_at", System.currentTimeMillis())
            put(r); changed()
        }
        save() // Fence new background work before cancellation or activation.
        val failures = JSONObject()
        for (part in task.pending.toList()) {
            try {
                when {
                    part == "native:dog" -> if (paused) app.dog.stopForConversion() else {
                        startDog(AppletPackage.compile(AppletPackage.check(r.getJSONObject("definition")), r.getJSONObject("config")).native!!)
                        check(app.dog.state.value.running) { "Pet Monitor has not started." }
                    }
                    part.startsWith("watch:") -> {
                        val id = part.removePrefix("watch:")
                        val selected = r.optJSONArray("resume_watches")?.let(AppletPackage::strings)
                        if (app.watches.get(agentId, id) != null && (paused || selected == null || id in selected))
                            app.watches.setPaused(agentId, id, paused)
                    }
                    part.startsWith("workflow:") -> {
                        val id = part.removePrefix("workflow:")
                        if (paused) app.platform.workflows.pauseAndWait(agentId, id)
                        else app.platform.workflows.activate(id)
                    }
                    part == "schedule" -> if (paused) app.schedules.pauseAndWait(agentId)
                        else app.schedules.setPaused(agentId, false, byOwner = true)
                    part == "speaker" -> if (paused) app.speaker.stopAndWait(agentId)
                    part == "devices" -> if (paused) kotlinx.coroutines.withContext(Dispatchers.IO) {
                        AppletDeviceStops.stopOwner(app, if (r.optString("native") == "dog") "native:dog" else agentId)
                    }
                }
                task = task.acknowledge(task.taskId, task.generation, part, task.phase)
                save()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failures.put(part, e.message ?: e.javaClass.simpleName) }
        }
        if (task.phase == AppletTaskState.Phase.RUNNING) r.remove("resume_watches")
        r.put("lifecycle_errors", failures); put(r); changed()
        app.log.system("audit", "applets", "Applet ${r.optString("name")} is ${task.phase.name.lowercase()}")
        r
    }

    private val dogConversions by lazy { app.getSharedPreferences("applet_dog_conversions", Context.MODE_PRIVATE) }

    @Volatile private var stagingDogAgent: String? = null
    internal fun canProposeTableWorkflow(agentId: String) = agentId == stagingDogAgent || forAgent(agentId) != null

    internal fun legacyDogReplaced(): Boolean = dogConversions.all.isNotEmpty()

    /** If the process died before activation, discard only the newly staged principal and its owned resources. */
    private fun recoverDogConversion(id: String) {
        val marker = dogConversions.getString(id,null)?.let(::JSONObject) ?: return
        val agentId = marker.getString("agent")
        val restore = marker.optBoolean("running") && get(id)?.optString("native") == "dog"
        val activated = get(id)?.optString("agent_id") == agentId
        if (!activated) {
            clearAgentParts(agentId)
            historyArchive.remove(id)
        }
        check(dogConversions.edit().remove(id).apply { if (activated) putString("retired",id) }.commit()) { "Could not finalize Dog Monitor conversion." }
        if (restore && !app.dog.state.value.running) app.dog.start()
    }

    private suspend fun convertDog(r: JSONObject, p: AppletPackage.Package, cfg: JSONObject, source: JSONObject, plan: AppletPackage.Plan, grants: Set<String>, pendingId: String): JSONObject {
        val id = r.getString("id")
        recoverDogConversion(id)
        require(plan.schedule == null && plan.flows.all { (_,flow) -> flow.optJSONObject("trigger")?.optString("type") == "called" } &&
            p.definition.optJSONObject("ui")?.has("portal") != true) { "Convert with owner-started flows before adding automatic triggers or a portal." }
        val staged = JSONObject(r.toString())
        AppletEvolution.record(staged,"Convert Dog Monitor to ${p.version}",JSONArray())
        staged.remove("native")
        staged.put("conversion_review", pendingId).put("resume_watches", JSONArray())
        staged.put("legacy_budget", JSONObject().put("limits",AppletBudget.json(AppletBudget.packageLimits(r))))
        staged.put("converted_from_dog", true).put("edition_source", source).put("version", p.version)
            .put("sha256",p.sha256).put("definition",p.definition).put("config",cfg).put("status","paused")
            .put("declared",JSONArray(grants.sorted())).put("updated_at",System.currentTimeMillis())
        val running = app.dog.state.value.running
        try {
            val agent = newAgent(p, grants) { a ->
                val marker = JSONObject().put("agent",a.id).put("running",running)
                check(dogConversions.edit().putString(id,marker.toString()).commit()) { "Could not checkpoint the new applet principal." }
            }
            staged.put("agent_id",agent.id).put("agent_name",agent.name)
            stagingDogAgent = agent.id
            try { build(staged,agent,plan,null,grants,p.definition) } finally { stagingDogAgent = null }
            app.dog.stopForConversion()
            val bytes = AppletJsonExport.dogDocument(app,r).toString().toByteArray()
            historyArchive.save(id,historyArchive.prepareDog(bytes.inputStream()))
            val bowl = cfg.optString("bowl_camera")
            if (bowl.startsWith("lan:") && bowl == app.dog.feeding.camera) {
                val saved = app.dog.feeding.transferableView()
                    ?: throw Refused("camera_unavailable","Access to the saved bowl view changed. Review the camera again.")
                app.cameraViews.replace(agent.id,"bowl",null,saved)
            }
            finishEvolution(staged,source,pendingGet(pendingId)?.optJSONObject("change"))
        } catch (e: Exception) {
            recoverDogConversion(id)
            throw e
        }
        recoverDogConversion(id)
        changed()
        return staged
    }

    private val historyArchive by lazy { AppletHistoryArchive(app) }

    internal suspend fun importDogHistory(id: String, input: java.io.InputStream): JSONObject {
        // A document provider may be slow. Only database activation needs to serialize with removal and update.
        val archive = historyArchive.prepareDog(input)
        lock.withLock {
            val r = get(id) ?: throw Refused("not_found", "This applet was removed.")
            require(r.optString("package_id") == "dog-monitor" && r.has("agent_id") && r.optString("native") != "dog") {
                "Install the portable Dog Monitor edition to import history."
            }
            historyArchive.save(id, archive)
        }
        return archive.history
    }

    internal suspend fun dogHistoryArchive(id: String): JSONObject? = lock.withLock {
        if (get(id)?.optString("package_id") == "dog-monitor") historyArchive.read(id) else null
    }

    /** Removes everything the instance created, then its agent. Returns what (if anything) was left behind. */
    suspend fun remove(instanceId: String): JSONObject = lock.withLock {
        val r = get(instanceId) ?: throw Refused("not_found", "No installed applet $instanceId.")
        recoverDogConversion(instanceId)
        if (r.optString("package_id") == "dog-monitor") historyArchive.remove(instanceId)
        val left = tearDown(r)
        if (AppletPackage.detachedMedia(r.getJSONObject("definition")).isNotEmpty()) packageMedia.prune(instanceId, null)
        app.log.system("audit", "applets", "Applet ${r.optString("name")} removed" + (if (left.length() > 0) " (left: $left)" else ""))
        changed()
        JSONObject().put("removed", instanceId).put("left_behind", left)
    }

    private fun tearDown(r: JSONObject): JSONArray {
        if (r.optString("native") == "dog") { app.dog.stop(); app.dog.configureClassifier(null); AppletBudget.forget(app, AppletBudget.DOG); drop(r.getString("id")); return JSONArray() }
        val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() }
        if (agentId != null) {
            closeCard(r)
            clearAgentParts(agentId)
            AppletBudget.forget(app, AppletBudget.appletOf(r))
        }
        drop(r.getString("id"))
        return leftovers(agentId)
    }

    private fun clearAgentParts(agentId: String) {
        for (w in app.watches.list(agentId)) app.watches.delete(agentId, w.id)
        app.schedules.remove(agentId)
        app.platform.store.list(agentId, "workflow", 1000).forEach { removeWorkflow(agentId, it.getString("id")) }
        app.interactions.closeAllFor(agentId)
        runCatching { AppletTableStore.of(app).dropAll(agentId) }
        app.platform.revoke(agentId)
        app.platform.store.deleteAll(agentId)
        app.agents.remove(agentId)
        app.cameraViews.removeAgent(agentId)
    }

    /** Anything still tied to a removed applet's agent: watches, a schedule, platform records, open cards. */
    fun leftovers(agentId: String?): JSONArray {
        val out = JSONArray()
        if (agentId == null) return out
        if (app.watches.list(agentId).isNotEmpty()) out.put("watches")
        if (app.schedules.record(agentId) != null) out.put("schedule")
        if (AppletTableStore.of(app).hasAny(agentId)) out.put("tables")
        if (app.platform.store.list(agentId, null, 10).isNotEmpty()) out.put("platform records")
        if (app.interactions.listFor(agentId, IStatus.PENDING, 10).isNotEmpty() || app.interactions.listFor(agentId, IStatus.SEEN, 10).isNotEmpty()) out.put("cards")
        if (app.agents.get(agentId) != null) out.put("agent")
        return out
    }

    // ---- the Pet monitor adapter ---------------------------------------------------------------------------------

    private fun applyDogConfig(n: JSONObject) {
        app.dog.configureClassifier(n.optJSONObject("classifier"))
        n.optString("camera").takeIf { it == "front" || it == "rear" }?.let { app.dog.setFrontCamera(it == "front") }
    }
    private fun startDog(n: JSONObject) { applyDogConfig(n); app.dog.start() }

    // ---- keeping it running --------------------------------------------------------------------------------------

    /** Whether a watch's lifetime should be extended: it belongs to an active installed applet. */
    fun renews(agentId: String): Boolean = forAgent(agentId)?.optString("status") == "active"

    /** Re-posts an applet's card if it expired, every few hours while Bridgit runs. */
    fun start() {
        // A row in a table shared with the agent that installed the applet: that agent hears so in /v1/events.
        AppletTableStore.of(app).rowAdded = { agentId, table, rowId ->
            forAgent(agentId)?.let { r ->
                r.optString("requested_by").takeIf { !r.isNull("requested_by") && it.isNotEmpty() && app.agents.get(it) != null }?.let {
                    app.interactions.event(it, "applet.row_added", JSONObject().put("instance_id", r.getString("id")).put("table", table).put("row_id", rowId))
                }
            }
        }
        scope.launch { lock.withLock { dogConversions.all.keys.filter { it != "retired" }.forEach(::recoverDogConversion) } }
        scope.launch {
            for (r in all()) {
                when (AppletLifecycle.state(r)) {
                    "pausing" -> runCatching { setPaused(r.getString("id"), true) }
                    "starting" -> runCatching { setPaused(r.getString("id"), false) }
                }
            }
        }
        scope.launch { delay(15_000); all().forEach { checkTemplateSoon(it.getString("id")) } }
        scope.launch {
            while (isActive) {
                delay(CARD_CHECK_MS)
                runCatching { refreshCards() }
                runCatching { cleanTables() }
            }
        }
        scope.launch { delay(30_000); runCatching { refreshCards() } }
    }

    /** Table rows past their keep_days or max_rows, and kept photos no row uses, are deleted (every few hours). */
    fun cleanTables() {
        val store = AppletTableStore.of(app)
        for (r in all()) {
            val agentId = r.optString("agent_id").takeIf { it.isNotEmpty() } ?: continue
            val tables = runCatching { AppletTables.parse(r.getJSONObject("definition").optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
            runCatching { store.clean(agentId, tables) }
        }
    }

    suspend fun refreshCards() = lock.withLock {
        for (r in all()) {
            if (r.optString("status") != "active" || r.optString("native") == "dog") continue
            val id = r.optLong("card_id", -1).takeIf { it > 0 } ?: continue
            val i = app.interactions.getFor(r.getString("agent_id"), id) ?: continue
            if (i.status != IStatus.EXPIRED) continue // an owner who dismissed it keeps it dismissed
            val agent = app.agents.get(r.getString("agent_id")) ?: continue
            val p = runCatching { AppletPackage.check(r.getJSONObject("definition")) }.getOrNull() ?: continue
            r.remove("card_digest")
            runCatching { build(r, agent, AppletPackage.compile(p, r.getJSONObject("config")), null, AppletPackage.strings(r.optJSONArray("declared") ?: org.json.JSONArray()).toSet(), p.definition) }
            put(r)
        }
    }

    // ---- telling the owner ---------------------------------------------------------------------------------------

    private fun notice(r: JSONObject, p: AppletPackage.Package) {
        if (!app.granted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val id = r.getString("id")
        val open = PendingIntent.getActivity(app, id.hashCode(), Intent(app, AppletInstallActivity::class.java).putExtra(AppletInstallActivity.EXTRA_PENDING, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val who = r.optString("requested_by_name").takeIf { !r.isNull("requested_by_name") && it.isNotEmpty() } ?: "A link"
        val update = !r.isNull("replaces")
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + (id.hashCode() and 0xfff), NotificationCompat.Builder(app, BridgeApp.INTERACTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (update) "$who wants to update ${p.name}" else "$who wants to install ${p.name}")
            .setContentText("Review what it uses before anything runs.")
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    private fun cancelNotice(id: String) = app.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + (id.hashCode() and 0xfff))

    companion object {
        /** What a permission means for an applet (the module descriptions are written for agents). */
        val WORDS = mapOf(
            "camera" to "Camera: takes photos for this applet",
            "watches" to "Watches: runs its checks on this device by itself",
            "interactions" to "Cards: shows its card and notices on this device",
            "schedule" to "Scheduled actions: runs its actions at set times",
            "weather" to "Weather: fetches the forecast over the internet",
            "mic" to "Microphone: records only when you tap Record",
            "ocr" to "Text in photos: reads a photo you pick",
            "find_crop" to "Find, crop and price: finds and reads things in photos (faces on this device, the rest by a paid cloud model)",
            "applet_data" to "Its own records: keeps what it saves on this device; removing the applet deletes them",
            "lan_control" to "Network devices: lists the devices you enrolled",
            "sounds" to "Loud sounds: listens through the microphone",
            "workflows" to "Workflows: runs its approved steps",
            "decisions" to "Jev and Clef: sends questions, and the photos they look at, off this device for paid answers",
            "location" to "Location: knows where this device is",
            AppletPackage.PEOPLE_SEEN to "Spot people in the kitchen: when the picture changes, takes a few photos, finds faces on this device and sends face photos to your private web portal, where only you can name people. Photos there are deleted after 30 days.",
        )
        const val MAX_PENDING = 8
        const val MAX_INSTANCES = 20
        const val PENDING_MS = 7L * 24 * 3600 * 1000
        /** Platform grants for an applet's workflow last as long as the applet (removal revokes them). */
        const val GRANT_MS = 10L * 365 * 24 * 3600 * 1000
        const val CARD_CHECK_MS = 3L * 3600 * 1000
        const val NOTIF_BASE = 47_000
    }
}
