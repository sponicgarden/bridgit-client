import Foundation
import XCTest
@testable import BridgitCore

/// A scripted names backend: records each call and answers from a queue.
private actor NamesBackend {
    struct Call { let method: String; let path: String; let body: [String: Any]? }
    private(set) var calls: [Call] = []
    private var replies: [(Int, [String: Any])] = []
    func queue(_ status: Int, _ body: [String: Any]) { replies.append((status, body)) }
    func answer(_ method: String, _ path: String, _ body: [String: Any]?) -> (Int, [String: Any]) {
        calls.append(.init(method: method, path: path, body: body))
        return replies.isEmpty ? (500, ["error": "unscripted"]) : replies.removeFirst()
    }
}

private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 1_800_000_000)
    var now: Date { lock.lock(); defer { lock.unlock() }; return value }
    func advance(_ seconds: TimeInterval) { lock.lock(); value += seconds; lock.unlock() }
}

private final class Cache: @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?
    func load() -> Data? { lock.lock(); defer { lock.unlock() }; return data }
    func store(_ value: Data?) { lock.lock(); data = value; lock.unlock() }
}

final class DeviceDomainNamesTests: XCTestCase {
    private let own: [String: Any] = ["name": "porch-cam", "label": "Porch", "visible": true, "heartbeat_every_s": 300]

    private func service(_ backend: NamesBackend, cache: Cache = Cache(), clock: Clock = Clock()) -> DeviceDomainNameService {
        DeviceDomainNameService(call: { method, path, body in await backend.answer(method, path, body) },
                                load: { cache.load() }, store: { cache.store($0) }, now: { clock.now })
    }

    private func code(_ work: () async throws -> Void) async -> String? {
        do { try await work(); return nil } catch let error as DeviceAPIError { return error.code } catch { return "other" }
    }

    func testParseMatchesBackendRules() {
        XCTAssertEqual(DeviceDomainName.parse(" Kitchen-Tab "), "kitchen-tab")
        XCTAssertEqual(DeviceDomainName.parse("abc"), "abc")
        XCTAssertEqual(DeviceDomainName.parse(String(repeating: "a", count: 30)), String(repeating: "a", count: 30))
        for bad in ["ab", String(repeating: "a", count: 31), "-porch", "porch-", "por--ch", "por ch", "por/ch", "porch_cam", "émile"] {
            XCTAssertNil(DeviceDomainName.parse(bad), bad)
        }
    }

    func testSuggestionComesFromTheDeviceName() {
        XCTAssertEqual(DeviceDomainName.suggestion(from: "Rahul’s MacBook Air"), "rahuls-macbook-air")
        XCTAssertEqual(DeviceDomainName.suggestion(from: "Rahul's iPad (2)"), "rahuls-ipad-2")
        XCTAssertEqual(DeviceDomainName.suggestion(from: "Zoë"), "zoe")
        XCTAssertEqual(DeviceDomainName.suggestion(from: "!!"), "my-bridgit")
        let long = DeviceDomainName.suggestion(from: "A very long Mac name that goes on and on")
        XCTAssertNotNil(DeviceDomainName.parse(long)); XCTAssertLessThanOrEqual(long.count, 30)
    }

    func testRegisterCachesTheBackendAnswer() async throws {
        let backend = NamesBackend(); let cache = Cache()
        await backend.queue(201, own)
        let names = service(backend, cache: cache)
        let result = try await names.set(name: "porch-cam", label: .some("Porch"), visible: true)
        XCTAssertEqual(result["status"] as? String, "registered")
        XCTAssertEqual(result["created"] as? Bool, true)
        let call = await backend.calls.first
        XCTAssertEqual(call?.method, "POST"); XCTAssertEqual(call?.path, "/v1/names")
        XCTAssertEqual(call?.body?["name"] as? String, "porch-cam")
        XCTAssertEqual(call?.body?["visible"] as? Bool, true)
        let cached = await names.cached
        XCTAssertEqual(cached, DeviceDomainNameRecord(name: "porch-cam", label: "Porch", visible: true))
    }

