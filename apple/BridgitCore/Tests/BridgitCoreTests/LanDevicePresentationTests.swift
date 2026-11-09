import XCTest
@testable import BridgitCore

/// Cases from pinned Android NetworkDevicePresentationTest, not inferred from
/// Apple output. Model-code display names remain a separate catalog task.
final class LanDevicePresentationTests: XCTestCase {
    private func device(_ name: String, _ services: [String], host: String = "192.168.1.20", models: [String] = []) -> LanDiscoveredDevice {
        .init(host: host, name: name, services: services, cameraCandidate: false, modelHints: models)
    }
    func testPinnedAndroidTitlesModelsAndIdentifiers() {
        let cases: [(LanDiscoveredDevice, LanDevicePresentation.Info)] = [
            (device("HIKVISION DS-2CD2086G2-IU - DEMO123456", ["_http._tcp."]), .init(title: "Hikvision camera", kind: .camera, model: "DS-2CD2086G2-IU", identifier: "DEMO123456")),
            (device("ABCDEF0123456789ABCDEF12", ["_googlecast._tcp."]), .init(title: "Cast device", kind: .cast, model: "", identifier: "ABCDEF0123456789ABCDEF12")),
            (device("AABBCCDDEEFFDenon Home 350", ["_spotify-connect._tcp."]), .init(title: "Denon Home 350", kind: .speaker, model: "", identifier: "AABBCCDDEEFF")),
            (device("192.168.1.20", ["_http._tcp."]), .init(title: "Network device", kind: .unknown, model: "", identifier: "")),
            (device("Kitchen", ["_http._tcp."]), .init(title: "Kitchen", kind: .unknown, model: "", identifier: "")),
            (device("Tapo C210", ["tapo_camera"]), .init(title: "Tapo C210", kind: .camera, model: "C210", identifier: ""))]
        for (input, expected) in cases {
            XCTAssertEqual(LanDevicePresentation.describe(input), expected, input.name)
            XCTAssertFalse(input.cameraCandidate, "Classification cannot grant an adapter")
        }
    }
    func testPinnedAndroidTypePrecedenceAndNetworkSwitches() {
        let cases: [(String, [String], LanDevicePresentation.Kind)] = [
            ("Office iMac", ["_raop._tcp."], .computer),
            ("Living room TV", ["_googlecast._tcp."], .tv),
            ("Office", ["_ipp._tcp."], .printer),
            ("WiiM Amp", ["_linkplay._tcp."], .speaker),
            ("", ["urn:schemas-upnp-org:device:InternetGatewayDevice:1"], .router),
            ("Tapo P110", ["tplink_smart_device"], .smart),
            ("Android TV", ["_androidtvremote2._tcp."], .tv),
            ("Workstation", ["_airplay._tcp.", "_rfb._tcp."], .computer)]
        for (name, services, expected) in cases { XCTAssertEqual(LanDevicePresentation.describe(device(name, services)).kind, expected, name) }
        XCTAssertEqual(LanDevicePresentation.describe(device("Travel machine", ["_airplay._tcp."], models: ["Mac14,15"])).kind, .computer)
        for name in ["NETGEAR GS308E switch", "UniFi USW-Lite-8", "TP-Link TL-SG108E", "Office access point"] {
            XCTAssertEqual(LanDevicePresentation.describe(device(name, ["_http._tcp."])).kind, .router)
        }
        XCTAssertNotEqual(LanDevicePresentation.describe(device("Nintendo Switch", ["_http._tcp."])).kind, .router)
    }
    func testGroupsKeepAddedThenSupportedDevicesFirstWithoutInventingSupport() throws {
        let a = device("A", ["_ssh._tcp."], host: "192.168.1.2")
        let b = device("B", ["_ssh._tcp."], host: "192.168.1.3")
        var c = device("C", ["_ssh._tcp."], host: "192.168.1.4"); c.connected = false; c.present = false
        let grouped = LanDevicePresentation.groups([a,b,c], added: [c.host], supported: [b.host])
        XCTAssertEqual(grouped.first?.category, .computers)
        XCTAssertEqual(grouped.first?.devices.map(\.host), [c.host,b.host,a.host])
        XCTAssertFalse(try XCTUnwrap(grouped.first).category.expandedByDefault)
        XCTAssertEqual(LanDevicePresentation.Category.network.title, "Routers & switches")
        XCTAssertEqual(LanDevicePresentation.groups([a,b]).first?.devices.map(\.host), [a.host,b.host])
    }
}
