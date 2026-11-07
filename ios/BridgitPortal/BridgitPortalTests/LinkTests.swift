import CryptoKit
import XCTest
@testable import Bridgit_Portal

/// A fake team backend that checks every signature the way feedback/src/worker.mjs does.
final class FakeBackend: URLProtocol {
    nonisolated(unsafe) static var keys: [String: P256.Signing.PublicKey] = [:]
    nonisolated(unsafe) static var names: [String: [String: Any]] = [:]
    nonisolated(unsafe) static var calls: [String] = []
    nonisolated(unsafe) static var forgetInstalls = false
    nonisolated(unsafe) static var resolveAnswer: (Int, [String: Any]) = (404, ["error": "code_invalid"])
    nonisolated(unsafe) static var proofs: [String] = []

    nonisolated(unsafe) static var jobs: [(bearer: String, request: [String: Any])] = []
    nonisolated(unsafe) static var jobAnswer: (Int, [String: Any]) = (200, [:])

    static func reset() { keys = [:]; names = [:]; calls = []; forgetInstalls = false; proofs = []; jobs = [] }

    static var session: URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeBackend.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    private func body() -> Data {
        if let b = request.httpBody { return b }
        guard let s = request.httpBodyStream else { return Data() }
        s.open(); defer { s.close() }
        var out = Data(), buf = [UInt8](repeating: 0, count: 4096)
        while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; out.append(buf, count: n) }
        return out
    }

    private func verify(_ raw: Data, key: P256.Signing.PublicKey?) -> String? {
        guard let id = request.value(forHTTPHeaderField: "X-Bridgit-Install"), let key,
              let sig = request.value(forHTTPHeaderField: "X-Bridgit-Signature") else { return nil }
        let parts = Dictionary(uniqueKeysWithValues: sig.split(separator: ",").compactMap { p -> (String, String)? in
            guard let i = p.firstIndex(of: "=") else { return nil }
            return (String(p[..<i]), String(p[p.index(after: i)...]))
        })
        guard let at = parts["at"].flatMap(Int64.init), let nonce = parts["nonce"], let s = parts["sig"].flatMap({ Data(base64Encoded: $0) }),
              let signature = try? P256.Signing.ECDSASignature(rawRepresentation: s) else { return nil }
        let url = request.url!
        let text = InstallSigning.signingText(id: id, method: request.httpMethod!, pathAndQuery: url.path + (url.query.map { "?" + $0 } ?? ""),
                                              at: at, nonce: nonce, body: raw)
        return key.isValidSignature(signature, for: Data(text.utf8)) ? id : nil
    }

    override func startLoading() {
        let raw = body()
        let json = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] ?? [:]
        let path = request.url!.path
        Self.calls.append(request.httpMethod! + " " + path)
        var status = 200
        var out: [String: Any] = [:]
        func own(_ id: String) -> [String: Any]? { Self.names[id] }
        switch (request.httpMethod!, path) {
        case ("POST", let p) where p.hasPrefix("/d/") && p.hasSuffix("/jobs"):
            Self.jobs.append((request.value(forHTTPHeaderField: "Authorization") ?? "", json["request"] as? [String: Any] ?? [:]))
            let body = (try? JSONSerialization.data(withJSONObject: Self.jobAnswer.1)) ?? Data()
            out = ["job_id": "job_1", "response": ["status": Self.jobAnswer.0, "body_base64": body.base64EncodedString()]]
        case ("POST", "/v1/install/challenge"):
            out = ["challenge": Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()]
        case ("POST", "/v1/install/register"):
            let spki = Data(base64Encoded: json["public_key"] as? String ?? "") ?? Data()
            let key = try? P256.Signing.PublicKey(derRepresentation: spki)
            let id = InstallSigning.installId(spki: spki)
            if verify(raw, key: key) == id, (json["attestation_chain"] as? [Any])?.isEmpty == true {
                Self.keys[id] = key
                status = 201; out = ["install_id": id, "trust": "unverified", "reason": "no_chain"]
            } else { status = 401; out = ["error": "bad_signature"] }
        default:
            let header = request.value(forHTTPHeaderField: "X-Bridgit-Install") ?? ""
            if Self.forgetInstalls { Self.forgetInstalls = false; Self.keys = [:] }
            guard let id = verify(raw, key: Self.keys[header]) else {
                status = 401; out = ["error": Self.keys[header] == nil ? "unknown_install" : "bad_signature"]; break
            }
            switch (request.httpMethod!, path) {
            case ("POST", "/v1/names"):
                let n = json["name"] as? String ?? ""
                if Self.names.values.contains(where: { $0["name"] as? String == n }) && own(id)?["name"] as? String != n {
                    status = 409; out = ["error": "name_taken", "message": "\(n) is taken. Suggest another."]
                } else {
                    let row: [String: Any] = ["name": n, "label": NSNull(), "visible": true, "heartbeat_every_s": 300]
                    Self.names[id] = row; status = 201; out = row.merging(["registered": true]) { a, _ in a }
                }
            case ("GET", "/v1/names/me"):
                if let r = own(id) { out = r } else { status = 404; out = ["error": "no_name"] }
            case ("PATCH", "/v1/names/me"):
                guard var r = own(id) else { status = 404; out = ["error": "no_name"]; break }
                if let v = json["visible"] as? Bool { r["visible"] = v }
                Self.names[id] = r; out = r
            case ("POST", "/v1/names/heartbeat"):
                if let r = own(id) { out = r } else { status = 404; out = ["error": "no_name"] }
            case ("GET", let p) where p.hasPrefix("/v1/names/"):
                let n = String(p.dropFirst("/v1/names/".count))
                out = Self.names.values.contains { $0["name"] as? String == n } ? ["name": n, "status": "registered"] : ["name": n, "status": "unregistered"]
            case ("POST", let p) where p.hasPrefix("/v1/names/") && p.hasSuffix("/resolve"):
                Self.proofs.append(json["code_proof"] as? String ?? "")
                (status, out) = Self.resolveAnswer
            default:
                status = 404; out = ["error": "not_found"]
            }
        }
        let data = (try? JSONSerialization.data(withJSONObject: out)) ?? Data()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
}

