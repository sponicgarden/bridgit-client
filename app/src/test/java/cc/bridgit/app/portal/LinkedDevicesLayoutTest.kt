package cc.bridgit.app.portal

import cc.bridgit.app.portal.LinkedDevicesLayout.Edge
import cc.bridgit.app.portal.LinkedDevicesLayout.Kind
import cc.bridgit.app.portal.LinkedDevicesLayout.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.bridgit.app.link.LinkModel.State as S
import cc.bridgit.app.platform.DeviceType

class LinkedDevicesLayoutTest {
    private fun build(hubsOf: List<Peer> = emptyList(), viewing: List<Peer> = emptyList(),
                      satellites: List<Peer> = emptyList(), viewers: List<Peer> = emptyList(),
                      network: List<LinkedDevicesLayout.NetPeer> = emptyList()) =
        LinkedDevicesLayout.build("Pixel 10", cc.bridgit.app.platform.DeviceType.PHONE, hubsOf, viewing, satellites, viewers, network)

    private fun net(id: String, added: Boolean = false, online: Boolean = true, rank: Int = 5) =
        LinkedDevicesLayout.NetPeer(id, "Dev $id", "Camera", "CAMERA", online, added, rank)

    @Test fun `network devices hang below this phone's hub and do not count as links`() {
        val d = build(network = listOf(net("10.0.0.5"), net("10.0.0.6")))
        assertTrue(d.alone)
        assertEquals(3, d.rows.size) // hubs, satellites, network: no portal row
        assertEquals(listOf("net:10.0.0.5", "net:10.0.0.6"), d.network.map { it.key })
        assertEquals(setOf(Edge("net:10.0.0.5", "hub:self"), Edge("net:10.0.0.6", "hub:self")), d.edges.toSet())
    }

    @Test fun `no network devices and no portal row leaves hubs and satellites`() {
        assertEquals(listOf(LinkedDevicesLayout.Lane.HUBS, LinkedDevicesLayout.Lane.SATELLITES), build().lanes.map { it.first })
    }

    @Test fun `the portal row shows only when this phone views another`() {
        val d = build(viewing = listOf(Peer("d1", "Kitchen", true)), viewers = listOf(Peer("r1", "iPad", true)))
        assertEquals(LinkedDevicesLayout.Lane.PORTALS, d.lanes.first().first)
        assertEquals(listOf("portal:self"), d.portals.map { it.key })
    }

    @Test fun `a satellite-only phone hangs its network devices from itself, not the remote hub`() {
        val d = build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", true)), network = listOf(net("10.0.0.5")))
        assertEquals(listOf(Edge("sat:self", "hub:sat:a1"), Edge("net:10.0.0.5", "sat:self")), d.edges)
    }

    @Test fun `added devices come first and the rest fold into a more tile`() {
        val d = build(network = listOf(net("a", rank = 1), net("b", rank = 2), net("c", added = true, online = false, rank = 9),
            net("d", rank = 3), net("e", rank = 4), net("f", rank = 5)))
        assertEquals(listOf("net:c", "net:a", "net:b", "net:d", LinkedDevicesLayout.NETWORK_MORE), d.network.map { it.key })
        assertEquals("+2 more", d.network.last().name)
        assertEquals(Kind.NETWORK_MORE, d.network.last().kind)
    }

    @Test fun `exactly the cap shows every device without a more tile`() {
        val d = build(network = (1..LinkedDevicesLayout.NETWORK_SHOWN).map { net("h$it") })
        assertEquals(LinkedDevicesLayout.NETWORK_SHOWN, d.network.size)
        assertTrue(d.network.none { it.kind == Kind.NETWORK_MORE })
    }

    @Test fun `nothing linked shows just this phone as a hub`() {
        val d = build()
        assertEquals(listOf("hub:self"), d.all.map { it.key })
        assertTrue(d.alone)
        assertTrue(d.hubs.single().thisPhone)
    }

    @Test fun `a satellite hangs below its hub and this phone is not drawn as a hub`() {
        val d = build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", true)))
        assertEquals(listOf("hub:sat:a1"), d.hubs.map { it.key })
        assertEquals(listOf("sat:self"), d.satellites.map { it.key })
        assertTrue(d.portals.isEmpty())
        assertEquals(listOf(Edge("sat:self", "hub:sat:a1")), d.edges)
        assertEquals("a1", d.hubs.single().satLink)
    }

