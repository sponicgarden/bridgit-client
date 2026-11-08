package cc.bridgit.app.satellite

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.Net
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone as a satellite: which of its agents are hub links (docs/spec-satellites.md, SAT-08). A hub link is an
 * ordinary direct agent with the phase-1 grant set, so every normal check (switches, grants, pause, device lock,
 * limits, log) still applies; this list only marks it so the satellite can show it, honour On-Behalf-Of from it,
 * and serve it the manifest. Unlinking removes the agent, so its key stops working at once.
 *
 * Pairing: [make] shows a link with a one-time claim token (only its hash is kept, 15 minutes). The hub sends it to
 * POST /v1/satellite/claim; [claim] then makes the hub-link agent, locks its key to the hub's Tailscale address and
 * hands the key over once. The key itself is never in the QR code.
 */
class HubLinks(private val app: BridgeApp) {
    private val prefs = app.getSharedPreferences("hub_links_v1", Context.MODE_PRIVATE)

    fun ids(): Set<String> = (prefs.getStringSet("ids", emptySet()) ?: emptySet()).filter { app.agents.get(it) != null }.toSet()
    fun isHub(agentId: String?): Boolean = agentId != null && agentId in (prefs.getStringSet("ids", emptySet()) ?: emptySet())
    fun agents() = ids().mapNotNull { app.agents.get(it) }.sortedBy { it.createdAt }

    /** A link is waiting to be claimed (shown on the Satellite screen). */
    fun pending(now: Long = System.currentTimeMillis()): Boolean = prefs.getLong("claim_until", 0) > now && prefs.getString("claim_hash", null) != null

    /**
     * A new pairing link for this phone, named [name] on the hub. Replaces any unclaimed one.
     * Needs a Tailscale address to put in the link.
     */
    @Synchronized fun make(name: String, now: Long = System.currentTimeMillis()): String {
        val l = app.listener.value
        val host = l.boundTo.firstOrNull { !Net.isIpv6(it) } ?: l.boundTo.firstOrNull()
            ?: throw IllegalStateException("Turn Tailscale on first: the hub reaches this phone over Tailscale.")
        val token = SatelliteLink.newClaimToken()
        val n = name.filterNot { it.isISOControl() }.trim().take(60).ifBlank { "Satellite" }
        check(prefs.edit().putString("claim_hash", cc.bridgit.app.relay.RelayStore.hash(token)).putLong("claim_until", now + CLAIM_MS)
            .putString("name", n).commit())
        val install = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull()?.takeIf { it.startsWith("bgi_") }
        app.log.system("audit", "satellite", "Made a satellite pairing link (works once, for 15 minutes)")
        return SatelliteLink.build(host, l.port, token, n, install)
    }

    fun cancelPending() { prefs.edit().remove("claim_hash").remove("claim_until").commit() }

    /** This phone's name on its hubs. */
    fun name(): String = cc.bridgit.app.platform.DeviceName.tagged(prefs.getString("name", null) ?: cc.bridgit.app.platform.DeviceName.of(app))

    data class Claimed(val agentId: String, val agentName: String, val key: String)

    /**
     * Redeems [token] from a hub calling from [source]. Null if no link is waiting, it expired, or the token is wrong.
     * The new agent's key is locked to [source] (device lock), so only that hub can use it.
     */
    @Synchronized fun claim(token: String, source: String, hubName: String?, now: Long = System.currentTimeMillis()): Claimed? {
        val hash = prefs.getString("claim_hash", null) ?: return null
        if (prefs.getLong("claim_until", 0) <= now) { cancelPending(); return null }
        if (!SatelliteLink.CLAIM_RE.matches(token)) return null
        val given = cc.bridgit.app.relay.RelayStore.hash(token)
        if (!java.security.MessageDigest.isEqual(given.toByteArray(), hash.toByteArray())) return null
        cancelPending()
        val (id, key, name) = newLinkAgent(source, hubName, SatellitePolicy.PHASE1_CAPS)
        claims.value = ClaimEvent(now, hubName?.filterNot { it.isISOControl() }?.trim()?.take(60)?.ifBlank { null })
        return Claimed(id, name, key)
    }

    /**
     * A link agent for a device that claimed a link code (docs/spec-linked-bridgits.md, LNK-11): grants = [caps] (the
     * direction's service list, possibly none), key locked to [source]. Returns (agent id, key, agent name).
     */
    @Synchronized fun claimLink(source: String, hubName: String?, caps: Set<String>): Triple<String, String, String> {
        val r = newLinkAgent(source, hubName, caps)
        claims.value = ClaimEvent(System.currentTimeMillis(), hubName?.filterNot { it.isISOControl() }?.trim()?.take(60)?.ifBlank { null })
        return r
    }

