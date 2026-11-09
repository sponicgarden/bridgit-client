import XCTest
import Network
@testable import BridgitCore

final class LanInterfaceSelectionTests: XCTestCase {
    private func candidate(_ index: UInt32 = 4, _ address: String = "192.168.2.1",
                           physical: Bool = true, up: Bool = true, mask: UInt32 = 0xffffff00) -> LanInterfaceSelection.Candidate {
        .init(name: "fixture\(index)", index: index, address: address, netmask: mask, physical: physical, up: up)
    }

    func testDoesNotSelectVPNCellularInactiveOrPublicInterfaces() throws {
        let selected = try LanInterfaceSelection.select([
            candidate(1, "10.8.0.1", physical: false), candidate(2, up: false),
            candidate(3, "8.8.8.8"), candidate()
        ])
        XCTAssertEqual(selected.index, 4)
        XCTAssertEqual(selected.address, "192.168.2.1")
        XCTAssertEqual(selected.prefix, 24)
        XCTAssertThrowsError(try LanInterfaceSelection.select([candidate(1, "10.8.0.1", physical: false)]))
    }

    func testRefusesAmbiguousPhysicalNetworksButNotAnAliasOnOneInterface() throws {
        XCTAssertThrowsError(try LanInterfaceSelection.select([candidate(), candidate(5, "10.0.0.1")]))
        let selected = try LanInterfaceSelection.select([candidate(), candidate(4, "192.168.2.2")])
        XCTAssertEqual(selected.address, "192.168.2.1")
        XCTAssertThrowsError(try LanInterfaceSelection.select([]))
    }

    func testInvalidNetmaskCannotBroadenDiscovery() {
        for mask: UInt32 in [0, 0xff00ff00, 0xffffff01] {
            XCTAssertThrowsError(try LanInterfaceSelection.select([candidate(mask: mask)]))
        }
        XCTAssertThrowsError(try LanInterfaceSelection.select([candidate(0)]))
    }
    func testGatewayMetadataUsesOnlyLiteralOnLinkIPv4AndDeduplicates() throws {
        let selected = try LanInterfaceSelection.select([candidate()])
        func ipv4(_ value: String) -> NWEndpoint {
            .hostPort(host: .ipv4(IPv4Address(value)!), port: 0)
        }
        let result = selected.withGateways([
            ipv4("192.168.2.254"), ipv4("192.168.2.3"), ipv4("192.168.2.254"),
            ipv4("192.168.3.1"), ipv4("8.8.8.8"), ipv4("192.168.2.0"),
            ipv4("192.168.2.255"), ipv4("192.168.2.1"),
            .hostPort(host: .name("router.local", nil), port: 0),
            .hostPort(host: .ipv6(IPv6Address("fe80::1")!), port: 0)
        ])
        XCTAssertEqual(result.gateways, ["192.168.2.254", "192.168.2.3"])
        XCTAssertEqual(result.address, selected.address)
        XCTAssertEqual(result.index, selected.index)
        XCTAssertTrue(selected.withGateways([]).gateways.isEmpty)
    }

}