    func testTakenNameKeepsNothingCached() async {
        let backend = NamesBackend()
        await backend.queue(409, ["error": "name_taken", "message": "porch-cam is taken.", "lookup": ["status": "visible", "label": "Next door"]])
        let names = service(backend)
        let failure = await code { _ = try await names.set(name: "porch-cam", label: nil, visible: nil) }
        XCTAssertEqual(failure, "name_taken")
        let cached = await names.cached
        XCTAssertNil(cached)
    }

    func testAlreadyNamedRefreshesAndRefusesAnotherName() async throws {
        let backend = NamesBackend()
        await backend.queue(409, ["error": "already_named", "message": "This Bridgit is already porch-cam.", "name": "porch-cam"])
        await backend.queue(200, own)
        let names = service(backend)
        let first = await code { _ = try await names.set(name: "kitchen-tab", label: nil, visible: nil) }
        XCTAssertEqual(first, "already_named")
        let cached = await names.cached
        XCTAssertEqual(cached?.name, "porch-cam")
        let count = await backend.calls.count
        let second = await code { _ = try await names.set(name: "kitchen-tab", label: nil, visible: nil) }
        XCTAssertEqual(second, "already_named")
        let after = await backend.calls.count
        XCTAssertEqual(after, count, "a held name refuses another without a call")
    }

    func testChangesLabelAndVisibilityWithPatch() async throws {
        let backend = NamesBackend(); let cache = Cache()
        cache.store(try JSONEncoder().encode(DeviceDomainNameRecord(name: "porch-cam", label: "Porch", visible: true)))
        await backend.queue(200, ["name": "porch-cam", "label": NSNull(), "visible": false])
        let names = service(backend, cache: cache)
        let result = try await names.set(name: "porch-cam", label: .some(nil), visible: false)
        XCTAssertEqual(result["visible"] as? Bool, false)
        let call = await backend.calls.first
        XCTAssertEqual(call?.method, "PATCH"); XCTAssertEqual(call?.path, "/v1/names/me")
        XCTAssertTrue(call?.body?["label"] is NSNull)
        XCTAssertNil(call?.body?["name"])
        let cached = await names.cached
        XCTAssertNil(cached?.label)
    }

    func testEmptyCacheAsksTheBackendBeforeChanging() async throws {
        let backend = NamesBackend()
        await backend.queue(200, own)
        await backend.queue(200, ["name": "porch-cam", "label": "Back porch", "visible": true])
        let names = service(backend)
        _ = try await names.set(name: nil, label: .some("Back porch"), visible: nil)
        let methods = await backend.calls.map(\.method)
        XCTAssertEqual(methods, ["GET", "PATCH"])
    }

    func testNoNameAndNoneGivenIsAnError() async {
        let backend = NamesBackend()
        await backend.queue(404, ["error": "no_name"])
        let names = service(backend)
        let failure = await code { _ = try await names.set(name: nil, label: .some("Porch"), visible: nil) }
        XCTAssertEqual(failure, "invalid_parameter")
    }

    func testHideAndForgottenName() async throws {
        let backend = NamesBackend(); let cache = Cache()
        cache.store(try JSONEncoder().encode(DeviceDomainNameRecord(name: "porch-cam", visible: true)))
        await backend.queue(200, ["name": "porch-cam", "label": NSNull(), "visible": false])
        await backend.queue(404, ["error": "no_name"])
        let names = service(backend, cache: cache)
        let hidden = try await names.hide()
        XCTAssertEqual(hidden["visible"] as? Bool, false)
        let method = await backend.calls.first?.method
        XCTAssertEqual(method, "DELETE")
        let refreshed = try await names.refresh()
        XCTAssertEqual(refreshed["status"] as? String, "unregistered")
        let cached = await names.cached
        XCTAssertNil(cached)
    }

