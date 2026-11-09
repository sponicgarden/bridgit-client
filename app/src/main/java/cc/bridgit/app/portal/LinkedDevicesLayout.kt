package cc.bridgit.app.portal

import cc.bridgit.app.link.LinkModel.State

/**
 * The Linked devices map (owner, 5 Nov 2026): every hub, portal and satellite this phone is linked to, in three rows.
 * Portals that view this phone are drawn as view-only satellites (Kind.VIEWER in the satellite row); the portal row
 * holds this phone when it views others.
 * Hubs sit in the middle; the portals that view a hub sit above it (they look down into it); the satellites that lend
 * a hub their camera, microphone and more hang below it; cameras, TVs and other devices on this phone's network sit in a
 * bottom row. Each line joins a portal or satellite to its hub. This phone
 * is drawn in every role it has (a satellite of one hub can be a hub to its own satellites), each time marked.
 * Pure: [build] turns what this phone knows into rows and lines, so the layout is tested without a screen.
 */
object LinkedDevicesLayout {
    enum class Role { PORTAL, HUB, SATELLITE, NETWORK }

    /** What a node is, from this phone's point of view; decides what a tap on it offers. */
    enum class Kind { THIS_PHONE, HUB, SATELLITE, VIEWER, NETWORK, NETWORK_MORE }

    /**
     * One device known to this phone. [id] is the store's id: a hub link agent ([satLink]) and/or a Portal link
     * device ([portalLink]) for a hub, a satellite id, or a relay agent id for a viewing iPhone/iPad.
     */
    data class Peer(val id: String, val name: String, val online: Boolean, val status: String? = null,
                    val type: cc.bridgit.app.platform.DeviceType? = null,
                    /** A0243: the link's two directions as this phone sees them: [out] this phone uses the peer, [inn] the peer uses this phone. */
                    val out: State? = null, val inn: State? = null)

    /**
     * A device on this phone's network (owner, 5 Nov 2026): one the last network scan found, or one added to Bridgit
     * (a camera or smart device). [id] is its address; [label] what it is ("Camera", "TV"); [icon] the
     * [cc.bridgit.app.lan.NetworkDevicePresentation.Kind] name that picks its picture; [rank] lower draws first.
     */
    data class NetPeer(val id: String, val name: String, val label: String, val icon: String, val online: Boolean,
                       val added: Boolean, val rank: Int = 0)

    /** [status]: shown in place of "offline" under an offline device, e.g. "doesn't list this phone". */
    data class Node(val key: String, val name: String, val role: Role, val kind: Kind, val online: Boolean,
                    val id: String = "", val satLink: String? = null, val portalLink: String? = null, val status: String? = null,
                    val label: String = "", val icon: String = "", val added: Boolean = false,
                    /** Phone, tablet, computer…: this device's own, or what a linked one sent; null when it sent none. */
                    val type: cc.bridgit.app.platform.DeviceType? = null) {
        val thisPhone: Boolean get() = kind == Kind.THIS_PHONE
    }

    /** A row of the map, named up its left edge. */
    enum class Lane { PORTALS, HUBS, SATELLITES, NETWORK }

    /**
     * A line from a portal or satellite ([from]) to its hub ([to]), by node key. [flow]: the link's two directions
     * (A0243), drawn as two arrows; null for a network device or a link whose directions aren't known (one plain line).
     */
    data class Edge(val from: String, val to: String, val flow: Flow? = null)

    /**
     * A link's two directions, each on only when both devices opted in (double opt-in, LNK-03/04).
     * [forward]: [Edge.from] uses [Edge.to] (an arrow from → to); [back]: [Edge.to] uses [Edge.from].
     * [LinkModel.State.WAITING_FOR_TARGET]: the using device said yes, the used one hasn't; WAITING_FOR_USER the reverse.
     */
    data class Flow(val forward: State, val back: State)

    /** Each direction with no opt-in recorded reads as off. */
    private fun flow(forward: State?, back: State?) = if (forward == null && back == null) null else Flow(forward ?: State.OFF, back ?: State.OFF)

    data class Diagram(val portals: List<Node>, val hubs: List<Node>, val satellites: List<Node>, val edges: List<Edge>,
                       val network: List<Node> = emptyList()) {
        /**
         * Hubs and satellites; the portal row only when this phone views another (portals viewing this one are drawn
         * as satellites, so it is otherwise empty), and the network row only when there is one.
         */
        val lanes: List<Pair<Lane, List<Node>>> get() = buildList {
            if (portals.isNotEmpty()) add(Lane.PORTALS to portals)
            add(Lane.HUBS to hubs); add(Lane.SATELLITES to satellites)
            if (network.isNotEmpty()) add(Lane.NETWORK to network)
        }
        val rows: List<List<Node>> get() = lanes.map { it.second }
        val all: List<Node> get() = portals + hubs + satellites + network
        /** Nothing linked yet: the map shows just this phone (network devices are not links). */
        val alone: Boolean get() = edges.none { it.from !in networkKeys }
        private val networkKeys get() = network.map { it.key }.toSet()
    }