    @Test fun `a hub that doesn't list this phone keeps that status on its node`() {
        val d = build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", false, "doesn't list this phone")))
        assertEquals("doesn't list this phone", d.hubs.single().status)
        assertEquals(null, build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", false))).hubs.single().status)
    }

    @Test fun `portals viewing a hub hang below it with its satellites, as view-only satellites`() {
        val d = build(satellites = listOf(Peer("s1", "A9", true), Peer("s2", "A11", false)), viewers = listOf(Peer("r1", "iPad", true)))
        assertEquals(listOf("hub:self"), d.hubs.map { it.key })
        assertEquals(emptyList<String>(), d.portals.map { it.key })
        assertEquals(listOf("sat:s1", "sat:s2", "portal:r1"), d.satellites.map { it.key })
        assertEquals(setOf(Edge("portal:r1", "hub:self"), Edge("sat:s1", "hub:self"), Edge("sat:s2", "hub:self")), d.edges.toSet())
        val viewer = d.satellites.last()
        assertEquals(Kind.VIEWER, viewer.kind); assertEquals(LinkedDevicesLayout.Role.SATELLITE, viewer.role)
    }

    @Test fun `an offline portal is still drawn`() {
        val d = build(viewers = listOf(Peer("r1", "iPhone", false), Peer("r2", "Bridgit viewer 2", false)))
        assertEquals(listOf("portal:r1", "portal:r2"), d.satellites.map { it.key })
        assertTrue(d.satellites.none { it.online })
    }

    @Test fun `a portal of several hubs has a line to each`() {
        val d = build(viewing = listOf(Peer("d1", "Kitchen", true), Peer("d2", "Office", false)))
        assertEquals(listOf("hub:portal:d1", "hub:portal:d2"), d.hubs.map { it.key })
        assertEquals(listOf("portal:self"), d.portals.map { it.key })
        assertEquals(listOf(Edge("portal:self", "hub:portal:d1"), Edge("portal:self", "hub:portal:d2")), d.edges)
    }

    @Test fun `a hub this phone belongs to and views is drawn once`() {
        val d = build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", false)), viewing = listOf(Peer("d1", "pixel 2 xl", true)))
        val hub = d.hubs.single()
        assertEquals("a1", hub.satLink); assertEquals("d1", hub.portalLink); assertTrue(hub.online)
        assertEquals(setOf(Edge("sat:self", hub.key), Edge("portal:self", hub.key)), d.edges.toSet())
    }

    @Test fun `this phone can be a satellite and a hub at once`() {
        val d = build(hubsOf = listOf(Peer("a1", "Hub", true)), satellites = listOf(Peer("s1", "Spare", true)))
        assertEquals(listOf("hub:self", "hub:sat:a1"), d.hubs.map { it.key })
        assertEquals(listOf("sat:s1", "sat:self"), d.satellites.map { it.key })
        assertEquals(2, d.all.count { it.thisPhone }) // once as a hub, once as a satellite
    }

    @Test fun `every node carries its device type, this device's own and what linked ones sent`() {
        val d = LinkedDevicesLayout.build("A11", DeviceType.TABLET, hubsOf = listOf(Peer("h", "Kitchen", true, type = DeviceType.COMPUTER)),
            viewing = emptyList(), satellites = listOf(Peer("s", "Cam", true, type = DeviceType.CAMERA), Peer("o", "Old", false)),
            viewers = listOf(Peer("v", "iPad", true, type = DeviceType.TABLET)))
        val by = d.all.associate { it.key to it.type }
        assertEquals(DeviceType.TABLET, by["hub:self"])
        assertEquals(DeviceType.TABLET, by["sat:self"])
        assertEquals(DeviceType.COMPUTER, by["hub:sat:h"])
        assertEquals(DeviceType.CAMERA, by["sat:s"])
        assertEquals(null, by["sat:o"])
        assertEquals(DeviceType.TABLET, by["portal:v"])
    }

    @Test fun `portals that send no type are read from their kind or model`() {
        assertEquals(DeviceType.TABLET, PortalClients.guess("iPad"))
        assertEquals(DeviceType.PHONE, PortalClients.guess("iPhone 16 Pro · iOS 26.1 · Portal 1.4"))
        assertEquals(DeviceType.COMPUTER, PortalClients.guess("Mac"))
        assertEquals(null, PortalClients.guess("Android"))
        assertEquals(DeviceType.TABLET, PortalClients.Info("iPhone", null, 0, sentType = DeviceType.TABLET).type)
    }

    // ---- A0243: each link's two directions ----

    @Test fun `a satellite's arrows run satellite to hub for what it uses and back for what the hub uses`() {
        val d = build(satellites = listOf(Peer("s1", "Kitchen", true, out = S.ON, inn = S.WAITING_FOR_USER)))
        val e = d.edges.single()
        assertEquals(Edge("sat:s1", "hub:self", LinkedDevicesLayout.Flow(forward = S.WAITING_FOR_USER, back = S.ON)), e)
    }

    @Test fun `this phone as a satellite points at its hub with its own out direction`() {
        val d = build(hubsOf = listOf(Peer("a1", "Pixel 2 XL", true, out = S.PAUSED, inn = S.WAITING_FOR_TARGET)))
        assertEquals(LinkedDevicesLayout.Flow(forward = S.PAUSED, back = S.WAITING_FOR_TARGET), d.edges.single { it.from == "sat:self" }.flow)
    }

    @Test fun `a phone this one both belongs to and views keeps each link's own directions`() {
        val d = build(hubsOf = listOf(Peer("a1", "Kitchen", true, out = S.ON, inn = S.OFF)),
            viewing = listOf(Peer("d1", "kitchen", true, out = S.ON, inn = S.OFF)))
        assertEquals(1, d.hubs.size)
        assertEquals(setOf("sat:self", "portal:self"), d.edges.map { it.from }.toSet())
        assertTrue(d.edges.all { it.flow == LinkedDevicesLayout.Flow(S.ON, S.OFF) })
    }

    @Test fun `a viewer only uses this phone, and one direction alone still draws two arrows`() {
        val d = build(viewers = listOf(Peer("r1", "iPad", true, inn = S.ON)))
        assertEquals(LinkedDevicesLayout.Flow(forward = S.ON, back = S.OFF), d.edges.single().flow)
    }

    @Test fun `unknown directions and network devices stay a single line`() {
        val d = build(satellites = listOf(Peer("s1", "Kitchen", true)), network = listOf(net("10.0.0.5")))
        assertTrue(d.edges.all { it.flow == null })
    }
}
