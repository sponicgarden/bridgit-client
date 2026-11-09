import XCTest
@testable import BridgitCore

final class LanDiscoveryReportTests: XCTestCase {
    func testInventorySchemaCountsTypesAndHonestInterfaceState() throws {
        let report = LanDiscoveryReport(catalog: try .init())
        var mac = LanDiscoveredDevice(host: "192.168.1.2", name: "Travel machine", services: ["_ssh._tcp."], cameraCandidate: false, modelHints: ["Mac14,15"])
        mac.connected = false; mac.lastSeen = 12345
        let camera = LanDiscoveredDevice(host: "192.168.1.3", name: "Tapo C210", services: ["tapo_camera"], cameraCandidate: true, modelHints: [])
        let result = report.enrich(["complete": false, "observations": [], "methods": []], devices: [mac,camera])
        XCTAssertTrue(JSONSerialization.isValidJSONObject(result))
        XCTAssertEqual(result["total_devices"] as? Int, 2)
        XCTAssertEqual(result["connected_devices"] as? Int, 1)
        XCTAssertEqual(result["unconnected_devices"] as? Int, 1)
        let categories = try XCTUnwrap(result["categories"] as? [[String: Any]])
        XCTAssertEqual(categories.compactMap { $0["id"] as? String }, ["cameras", "computers"])
        let rows = try XCTUnwrap(result["devices"] as? [[String: Any]])
        let computer = try XCTUnwrap(rows.first { $0["type"] as? String == "computer" })
        XCTAssertEqual(computer["model"] as? String, "MacBook Air (15-inch, M2, 2023)")
        XCTAssertEqual(computer["last_seen"] as? Int64, 12345)
        XCTAssertEqual(computer["connection_state"] as? String, "not_seen")
        for row in rows {
            XCTAssertEqual(row["verified"] as? Bool, false)
            XCTAssertEqual(row["enrolled"] as? Bool, false)
            XCTAssertEqual(row["bridgit_interfaced"] as? Bool, false)
            XCTAssertTrue(row["interface"] is NSNull)
        }
    }
    func testOnlyRegisteredAdapterIsPresentedWithoutEnrollmentClaim() throws {
        var report = LanDiscoveryReport(catalog: try .init())
        let device = LanDiscoveredDevice(host: "192.168.1.2", name: "Workstation", services: ["_ssh._tcp."], cameraCandidate: false, modelHints: [])
        // Fixture registration, not a claim that Apple SSH has been implemented.
        report.interfaces[device.host] = .init(adapter: "fixture", label: "Fixture", setupRequired: true, enrollment: "/fixture/enroll")
        let rows = try XCTUnwrap(report.enrich([:], devices: [device])["devices"] as? [[String: Any]])
        XCTAssertEqual(rows.first?["bridgit_interfaced"] as? Bool, true)
        XCTAssertEqual(rows.first?["enrolled"] as? Bool, false)
        XCTAssertEqual((rows.first?["interface"] as? [String: Any])?["adapter"] as? String, "fixture")
    }
    func testCoverageCatalogIsJSONAndRetainsPinnedMethodsAndServiceCount() throws {
        let coverage = LanDiscoveryReport(catalog: try .init()).coverage
        XCTAssertTrue(JSONSerialization.isValidJSONObject(coverage))
        XCTAssertEqual(coverage["methods"] as? [String], ["mdns", "ssdp", "onvif", "tplink"])
        XCTAssertEqual((coverage["services"] as? [[String: String]])?.count, 30)
        XCTAssertTrue((coverage["agent_guidance"] as? String)?.contains("on this host") == true)
    }
}
