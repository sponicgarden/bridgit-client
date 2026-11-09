import BridgitCore
import Foundation
import XCTest
@testable import Bridgit_iOS

private final class DelayedAccountProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var held: [String: DelayedAccountProtocol] = [:]
    private static var hold = Set<String>()
    static var waiting: ((String) -> Void)?
    static func reset(paths: Set<String>, waiting callback: @escaping (String) -> Void) {
        lock.lock(); held = [:]; hold = paths; waiting = callback; lock.unlock()
    }
    static func release(_ suffix: String) {
        lock.lock(); let request = held.removeValue(forKey: suffix); hold.remove(suffix); lock.unlock()
        request?.reply()
    }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "account.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let suffix = request.url!.lastPathComponent
        Self.lock.lock()
        let shouldHold = Self.hold.contains(suffix)
        if shouldHold { Self.held[suffix] = self }
        let callback = Self.waiting; Self.lock.unlock()
        if shouldHold { callback?(suffix) } else { reply() }
    }
    private func reply() {
        let suffix = request.url!.lastPathComponent
        let body: String
        switch suffix {
        case "phones": body = "{\"phones\":[]}"
        case "applets": body = "{\"applets\":[]}"
        case "request": body = "{\"request\":\"fixture-request\"}"
        case "pairing": body = "{\"status\":\"approved\",\"relay\":\"https://relay.invalid\",\"device\":\"device_fixture_123\",\"token\":\"rly_fixturefixturefixturefixturefixture\",\"name\":\"Old account device\"}"
        default: body = "{}"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: (request.value(forHTTPHeaderField: "Authorization")?.contains("failurefixture") == true ? 500 : 200), httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8)); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor final class AccountLifecycleTests: XCTestCase {
    private func model() -> (AppModel, URLSession) {
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [DelayedAccountProtocol.self]
        let session = URLSession(configuration: configuration)
        let client = WatchClient(base: URL(string: "https://account.invalid")!, http: session)
        return (AppModel(cloud: client, relays: RelayStore(loadStored: false), loadStored: false, accessPollNanoseconds: 0), session)
    }
    func testUninitiatedCallbackCannotSignInAfterSignOut() async throws {
        DelayedAccountProtocol.reset(paths: [], waiting: { _ in })
        let (model, session) = model(); defer { session.invalidateAndCancel() }
        await model.adopt(session: "bws_" + String(repeating: "oldfixture", count: 4), email: nil)
        await model.signOut()
        model.handle(url: URL(string: "https://bridgit.cc/app-signin/complete/ios#session=bws_" + String(repeating: "latefixture", count: 4) + "&state=" + String(repeating: "a", count: 64))!)
        for _ in 0..<100 { await Task.yield() }
        XCTAssertEqual(model.mode, .signedOut); XCTAssertNil(model.cloud.session)
    }
    func testAccountSwitchDropsOldRowsEvenIfNewRefreshFails() async {
        DelayedAccountProtocol.reset(paths: [], waiting: { _ in })
        let (model, session) = model(); defer { session.invalidateAndCancel() }
        model.phones = SampleData.phones
        model.applets = SampleData.applets
        await model.adopt(session: "bws_" + String(repeating: "failurefixture", count: 3), email: "new@example.test")
        XCTAssertEqual(model.mode, .signedIn)
        XCTAssertTrue(model.phones.isEmpty); XCTAssertTrue(model.applets.isEmpty)
        XCTAssertNotNil(model.error)
    }
    func testOldAccountPairingReplyCannotBeSavedAfterAccountSwitch() async throws {
        DelayedAccountProtocol.reset(paths: [], waiting: { _ in })
        let (model, session) = model(); defer { session.invalidateAndCancel() }
        await model.adopt(session: "bws_" + String(repeating: "oldfixture", count: 4), email: nil)
        let waiting = expectation(description: "Old account pairing response pending")
        DelayedAccountProtocol.reset(paths: ["pairing"], waiting: { if $0 == "pairing" { waiting.fulfill() } })
        let phone = SampleData.phones[0]
        let request = Task { await model.requestLiveAccess(for: phone, progress: { _ in }) }
        await fulfillment(of: [waiting], timeout: 5)
        await model.adopt(session: "bws_" + String(repeating: "newfixture", count: 4), email: nil)
        DelayedAccountProtocol.release("pairing"); await request.value
        XCTAssertTrue(model.relays.pairings.isEmpty)
    }
}
