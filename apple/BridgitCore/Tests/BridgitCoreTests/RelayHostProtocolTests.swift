import CryptoKit
import Foundation
import XCTest
@testable import BridgitCore

private actor FixtureRelay: RelayHostTransport {
    let job: [String: Any]?
    let result: (Data) -> Void
    let providers: [String]?
    private(set) var pushFrames: [[String: Any]] = []
    private var publicKey: P256.Signing.PublicKey?
    private var until: Int64 = 0
    private var frames: [Data] = []
    private var waiting: CheckedContinuation<Data, Error>?
    private var chunks: [String: String] = [:]
    private(set) var failures: [String: String] = [:]
    let resultForJob: ((String, Data) -> Void)?
    private var sentJob = false
    private(set) var verifiedEnrollment = false
    private(set) var verifiedChallenge = false
    private(set) var verifiedSocket = false
    private(set) var announcement: [String: Any] = [:]
    private(set) var firstAnnouncement: [String: Any]?
    private(set) var finalFrames: [String: [String: Any]] = [:]
    private(set) var resultAnnouncements: [String: [String: Any]] = [:]
    private var closed = false
    private var holdResults = false
    private var heldAcks: [[String: Any]] = []
    var waitingForResultAcknowledgement: Bool { !heldAcks.isEmpty }
    func holdResultAcknowledgements() { holdResults = true }
    func releaseResultAcknowledgements() throws {
        holdResults = false
        for frame in heldAcks { try enqueue(frame) }
        heldAcks.removeAll()
    }
    private var enrollmentGate: AsyncStream<Void>?
    private(set) var posts: [String] = []
    private(set) var opens = 0
    func holdEnrollment(until gate: AsyncStream<Void>) { enrollmentGate = gate }

    private var holdPush = false
    private(set) var pushBlocked = false
    private var pushWaiter: CheckedContinuation<Void, Error>?
    func holdNextPush() { holdPush = true }
    func releasePush() { pushWaiter?.resume(); pushWaiter = nil }
    init(job: [String: Any]?, providers: [String]? = nil, resultForJob: ((String, Data) -> Void)? = nil, result: @escaping (Data) -> Void) { self.job = job; self.providers = providers; self.resultForJob = resultForJob; self.result = result }
    func post(_ request: URLRequest) async throws -> (Data, Int) {
        let object = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
        let device = request.url!.path.split(separator: "/")[1]
        let endpoint = request.url!.lastPathComponent
        posts.append(endpoint)
        if endpoint == "enroll" {
            if let gate = enrollmentGate {
                enrollmentGate = nil
                for await _ in gate { break }
            }
            let key = try P256.Signing.PublicKey(derRepresentation: Data(base64Encoded: object["identity_spki"] as! String)!)
            publicKey = key
            let proof = object["proof"] as! [String: Any]
            let text = ["bridgit-relay-enroll-v1", String(device), proof["nonce"] as! String, String((proof["at"] as! NSNumber).int64Value)].joined(separator: "\n")
            verifiedEnrollment = key.isValidSignature(try .init(rawRepresentation: Data(base64Encoded: proof["signature"] as! String)!), for: Data(text.utf8))
            return (Data("{}".utf8), 201)
        }
        let text = ["bridgit-relay-challenge-v1", String(device), object["nonce"] as! String, String((object["at"] as! NSNumber).int64Value)].joined(separator: "\n")
        verifiedChallenge = publicKey!.isValidSignature(try .init(rawRepresentation: Data(base64Encoded: object["signature"] as! String)!), for: Data(text.utf8))
        until = Int64(Date().timeIntervalSince1970 * 1000) + 60000
        var response: [String: Any] = ["nonce": "local-challenge", "until": until]
        if let providers { response["push_providers"] = providers }
        return (try JSONSerialization.data(withJSONObject: response), 200)
    }
    func open(_ request: URLRequest) throws {
        opens += 1
        closed = false
        let device = request.url!.path.split(separator: "/")[1]
        let text = ["bridgit-relay-v1", String(device), "local-challenge", String(until)].joined(separator: "\n")
        verifiedSocket = publicKey!.isValidSignature(try .init(rawRepresentation: Data(base64Encoded: request.value(forHTTPHeaderField: "X-Relay-Signature")!)!), for: Data(text.utf8))
    }
    func enqueue(_ object: [String: Any]) throws {
        let bytes = try JSONSerialization.data(withJSONObject: object)
        if let waiting { self.waiting = nil; waiting.resume(returning: bytes) } else { frames.append(bytes) }
    }
    func send(_ text: String) async throws {
        let frame = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
        if frame["type"] as? String == "push" {
            if holdPush { holdPush = false; pushBlocked = true; try await withCheckedThrowingContinuation { pushWaiter = $0 } }
            pushFrames.append(frame)
        }
        else if frame["type"] as? String == "agents" {
            if firstAnnouncement == nil { firstAnnouncement = frame }
            announcement = frame
            if !sentJob, let job { sentJob = true; try enqueue(job) }
        } else if frame["type"] as? String == "result" {
            let id = frame["id"] as! String
            chunks[id, default: ""] += frame["data"] as! String
            let ack: [String: Any] = ["type": "ack", "id": frame["id"]!, "seq": frame["seq"]!]
            if holdResults { heldAcks.append(ack) } else { try enqueue(ack) }
            if frame["final"] as? Bool == true { finalFrames[id] = frame; resultAnnouncements[id] = announcement; let bytes = Data(chunks.removeValue(forKey: id)!.utf8); result(bytes); resultForJob?(id, bytes) }
        } else if frame["type"] as? String == "failure" { failures[frame["id"] as! String] = frame["error"] as? String ?? "unknown"
        } else if frame["type"] as? String == "ping" { try enqueue(["type": "pong"]) }
    }
    func receive() async throws -> Data {
        if !frames.isEmpty { return frames.removeFirst() }
        guard !closed else { throw CancellationError() }
        return try await withCheckedThrowingContinuation { waiting = $0 }
    }
    func close() { closed = true; waiting?.resume(throwing: CancellationError()); waiting = nil; pushWaiter?.resume(throwing: CancellationError()); pushWaiter = nil }
    func verified() -> Bool { verifiedEnrollment && verifiedChallenge && verifiedSocket }
}
private actor AgentControlFixture: HostConfigurationService {
    let authority: HostAuthority
    var connection: RelayHostConnection?
    init(_ authority: HostAuthority) { self.authority = authority }
    func use(_ connection: RelayHostConnection?) { self.connection = connection }
    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        let result = try await authority.administer(request, caller: agent)
        try await connection?.agentAccessChanged(target: request.path.split(separator: "/").last.map(String.init))
        return result
    }
}

