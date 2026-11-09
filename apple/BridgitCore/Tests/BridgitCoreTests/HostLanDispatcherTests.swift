import XCTest
@testable import BridgitCore

final class HostLanDispatcherTests: XCTestCase {
    private func fixture(service: Bool = true, enabled: Bool = true, policy: (@Sendable () async -> Bool)? = nil, network injectedNetwork: LanNetworkProvider? = nil) async throws -> (HostAuthority, HostDispatcher, HostAgent) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let store = try AppletStore(directory: root)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "LAN test", operations: [.deviceRead, .lanDiscovery], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let network = injectedNetwork ?? LanNetworkProvider(testing: ())
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        let lan = try HostLanService(network: network, history: .init(directory: root.appendingPathComponent("lan")),
            session: .init { method, context in
                if method == .mdns { await context.observations.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.") }
            }, enabled: { await policy?() ?? enabled })
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() }, lan: service ? lan : nil)
        return (authority, dispatcher, agent)
    }
    private func job(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation,
            deviceID: "fixture", deadline: now + 120000, createdAt: now, request: request)
    }
    func testCapabilitySeparatesSettingNetworkContextAndGrant() async throws {
        let (_, _, agent) = try await fixture()
        func capability(_ enabled: Bool = true, network: Bool = true, context: Bool = true, caller: HostAgent? = nil) -> ProfileCapability {
            HostLanService.capability(agent: caller ?? agent, serviceAvailable: true, enabled: enabled,
                networkAvailable: network, hostReady: true, contextAllowed: context, execution: ["foreground"])
        }
        XCTAssertTrue(capability().callable)
        XCTAssertEqual(capability().operations.count, 4)
        XCTAssertFalse(capability(false).callable)
        XCTAssertEqual(capability(false).blockedReason, "capability_disabled")
        XCTAssertEqual(capability(network: false).blockedReason, "lan_unavailable")
        XCTAssertEqual(capability(context: false).blockedReason, "foreground_required")
        var denied = agent; denied.operations = [.deviceRead]
        XCTAssertEqual(capability(caller: denied).blockedReason, "agent_not_allowed")
        var viewer = agent; viewer.viewer = true
        XCTAssertFalse(capability(caller: viewer).callable)
        XCTAssertEqual(capability().constraints.parameters?["os_local_network_permission"], ["checked_when_requested"])
    }

    func testAuthorizedHTTPScanAndRead() async throws {
        let (_, dispatcher, agent) = try await fixture()
        let scan = await dispatcher.dispatch(job(agent, 1, .init(method: "POST", path: "/v1/lan/discover", idempotencyKey: "scan-once")))
        XCTAssertEqual(scan.status, 200, String(decoding: scan.data, as: UTF8.self))
        let remembered = await dispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/lan/discovered")))
        XCTAssertEqual(remembered.status, 200)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: remembered.data) as? [String: Any])
        XCTAssertEqual(object["total_devices"] as? Int, 1)
        let openAPI = await dispatcher.dispatch(job(agent, 3, .init(method: "GET", path: "/v1/openapi.json")))
        XCTAssertTrue(String(decoding: openAPI.data, as: UTF8.self).contains("lan_discover"))
    }
    private actor Policy {
        var enabled = true
        func read() -> Bool { enabled }
        func disable() { enabled = false }
    }
    func testCachedScanRechecksPolicyBeforeDelivery() async throws {
        let policy = Policy()
        let (_, dispatcher, agent) = try await fixture(policy: { await policy.read() })
        let request = HostRequest(method: "POST", path: "/v1/lan/discover", idempotencyKey: "cached-scan")
        let original = await dispatcher.dispatch(job(agent, 1, request))
        XCTAssertEqual(original.status, 200)
        let cached = await dispatcher.dispatch(job(agent, 2, request))
        XCTAssertEqual(cached.status, 200)
        XCTAssertEqual(cached.data, original.data)
        XCTAssertNotNil(cached.beforeDelivery)
        await policy.disable()
        do {
            try await cached.beforeDelivery?()
            XCTFail("A cached scan must not escape the disabled policy")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.status, 403)
            XCTAssertEqual(error.code, "lan_disabled")
        }
        let denied = await dispatcher.dispatch(job(agent, 3, request))
        XCTAssertEqual(denied.status, 403)
    }

    func testCachedScanCannotCrossNetworkChangesOrExposePrivateScope() async throws {
        let network = LanNetworkProvider(testing: ())
        let (_, dispatcher, agent) = try await fixture(network: network)
        let request = HostRequest(method: "POST", path: "/v1/lan/discover", idempotencyKey: "network-bound-scan")
        let first = await dispatcher.dispatch(job(agent, 1, request))
        XCTAssertEqual(first.status, 200)
        let cached = await dispatcher.dispatch(job(agent, 2, request))
        XCTAssertEqual(cached.status, 200)
        try await cached.beforeDelivery?()
        XCTAssertNil(first.envelope["lan_network_scope"])
        XCTAssertFalse(String(decoding: first.data, as: UTF8.self).contains("session:"))
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        do {
            try await cached.beforeDelivery?()
            XCTFail("Cached data from the old network must not be delivered")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_changed") }
        let denied = await dispatcher.dispatch(job(agent, 3, request))
        XCTAssertEqual(denied.status, 409)
        let fresh = await dispatcher.dispatch(job(agent, 4, .init(method: "POST", path: "/v1/lan/discover", idempotencyKey: "new-network-scan")))
        XCTAssertEqual(fresh.status, 200)
    }

    func testDisabledAndAbsentServiceAreNotAdvertised() async throws {
        for (service, enabled, status) in [(false, true, 503), (true, false, 403)] {
            let (_, dispatcher, agent) = try await fixture(service: service, enabled: enabled)
            let response = await dispatcher.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/lan/catalog")))
            XCTAssertEqual(response.status, status)
            let api = await dispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/openapi.json")))
            XCTAssertFalse(String(decoding: api.data, as: UTF8.self).contains("lan_discover"))
        }
    }
    func testGrantAndViewerIsolationAndCanonicalSchemas() async throws {
        let (authority, dispatcher, agent) = try await fixture()
        let (ungranted, _) = try await authority.create(name: "No LAN", operations: [.deviceRead], applets: [])
        let response = await dispatcher.dispatch(job(ungranted, 1, .init(method: "GET", path: "/v1/lan/catalog")))
        XCTAssertEqual(response.status, 403)
        XCTAssertEqual(HostAPIContract.allowed(agent, lanAvailable: true).filter { $0.grant == .lanDiscovery }.count, 4)
        XCTAssertFalse(HostAPIContract.tools(agent).contains { ($0["name"] as? String)?.hasPrefix("lan_") == true })
        XCTAssertEqual(HostAPIContract.tools(agent, lanAvailable: true).filter { ($0["name"] as? String)?.hasPrefix("lan_") == true }.count, 4)
        var viewer = agent; viewer.viewer = true
        XCTAssertFalse(HostAPIContract.allowed(viewer, lanAvailable: true).contains { $0.grant == .lanDiscovery })
        let operation = try XCTUnwrap(HostAPIContract.operations.first { $0.name == "lan_discover" })
        XCTAssertEqual(operation.bodySchema["additionalProperties"] as? Bool, false)
        XCTAssertEqual(HostRequest(method: "POST", path: "/v1/lan/discover").route?.0, .lanDiscovery)
    }
}