final class DeviceNameTests: XCTestCase {
    override func setUp() { FakeBackend.reset() }

    private func defaults() -> UserDefaults {
        let name = "test-" + UUID().uuidString
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    func testNameRulesMatchTheBackend() {
        XCTAssertEqual(DeviceNameStore.parse("  Kitchen-Tab "), "kitchen-tab")
        XCTAssertNil(DeviceNameStore.parse("ab"))
        XCTAssertNil(DeviceNameStore.parse("-kitchen"))
        XCTAssertNil(DeviceNameStore.parse("kitchen--tab"))
        XCTAssertNil(DeviceNameStore.parse("kitchen/tab"))
        XCTAssertNil(DeviceNameStore.parse(String(repeating: "a", count: 31)))
        XCTAssertEqual(DeviceNameStore.suggestion(from: "Rahul’s iPad"), "rahuls-ipad")
        XCTAssertEqual(DeviceNameStore.suggestion(from: "Café Mac mini!"), "cafe-mac-mini")
        XCTAssertEqual(DeviceNameStore.suggestion(from: "😀"), "my-bridgit")
    }

    func testInstallIdMatchesTheAppletSigner() {
        let key = P256.Signing.PrivateKey()
        XCTAssertEqual(DeviceIdentity(key: key, defaults: defaults()).id, DecisionInstall(key: key).id)
        XCTAssertTrue(DeviceIdentity(key: key, defaults: defaults()).id.hasPrefix("bgi_"))
        XCTAssertEqual(DeviceIdentity(key: key, defaults: defaults()).id.count, 28)
    }

    @MainActor func testRegistersInstallThenNameSignedAndHeartbeats() async throws {
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: defaults())
        let backend = TeamBackend(base: URL(string: "https://fake.test")!, identity: identity, http: FakeBackend.session)
        let store = DeviceNameStore(defaults: defaults(), backend: { backend })
        try await store.register("Kitchen-Tab")
        XCTAssertEqual(store.name, "kitchen-tab")
        XCTAssertEqual(identity.trust, "unverified")
        XCTAssertEqual(FakeBackend.calls, ["POST /v1/install/challenge", "POST /v1/install/register", "POST /v1/names"])
        await store.heartbeatIfDue(now: Date().addingTimeInterval(400))
        XCTAssertEqual(FakeBackend.calls.last, "POST /v1/names/heartbeat")
        let before = FakeBackend.calls.count
        await store.heartbeatIfDue(now: Date().addingTimeInterval(450))
        XCTAssertEqual(FakeBackend.calls.count, before, "no second heartbeat inside the interval")
        try await store.update(visible: false)
        XCTAssertEqual(store.own?.visible, false)
        await store.heartbeatIfDue(now: Date().addingTimeInterval(9999))
        XCTAssertEqual(FakeBackend.calls.last, "PATCH /v1/names/me", "no heartbeat while hidden")
    }