private actor OversizeActionCounter {
    private(set) var calls = 0
    func run() -> HostResponse { calls += 1; return .init(contentType: "image/jpeg", data: Data(repeating: 0xff, count: 5 * 1024 * 1024)) }
}

final class RelayHostProtocolTests: XCTestCase {
    func testStopDuringSignedEnrollmentPreventsStaleSocketAndFreshStartConnects() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let relay = FixtureRelay(job: nil) { _ in XCTFail("This fixture did not issue a job.") }
        let (gate, release) = AsyncStream<Void>.makeStream()
        defer { release.finish() }
        await relay.holdEnrollment(until: gate)
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!,
            identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        addTeardownBlock { await connection.stop() }

        let startup = Task { await connection.start() }
        for _ in 0..<200 {
            if await relay.posts == ["enroll"] { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        let heldPosts = await relay.posts
        XCTAssertEqual(heldPosts, ["enroll"])
        await connection.stop()
        release.finish()
        await startup.value

        let stoppedPosts = await relay.posts, stoppedOpens = await relay.opens
        XCTAssertEqual(stoppedPosts, ["enroll"])
        XCTAssertEqual(stoppedOpens, 0)
        let stoppedAnnouncement = await relay.firstAnnouncement
        XCTAssertNil(stoppedAnnouncement)

        await connection.start()
        let resumedPosts = await relay.posts, resumedOpens = await relay.opens, verified = await relay.verified()
        XCTAssertEqual(resumedPosts, ["enroll", "enroll", "challenge"])
        XCTAssertEqual(resumedOpens, 1)
        XCTAssertTrue(verified)
        let resumedAnnouncement = await relay.firstAnnouncement
        XCTAssertNotNil(resumedAnnouncement)
    }

    func testAuthenticatedRelayDeliversLongMicrophoneJobWithoutExtendingOrdinaryJobs() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, _) = try await authority.create(name: "Fixture recorder", operations: [.deviceRead, .microphoneRecord], applets: [])
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let payload: [String: Any] = ["method": "POST", "path": "/v1/mic/record",
                                     "body": ["duration_seconds": 120], "idempotency_key": "long-clip"]
        let frame: [String: Any] = ["type": "job", "id": "long-clip", "seq": 1, "agent_id": agent.id,
            "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector",
            "deadline": now + 150_000, "created_at": now, "payload": payload]
        let delivered = expectation(description: "Signed relay connection delivers a bounded long microphone reply")
        let relay = FixtureRelay(job: frame) { data in
            do {
                let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                XCTAssertEqual(envelope["status"] as? Int, 200)
                let bytes = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(envelope["body_base64"] as? String)))
                let clip = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
                XCTAssertEqual(clip["duration_seconds"] as? Int, 120)
            } catch { XCTFail("Invalid fixture reply: " + error.localizedDescription) }
            delivered.fulfill()
        }
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() },
            sensor: { request, caller in
                XCTAssertEqual(caller.id, agent.id)
                return try .json(["duration_seconds": MicrophoneClipRequest(request).seconds])
            })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!,
            identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start()
        await fulfillment(of: [delivered], timeout: 5)
        var ordinary = frame
        ordinary["id"] = "ordinary"; ordinary["seq"] = 2
        ordinary["payload"] = ["method": "GET", "path": "/v1/health"]
        try await relay.enqueue(ordinary)
        for _ in 0..<100 {
            if await relay.failures["ordinary"] != nil { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        let failure = await relay.failures["ordinary"]
        XCTAssertEqual(failure, "invalid_job")
        let verified = await relay.verified()
        XCTAssertTrue(verified)
        await connection.stop()
    }

    func testActualRelayTargetHeaderRoutesOnceAndConflictCannotRunLocally() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, _) = try await authority.create(name: "Fixture control", operations: [.deviceRead], applets: [])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let target = "sat:fixture"
        let payload: [String: Any] = ["method": "GET", "path": "/v1/health", "headers": ["X-Bridgit-Target": target]]
        let frame: [String: Any] = ["type": "job", "id": "target-header", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30000, "created_at": now, "payload": payload]
        let received = expectation(description: "The transport uses the target adapter, not local Health")
        let relay = FixtureRelay(job: frame) { data in
            do {
                let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
                let body = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(object["body_base64"] as? String)))
                XCTAssertEqual((try JSONSerialization.jsonObject(with: body) as? [String: String])?["fixture_target"], target)
            } catch { XCTFail("Invalid routed fixture response") }
            received.fulfill()
        }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() }, satelliteForward: { request, caller, authorize in
            try await authorize(false)
            XCTAssertEqual(request.query["target"] as? String, target); XCTAssertEqual(caller.id, agent.id)
            return try .json(["fixture_target": target])
        })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await fulfillment(of: [received], timeout: 5)
        var conflict = frame, conflicting = payload
        conflict["id"] = "target-conflict"; conflict["seq"] = 2
        conflicting["query"] = ["target": "sat:other"]; conflict["payload"] = conflicting
        try await relay.enqueue(conflict)
        for _ in 0..<100 {
            if await relay.failures["target-conflict"] != nil { break }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let failure = await relay.failures["target-conflict"]
        XCTAssertEqual(failure, "invalid_job") // The relay deliberately masks admission errors.
        await connection.stop()
    }
    func testDirectSatelliteAuthorityIsNeverPublishedToRelay() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setDirectEnabled(true)
        let address = try XCTUnwrap(TailnetAddress("100.80.1.2"))
        let link = try await authority.beginSatellitePairing(address: address, port: 18471, name: "Fixture", operations: [.deviceRead])
        let direct = try await authority.claimSatellite(token: link.claim, source: "100.80.2.3", hubName: nil)
        let (ordinary, _) = try await authority.create(name: "Relay control", operations: [.deviceRead], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }) { _ in throw CancellationError() }
        let relay = FixtureRelay(job: nil) { _ in XCTFail("No job was sent") }
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start()
        let announcement = await relay.announcement
        XCTAssertEqual((announcement["agents"] as? [[String: Any]])?.compactMap { $0["id"] as? String }, [ordinary.id])
        let bytes = try JSONSerialization.data(withJSONObject: announcement)
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains(direct.agent.tokenHash))
        await connection.stop()
    }
    func testViewerClaimPublishesRotatedAuthorityBeforeReplyAndRejectsPhotographedCode() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, original) = try await authority.createViewer(name: "Owner viewer")
        let now = Int64(Date().timeIntervalSince1970 * 1000), replacement = PairingClaim.newToken()
        let frame: [String: Any] = ["type": "job", "id": "claim-once", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30000, "created_at": now,
            "payload": ["method": "POST", "path": HostViewerPairing.claimPath, "body": ["token_hash": PairingClaim.tokenHash(replacement)]]]
        let received = expectation(description: "Actual transport delivered claim")
        let relay = FixtureRelay(job: frame) { data in
            do {
                let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                XCTAssertEqual(object["status"] as? Int, 200)
            } catch { XCTFail("Invalid claim envelope") }
            received.fulfill()
        }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }) { _ in throw CancellationError() }
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start()
        await fulfillment(of: [received], timeout: 5)
        let published = await relay.resultAnnouncements["claim-once"]
        let announced = try XCTUnwrap((published?["agents"] as? [[String: Any]])?.first)
        XCTAssertEqual(announced["token_hash"] as? String, PairingClaim.tokenHash(replacement))
        XCTAssertNotEqual(announced["generation"] as? String, agent.generation)
        let rows = try XCTUnwrap(published?["agents"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[1]["generation"] as? String, agent.generation)
        XCTAssertEqual(rows[1]["id"] as? String, agent.id)
        XCTAssertEqual(rows[1]["token_hash"] as? String, PairingClaim.tokenHash(original))
        XCTAssertTrue(rows.allSatisfy { $0["portal"] as? Bool == true })
        let expired = try await authority.relayPublication(now: now + 35_001)
        XCTAssertEqual(expired.agents.count, 1)
        XCTAssertNil(expired.refreshAt)

        // Optional handoff to the offline Worker contract test. Both bearers
        // were generated for this fixture and have never been enrolled.
        if let path = ProcessInfo.processInfo.environment["BRIDGIT_VIEWER_WORKER_FIXTURE_OUT"] {
            let initial = await relay.firstAnnouncement
            let result = await relay.finalFrames["claim-once"]
            let fixture: [String: Any] = ["old": original, "replacement": replacement, "initial": try XCTUnwrap(initial),
                                          "during": try XCTUnwrap(published), "result": try XCTUnwrap(result),
                                          "expired": ["type": "agents", "agents": expired.agents]]
            let bytes = try JSONSerialization.data(withJSONObject: fixture)
            XCTAssertTrue(FileManager.default.createFile(atPath: path, contents: bytes, attributes: [.posixPermissions: 0o600]))
        }

        let verified = await relay.verified(); XCTAssertTrue(verified)
        await connection.stop()
    }
    func testAdminCanPausePeerWithoutCancellingItsOwnRelayReply() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (admin, _) = try await authority.create(name: "Administrator", operations: [.administrator], applets: [])
        let (peer, _) = try await authority.create(name: "Controller", operations: [.configurationWrite], applets: [])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let command: [String: Any] = ["method": "PATCH", "path": "/v1/agents/" + peer.id, "body": ["paused": true], "idempotency_key": "pause-peer"]
        let frame: [String: Any] = ["type": "job", "id": "pause", "seq": 1, "agent_id": admin.id, "generation": admin.generation, "device_id": identity.deviceID, "mode": admin.mode, "deadline": now + 30000, "created_at": now, "payload": command]
        let received = expectation(description: "Relay delivered the administration result")
        let relay = FixtureRelay(job: frame) { data in
            do {
                let reply = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                XCTAssertEqual(reply["status"] as? Int, 200)
            } catch { XCTFail(error.localizedDescription) }
            received.fulfill()
        }
        let service = AgentControlFixture(authority)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() }, configuration: service)
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await service.use(connection)
        await connection.start()
        await fulfillment(of: [received], timeout: 5)
        let announcement = await relay.announcement
        XCTAssertEqual((announcement["agents"] as? [[String: Any]])?.compactMap { $0["id"] as? String }, [admin.id])
        await connection.stop()
        await service.use(nil)
    }

    func testSealedResultBoundaryReturnsDecodable413WithoutTruncation() throws {
        let identity = try RelayHostIdentity.ephemeral(), peer = P256.KeyAgreement.PrivateKey()
        let job = HostJob(id: "job-boundary", sequence: 9, agentID: "agent", generation: "generation", deviceID: identity.deviceID, mode: "sealed", deadline: 1_800_000_000_000, createdAt: 1, request: HostRequest(method: "GET", path: "/v1/applets"), sealedRequestID: "request-boundary")
        let empty = HostResponse(contentType: "text/csv; charset=utf-8", data: Data(), headers: ["Cache-Control": "no-store"])
        let innerBase = try JSONSerialization.data(withJSONObject: empty.envelope, options: [.withoutEscapingSlashes]).count
        let emptyBytes = try RelayHostConnection.encodedResult(empty, identity: identity, peer: peer.publicKey.x963Representation, job: job)
        let outerBase = emptyBytes.count - 4 * ((innerBase + 16 + 2) / 3)
        let limit = 8 * 1024 * 1024
        func size(_ n: Int) -> Int { outerBase + 4 * ((innerBase + 4 * ((n + 2) / 3) + 16 + 2) / 3) }
        var lo = 0, hi = limit
        while lo < hi { let mid = (lo + hi + 1) / 2; if size(mid) <= limit { lo = mid } else { hi = mid - 1 } }
        func decoded(_ data: Data) throws -> [String: Any] {
            let outer = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            let enc = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(outer["enc"] as? String)))
            let ciphertext = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(outer["ct"] as? String)))
            var recipient = try HPKE.Recipient(privateKey: peer, ciphersuite: .init(kem: .P256_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_128), info: Data("bridgit-relay-v1".utf8), encapsulatedKey: enc, authenticatedBy: P256.KeyAgreement.PublicKey(x963Representation: identity.hpkePublic))
            let aad = RelaySealed.aad(device: job.deviceID, agent: job.agentID, generation: job.generation, requestID: job.sealedRequestID!, deadline: job.deadline, sequence: job.sequence, direction: "response")
            return try XCTUnwrap(JSONSerialization.jsonObject(with: recipient.open(ciphertext, authenticating: aad)) as? [String: Any])
        }
        var plainLo = 0, plainHi = limit
        while plainLo < plainHi {
            let mid = (plainLo + plainHi + 1) / 2
            if innerBase + 4 * ((mid + 2) / 3) <= limit { plainLo = mid } else { plainHi = mid - 1 }
        }
        let plainBody = Data(repeating: 0xff, count: plainLo)
        let plainBytes = try RelayHostConnection.encodedResult(.init(contentType: empty.contentType, data: plainBody, headers: empty.headers), identity: identity, peer: nil, job: job)
        let plainResult = try XCTUnwrap(JSONSerialization.jsonObject(with: plainBytes) as? [String: Any])
        XCTAssertEqual(plainResult["status"] as? Int, 200)
        XCTAssertEqual(Data(base64Encoded: try XCTUnwrap(plainResult["body_base64"] as? String)), plainBody)
        let plainTooLarge = try RelayHostConnection.encodedResult(.init(contentType: empty.contentType, data: Data(repeating: 0xff, count: plainLo + 1), headers: empty.headers), identity: identity, peer: nil, job: job)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: plainTooLarge) as? [String: Any])?["status"] as? Int, 413)
        let body = Data(repeating: 0xff, count: lo)
        let acceptedDelivery = try RelayHostConnection.encodedDelivery(.init(contentType: empty.contentType, data: body, headers: empty.headers), identity: identity, peer: peer.publicKey.x963Representation, job: job)
        XCTAssertTrue(acceptedDelivery.deliveredOriginal)
        let accepted = acceptedDelivery.data
        XCTAssertEqual(accepted.count, size(lo)); XCTAssertLessThanOrEqual(accepted.count, limit)
        let result = try decoded(accepted)
        XCTAssertEqual(result["status"] as? Int, 200)
        XCTAssertEqual(Data(base64Encoded: try XCTUnwrap(result["body_base64"] as? String)), body)
        let rejectedDelivery = try RelayHostConnection.encodedDelivery(.init(contentType: empty.contentType, data: Data(repeating: 0xff, count: lo + 1), headers: empty.headers), identity: identity, peer: peer.publicKey.x963Representation, job: job)
        XCTAssertFalse(rejectedDelivery.deliveredOriginal)
        let error = try decoded(rejectedDelivery.data)
        XCTAssertEqual((error["headers"] as? [String: String])?["X-Bridgit-API-Revision"], String(HostAPIChanges.current))
        XCTAssertEqual(error["status"] as? Int, 413)
        let failure = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(error["body_base64"] as? String)))
        XCTAssertEqual((try JSONSerialization.jsonObject(with: failure) as? [String: Any])?["error"] as? String, "result_too_large")
    }

    func testActivityExportStopsDeliveryWhenOwnerNarrowsVisibility() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (reader, _) = try await authority.create(name: "Export reader", operations: [.log], applets: [])
        let (other, _) = try await authority.create(name: "Other agent", operations: [.log], applets: [])
        let note = try HostLogNote(.init(method: "POST", path: "/v1/log",
            body: ["message": String(repeating: "private fixture content ", count: 40)]))
        for _ in 0..<40 { _ = try await store.recordHostNote(note, agent: other) }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let id = "fixture-activity-export"
        let frame: [String: Any] = ["type": "job", "id": id, "seq": 1, "agent_id": reader.id,
            "generation": reader.generation, "device_id": identity.deviceID, "mode": "connector",
            "deadline": now + 30_000, "created_at": now,
            "payload": ["method": "GET", "path": "/v1/log/export", "query": ["kind": "note"]]]
        let relay = FixtureRelay(job: frame) { _ in XCTFail("The all-Activity result completed after visibility narrowed.") }
        await relay.holdResultAcknowledgements()
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!,
            identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start()
        for _ in 0..<200 {
            if await relay.waitingForResultAcknowledgement { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let reachedFirstChunk = await relay.waitingForResultAcknowledgement
        XCTAssertTrue(reachedFirstChunk)
        try await store.setHostLogVisibility(.own)
        try await relay.releaseResultAcknowledgements()
        for _ in 0..<200 {
            if await relay.failures[id] != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let failure = await relay.failures[id]
        let delivered = await connection.completedJobs
        XCTAssertEqual(failure, "log_visibility_changed")
        XCTAssertEqual(delivered, 0)
        await connection.stop()
    }

    func testOversizeSealedActionReturns413WithoutReplayingAction() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let peer = P256.KeyAgreement.PrivateKey(), counter = OversizeActionCounter()
        let (agent, _) = try await authority.create(name: "Synthetic action", operations: [.cameraSnapshot], applets: [], peer: peer.publicKey.x963Representation.base64EncodedString())
        let now = Int64(Date().timeIntervalSince1970 * 1000), deadline = now + 30000, requestID = "oversize-action-001"
        let aad = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: requestID, deadline: deadline, sequence: 1, direction: "request")
        let request: [String: Any] = ["method": "POST", "path": "/v1/camera/snapshot", "idempotency_key": "synthetic-oversize"]
        let plaintext = try JSONSerialization.data(withJSONObject: ["created_at": now, "request": request])
        var envelope = try RelaySealed.seal(privateKey: peer, peer: identity.hpkePublic, plaintext: plaintext, aad: aad)
        envelope["request_id"] = requestID; envelope["seq"] = 1; envelope["deadline"] = deadline
        let frame: [String: Any] = ["type": "job", "id": "oversize-relay-job", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "sealed", "deadline": deadline, "created_at": now, "payload": envelope]
        let received = expectation(description: "Encrypted oversize error")
        let relay = FixtureRelay(job: frame) { data in
            defer { received.fulfill() }
            do {
                let outer = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                let responseAAD = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: requestID, deadline: deadline, sequence: 1, direction: "response")
                let opened = try RelaySealed.open(privateKey: peer, peer: identity.hpkePublic, envelope: outer, aad: responseAAD)
                let result = try XCTUnwrap(JSONSerialization.jsonObject(with: opened) as? [String: Any])
                XCTAssertEqual(result["status"] as? Int, 413)
                let bytes = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(result["body_base64"] as? String)))
                XCTAssertEqual((try JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["error"] as? String, "result_too_large")
            } catch { XCTFail("Invalid encrypted error: \(error)") }
        }
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, sensor: { _, _ in await counter.run() })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await fulfillment(of: [received], timeout: 20)
        let calls = await counter.calls
        XCTAssertEqual(calls, 1)
        let failures = await relay.failures
        XCTAssertTrue(failures.isEmpty)
        await connection.stop()
    }

    func testDeniedSealedRequestIsAttributedOnlyAfterAuthentication() async throws {
        for tampered in [false, true] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
            let authority = HostAuthority(store: store, deviceID: identity.deviceID)
            let peer = P256.KeyAgreement.PrivateKey()
            let (agent, _) = try await authority.create(name: "Verified agent", operations: [.deviceRead], applets: [], peer: peer.publicKey.x963Representation.base64EncodedString())
            let now = Int64(Date().timeIntervalSince1970 * 1000), deadline = now + 30000, requestID = "denied-request-001"
            let aad = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: requestID, deadline: deadline, sequence: 1, direction: "request")
            let bytes = try JSONSerialization.data(withJSONObject: ["created_at": now, "request": ["method": "POST", "path": "/v1/camera/snapshot"]])
            var payload = try RelaySealed.seal(privateKey: peer, peer: identity.hpkePublic, plaintext: bytes, aad: aad)
            if tampered { payload["ct"] = Data(repeating: 0, count: 32).base64EncodedString() }
            payload["request_id"] = requestID; payload["seq"] = 1; payload["deadline"] = deadline
            let frame: [String: Any] = ["type": "job", "id": "denied-job", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "sealed", "deadline": deadline, "created_at": now, "payload": payload]
            let relay = FixtureRelay(job: frame) { _ in XCTFail("Denied request returned a successful result") }
            let engine = AppletEngine(store: store) { _, _, _ in [:] }
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine) { _ in throw CancellationError() }
            let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
            await connection.start()
            for _ in 0..<500 { if await relay.failures["denied-job"] != nil { break }; try await Task.sleep(nanoseconds: 1_000_000) }
            let failure = await relay.failures["denied-job"]
            XCTAssertEqual(failure, "invalid_job")
            let history = try await store.hostActivity(kinds: [.request])
            XCTAssertEqual(history.count, tampered ? 0 : 1)
            if !tampered {
                XCTAssertEqual(history.first?.agent, agent.name)
                XCTAssertEqual(history.first?.operation, HostOperation.cameraSnapshot.rawValue)
                XCTAssertEqual(history.first?.status, 403)
            }
            await connection.stop()
        }
    }
    func testSignedHandshakeAndSealedJobWithIndependentRelayIdentifiers() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let peer = P256.KeyAgreement.PrivateKey()
        let (agent, token) = try await authority.create(name: "Protocol fixture", operations: [.deviceRead], applets: [], peer: peer.publicKey.x963Representation.base64EncodedString())
        let now = Int64(Date().timeIntervalSince1970 * 1000), deadline = now + 30000
        let requestID = "sealed-request-001"
        let aad = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: requestID, deadline: deadline, sequence: 1, direction: "request")
        let plaintext = try JSONSerialization.data(withJSONObject: ["created_at": now, "request": ["method": "GET", "path": "/v1/health"]])
        var envelope = try RelaySealed.seal(privateKey: peer, peer: identity.hpkePublic, plaintext: plaintext, aad: aad)
        envelope["request_id"] = requestID; envelope["seq"] = 1; envelope["deadline"] = deadline
        let frame: [String: Any] = ["type": "job", "id": "different-relay-job-99", "seq": 99, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "sealed", "deadline": deadline, "created_at": now + 3000, "payload": envelope]
        let received = expectation(description: "Authenticated health reply")
        let relay = FixtureRelay(job: frame) { data in
            do {
                let response = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                XCTAssertEqual(response["request_id"] as? String, requestID); XCTAssertEqual(response["seq"] as? Int, 1)
                let responseAAD = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: requestID, deadline: deadline, sequence: 1, direction: "response")
                let opened = try RelaySealed.open(privateKey: peer, peer: identity.hpkePublic, envelope: response, aad: responseAAD)
                let result = try JSONSerialization.jsonObject(with: opened) as! [String: Any]
                XCTAssertEqual(result["status"] as? Int, 200)
                let body = try JSONSerialization.jsonObject(with: Data(base64Encoded: result["body_base64"] as! String)!) as! [String: Any]
                XCTAssertEqual(body["device_id"] as? String, identity.deviceID)
            } catch { XCTFail("Invalid reply: \(error)") }; received.fulfill()
        }
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine) { _ in throw CancellationError() }
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await fulfillment(of: [received], timeout: 5)
        let verified = await relay.verified(); XCTAssertTrue(verified)
        let announcement = await relay.announcement
        let text = String(decoding: try JSONSerialization.data(withJSONObject: announcement), as: UTF8.self)
        XCTAssertFalse(text.contains(token)); XCTAssertFalse(text.contains("peer"))
        await connection.stop()
    }
    func testAppleTokenCannotBeSentToLegacyFCMOnlyRelay() async throws {
        for providers: [String]? in [nil, ["apns"]] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
            let authority = HostAuthority(store: store, deviceID: identity.deviceID)
            let engine = AppletEngine(store: store) { _, _, _ in [:] }
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine) { _ in throw CancellationError() }
            let relay = FixtureRelay(job: nil, providers: providers) { _ in }
            let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
            await connection.start()
            let registration = try RelayPushRegistration(apns: Data(repeating: 0xab, count: 32), environment: "production")
            do { try await connection.publishPush(registration); XCTAssertNotNil(providers) }
            catch let error as DeviceAPIError { XCTAssertNil(providers); XCTAssertEqual(error.code, "push_not_supported") }
            let frames = await relay.pushFrames
            XCTAssertEqual(frames.count, providers == nil ? 0 : 1)
            if let frame = frames.first { XCTAssertEqual(frame["provider"] as? String, "apns"); XCTAssertEqual(frame["environment"] as? String, "production") }
            await connection.stop()
        }
        XCTAssertThrowsError(try RelayPushRegistration(apns: Data([1]), environment: "https://invalid.test"))
    }

}

