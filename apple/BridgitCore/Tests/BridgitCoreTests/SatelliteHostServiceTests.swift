import XCTest
@testable import BridgitCore

private actor SatelliteFixtureSecrets: SatelliteSecrets {
    var values: [String: String] = [:]
    var failing = false
    func failWrites() { failing = true }
    func load(_ id: String) -> String? { values[id] }
    func save(_ value: String, for id: String) throws {
        if failing { throw DeviceAPIError(503, "fixture_keychain_failure", "Fixture credential storage failed.") }
        values[id] = value
    }
    func remove(_ id: String) { values[id] = nil }
}
final class SatelliteHostServiceTests: XCTestCase {
    private func store() throws -> AppletStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try AppletStore(directory: directory)
    }
    private func facts(_ agent: HostAgent) -> DeviceProfile {
        .init(profileRevision: 1, observedAt: Date(), expiresAt: Date().addingTimeInterval(60),
              device: .init(installID: nil, name: "Fixture Mac", deviceType: "computer", platform: "macos", osVersion: "fixture", appVersion: "fixture", apiRevision: 1, services: [], hardware: [:]),
              availability: .init(state: "online", inForeground: true),
              capabilities: ["camera": .init(runtimeID: "camera", implemented: true, supported: false, enabled: false, ready: false, agentAllowed: agent.allows(.cameraSnapshot), callable: false, blockedReason: "no_hardware", legacyReason: nil, constraints: .init(execution: ["foreground"], parameters: ["camera": ["default"]]), operations: [])])
    }
    private func wire(_ method: String, _ path: String, key: String? = nil, body: [String: Any] = [:]) throws -> TailnetHTTPRequest {
        .init(method: method, target: path, headers: key.map { ["authorization": "Bearer " + $0] } ?? [:], body: body.isEmpty ? Data() : try JSONSerialization.data(withJSONObject: body))
    }
    private func setup() async throws -> (AppletStore, HostAuthority, SatelliteFixtureSecrets, SatelliteHostService, SatellitePairingLink) {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture_satellite")
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setDirectEnabled(true)
        let secrets = SatelliteFixtureSecrets()
        let profile: HostDispatcher.Profile = { self.facts($0) }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: profile)
        let service = SatelliteHostService(authority: authority, dispatcher: dispatcher, store: store, secrets: secrets, profile: profile)
        let link = try await authority.beginSatellitePairing(address: XCTUnwrap(TailnetAddress("100.80.1.2")), port: 18471, name: "Garage", operations: [.deviceRead, .cameraSnapshot])
        return (store, authority, secrets, service, link)
    }
    func testAuthenticatedDeniedRequestIsRecordedWithoutCredentialLeak() async throws {
        let (store, authority, _, service, link) = try await setup()
        let claimed = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: "Audit fixture")
        let bad = await service.handle(try wire("POST", "/v1/interactions", key: "brg_wrong_secret"), source: "100.80.2.3")
        XCTAssertEqual(bad.status, 401)
        let before = try await store.hostActivity(kinds: [.request])
        XCTAssertTrue(before.isEmpty)
        let denied = await service.handle(try wire("POST", "/v1/interactions", key: claimed.key), source: "100.80.2.3")
        XCTAssertEqual(denied.status, 403)
        let history = try await store.hostActivity(kinds: [.request])
        XCTAssertEqual(history.count, 1)
        XCTAssertEqual(history.first?.agent, claimed.agent.name)
        XCTAssertEqual(history.first?.operation, HostOperation.interactionsWrite.rawValue)
        XCTAssertEqual(history.first?.status, 403)
        XCTAssertFalse(String(describing: history).contains(claimed.key))
    }
    func testUnsignedActualHTTPClaimAndAuthenticatedManifestUseOnlyFixtureSockets() async throws {
        let (_, authority, _, service, link) = try await setup()
        let fixture = TailnetHTTPListener(fixtureLoopbackHandler: { request, source in
            XCTAssertEqual(source, "127.0.0.1")
            // Explicit fixture mapping only. Production listener uses the actual numeric peer.
            return await service.handle(request, source: "100.80.2.3")
        })
        let port = try await fixture.start(address: "127.0.0.1", port: 0)
        let configuration = URLSessionConfiguration.ephemeral; configuration.timeoutIntervalForRequest = 5
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/v1/satellite/claim")!)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["token": link.claim, "hub_name": "Fixture hub"])
        let (bytes, response) = try await session.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let claimed = try XCTUnwrap(try JSONSerialization.jsonObject(with: bytes) as? [String: String])
        let key = try XCTUnwrap(claimed["key"])
        XCTAssertTrue(SatelliteAuthorityPolicy.validKey(key))
        XCTAssertEqual(claimed["name"], "Garage")
        var manifest = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/v1/satellite/manifest")!)
        manifest.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        let (data, manifestResponse) = try await session.data(for: manifest)
        XCTAssertEqual((manifestResponse as? HTTPURLResponse)?.statusCode, 200)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(object["name"] as? String, "Garage")
        XCTAssertEqual(object["device_type"] as? String, "computer")
        XCTAssertEqual(object["platform"] as? String, "macos")
        XCTAssertTrue((object["cameras"] as? [String] ?? []).isEmpty)
        XCTAssertFalse((object["endpoints"] as? [[String: String]] ?? []).contains { $0["path"] == "/v1/camera/snapshot" }, "Disabled/absent sensors must not be advertised as shared")
        let all = try await authority.list(); XCTAssertEqual(all.count, 1)
        let (_, repeated) = try await session.data(for: request)
        XCTAssertEqual((repeated as? HTTPURLResponse)?.statusCode, 401)
        await fixture.stop()
    }
    func testReturnKeyUsesCredentialStoreAndUnlinkRevokesOnlyAfterDelivery() async throws {
        let (store, authority, secrets, service, link) = try await setup()
        let claimed = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: nil)
        let returnKey = "brg_" + String(PairingClaim.newToken().dropFirst(4))
        let body: [String: Any] = ["port": 18471, "key": returnKey, "name": "Kitchen", "device_type": "tablet", "targets": [["id": "fixture", "name": "Fixture target"]], "portals": []]
        let accepted = await service.handle(try wire("POST", "/v1/satellite/hub-info", key: claimed.key, body: body), source: "100.80.2.3")
        XCTAssertEqual(accepted.status, 200)
        let info = try await service.hubInfo(claimed.agent.id)
        XCTAssertEqual(info?.name, "Kitchen"); XCTAssertEqual(info?.deviceType, "tablet"); XCTAssertEqual(info?.address.value, "100.80.2.3")
        let saved = try await store.state("satellite-hub-info:" + claimed.agent.id)
        XCTAssertFalse(saved?.contains(returnKey) ?? true)
        let secret = try await secrets.load("satellite-return:" + claimed.agent.id)
        XCTAssertTrue(secret == returnKey)
        let wrongPeer = await service.handle(try wire("GET", "/v1/satellite/manifest", key: claimed.key), source: "100.80.2.4")
        XCTAssertEqual(wrongPeer.status, 401)
        let reply = await service.handle(try wire("POST", "/v1/satellite/unlink", key: claimed.key), source: "100.80.2.3")
        XCTAssertEqual(reply.status, 200)
        let before = try await authority.agent(claimed.agent.id); XCTAssertNotNil(before)
        await reply.afterDelivery?()
        let after = try await authority.agent(claimed.agent.id); XCTAssertNil(after)
        let erased = try await secrets.load("satellite-return:" + claimed.agent.id); XCTAssertNil(erased)
    }
    func testCredentialFailureInvalidHubInfoAndRateLimitNeverPretendSuccess() async throws {
        let (_, authority, secrets, service, link) = try await setup()
        let claimed = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: nil)
        await secrets.failWrites()
        let returnKey = "brg_" + String(PairingClaim.newToken().dropFirst(4))
        let failure = await service.handle(try wire("POST", "/v1/satellite/hub-info", key: claimed.key, body: ["port": 18471, "key": returnKey]), source: "100.80.2.3")
        XCTAssertEqual(failure.status, 503)
        let info = try await service.hubInfo(claimed.agent.id); XCTAssertNil(info)
        let malformed = await service.handle(try wire("POST", "/v1/satellite/hub-info", key: claimed.key, body: ["port": true, "key": returnKey]), source: "100.80.2.3")
        XCTAssertEqual(malformed.status, 422)
        let blocked = await service.handle(try wire("GET", "/v1/agents", key: claimed.key), source: "100.80.2.3")
        XCTAssertEqual(blocked.status, 403)
        for index in 0...20 {
            let attempt = await service.handle(try wire("POST", "/v1/satellite/claim", body: ["token": "sct_" + String(repeating: "a", count: 43)]), source: "100.80.2.4")
            XCTAssertEqual(attempt.status, index < 20 ? 401 : 429)
        }
    }
    func testActualNativeClientClaimsReadsAndUnlinksOnlyOwnedFixture() async throws {
        let (_, authority, _, service, original) = try await setup()
        let fixture = TailnetHTTPListener(fixtureLoopbackHandler: { request, source in
            XCTAssertEqual(source, "127.0.0.1")
            return await service.handle(request, source: "100.80.2.3")
        })
        let port = try await fixture.start(address: "127.0.0.1", port: 0)
        let link = try SatellitePairingLink(address: original.address, port: port, claim: original.claim, name: original.name)
        let client = SatelliteClient(transport: TailnetHTTPTransport(fixtureLoopback: true))
        let claimed = try await client.claim(link, hubName: "Fixture hub")
        XCTAssertTrue(SatelliteAuthorityPolicy.validKey(claimed.key)); XCTAssertEqual(claimed.name, "Garage")
        let manifest = try await client.manifest(address: link.address, port: port, key: claimed.key)
        XCTAssertEqual(manifest["device_type"] as? String, "computer")
        let agent = try await authority.authenticateDirect(key: claimed.key, source: "100.80.2.3")
        try await client.unlink(address: link.address, port: port, key: claimed.key)
        for _ in 0..<100 {
            if try await authority.agent(agent.id) == nil { break }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let removed = try await authority.agent(agent.id); XCTAssertNil(removed)
        do { _ = try await client.manifest(address: link.address, port: port, key: claimed.key); XCTFail("Removed key must fail") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 401) }
        await fixture.stop()
    }

}