    @MainActor func testTakenNameIsReportedAndForgottenInstallReRegisters() async throws {
        FakeBackend.names["bgi_someoneelse"] = ["name": "porch-cam"]
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: defaults())
        let backend = TeamBackend(base: URL(string: "https://fake.test")!, identity: identity, http: FakeBackend.session)
        let store = DeviceNameStore(defaults: defaults(), backend: { backend })
        do { try await store.register("porch-cam"); XCTFail("taken") } catch let e as BackendError { XCTAssertEqual(e.code, "name_taken") }
        XCTAssertNil(store.name)
        FakeBackend.forgetInstalls = true
        try await store.register("porch-cam-2")
        XCTAssertEqual(store.name, "porch-cam-2")
        XCTAssertEqual(FakeBackend.calls.filter { $0 == "POST /v1/install/register" }.count, 2)
    }

    @MainActor func testSampleNameNeverTouchesTheBackendOrTheCache() async throws {
        let d = defaults()
        let store = DeviceNameStore(defaults: d, backend: { XCTFail("no backend in sample"); throw URLError(.cancelled) })
        store.setSampleName("rahuls-ipad")
        await store.heartbeatIfDue(now: Date().addingTimeInterval(9999))
        try await store.refresh()
        try await store.update(visible: false)
        XCTAssertEqual(store.name, "rahuls-ipad")
        store.reloadCached()
        XCTAssertNil(store.name, "the real (empty) cache is untouched")
    }

    @MainActor func testRefreshClearsANameTheBackendDoesNotHave() async throws {
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: defaults())
        let backend = TeamBackend(base: URL(string: "https://fake.test")!, identity: identity, http: FakeBackend.session)
        let d = defaults()
        let store = DeviceNameStore(defaults: d, backend: { backend })
        try await store.register("hall-ipad")
        FakeBackend.names = [:]
        try await store.refresh()
        XCTAssertNil(store.name)
        XCTAssertNil(DeviceNameStore(defaults: d, backend: { backend }).name)
    }

    func testResolveSendsAnInstallBoundProofNeverTheCode() async throws {
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: defaults())
        let backend = TeamBackend(base: URL(string: "https://fake.test")!, identity: identity, http: FakeBackend.session)
        FakeBackend.names["bgi_target"] = ["name": "kitchen-tab"]
        FakeBackend.resolveAnswer = (200, ["name": "kitchen-tab", "offer_id": "off_1", "expires_at": 1,
                                           "reach": ["relay": ["origin": "https://relay.test", "device": "dev-12345678"]]])
        let reach = try await RelayLinkWire(backend: { backend }).resolve(name: "kitchen-tab", code: "k7qx-3mpa")
        XCTAssertEqual(reach, LinkReach(name: "kitchen-tab", relay: "https://relay.test", device: "dev-12345678",
                                        claimToken: "rly_5ebc2056b9789e83d7f47a1820dff822759486f328800082010705b0a7afd46d"))
        let keys = try XCTUnwrap(LinkCode.keys(code: "K7QX3MPA", name: "kitchen-tab"))
        XCTAssertEqual(FakeBackend.proofs, [keys.proof(name: "kitchen-tab", claimingInstall: identity.id)])
        FakeBackend.resolveAnswer = (410, ["error": "code_expired"])
        do { _ = try await RelayLinkWire(backend: { backend }).resolve(name: "kitchen-tab", code: "K7QX3MPA"); XCTFail() }
        catch let e as LinkError { XCTAssertEqual(e.code, "code_expired") }
        do { _ = try await RelayLinkWire(backend: { backend }).resolve(name: "nobody-here", code: "K7QX3MPA"); XCTFail() }
        catch let e as LinkError { XCTAssertEqual(e.code, "code_invalid", "unregistered names never reach resolve") }
        XCTAssertEqual(FakeBackend.proofs.count, 2)
    }
}

final class RelayClaimTests: XCTestCase {
    override func setUp() { FakeBackend.reset() }

    func testClaimUsesTheDerivedBearerAndSendsOnlyATokenHash() async throws {
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: UserDefaults(suiteName: UUID().uuidString)!)
        let wire = RelayLinkWire(backend: { TeamBackend(base: URL(string: "https://fake.test")!, identity: identity, http: FakeBackend.session) },
                                 http: FakeBackend.session)
        FakeBackend.jobAnswer = (200, ["link_id": "lnk_abcdefghijklmnopqrst", "name": "Kitchen tablet", "bridgit_name": "kitchen-tab",
                                       "install": "bgi_target", "services": ["camera", "view"], "directions": ["in"]])
        let reach = LinkReach(name: "kitchen-tab", relay: "https://relay.test", device: "dev-12345678", claimToken: "rly_" + String(repeating: "a", count: 64))
        let me = LinkSelf(name: "rahuls-ipad", displayName: "Rahul’s iPad", install: identity.id, platform: "ipados", deviceType: "tablet")
        let c = try await wire.claim(reach, name: "kitchen-tab", code: "K7QX3MPA", me: me)
        let job = try XCTUnwrap(FakeBackend.jobs.first)
        XCTAssertEqual(job.bearer, "Bearer " + reach.claimToken)
        XCTAssertEqual(job.request["path"] as? String, "/v1/link/claim")
        let body = try XCTUnwrap(job.request["body"] as? [String: Any])
        XCTAssertEqual(body["bridgit_name"] as? String, "rahuls-ipad")
        XCTAssertEqual(body["install"] as? String, identity.id)
        XCTAssertEqual(body["token_hash"] as? String, PairingClaim.tokenHash(c.pairing.token))
        XCTAssertNil(body["code"], "the code never leaves this device")
        XCTAssertNotEqual(c.pairing.token, reach.claimToken)
        XCTAssertEqual(c.pairing.linkId, "lnk_abcdefghijklmnopqrst")
        XCTAssertEqual(c.link.name, "kitchen-tab")
        XCTAssertEqual(c.link.out.state, .on)
        XCTAssertEqual(c.link.out.services, ["camera", "view"])

        FakeBackend.jobAnswer = (410, ["error": "code_used_up"])
        do { _ = try await wire.claim(reach, name: "kitchen-tab", code: "K7QX3MPA", me: me); XCTFail() }
        catch let e as LinkError { XCTAssertEqual(e.code, "code_used_up") }
    }
}