extension RelayHostProtocolTests {
    func testCancellationTraversesFullRelayWithTwoBusyJobsInBothModes() async throws {
        for sealed in [false, true] { try await fullRelayCancellation(sealed: sealed) }
    }
    private func fullRelayCancellation(sealed: Bool) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID), peer = P256.KeyAgreement.PrivateKey()
        let (agent, _) = try await authority.create(name: "Cancellation fixture", operations: [.deviceRead, .interactionsRead], applets: [], peer: sealed ? peer.publicKey.x963Representation.base64EncodedString() : nil)
        let card = try await store.createInteraction(owner: agent.id + ":" + agent.generation, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        let now = Int64(Date().timeIntervalSince1970 * 1000), deadline = now + 30000
        let initialized = expectation(description: "Initialized"), firstCancelled = expectation(description: "First request and cancellation reply"), secondCancelled = expectation(description: "Second request and cancellation reply")
        firstCancelled.expectedFulfillmentCount = 2; secondCancelled.expectedFulfillmentCount = 2
        let sequences: [String: Int64] = ["init-job": 1, "wait-one": 2, "wait-two": 3, "busy-job": 4, "cancel-one": 5, "cancel-two": 6]
        let relay = FixtureRelay(job: nil, resultForJob: { relayID, bytes in
            do {
                let name = String(relayID.dropFirst("relay-".count))
                var response = try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
                if sealed {
                    let aad = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: name, deadline: deadline, sequence: sequences[name]!, direction: "response")
                    let opened = try RelaySealed.open(privateKey: peer, peer: identity.hpkePublic, envelope: response, aad: aad)
                    response = try JSONSerialization.jsonObject(with: opened) as! [String: Any]
                }
                if name == "init-job" { XCTAssertEqual(response["status"] as? Int, 200); initialized.fulfill() }
                else if name.hasPrefix("cancel") { XCTAssertEqual(response["status"] as? Int, 202); (name == "cancel-one" ? firstCancelled : secondCancelled).fulfill() }
                else if name.hasPrefix("wait") {
                    let value = try JSONSerialization.jsonObject(with: Data(base64Encoded: response["body_base64"] as! String)!) as! [String: Any]
                    XCTAssertEqual((value["error"] as? [String: Any])?["code"] as? Int, -32800)
                    (name == "wait-one" ? firstCancelled : secondCancelled).fulfill()
                }
            } catch { XCTFail("Invalid cancellation reply: \(error)") }
        }, result: { _ in })
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        func frame(_ name: String, request: [String: Any]) throws -> [String: Any] {
            let sequence = sequences[name]!
            var payload = request
            if sealed {
                let aad = RelaySealed.aad(device: identity.deviceID, agent: agent.id, generation: agent.generation, requestID: name, deadline: deadline, sequence: sequence, direction: "request")
                payload = try RelaySealed.seal(privateKey: peer, peer: identity.hpkePublic, plaintext: JSONSerialization.data(withJSONObject: ["created_at": now, "request": request]), aad: aad)
                payload["request_id"] = name; payload["deadline"] = deadline; payload["seq"] = sequence
            }
            return ["type": "job", "id": "relay-" + name, "seq": sequence, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": sealed ? "sealed" : "connector", "deadline": deadline, "created_at": now, "payload": payload]
        }
        await connection.start()
        let initBody: [String: Any] = ["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": ["protocolVersion": "2025-11-25", "capabilities": [:], "clientInfo": ["name": "Fixture", "version": "1"]]]
        try await relay.enqueue(frame("init-job", request: ["method": "POST", "path": "/v1/mcp", "body": initBody]))
        await fulfillment(of: [initialized], timeout: 5)
        for (name, rpcID) in [("wait-one", "first"), ("wait-two", "second")] {
            let body: [String: Any] = ["jsonrpc": "2.0", "id": rpcID, "method": "tools/call", "params": ["name": "interactions_get", "arguments": ["id": card["interaction_id"]!, "query": ["wait": 25]]]]
            try await relay.enqueue(frame(name, request: ["method": "POST", "path": "/v1/mcp", "body": body]))
        }
        for _ in 0..<200 { if await dispatcher.runningMCPRequests() == 2 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        let busy = await dispatcher.runningMCPRequests(); XCTAssertEqual(busy, 2)
        try await relay.enqueue(frame("busy-job", request: ["method": "GET", "path": "/v1/health"]))
        for _ in 0..<200 { if await relay.failures["relay-busy-job"] != nil { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        let refused = await relay.failures["relay-busy-job"]; XCTAssertEqual(refused, "device_busy")
        let cancel: (String) -> [String: Any] = { ["jsonrpc": "2.0", "method": "notifications/cancelled", "params": ["requestId": $0]] }
        try await relay.enqueue(frame("cancel-one", request: ["method": "POST", "path": "/v1/mcp", "body": cancel("first")]))
        await fulfillment(of: [firstCancelled], timeout: 5)
        try await relay.enqueue(frame("cancel-two", request: ["method": "POST", "path": "/v1/mcp", "body": cancel("second")]))
        await fulfillment(of: [secondCancelled], timeout: 5)
        await connection.stop()
    }
}

extension RelayHostProtocolTests {
    func testWakeRemovalIsOrderedAfterAnInFlightTokenAndRejectsLateOlderConsent() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let relay = FixtureRelay(job: nil, providers: ["apns"], result: { _ in })
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await relay.holdNextPush()
        let token = try RelayPushRegistration(apns: Data([0xab]), environment: "sandbox")
        let earlier = Task { try await connection.publishPush(token, revision: 1) }
        for _ in 0..<10000 { if await relay.pushBlocked { break }; await Task.yield() }
        let blocked = await relay.pushBlocked; XCTAssertTrue(blocked)
        let off = Task { try await connection.publishPush(nil, revision: 2) }
        for _ in 0..<10000 { if await connection.currentPushRevision() == 2 { break }; await Task.yield() }
        do { try await connection.publishPush(token, revision: 1); XCTFail("Late token restored withdrawn consent") } catch is CancellationError {}
        await relay.releasePush(); try await earlier.value; try await off.value
        let frames = await relay.pushFrames
        XCTAssertEqual(frames.count, 2); XCTAssertEqual(frames.first?["token"] as? String, "ab")
        XCTAssertTrue(frames.last?["token"] is NSNull, "Removal must be the final frame")
        await connection.stop()
    }
}

private actor DeliveryConfiguration: HostConfigurationService {
    private(set) var applied = 0
    private(set) var created = 0
    let completed: () -> Void
    let authority: HostAuthority
    init(authority: HostAuthority, completed: @escaping () -> Void) { self.authority = authority; self.completed = completed }
    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        created += 1
        return await attachDelivery(to: try .json(["status": "queued", "request_id": UUID().uuidString], status: 202), agent: agent)
    }
    func attachDelivery(to response: HostResponse, agent: HostAgent) async -> HostResponse {
        var response = response
        response.afterDelivery = { await self.apply() }
        return response
    }
    private func apply() async { applied += 1; await authority.setConnected(false); completed() }
}
private actor OriginalDeliveryProbe: HostConfigurationService {
    let oversized: Bool
    private(set) var ordinary = 0
    private(set) var original = 0
    init(oversized: Bool) { self.oversized = oversized }
    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        var response = HostResponse(contentType: "application/octet-stream", data: Data(repeating: 42, count: oversized ? 7 * 1024 * 1024 : 8))
        response.afterDelivery = { await self.ordinaryDelivered() }
        response.afterOriginalDelivery = { await self.originalDelivered() }
        return response
    }
    private func ordinaryDelivered() { ordinary += 1 }
    private func originalDelivered() { original += 1 }
}

