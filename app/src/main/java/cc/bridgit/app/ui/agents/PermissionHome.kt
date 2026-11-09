package cc.bridgit.app.ui.agents

import cc.bridgit.app.core.Agent

/**
 * The one permission home (A0115-A0118): the Agents and Linked Devices tab lists every agent, then this device and
 * every linked device, each with its permissions as "granted of total", expandable, switchable in place. Agents and
 * linked devices use the same permission ids (the capability modules, the backend's shared vocabulary at
 * GET /v1/device-profiles/vocabulary) and the same layers, so the tab, the API read
 * (GET /v1/owner/configuration/permissions) and every other screen's read-only pointer agree. Pure logic.
 */
object PermissionHome {
    /** What decided a permission's state; the API reads the same wire names. */
    enum class Layer(val wire: String) {
        /** The owner (or an agent with authority) set it on or off for this agent or device. */
        SPECIFIC("specific"),
        /** Inherited: an agent with every permission (including new ones), or a device's (type, purpose) default. */
        DEFAULT("default"),
        /** Granted, and owner-level because the agent has Superpowers (A0113/A0114's "on via Superpowers"). */
        SUPERPOWERS("superpowers"),
        /** A device switch nobody has set: the switch's own starting value. */
        BASELINE("baseline"),
        /** A linked device can't be given it: more than it was paired with. */
        UNAVAILABLE("unavailable"),
    }

    data class Perm(val id: String, val title: String, val group: String, val on: Boolean, val layer: Layer, val canChange: Boolean = true)
    data class Summary(val granted: Int, val total: Int)

    /** Permissions whose owner-level routes need Superpowers: on via Superpowers when granted to a Super agent. */
    val SUPER_GATED = setOf("settings", "owner_features", "adb")

    fun agent(items: List<PermissionGroups.Item>, agent: Agent): List<Perm> = items.map { item ->
        val on = agent.allows(item.id)
        Perm(item.id, item.title, item.group, on, when {
            on && agent.superPowers && item.id in SUPER_GATED -> Layer.SUPERPOWERS
            agent.caps == null -> Layer.DEFAULT
            else -> Layer.SPECIFIC
        })
    }

    /**
     * A linked device's principal on this phone (a portal viewer, a hub or a satellite): the same list, but it can only
     * be given what its pairing gave it ([ceiling]); anything more reads as unavailable, never hidden.
     */
    fun linked(items: List<PermissionGroups.Item>, agent: Agent, ceiling: Set<String>?): List<Perm> = agent(items, agent).map { p ->
        val allowed = ceiling == null || p.id in ceiling
        if (!p.on && !allowed) p.copy(layer = Layer.UNAVAILABLE, canChange = false) else p
    }

    /** This device's own switches: what any agent may use here. [inherited] is the (type, purpose) default, if any. */
    fun device(items: List<PermissionGroups.Item>, isOn: (String) -> Boolean, isSpecific: (String) -> Boolean, inherited: (String) -> Boolean?): List<Perm> =
        items.map { item ->
            Perm(item.id, item.title, item.group, isOn(item.id), when {
                isSpecific(item.id) -> Layer.SPECIFIC
                inherited(item.id) != null -> Layer.DEFAULT
                else -> Layer.BASELINE
            })
        }

    fun summary(perms: List<Perm>) = Summary(perms.count { it.on }, perms.size)
}