final class LinkCodeTests: XCTestCase {
    func testCrockfordNormalisation() {
        XCTAssertEqual(LinkCode.normalize("k7qx-3mpa"), "K7QX3MPA")
        XCTAssertEqual(LinkCode.normalize("K7QX 3MPA"), "K7QX3MPA")
        XCTAssertEqual(LinkCode.normalize("o1il-0000"), "0111" + "0000")
        XCTAssertNil(LinkCode.normalize("K7QX-3MP"), "seven characters")
        XCTAssertNil(LinkCode.normalize("K7QX-3MPU"), "U is not Crockford")
        XCTAssertNil(LinkCode.normalize("K7QX-3MPA9"))
        XCTAssertEqual(LinkCode.display("k7qx3mpa"), "K7QX-3MPA")
    }

    /// Spec §4.1 vector, computed independently with Python's hashlib.pbkdf2_hmac and hmac.
    func testCodeKeysMatchTheBackendContract() throws {
        let k = try XCTUnwrap(LinkCode.keys(code: "k7qx-3mpa", name: "kitchen-tab"))
        XCTAssertEqual(k.codeHash, "83c1e52828c43f9d7205b3bbb3753ad402782e7a323959c755b1335351456fe0")
        XCTAssertEqual(k.claimToken, "rly_5ebc2056b9789e83d7f47a1820dff822759486f328800082010705b0a7afd46d")
        XCTAssertEqual(k.proof(name: "kitchen-tab", claimingInstall: "bgi_aaaaaaaaaaaaaaaaaaaaaaaa"),
                       "84f47b105aa7b73ae689213c41188786c6d24a4487e067864f4991a12dc51601")
        XCTAssertEqual(PairingClaim.tokenHash(k.claimToken), "a9e960da867c1dfd7b795672d8958864d407af5876b0ad022871b25d419abe1a")
        XCTAssertNil(LinkCode.keys(code: "K7QX3MPU", name: "kitchen-tab"))
    }

    func testOfferParsing() {
        let want = LinkOffer(name: "kitchen-tab", code: "K7QX3MPA")
        XCTAssertEqual(LinkOffer.parse("kitchen-tab K7QX-3MPA"), want)
        XCTAssertEqual(LinkOffer.parse("Kitchen-Tab\nk7qx 3mpa"), want)
        XCTAssertEqual(LinkOffer.parse("bridgit://link?name=kitchen-tab&code=K7QX-3MPA"), want)
        XCTAssertEqual(LinkOffer.parse("bridgitportal://link#name=kitchen-tab&code=K7QX3MPA"), want)
        XCTAssertEqual(LinkOffer.parse("https://bridgit.cc/link#name=kitchen-tab&code=K7QX3MPA"), want)
        XCTAssertNil(LinkOffer.parse("https://evil.test/link#name=kitchen-tab&code=K7QX3MPA"))
        XCTAssertNil(LinkOffer.parse("kitchen-tab"))
        XCTAssertNil(LinkOffer.parse("kitchen/tab K7QX3MPA"))
    }

    func testRelayUnwrapKeepsThePhoneErrorForLinkCalls() throws {
        let inner = try JSONSerialization.data(withJSONObject: ["error": "code_invalid", "message": "no"])
        let job = try JSONSerialization.data(withJSONObject: ["job_id": "j", "response": ["status": 403, "body_base64": inner.base64EncodedString()]])
        let raw = try RelayClient.decodeCompletion(status: 200, data: job, raw: true)
        XCTAssertEqual(raw.status, 403)
        XCTAssertThrowsError(try RelayLinkWire.check(raw))
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 200, data: job))
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 503, data: Data(#"{"error":"phone_unreachable"}"#.utf8), raw: true))
    }
}

@MainActor
final class LinkStoreTests: XCTestCase {
    private var keychainKeys: [String] = []

    private func pairing(_ device: String, name: String = "Kitchen phone") -> Pairing {
        Pairing(base: URL(string: "https://relay.test/d/" + device)!, deviceId: device, token: "rly_aaaaaaaaaaaaaaaaaaaaaa", name: name,
                addedAt: Date(timeIntervalSince1970: 1_700_000_000))
    }

    private func store(_ wire: LinkWire, relays: RelayStore, window: TimeInterval = 0.2) -> LinkStore {
        let s = LinkStore(relays: relays, wire: wire, defaults: nil, undoWindow: window)
        s.deviceName = { "rahuls-ipad" }
        return s
    }

    private func relays() -> RelayStore {
        let r = RelayStore(persist: false)
        return r
    }

    private let me = LinkSelf(name: "rahuls-ipad", install: "bgi_test", platform: "ios", deviceType: "phone")