extension RelayHostProtocolTests {
    func testOriginalDeliveryCallbackTracksActualRelayResult() async throws {
        for oversized in [false, true] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
            let authority = HostAuthority(store: store, deviceID: identity.deviceID)
            let (agent, _) = try await authority.create(name: "Delivery", operations: [.configurationRead], applets: [])
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let frame: [String: Any] = ["type": "job", "id": "delivery", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30000, "created_at": now, "payload": ["method": "GET", "path": "/v1/settings"]]
            let received = expectation(description: "Relay result delivered")
            let relay = FixtureRelay(job: frame) { bytes in
                let envelope = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any]
                XCTAssertEqual(envelope?["status"] as? Int, oversized ? 413 : 200); received.fulfill()
            }
            let probe = OriginalDeliveryProbe(oversized: oversized)
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() }, configuration: probe)
            let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
            await connection.start(); await fulfillment(of: [received], timeout: 10)
            for _ in 0..<100 {
                let ordinary = await probe.ordinary, original = await probe.original
                if ordinary == 1 && (oversized || original == 1) { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            let ordinary = await probe.ordinary, original = await probe.original
            XCTAssertEqual(ordinary, 1); XCTAssertEqual(original, oversized ? 0 : 1)
            await connection.stop()
        }
    }

    func testConfigurationCannotDisconnectCallerUntilResultAcknowledged() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, _) = try await authority.create(name: "Control fixture", operations: [.configurationWrite], applets: [])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let frame: [String: Any] = ["type": "job", "id": "configuration-job", "seq": 1, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30000, "created_at": now,
            "payload": ["method": "POST", "path": "/v1/settings", "idempotency_key": "stop-host", "body": ["field": "host_enabled", "value": false]]]
        let received = expectation(description: "Accepted operation delivered"), applied = expectation(description: "Applied after ack")
        let configuration = DeliveryConfiguration(authority: authority) { applied.fulfill() }
        let relay = FixtureRelay(job: frame) { bytes in
            let envelope = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any]
            XCTAssertEqual(envelope?["status"] as? Int, 202); received.fulfill()
        }
        await relay.holdResultAcknowledgements()
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: configuration)
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await fulfillment(of: [received], timeout: 5)
        let before = await configuration.applied; XCTAssertEqual(before, 0)
        try await relay.releaseResultAcknowledgements()
        await fulfillment(of: [applied], timeout: 5)
        let after = await configuration.applied; XCTAssertEqual(after, 1)
        let delivered = await connection.completedJobs; XCTAssertEqual(delivered, 1)
        await connection.stop()
    }
}

