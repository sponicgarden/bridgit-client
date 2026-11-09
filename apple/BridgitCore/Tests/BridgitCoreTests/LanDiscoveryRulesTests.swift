import XCTest
@testable import BridgitCore

final class LanDiscoveryRulesTests: XCTestCase {
    func testRejectsNamesURLsAlternateNumericFormsAndNonPrivateAddresses() {
        for address in ["localhost", "https://192.168.1.2", "192.168.1.2:80", "192.168.1.02",
                        " 192.168.1.2", "192.168.1.2\n", "192.168.1", "192.168.1.2.",
                        "192.168.1.256", "１２７.0.0.1", "127.0.0.1", "169.254.1.2",
                        "100.64.1.2", "8.8.8.8", "172.15.1.2", "172.32.1.2", "::1"] {
            XCTAssertNil(LanDiscoveryRules.privateIPv4(address), address)
        }
        for address in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.255.254"] {
            XCTAssertEqual(LanDiscoveryRules.privateIPv4(address).map(LanDiscoveryRules.host), address)
        }
    }

    func testOnLinkRejectsSelfNetworkBroadcastAndOtherSubnets() {
        for address in ["192.168.2.0", "192.168.2.1", "192.168.2.255", "192.168.3.2", "10.0.0.2"] {
            XCTAssertFalse(LanDiscoveryRules.onLink(address, local: "192.168.2.1", prefix: 24), address)
        }
        XCTAssertTrue(LanDiscoveryRules.onLink("192.168.2.254", local: "192.168.2.1", prefix: 24))
        for prefix in [-1, 0, 31, 32, 100] {
            XCTAssertFalse(LanDiscoveryRules.onLink("192.168.2.2", local: "192.168.2.1", prefix: prefix))
        }
    }

    func testSubnetBoundsAndExplicitLargeNetworkTargets() throws {
        let hosts = try LanDiscoveryRules.boundedHosts(local: "10.1.2.1", prefix: 24)
        XCTAssertEqual(hosts.count, 253)
        XCTAssertEqual(hosts.first, "10.1.2.2")
        XCTAssertEqual(hosts.last, "10.1.2.254")
        XCTAssertEqual(try LanDiscoveryRules.boundedHosts(local: "10.1.2.1", prefix: 30), ["10.1.2.2"])
        XCTAssertThrowsError(try LanDiscoveryRules.boundedHosts(local: "10.1.2.1", prefix: 16))
        XCTAssertEqual(try LanDiscoveryRules.targets(hosts: ["10.1.3.2"], subnet: false, local: "10.1.2.1", prefix: 16), ["10.1.3.2"])
        XCTAssertThrowsError(try LanDiscoveryRules.targets(hosts: ["10.2.3.2"], subnet: false, local: "10.1.2.1", prefix: 16))
        XCTAssertThrowsError(try LanDiscoveryRules.targets(hosts: Array(repeating: "10.1.2.2", count: 255), subnet: true, local: "10.1.2.1", prefix: 24))
    }
}