    func testPortalPairingsBecomeViewLinksWithoutRePairing() {
        let r = relays()
        r.replaceAllForSample([pairing("dev-00000001")])
        let s = store(FixtureLinkWire(), relays: r)
        s.reconcile()
        XCTAssertEqual(s.links.count, 1)
        let l = s.links[0]
        XCTAssertEqual(l.out.state, .on)
        XCTAssertEqual(l.out.services, ["view"])
        XCTAssertEqual(l.out.grantedBy?.kind, .migrated)
        XCTAssertEqual(l.out.grantedAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertTrue(l.migrated)
        XCTAssertFalse(l.linkRoutes)
        XCTAssertEqual(l.inbound.state, .off)
        XCTAssertEqual(l.id, LinkStore.localId(relayDevice: "dev-00000001"))
        XCTAssertEqual(r.pairings.first?.token, "rly_aaaaaaaaaaaaaaaaaaaaaa", "token kept")
        XCTAssertEqual(s.usable(r.pairings).count, 1)
        s.reconcile()
        XCTAssertEqual(s.links.count, 1, "migrates once")
        XCTAssertEqual(s.log.filter { $0.event == "migrated" }.count, 1)
    }

    func testClaimNeedsANameThenLinks() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        let r = relays()
        let s = store(wire, relays: r)
        do { try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: nil); XCTFail() }
        catch let e as LinkError { XCTAssertEqual(e.code, "name_required") }
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX-3MPA"), me: me)
        XCTAssertEqual(l.out.state, .on)
        XCTAssertEqual(l.out.services, ["camera", "mic", "speaker", "interactions", "view"])
        XCTAssertEqual(r.pairings.count, 1)
        XCTAssertEqual(r.pairings[0].linkId, l.id)
        XCTAssertTrue(r.pairings[0].token.hasPrefix("rly_"), "a token made here, not the claim token")
        XCTAssertEqual(s.log.last?.event, "code_claimed")
        XCTAssertEqual(s.log.last?.granter, .person(on: "rahuls-ipad"))
        let sent = await wire.sentEvents()
        XCTAssertEqual(sent.map(\.event), ["code_claimed"], "the far device is told")
        do { try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me); XCTFail("one use") } catch {}
    }

    func testPauseStopsUseAndResumes() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        let r = relays()
        let s = store(wire, relays: r)
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        s.setPaused(l.id, true)
        XCTAssertEqual(s.link(l.id)?.out.state, .paused)
        XCTAssertTrue(s.usable(r.pairings).isEmpty)
        s.setPaused(l.id, false)
        XCTAssertEqual(s.link(l.id)?.out.state, .on)
        XCTAssertEqual(s.usable(r.pairings).count, 1)
        XCTAssertEqual(s.log.suffix(2).map(\.event), ["paused", "resumed"])
    }

    func testDisconnectCutsAtOnceUndoRestoresWithoutTellingTheTarget() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        let r = relays()
        let s = store(wire, relays: r, window: 5)
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        s.disconnect(l.id)
        XCTAssertTrue(s.usable(r.pairings).isEmpty, "access cut at once")
        s.undo(l.id)
        XCTAssertEqual(s.usable(r.pairings).count, 1)
        XCTAssertEqual(r.pairings.count, 1, "token kept")
        let calls = await wire.disconnectCalls()
        XCTAssertTrue(calls.isEmpty)
        XCTAssertEqual(s.log.suffix(2).map(\.event), ["disconnected", "disconnect_undone"])
    }

    func testDisconnectCommitsAfterTheWindowAndForgetsTheToken() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        let r = relays()
        let s = store(wire, relays: r, window: 0.2)
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        s.disconnect(l.id)
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertNil(s.link(l.id))
        XCTAssertTrue(r.pairings.isEmpty)
        let calls = await wire.disconnectCalls()
        XCTAssertEqual(calls.map(\.0), [l.id])
        XCTAssertEqual(calls.map(\.1), ["both"])
        s.undo(l.id)
        XCTAssertNil(s.link(l.id), "too late for Undo: a new code is needed")
    }

    func testOfflineDisconnectIsRetriedAndOldPairingsDisconnectLocally() async throws {
        struct Offline: LinkWire {
            func resolve(name: String, code: String) async throws -> LinkReach { throw RelayError.offline }
            func claim(_ reach: LinkReach, name: String, code: String, me: LinkSelf) async throws -> LinkClaimed { throw RelayError.offline }
            func sync(events: [LinkEvent], ack: [String], link: LinkedBridgit, me: String, pausedHere: Bool, over pairing: Pairing) async throws -> LinkSync { throw RelayError.offline }
            func peers(over pairing: Pairing) async throws -> [LinkPeer] { throw RelayError.offline }
            func introductions(over pairing: Pairing, link: LinkedBridgit) async throws -> [Introduction] { throw RelayError.offline }
            func answer(introduction: Introduction, allow: Bool, over pairing: Pairing) async throws { throw RelayError.offline }
            func disconnect(linkId: String, direction: String, over pairing: Pairing) async throws { throw RelayError.offline }
        }
        let r = relays()
        var p = pairing("dev-00000002")
        p.linkId = "lnk_abc"
        r.replaceAllForSample([p, pairing("dev-00000003", name: "Old phone")])
        let s = store(Offline(), relays: r)
        s.reconcile()
        let claimed = try XCTUnwrap(s.links.first { $0.id == "lnk_abc" })
        let old = try XCTUnwrap(s.links.first { $0.migrated })
        s.disconnect(claimed.id)
        s.disconnect(old.id)
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertNil(s.link(old.id), "old Portal pairing: removed locally, as before")
        XCTAssertEqual(s.retiring[claimed.id], "both", "offline: kept to tell the far device later")
        XCTAssertTrue(s.usable(r.pairings).isEmpty)
        XCTAssertEqual(r.pairings.map(\.deviceId), ["dev-00000002"])
    }

    func testFarHalvesAndRevocationReachThisSide() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        let r = relays()
        let s = store(wire, relays: r)
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        await wire.setFarHalf(.init(optedIn: true, paused: true, services: ["camera"]), via: l.relayDeviceId)
        await s.flush()
        XCTAssertEqual(s.link(l.id)?.out.state, .paused, "paused on the far device")
        XCTAssertEqual(s.link(l.id)?.out.services, ["camera"])
        await wire.setFarHalf(.init(optedIn: true, paused: false, services: ["camera"]), via: l.relayDeviceId)
        await s.flush()
        XCTAssertEqual(s.link(l.id)?.out.state, .on)
        s.setPaused(l.id, true)
        await s.flush()
        XCTAssertEqual(s.link(l.id)?.out.state, .paused, "a pause made here survives the far device's view")
        s.setPaused(l.id, false)
        await wire.revoke(l.relayDeviceId)
        await s.flush()
        XCTAssertEqual(s.link(l.id)?.out.state, .off, "link_revoked marks this side off")
        XCTAssertTrue(s.usable(r.pairings).isEmpty)
        XCTAssertEqual(s.log.last?.event, "marked_off")
    }

    func testABridgitWithoutIntroductionsKeepsItsLinkRoutes() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        await wire.setNoIntroductions()
        let s = store(wire, relays: relays())
        let l = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        await wire.setPeers([LinkPeer(name: "porch-cam", uses: true, usedBy: false)], via: l.relayDeviceId)
        await s.refreshRemote()
        XCTAssertEqual(s.link(l.id)?.linkRoutes, true)
        XCTAssertEqual(s.link(l.id)?.peers.map(\.name), ["porch-cam"])
    }

    func testFarEventsAreStoredFlippedAndReportedByThem() {
        let l = LinkedBridgit(id: "lnk_x", name: "kitchen-tab", platform: "android", deviceType: "tablet", relayDeviceId: "d", out: .off, linkedAt: Date())
        let e = RelayLinkWire.farEvents([["id": "lev_1", "at": 1_700_000_000_000.0, "direction": "in", "event": "opt_in", "after": ["camera", "mic"],
                                          "granter": ["kind": "agent", "agent_name": "Muse", "device": "kitchen-tab"], "late": true]], link: l)
        XCTAssertEqual(e.count, 1)
        XCTAssertEqual(e[0].direction, "out", "their in is this device's out")
        XCTAssertEqual(e[0].after, "camera, microphone")
        XCTAssertEqual(e[0].reportedBy, "kitchen-tab")
        XCTAssertEqual(e[0].granter.label, "Muse on kitchen-tab")
        XCTAssertTrue(e[0].late)
    }

    func testDisconnectsJoinOneBatchAndUndoAllRestoresThem() async throws {
        let r = relays()
        r.replaceAllForSample([pairing("dev-00000006"), pairing("dev-00000007")])
        let s = store(FixtureLinkWire(), relays: r, window: 10)
        s.reconcile()
        let t0 = Date()
        s.disconnect(s.links[0].id, now: t0)
        s.disconnect(s.links[1].id, now: t0.addingTimeInterval(5))
        XCTAssertEqual(Set(s.pending.values.map(\.until)), [t0.addingTimeInterval(15)], "the batch's 10 seconds start again")
        s.undoAll()
        XCTAssertTrue(s.pending.isEmpty)
        XCTAssertEqual(s.usable(r.pairings).count, 2)
    }

    func testDisconnectAll() async throws {
        let r = relays()
        r.replaceAllForSample([pairing("dev-00000004"), pairing("dev-00000005")])
        let s = store(FixtureLinkWire(), relays: r, window: 5)
        s.reconcile()
        s.disconnectAll()
        XCTAssertEqual(s.pending.count, 2)
        XCTAssertTrue(s.usable(r.pairings).isEmpty)
    }

    func testIntroductionAllowClaimsAndDenyOnlyAnswers() async throws {
        let wire = FixtureLinkWire()
        await wire.addOffer(name: "kitchen-tab", code: "K7QX3MPA")
        await wire.addOffer(name: "porch-cam", code: "4M9R2KQZ", offer: .init(services: ["camera"]))
        let r = relays()
        let s = store(wire, relays: r)
        let via = try await s.claim(LinkOffer(name: "kitchen-tab", code: "K7QX3MPA"), me: me)
        let allow = Introduction(id: "int_1", fromName: "porch-cam", code: "4M9R2KQZ", services: ["camera"], agentName: "Muse",
                                 via: via.name, viaLink: via.id, note: nil, at: Date())
        let deny = Introduction(id: "int_2", fromName: "garage-tab", code: "AAAA2222", services: ["mic"], agentName: nil,
                                via: via.name, viaLink: via.id, note: nil, at: Date())
        await wire.setIntroductions([allow, deny], via: via.relayDeviceId)
        await wire.setPeers([LinkPeer(name: "porch-cam", uses: true, usedBy: false)], via: via.relayDeviceId)
        await s.refreshRemote()
        XCTAssertEqual(s.introductions.map(\.id), ["int_1", "int_2"])
        XCTAssertEqual(s.link(via.id)?.peers.map(\.name), ["porch-cam"])
        try await s.answer("int_2", allow: false, me: me)
        XCTAssertEqual(s.links.count, 1)
        try await s.answer("int_1", allow: true, me: me)
        XCTAssertEqual(Set(s.links.map(\.name)), ["kitchen-tab", "porch-cam"])
        XCTAssertTrue(s.introductions.isEmpty)
        XCTAssertEqual(Set(s.log.map(\.event)).isSuperset(of: ["introduction_allowed", "introduction_denied"]), true)
        let left = await wire.pendingIntroductions[via.relayDeviceId] ?? []
        XCTAssertTrue(left.isEmpty, "both answers reached the linked Bridgit")
    }

    func testIntroductionJSONNeedsAValidCode() {
        let via = LinkedBridgit(id: "lnk_v", name: "kitchen-tab", platform: "android", deviceType: "tablet", relayDeviceId: "d",
                                out: .off, linkedAt: Date())
        XCTAssertNotNil(Introduction(json: ["id": "i", "from_name": "porch-cam", "code": "K7QX-3MPA", "introduced_by": ["agent_name": "Muse"]], via: via))
        XCTAssertNil(Introduction(json: ["id": "i", "from_name": "porch-cam", "code": "nope"], via: via))
    }
}