    func testHeartbeatOnlyWhileVisibleAndOncePerInterval() async throws {
        let backend = NamesBackend(); let cache = Cache(); let clock = Clock()
        let names = service(backend, cache: cache, clock: clock)
        let none = await names.heartbeatIfDue()
        XCTAssertFalse(none)
        cache.store(try JSONEncoder().encode(DeviceDomainNameRecord(name: "porch-cam", visible: false)))
        let hidden = await names.heartbeatIfDue()
        XCTAssertFalse(hidden)
        var calls = await backend.calls.count
        XCTAssertEqual(calls, 0)

        cache.store(try JSONEncoder().encode(DeviceDomainNameRecord(name: "porch-cam", visible: true)))
        await backend.queue(200, own)
        let first = await names.heartbeatIfDue()
        XCTAssertTrue(first)
        clock.advance(60)
        let early = await names.heartbeatIfDue()
        XCTAssertFalse(early)
        calls = await backend.calls.count
        XCTAssertEqual(calls, 1)
        clock.advance(240)
        await backend.queue(404, ["error": "no_name"])
        _ = await names.heartbeatIfDue()
        let paths = await backend.calls.map(\.path)
        XCTAssertEqual(paths, ["/v1/names/heartbeat", "/v1/names/heartbeat"])
        let cached = await names.cached
        XCTAssertNil(cached, "a forgotten name stops the heartbeat")
    }

    func testBackendErrorsKeepTheirCodes() async {
        for (status, error, expected) in [(429, "rate_limited", "rate_limited"), (401, "unknown_install", "team_unavailable"),
                                          (400, "invalid_parameter", "invalid_parameter"), (500, "boom", "upstream_failed")] {
            let backend = NamesBackend()
            await backend.queue(status, ["error": error, "message": "nope"])
            let names = service(backend)
            let failure = await code { _ = try await names.set(name: "porch-cam", label: nil, visible: nil) }
            XCTAssertEqual(failure, expected, error)
        }
    }

    func testHints() async throws {
        let cache = Cache()
        let names = service(NamesBackend(), cache: cache)
        let blocked = await names.hint(canConfigure: false)
        XCTAssertEqual(blocked["status"] as? String, "unregistered")
        XCTAssertNotNil(blocked["blocked"])
        XCTAssertEqual(blocked["advice"] as? String, DeviceDomainName.advice)
        let open = await names.hint(canConfigure: true)
        XCTAssertNil(open["blocked"])
        cache.store(try JSONEncoder().encode(DeviceDomainNameRecord(name: "porch-cam", label: "Porch", visible: true)))
        let registered = await names.hint(canConfigure: false)
        XCTAssertEqual(registered["name"] as? String, "porch-cam")
        XCTAssertNil(registered["blocked"])
    }

    func testChangeValidation() throws {
        let change = try DeviceDomainNameChange(body: ["name": "Porch-Cam", "label": "  Porch ", "visible": true])
        XCTAssertEqual(change.name, "porch-cam")
        XCTAssertEqual(change.label, .some("Porch"))
        XCTAssertEqual(change.visible, true)
        XCTAssertEqual(try DeviceDomainNameChange(body: ["label": NSNull()]).label, .some(nil))
        XCTAssertEqual(try DeviceDomainNameChange(body: ["label": " "]).label, .some(nil))
        XCTAssertTrue(try DeviceDomainNameChange(body: ["visible": false]).label == nil, "label not sent")
        for bad: [String: Any] in [[:], ["name": "x"], ["name": 5], ["visible": 1], ["visible": "true"],
                                   ["label": String(repeating: "a", count: 61)], ["label": "a\u{7}"], ["owner": "me"]] {
            XCTAssertThrowsError(try DeviceDomainNameChange(body: bad), "\(bad)") { error in
                XCTAssertEqual((error as? DeviceAPIError)?.code, "invalid_parameter")
            }
        }
    }

    func testContractAndRoutes() {
        let names = HostAPIContract.operations.filter { $0.path == DeviceDomainName.operationPath }
        XCTAssertEqual(Set(names.map(\.method)), ["GET", "POST", "DELETE"])
        XCTAssertEqual(names.first { $0.method == "GET" }?.grant, .configurationRead)
        XCTAssertTrue(names.filter { $0.method != "GET" }.allSatisfy { $0.grant == .configurationWrite })
        XCTAssertTrue(names.allSatisfy(OwnerDirectHostService.permitsOperation))
        let revision = HostAPIChanges.document(since: 8, appVersion: "test")["changes"] as? [[String: Any]]
        XCTAssertEqual(revision?.count, 1)
        XCTAssertEqual((revision?.first?["items"] as? [[String: Any]])?.first?["endpoint"] as? String, DeviceDomainName.operationPath)
    }
}