extension RelayHostProtocolTests {
    func testLostFinalAckCanRecoverWithSameKeyAfterReconnectWithoutRepeatingOperation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, _) = try await authority.create(name: "Control fixture", operations: [.configurationWrite], applets: [])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func frame(_ seq: Int) -> [String: Any] {
            ["type": "job", "id": "configuration-\(seq)", "seq": seq, "agent_id": agent.id, "generation": agent.generation, "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30000, "created_at": now,
             "payload": ["method": "POST", "path": "/v1/settings", "idempotency_key": "same-action", "body": ["field": "host_enabled", "value": false]]]
        }
        let first = expectation(description: "Result saved by relay but ack lost"), retry = expectation(description: "Retried same result"), applied = expectation(description: "Recovered operation applied")
        let configuration = DeliveryConfiguration(authority: authority) { applied.fulfill() }
        let relay = FixtureRelay(job: frame(1), resultForJob: { id, _ in
            if id == "configuration-1" { first.fulfill() } else { retry.fulfill() }
        }) { _ in }
        await relay.holdResultAcknowledgements()
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: configuration)
        let connection = try RelayHostConnection(base: URL(string: "https://relay.invalid/d/" + identity.deviceID)!, identity: identity, authority: authority, dispatcher: dispatcher, transport: relay)
        await connection.start(); await fulfillment(of: [first], timeout: 5)
        await connection.stop()
        let before = await configuration.applied; XCTAssertEqual(before, 0)
        try await relay.releaseResultAcknowledgements()
        await connection.start(); try await relay.enqueue(frame(2))
        await fulfillment(of: [retry, applied], timeout: 5)
        let created = await configuration.created, count = await configuration.applied
        XCTAssertEqual(created, 1); XCTAssertEqual(count, 1)
        await connection.stop()
    }
}

