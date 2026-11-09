import CryptoKit
import XCTest
@testable import BridgitCore

final class HostViewerPairingTests: XCTestCase {
    private func store() throws -> AppletStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try AppletStore(directory: directory)
    }
    private func job(_ agent: HostAgent, sequence: Int64 = 1, request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return HostJob(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "host_fixture", deadline: now + 30_000, createdAt: now, request: request)
    }
    private func denied(_ work: () async throws -> Void, code: String) async {
        do { try await work(); XCTFail("Expected denial") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, code) }
        catch { XCTFail("Unexpected error type") }
    }
    func testOutgoingCurrentBrandRoundTripsEscapedNamesAndRemainsOneTime() throws {
        let url = try HostViewerPairing.link(base: URL(string: "https://relay.invalid/d/host_fixture")!, device: "host_fixture", token: "rly_fixture_0123456789abcdef", name: "A & B #100%", install: "bgi_fixture")
        XCTAssertEqual(url.scheme, "bridgit")
        let parsed = try XCTUnwrap(Pairing.parse(link: url))
        XCTAssertEqual(parsed.name, "A & B #100%")
        XCTAssertTrue(parsed.portal); XCTAssertEqual(parsed.needsClaim, true)
    }
    func testExplicitAPK3347WireFormatIsImportDataWithoutGlobalAlias() throws {
        let legacy = try HostViewerPairing.link(base: URL(string: "https://relay.invalid/d/host_fixture")!, device: "host_fixture", token: "rly_fixture_0123456789abcdef", name: "A & B #100%", format: .androidAPK3347)
        XCTAssertEqual(legacy.scheme, "bridgitportal")
        XCTAssertNil(Pairing.parse(link: legacy))
        let imported = try XCTUnwrap(Pairing.parse(legacy.absoluteString))
        XCTAssertEqual(imported.name, "A & B #100%")
        XCTAssertEqual(imported.needsClaim, true)
        XCTAssertTrue(imported.portal)
        // This is the literal guard verified in PortalData.parsePairLink's packaged DEX.
        XCTAssertTrue(legacy.absoluteString.hasPrefix("bridgitportal://pair#"))
        if let path = ProcessInfo.processInfo.environment["BRIDGIT_PAIRING_WIRE_FIXTURE_OUT"] {
            let current = try HostViewerPairing.link(base: URL(string: "https://relay.invalid/d/host_fixture")!, device: "host_fixture", token: "rly_fixture_0123456789abcdef", name: "A & B #100%")
            // Explicitly synthetic data: never an issued or claimed credential.
            try ("current\t" + current.absoluteString + "\napk3347\t" + legacy.absoluteString + "\n").write(toFile: path, atomically: true, encoding: .utf8)
        }
    }
    func testUnclaimedReadDeniedThenAtomicReplacementRevokesOldGenerationAndPersists() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host_fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (viewer, original) = try await authority.createViewer(name: "Owner approved viewer")
        await denied({ _ = try await authority.admit(self.job(viewer, request: .init(method: "GET", path: "/v1/portal/snapshot"))) }, code: "claim_required")
        let replacement = PairingClaim.newToken()
        let claim = job(viewer, sequence: 2, request: .init(method: "POST", path: HostViewerPairing.claimPath, body: ["token_hash": PairingClaim.tokenHash(replacement)]))
        _ = try await authority.admit(claim)
        try await authority.claimViewer(claim)
        let pairingEvents = try await store.hostActivity(agentFilter: .identified(viewer.id), kinds: [.pairing])
        XCTAssertEqual(pairingEvents.map(\.operation), ["Updated Owner approved viewer", "Added Owner approved viewer"])
        let exported = try await store.hostActivityExport(agentFilter: .identified(viewer.id), kinds: [.pairing])
        let eventText = String(decoding: try exported.data(format: .jsonl), as: UTF8.self)
        XCTAssertFalse(eventText.contains(original)); XCTAssertFalse(eventText.contains(replacement))
        XCTAssertFalse(eventText.contains(PairingClaim.tokenHash(original)))
        XCTAssertFalse(eventText.contains(PairingClaim.tokenHash(replacement)))
        _ = try await authority.validateDelivery(claim)
        await denied({ _ = try await authority.admit(self.job(viewer, sequence: 3, request: .init(method: "GET", path: "/v1/portal/snapshot"))) }, code: "agent_revoked")
        let stored = try await authority.agent(viewer.id)
        let current = try XCTUnwrap(stored)
        XCTAssertNotEqual(current.generation, viewer.generation)
        XCTAssertEqual(current.tokenHash, PairingClaim.tokenHash(replacement))
        XCTAssertNotEqual(current.tokenHash, PairingClaim.tokenHash(original))
        XCTAssertNil(current.viewerClaimUntil)
        XCTAssertEqual(Set(HostAPIContract.allowed(current).map(\.path)), HostViewerPairing.reads)
        XCTAssertTrue(HostAPIContract.allowed(viewer).isEmpty)
        _ = try await authority.admit(job(current, sequence: 1, request: .init(method: "GET", path: "/v1/portal/snapshot")))
        await denied({ try await authority.claimViewer(self.job(current, sequence: 2, request: .init(method: "POST", path: HostViewerPairing.claimPath, body: ["token_hash": PairingClaim.tokenHash(PairingClaim.newToken())]))) }, code: "already_claimed")
        let reopened = HostAuthority(store: store, deviceID: "host_fixture")
        let persisted = try await reopened.agent(viewer.id)
        XCTAssertEqual(persisted, current)
        await reopened.setState(enabled: true, awake: true, foreground: true)
        _ = try await reopened.validateDelivery(claim)
        let reuse = job(viewer, sequence: 4, request: claim.request)
        _ = try await reopened.admit(reuse)
        await denied({ try await reopened.claimViewer(reuse) }, code: "already_claimed")
        let expiredPublication = try await reopened.relayPublication(now: claim.deadline + 5_001)
        XCTAssertEqual(expiredPublication.agents.count, 1)
        XCTAssertNil(expiredPublication.refreshAt)

        await denied({ try await authority.setAdministrator(viewer.id, enabled: true) }, code: "viewer_read_only")
        await denied({ _ = try await authority.admit(self.job(current, sequence: 3, request: .init(method: "GET", path: "/v1/health"))) }, code: "viewer_read_only")
        await denied({ _ = try await authority.admit(self.job(current, sequence: 4, request: .init(method: "POST", path: "/v1/camera/snapshot"))) }, code: "viewer_read_only")
        try await authority.pause(viewer.id, paused: true)
        await denied({ _ = try await authority.validateDelivery(claim) }, code: "agent_revoked")
    }
    func testPendingViewerReservesRelayReceiptSlotWithoutDroppingAnotherAgent() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "host_fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        for i in 0..<14 {
            _ = try await authority.create(name: "Controller \(i)", operations: [.deviceRead], applets: [])
        }
        let (viewer, _) = try await authority.createViewer(name: "Reserved slot")
        await denied({ _ = try await authority.create(name: "No capacity", operations: [.deviceRead], applets: []) }, code: "relay_slot_limit")
        let claim = job(viewer, request: .init(method: "POST", path: HostViewerPairing.claimPath,
                                              body: ["token_hash": PairingClaim.tokenHash(PairingClaim.newToken())]))
        try await authority.claimViewer(claim)
        let publication = try await authority.relayPublication()
        XCTAssertEqual(publication.agents.count, 16)
        XCTAssertEqual(publication.agents.filter { $0["portal"] as? Bool == true }.count, 2)
        XCTAssertEqual(publication.refreshAt, claim.deadline + 5_000)
    }
    func testExpiredMalformedAndOrdinaryAgentCannotClaim() async throws {
        let authority = HostAuthority(store: try store(), deviceID: "host_fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let (expired, _) = try await authority.createViewer(name: "Expired", now: now - 86_400_001)
        await denied({ _ = try await authority.claimViewer(self.job(expired, request: .init(method: "POST", path: HostViewerPairing.claimPath))) }, code: "link_expired")
        let (viewer, token) = try await authority.createViewer(name: "Fresh")
        for body: [String: Any] in [["token_hash": "bad"], ["token_hash": PairingClaim.tokenHash(token)], ["token_hash": PairingClaim.tokenHash(PairingClaim.newToken()), "extra": true]] {
            await denied({ try await authority.claimViewer(self.job(viewer, request: .init(method: "POST", path: HostViewerPairing.claimPath, body: body))) }, code: "invalid_token_hash")
        }
        let (ordinary, _) = try await authority.create(name: "Existing control", operations: [.deviceRead], applets: [])
        await denied({ try await authority.claimViewer(self.job(ordinary, request: .init(method: "POST", path: HostViewerPairing.claimPath))) }, code: "viewer_required")
    }
    func testActualDispatcherClaimsThenServesOnlyNativeViewerProjection() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host_fixture")
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in
            DeviceProfile(profileRevision: 1, observedAt: Date(), expiresAt: Date().addingTimeInterval(60),
                          device: .init(installID: nil, name: "Fixture Mac", deviceType: "computer", platform: "macos", osVersion: "fixture", appVersion: "fixture", apiRevision: 1, services: [], hardware: [:]),
                          availability: .init(state: "online", inForeground: true), capabilities: [:])
        }, additionalViewerApplets: { _ in
            [["id": "library-fixture", "name": "Maintained applet", "private_rows": "must-not-leak"]]
        })
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        let deniedRead = await dispatcher.dispatch(job(viewer, request: .init(method: "GET", path: "/v1/portal/snapshot")))
        XCTAssertEqual(deniedRead.status, 403)
        let claim = job(viewer, sequence: 2, request: .init(method: "POST", path: HostViewerPairing.claimPath, body: ["token_hash": PairingClaim.tokenHash(PairingClaim.newToken())]))
        let response = await dispatcher.dispatch(claim)
        XCTAssertEqual(response.status, 200)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: response.data) as? [String: Bool])?["claimed"], true)
        let stored = try await authority.agent(viewer.id), current = try XCTUnwrap(stored)
        let read = await dispatcher.dispatch(job(current, request: .init(method: "GET", path: "/v1/portal/snapshot", query: ["logs": "50", "client_type": "tablet"])))
        XCTAssertEqual(read.status, 200)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        let home = try XCTUnwrap(object["home"] as? [String: Any])
        XCTAssertEqual((home["device"] as? [String: String])?["platform"], "macos")
        XCTAssertFalse(String(decoding: read.data, as: UTF8.self).contains(current.tokenHash))
        let names = try XCTUnwrap(home["applets"] as? [[String: String]])
        XCTAssertEqual(names, [["id": "library-fixture", "name": "Maintained applet"]])
        XCTAssertFalse(String(decoding: read.data, as: UTF8.self).contains("must-not-leak"))
        let controllerRead = await dispatcher.dispatch(job(current, sequence: 3, request: .init(method: "GET", path: "/v1/applets/installed")))
        XCTAssertEqual(controllerRead.status, 403)
        let deniedWrite = await dispatcher.dispatch(job(current, sequence: 4, request: .init(method: "POST", path: "/v1/settings", body: ["camera": true])))
        XCTAssertEqual(deniedWrite.status, 403)
        let (ordinary, _) = try await authority.create(name: "Ordinary", operations: [.deviceRead], applets: [])
        await authority.setOwnerDirectEnabled(true)
        await authority.setDirectEnabled(true)
        let sealedKey = P256.KeyAgreement.PrivateKey().publicKey.x963Representation.base64EncodedString()
        let (sealed, _) = try await authority.create(name: "Sealed", operations: [.deviceRead], applets: [], peer: sealedKey)
        let (ownerDirect, _) = try await authority.createOwnerDirect(name: "Owner direct", operations: [.deviceRead], applets: [])
        let address = try XCTUnwrap(TailnetAddress("100.80.2.3"))
        let (satellite, _) = try await authority.createSatelliteReturn(name: "Satellite return", peer: address)
        let roster = await dispatcher.dispatch(job(current, sequence: 5, request: .init(method: "GET", path: "/v1/portal/agents")))
        XCTAssertEqual(roster.status, 200)
        let rosterJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: roster.data) as? [String: Any])
        let rows = try XCTUnwrap(rosterJSON["agents"] as? [[String: Any]])
        for (agent, mode, transport) in [
            (ordinary, "connector", "relay"), (sealed, "sealed", "relay"),
            (ownerDirect, "owner_direct", "direct"), (satellite, "direct", "direct")
        ] {
            let row = try XCTUnwrap(rows.first { ($0["id"] as? String) == agent.id })
            XCTAssertEqual(row["mode"] as? String, mode)
            XCTAssertEqual(row["transport"] as? String, transport)
        }
        let noNewGrant = await dispatcher.dispatch(job(ordinary, request: .init(method: "GET", path: "/v1/portal/snapshot")))
        XCTAssertEqual(noNewGrant.status, 403)
    }

    func testViewerRevokedDuringAdditionalLookupReceivesNoNames() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "host_fixture")
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in
            DeviceProfile(profileRevision: 1, observedAt: Date(), expiresAt: Date().addingTimeInterval(60),
                          device: .init(installID: nil, name: "Fixture Mac", deviceType: "computer", platform: "macos", osVersion: "fixture", appVersion: "fixture", apiRevision: 1, services: [], hardware: [:]),
                          availability: .init(state: "online", inForeground: true), capabilities: [:])
        }, additionalViewerApplets: { agent in
            try await authority.pause(agent.id, paused: true)
            return [["id": "library-fixture", "name": "Private installed name"]]
        })
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        let claim = await dispatcher.dispatch(job(viewer, request: .init(method: "POST", path: HostViewerPairing.claimPath, body: ["token_hash": PairingClaim.tokenHash(PairingClaim.newToken())])))
        XCTAssertEqual(claim.status, 200)
        let stored = try await authority.agent(viewer.id), current = try XCTUnwrap(stored)
        let response = await dispatcher.dispatch(job(current, request: .init(method: "GET", path: "/v1/portal/applets")))
        XCTAssertEqual(response.status, 403)
        XCTAssertFalse(String(decoding: response.data, as: UTF8.self).contains("Private installed name"))
    }

}
