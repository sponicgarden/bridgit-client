package cc.bridgit.app.portal

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.relay.RelayDispatcher
import cc.bridgit.app.relay.RelayStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.first

/**
 * Pairing a Bridgit Portal: each iPhone or iPad gets its own relay agent in connector mode ("Bridgit Portal",
 * "Bridgit Portal 2", …) with only the read-only portal routes and no control tools. Disconnecting removes the
 * agent: its token stops working on the next relay frame (within about a second while the phone is connected).
 */
object PortalPairing {
    data class Pairing(val agent: Agent, val link: String)

    /** The phone's friendly name (see [cc.bridgit.app.platform.DeviceName]), e.g. "A11 Blue Tab". */
    fun phoneName(app: BridgeApp): String = cc.bridgit.app.platform.DeviceName.of(app)

    /** "Bridgit Portal", then "Bridgit Portal 2", … for the first name no agent has yet. */
    fun nextName(app: BridgeApp): String = generateSequence(1) { it + 1 }
        .map { if (it == 1) PortalData.AGENT_NAME else "${PortalData.AGENT_NAME} $it" }
        .first { n -> app.agents.all().none { it.name.equals(n, ignoreCase = true) } }

    /** The relay must already be on (RelaySetup.turnOn). Returns the new agent and its pairing link (shown to the owner once). */
    /**
     * [singleUse]: the link works once (the portal claims it with a token of its own) and expires in 24 h unclaimed.
     * Off only for the Google-approve path, where the backend hands the pairing to one signed-in device itself.
     */
    fun create(app: BridgeApp, store: RelayStore, singleUse: Boolean = true): Pairing {
        check(store.enabled && store.base.isNotEmpty()) { "The relay is off" }
        val name = nextName(app)
        app.agents.checkName(name)?.let { error(it) }
        val a = app.agents.add(name, Connection.RELAY).first
        val live = app.agents.update(a.id) { it.copy(caps = PortalData.GRANTS, fixer = false, superPowers = false) } ?: a
        val token = try { store.addAgent(a.id, "connector", null, PortalData.ROUTES.toSet(), emptySet(), if (singleUse) RelayStore.CLAIM_MS else 0) }
            catch (e: Exception) { app.agents.remove(a.id); throw e }
        val install = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull()?.takeIf { it.startsWith("bgi_") }
        val link = PortalData.pairLink(PortalData.relayOrigin(store.base, store.device), store.device, token, phoneName(app), install, portal = true, claim = singleUse)
        return Pairing(live, link)
    }

    /** Paired portals: relay agents whose allowlist is the portal's. */
    fun paired(app: BridgeApp, store: RelayStore): List<Agent> = store.agents()
        .filter { PortalData.isPortalConfig(RelayDispatcher.strings(it, "routes"), RelayDispatcher.strings(it, "tools")) }
        .mapNotNull { app.agents.get(it.getString("id")) }.sortedBy { it.createdAt }

    /**
     * Every portal granted access to this phone: claimed single-use links and approved pairings, whether or not they
     * have called lately. A link still waiting to be scanned is not a device yet and is left out.
     */
    fun granted(app: BridgeApp, store: RelayStore): List<Agent> = store.agents()
        .filter { PortalData.isPortalConfig(RelayDispatcher.strings(it, "routes"), RelayDispatcher.strings(it, "tools")) && it.optLong("claim_until", 0) <= 0 }
        .mapNotNull { app.agents.get(it.getString("id")) }.sortedBy { it.createdAt }

    /** A paired iPhone/iPad (or Android phone) that has called recently; [kind] is what it said it is (client=…). */
    data class Viewer(val agentId: String, val name: String, val kind: String?, val lastAt: Long, val live: Boolean)

    /** Shown on Home after a call within this long; drawn as live (moving link) within [LIVE_MS]. The portal polls every 10 s on Home. */
    const val SHOWN_MS = 5 * 60_000L
    const val LIVE_MS = 60_000L

    /** Who is viewing this phone through the portal, most recent first. */
    fun viewers(app: BridgeApp, store: RelayStore, now: Long = System.currentTimeMillis()): List<Viewer> =
        paired(app, store).filter { it.lastUsedAt > 0 && now - it.lastUsedAt in 0..SHOWN_MS }.sortedByDescending { it.lastUsedAt }
            .map { Viewer(it.id, it.name, PortalModule.clientOf(it.id), it.lastUsedAt, now - it.lastUsedAt <= LIVE_MS) }

    /** What Home's scene draws for [viewers] (portal-overlay.js viewer()), or null for nothing. */
    fun sceneViewer(viewers: List<Viewer>): org.json.JSONObject? {
        val v = viewers.firstOrNull() ?: return null
        return org.json.JSONObject().put("kind", v.kind ?: "iPhone/iPad")
            .put("sub", if (viewers.size > 1) "${viewers.size} viewing" else "Portal").put("live", v.live)
    }

    /**
     * Fires when a relay agent makes its first call (a new portal has connected) or one is removed, so the
     * Linked devices map redraws at once rather than on its next 10 s tick. Ordinary calls don't fire it.
     */
    fun portalsChanged(app: BridgeApp): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        fun key() = app.agents.all().filter { it.connection == Connection.RELAY && it.lastUsedAt > 0 }.map { it.id }.toSet()
        var last = key()
        val l = { val k = key(); if (k != last) { last = k; trySend(Unit) } }
        app.agents.addListener(l)
        awaitClose { app.agents.removeListener(l) }
    }

    /** Waits for the pairing [agentId] to make its first call: the portal scanned the link and is now viewing. */
    suspend fun awaitFirstCall(app: BridgeApp, agentId: String) {
        fun used() = (app.agents.get(agentId)?.lastUsedAt ?: 0L) > 0
        if (used()) return
        kotlinx.coroutines.flow.callbackFlow {
            val l = { if (used()) trySend(Unit); Unit }
            app.agents.addListener(l)
            if (used()) trySend(Unit)
            awaitClose { app.agents.removeListener(l) }
        }.first()
    }

    /** What a connected portal calls itself ("iPhone 16 Pro", "iPad"), once it has said; null before. */
    fun clientName(app: BridgeApp, agentId: String): String? =
        PortalClients.get(app, agentId)?.name ?: PortalModule.clientOf(agentId)

    /** Cuts the iPhone/iPad off: the relay token and the agent are removed. */
    fun disconnect(app: BridgeApp, store: RelayStore, id: String) { store.remove(id); app.agents.remove(id) }
}
