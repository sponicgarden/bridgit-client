import XCTest
@testable import BridgitCore

final class LanBonjourDiscoveryTests: XCTestCase {
    func testAdmissionCapsPerTypeAndTotalAndDeduplicates() async {
        let budget = LanBonjourDiscovery.Budget()
        var accepted = 0
        for type in 0..<12 {
            for name in 0..<40 {
                let service = LanBonjourOperation.Service(name: "n\(name)", type: "t\(type)", domain: "local.", interface: 4)
                if await budget.admit(service) { accepted += 1 }
                let duplicate = await budget.admit(service)
                XCTAssertFalse(duplicate)
            }
        }
        XCTAssertEqual(accepted, 192)
    }

    private actor State {
        var types = Set<String>()
        var resolved = 0
        var revoked = false
        func browsed(_ type: String) { types.insert(type) }
        func resolving(revoke: Bool) { resolved += 1; revoked = revoke }
        func authorize() throws {
            if revoked { throw DeviceAPIError(403, "agent_revoked", "Revoked") }
        }
    }

    func testFixedCatalogAndInterfaceWithOffLinkResultsFiltered() async throws {
        let state = State(), network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let observations = LanDiscoveryAccumulator(network: network)
        try await LanBonjourDiscovery.scan(network: network, observations: observations, authorize: {}, currentNetwork: { network }, browse: { type, index in
            AsyncThrowingStream { continuation in
                let task = Task {
                    await state.browsed(type)
                    continuation.yield(.service(.init(name: "device", type: type, domain: "local.", interface: index)))
                    continuation.yield(.service(.init(name: "wrong", type: type, domain: "local.", interface: 99)))
                    try? await Task.sleep(nanoseconds: 20_000_000)
                    continuation.finish()
                }
                continuation.onTermination = { _ in task.cancel() }
            }
        }, resolve: { service in
            await state.resolving(revoke: false)
            XCTAssertEqual(service.interface, 4)
            return .init(resolution: .init(host: "device.local.", port: 80, attributes: ["fn": Data("Friendly".utf8), "md": Data("Model".utf8)], interface: 4), addresses: ["192.168.2.2", "8.8.8.8", "192.168.2.1"])
        })
        let types = await state.types, values = await observations.snapshot()
        XCTAssertEqual(types, Set(LanDiscoveryCatalog.services.map(\.type)))
        XCTAssertEqual(values.count, 30)
        XCTAssertTrue(values.allSatisfy { $0.host == "192.168.2.2" && !$0.verified && !$0.enrolled })
        let cast = values.first { $0.service == "_googlecast._tcp." }
        XCTAssertEqual(cast?.name, "Friendly"); XCTAssertEqual(cast?.advertisedModel, "Model")
    }

    func testRevocationDiscardsInFlightResolution() async throws {
        let state = State(), network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let observations = LanDiscoveryAccumulator(network: network)
        do {
            try await LanBonjourDiscovery.scan(network: network, observations: observations,
                authorize: { try await state.authorize() }, currentNetwork: { network }, browse: { type, index in
                    AsyncThrowingStream { continuation in
                        continuation.yield(.service(.init(name: "device", type: type, domain: "local.", interface: index)))
                        let task = Task { try? await Task.sleep(nanoseconds: 100_000_000); continuation.finish() }
                        continuation.onTermination = { _ in task.cancel() }
                    }
                }, resolve: { _ in
                    await state.resolving(revoke: true)
                    return .init(resolution: .init(host: "device.local.", port: 80, attributes: [:], interface: 4), addresses: ["192.168.2.2"])
                })
            XCTFail("Revocation must terminate the scan")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let values = await observations.snapshot()
        XCTAssertTrue(values.isEmpty)
    }
    func testIndividualResolutionFailureDoesNotAbortOtherServices() async throws {
        let network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let observations = LanDiscoveryAccumulator(network: network)
        try await LanBonjourDiscovery.scan(network: network, observations: observations,
            authorize: {}, currentNetwork: { network }, browse: Self.fixtureBrowse, resolve: { service in
                if service.type == "_googlecast._tcp." {
                    throw DeviceAPIError(503, "mdns_unavailable", "Service disappeared")
                }
                return Self.result()
            })
        let values = await observations.snapshot()
        XCTAssertEqual(values.count, 29)
        XCTAssertFalse(values.contains { $0.service == "_googlecast._tcp." })
    }

    func testNetworkChangeRejectsInFlightResult() async throws {
        actor NetworkState {
            var changed = false
            func change() { changed = true }
        }
        let state = NetworkState()
        let network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let observations = LanDiscoveryAccumulator(network: network)
        do {
            try await LanBonjourDiscovery.scan(network: network, observations: observations,
                authorize: {}, currentNetwork: {
                    if await state.changed { return .init(name: "other", index: 5, address: "10.0.0.1", prefix: 24) }
                    return network
                }, browse: Self.fixtureBrowse, resolve: { _ in
                    await state.change()
                    return Self.result()
                })
            XCTFail("A changed network must stop discovery")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_changed") }
        let values = await observations.snapshot()
        XCTAssertTrue(values.isEmpty)
    }

    func testBrowseFailureIsNotSilentlyReportedAsEmptySuccess() async throws {
        let network = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let observations = LanDiscoveryAccumulator(network: network)
        do {
            try await LanBonjourDiscovery.scan(network: network, observations: observations,
                authorize: {}, currentNetwork: { network }, browse: { _, _ in
                    AsyncThrowingStream { $0.finish(throwing: DeviceAPIError(503, "mdns_unavailable", "Unavailable")) }
                }, resolve: { _ in XCTFail("No service was discovered"); return nil })
            XCTFail("Browse failure must propagate")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "mdns_unavailable") }
    }

    private static func result() -> LanBonjourDiscovery.Result {
        .init(resolution: .init(host: "device.local.", port: 80, attributes: [:], interface: 4), addresses: ["192.168.2.2"])
    }

    private static let fixtureBrowse: LanBonjourDiscovery.Browse = { type, index in
        AsyncThrowingStream { continuation in
            continuation.yield(.service(.init(name: "device", type: type, domain: "local.", interface: index)))
            let task = Task { try? await Task.sleep(nanoseconds: 20_000_000); continuation.finish() }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

}