    /** A link agent for a Bridgit that claimed over the relay: a relay agent with the token it made (no Tailscale lock). */
    @Synchronized fun claimRelayLink(hubName: String?, caps: Set<String>, routes: Set<String>, tokenHash: String): Pair<String, String> {
        val agentName = (1..50).map { linkName(hubName, it) }.firstOrNull { app.agents.checkName(it, exceptId = "-") == null }
            ?: throw IllegalStateException("No free name for the link agent.")
        val (a, _) = app.agents.add(agentName, Connection.RELAY, limited = false)
        app.agents.update(a.id) { it.copy(caps = caps, fixer = false, superPowers = false, notes = "Link agent (Linked Bridgits, over the relay). Disconnect the link to remove it.") }
        cc.bridgit.app.relay.RelayStore(app).addAgentHash(a.id, routes, tokenHash)
        check(prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) + a.id).commit())
        claims.value = ClaimEvent(System.currentTimeMillis(), hubName?.filterNot { it.isISOControl() }?.trim()?.take(60)?.ifBlank { null })
        return a.id to agentName
    }

    private fun newLinkAgent(source: String, hubName: String?, caps: Set<String>): Triple<String, String, String> {
        val agentName = (1..50).map { linkName(hubName, it) }.firstOrNull { app.agents.checkName(it, exceptId = "-") == null }
            ?: throw IllegalStateException(app.agents.checkName(linkName(hubName, 1), exceptId = "-") ?: "No free name for the hub link.")
        // Link agents don't use one of the owner's agent slots (as a satellite's return link on its hub).
        val (a, key) = app.agents.add(agentName, Connection.DIRECT, limited = false)
        app.agents.update(a.id) { it.copy(caps = caps, fixer = false, superPowers = false,
            boundIps = listOf(Net.normalise(source)), notes = "Link agent (Linked Bridgits). Disconnect the link to remove it.") }
        check(prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) + a.id).commit())
        return Triple(a.id, key, agentName)
    }

    /** A hub that didn't send its name when claiming names its link agent on a later manifest call. */
    fun noteHubName(agentId: String, hubName: String?) {
        val n = hubName?.filterNot { it.isISOControl() }?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: return
        val a = app.agents.get(agentId) ?: return
        if (!a.name.startsWith("Satellite hub")) return
        val want = linkName(n, 1)
        if (app.agents.checkName(want, agentId) == null) app.agents.update(agentId) { it.copy(name = want) }
    }

    /**
     * Drops the hub link [agentId]. Unless [tellHub] is false (the hub asked for it, or already forgot this phone),
     * the hub is told in the background with the return key, so it removes this satellite too. A hub that is
     * offline finds out on its next check, when the hub-link key stops working (SatelliteHub.refresh).
     */
    fun unlink(agentId: String, tellHub: Boolean = true) {
        if (!isHub(agentId)) return
        val info = if (tellHub) hubs().firstOrNull { it.linkId == agentId } else null
        app.agents.remove(agentId)
        runCatching { cc.bridgit.app.relay.RelayStore(app).remove(agentId) }
        prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) - agentId).remove(INFO + agentId).commit()
        cc.bridgit.app.portal.PortalLinks(app).let { if (it.selected.value == cc.bridgit.app.portal.PortalLinks.HUB + agentId) it.select(null) }
        changes.value = changes.value + 1
        app.log.system("audit", "satellite", "Unlinked a hub")
        if (info != null) kotlin.concurrent.thread(isDaemon = true, name = "hub-unlink") {
            runCatching { SatelliteClient.toHub(info.base, info.key, "POST", "/v1/satellite/hub/unlink", JSONObject(), readS = 8) }
        }
    }

    /**
     * Another hub link that reaches the same hub app (same address and port) as [keep]: the hub added this phone
     * again and replaced its key, so the old link would only show the hub twice. Dropped without telling the hub.
     */
    fun dropOlderLinksTo(keep: String, host: String, port: Int) {
        hubs().filter { it.linkId != keep && it.host == cc.bridgit.app.core.Net.normalise(host) && it.port == port }
            .forEach { unlink(it.linkId, tellHub = false) }
    }

    /**
     * How hub link [agentId] stands, from this phone's side. A hub that never sent its details ([HubInfo]) gave this
     * phone no way to call it back, so this phone can't ask whether the hub still lists it: if such a hub also stops
     * checking in, it was turned off or it forgot this phone, and the owner is told to pair again (5 Nov 2026: a
     * Pixel 2 XL hub on an old build dropped its satellite and the satellite kept showing it as its hub).
     */
    fun state(agentId: String, now: Long = System.currentTimeMillis()): LinkState {
        val a = app.agents.get(agentId) ?: return LinkState.STALE
        val hasInfo = prefs.getString(INFO + agentId, null) != null
        return stateOf(hasInfo, maxOf(a.lastUsedAt, a.createdAt), now, probes[agentId]?.takeIf { now - it.second <= PROBE_FRESH_MS }?.first)
    }

    /** Per hub link: whether Bridgit answered at the hub's address on the usual port ([probeHub]), and when. */
    private val probes = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /**
     * Blocking. For each hub link that never sent its details and stopped checking in ([LinkState.STALE]): does
     * Bridgit answer at that phone's Tailscale address? If it does, the phone is on and the Bridgit there simply
     * doesn't list this one ([LinkState.FORGOTTEN]), which is what the owner is told instead of "offline"
     * (5 Nov 2026: the Pixel 10 had been paired to a test copy of Bridgit on the Pixel 2 XL that was later removed).
     */
    fun probeStaleHubs(now: Long = System.currentTimeMillis()) {
        if (LinkPause.hubsPaused) return
        for (a in agents()) {
            if (prefs.getString(INFO + a.id, null) != null) continue
            if (stateOf(false, maxOf(a.lastUsedAt, a.createdAt), now) != LinkState.STALE) continue
            val fresh = probes[a.id]?.takeIf { now - it.second < PROBE_EVERY_MS }
            if (fresh == null) probeHub(a.id, now)
        }
    }

    /** Blocking. A plain connection to the hub's address on Bridgit's usual port: no request is sent, so nothing lands in its log. */
    private fun probeHub(agentId: String, now: Long): Boolean {
        val host = app.agents.get(agentId)?.boundIps?.firstOrNull()?.takeIf { Net.isTailnet(it) } ?: return false
        val started = System.currentTimeMillis()
        val answered = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(host), DEFAULT_PORT), PROBE_TIMEOUT_MS) }
        }.isSuccess
        cc.bridgit.app.outbound.Outbound.record("tcp://$host:$DEFAULT_PORT", "CONNECT", "tcp", status = if (answered) 200 else 0,
            error = if (answered) null else "no answer", startedAt = started, actor = cc.bridgit.app.outbound.Outbound.Actor.PHONE)
        val before = probes.put(agentId, answered to now)?.first
        if (before != answered) changes.value = changes.value + 1
        return answered
    }

    enum class LinkState {
        /** The hub sent its details; a hub that forgets this phone is noticed on the next check (a 401). */
        OK,
        /** No details yet, but the hub checked in lately: it is probably on an older Bridgit. */
        WAITING,
        /** No details and no check-in for a while, and Bridgit doesn't answer there: the hub is off or unreachable. */
        STALE,
        /** No details and no check-in for a while, but Bridgit answers at the hub's address: it doesn't list this phone. */
        FORGOTTEN;

        /** The hub has stopped checking in: Linked devices offers Pair again. */
        val needsPairing: Boolean get() = this == STALE || this == FORGOTTEN
    }

    @Volatile private var lastCheck = 0L

    /**
     * From the service's 5-minute check, at most every 10 minutes: asks each hub for its applets and portals. A hub
     * that no longer knows this phone's return key removed this satellite, so the hub link goes too.
     */
    fun checkHubsIfDue(now: Long = System.currentTimeMillis()) {
        if (now - lastCheck < 10 * 60_000L || ids().isEmpty() || LinkPause.hubsPaused) return
        lastCheck = now
        refreshFromHubs()
        probeStaleHubs(now)
    }

    // ---- The hub's applets, used from this satellite (owner, 5 Nov 2026) ----

    /** A photo applet or a portal on the hub. */
    data class Item(val id: String, val name: String, val url: String = "")

    /**
     * What a hub told this satellite (POST /v1/satellite/hub-info): its name, its photo applets and portals, and the
     * return key this satellite calls it back with at [host]:[port]. [host] is where the hub's calls come from.
     */
    data class HubInfo(val linkId: String, val name: String, val host: String, val port: Int, val key: String,
                       val targets: List<Item>, val portals: List<Item>, val at: Long,
                       /** What kind of device the hub is; null for a hub on an older version that doesn't say. */
                       val deviceType: cc.bridgit.app.platform.DeviceType? = null) {
        val base: String get() = SatelliteClient.base(host, port)

        fun toJson(): JSONObject = JSONObject().put("name", name).put("host", host).put("port", port)
            .put("key", SatelliteStore.seal(key)).put("at", at).put(cc.bridgit.app.platform.DeviceType.FIELD, deviceType?.wire)
            .put("targets", JSONArray(targets.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("portals", JSONArray(portals.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }))

        companion object {
            fun items(a: JSONArray?, urls: Boolean): List<Item> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val o = a!!.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 80 } ?: return@mapNotNull null
                val name = o.optString("name").filterNot { it.isISOControl() }.trim().take(60).ifBlank { return@mapNotNull null }
                val url = o.optString("url")
                // A portal from the hub may only open bridgit.cc pages, as the Portals tab does for its own.
                if (urls && runCatching { cc.bridgit.app.ui.applets.AppletLists.staysInPortal(android.net.Uri.parse(url)) }.getOrDefault(false) != true) return@mapNotNull null
                Item(id, name, if (urls) url else "")
            }.take(50)

            fun fromBody(linkId: String, source: String, port: Int, key: String, o: JSONObject, now: Long = System.currentTimeMillis()) =
                HubInfo(linkId, o.optString("name").filterNot { it.isISOControl() }.trim().take(60).ifBlank { "Hub" },
                    cc.bridgit.app.core.Net.normalise(source), port, key, items(o.optJSONArray("targets"), false), items(o.optJSONArray("portals"), true), now,
                    cc.bridgit.app.platform.DeviceType.parse(o.optString(cc.bridgit.app.platform.DeviceType.FIELD)))

            fun fromJson(linkId: String, o: JSONObject): HubInfo? {
                val key = o.optJSONObject("key")?.let { SatelliteStore.open(it) } ?: return null
                return HubInfo(linkId, o.optString("name", "Hub"), o.optString("host"), o.optInt("port"), key,
                    items(o.optJSONArray("targets"), false), items(o.optJSONArray("portals"), true), o.optLong("at"),
                    cc.bridgit.app.platform.DeviceType.parse(o.optString(cc.bridgit.app.platform.DeviceType.FIELD)))
            }
        }
    }

    fun saveHubInfo(linkId: String, info: HubInfo) {
        if (!isHub(linkId)) return
        check(prefs.edit().putString(INFO + linkId, info.toJson().toString()).commit())
        changes.value = changes.value + 1
    }

    /** (link id, hub name) per hub, without decrypting keys: Home draws these every second. */
    fun hubNames(): List<Pair<String, String>> = ids().mapNotNull { id ->
        prefs.getString(INFO + id, null)?.let { runCatching { id to JSONObject(it).optString("name", "Hub") }.getOrNull() }
    }

    /** The hubs that told this phone how to call back, oldest link first. */
    fun hubs(): List<HubInfo> = agents().mapNotNull { a ->
        prefs.getString(INFO + a.id, null)?.let { runCatching { HubInfo.fromJson(a.id, JSONObject(it)) }.getOrNull() }
    }

    /** Blocking. Re-reads each hub's photo applets and portals (GET /v1/satellite/hub); a hub that doesn't answer keeps its last list. */
    fun refreshFromHubs() {
        if (LinkPause.hubsPaused) return
        for (h in hubs()) try {
            val o = SatelliteClient.toHub(h.base, h.key, "GET", "/v1/satellite/hub", null, readS = 8)
            val fresh = HubInfo.fromBody(h.linkId, h.host, h.port, h.key, o)
            if (fresh.targets != h.targets || fresh.portals != h.portals || fresh.name != h.name || fresh.deviceType != h.deviceType) saveHubInfo(h.linkId, fresh)
        } catch (e: Exception) {
            if (SatelliteClient.isGone(e)) {
                unlink(h.linkId, tellHub = false)
                app.log.system("audit", "satellite", "${h.name} removed this phone, so its hub link was removed here too")
            }
        }
    }

    /** Blocking. A photo checked by [hub]'s photo applets [ids] (empty = all): the hub's answer, as OwnerPhotos.submit. */
    fun sendPhoto(hub: HubInfo, jpeg: ByteArray, camera: String, ids: List<String>): JSONObject =
        SatelliteClient.toHub(hub.base, hub.key, "POST", "/v1/satellite/hub/photo", JSONObject().put("camera", camera)
            .put("data_base64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)).put("targets", JSONArray(ids)), readS = 40)

    /**
     * Blocking. The hub's Home for this satellite's Home when it is switched to the hub: GET /v1/portal/home or
     * /v1/portal/logs with the return key, the same shapes a Bridgit Portal reads through the relay.
     */
    fun portalGet(hub: HubInfo, path: String, query: Map<String, String> = emptyMap()): JSONObject {
        require(path == "/v1/portal/home" || path == "/v1/portal/logs" || path == "/v1/portal/devices") { "Only Home and Devices reads" }
        return SatelliteClient.toHub(hub.base, hub.key, "GET", path, null, readS = if (path == "/v1/portal/devices") 35 else 15,
            query = (query + ("client" to "Satellite")).toList())
    }

    /** One of the hub's control agents, as a satellite sees it. */
    data class HubAgent(val id: String, val name: String, val model: String?, val paused: Boolean, val lastUsedAt: Long)

    /** Blocking. The hub's control agents (GET /v1/satellite/hub/agents); throws when the hub can't be reached or paused this satellite. */
    fun hubAgents(hub: HubInfo): List<HubAgent> {
        val a = SatelliteClient.toHub(hub.base, hub.key, "GET", "/v1/satellite/hub/agents", null, readS = 8).optJSONArray("agents") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            HubAgent(o.optString("id"), o.optString("name"), o.optString("model").takeIf { it.isNotEmpty() && !o.isNull("model") },
                o.optBoolean("paused"), o.optLong("last_used_at")) } }
    }

    /** Blocking. Pauses or resumes one of the hub's control agents. */
    fun pauseHubAgent(hub: HubInfo, id: String, paused: Boolean) {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        SatelliteClient.toHub(hub.base, hub.key, "POST", "/v1/satellite/hub/agents/$id/pause", JSONObject().put("paused", paused), readS = 8)
    }

    /** Blocking. A one-use bridgit.cc sign-in code from the first hub that has one. */
    fun portalLoginCode(): String? = hubs().firstNotNullOfOrNull { h ->
        runCatching { SatelliteClient.toHub(h.base, h.key, "POST", "/v1/satellite/hub/portal-login", JSONObject(), readS = 8).optString("code") }
            .getOrNull()?.takeIf { cc.bridgit.app.ui.applets.AppletLists.LOGIN_CODE.matches(it) }
    }

    companion object {
        const val CLAIM_MS = 15 * 60_000L
        /** A hub reads its satellites every 5 minutes while it is on: three missed checks. */
        const val STALE_MS = 15 * 60_000L

        /** [answered]: whether Bridgit answered at the hub's address lately (null = not checked). */
        fun stateOf(hasInfo: Boolean, heard: Long, now: Long, answered: Boolean? = null): LinkState = when {
            hasInfo -> LinkState.OK
            now - heard <= STALE_MS -> LinkState.WAITING
            answered == true -> LinkState.FORGOTTEN
            else -> LinkState.STALE
        }
        /** Bridgit's usual port. A hub that never sent its details never said which port it uses. */
        const val DEFAULT_PORT = 8734
        private const val PROBE_TIMEOUT_MS = 3_000
        /** A stale hub is checked again at most this often, and an answer counts for [PROBE_FRESH_MS]. */
        const val PROBE_EVERY_MS = 2 * 60_000L
        const val PROBE_FRESH_MS = 30 * 60_000L
        private const val INFO = "hub_info_"
        /** Bumped when a hub's info changes, so the Camera and Portals tabs can redraw. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** A hub just redeemed this phone's pairing link, so the open QR sheet can say "Connected" and drop the code. */
        data class ClaimEvent(val at: Long, val hubName: String?)
        val claims = kotlinx.coroutines.flow.MutableStateFlow<ClaimEvent?>(null)

        /**
         * The hub link agent's name: "Hub Kitchen tablet", "Hub Kitchen tablet 2", … within the agent-name rules
         * (letters, digits, spaces, - and _, at most 24). "Satellite hub" when the hub sent no usable name.
         */
        fun linkName(hubName: String?, n: Int): String {
            val clean = hubName.orEmpty().map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') it else ' ' }.joinToString("")
                .replace(Regex(" +"), " ").trim()
            val suffix = if (n == 1) "" else " $n"
            val base = if (clean.isEmpty()) "Satellite hub" else "Hub " + clean
            return base.take(24 - suffix.length).trim() + suffix
        }
    }
}
