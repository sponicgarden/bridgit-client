import XCTest
import BridgitCore
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

final class NetworkInventoryTests: XCTestCase {
    @MainActor func testBundledBrandLogoLoadsAndUnknownBrandUsesFallback() throws {
        XCTAssertFalse(BrandCatalog.shared.entries.isEmpty)
        let rendered = BrandCatalog.shared.entries.compactMap { BrandImages.image(for: $0.id) }
        XCTAssertFalse(rendered.isEmpty, "At least one bundled logo must be available on each platform")
        XCTAssertTrue(rendered.allSatisfy { $0.width > 0 && $0.height > 0 })
        XCTAssertNil(BrandImages.image(for: "unknown-fixture-brand"))
    }

    func testCoverageGroupsPreserveOrderAndDeduplicateServiceLabels() throws {
        let data = Data(#"{"methods":["mdns"],"services":[{"group":"Audio","label":"AirPlay"},{"group":"Audio","label":"AirPlay"},{"group":"Cameras","label":"Camera"},{"group":"Audio","label":"Cast"}]}"#.utf8)
        let coverage = try JSONDecoder().decode(NetworkCoverage.self, from: data)
        XCTAssertEqual(coverage.groups.map(\.title), ["Audio", "Cameras"])
        XCTAssertEqual(coverage.groups[0].labels, ["AirPlay", "Cast"])
    }

    func testOwnerCoverageResponseDecodesIntoPresentedGroups() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let service = try HostLanService(directory: directory, enabled: { false })
        let coverage = try JSONDecoder().decode(NetworkCoverage.self, from: await service.ownerCoverage().data)
        XCTAssertEqual(coverage.groups.first?.title, "Audio & video")
        XCTAssertEqual(coverage.groups.first?.labels.filter { $0 == "AirPlay" }.count, 1)
        XCTAssertEqual(coverage.otherDiscovery, ["TP-Link / Tapo", "UPnP / DLNA and router announcements", "ONVIF cameras"])
    }

    func testUnknownReachabilityIsNotDisconnectedAndMapKeepsDeviceType() throws {
        let categories = NetworkInventory.parseLan(["devices": [
            ["id": "192.168.1.2", "name": "Desk", "type": "computer", "category": "computers"],
            ["id": "192.168.1.3", "name": "Camera", "type": "camera", "connected": false]
        ]])
        let devices = NetworkInventory.devices(in: categories)
        XCTAssertEqual(devices.count, 2)
        XCTAssertFalse(devices[0].disconnected)
        XCTAssertEqual(devices[0].connectionDescription, "Not checked")
        XCTAssertTrue(devices[1].disconnected)
        let map = DevicesMapOptions.build(categories: categories, added: [], core: "This Mac")
        let rows = try XCTUnwrap(map["devices"] as? [[String: Any]])
        XCTAssertEqual(rows[0]["kind"] as? String, "computer")
        XCTAssertEqual(rows[0]["group"] as? String, "COMPUTERS")
        XCTAssertEqual(rows[0]["state"] as? String, "blocked")
        XCTAssertEqual(rows[1]["state"] as? String, "off")
        XCTAssertEqual(map["core"] as? String, "This Mac")
    }

    func testListFiltersDoNotInventOfflineStateForUnknownDevices() {
        let categories = NetworkInventory.parseLan(["devices": [
            ["id": "known-online", "connected": true],
            ["id": "known-offline", "connected": false],
            ["id": "unreported"]
        ]])
        let devices = NetworkInventory.devices(in: categories)
        XCTAssertEqual(ListMode.all.visible(devices).count, 3)
        XCTAssertEqual(ListMode.groups.visible(devices).count, 3)
        XCTAssertEqual(ListMode.connected.visible(devices).map(\.id), ["known-online"])
        XCTAssertEqual(ListMode.unconnected.visible(devices).map(\.id), ["known-offline"])
    }

    func testAddedDeviceAppearsOnceAheadOfRememberedObservation() throws {
        let payload: [String: Any] = [
            "network": ["address": "192.168.1.1", "prefix_length": 24],
            "devices": [["id": "camera", "host": "192.168.1.2", "connected": true]],
            "added": [["id": "lan:camera", "host": "192.168.1.2", "name": "Front", "kind": "camera", "state": "on", "applets": ["Pet monitor"]]]
        ]
        let added = try XCTUnwrap(NetworkInventory.parseAdded(payload))
        XCTAssertEqual(added[0].applets, ["Pet monitor"])
        XCTAssertEqual(NetworkInventory.parseNetwork(payload), "192.168.1.1/24")
        let map = DevicesMapOptions.build(categories: NetworkInventory.parseLan(payload), added: added)
        let rows = try XCTUnwrap(map["devices"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0]["id"] as? String, "lan:camera")
        XCTAssertEqual(rows[0]["linked"] as? Bool, true)
        XCTAssertEqual(rows[0]["group"] as? String, "CAMERAS")
    }
}
