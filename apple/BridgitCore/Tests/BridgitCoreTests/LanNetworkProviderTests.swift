import XCTest
@testable import BridgitCore

final class LanNetworkProviderTests: XCTestCase {
    private let network = LanInterfaceSelection(name: "en0", index: 4, address: "192.168.1.2", prefix: 24)
    func testUnknownAndUnavailableNetworkNeverReturnOldSnapshot() throws {
        let provider = LanNetworkProvider(testing: ())
        XCTAssertThrowsError(try provider.snapshot())
        provider.accept(.success(network))
        let first = try provider.snapshot()
        XCTAssertEqual(try provider.require(first), network)
        provider.accept(.failure(DeviceAPIError(409, "lan_unavailable", "Disconnected")))
        XCTAssertThrowsError(try provider.snapshot())
        XCTAssertThrowsError(try provider.require(first))
    }
    func testSameAddressAfterPathChangeCannotReuseScopeOrInFlightScan() throws {
        let provider = LanNetworkProvider(testing: ())
        provider.accept(.success(network)); let first = try provider.snapshot()
        provider.accept(.success(network)); let second = try provider.snapshot()
        XCTAssertEqual(first.network, second.network)
        XCTAssertNotEqual(first.scope, second.scope)
        XCTAssertThrowsError(try provider.require(first)) { error in
            XCTAssertEqual((error as? DeviceAPIError)?.code, "lan_changed")
        }
        XCTAssertEqual(try provider.require(second), network)
    }
    func testProcessProvidersNeverMergeIdenticalPrivateNetworks() throws {
        let first = LanNetworkProvider(testing: ()), second = LanNetworkProvider(testing: ())
        first.accept(.success(network)); second.accept(.success(network))
        XCTAssertNotEqual(try first.snapshot().scope, try second.snapshot().scope)
    }
    func testAuthorizedHistorySurvivesProviderRecreationButNotDeliveryGeneration() throws {
        let identity = LanHistoryIdentity(bssid: "10:20:30:40:50:60", gateways: ["192.168.1.1"])
        let first = LanNetworkProvider(testing: ()), second = LanNetworkProvider(testing: ())
        first.accept(.success(network), identity: identity)
        second.accept(.success(network), identity: identity)
        let old = try first.snapshot(), fresh = try second.snapshot()
        XCTAssertEqual(old.historyScope, fresh.historyScope)
        XCTAssertNotEqual(old.scope, fresh.scope)
        XCTAssertFalse(old.historyScope.contains(identity.bssid))
        first.accept(.success(network), identity: identity)
        XCTAssertEqual(try first.snapshot().historyScope, old.historyScope)
        XCTAssertThrowsError(try first.require(old))
        first.accept(.success(network), identity: .init(bssid: "10:20:30:40:50:61", gateways: ["192.168.1.1"]))
        XCTAssertNotEqual(try first.snapshot().historyScope, old.historyScope)
    }

    func testUnknownRedactedOrIncompleteIdentityUsesOnlyConnectionLifetime() throws {
        let provider = LanNetworkProvider(testing: ())
        for identity in [
            LanHistoryIdentity(bssid: "02:00:00:00:00:00", gateways: []),
            .init(bssid: "00:00:00:00:00:00", gateways: []),
            .init(bssid: "ff:ff:ff:ff:ff:ff", gateways: []),
            .init(bssid: "not-an-access-point", gateways: []),
            .init(bssid: "10:20:30:40:50:60", gateways: nil),
            .init(bssid: "10:20:30:40:50:60", gateways: ["192.168.2.1"])
        ] {
            provider.accept(.success(network), identity: identity)
            let snapshot = try provider.snapshot()
            XCTAssertEqual(snapshot.historyScope, snapshot.scope)
        }
        let known = LanHistoryIdentity(bssid: "10:20:30:40:50:60", gateways: [])
        provider.accept(.success(network), identity: known)
        let authorized = try provider.snapshot()
        provider.accept(.success(network))
        XCTAssertNotEqual(try provider.snapshot().historyScope, authorized.historyScope)
        XCTAssertThrowsError(try provider.require(authorized))
    }

    func testIdentityRefreshIsStableAndPermissionLossInvalidatesDelivery() async throws {
        actor Reader {
            var allowed = true
            func revoke() { allowed = false }
            func read() -> LanHistoryIdentity? {
                allowed ? .init(bssid: "10:20:30:40:50:60", gateways: ["192.168.1.1"]) : nil
            }
        }
        let reader = Reader()
        let provider = LanNetworkProvider(testing: (), readIdentity: { _ in await reader.read() })
        provider.accept(.success(network))
        try await provider.refreshIdentity()
        let authorized = try provider.snapshot()
        XCTAssertTrue(authorized.historyScope.hasPrefix("wifi:"))
        try await provider.refreshIdentity()
        XCTAssertEqual(try provider.snapshot(), authorized)
        await reader.revoke()
        try await provider.refreshIdentity()
        let revoked = try provider.snapshot()
        XCTAssertEqual(revoked.historyScope, revoked.scope)
        XCTAssertNotEqual(revoked.scope, authorized.scope)
        XCTAssertThrowsError(try provider.require(authorized))
    }

    func testLateIdentityResultCannotAttachPreviousAccessPointToNewPath() async throws {
        actor Reader {
            var pending: CheckedContinuation<LanHistoryIdentity?, Never>?
            func read(_ started: XCTestExpectation) async -> LanHistoryIdentity? {
                await withCheckedContinuation { continuation in
                    pending = continuation
                    started.fulfill()
                }
            }
            func finish() {
                pending?.resume(returning: .init(bssid: "10:20:30:40:50:60", gateways: ["192.168.1.1"]))
                pending = nil
            }
        }
        let started = expectation(description: "Identity lookup started")
        let reader = Reader()
        let provider = LanNetworkProvider(testing: (), readIdentity: { _ in await reader.read(started) })
        provider.accept(.success(network))
        let lookup = Task { try await provider.refreshIdentity() }
        await fulfillment(of: [started], timeout: 2)
        provider.accept(.success(network))
        let replacement = try provider.snapshot()
        await reader.finish()
        try await lookup.value
        XCTAssertEqual(try provider.snapshot(), replacement)
        XCTAssertEqual(replacement.historyScope, replacement.scope)
    }

}
