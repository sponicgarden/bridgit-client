import XCTest
@testable import BridgitCore
final class SatelliteAuthorityTests: XCTestCase {
    private func store() throws -> AppletStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try AppletStore(directory: directory)
    }
    private func address() throws -> TailnetAddress { try XCTUnwrap(TailnetAddress("100.80.1.2")) }
    private func denied(_ work: () async throws -> Void, code: String) async {
        do { try await work(); XCTFail("Expected denial") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, code) }
        catch { XCTFail("Unexpected error type") }
    }
    func testOneTimeClaimCreatesOnlyOwnerSelectedSourceBoundAuthorityAtomically() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture_satellite")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await denied({ _ = try await authority.beginSatellitePairing(address: self.address(), port: 18471, name: "Garage", operations: [.deviceRead]) }, code: "foreground_required")
        await authority.setDirectEnabled(true)
        await denied({ _ = try await authority.beginSatellitePairing(address: self.address(), port: 18471, name: "Garage", operations: [.deviceRead, .administrator]) }, code: "satellite_grants")
        let link = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Garage", operations: [.deviceRead, .cameraSnapshot])
        let before = try await authority.list(); XCTAssertTrue(before.isEmpty)
        let claim = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: "Kitchen")
        XCTAssertEqual(claim.name, "Garage")
        XCTAssertEqual(claim.agent.mode, "direct")
        XCTAssertEqual(claim.agent.operations, [.deviceRead, .cameraSnapshot])
        XCTAssertEqual(claim.agent.tailnetPeer, "100.80.2.3")
        XCTAssertTrue(SatelliteAuthorityPolicy.validKey(claim.key))
        let persisted = try await store.state("host-agents-v1")
        XCTAssertFalse(persisted?.contains(claim.key) ?? true)
        let pending = try await store.state(SatelliteAuthorityPolicy.claimKey); XCTAssertNil(pending)
        let authenticated = try await authority.authenticateDirect(key: claim.key, source: "::ffff:100.80.2.3")
        XCTAssertEqual(authenticated.id, claim.agent.id)
        await denied({ _ = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: nil) }, code: "unauthorized")
        let reopened = HostAuthority(store: store, deviceID: "fixture_satellite")
        await reopened.setState(enabled: false, awake: true, foreground: true)
        await denied({ _ = try await reopened.authenticateDirect(key: claim.key, source: "100.80.2.3") }, code: "foreground_required")
        await reopened.setDirectEnabled(true)
        let restored = try await reopened.authenticateDirect(key: claim.key, source: "100.80.2.3")
        XCTAssertEqual(restored, claim.agent)
        await denied({ _ = try await reopened.authenticateDirect(key: claim.key, source: "100.80.2.4") }, code: "unauthorized")
        try await reopened.pause(claim.agent.id, paused: true)
        await denied({ _ = try await reopened.authenticateDirect(key: claim.key, source: "100.80.2.3") }, code: "link_paused")
    }
    func testWrongExpiredCancelledAndReplacedClaimsNeverCreateAgents() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture_satellite")
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setDirectEnabled(true)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let old = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Garage", operations: [.deviceRead], now: now - 900_001)
        await denied({ _ = try await authority.claimSatellite(token: old.claim, source: "100.80.2.3", hubName: nil) }, code: "unauthorized")
        let replaced = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Garage", operations: [.deviceRead])
        let current = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Garage", operations: [.deviceRead])
        await denied({ _ = try await authority.claimSatellite(token: replaced.claim, source: "100.80.2.3", hubName: nil) }, code: "unauthorized")
        await denied({ _ = try await authority.claimSatellite(token: current.claim, source: "192.168.1.2", hubName: nil) }, code: "unauthorized")
        await denied({ _ = try await authority.claimSatellite(token: "sct_" + String(repeating: "a", count: 43), source: "100.80.2.3", hubName: nil) }, code: "unauthorized")
        try await authority.cancelSatellitePairing()
        await denied({ _ = try await authority.claimSatellite(token: current.claim, source: "100.80.2.3", hubName: nil) }, code: "unauthorized")
        let agents = try await authority.list(); XCTAssertTrue(agents.isEmpty)
    }
    func testDirectHubCannotUseRelayOrGenericControlAndFreshRequestsUseExistingDispatcher() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture_satellite")
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setDirectEnabled(true)
        let link = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Garage", operations: [.deviceRead])
        let claimed = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: nil)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func job(_ path: String, mode: String = "direct", source: String? = "100.80.2.3", method: String = "GET") -> HostJob {
            HostJob(id: UUID().uuidString, sequence: 0, agentID: claimed.agent.id, generation: claimed.agent.generation, deviceID: "fixture_satellite", mode: mode, deadline: now + 30_000, createdAt: now, request: .init(method: method, path: path), tailnetSource: source)
        }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }) { _ in throw CancellationError() }
        let health = await dispatcher.dispatch(job("/v1/health")); XCTAssertEqual(health.status, 200)
        let healthJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: health.data) as? [String: Any])
        XCTAssertEqual(healthJSON["transport"] as? String, "tailscale")
        XCTAssertNil(healthJSON["base_url"])
        let schema = await dispatcher.dispatch(job("/v1/openapi.json"))
        XCTAssertEqual(schema.status, 403)
        let repeatedRead = await dispatcher.dispatch(job("/v1/health")); XCTAssertEqual(repeatedRead.status, 200)
        let forbidden = await dispatcher.dispatch(job("/v1/agents")); XCTAssertEqual(forbidden.status, 403)
        let forbiddenWrite = await dispatcher.dispatch(job("/v1/settings", method: "POST")); XCTAssertEqual(forbiddenWrite.status, 403)
        let wrongPeer = await dispatcher.dispatch(job("/v1/health", source: "100.80.2.4")); XCTAssertEqual(wrongPeer.status, 403)
        let forgedRelay = await dispatcher.dispatch(job("/v1/health", mode: "connector", source: nil)); XCTAssertNotEqual(forgedRelay.status, 200)
        await authority.setState(enabled: false, awake: true, foreground: false)
        let background = await dispatcher.dispatch(job("/v1/health")); XCTAssertEqual(background.status, 409)
    }
    func testCancellingAnOlderDisplayedCodeDoesNotRevokeItsReplacement() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "fixture_satellite")
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setDirectEnabled(true)
        let older = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "Old", operations: [.deviceRead])
        let current = try await authority.beginSatellitePairing(address: address(), port: 18471, name: "New", operations: [.deviceRead])
        try await authority.cancelSatellitePairing(claim: older.claim)
        let claimed = try await authority.claimSatellite(token: current.claim, source: "100.80.2.3", hubName: nil)
        XCTAssertEqual(claimed.name, "New")
    }

}
