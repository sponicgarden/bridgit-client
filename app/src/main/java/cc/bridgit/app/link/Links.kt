package cc.bridgit.app.link

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.Net
import cc.bridgit.app.satellite.HubLinks
import cc.bridgit.app.satellite.LinkPause
import cc.bridgit.app.satellite.SatelliteClient
import cc.bridgit.app.satellite.SatelliteHub
import cc.bridgit.app.satellite.SatelliteLink
import cc.bridgit.app.satellite.SatelliteStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linked Bridgits (docs/spec-linked-bridgits.md) on top of today's satellite stores. Everything physical stays where
 * it was, so existing links keep their sat: ids, keys and agent access (LNK-54):
 *
 * - [Role.HUB]: the other device is this one's satellite. "out" (this uses it) is the [SatelliteStore] entry: its key
 *   is the other device's link agent, its agents are this device's user opt-in. "in" (it uses this) is the entry's
 *   return agent here, whose grants are the service list.
 * - [Role.SAT]: the other device is this one's hub. "in" is the [HubLinks] agent here (grants = service list);
 *   "out" is the return key the hub sent ([HubLinks.HubInfo]), with [Rec.outAgents] as the user opt-in.
 *
 * This class keeps only what is new: the shared lnk_ id, both halves of both directions with who granted them, the
 * other device's halves as it last reported them, a disconnect waiting for its Undo, and the link codes.
 * Taking access away needs less than giving it (LNK-29): the callers check who may do what.
 */
class Links(private val app: BridgeApp) {
    enum class Role(val wire: String) { HUB("hub"), SAT("satellite") }

    /** The other device's half of a direction, as it last reported it. */
    data class Far(val optedIn: Boolean, val paused: Boolean = false, val services: Set<String> = emptySet(), val at: Long = 0) {
        fun half() = LinkModel.Half(optedIn, paused)
        fun toJson(): JSONObject = JSONObject().put("opted_in", optedIn).put("paused", paused).put("services", LinkModel.json(services)).put("at", at)
        companion object {
            fun fromJson(o: JSONObject?): Far? = o?.let {
                Far(it.optBoolean("opted_in"), it.optBoolean("paused"), runCatching { LinkModel.parseServices(it.optJSONArray("services")) }.getOrDefault(emptySet()), it.optLong("at"))
            }
        }
    }

    /** A disconnect that takes effect now and becomes final at [until] unless undone (LNK-26). */
    data class Pending(val direction: String, val until: Long, val granter: Granter, val eventId: String? = null)

