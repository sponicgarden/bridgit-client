import XCTest
@testable import BridgitCore

final class LanDiscoverySessionTests: XCTestCase {
    private let network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
    func testPartialMethodFailureIsExplicitAndNeverComplete() async throws {
        let network = network
        let session = LanDiscoverySession { method, context in
            if method == .onvif { throw DeviceAPIError(503, "lan_discovery_unavailable", "No permission") }
            if method == .mdns { await context.observations.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.") }
            XCTAssertEqual(context.targets, ["192.168.2.2"])
        }
        let result = try await session.scan(hosts: ["192.168.2.2"], authorize: {}, currentNetwork: { network })
        XCTAssertFalse(result.complete); XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.methods.count, 5)
        XCTAssertEqual(result.methods.filter { $0.status == "unavailable" }.map(\.method), ["onvif"])
        XCTAssertEqual(result.observations.count, 1)
    }
    func testDeadlineCancelsWorkersAndReleasesOperationLock() async throws {
        let network = network
        actor State {
            var stopped = 0
            var delay = true
            func stop() { stopped += 1 }
            func finishDelaying() { delay = false }
        }
        let state = State()
        let session = LanDiscoverySession(deadline: .milliseconds(30)) { _, _ in
            if await state.delay {
                do { try await Task.sleep(for: .seconds(10)) }
                catch { await state.stop(); throw error }
            }
        }
        do {
            _ = try await session.scan(authorize: {}, currentNetwork: { network })
            XCTFail("Deadline must not return a success")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_timeout") }
        let stopped = await state.stopped
        XCTAssertEqual(stopped, 5)
        await state.finishDelaying()
        let result = try await session.scan(authorize: {}, currentNetwork: { network })
        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.complete)
    }
    func testRevocationIsNotConvertedToUnavailableMethod() async throws {
        let network = network
        let session = LanDiscoverySession { _, _ in throw DeviceAPIError(403, "agent_revoked", "Revoked") }
        do {
            _ = try await session.scan(authorize: {}, currentNetwork: { network })
            XCTFail("Revocation must fail the entire operation")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }
    func testInvalidTargetsNeverInvokeDiscovery() async throws {
        let network = network
        let session = LanDiscoverySession { _, _ in XCTFail("Invalid input must not start discovery") }
        do {
            _ = try await session.scan(hosts: ["8.8.8.8"], authorize: {}, currentNetwork: { network })
            XCTFail("Routed targets must fail")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_parameter") }
    }
}
