package cc.bridgit.app.portal

import cc.bridgit.app.link.LinkTier
import cc.bridgit.app.portal.LinkedDevicesLayout.Node
import cc.bridgit.app.portal.LinkedDevicesLayout.Role

/**
 * Linked devices' three sections (owner, 8 Nov 2026): every linked device in exactly one trust tier, in the order
 * Your Other Devices, Devices of Associates, Devices in the Wild. This phone and the devices on its network are not
 * links and stay on the map only. Pure: tested without a screen.
 */
object LinkTierSections {
    /**
     * One linked device's place: [ids] are what its tier is changed through (a link id, portal:… or viewing:…; a hub
     * this phone both belongs to and views has two). A device with two links takes the narrower tier.
     */
    data class Part(val id: String, val tier: LinkTier, val expiresAt: Long? = null, val reviewAt: Long? = null, val associate: String? = null,
                    val email: String? = null, val state: State? = null)
    data class Row(val node: Node, val tier: LinkTier, val ids: List<String>, val expiresAt: Long?, val reviewAt: Long?, val associate: String?,
                   val email: String? = null, val state: State? = null)

    /** A link's state worth saying on its row, most important first (design spec: Ended, Paused, Waiting). */
    enum class State { ENDED, PAUSED, WAITING }

    fun row(node: Node, parts: List<Part>): Row {
        // A node with no known link reads as the most restrictive tier.
        val tier = parts.map { it.tier }.maxByOrNull { it.rank } ?: LinkTier.PUBLIC
        return Row(node, tier, parts.map { it.id }, parts.mapNotNull { it.expiresAt }.minOrNull(), parts.mapNotNull { it.reviewAt }.minOrNull(),
            parts.firstNotNullOfOrNull { it.associate }, parts.firstNotNullOfOrNull { it.email }, parts.mapNotNull { it.state }.minOrNull())
    }

    fun group(nodes: List<Node>, parts: (Node) -> List<Part>): List<Pair<LinkTier, List<Row>>> {
        val rows = nodes.filter { !it.thisPhone && it.role != Role.NETWORK }.distinctBy { it.key }.map { row(it, parts(it)) }
        return LinkTier.ORDER.map { t -> t to rows.filter { it.tier == t }.sortedBy { it.node.name.lowercase() } }
    }
}