final class LinkMapTests: XCTestCase {
    private func link(_ name: String, out: DirectionState, inbound: DirectionState = .off, peers: [LinkPeer] = []) -> LinkedBridgit {
        LinkedBridgit(id: "lnk_" + name, name: name, platform: "android", deviceType: "tablet", relayDeviceId: "d-" + name,
                      out: LinkDirection(state: out, services: [], grantedBy: nil, grantedAt: nil),
                      inbound: LinkDirection(state: inbound, services: [], grantedBy: nil, grantedAt: nil),
                      linkedAt: Date(), peers: peers)
    }

    func testArrowsPointAtTheControlledDeviceAndStylesFollowState() {
        let size = CGSize(width: 1000, height: 1000)
        let m = LinkMapLayout.make(me: "rahuls-ipad", links: [
            link("kitchen-tab", out: .on, peers: [LinkPeer(name: "porch-cam", uses: true, usedBy: false), LinkPeer(name: "rahuls-ipad", uses: false, usedBy: true)]),
            link("hall-phone", out: .paused),
            link("garage-tab", out: .waiting_for_target),
            link("den-tab", out: .on, inbound: .on),
        ], gone: [], viewport: size)
        func edge(_ to: String) -> LinkMapLayout.Edge? { m.edges.first { $0.to == to } }
        let centre = m.nodes[0].point
        XCTAssertEqual(edge("link:lnk_kitchen-tab")?.arrowAtTo, true)
        XCTAssertEqual(edge("link:lnk_kitchen-tab")?.arrowAtFrom, false)
        XCTAssertEqual(edge("link:lnk_kitchen-tab")?.style, .solid)
        XCTAssertEqual(edge("link:lnk_hall-phone")?.style, .dashed)
        XCTAssertEqual(edge("link:lnk_garage-tab")?.style, .faint)
        XCTAssertEqual(edge("link:lnk_garage-tab")?.label, "waiting")
        XCTAssertEqual(edge("link:lnk_den-tab")?.arrowAtFrom, true, "two-way: double arrow")
        XCTAssertEqual(edge("link:lnk_den-tab")?.arrowAtTo, true)
        let via = m.nodes.filter { $0.ring == 2 }
        XCTAssertEqual(via.map(\.name), ["porch-cam"], "this device is never drawn twice")
        XCTAssertEqual(via.first?.via, "kitchen-tab")
        XCTAssertEqual(edge(via[0].id)?.label, "via kitchen-tab")
        XCTAssertEqual(edge(via[0].id)?.style, .indirect)
        XCTAssertEqual(edge(via[0].id)?.from, "link:lnk_kitchen-tab")
        // Sorted by name, clockwise from twelve o'clock: den-tab first, straight up.
        let first = m.nodes.first { $0.ring == 1 }!
        XCTAssertEqual(first.name, "den-tab")
        XCTAssertEqual(first.point.x, centre.x, accuracy: 0.5)
        XCTAssertLessThan(first.point.y, centre.y)
        // Every node box (circle + name) is inside the canvas and none overlap.
        let boxes = m.nodes.map { LinkMapLayout.box($0).offsetBy(dx: $0.point.x, dy: $0.point.y) }
        for (i, b) in boxes.enumerated() {
            XCTAssertTrue(CGRect(origin: .zero, size: m.size).contains(b), m.nodes[i].name)
            for c in boxes[(i + 1)...] { XCTAssertFalse(b.intersects(c), m.nodes[i].name) }
        }
        let inner = m.nodes.filter { $0.ring == 1 }.map { hypot($0.point.x - centre.x, $0.point.y - centre.y) }
        let outer = via.map { hypot($0.point.x - centre.x, $0.point.y - centre.y) }
        XCTAssertTrue(outer.allSatisfy { o in inner.allSatisfy { o > $0 } }, "via ring is further out")
        XCTAssertGreaterThanOrEqual(inner.min() ?? 0, 112)
    }