extension RelayHostProtocolTests {
    func testFourAuthenticatedFeedbackWaitsTraverseRelayAndPreserveOrdinarySlot() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        let identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        let (agent, _) = try await authority.create(name: "Feedback reporter", operations: [.deviceRead], applets: [])
        let gate = FeedbackWaitGate()
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, feedback: { _, _ in
                await gate.hold()
                return (try .json(["reports": []]), false)
            })
        let refused = expectation(description: "Fifth feedback wait refused")
        let health = expectation(description: "Ordinary health while four waits hold")
        let finished = expectation(description: "All four feedback waits finished")
        finished.expectedFulfillmentCount = 4
        let transport = FixtureRelay(job: nil, resultForJob: { id, bytes in
            do {
                let reply = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
                if id == "feedback-5" {
                    XCTAssertEqual(reply["status"] as? Int, 429)
                    let encoded = try XCTUnwrap(reply["body_base64"] as? String)
                    let body = try XCTUnwrap(Data(base64Encoded: encoded))
                    XCTAssertTrue(String(decoding: body, as: UTF8.self).contains("too_many_waits"))
                    refused.fulfill()
                } else if id == "feedback-health" {
                    XCTAssertEqual(reply["status"] as? Int, 200)
                    health.fulfill()
                } else {
                    XCTAssertEqual(reply["status"] as? Int, 200)
                    finished.fulfill()
                }
            } catch { XCTFail("Invalid relay feedback result: " + error.localizedDescription) }
        }, result: { _ in })
        let base = try XCTUnwrap(URL(string: "https://relay.invalid/d/" + identity.deviceID))
        let connection = try RelayHostConnection(base: base, identity: identity, authority: authority,
                                                 dispatcher: dispatcher, transport: transport)
        addTeardownBlock {
            await gate.release()
            await connection.stop()
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        await connection.start()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func frame(_ sequence: Int, id: String, path: String, query: [String: Any] = [:]) -> [String: Any] {
            ["type": "job", "id": id, "seq": sequence, "agent_id": agent.id, "generation": agent.generation,
             "device_id": identity.deviceID, "mode": "connector", "deadline": now + 30_000,
             "created_at": now, "payload": ["method": "GET", "path": path, "query": query]]
        }
        let query = ["since": "2026-11-07T12:00:00Z", "wait": "20"]
        for sequence in 1...4 {
            try await transport.enqueue(frame(sequence, id: "feedback-\(sequence)", path: "/v1/feedback", query: query))
        }
        for _ in 0..<100 {
            if await gate.count == 4 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard await gate.count == 4 else { XCTFail("Expected four authenticated waits to enter."); return }
        try await transport.enqueue(frame(5, id: "feedback-5", path: "/v1/feedback", query: query))
        try await transport.enqueue(frame(6, id: "feedback-health", path: "/v1/health"))
        await fulfillment(of: [refused, health], timeout: 5)
        let active = await gate.count
        XCTAssertEqual(active, 4)
        await gate.release()
        await fulfillment(of: [finished], timeout: 5)
    }
}

