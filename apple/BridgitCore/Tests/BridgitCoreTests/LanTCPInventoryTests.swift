import Darwin
import XCTest
@testable import BridgitCore

final class LanTCPInventoryTests: XCTestCase {
    private let network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
    private actor State {
        var calls: [UInt16] = []
        var revoked = false
        var changed = false
        func probe(_ port: UInt16, revoke: Bool = false, change: Bool = false) -> Bool {
            calls.append(port); revoked = revoke; changed = change; return true
        }
        func authorize() throws { if revoked { throw DeviceAPIError(403, "agent_revoked", "Revoked") } }
    }

    func testOnlyFixedPortsAndUniqueOnLinkHostsAreProbed() async throws {
        let network = network, state = State(), observations = LanDiscoveryAccumulator(network: network)
        try await LanTCPInventory.scan(network: network, hosts: ["192.168.2.2", "192.168.2.2"], observations: observations,
            authorize: {}, currentNetwork: { network }, probe: { _, _, port in await state.probe(port) })
        let calls = await state.calls, values = await observations.snapshot()
        XCTAssertEqual(calls, [80, 443, 554, 2020])
        XCTAssertEqual(values.count, 4)
        XCTAssertTrue(values.allSatisfy { !$0.verified && !$0.enrolled && $0.source == .tcp })
    }

    func testRevokedAgentCannotAdmitPendingResultOrStartAnotherPort() async throws {
        let network = network, state = State(), observations = LanDiscoveryAccumulator(network: network)
        do {
            try await LanTCPInventory.scan(network: network, hosts: ["192.168.2.2"], observations: observations,
                authorize: { try await state.authorize() }, currentNetwork: { network },
                probe: { _, _, port in await state.probe(port, revoke: true) })
            XCTFail("Revocation must stop discovery")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let calls = await state.calls, values = await observations.snapshot()
        XCTAssertEqual(calls, [80]); XCTAssertTrue(values.isEmpty)
    }

    func testNetworkChangeDiscardsPendingObservation() async throws {
        let network = network, state = State(), observations = LanDiscoveryAccumulator(network: network)
        do {
            try await LanTCPInventory.scan(network: network, hosts: ["192.168.2.2"], observations: observations,
                authorize: {}, currentNetwork: {
                    if await state.changed { return .init(name: "new", index: 5, address: "10.0.0.1", prefix: 24) }
                    return network
                }, probe: { _, _, port in await state.probe(port, change: true) })
            XCTFail("Changed network must stop discovery")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_changed") }
        let values = await observations.snapshot(); XCTAssertTrue(values.isEmpty)
    }

    func testRealSocketConnectsWithoutPayloadAndDoesNotFallbackFromInvalidInterface() throws {
        let loopback = if_nametoindex("lo0")
        XCTAssertNotEqual(loopback, 0)
        let listener = try boundLoopbackSocket()
        defer { close(listener.descriptor) }
        XCTAssertEqual(listen(listener.descriptor, 1), 0)
        let connected = try LanTCPInventory.connectSocket(local: 0x7f000001, target: 0x7f000001,
                                                          interfaceIndex: loopback, port: listener.port)
        XCTAssertTrue(connected)
        guard connected else { return }
        let accepted = accept(listener.descriptor, nil, nil)
        XCTAssertGreaterThanOrEqual(accepted, 0)
        guard accepted >= 0 else { return }
        defer { close(accepted) }
        var timeout = timeval(tv_sec: 0, tv_usec: 200_000)
        XCTAssertEqual(setsockopt(accepted, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout.size(ofValue: timeout))), 0)
        var byte: UInt8 = 0
        XCTAssertEqual(recv(accepted, &byte, 1, 0), 0, "Inventory must close without sending application data")
        XCTAssertThrowsError(try LanTCPInventory.connectSocket(local: 0x7f000001, target: 0x7f000001,
                                                              interfaceIndex: UInt32.max, port: listener.port))
        // A bound but non-listening socket reserves this port and reliably refuses TCP.
        let closed = try boundLoopbackSocket()
        defer { close(closed.descriptor) }
        XCTAssertFalse(try LanTCPInventory.connectSocket(local: 0x7f000001, target: 0x7f000001,
                                                        interfaceIndex: loopback, port: closed.port))
    }

    func testPublicDiscoveryEntryRejectsLoopbackBeforeAnySocket() async {
        do {
            _ = try await LanTCPInventory.connect(network: network, host: "127.0.0.1", port: 80)
            XCTFail("Loopback must never be a discovery target")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_parameter") }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    private func boundLoopbackSocket() throws -> (descriptor: Int32, port: UInt16) {
        let descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard descriptor >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr = in_addr(s_addr: UInt32(0x7f000001).bigEndian)
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard result == 0 else { let error = errno; close(descriptor); throw NSError(domain: NSPOSIXErrorDomain, code: Int(error)) }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(descriptor, $0, &length) }
        }
        guard named == 0 else { let error = errno; close(descriptor); throw NSError(domain: NSPOSIXErrorDomain, code: Int(error)) }
        return (descriptor, UInt16(bigEndian: address.sin_port))
    }
}
