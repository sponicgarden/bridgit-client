package cc.bridgit.app.lan

import org.junit.Assert.*
import org.junit.Test

class NetworkDevicePresentationTest {
    private fun device(name: String, vararg services: String) = NetworkDevices.Device("192.168.1.20", name, services.toList(), false)
    @Test fun cameraModelsAndSerialsAreSecondaryWithoutGrantingSetup() {
        val d = device("HIKVISION DS-2CD2086G2-IU - DEMO123456", "_http._tcp.")
        val info = NetworkDevicePresentation.describe(d)
        assertEquals("Hikvision camera", info.title)
        assertEquals(NetworkDevicePresentation.Kind.CAMERA, info.kind)
        assertEquals("DS-2CD2086G2-IU", info.model)
        assertEquals("DEMO123456", info.identifier)
        assertFalse(d.cameraCandidate)
    }
    @Test fun unknownIdentifiersBecomeSecondaryToTheServiceBasedType() {
        val info = NetworkDevicePresentation.describe(device("ABCDEF0123456789ABCDEF12", "_googlecast._tcp."))
        assertEquals("Cast device", info.title)
        assertEquals("ABCDEF0123456789ABCDEF12", info.identifier)
        assertEquals(NetworkDevicePresentation.Kind.CAST, info.kind)
    }
    @Test fun speakersLoseMachinePrefixesWithoutLosingTheName() {
        val info = NetworkDevicePresentation.describe(device("AABBCCDDEEFFDenon Home 350", "_spotify-connect._tcp."))
        assertEquals("Denon Home 350", info.title)
        assertEquals("AABBCCDDEEFF", info.identifier)
        assertEquals(NetworkDevicePresentation.Kind.SPEAKER, info.kind)
    }
    @Test fun computersAreNotMistakenForSpeakersBecauseTheyAdvertiseAirplay() {
        assertEquals(NetworkDevicePresentation.Kind.COMPUTER,
            NetworkDevicePresentation.describe(device("Office iMac", "_raop._tcp.")).kind)
        assertEquals(NetworkDevicePresentation.Kind.TV,
            NetworkDevicePresentation.describe(device("Living room TV", "_googlecast._tcp.")).kind)
        assertEquals(NetworkDevicePresentation.Kind.PRINTER,
            NetworkDevicePresentation.describe(device("Office", "_ipp._tcp.")).kind)
    }
    @Test fun standardRemoteUseCasesSortAboveUnidentifiedWebServices() {
        val unknown = device("192.168.1.20", "_http._tcp.")
        for (d in listOf(device("WiiM Amp", "_linkplay._tcp."), device("Tapo C210", "tapo_camera"),
            device("Office", "_ipps._tcp."), device("TP-Link Deco M4R", "tplink_router"))) {
            assertTrue(NetworkDevicePresentation.remotePriority(d) < NetworkDevicePresentation.remotePriority(unknown))
        }
        assertEquals(NetworkDevicePresentation.Kind.SPEAKER, NetworkDevicePresentation.describe(device("WiiM Amp", "_linkplay._tcp.")).kind)
        assertEquals(NetworkDevicePresentation.Kind.ROUTER, NetworkDevicePresentation.describe(device("", "urn:schemas-upnp-org:device:InternetGatewayDevice:1")).kind)
    }
    @Test fun explicitTypesTakePriorityOverAmbiguousBrands() {
        assertEquals(NetworkDevicePresentation.Kind.SMART,
            NetworkDevicePresentation.describe(device("Tapo P110", "tplink_smart_device")).kind)
        assertEquals(NetworkDevicePresentation.Kind.TV,
            NetworkDevicePresentation.describe(device("Android TV", "_androidtvremote2._tcp.")).kind)
    }
    @Test fun appleModelCodesAndDesktopServicesAreNotClassifiedAsSpeakers() {
        val mac = NetworkDevices.Device("192.168.1.20", "Travel machine", listOf("_airplay._tcp."), false, listOf("Mac14,15"))
        assertEquals(NetworkDevicePresentation.Kind.COMPUTER, NetworkDevicePresentation.describe(mac).kind)
        assertEquals("MacBook Air (15-inch, M2, 2023)", DeviceModelNames.label("Mac14,15"))
        assertEquals("MacBook Air (M2, 2022)", DeviceModelNames.label("Mac14,2"))
        assertEquals("Unknown42,1", DeviceModelNames.label("Unknown42,1"))
        assertEquals(NetworkDevicePresentation.Kind.COMPUTER, NetworkDevicePresentation.describe(device("Workstation", "_airplay._tcp.", "_rfb._tcp.")).kind)
    }
    @Test fun missingNamesHaveAnHonestFallbackAndReadableNamesStayIntact() {
        assertEquals("Network device", NetworkDevicePresentation.describe(device("192.168.1.20", "_http._tcp.")).title)
        assertEquals("Kitchen", NetworkDevicePresentation.describe(device("Kitchen", "_http._tcp.")).title)
    }
    @Test fun switchesAndAccessPointsGroupWithRouters() {
        listOf("NETGEAR GS308E switch", "UniFi USW-Lite-8", "TP-Link TL-SG108E", "Office access point").forEach { name ->
            val kind = NetworkDevicePresentation.describe(device(name, "_http._tcp.")).kind
            assertEquals(name, NetworkDevicePresentation.Kind.ROUTER, kind)
            assertEquals("Routers & switches", NetworkDevicePresentation.category(kind).title)
        }
        assertNotEquals(NetworkDevicePresentation.Kind.ROUTER, NetworkDevicePresentation.describe(device("Nintendo Switch", "_http._tcp.")).kind)
    }
}