extension RelayHostProtocolTests {
    func testRelayReconnectInvalidatesFeedbackPermitAndAcceptsFreshMutation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        let identity = try RelayHostIdentity.ephemeral()
        let authority = HostAuthority(store: store, deviceID: identity.deviceID)
        await authority.setState(enabled: false, awake: true, foreground: true)
        let (agent, _) = try await authority.create(name: "Feedback reporter", operations: [.deviceRead], applets: [])
        let gate = FeedbackWaitGate()
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                                        profile: { _ in throw CancellationError() })
        let transport = FixtureRelay(job: nil, result: { _ in })
        let base = try XCTUnwrap(URL(string: "https://relay.invalid/d/" + identity.deviceID))
        let connection = try RelayHostConnection(base: base, identity: identity, authority: authority,
                                                 dispatcher: dispatcher, transport: transport)
        addTeardownBlock {
            await gate.release()
            await connection.stop()
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        await connection.start()
        let initial = try await authority.withFeedbackMutation(agent, requiresFixer: false) { _, permit in
            try permit.commit { "initial" }
        }
        XCTAssertEqual(initial, "initial")
        let held = Task {
            try await authority.withFeedbackMutation(agent, requiresFixer: false) { _, permit in
                await gate.hold()
                return try permit.commit { "stale" }
            }
        }
        for _ in 0..<100 {
            if await gate.count == 1 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard await gate.count == 1 else {
            await gate.release()
            _ = await held.result
            XCTFail("Feedback write did not reach the held store boundary.")
            return
        }
        await connection.stop()
        await connection.start()
        await gate.release()
        switch await held.result {
        case .success: XCTFail("The old permit survived a real relay connection restart.")
        case .failure(let error): XCTAssertEqual((error as? DeviceAPIError)?.code, "foreground_required")
        }
        let fresh = try await authority.withFeedbackMutation(agent, requiresFixer: false) { _, permit in
            await authority.setConnected(true)
            return try permit.commit { "fresh" }
        }
        XCTAssertEqual(fresh, "fresh")
    }
}
