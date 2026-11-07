package cc.bridgit.app.portal

import cc.bridgit.app.platform.DeviceType

/** Counts the same devices as the map, using connection enablement rather than recent activity. */
object LinkedDeviceCounts {
    data class Link(val id: String, val name: String, val enabled: Boolean)

    fun count(hubs: List<Link>, viewing: List<Link>, satellites: List<Link>, viewers: List<Link>): Pair<Int, Int> {
        fun List<Link>.peers() = map { LinkedDevicesLayout.Peer(it.id, it.name, it.enabled) }
        val diagram = LinkedDevicesLayout.build("", DeviceType.PHONE, hubs.peers(), viewing.peers(), satellites.peers(), viewers.peers())
        val devices = diagram.all.filterNot { it.thisPhone }
        return devices.count { it.online } to devices.count { !it.online }
    }
}
