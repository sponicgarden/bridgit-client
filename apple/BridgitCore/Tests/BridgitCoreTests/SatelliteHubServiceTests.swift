import XCTest
@testable import BridgitCore

private actor HubFixtureSecrets: SatelliteSecrets {
    var values: [String: String] = [:]
    var fail = false
    func failWrites() { fail = true }
    func load(_ id: String) -> String? { values[id] }
    func save(_ value: String, for id: String) throws { if fail { throw DeviceAPIError(503, "fixture_keychain_failure", "Fixture credential storage failed.") }; values[id] = value }
    func remove(_ id: String) { values[id] = nil }
}
private actor HubFixtureTransport: SatelliteHTTPTransport {
    let receiver: SatelliteHostService
    private(set) var calls = 0
    private(set) var healthStarted = false
    private(set) var healthCancelled = false
    private var holdHealth = false
    func holdNextHealth() { holdHealth = true }
    init(_ receiver: SatelliteHostService) { self.receiver = receiver }
    func call(address: TailnetAddress, port: UInt16, request: HostRequest, key: String?) async throws -> HostResponse {
        guard address.value == "100.80.1.2", port == 18471 else { throw DeviceAPIError(503, "fixture_wrong_peer", "Unexpected fixture peer") }
        calls += 1
        if request.path == "/v1/health", holdHealth {
            holdHealth = false
            healthStarted = true
            do { try await Task.sleep(for: .seconds(8)) }
            catch { healthCancelled = true; throw error }
        }
        let wire = TailnetHTTPRequest(method: request.method, target: request.path, headers: key.map { ["authorization": "Bearer " + $0] } ?? [:], body: request.body.isEmpty ? Data() : try JSONSerialization.data(withJSONObject: request.body))
        let response = await receiver.handle(wire, source: "100.80.2.3")
        await response.afterDelivery?()
        return response
    }
}
final class SatelliteHubServiceTests: XCTestCase {
    private func store() throws -> AppletStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try AppletStore(directory: directory)
    }
    private func profile(_ agent: HostAgent) -> DeviceProfile {
        .init(profileRevision: 1, observedAt: Date(), expiresAt: Date().addingTimeInterval(60), device: .init(installID: nil, name: "Fixture", deviceType: "phone", platform: "ios", osVersion: "fixture", appVersion: "fixture", apiRevision: 1, services: [], hardware: [:]), availability: .init(state: "online", inForeground: true), capabilities: [:])
    }
    private func setup() async throws -> (SatelliteHubService, SatellitePairingLink, HostAuthority, HostAuthority, HubFixtureSecrets, HubFixtureTransport, AppletStore, SatelliteHostService, HubFixtureSecrets) {
        let localStore = try store(), remoteStore = try store()
        let local = HostAuthority(store: localStore, deviceID: "fixture_hub"), remote = HostAuthority(store: remoteStore, deviceID: "fixture_satellite")
        await local.setState(enabled: true, awake: true, foreground: true); await remote.setState(enabled: false, awake: true, foreground: true)
        await local.setDirectEnabled(true); await remote.setDirectEnabled(true)
        let localDispatcher = HostDispatcher(authority: local, store: localStore, engine: AppletEngine(store: localStore) { _, _, _ in [:] }) { self.profile($0) }
        let remoteDispatcher = HostDispatcher(authority: remote, store: remoteStore, engine: AppletEngine(store: remoteStore) { _, _, _ in [:] }) { self.profile($0) }
        let secrets = HubFixtureSecrets(), remoteSecrets = HubFixtureSecrets()
        let receiver = SatelliteHostService(authority: remote, dispatcher: remoteDispatcher, store: remoteStore, secrets: remoteSecrets) { self.profile($0) }
        let localReceiver = SatelliteHostService(authority: local, dispatcher: localDispatcher, store: localStore, secrets: secrets) { self.profile($0) }
        let transport = HubFixtureTransport(receiver)
        let hub = SatelliteHubService(store: localStore, authority: local, secrets: secrets, client: .init(transport: transport), endpoint: { (TailnetAddress("100.80.2.3")!, 18471) }, localName: { "Fixture hub" }, localType: { "tablet" }, sharedView: { try await localDispatcher.satelliteReturnView($0, agent: $1) })
        await localReceiver.setReturnHandler { try await hub.returnCall($0, agent: $1) }
        let link = try await remote.beginSatellitePairing(address: XCTUnwrap(TailnetAddress("100.80.1.2")), port: 18471, name: "Garage", operations: [.deviceRead])
        return (hub, link, local, remote, secrets, transport, localStore, localReceiver, remoteSecrets)
    }
    func testRealClaimPersistenceUsesTwoAuthoritiesAndOwnerSelectedAgentScope() async throws {
        let (hub, link, local, remote, secrets, transport, store, _, _) = try await setup()
        let (reader, _) = try await local.create(name: "Reader", operations: [.deviceRead], applets: [])
        let record = try await hub.add(link, allowedAgents: [reader.id])
        XCTAssertEqual(record.name, "Garage"); XCTAssertEqual(record.state(), "online")
        let actualRemote = try await remote.list(); XCTAssertEqual(actualRemote.count, 1)
        let returnAgent = try await local.agent(record.returnAgentID); XCTAssertEqual(returnAgent?.satelliteRole, "return")
        let stored = try await store.state("host-satellites-v1")
        let key = try await secrets.load("satellite-outgoing:" + record.id)
        XCTAssertNotNil(key); XCTAssertFalse(stored?.contains(key ?? "impossible") ?? true)
        let response = try await hub.forward(.init(method: "GET", path: "/v1/health", query: ["target": record.id]), agent: reader, authorize: { _ in })
        XCTAssertEqual(response.status, 200); XCTAssertEqual(response.headers["X-Bridgit-Ran-On"], record.id)
        let before = await transport.calls
        try await hub.setAllowedAgents(record.id, agents: [])
        do { _ = try await hub.forward(.init(method: "GET", path: "/v1/health", query: ["target": record.id]), agent: reader, authorize: { _ in }); XCTFail("Removed scope must deny before sending") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "satellite_not_allowed") }
        let after = await transport.calls; XCTAssertEqual(before, after)
        let acknowledged = try await hub.remove(record.id); XCTAssertTrue(acknowledged)
        let peers = try await remote.list(); XCTAssertTrue(peers.isEmpty)
        let remaining = try await hub.list(); XCTAssertTrue(remaining.isEmpty)
    }
    func testOwnerDirectRoutesOnlyGrantedSatellitesAndCancelsWhenSourceIsRemoved() async throws {
        let (hub, link, local, remote, _, transport, store, _, remoteSecrets) = try await setup()
        await local.setConnected(false)
        await local.setOwnerDirectEnabled(true)
        let (reader, key) = try await local.createOwnerDirect(name: "Direct operator", operations: [.deviceRead], applets: [])
        let (_, unassigned) = try await local.createOwnerDirect(name: "Other operator", operations: [.deviceRead], applets: [])
        let record = try await hub.add(link, allowedAgents: [reader.id])
        let dispatcher = HostDispatcher(authority: local, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { self.profile($0) },
            satelliteForward: { request, agent, authorize in try await hub.forward(request, agent: agent, authorize: authorize) },
            satelliteList: { agent in try await hub.publicSatellites(for: agent) })
        let direct = OwnerDirectHostService(authority: local, dispatcher: dispatcher)
        let source = "100.80.2.44"
        func request(_ target: String, key: String) -> TailnetHTTPRequest {
            .init(method: "GET", target: target, headers: ["authorization": "Bearer " + key], body: Data())
        }
        let list = await direct.handle(request("/v1/satellites", key: key), source: source)
        let listing = try XCTUnwrap(JSONSerialization.jsonObject(with: list.data) as? [String: Any])
        XCTAssertEqual(list.status, 200)
        XCTAssertEqual((listing["satellites"] as? [[String: Any]])?.first?["id"] as? String, record.id)
        let path = "/v1/health?target=" + record.id
        let success = await direct.handle(request(path, key: key), source: source)
        XCTAssertEqual(success.status, 200)
        XCTAssertEqual(success.headers["X-Bridgit-Ran-On"], record.id)
        func mcp(_ body: [String: Any]) async throws -> HostResponse {
            await direct.handle(.init(method: "POST", target: "/v1/mcp", headers: ["authorization": "Bearer " + key],
                body: try JSONSerialization.data(withJSONObject: body)), source: source)
        }
        _ = try await mcp(["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": [
            "protocolVersion": "2025-11-25", "capabilities": [:], "clientInfo": ["name": "Fixture", "version": "1"]
        ]])
        let beforeMCP = await transport.calls
        let mcpResponse = try await mcp(["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": [
            "name": "device_health", "arguments": ["query": ["target": record.id]]
        ]])
        let mcpBody = try XCTUnwrap(JSONSerialization.jsonObject(with: mcpResponse.data) as? [String: Any])
        XCTAssertEqual((mcpBody["result"] as? [String: Any])?["isError"] as? Bool, false)
        let afterMCP = await transport.calls
        XCTAssertEqual(afterMCP, beforeMCP + 1)
        let beforeDenials = await transport.calls
        let denied = await direct.handle(request(path, key: unassigned), source: source)
        let missing = await direct.handle(request("/v1/health?target=sat:missing", key: key), source: source)
        let absent = await direct.handle(request("/v1/log?target=" + record.id, key: key), source: source)
        XCTAssertEqual(denied.status, 403)
        XCTAssertEqual(missing.status, 403)
        XCTAssertNotEqual(absent.status, 200)
        let afterDenials = await transport.calls
        XCTAssertEqual(afterDenials, beforeDenials)
        let remoteAgents = try await remote.list()
        let claimedHub = try XCTUnwrap(remoteAgents.first)
        let returnedSecret = await remoteSecrets.load("satellite-return:" + claimedHub.id)
        let returnKey = try XCTUnwrap(returnedSecret)
        let isolated = await direct.handle(request(path, key: returnKey), source: source)
        XCTAssertEqual(isolated.status, 401)

        await transport.holdNextHealth()
        let work = Task { await direct.handle(request(path, key: key), source: source) }
        defer { work.cancel() }
        for _ in 0..<100 {
            if await transport.healthStarted { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let started = await transport.healthStarted
        XCTAssertTrue(started)
        try await local.setOwnerDirectPeers(reader.id, unlock: true)
        let failed = await work.value
        let cancelled = await transport.healthCancelled
        XCTAssertTrue(cancelled)
        XCTAssertNotEqual(failed.status, 200)
        XCTAssertNil(failed.headers["X-Bridgit-Ran-On"])
    }

    func testKeychainFailureRevokesActualRemoteAndReturnGrantsWithoutSavedRow() async throws {
        let (hub, link, local, remote, secrets, _, _, _, _) = try await setup()
        await secrets.failWrites()
        do { _ = try await hub.add(link, allowedAgents: []); XCTFail("Credential failure cannot save pairing") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "fixture_keychain_failure") }
        let remoteAgents = try await remote.list(), localAgents = try await local.list(), records = try await hub.list()
        XCTAssertTrue(remoteAgents.isEmpty); XCTAssertTrue(localAgents.isEmpty); XCTAssertTrue(records.isEmpty)
    }
    func testPauseIsAtomicAndReturnKeyCannotManageAgentsWithoutSeparateOwnerConsent() async throws {
        let (hub, link, local, remote, _, _, _, receiver, remoteSecrets) = try await setup()
        let (reader, _) = try await local.create(name: "Reader", operations: [.deviceRead], applets: [])
        let record = try await hub.add(link, allowedAgents: [reader.id])
        let remoteAgents = try await remote.list(), remoteAgent = try XCTUnwrap(remoteAgents.first)
        let storedKey = try await remoteSecrets.load("satellite-return:" + remoteAgent.id), key = try XCTUnwrap(storedKey)
        func request(_ path: String) -> TailnetHTTPRequest { .init(method: "GET", target: path, headers: ["authorization": "Bearer " + key], body: Data()) }
        let home = await receiver.handle(request("/v1/portal/home"), source: "100.80.1.2"); XCTAssertEqual(home.status, 200)
        let management = await receiver.handle(request("/v1/satellite/hub/agents"), source: "100.80.1.2"); XCTAssertEqual(management.status, 403)
        let control = await receiver.handle(request("/v1/agents"), source: "100.80.1.2"); XCTAssertNotEqual(control.status, 200)
        try await hub.setPaused(record.id, paused: true)
        let paused = await receiver.handle(request("/v1/portal/home"), source: "100.80.1.2"); XCTAssertEqual(paused.status, 403)
        let saved = try await hub.list(); XCTAssertEqual(saved.first?.paused, true)
        let grant = try await local.agent(record.returnAgentID); XCTAssertEqual(grant?.paused, true)
    }
    func testReaddingSamePeerDoesNotReuseTargetIdentity() async throws {
        let (hub, link, local, remote, _, transport, _, _, _) = try await setup()
        let (reader, _) = try await local.create(name: "Reader", operations: [.deviceRead], applets: [])
        let old = try await hub.add(link, allowedAgents: [reader.id])
        _ = try await hub.remove(old.id)
        let replacement = try await remote.beginSatellitePairing(address: link.address, port: link.port, name: link.name, operations: [.deviceRead])
        let current = try await hub.add(replacement, allowedAgents: [reader.id])
        XCTAssertNotEqual(current.id, old.id)
        let before = await transport.calls
        do { _ = try await hub.forward(.init(method: "GET", path: "/v1/health", query: ["target": old.id]), agent: reader, authorize: { _ in }); XCTFail("Old target must not be rebound") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "satellite_not_allowed") }
        let after = await transport.calls; XCTAssertEqual(before, after)
    }

}