    data class Rec(
        val id: String, val role: Role, val ref: String, val createdAt: Long,
        val peerName: String? = null, val peerInstall: String? = null, val provisional: Boolean = false,
        val inServices: Set<String> = emptySet(), val inGranter: Granter? = null, val inRevoked: Boolean = false,
        val outOptIn: Boolean = false, val outGranter: Granter? = null, val outPaused: Boolean = false, val outRevoked: Boolean = false,
        val outAgents: Set<String> = emptySet(),
        val farIn: Far? = null, val farOut: Far? = null, val pending: Pending? = null,
        /** The other Bridgit's platform as it said when claiming (ios, ipados, macos); null = Android. */
        val platform: String? = null,
    ) {
        fun covers(direction: String) = pending != null && (pending.direction == "both" || pending.direction == direction)

        fun toJson(): JSONObject = JSONObject().put("id", id).put("role", role.wire).put("ref", ref).put("created_at", createdAt)
            .put("peer_name", peerName ?: JSONObject.NULL).put("peer_install", peerInstall ?: JSONObject.NULL).put("provisional", provisional)
            .put("in_services", LinkModel.json(inServices)).put("in_granter", inGranter?.toJson() ?: JSONObject.NULL).put("in_revoked", inRevoked)
            .put("out_opt_in", outOptIn).put("out_granter", outGranter?.toJson() ?: JSONObject.NULL).put("out_paused", outPaused)
            .put("out_revoked", outRevoked).put("out_agents", JSONArray(outAgents.sorted()))
            .put("far_in", farIn?.toJson() ?: JSONObject.NULL).put("far_out", farOut?.toJson() ?: JSONObject.NULL)
            .put("platform", platform ?: JSONObject.NULL)
            .put("pending", pending?.let { JSONObject().put("direction", it.direction).put("until", it.until).put("granter", it.granter.toJson()).put("event_id", it.eventId ?: JSONObject.NULL) } ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject): Rec {
                fun str(k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotEmpty() }
                fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                return Rec(o.getString("id"), if (o.optString("role") == "hub") Role.HUB else Role.SAT, o.getString("ref"), o.optLong("created_at"),
                    str("peer_name"), str("peer_install"), o.optBoolean("provisional"),
                    set("in_services").filter { it in LinkModel.SERVICES }.toSet(), Granter.fromJson(o.optJSONObject("in_granter")), o.optBoolean("in_revoked"),
                    o.optBoolean("out_opt_in"), Granter.fromJson(o.optJSONObject("out_granter")), o.optBoolean("out_paused"), o.optBoolean("out_revoked"),
                    set("out_agents"), Far.fromJson(o.optJSONObject("far_in")), Far.fromJson(o.optJSONObject("far_out")),
                    o.optJSONObject("pending")?.let { p -> Granter.fromJson(p.optJSONObject("granter"))?.let { Pending(p.optString("direction"), p.optLong("until"), it, p.optString("event_id").takeIf { e -> e.isNotEmpty() && !p.isNull("event_id") }) } },
                    str("platform")?.takeIf { it in PLATFORMS })
            }
        }
    }

    private val prefs = app.getSharedPreferences("links_v1", Context.MODE_PRIVATE)
    val log by lazy { LinkLog(app) }

    // ---- Records ---------------------------------------------------------------------------------

    @Synchronized private fun load(): List<Rec> {
        val a = runCatching { JSONArray(prefs.getString("links", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { runCatching { Rec.fromJson(it) }.getOrNull() } }
    }

    @Synchronized private fun save(list: List<Rec>) {
        check(prefs.edit().putString("links", JSONArray(list.map { it.toJson() }).toString()).commit())
        changes.value = changes.value + 1
    }

    /**
     * Every link, after matching the records to the satellite stores: a satellite or hub link without a record is an
     * existing link from before this version and gets one now (LNK-54: both directions on as they were, granter
     * "migrated" with the original pairing time); a record whose satellite or hub link is gone is dropped.
     */
    @Synchronized fun all(now: Long = System.currentTimeMillis()): List<Rec> {
        val recs = load()
        val sats = app.satellites.metas()
        val hubs = app.hubLinks.agents()
        // A claim in progress makes its agent or satellite entry just before its record: not a link to migrate.
        if (claiming.get() > 0) return recs.filter { r -> when (r.role) { Role.HUB -> sats.any { it.id == r.ref }; Role.SAT -> hubs.any { it.id == r.ref } } }
        val me = selfName()
        val kept = recs.filter { r -> when (r.role) { Role.HUB -> sats.any { it.id == r.ref }; Role.SAT -> hubs.any { it.id == r.ref } } }
        val added = mutableListOf<Rec>()
        for (s in sats) if (kept.none { it.role == Role.HUB && it.ref == s.id }) {
            val at = s.addedAt.takeIf { it > 0 } ?: now
            val g = Granter.migrated(at, me)
            val caps = s.manifest?.optJSONArray("capabilities")
            val offered = caps?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optBoolean("enabled") }.map { it.optString("id") }.toSet() }
                ?: cc.bridgit.app.satellite.SatellitePolicy.PHASE1_CAPS
            added += Rec(LinkIds.newLink(), Role.HUB, s.id, at, peerInstall = s.install,
                inServices = setOf(LinkModel.VIEW), inGranter = g, outOptIn = true, outGranter = g,
                farIn = Far(true, at = at), farOut = Far(true, services = offered, at = at))
        }
        for (a in hubs) if (kept.none { it.role == Role.SAT && it.ref == a.id }) {
            val at = a.createdAt
            val g = Granter.migrated(at, me)
            val info = app.hubLinks.hubs().firstOrNull { it.linkId == a.id }
            added += Rec(LinkIds.provisional(a.id), Role.SAT, a.id, at, provisional = true,
                inServices = LinkModel.servicesOf(a.caps), inGranter = g, outOptIn = info != null, outGranter = if (info != null) g else null,
                farIn = Far(true, at = at), farOut = if (info != null) Far(true, services = setOf(LinkModel.VIEW), at = at) else null)
        }
        val result = kept + added
        if (added.isNotEmpty() || kept.size != recs.size) {
            save(result)
            added.forEach { r -> log.add(entry(r, "both", "migrated", null, JSONObject().put("in", LinkModel.json(r.inServices)).put("out", r.outOptIn),
                r.inGranter ?: Granter.migrated(r.createdAt, me), delivered = true)) }
        }
        return result
    }

    fun get(id: String): Rec? = all().firstOrNull { it.id == id }

    /** Claims being saved (their agent exists a moment before their record): [all] doesn't migrate meanwhile. */
    private val claiming = java.util.concurrent.atomic.AtomicInteger(0)
    private inline fun <T> whileClaiming(f: () -> T): T { claiming.incrementAndGet(); try { return f() } finally { claiming.decrementAndGet() } }

    @Synchronized private fun update(id: String, f: (Rec) -> Rec): Rec? {
        val list = all(); val old = list.firstOrNull { it.id == id } ?: return null
        val n = f(old); save(list.map { if (it.id == id) n else it }); return n
    }

    /** The record whose "in" direction this agent carries (the hub link here, or a satellite's return agent here). */
    fun byInAgent(agentId: String): Rec? {
        // Read on every link-agent request: the saved records first, matching (and migrating) only when missing.
        if (app.hubLinks.isHub(agentId)) return load().firstOrNull { it.role == Role.SAT && it.ref == agentId } ?: all().firstOrNull { it.role == Role.SAT && it.ref == agentId }
        val s = app.satellites.metas().firstOrNull { it.returnAgent == agentId } ?: return null
        return load().firstOrNull { it.role == Role.HUB && it.ref == s.id } ?: all().firstOrNull { it.role == Role.HUB && it.ref == s.id }
    }

    /** A link agent whose direction is being disconnected or was: its service calls stop (also mid-call). */
    fun inBlocked(agentId: String): Boolean = byInAgent(agentId)?.let { it.inRevoked || it.covers("in") } == true

    /** The owner's pills (LinkPause), by role as on Linked devices: Satellites pauses every satellite link, Hubs every hub link, both ways. */
    fun inPausedAll(r: Rec) = if (r.role == Role.HUB) LinkPause.satellites else LinkPause.hubsPaused
    fun outPausedAll(r: Rec) = inPausedAll(r)

    /** Whether [agentId] is a link agent (any direction, any version). Read on every request: no decrypting. */
    fun isLinkAgent(agentId: String?): Boolean = app.hubLinks.isHub(agentId) || app.satellites.isReturnAgent(agentId)

    fun inAgentId(r: Rec): String? = when (r.role) { Role.SAT -> r.ref; Role.HUB -> app.satellites.meta(r.ref)?.returnAgent }
    fun outAgents(r: Rec): Set<String> = when (r.role) { Role.HUB -> app.satellites.meta(r.ref)?.agents ?: emptySet(); Role.SAT -> r.outAgents }

    /** [pendingCounts]: false for what the other device is told, so a disconnect that may still be undone isn't acted on there. */
    fun inHalf(r: Rec, pendingCounts: Boolean = true) = LinkModel.Half(r.inServices.isNotEmpty() && !r.inRevoked && !(pendingCounts && r.covers("in")),
        inAgentId(r)?.let { app.agents.get(it)?.paused } == true || inPausedAll(r))
    fun outHalf(r: Rec, pendingCounts: Boolean = true) = LinkModel.Half(r.outOptIn && !r.outRevoked && !(pendingCounts && r.covers("out")),
        r.outPaused || outPausedAll(r))
    fun inState(r: Rec) = LinkModel.state(inHalf(r), r.farIn?.half())
    fun outState(r: Rec) = LinkModel.state(r.farOut?.half(), outHalf(r))

    /** The other device's display name: its Device Domain Name, else the name it gave at pairing. */
    fun peerLabel(r: Rec): String = r.peerName ?: when (r.role) {
        Role.HUB -> app.satellites.meta(r.ref)?.name
        Role.SAT -> app.hubLinks.hubs().firstOrNull { it.linkId == r.ref }?.name ?: app.agents.get(r.ref)?.name
    } ?: r.id

    /** This device's own Device Domain Name, else its device name. */
    fun selfName(): String = runCatching { cc.bridgit.app.feedback.DeviceNames.cached(app)?.text("name")?.takeIf { it.isNotBlank() } }.getOrNull()
        ?: cc.bridgit.app.platform.DeviceName.forLinks(app)
    fun registeredName(): String? = runCatching { cc.bridgit.app.feedback.DeviceNames.cached(app)?.text("name")?.takeIf { it.isNotBlank() } }.getOrNull()

    // ---- JSON for the configuration API and the agents' list ------------------------------------

    fun json(r: Rec, now: Long = System.currentTimeMillis()): JSONObject {
        val inAgent = inAgentId(r)?.let { app.agents.get(it) }
        val outA = outAgents(r).mapNotNull { id -> app.agents.get(id)?.let { JSONObject().put("id", it.id).put("name", it.name) } }
        return JSONObject().put("id", r.id).put("name", peerLabel(r)).put("bridgit_name", r.peerName ?: JSONObject.NULL)
            .put("target", targetOf(r)).put("sat_id", if (r.role == Role.HUB) r.ref else JSONObject.NULL)
            .put("role", r.role.wire).put("created_at", r.createdAt)
            .put("in", JSONObject().put("state", inState(r).wire).put("services", LinkModel.json(r.inServices))
                .put("granter", r.inGranter?.toJson() ?: JSONObject.NULL).put("paused", inAgent?.paused == true)
                .put("revoked", r.inRevoked).put("other_side", r.farIn?.toJson() ?: JSONObject.NULL)
                .put("about", "${peerLabel(r)} uses this device"))
            .put("out", JSONObject().put("state", outState(r).wire).put("agents", JSONArray(outA))
                .put("granter", r.outGranter?.toJson() ?: JSONObject.NULL).put("paused", r.outPaused).put("revoked", r.outRevoked)
                .put("services", LinkModel.json(r.farOut?.services ?: emptySet())).put("other_side", r.farOut?.toJson() ?: JSONObject.NULL)
                .put("about", "this device uses ${peerLabel(r)}"))
            .put("disconnecting", r.pending?.takeIf { it.until > now }?.let { JSONObject().put("direction", it.direction).put("undo_until", it.until) } ?: JSONObject.NULL)
    }

    /** What an agent names to run a call there (LNK-46): the Device Domain Name, else the sat: id, else the link id. */
    fun targetOf(r: Rec): String = r.peerName ?: if (r.role == Role.HUB) r.ref else r.id

    /**
     * One linked Bridgit as agent [a] sees it in GET /v1/links (and the /v1/satellites alias). Both ends of a link list
     * it (A0152): "reachable" says whether [a] may target it; only then does the entry carry the other device's
     * manifest (cameras, capabilities, endpoints, battery).
     */
    fun agentEntry(r: Rec, a: Agent): JSONObject {
        val t = targetOf(r)
        val canReach = reachable(r, a)
        val sat = if (r.role == Role.HUB) app.satellites.get(r.ref) else null
        val full = sat?.let { cc.bridgit.app.satellite.SatelliteEndpoints.publicJson(it) }
        val o = if (sat != null) {
            // Not reachable by [a]: who and whether it is up, but not what the other device has.
            if (canReach) full!! else JSONObject().put("id", t).put("name", peerLabel(r)).put("kind", "satellite")
                .put("status", full!!.get("status")).put("last_seen", sat.lastSeen)
                .put(cc.bridgit.app.platform.DeviceType.FIELD, full.get(cc.bridgit.app.platform.DeviceType.FIELD))
        } else {
            val info = app.hubLinks.hubs().firstOrNull { it.linkId == r.ref }
            JSONObject().put("id", t).put("name", peerLabel(r)).put("kind", "linked_bridgit")
                .put("status", if (info == null) "not_checked" else if (System.currentTimeMillis() - (app.agents.get(r.ref)?.lastUsedAt ?: 0) <= SatelliteStore.ONLINE_MS) "online" else "offline")
                .put("last_seen", app.agents.get(r.ref)?.lastUsedAt ?: 0)
                .put(cc.bridgit.app.platform.DeviceType.FIELD, info?.deviceType?.wire ?: JSONObject.NULL)
        }
        val how = if (canReach) "Send header X-Bridgit-Target: $t (or ?target=$t) with an endpoint whose capability is in services. Example: POST /v1/camera/snapshot?target=$t {\"camera\":\"rear\"}"
            else "You may not use ${peerLabel(r)} yet. Ask the owner, or an agent with owner.configuration: POST /v1/owner/configuration/links/${r.id}/directions/out {\"agents\":[\"${a.id}\"]}."
        return o.put("link_id", r.id).put("target", t).put("bridgit_name", r.peerName ?: JSONObject.NULL)
            .put("role", r.role.wire).put("reachable", canReach)
            .put("platform", r.platform ?: "android").put("state", outState(r).wire).put("services", LinkModel.json(r.farOut?.services ?: emptySet()))
            .put("directions", JSONObject()
                .put("in", JSONObject().put("state", inState(r).wire).put("services", LinkModel.json(r.inServices)).put("about", "${peerLabel(r)} uses this device"))
                .put("out", JSONObject().put("state", outState(r).wire).put("services", LinkModel.json(r.farOut?.services ?: emptySet()))
                    .put("about", "this device uses ${peerLabel(r)}")))
            .put("how_to_call", how)
    }

    /** Whether agent [a] may target [r]: it is in the user opt-in and this device's use of it wasn't disconnected. */
    fun reachable(r: Rec, a: Agent): Boolean = a.id in outAgents(r) && !r.outRevoked

    /** The links agent [a] may reach. */
    fun reachableBy(a: Agent): List<Rec> = all().filter { reachable(it, a) }

    /** Every link of this device, either role, one entry each, for GET /v1/links (A0152: a hub lists its satellites too). */
    fun agentList(a: Agent): JSONArray = JSONArray(all().distinctBy { it.id }.map { agentEntry(it, a) })

    // ---- Reaching a target (forwarding, LNK-12, LNK-46) -----------------------------------------

    /** Where and how to send a call that names a linked Bridgit, after this device's own checks. */
    class Reach(val id: String, val name: String, val base: String, val key: String, val lastSeen: Long, val toHub: Boolean,
                val rec: Rec, val stillAllowed: (String) -> Boolean, val seen: (Int) -> Unit, val offline: () -> Unit)

    /** A target that means this device itself: its own name, or the old "hub". */
    fun isSelf(target: String): Boolean = target == "hub" || target.equals(registeredName(), ignoreCase = true)

    fun find(target: String): Rec? {
        val t = target.lowercase()
        val list = all()
        return list.firstOrNull { it.role == Role.HUB && it.ref == t } ?: list.firstOrNull { it.id == t }
            ?: list.firstOrNull { it.peerName?.lowercase() == t }
    }

    /**
     * The checks a call through this device makes before it leaves (LNK-12, LNK-49): the link exists and isn't being
     * disconnected, both devices opted in, the agent is in the user opt-in, nothing is paused. The target then applies
     * its service list and its own switches.
     */
    fun reach(target: String, agent: Agent): Reach {
        val r = find(target) ?: throw ErrorCatalog.error("unknown_target", "No linked Bridgit called $target. GET /v1/links lists the ones you may use; leave target out to use this device.")
        val label = peerLabel(r)
        if (r.outRevoked) throw ErrorCatalog.error("link_revoked", "This device no longer uses $label: the link was disconnected. Linking again needs a new link code.", mapOf("link_id" to r.id))
        if (r.covers("out")) throw ErrorCatalog.error("link_paused", "The owner is disconnecting this device from $label (Undo is possible until ${r.pending!!.until}). Don't retry.", mapOf("link_id" to r.id, "reason" to "disconnecting", "undo_until" to r.pending.until))
        if (agent.id !in outAgents(r)) throw ErrorCatalog.error("target_not_granted", "The owner hasn't let ${agent.name} use $label. Ask them, or an agent with owner.configuration: POST /v1/owner/configuration/links/${r.id}/directions/out {\"agents\":[…]}.", mapOf("link_id" to r.id))
        if (!r.outOptIn) throw ErrorCatalog.error("link_waiting", "This device hasn't opted in to use $label yet: POST /v1/owner/configuration/links/${r.id}/directions/out {\"agents\":[…]}.", mapOf("link_id" to r.id, "waiting_for" to "user"))
        if (r.farOut?.optedIn == false) throw ErrorCatalog.error("link_waiting", "$label hasn't opted in to be used by this device yet. Its own agent or owner sets its service list.", mapOf("link_id" to r.id, "waiting_for" to "target"))
        if (r.outPaused) throw ErrorCatalog.error("link_paused", "The owner paused this device's use of $label. Wait until it is resumed; don't retry before then.", mapOf("link_id" to r.id))
        return when (r.role) {
            Role.HUB -> {
                if (outPausedAll(r)) throw ErrorCatalog.error("forbidden", LinkPause.SATELLITES_MESSAGE, mapOf("reason" to "satellites_paused"))
                val s = app.satellites.get(r.ref) ?: throw ErrorCatalog.error("unknown_target", "No linked Bridgit called $target.")
                Reach(s.id, s.name, s.base, s.key, s.lastSeen, false, r,
                    { id -> load().firstOrNull { it.id == r.id }?.let { !outPausedAll(it) && it.outOptIn && !it.outRevoked && !it.covers("out") && !it.outPaused && id in outAgents(it) } == true },
                    { code -> app.satellites.update(s.id) { it.copy(lastSeen = System.currentTimeMillis(), lastError = if (code == 401) "unlinked" else null) } },
                    { app.satellites.update(s.id) { it.copy(lastError = "offline") } })
            }
            Role.SAT -> {
                if (outPausedAll(r)) throw ErrorCatalog.error("forbidden", if (LinkPause.satellites) LinkPause.SATELLITES_MESSAGE else LinkPause.HUBS_MESSAGE, mapOf("reason" to if (LinkPause.satellites) "satellites_paused" else "hubs_paused"))
                val h = app.hubLinks.hubs().firstOrNull { it.linkId == r.ref }
                    ?: throw ErrorCatalog.error("link_waiting", "$label hasn't told this device how to reach it yet. Retry in a few minutes.", mapOf("link_id" to r.id, "waiting_for" to "target"))
                Reach(targetOf(r), label, h.base, h.key, app.agents.get(r.ref)?.lastUsedAt ?: 0, true, r,
                    { id -> load().firstOrNull { it.id == r.id }?.let { !outPausedAll(it) && it.outOptIn && !it.outRevoked && !it.covers("out") && !it.outPaused && id in it.outAgents } == true },
                    { _ -> }, { })
            }
        }
    }

    /** LNK-30: the other device refused with link_revoked, so this device marks its side off and says so in the log. */
    fun farRevoked(r: Rec, scope: String?, reason: String = "link_revoked from ${peerLabel(r)}") {
        if (scope == "link") { removeLocally(r, Granter(Granter.PERSON, peerLabel(r)), "the other device disconnected the link"); return }
        if (r.outRevoked) return
        update(r.id) { it.copy(outRevoked = true, outOptIn = false, outAgents = emptySet(), farOut = Far(false, at = System.currentTimeMillis())) }
        if (r.role == Role.HUB) app.satellites.update(r.ref) { it.copy(agents = emptySet()) }
        log.add(entry(r, "out", "marked_off", "on", "off", Granter(Granter.PERSON, peerLabel(r)), reason = reason))
        app.log.system("audit", "link", "${peerLabel(r)} disconnected this device's use of it; marked off here")
    }

    // ---- Link codes (LNK-16, LNK-17) --------------------------------------------------------------

    /** [hash]: SHA-256 of the code's claim token (the bearer of POST /v1/link/claim); [offerId]: its offer at the names backend. */
    data class Code(val id: String, val hash: String, val services: Set<String>, val directions: Set<String>, val agents: Set<String>,
                    val createdAt: Long, val expiresAt: Long, val tries: Int = 0, val state: String = "open", val granter: Granter,
                    val offerId: String? = null, val relayAgent: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("hash", hash).put("offer_id", offerId ?: JSONObject.NULL).put("relay_agent", relayAgent ?: JSONObject.NULL).put("services", LinkModel.json(services))
            .put("directions", JSONArray(directions.sorted())).put("agents", JSONArray(agents.sorted())).put("created_at", createdAt)
            .put("expires_at", expiresAt).put("tries", tries).put("state", state).put("granter", granter.toJson())
        fun public(): JSONObject = JSONObject().put("id", id).put("services", LinkModel.json(services)).put("directions", JSONArray(directions.sorted()))
            .put("expires_at", expiresAt).put("tries_left", LinkCode.MAX_TRIES - tries).put("state", state).put("granter", granter.toJson())
        companion object {
            fun fromJson(o: JSONObject): Code? = runCatching {
                fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                Code(o.getString("id"), o.getString("hash"), set("services"), set("directions"), set("agents"), o.getLong("created_at"),
                    o.getLong("expires_at"), o.optInt("tries"), o.optString("state", "open"), Granter.fromJson(o.optJSONObject("granter"))!!,
                    o.optString("offer_id").takeIf { !o.isNull("offer_id") && it.isNotEmpty() },
                    o.optString("relay_agent").takeIf { !o.isNull("relay_agent") && it.isNotEmpty() })
            }.getOrNull()
        }
    }

    @Synchronized fun codes(now: Long = System.currentTimeMillis()): List<Code> {
        val a = runCatching { JSONArray(prefs.getString("codes", "[]")) }.getOrDefault(JSONArray())
        // Closed and expired codes are kept a day, so a late try says "used" or "expired" rather than "wrong".
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Code.fromJson(o) } }.filter { now - it.expiresAt < 24 * 3600_000L }
    }
    @Synchronized private fun saveCodes(list: List<Code>) { check(prefs.edit().putString("codes", JSONArray(list.map { it.toJson() }).toString()).commit()) }
    fun openCodes(now: Long = System.currentTimeMillis()) = codes(now).filter { it.state == "open" && it.expiresAt > now }

    /** The pairing link a QR code holds: the name and the code, plus this device's Tailscale address while names can't resolve one. */
    fun codeLink(code: String, name: String): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val l = app.listener.value
        val host = l.boundTo.firstOrNull { !Net.isIpv6(it) } ?: l.boundTo.firstOrNull()
        val install = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull()
        return LINK_PREFIX + listOfNotNull("v=1", "name=${e(name)}", "code=${LinkCode.format(code)}",
            host?.let { "host=${e(Net.normalise(it))}" }, host?.let { "port=${l.port}" }, install?.let { "install=${e(it)}" }).joinToString("&")
    }

    /**
     * LNK-17: a new one-time code for this device as the target. [directions]: "in" lets the claiming device use this
     * one for [services]; "out" also opts this device in to use the claiming device, with [agents] of its own.
     */
    fun makeCode(services: Set<String>, directions: Set<String>, agents: Set<String>, granter: Granter, now: Long = System.currentTimeMillis()): JSONObject {
        val name = registeredName() ?: throw nameRequired()
        if (directions.isEmpty() || !directions.all { it == "in" || it == "out" }) throw ErrorCatalog.error("invalid_parameter", "directions must be [\"in\"], [\"out\"] or [\"in\",\"out\"].")
        if ("in" in directions && services.isEmpty()) throw ErrorCatalog.error("invalid_parameter", "services is required for the in direction: what the other device may use here (suggested: ${LinkModel.SUGGESTED.joinToString()}).")
        if ("in" !in directions && services.isNotEmpty()) throw ErrorCatalog.error("invalid_parameter", "services only applies to the in direction.")
        if ("out" !in directions && agents.isNotEmpty()) throw ErrorCatalog.error("invalid_parameter", "agents only applies to the out direction.")
        agents.forEach { requireControlAgent(it) }
        if (openCodes(now).size >= 5) throw ErrorCatalog.error("conflict", "Five link codes are already waiting. Cancel one (DELETE /v1/owner/configuration/links/codes/{id}) or let them expire.")
        val code = LinkCode.generate()
        val keys = LinkCode.keys(code, name)
        var c = Code(LinkIds.newCode(), LinkCode.sha256(keys.claimToken), services, directions, agents, now, now + LinkCode.VALID_MS, granter = granter,
            relayAgent = runCatching { claimAgent(LinkCode.sha256(keys.claimToken), now + LinkCode.VALID_MS) }.getOrNull())
        synchronized(this) { saveCodes(codes(now) + c) }
        // LNK-19: the names backend learns only the code's hash and where to reach this device.
        val offer = runCatching { LinkResolver.offer(app, keys.codeHash, c.expiresAt) }
        offer.getOrNull()?.let { id -> c = c.copy(offerId = id); synchronized(this) { saveCodes(codes(now).map { if (it.id == c.id) it.copy(offerId = id) else it }) } }
        log.add(LinkLog.Entry(LinkLog.newId(), now, "-", directions.singleOrNull() ?: "both", "code_made", null,
            JSONObject().put("services", LinkModel.json(services)).put("directions", JSONArray(directions.sorted())), granter, selfName(), c.id, delivered = true))
        app.log.system("audit", "link", "${granter.label()} made a link code (works once, for 10 minutes)")
        return JSONObject().put("code_id", c.id).put("code", LinkCode.format(code)).put("name", name).put("expires_at", c.expiresAt)
            .put("services", LinkModel.json(services)).put("directions", JSONArray(directions.sorted())).put("link", codeLink(code, name))
            .put("name_lookup", if (offer.isSuccess) "ready" else "unavailable: claim with the link (it holds this device's Tailscale address)")
            .put("how", "On the other Bridgit, an agent with owner.configuration calls POST /v1/owner/configuration/links/claims {\"name\":\"$name\",\"code\":\"${LinkCode.format(code)}\"} (or {\"link\":\"…\"}). The code works once, for 10 minutes, and 5 wrong tries void it.")
    }

    /**
     * §4.1: a code's claim-only relay agent, so a Bridgit that has only the relay (an iPhone) can claim it. Its token is
     * the code's claim token, which only a holder of the code can derive; it may call only POST /v1/link/claim, once.
     */
    private fun claimAgent(tokenHash: String, until: Long): String? {
        val store = cc.bridgit.app.relay.RelayStore(app)
        if (!store.enabled) return null
        val (a, _) = app.agents.add("Link code " + tokenHash.take(6), Connection.RELAY, limited = false)
        app.agents.update(a.id) { it.copy(caps = emptySet(), notes = "A link code waiting to be claimed over the relay. Removed once used or expired.") }
        store.addAgentHash(a.id, setOf("POST /v1/link/claim"), tokenHash, until)
        return a.id
    }

    private fun dropClaimAgent(id: String?) {
        id ?: return
        // Off this object's lock: the agent registry calls listeners (forgetAgent) while holding its own.
        kotlin.concurrent.thread(isDaemon = true, name = "link-claim-agent") {
            runCatching { cc.bridgit.app.relay.RelayStore(app).remove(id) }
            app.agents.remove(id)
        }
    }

    fun isClaimAgent(id: String): Boolean = codes().any { it.relayAgent == id }

    /** POST /v1/link/claim arriving over the relay with a code's claim token: (HTTP status, body) for the relay job. */
    fun relayClaim(claimAgentId: String, body: JSONObject, now: Long = System.currentTimeMillis()): Pair<Int, JSONObject> {
        if (LinkPause.hubsPaused) return 403 to JSONObject().put("error", "forbidden").put("message", LinkPause.HUBS_MESSAGE).put("reason", "hubs_paused")
        val c = codes(now).firstOrNull { it.relayAgent == claimAgentId } ?: return 403 to JSONObject().put("error", "code_invalid").put("message", "No such link code.")
        val tokenHash = body.optString("token_hash").takeIf { Regex("[a-f0-9]{64}").matches(it) && it != c.hash }
            ?: return 400 to JSONObject().put("error", "invalid_parameter").put("message", "token_hash must be the SHA-256 hex of a new rly_ token the claiming Bridgit made.")
        synchronized(this) {
            val fresh = codes(now).firstOrNull { it.id == c.id } ?: return 403 to JSONObject().put("error", "code_invalid")
            if (fresh.state != "open") return 410 to JSONObject().put("error", "code_used_up").put("message", "That link code was already used. Make a new one.")
            if (fresh.expiresAt <= now) { dropClaimAgent(claimAgentId); return 410 to JSONObject().put("error", "code_expired").put("message", "That link code expired. Make a new one.") }
            saveCodes(codes(now).map { if (it.id == c.id) it.copy(state = "used") else it })
        }
        c.offerId?.let { o -> kotlin.concurrent.thread(isDaemon = true, name = "link-offer") { runCatching { LinkResolver.deleteOffer(app, o) } } }
        return try {
            200 to acceptClaim(c, "relay", body, relayTokenHash = tokenHash).also { dropClaimAgent(claimAgentId) }
        } catch (e: ApiError) {
            // A refused claim (already linked the other way) doesn't use up the code.
            if (e.code == "conflict") synchronized(this) { saveCodes(codes().map { if (it.id == c.id) it.copy(state = "open") else it }) } else dropClaimAgent(claimAgentId)
            e.http to JSONObject().put("error", e.code).put("message", e.message)
        }
    }

    /** The relay routes of a link agent over the relay: the link's own calls, plus what its service list lets through. */
    fun relayRoutes(services: Set<String>): Set<String> =
        LinkPolicy.PLUMBING.filter { it.startsWith("/link/") }.map { p -> cc.bridgit.app.api.Endpoints.all.first { it.path == p } }.map { "${it.method} ${it.fullPath}" }.toSet() +
        cc.bridgit.app.api.Endpoints.all.filter { LinkPolicy.mayCall(it, services) && !LinkPolicy.isPlumbing(it) }.map { "${it.method} ${it.fullPath}" }

    private fun syncAgent(agentId: String, services: Set<String>) {
        app.agents.update(agentId) { it.copy(caps = LinkModel.capsFor(services)) }
        if (app.agents.get(agentId)?.connection == Connection.RELAY) runCatching { cc.bridgit.app.relay.RelayStore(app).setRoutes(agentId, relayRoutes(services)) }
    }

    fun cancelCode(id: String, granter: Granter): JSONObject {
        val c = synchronized(this) {
            val list = codes(); val c = list.firstOrNull { it.id == id && it.state == "open" } ?: throw ErrorCatalog.error("not_found", "No open link code $id.")
            saveCodes(list.map { if (it.id == id) it.copy(state = "cancelled") else it }); c
        }
        dropClaimAgent(c.relayAgent)
        c.offerId?.let { o -> kotlin.concurrent.thread(isDaemon = true, name = "link-offer") { runCatching { LinkResolver.deleteOffer(app, o) } } }
        log.add(LinkLog.Entry(LinkLog.newId(), System.currentTimeMillis(), "-", null, "code_cancelled", null, null, granter, selfName(), c.id, delivered = true))
        return JSONObject().put("code_id", id).put("state", "cancelled")
    }

    /**
     * Checks the claim token a claiming device derived from the code (its bearer). A wrong one counts against every
     * open code (5 tries each, then void).
     */
    @Synchronized fun redeem(token: String?, now: Long = System.currentTimeMillis()): Code {
        if (token == null || !LinkCode.CLAIM_TOKEN_RE.matches(token)) throw ErrorCatalog.error("code_invalid", "Send the claim token derived from the link code as Authorization: Bearer rly_… (docs: Link two Bridgits).")
        val h = LinkCode.sha256(token)
        val list = codes(now)
        val hit = list.firstOrNull { java.security.MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) }
        if (hit != null && hit.state == "open" && hit.expiresAt > now) {
            saveCodes(list.map { if (it.id == hit.id) it.copy(state = "used") else it })
            dropClaimAgent(hit.relayAgent)
            hit.offerId?.let { o -> kotlin.concurrent.thread(isDaemon = true, name = "link-offer") { runCatching { LinkResolver.deleteOffer(app, o) } } }
            return hit
        }
        if (hit == null) {
            val voided = mutableListOf<Code>()
            saveCodes(list.map { c ->
                if (c.state != "open" || c.expiresAt <= now) c else c.copy(tries = c.tries + 1).let { n -> if (n.tries >= LinkCode.MAX_TRIES) n.copy(state = "void").also { voided += it } else n }
            })
            voided.forEach { v ->
                log.add(LinkLog.Entry(LinkLog.newId(), now, "-", null, "code_voided", null, "5 wrong tries", null, selfName(), v.id, delivered = true))
                // A void code is dead everywhere: its relay claim agent goes and the names backend stops answering for it.
                dropClaimAgent(v.relayAgent)
                v.offerId?.let { o -> kotlin.concurrent.thread(isDaemon = true, name = "link-offer") { runCatching { LinkResolver.deleteOffer(app, o) } } }
            }
            log.add(LinkLog.Entry(LinkLog.newId(), now, "-", null, "code_refused", null, "wrong code", null, selfName(), delivered = true))
            throw ErrorCatalog.error("code_invalid", "Wrong link code. After 5 wrong tries a code stops working.")
        }
        log.add(LinkLog.Entry(LinkLog.newId(), now, "-", null, "code_refused", null, if (hit.state == "open") "expired" else hit.state, null, selfName(), hit.id, delivered = true))
        if (hit.state == "open") throw ErrorCatalog.error("code_expired", "That link code expired (they last 10 minutes). Make a new one on the target device.")
        throw ErrorCatalog.error("code_used_up", "That link code was already used, cancelled or voided after 5 wrong tries. Make a new one on the target device.")
    }

    // ---- The target's side of a claim: POST /v1/link/claim -----------------------------------------

    /**
     * Another Bridgit claims this device's [code] from [source] (its Tailscale address). Makes (or, for a device
     * already linked, re-keys) the link agent for "it uses this device" with the code's services, and returns its key.
     * The claim itself is the other device's user opt-in; its agent is recorded as the granter on both devices.
     */
    fun acceptClaim(code: Code, source: String, body: JSONObject, now: Long = System.currentTimeMillis(), relayTokenHash: String? = null): JSONObject =
        whileClaiming { acceptClaimNow(code, source, body, now, relayTokenHash) }

    private fun acceptClaimNow(code: Code, source: String, body: JSONObject, now: Long, relayTokenHash: String?): JSONObject {
        val peerName = body.text("bridgit_name").let { cc.bridgit.app.feedback.DeviceNames.parse(it) }
        val install = body.text("install").takeIf { it.startsWith("bgi_") && it.length <= 40 }
        val display = body.text("name").filterNot { it.isISOControl() }.trim().take(60).ifBlank { peerName ?: "Bridgit" }
        val claimer = Granter.fromJson(body.optJSONObject("granter"))?.copy(device = peerName ?: display) ?: Granter.person(peerName ?: display)
        val match = all().firstOrNull { (install != null && it.peerInstall == install) || (peerName != null && it.peerName == peerName) }
        if (match != null && match.role == Role.HUB)
            throw ErrorCatalog.error("conflict", "These two Bridgits are already linked the other way round. Change the link's directions on either device instead (POST /v1/owner/configuration/links/${match.id}/directions/{in|out}).")
        // LNK-10: the same device keeps its link id. A claim's name and install are only the claimer's word, so a link is
        // reused (and re-keyed) only for the same transport and, over Tailscale, the same address its key is locked to.
        val oldAgent = match?.ref?.let { app.agents.get(it) }
        val existing = match?.takeIf { oldAgent != null && if (relayTokenHash != null) oldAgent.connection == Connection.RELAY
            else oldAgent.connection != Connection.RELAY && Net.normalise(source) in oldAgent.boundIps }
        val services = if ("in" in code.directions) code.services else emptySet()
        val caps = LinkModel.capsFor(services)
        val (agentId, key, agentName) = if (relayTokenHash != null) {
            if (existing != null) { cc.bridgit.app.relay.RelayStore(app).addAgentHash(existing.ref, relayRoutes(services), relayTokenHash); syncAgent(existing.ref, services); Triple(existing.ref, null, oldAgent!!.name) }
            else app.hubLinks.claimRelayLink(display, caps, relayRoutes(services), relayTokenHash).let { (id, n) -> Triple(id, null, n) }
        } else if (existing != null) {
            val (a, k) = app.agents.rotate(existing.ref)
            app.agents.update(a.id) { it.copy(caps = caps, boundIps = listOf(Net.normalise(source)), paused = false) }
            Triple(a.id, k, a.name)
        } else app.hubLinks.claimLink(source, display, caps)
        val id = existing?.id ?: LinkIds.newLink()
        val r = Rec(id, Role.SAT, agentId, existing?.createdAt ?: now, peerName, install,
            inServices = LinkModel.servicesOf(caps).let { if ("in" in code.directions) it else emptySet() }, inGranter = code.granter,
            outOptIn = "out" in code.directions, outGranter = if ("out" in code.directions) code.granter else null, outAgents = code.agents,
            farIn = Far(true, at = now), farOut = existing?.farOut,
            platform = body.optString("platform").takeIf { it in PLATFORMS })
        synchronized(this) { save(load().filter { it.id != id && !(it.role == Role.SAT && it.ref == agentId) } + r) }
        log.add(entry(r, null, "code_claimed", null, JSONObject().put("by", peerName ?: display), claimer, codeId = code.id))
        if ("in" in code.directions) log.add(entry(r, "in", "opt_in", null, LinkModel.json(r.inServices), code.granter, codeId = code.id))
        log.add(entry(r, "in", "opt_in_far", null, "claimed", claimer, codeId = code.id, reason = "the claim is ${peerName ?: display}'s user opt-in"))
        if ("out" in code.directions) log.add(entry(r, "out", "opt_in", null, JSONObject().put("agents", JSONArray(code.agents.sorted())), code.granter, codeId = code.id))
        app.log.system("audit", "link", "${claimer.label()} linked ${peerName ?: display} to this device (${LinkModel.words(r.inServices)})")
        return JSONObject().put("link_id", id).put("key", key ?: JSONObject.NULL).put("agent", agentName).put("name", app.hubLinks.name())
            .put("bridgit_name", registeredName() ?: JSONObject.NULL)
            .put("install", runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull() ?: JSONObject.NULL)
            .put("services", LinkModel.json(r.inServices)).put("directions", JSONArray(code.directions.sorted()))
            .put("events", JSONArray(log.undelivered(id).map { it.toJson() }))
    }

    // ---- The user's side of a claim: POST /v1/owner/configuration/links/claims ----------------------

    /** [host]/[port]: the target's Tailscale address when known (from its link); null = ask the names backend. */
    data class ClaimTarget(val name: String?, val code: String, val host: String? = null, val port: Int? = null, val install: String? = null)

    /** Parses a bridgit://link# link (LNK-15's QR code). */
    fun parseLink(link: String): ClaimTarget {
        val t = link.trim()
        if (!t.startsWith(LINK_PREFIX)) throw ErrorCatalog.error("invalid_parameter", "link must start with $LINK_PREFIX")
        val f = t.removePrefix(LINK_PREFIX).split('&').mapNotNull { p -> val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i) to java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8") }.toMap()
        if (f["v"] != "1") throw ErrorCatalog.error("invalid_parameter", "This link is from a newer Bridgit. Update Bridgit here and try again.")
        val code = f["code"] ?: throw ErrorCatalog.error("invalid_parameter", "The link has no code.")
        // A link from a device without Tailscale has no address: the names backend then resolves it.
        val host = f["host"]?.let { Net.normalise(it) }?.takeIf { Net.isTailnet(it) }
        val port = f["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
        return ClaimTarget(f["name"], code, host?.takeIf { port != null }, port?.takeIf { host != null }, f["install"]?.takeIf { it.startsWith("bgi_") })
    }

    /**
     * Claims the target's code. Finding it: [ClaimTarget.host] when given (a link, or the owner's Tailscale address),
     * else the names backend resolves the name with a proof of the code (LNK-19). [agents]: this device's agents that
     * may use the target (default: the agent claiming). [reverseServices]: what the target may use here in return.
     */
    fun claim(t: ClaimTarget, agents: Set<String>, reverseServices: Set<String>, granter: Granter): JSONObject =
        whileClaiming { claimNow(t, agents, reverseServices, granter) }

    private fun claimNow(t: ClaimTarget, agents: Set<String>, reverseServices: Set<String>, granter: Granter): JSONObject {
        registeredName() ?: throw nameRequired()
        if (LinkPause.satellites) throw ErrorCatalog.error("forbidden", LinkPause.SATELLITES_MESSAGE, mapOf("reason" to "satellites_paused"))
        val code = LinkCode.normalise(t.code) ?: throw ErrorCatalog.error("code_invalid", "That isn't a link code (8 letters and digits, like K7QX-3MPA).")
        val targetName = t.name?.let { cc.bridgit.app.feedback.DeviceNames.parse(it) } ?: throw ErrorCatalog.error("invalid_parameter", "name is required: the other Bridgit's Device Domain Name.")
        agents.forEach { requireControlAgent(it) }
        val own = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull()
        if (targetName.equals(registeredName(), ignoreCase = true) || (t.install != null && t.install == own) ||
            (t.host != null && t.host in app.listener.value.boundTo.map { Net.normalise(it) }))
            throw ErrorCatalog.error("invalid_parameter", "That code is from this device. Claim it on the other Bridgit.")
        val keys = LinkCode.keys(code, targetName)
        // LNK-19: without an address from a link, the names backend gives it for a proof of the code.
        val (host, port) = if (t.host != null && t.port != null) t.host to t.port
            else LinkResolver.resolve(app, targetName, keys.proof(targetName, own ?: throw ErrorCatalog.error("team_unavailable", "This device couldn't prove who it is to the names backend. Retry.")))
        val body = JSONObject().put("name", cc.bridgit.app.platform.DeviceName.forLinks(app))
            .put("bridgit_name", registeredName()).put("install", own ?: JSONObject.NULL).put("granter", granter.toJson())
        val base = SatelliteClient.base(host, port)
        val o = try { SatelliteClient.linkClaim(base, keys.claimToken, body) } catch (e: SatelliteClient.Failure) {
            throw ErrorCatalog.error("target_offline", "$targetName didn't answer at $host. Check that Tailscale and Bridgit are on there, then retry with the same code.", mapOf("retry_after_s" to 30))
        }
        val key = o.optString("key").takeIf { it.startsWith("brg_") } ?: throw ErrorCatalog.error("upstream_failed", "The other Bridgit didn't send a key.")
        val linkId = o.optString("link_id").takeIf { LinkIds.LINK_RE.matches(it) } ?: LinkIds.newLink()
        val peerName = cc.bridgit.app.feedback.DeviceNames.parse(o.text("bridgit_name")) ?: targetName
        val p = SatelliteLink.Parsed(host, port, "", o.text("name").ifBlank { peerName ?: "Bridgit" }, o.text("install").takeIf { it.startsWith("bgi_") } ?: t.install)
        val manifest = runCatching { SatelliteClient.manifest(base, key, cc.bridgit.app.platform.DeviceName.forLinks(app)) }.getOrNull()
        val s = try { app.satellites.add(p, key, manifest) } catch (e: Exception) { runCatching { SatelliteClient.unlink(base, key) }; throw e }
        val now = System.currentTimeMillis()
        val services = runCatching { LinkModel.parseServices(o.optJSONArray("services")) }.getOrDefault(emptySet())
        val directions = o.optJSONArray("directions")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: setOf("in")
        app.satellites.update(s.id) { it.copy(agents = agents) }
        val old = all().firstOrNull { it.role == Role.HUB && it.ref == s.id }
        val r = Rec(linkId, Role.HUB, s.id, old?.createdAt ?: now, peerName, p.install,
            inServices = reverseServices, inGranter = if (reverseServices.isNotEmpty()) granter else null,
            outOptIn = true, outGranter = granter,
            farIn = Far("out" in directions, at = now), farOut = Far("in" in directions && services.isNotEmpty(), services = services, at = now))
        synchronized(this) { save(load().filter { it.id != linkId && !(it.role == Role.HUB && it.ref == s.id) } + r) }
        o.optJSONArray("events")?.let { receiveEvents(r, it) }
        log.add(entry(r, "out", "opt_in", null, JSONObject().put("agents", JSONArray(agents.sorted())), granter, reason = "claimed a link code"))
        if (reverseServices.isNotEmpty()) log.add(entry(r, "in", "opt_in", null, LinkModel.json(reverseServices), granter))
        app.log.system("audit", "link", "${granter.label()} linked this device to ${peerName ?: s.name} (${LinkModel.words(services)})")
        SatelliteHub.pushHubInfo(app, s.id)
        flushSoon(linkId)
        return json(get(linkId) ?: r)
    }

    // ---- Changing this device's half (LNK-45) ------------------------------------------------------

    /** POST …/links/{id}/directions/{in|out}: giving or widening; the endpoint requires owner.configuration. */
    fun setDirection(id: String, direction: String, services: Set<String>?, agents: Set<String>?, paused: Boolean?, granter: Granter): JSONObject {
        val r = get(id) ?: throw notFound(id)
        when (direction) {
            "in" -> {
                if (agents != null) throw ErrorCatalog.error("invalid_parameter", "agents belongs to the out direction (this device's agents that may use the other one).")
                if (services != null) {
                    if (r.inRevoked || r.covers("in")) throw ErrorCatalog.error("link_revoked", "This direction was disconnected. Linking again needs a new link code.")
                    val before = r.inServices
                    setInServices(r, services)
                    log.add(entry(r, "in", if (before.isEmpty()) "opt_in" else "services_changed", LinkModel.json(before), LinkModel.json(services), granter))
                }
                if (paused != null) {
                    val agent = inAgentId(r) ?: throw ErrorCatalog.error("link_waiting", "The other device hasn't set this link up fully yet.")
                    if (app.agents.get(agent)?.paused != paused) {
                        app.agents.update(agent) { it.copy(paused = paused) }
                        log.add(entry(r, "in", if (paused) "paused" else "resumed", !paused, paused, granter))
                    }
                }
            }
            "out" -> {
                if (services != null) throw ErrorCatalog.error("invalid_parameter", "services belongs to the in direction (set on the device being used).")
                if ((r.outRevoked || r.covers("out")) && agents != null) throw ErrorCatalog.error("link_revoked", "This direction was disconnected. Linking again needs a new link code.")
                if (agents != null) {
                    agents.forEach { requireControlAgent(it) }
                    val before = outAgents(r)
                    when (r.role) { Role.HUB -> app.satellites.update(r.ref) { it.copy(agents = agents) }; Role.SAT -> {} }
                    update(r.id) { it.copy(outAgents = if (it.role == Role.SAT) agents else it.outAgents, outOptIn = true, outGranter = if (!it.outOptIn) granter else it.outGranter) }
                    log.add(entry(r, "out", if (!r.outOptIn) "opt_in" else "agents_changed", JSONArray(before.sorted()), JSONArray(agents.sorted()), granter))
                }
                if (paused != null && paused != r.outPaused) {
                    update(r.id) { it.copy(outPaused = paused) }
                    log.add(entry(r, "out", if (paused) "paused" else "resumed", !paused, paused, granter))
                }
            }
            else -> throw ErrorCatalog.error("invalid_parameter", "direction must be in or out.")
        }
        flushSoon(r.id)
        return json(get(r.id) ?: r)
    }

    private fun setInServices(r: Rec, services: Set<String>) {
        update(r.id) { it.copy(inServices = services, inGranter = it.inGranter) }
        inAgentId(r)?.let { syncAgent(it, services) }
    }

    /** The grants a hub's return agent for satellite [satId] should have (SatelliteHub.pushHubInfo); null before a record exists. */
    fun returnCaps(satId: String): Set<String>? = all().firstOrNull { it.role == Role.HUB && it.ref == satId }?.let {
        if (it.inRevoked) emptySet() else LinkModel.capsFor(it.inServices) }

    // ---- Disconnecting (LNK-26..31) ----------------------------------------------------------------

    /**
     * Cuts access at once: the direction's calls are refused from now, and it becomes final after [UNDO_MS] unless
     * [undo] runs first (LNK-26). [pause] only pauses instead (LNK-27). Works with the other device offline: it learns
     * on its next contact, or its next call is refused with link_revoked (LNK-30).
     */
    fun disconnect(id: String, direction: String, granter: Granter, pause: Boolean = false, now: Long = System.currentTimeMillis()): JSONObject {
        val r = get(id) ?: throw notFound(id)
        if (direction !in setOf("in", "out", "both")) throw ErrorCatalog.error("invalid_parameter", "direction must be in, out or both.")
        if (pause) {
            if (direction != "out") inAgentId(r)?.let { a -> if (app.agents.get(a)?.paused == false) { app.agents.update(a) { it.copy(paused = true) }; log.add(entry(r, "in", "paused", false, true, granter)) } }
            if (direction != "in" && !r.outPaused) { update(r.id) { it.copy(outPaused = true) }; log.add(entry(r, "out", "paused", false, true, granter)) }
            flushSoon(r.id)
            return json(get(r.id) ?: r)
        }
        // A disconnect already due becomes final first; one still waiting is widened to cover this one too.
        if (r.pending != null && r.pending.until <= now) { finishDue(now); return get(id)?.let { disconnect(it.id, direction, granter, false, now) }
            ?: JSONObject().put("id", id).put("removed", true) }
        val merged = r.pending?.let { if (it.direction == direction) direction else "both" } ?: direction
        r.pending?.eventId?.let { log.markDelivered(setOf(it)) } // replaced by the merged event below, never sent on its own
        val e = log.add(entry(r, merged, "disconnected", "on", "off", granter))
        val n = update(r.id) { it.copy(pending = Pending(merged, now + UNDO_MS, granter, e.id)) } ?: r
        app.log.system("audit", "link", "${granter.label()} disconnected ${if (merged == "both") "the link with" else "one direction of the link with"} ${peerLabel(r)}")
        scheduleFinal(r.id, UNDO_MS)
        return json(n).put("undo_until", now + UNDO_MS)
    }

    fun disconnectAll(granter: Granter): JSONObject {
        val done = all().map { runCatching { disconnect(it.id, "both", granter) }.isSuccess }
        return JSONObject().put("disconnected", done.count { it }).put("undo_until", System.currentTimeMillis() + UNDO_MS)
    }

    /** Restores a disconnect within [UNDO_MS]. Afterwards linking again needs a new code. Giving back access, so owner.configuration or the person. */
    fun undo(id: String, granter: Granter, now: Long = System.currentTimeMillis()): JSONObject {
        val r = get(id) ?: throw notFound(id)
        val p = r.pending?.takeIf { it.until > now } ?: throw ErrorCatalog.error("conflict", "Nothing to undo: Undo works for ${UNDO_MS / 1000} seconds after a disconnect. Linking again needs a new link code.")
        update(r.id) { it.copy(pending = null) }
        // The disconnect never left this device (held back while Undo can run), so the other device is told nothing of it.
        p.eventId?.let { log.markDelivered(setOf(it)) }
        log.add(entry(r, p.direction, "undone", "off", "on", granter, delivered = true))
        app.log.system("audit", "link", "${granter.label()} undid the disconnect from ${peerLabel(r)}")
        flushSoon(r.id) // its current halves, in case it saw the pause meanwhile
        return json(get(r.id) ?: r)
    }

    private fun scheduleFinal(id: String, delayMs: Long) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ kotlin.concurrent.thread(isDaemon = true, name = "link-final") { runCatching { finishDue() } } }, delayMs + 200)
    }

    /** Makes every disconnect whose Undo time has passed final. Also run from the service's periodic check and at start. */
    fun finishDue(now: Long = System.currentTimeMillis()) {
        if (!finishing.compareAndSet(false, true)) return
        try { finishDueNow(now) } finally { finishing.set(false) }
    }
    private val finishing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun finishDueNow(now: Long) {
        for (r in all()) {
            val p = r.pending ?: continue
            if (p.until > now) continue
            when (p.direction) {
                "in" -> { update(r.id) { it.copy(pending = null, inRevoked = true, inServices = emptySet()) }
                    inAgentId(r)?.let { syncAgent(it, emptySet()) } }
                "out" -> { update(r.id) { it.copy(pending = null, outRevoked = true, outOptIn = false, outAgents = emptySet()) }
                    if (r.role == Role.HUB) app.satellites.update(r.ref) { it.copy(agents = emptySet()) } }
                else -> {
                    // Revoked in the same step that clears the pending mark, so nothing works again while the notice goes out.
                    update(r.id) { it.copy(pending = null, inRevoked = true, inServices = emptySet(), outRevoked = true, outOptIn = false, outAgents = emptySet()) }
                    inAgentId(r)?.let { syncAgent(it, emptySet()) }
                    flush(r.id, readS = 5)
                    removeLocally(r, p.granter, null)
                    continue
                }
            }
            flushSoon(r.id)
        }
    }

    /**
     * Removes the whole link here: the link agent (its key now answers link_revoked) and the key to the other device.
     * A notice the other device hasn't received yet is kept and sent when it can be reached (LNK-30).
     */
    private fun removeLocally(r: Rec, granter: Granter, why: String?) {
        val undelivered = log.undelivered(r.id)
        if (undelivered.isNotEmpty()) channel(r)?.let { (base, key, toHub) -> notices.add(Notice(r.id, peerLabel(r), base, key, toHub, System.currentTimeMillis())) }
        when (r.role) {
            Role.HUB -> { app.satellites.get(r.ref)?.returnAgent?.let { app.agents.remove(it, REVOKED_REASON) }; SatelliteHub.remove(app, r.ref, tellSatellite = false) }
            Role.SAT -> { app.agents.remove(r.ref, REVOKED_REASON); app.hubLinks.unlink(r.ref, tellHub = false) }
        }
        synchronized(this) { save(load().filter { it.id != r.id }) }
        if (why != null) log.add(entry(r, "both", "removed", "on", "off", granter, reason = why, delivered = true))
    }

    // ---- Keeping both logs and both views in step (LNK-23) ----------------------------------------

    /** How this device reaches the other one for link calls: (base, key, whether that is "to a hub"). */
    private fun channel(r: Rec): Triple<String, String, Boolean>? = when (r.role) {
        Role.HUB -> app.satellites.get(r.ref)?.let { Triple(it.base, it.key, false) }
        Role.SAT -> app.hubLinks.hubs().firstOrNull { it.linkId == r.ref }?.let { Triple(it.base, it.key, true) }
    }

    /** This device's halves, as the other device should see them. */
    fun stateJson(r: Rec): JSONObject {
        // A disconnect that can still be undone isn't reported: the other device would act on it.
        val i = inHalf(r, pendingCounts = false); val o = outHalf(r, pendingCounts = false)
        return JSONObject().put("in", JSONObject().put("opted_in", i.optedIn).put("paused", i.paused).put("services", LinkModel.json(if (i.optedIn) r.inServices else emptySet())).put("revoked", r.inRevoked))
            .put("out", JSONObject().put("opted_in", o.optedIn).put("paused", o.paused).put("revoked", r.outRevoked))
    }

    /** The link block a hub sends in hub-info and a satellite sends in its manifest. */
    fun linkBlock(r: Rec?): JSONObject? = r?.let { JSONObject().put("link_id", it.id).put("bridgit_name", registeredName() ?: JSONObject.NULL).put("state", stateJson(it)) }

    fun flushSoon(id: String) { kotlin.concurrent.thread(isDaemon = true, name = "link-events") { runCatching { flush(id) } } }

    /** Blocking. Sends this device's undelivered events and current halves for link [id]. True when the other device took them. */
    fun flush(id: String, readS: Long = 10): Boolean {
        val r = get(id) ?: return false
        val (base, key, toHub) = channel(r) ?: return false
        // A disconnect is held back while it can still be undone.
        val events = log.undelivered(r.id).filter { it.id != r.pending?.eventId }
        val body = JSONObject().put("link_id", r.id).put("bridgit_name", registeredName() ?: JSONObject.NULL)
            .put("events", JSONArray(events.map { it.toJson().put("late", System.currentTimeMillis() - it.at > LATE_MS) })).put("state", stateJson(r))
            .put("ack", JSONArray(log.reportedIds(r.id, 200)))
        return try {
            val o = SatelliteClient.call(base, key, "POST", "/v1/link/events", body, toHub, readS)
            log.markDelivered(events.map { it.id }.toSet())
            o.optJSONArray("events")?.let { receiveEvents(get(r.id) ?: r, it) }
            o.optJSONObject("state")?.let { applyFar(r.id, it) }
            true
        } catch (e: SatelliteClient.Failure) {
            if (e.code == "link_revoked") farRevoked(r, e.scope)
            false
        } catch (e: Exception) { false }
    }

    /** POST /v1/link/events from the other device of [r]: its events for this log, and its halves. */
    fun receive(r: Rec, body: JSONObject): JSONObject {
        val sentId = body.optString("link_id")
        var rec = r
        if (r.provisional && LinkIds.LINK_RE.matches(sentId) && sentId != r.id) rec = adoptId(r, sentId)
        cc.bridgit.app.feedback.DeviceNames.parse(body.text("bridgit_name"))?.let { n -> if (n != rec.peerName) rec = update(rec.id) { it.copy(peerName = n) } ?: rec }
        body.optJSONArray("events")?.let { receiveEvents(rec, it) }
        body.optJSONObject("state")?.let { applyFar(rec.id, it) }
        // The other device lists the entries of this one it already stored: only those count as delivered.
        body.optJSONArray("ack")?.let { a -> log.markDelivered((0 until minOf(a.length(), 500)).map { a.optString(it) }.toSet()) }
        // This device's own events go back in the reply, so a Bridgit this one can't call (an iPhone over the relay) gets them too.
        val cur = get(rec.id) ?: rec
        val mine = log.undelivered(cur.id).filter { it.id != cur.pending?.eventId }
        return JSONObject().put("ok", true).put("link_id", cur.id).put("state", stateJson(cur))
            .put("events", JSONArray(mine.map { it.toJson().put("late", System.currentTimeMillis() - it.at > LATE_MS) }))
    }

    /** Stores the other device's events and acts on its disconnects. Its "in" is this device's "out", and the reverse. */
    fun receiveEvents(r: Rec, a: JSONArray) {
        val from = peerLabel(r)
        val events = (0 until minOf(a.length(), 200)).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            runCatching { LinkLog.Entry.fromJson(o.put("link_id", r.id)) }.getOrNull() } }
            .map { e -> e.copy(direction = when (e.direction) { "in" -> "out"; "out" -> "in"; else -> e.direction }) }
        log.receive(from, events)
        for (e in events.filter { it.event == "disconnected" }) when (e.direction) {
            "out" -> farRevoked(get(r.id) ?: continue, null)
            "in" -> get(r.id)?.let { cur -> markInOff(cur, e.granter, "$from stopped using this device") }
            else -> get(r.id)?.let { cur -> kotlin.concurrent.thread(isDaemon = true, name = "link-remove") {
                Thread.sleep(1500); runCatching { removeLocally(cur, e.granter ?: Granter.person(from), "$from disconnected the link") } } }
        }
    }

    /** The other device's halves: its "in" is this device's "out" target half, and its "out" this device's "in" user half. */
    fun applyFar(id: String, state: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        val i = state.optJSONObject("in"); val o = state.optJSONObject("out")
        val before = get(id) ?: return false
        val after = update(id) { r -> r.copy(
            farOut = i?.let { Far(it.optBoolean("opted_in"), it.optBoolean("paused"), runCatching { LinkModel.parseServices(it.optJSONArray("services")) }.getOrDefault(emptySet()), now) } ?: r.farOut,
            farIn = o?.let { Far(it.optBoolean("opted_in"), it.optBoolean("paused"), at = now) } ?: r.farIn) } ?: return false
        // LNK-30: a direction the other device disconnected for good is marked off here too, even before its log entry arrives.
        if (i?.optBoolean("revoked") == true) farRevoked(after, null, "${peerLabel(after)} disconnected this device's use of it")
        if (o?.optBoolean("revoked") == true) markInOff(get(id) ?: after, null, "${peerLabel(after)} stopped using this device")
        return before.farOut?.copy(at = 0) != after.farOut?.copy(at = 0) || before.farIn?.copy(at = 0) != after.farIn?.copy(at = 0)
    }

    /** The other device stopped using this one for good: its link agent loses every grant here. */
    private fun markInOff(r: Rec, granter: Granter?, reason: String) {
        if (r.inRevoked) return
        update(r.id) { it.copy(inRevoked = true, inServices = emptySet()) }
        inAgentId(r)?.let { syncAgent(it, emptySet()) }
        log.add(entry(r, "in", "marked_off", "on", "off", granter ?: Granter(Granter.PERSON, peerLabel(r)), reason = reason, delivered = true))
    }

    /** A satellite's manifest, read by this hub: the other device's name, the shared link id and its halves. */
    fun noteManifest(satId: String, m: JSONObject) {
        val r = all().firstOrNull { it.role == Role.HUB && it.ref == satId } ?: return
        val name = cc.bridgit.app.feedback.DeviceNames.parse(m.text("bridgit_name"))
        if (name != null && name != r.peerName) update(r.id) { it.copy(peerName = name) }
        // A manifest carries halves but no log entries: a change means the other device has news, so fetch it.
        if (m.optJSONObject("link")?.optJSONObject("state")?.let { applyFar(r.id, it) } == true) flushSoon(r.id)
    }

    /** POST /v1/satellite/hub-info on this satellite: adopts the hub's link id and name, and its halves. */
    fun noteHubInfo(agentId: String, body: JSONObject) {
        var r = all().firstOrNull { it.role == Role.SAT && it.ref == agentId } ?: return
        val link = body.optJSONObject("link")
        val sent = link?.optString("link_id")?.takeIf { LinkIds.LINK_RE.matches(it) }
        if (sent != null && sent != r.id && r.provisional) r = adoptId(r, sent)
        val name = cc.bridgit.app.feedback.DeviceNames.parse(link?.text("bridgit_name") ?: body.text("bridgit_name"))
        if (name != null && name != r.peerName) r = update(r.id) { it.copy(peerName = name) } ?: r
        val install = body.text("install").takeIf { it.startsWith("bgi_") }
        if (install != null && install != r.peerInstall) r = update(r.id) { it.copy(peerInstall = install) } ?: r
        if (link?.optJSONObject("state")?.let { applyFar(r.id, it) } == true) flushSoon(r.id)
        // A migrated satellite's out direction is the hub's view: on once the hub sends how to reach it.
        if (!r.outOptIn && !r.outRevoked && r.inGranter?.kind == Granter.MIGRATED) update(r.id) { it.copy(outOptIn = true, outGranter = it.inGranter) }
    }

    @Synchronized private fun adoptId(r: Rec, id: String): Rec {
        val list = load()
        if (list.any { it.id == id && !(it.role == r.role && it.ref == r.ref) }) return r
        val n = r.copy(id = id, provisional = false)
        save(list.map { if (it.role == r.role && it.ref == r.ref) n else it })
        log.rename(r.id, id)
        return n
    }

    /** From the service's periodic check: final disconnects, undelivered events, notices for removed links. */
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        finishDue(now)
        if (now - lastFlush < 5 * 60_000L) return
        lastFlush = now
        all().filter { log.undelivered(it.id).isNotEmpty() }.forEach { runCatching { flush(it.id) } }
        notices.retry(now)
    }
    @Volatile private var lastFlush = 0L

    /** LNK-31: a removed agent loses its access to every link. */
    @Synchronized fun forgetAgent(agentId: String) {
        val list = load()
        if (list.any { agentId in it.outAgents }) save(list.map { it.copy(outAgents = it.outAgents - agentId) })
    }

    // ---- Notices for links already removed here --------------------------------------------------

    data class Notice(val linkId: String, val peer: String, val base: String, val key: String, val toHub: Boolean, val at: Long)

    private val notices = Notices()
    private inner class Notices {
        private val p = app.getSharedPreferences("link_notices_v1", Context.MODE_PRIVATE)
        @Synchronized fun add(n: Notice) {
            val a = runCatching { JSONArray(p.getString("n", "[]")) }.getOrDefault(JSONArray())
            a.put(JSONObject().put("link_id", n.linkId).put("peer", n.peer).put("base", n.base).put("key", SatelliteStore.seal(n.key))
                .put("to_hub", n.toHub).put("at", n.at).put("events", JSONArray(log.undelivered(n.linkId).map { it.toJson() })))
            p.edit().putString("n", a.toString()).commit()
        }
        @Synchronized fun retry(now: Long) {
            val a = runCatching { JSONArray(p.getString("n", "[]")) }.getOrDefault(JSONArray())
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (now - o.optLong("at") > 7 * 86_400_000L) continue
                val key = o.optJSONObject("key")?.let { SatelliteStore.open(it) } ?: continue
                val events = o.optJSONArray("events") ?: JSONArray()
                for (j in 0 until events.length()) events.optJSONObject(j)?.put("late", true)
                val sent = runCatching { SatelliteClient.call(o.getString("base"), key, "POST", "/v1/link/events",
                    JSONObject().put("link_id", o.getString("link_id")).put("events", events), o.optBoolean("to_hub"), 8) }
                // Delivered, or the other device no longer knows this key (it removed the link too): done either way.
                if (sent.isFailure && !(sent.exceptionOrNull().let { it is SatelliteClient.Failure && it.status in setOf(401, 403) })) keep.put(o)
            }
            p.edit().putString("n", keep.toString()).commit()
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    fun entry(r: Rec, direction: String?, event: String, before: Any?, after: Any?, granter: Granter?, codeId: String? = null,
              reason: String? = null, delivered: Boolean = false): LinkLog.Entry =
        LinkLog.Entry(LinkLog.newId(), System.currentTimeMillis(), r.id, direction, event, before,
            if (reason != null) JSONObject().put("value", after ?: JSONObject.NULL).put("reason", reason) else after,
            granter, selfName(), codeId, delivered = delivered)

    /** A control agent of this device: not a link agent, applet or portal (they never get link access). */
    fun requireControlAgent(id: String) {
        val a = app.agents.get(id) ?: throw ErrorCatalog.error("invalid_parameter", "No agent $id on this device.")
        if (!isControlAgent(a)) throw ErrorCatalog.error("invalid_parameter", "${a.name} isn't a control agent of this device.")
    }

    fun isControlAgent(a: Agent): Boolean = a.connection != Connection.APPLET && !isLinkAgent(a.id) && app.applets.forAgent(a.id) == null &&
        runCatching { cc.bridgit.app.portal.PortalPairing.granted(app, cc.bridgit.app.relay.RelayStore(app)).none { it.id == a.id } }.getOrDefault(true)

    private fun notFound(id: String) = ErrorCatalog.error("not_found", "No link $id. GET /v1/owner/configuration/links lists them.")
    private fun nameRequired() = ErrorCatalog.error("name_required", "This Bridgit needs a Device Domain Name before it can link. ${cc.bridgit.app.feedback.DeviceNames.ADVICE} Then: ${cc.bridgit.app.feedback.DeviceNames.OPERATION} {\"name\":\"kitchen-tab\"}.")

    companion object {
        const val UNDO_MS = 10_000L
        val PLATFORMS = setOf("android", "ios", "ipados", "macos")
        const val LATE_MS = 2 * 60_000L
        const val LINK_PREFIX = "bridgit://link#"
        /** The tombstone reason of a link agent removed by a disconnect: its key then answers link_revoked. */
        const val REVOKED_REASON = "link_disconnected"
        val changes = MutableStateFlow(0)
    }
}

/** A text field, with JSON null (an unnamed Bridgit sends `"bridgit_name": null`) read as empty, not as "null". */
internal fun org.json.JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