    /**
     * [hubsOf]: hubs this phone is a satellite of. [viewing]: phones this one views as a Bridgit Portal.
     * [satellites]: this phone's satellites. [viewers]: iPhones, iPads and phones that view this one.
     * [network]: devices on this phone's network, drawn in a bottom row hanging from this phone (they share its network,
     * whatever role it has): the ones added to Bridgit first, then by [NetPeer.rank], at most [NETWORK_SHOWN] and a
     * "+N more" tile for the rest.
     */
    fun build(thisName: String, thisType: cc.bridgit.app.platform.DeviceType, hubsOf: List<Peer>, viewing: List<Peer>, satellites: List<Peer>, viewers: List<Peer>,
              network: List<NetPeer> = emptyList()): Diagram {
        // This phone is a hub when something hangs off it, or when nothing is linked at all (it stands alone).
        val selfHub = satellites.isNotEmpty() || viewers.isNotEmpty() || (hubsOf.isEmpty() && viewing.isEmpty())
        val hubs = mutableListOf<Node>()
        if (selfHub) hubs += Node(SELF_HUB, thisName, Role.HUB, Kind.THIS_PHONE, true, type = thisType)
        hubsOf.forEach { p -> hubs += Node("hub:sat:${p.id}", p.name, Role.HUB, Kind.HUB, p.online, p.id, satLink = p.id, status = p.status, type = p.type) }
        // A phone this one both belongs to and views is one hub, not two (matched by name: the stores share no id).
        viewing.forEach { p ->
            val same = hubs.indexOfFirst { it.kind == Kind.HUB && it.portalLink == null && sameName(it.name, p.name) }
            if (same >= 0) hubs[same] = hubs[same].let { it.copy(online = it.online || p.online, portalLink = p.id, type = it.type ?: p.type) }
            else hubs += Node("hub:portal:${p.id}", p.name, Role.HUB, Kind.HUB, p.online, p.id, portalLink = p.id, type = p.type)
        }

        val edges = mutableListOf<Edge>()
        val portals = mutableListOf<Node>()
        if (viewing.isNotEmpty()) {
            portals += Node(SELF_PORTAL, thisName, Role.PORTAL, Kind.THIS_PHONE, true, type = thisType)
            val byId = viewing.associateBy { it.id }
            hubs.filter { it.portalLink != null }.forEach { h -> val p = byId[h.portalLink]; edges += Edge(SELF_PORTAL, h.key, flow(p?.out, p?.inn)) }
        }

        val sats = mutableListOf<Node>()
        satellites.forEach { p ->
            val n = Node("sat:${p.id}", p.name, Role.SATELLITE, Kind.SATELLITE, p.online, p.id, type = p.type); sats += n; edges += Edge(n.key, SELF_HUB, flow(p.inn, p.out))
        }
        // Portals viewing this phone are view-only satellites (owner, 5 Nov 2026): same row and colour, after the full ones.
        viewers.forEach { p ->
            val n = Node("portal:${p.id}", p.name, Role.SATELLITE, Kind.VIEWER, p.online, p.id, status = p.status, type = p.type); sats += n; edges += Edge(n.key, SELF_HUB, flow(p.inn, p.out))
        }
        if (hubsOf.isNotEmpty()) {
            sats += Node(SELF_SAT, thisName, Role.SATELLITE, Kind.THIS_PHONE, true, type = thisType)
            val byId = hubsOf.associateBy { it.id }
            hubs.filter { it.satLink != null }.forEach { h -> val p = byId[h.satLink]; edges += Edge(SELF_SAT, h.key, flow(p?.out, p?.inn)) }
        }
        // This phone's node the network row hangs from: its hub, else its satellite, else its portal.
        val self = listOf(SELF_HUB, SELF_SAT, SELF_PORTAL).firstOrNull { k -> (hubs + sats + portals).any { it.key == k } }
        val net = mutableListOf<Node>()
        if (self != null) {
            val sorted = network.distinctBy { it.id }.sortedWith(compareBy<NetPeer> { !it.added }.thenBy { !it.online }.thenBy { it.rank }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val shown = if (sorted.size > NETWORK_SHOWN) sorted.take(NETWORK_SHOWN - 1) else sorted
            shown.forEach { p ->
                val n = Node("net:${p.id}", p.name, Role.NETWORK, Kind.NETWORK, p.online, p.id, label = p.label, icon = p.icon, added = p.added)
                net += n; edges += Edge(n.key, self)
            }
            val more = sorted.size - shown.size
            if (more > 0) net += Node(NETWORK_MORE, "+$more more", Role.NETWORK, Kind.NETWORK_MORE, true, label = "Network", icon = "MORE")
        }
        return Diagram(portals, hubs, sats, edges, net)
    }

    private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    const val SELF_HUB = "hub:self"
    const val SELF_PORTAL = "portal:self"
    const val SELF_SAT = "sat:self"
    const val NETWORK_MORE = "net:more"
    /** Tiles in the network row, the "+N more" tile included. */
    const val NETWORK_SHOWN = 5
}