/// The signed transport: no live backend.
private final class NamesHTTP: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var seen: [URLRequest] = []
    nonisolated(unsafe) private static var answers: [String: [(Int, String)]] = [:]
    static func reset(_ scripted: [String: [(Int, String)]]) { lock.lock(); seen = []; answers = scripted; lock.unlock() }
    static func requests() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return seen }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
        Self.lock.lock()
        Self.seen.append(request)
        let reply = Self.answers[path]?.isEmpty == false ? Self.answers[path]!.removeFirst() : (500, "{}")
        Self.lock.unlock()
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.0, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.1.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class InstallClientNamesTests: XCTestCase {
    private func client() throws -> (InstallClient, String) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [NamesHTTP.self]
        let signer = try DeviceSigner.ephemeral()
        return (InstallClient(signer: signer, base: URL(string: "https://names.test")!, http: URLSession(configuration: configuration)), signer.installID)
    }

    func testRegistersOnceSignsAndReRegistersWhenForgotten() async throws {
        let (client, id) = try client()
        let registered = #"{"install_id":"\#(id)","status":"active","trust":"unverified"}"#
        NamesHTTP.reset([
            "/v1/install/challenge": [(200, #"{"challenge":"c1"}"#), (200, #"{"challenge":"c2"}"#)],
            "/v1/install/register": [(201, registered), (200, registered)],
            "/v1/names/me": [(200, #"{"name":"porch-cam","visible":false}"#), (401, #"{"error":"unknown_install"}"#), (200, #"{"name":"porch-cam","visible":false}"#)]
        ])
        let (first, body) = try await client.teamCall(method: "GET", path: "/v1/names/me", body: nil, appVersion: "1.0")
        XCTAssertEqual(first, 200); XCTAssertEqual(body["name"] as? String, "porch-cam")
        let (second, _) = try await client.teamCall(method: "GET", path: "/v1/names/me", body: nil, appVersion: "1.0")
        XCTAssertEqual(second, 200)
        let paths = NamesHTTP.requests().compactMap { $0.url?.path }
        XCTAssertEqual(paths, ["/v1/install/challenge", "/v1/install/register", "/v1/names/me", "/v1/names/me",
                               "/v1/install/challenge", "/v1/install/register", "/v1/names/me"])
        let names = NamesHTTP.requests().filter { $0.url?.path == "/v1/names/me" }
        XCTAssertTrue(names.allSatisfy { $0.value(forHTTPHeaderField: "X-Bridgit-Install") == id })
        XCTAssertTrue(names.allSatisfy { $0.value(forHTTPHeaderField: "X-Bridgit-Signature")?.hasPrefix("at=") == true })
        XCTAssertTrue(names.allSatisfy { $0.httpMethod == "GET" })
    }

    func testNonSuccessIsReturnedNotThrown() async throws {
        let (client, id) = try client()
        NamesHTTP.reset([
            "/v1/install/challenge": [(200, #"{"challenge":"c1"}"#)],
            "/v1/install/register": [(201, #"{"install_id":"\#(id)"}"#)],
            "/v1/names": [(409, #"{"error":"name_taken","message":"taken"}"#)]
        ])
        let (status, body) = try await client.teamCall(method: "POST", path: "/v1/names", body: ["name": "porch-cam"], appVersion: "1.0")
        XCTAssertEqual(status, 409); XCTAssertEqual(body["error"] as? String, "name_taken")
    }

    func testOnlyNamesPathsAreAllowed() async throws {
        let (client, _) = try client()
        NamesHTTP.reset([:])
        do { _ = try await client.teamCall(method: "POST", path: "/v1/reports", body: [:], appVersion: "1.0"); XCTFail() }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_parameter") }
        XCTAssertTrue(NamesHTTP.requests().isEmpty)
    }
}