    /// Regression: an outer node straight out sideways used to push the rings out forever.
    func testSidewaysViaNodeSettlesQuickly() {
        let m = LinkMapLayout.make(me: "rahuls-ipad", links: [
            link("garage-tab", out: .waiting_for_target), link("hall-phone", out: .paused), link("Kitchen phone", out: .on),
            link("kitchen-tab", out: .on, peers: [LinkPeer(name: "porch-cam", uses: true, usedBy: false)]),
        ], viewport: CGSize(width: 361, height: 520))
        let c = m.nodes[0].point
        let radii = m.nodes.filter { $0.ring > 0 }.map { hypot($0.point.x - c.x, $0.point.y - c.y) }
        XCTAssertLessThanOrEqual(radii.max() ?? 0, 300)
        XCTAssertLessThan(m.size.width, 600)
    }

    func testMixedStatesDrawTwoLanesAndCrowdedMapsScroll() {
        let m = LinkMapLayout.make(me: "me", links: [link("a-tab", out: .on, inbound: .paused)], viewport: CGSize(width: 360, height: 360))
        XCTAssertEqual(m.edges.count, 2)
        XCTAssertEqual(Set(m.edges.map(\.style)), [.solid, .dashed])
        XCTAssertEqual(m.edges.filter(\.arrowAtTo).count, 1)
        XCTAssertFalse(m.scrolls)
        let five = LinkMapLayout.make(me: "me", links: (1...5).map { link("tab-\($0)", out: .on) }, viewport: CGSize(width: 360, height: 360))
        XCTAssertTrue(five.scrolls, "five direct links use the scrolling canvas")
    }

