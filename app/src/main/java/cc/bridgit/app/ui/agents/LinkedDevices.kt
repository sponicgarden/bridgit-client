package cc.bridgit.app.ui.agents

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.relay.RelayStore

/**
 * The linked devices that hold permissions on this phone (A0116): iPhones and iPads paired through the portal, and
 * the phone-to-phone links (a hub's link here, a satellite's return link). Each has an agent record, so its
 * permissions are the same ids, counts and switches as an agent's. What it can be given is capped at what its
 * pairing gave it: the kind's own grants plus anything it ever held here, so a switch turned off can be turned back on.
 */
object LinkedDevices {
    enum class Kind(val wire: String) { PORTAL("portal"), HUB("hub"), SATELLITE("satellite") }
    data class Linked(val agent: Agent, val kind: Kind, val name: String)

    fun all(app: BridgeApp): List<Linked> {
        val portal = runCatching { cc.bridgit.app.portal.PortalPairing.paired(app, RelayStore(app)).map { it.id }.toSet() }.getOrDefault(emptySet())
        return app.agents.all().filter { it.connection != Connection.APPLET }.mapNotNull { a ->
            val kind = when {
                a.id in portal -> Kind.PORTAL
                app.hubLinks.isHub(a.id) -> Kind.HUB
                app.satellites.isReturnAgent(a.id) -> Kind.SATELLITE
                else -> return@mapNotNull null
            }
            val name = if (kind == Kind.PORTAL) cc.bridgit.app.portal.PortalModule.clientOf(a.id) ?: a.name else a.name
            Linked(a, kind, name).also { remember(app, it) }
        }
    }

    fun get(app: BridgeApp, id: String): Linked? = all(app).firstOrNull { it.agent.id == id }

    /** What [l] may be given; null when it may have everything (it was paired with every permission). */
    fun ceiling(app: BridgeApp, l: Linked): Set<String>? {
        if (l.agent.caps == null) return null
        val base = when (l.kind) {
            Kind.PORTAL -> cc.bridgit.app.portal.PortalData.GRANTS
            Kind.HUB, Kind.SATELLITE -> cc.bridgit.app.satellite.SatellitePolicy.PHASE1_CAPS
        }
        val seen = prefs(app).getStringSet(l.agent.id, null) ?: emptySet()
        return base + seen + l.agent.caps
    }

    private fun remember(app: BridgeApp, l: Linked) {
        val caps = l.agent.caps ?: return
        val p = prefs(app)
        val seen = p.getStringSet(l.agent.id, null) ?: emptySet()
        if (!seen.containsAll(caps)) p.edit().putStringSet(l.agent.id, seen + caps).apply()
    }

    private fun prefs(app: BridgeApp) = app.getSharedPreferences("linked_device_ceilings", Context.MODE_PRIVATE)
}