    func testLinesClearTheNames() {
        let n = LinkMapLayout.Node(id: "x", name: "kitchen-tab", point: CGPoint(x: 100, y: 100), ring: 1)
        let down = LinkMapLayout.exit(from: n, toward: CGPoint(x: 100, y: 400))
        XCTAssertGreaterThan(down.y, 100 + 22 + 6 + 17, "a line going down starts below the name")
        let side = LinkMapLayout.exit(from: n, toward: CGPoint(x: 400, y: 100))
        XCTAssertEqual(side.x, 100 + 22 + 6, accuracy: 0.5, "sideways it starts 6 pt outside the circle")
    }

    func testNarrowViewportTurnsTheRingToFit() {
        let links = [link("a-tab", out: .on), link("b-tab", out: .on), link("c-tab", out: .on, peers: [LinkPeer(name: "porch-cam", uses: true, usedBy: false)])]
        let wide = LinkMapLayout.make(me: "me", links: links, viewport: CGSize(width: 2000, height: 2000))
        let narrow = LinkMapLayout.make(me: "me", links: links, viewport: CGSize(width: 361, height: 520))
        XCTAssertLessThanOrEqual(narrow.size.width, wide.size.width)
        let order = { (m: LinkMapLayout) in m.nodes.filter { $0.ring == 1 }.map(\.name) }
        XCTAssertEqual(order(narrow), order(wide), "same clockwise order")
    }

    func testDisconnectingLinkIsFaint() {
        let m = LinkMapLayout.make(me: "me", links: [link("kitchen-tab", out: .on)], gone: ["lnk_kitchen-tab"], viewport: CGSize(width: 300, height: 300))
        XCTAssertEqual(m.edges.first?.style, .faint)
    }
}

/// Live: the production names backend accepts this Apple install. Run with TEST_RUNNER_BRIDGIT_LIVE_NAMES=1.
/// Registers a throwaway install and a `claude-test-…` name, heartbeats, then hides the name.
final class LiveNamesTests: XCTestCase {
    @MainActor func testAppleInstallRegistersANameInProduction() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["BRIDGIT_LIVE_NAMES"] == "1")
        let identity = DeviceIdentity(key: P256.Signing.PrivateKey(), defaults: UserDefaults(suiteName: UUID().uuidString)!)
        let backend = TeamBackend(identity: identity, http: URLSession(configuration: .ephemeral))
        let store = DeviceNameStore(defaults: UserDefaults(suiteName: UUID().uuidString)!, backend: { backend })
        let name = "claude-test-" + String(UUID().uuidString.lowercased().prefix(6))
        try await store.register(name)
        XCTAssertEqual(store.name, name)
        XCTAssertEqual(identity.trust, "unverified")
        print("LIVE install", identity.id, "trust", identity.trust ?? "-", "name", name)
        try await store.refresh()
        XCTAssertEqual(store.name, name)
        let (hs, _) = try await backend.call("POST", "/v1/names/heartbeat", body: [:])
        XCTAssertEqual(hs, 200)
        let (ls, lookup) = try await backend.call("GET", "/v1/names/" + name)
        XCTAssertEqual(ls, 200)
        XCTAssertEqual(lookup["status"] as? String, "visible")
        XCTAssertNil(lookup["reach"])
        // Resolving its own name: the backend checks the signature, the proof's shape and the name, then refuses
        // a link to itself. That proves the signed resolve route without needing a second device.
        do { _ = try await RelayLinkWire(backend: { backend }).resolve(name: name, code: "K7QX3MPA"); XCTFail("can't link to itself") }
        catch let e as LinkError { print("LIVE resolve", e.code, e.message); XCTAssertEqual(e.code, "invalid_parameter"); XCTAssertTrue(e.message.contains("itself"), e.message) }
        let (ds, _) = try await backend.call("DELETE", "/v1/names/me")
        XCTAssertEqual(ds, 200)
    }
}
