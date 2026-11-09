import Foundation
import XCTest
import BridgitCore
@testable import Bridgit_macOS

private final class AccountProtocol: URLProtocol {
    static let lock = NSLock()
    static var relayCalls = 0
    static var stopped = 0
    static func counts() -> (Int, Int) { lock.lock(); defer { lock.unlock() }; return (relayCalls, stopped) }
    override class func canInit(with request: URLRequest) -> Bool { ["api.invalid", "relay.invalid"].contains(request.url?.host ?? "") }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        if request.url?.host == "relay.invalid" { Self.lock.lock(); Self.relayCalls += 1; Self.lock.unlock() }
        let forbidden = request.value(forHTTPHeaderField: "Authorization")?.contains("newaccount") == true
        let body = path.hasSuffix("phones") ? "{\"phones\":[{\"phone\":\"bgi_old\",\"name\":\"Old account\"}]}" : path.hasSuffix("applets") ? "{\"applets\":[{\"phone\":\"bgi_old\",\"applet\":\"dog\"},{\"phone\":\"bgi_other\",\"applet\":\"dog\"}]}" : "{\"response\":{\"status\":200,\"body\":{}}}"
        DispatchQueue.global().asyncAfter(deadline: .now() + (path.hasSuffix("session") ? 0 : 0.12)) { [weak self] in
            guard let self else { return }
            self.client?.urlProtocol(self, didReceive: HTTPURLResponse(url: self.request.url!, statusCode: forbidden ? 500 : 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: Data(body.utf8))
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { Self.lock.lock(); Self.stopped += 1; Self.lock.unlock() }
}
/// Only the fixture host is registered. A successful reply proves the request used the paired
/// device, the desktop client metadata, and an explicitly selected target with real JPEG bytes.
private final class CameraRelayProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            var n = stream.read(&buffer, maxLength: buffer.count)
            while n > 0 {
                data.append(contentsOf: buffer.prefix(n))
                n = stream.read(&buffer, maxLength: buffer.count)
            }
        }
        let envelope = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let job = envelope?["request"] as? [String: Any]
        let query = job?["query"] as? [String: String]
        let body = job?["body"] as? [String: Any]
        var status = 403
        var reply: [String: Any] = [:]
        if request.url?.host == "relay.invalid", request.url?.path == "/d/dev_ABCDEFGH12/jobs",
           request.value(forHTTPHeaderField: "Authorization") == "Bearer rly_fixture",
           query?["client"] == "Mac", query?["client_type"] == "computer" {
            switch (job?["method"] as? String, job?["path"] as? String) {
            case ("GET", "/v1/portal/snapshot"):
                status = 200
                reply = ["home": ["scene": ["options": ["nodes": ["camera": "on"]], "paused": true, "listening": ["camera"]]],
                         "logs": ["entries": [["id": 3, "message": "Newest"]], "has_older": true]]
            case ("GET", "/v1/portal/logs"):
                if query?["until"] == "3" {
                    status = 200
                    reply = ["entries": [["id": 2, "message": "Older"]], "has_older": false]
                }
            case ("GET", "/v1/portal/photo/targets"):
                status = 200
                reply = ["targets": [["id": "one", "name": "Kitchen"], ["id": "two", "name": "Garden"]], "chosen": ["two"]]
            case ("POST", "/v1/portal/photo"):
                if body?["targets"] as? [String] == ["two"], body?["camera"] as? String == "front",
                   let encoded = body?["data_base64"] as? String, let jpeg = Data(base64Encoded: encoded),
                   jpeg.starts(with: [0xff, 0xd8]) {
                    status = 200
                    reply = ["targets": [["id": "two", "name": "Garden", "checks": [["summary": "Visible"]]]]]
                }
            default: break
            }
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: ["response": ["status": status, "body": reply]]))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

/// Holds only the first synthetic profile reply so selection can change while it is in flight.
private final class DelayedProfileRelayProtocol: URLProtocol {
    static let gate = DispatchSemaphore(value: 0)
    static let lock = NSLock()
    static var started: (() -> Void)?
    static var held = false
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "profile-relay.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var bytes = request.httpBody ?? Data()
        if bytes.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while true {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                bytes.append(contentsOf: buffer.prefix(count))
            }
        }
        let envelope = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
        let job = envelope?["request"] as? [String: Any]
        let isProfile = job?["path"] as? String == "/v1/device/profile"
        Self.lock.lock()
        let hold = isProfile && !Self.held
        if hold { Self.held = true }
        let started = Self.started
        Self.lock.unlock()
        DispatchQueue.global().async {
            if hold { started?(); Self.gate.wait() }
            let now = Date()
            let profile = DeviceProfile(profileRevision: 1, observedAt: now, expiresAt: now.addingTimeInterval(60),
                device: .init(installID: nil, name: "Fixture", deviceType: "phone", platform: "ios", osVersion: "1", appVersion: "1", apiRevision: 1, services: [], hardware: [:]),
                availability: .init(state: "online", inForeground: true), capabilities: [:])
            let body: Any = isProfile ? ((try? JSONSerialization.jsonObject(with: profile.encoded())) ?? [:]) : [:]
            let response = HTTPURLResponse(url: self.request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: ["response": ["status": 200, "body": body]]))
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}

@MainActor
final class MacModelTests: XCTestCase {
    @MainActor func testPausedAccountClientRefusesBeforeURLSession() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AccountProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let base = try XCTUnwrap(URL(string: "https://relay.invalid"))
        let before = AccountProtocol.counts().0
        let paused = WatchClient(base: base, session: "synthetic-session", http: session, outboundAccess: .init(paused: { true }))
        do { _ = try await paused.phones(); XCTFail("Paused account client reached its transport") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        XCTAssertEqual(AccountProtocol.counts().0, before)
        let available = WatchClient(base: base, session: "synthetic-session", http: session, outboundAccess: .unrestricted)
        _ = try await available.phones()
        XCTAssertEqual(AccountProtocol.counts().0, before + 1)
    }

    func testAbilityProfileReplyCannotFollowSelectionAwayAndBack() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [DelayedProfileRelayProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let model = MacModel(cloud: WatchClient(), loadStored: false, relayHTTP: session)
        let pairing = Pairing(base: URL(string: "https://profile-relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_fixture", name: "Fixture")
        let started = expectation(description: "profile request reached selected peer")
        DelayedProfileRelayProtocol.lock.lock()
        DelayedProfileRelayProtocol.held = false
        DelayedProfileRelayProtocol.started = { started.fulfill() }
        DelayedProfileRelayProtocol.lock.unlock()
        defer { DelayedProfileRelayProtocol.gate.signal() }
        model.select(pairing)
        let pending = Task { try await model.readSelectedAbilityProfile() }
        await fulfillment(of: [started], timeout: 10)
        try model.selectConfiguredDevice("local")
        model.select(pairing)
        DelayedProfileRelayProtocol.gate.signal()
        do { _ = try await pending.value; XCTFail("A reply from the earlier selection must be rejected") }
        catch is CancellationError { }
        catch { XCTFail("Expected selection cancellation, got \(error)") }
        let current = try await model.readSelectedAbilityProfile()
        XCTAssertEqual(current.device.name, "Fixture")
    }

    func testDiagnosticsHistoryKeepsOlderEntriesAcrossRefreshAndClearsOnLocalSelection() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CameraRelayProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let model = MacModel(cloud: WatchClient(), loadStored: false, relayHTTP: session)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_fixture", name: "Fixture")
        model.select(pairing)
        await model.refreshSelected()
        await model.loadOlderDiagnosticsLogs()
        await model.refreshSelected()
        let page = try XCTUnwrap(model.snapshot["logs"] as? [String: Any])
        XCTAssertEqual((page["entries"] as? [[String: Any]])?.compactMap { $0["id"] as? Int }, [3, 2])
        XCTAssertEqual(page["has_older"] as? Bool, false)
        try model.selectConfiguredDevice("local")
        await model.loadOlderDiagnosticsLogs()
        XCTAssertTrue(model.snapshot.isEmpty)
        XCTAssertFalse(model.viewingLinkedDevice)
    }

    func testConfirmedPairingIsSavedAfterDialogDismissal() async throws {
        let model = MacModel(cloud: WatchClient(), loadStored: false)
        model.pause()
        let link = "bridgit://pair#v=1&relay=https%3A%2F%2F127.0.0.1%3A1&device=kitchen-fixture&token=rly_00000000000000000000000000000000&name=Kitchen%20phone"
        model.preparePairing(link)
        let confirmed = try XCTUnwrap(model.pendingPairing)
        model.pendingPairing = nil
        XCTAssertTrue(model.pairings.isEmpty)
        await model.confirmPairing(confirmed)
        XCTAssertEqual(model.selectedDeviceName, "Kitchen phone")
        XCTAssertEqual(model.pairings.map(\.id), [confirmed.id])
        try model.selectConfiguredDevice(nil)
        XCTAssertFalse(model.viewingLinkedDevice)
        XCTAssertEqual(model.pairings.map(\.id), [confirmed.id])
    }

    func testOwnerPhotoUsesOnlyTheSelectedPairingAndApplet() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CameraRelayProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let model = MacModel(cloud: WatchClient(), loadStored: false, relayHTTP: session)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_fixture", name: "Fixture")
        model.select(pairing)
        let choices = try await model.photoTargets(for: pairing.id)
        XCTAssertEqual(choices.targets.map(\.name), ["Kitchen", "Garden"])
        XCTAssertEqual(choices.chosen, ["two"])
        let pixel = try XCTUnwrap(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGkAAAAAASUVORK5CYII="))
        let results = try await model.sendPhoto(pixel, front: true, targets: choices.chosen, to: pairing.id)
        XCTAssertEqual(results.map(\.text), ["Garden: Visible"])
        do {
            _ = try await model.sendPhoto(pixel, front: true, targets: ["one"], to: pairing.id)
            XCTFail("The fixture denies this target")
        } catch RelayError.notAllowed { }
        model.pause()
        do {
            _ = try await model.sendPhoto(pixel, front: true, targets: ["two"], to: pairing.id)
            XCTFail("A paused device must not receive photos")
        } catch is CancellationError { }
        model.remove(pairing)
        model.pause()
        do {
            _ = try await model.sendPhoto(pixel, front: true, targets: ["two"], to: pairing.id)
            XCTFail("A removed pairing must not receive photos")
        } catch is CancellationError { }
    }

    func testSignOutCannotRestoreOldAccountData() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let cloud = WatchClient(base: URL(string: "https://api.invalid")!, session: "fixture", http: session)
        let model = MacModel(cloud: cloud, loadStored: false)
        let refresh = Task { await model.refreshAccount() }
        try await Task.sleep(nanoseconds: 30_000_000)
        await model.signOut(); await refresh.value
        XCTAssertFalse(model.signedIn); XCTAssertTrue(model.phones.isEmpty); XCTAssertTrue(model.applets.isEmpty)
        XCTAssertFalse(model.busy)
    }
    func testAccountSwitchClearsOldDataEvenWhenNewRefreshFails() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let cloud = WatchClient(base: URL(string: "https://api.invalid")!, session: "fixture", http: session)
        let model = MacModel(cloud: cloud, loadStored: false)
        await model.refreshAccount()
        XCTAssertEqual(model.phones.first?.name, "Old account")
        await model.adoptAccount(session: "bws_newaccountabcdefghijklmnopqrstuvwxyz012345", email: nil)
        XCTAssertTrue(model.signedIn)
        XCTAssertTrue(model.phones.isEmpty); XCTAssertTrue(model.applets.isEmpty)
        XCTAssertNotNil(model.problem)
    }
    func testAppletPortalsStayOnTheSelectedInstall() async {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let model = MacModel(cloud: WatchClient(base: URL(string: "https://api.invalid")!, session: "fixture", http: session), loadStored: false)
        await model.refreshAccount()
        XCTAssertNil(model.selectedInstallID, "Account refresh must keep the local device selected")
        guard let phone = model.phones.first else { return XCTFail("Missing account fixture") }
        model.selectAccount(phone)
        XCTAssertEqual(model.selectedInstallID, "bgi_old")
        XCTAssertEqual(model.selectedAccountApplets.map(\.phone), ["bgi_old"])
        XCTAssertEqual(model.appletPortals.map(\.id), ["dog"])
        for portal in model.appletPortals { XCTAssertTrue(portal.url.absoluteString.contains("device=bgi_old")) }
    }
    func testMacCardLibraryUsesTheBundledPhoneSamples() {
        XCTAssertGreaterThanOrEqual(AppletLists.bundledCards.count, 5)
        XCTAssertTrue(AppletLists.bundledCards.allSatisfy { !$0.html.isEmpty })
        XCTAssertNil(CardPreviewRules.parseRespond(String(repeating: "x", count: 16385)))
        XCTAssertTrue(CardPreviewRules.prepare("<meta http-equiv='refresh'>").contains("data-blocked-equiv"))
        XCTAssertTrue(CardPreviewRules.guardScript.contains("XMLHttpRequest"))
    }
    func testPauseCancelsAToolbarOriginatedRelayRefresh() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let model = MacModel(cloud: WatchClient(), loadStored: false, relayHTTP: session)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_fixture", name: "Fixture")
        let before = AccountProtocol.counts()
        model.select(pairing)
        for _ in 0..<100 { if !model.busy && AccountProtocol.counts().0 > before.0 { break }; try await Task.sleep(nanoseconds: 5_000_000) }
        let refresh = Task { await model.refreshSelected() }
        for _ in 0..<100 { if AccountProtocol.counts().0 >= before.0 + 2 { break }; try await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertTrue(model.busy)
        model.pause(); await refresh.value
        XCTAssertFalse(model.busy)
        XCTAssertGreaterThan(AccountProtocol.counts().1, before.1)
        model.remove(pairing)
        XCTAssertNil(model.selectedPairingID); XCTAssertTrue(model.snapshot.isEmpty)
    }
    func testPauseStopsNewRefreshAndClearsBusy() async {
        let model = MacModel(cloud: WatchClient(session: "fixture"), loadStored: false)
        model.pause()
        await model.refreshSelected(); await model.refreshAccount()
        XCTAssertTrue(model.paused); XCTAssertFalse(model.busy)
        XCTAssertTrue(model.snapshot.isEmpty)
    }
    func testLocalProfileDoesNotClaimHostOrAgentCapabilities() {
        let model = MacModel(cloud: WatchClient(), loadStored: false)
        XCTAssertEqual(model.profile.device.platform, "macos")
        XCTAssertEqual(model.profile.device.services, ["view"])
        XCTAssertNil(model.profile.device.installID)
        XCTAssertTrue(model.profile.capabilities.isEmpty)
    }
    func testSignInRejectsOtherRoutes() {
        for host in ["pair", "satellite", "signin/extra", "evil@signin", "signin:22"] {
            XCTAssertFalse(MacModel.isSignIn(URL(string: "bridgit://" + host + "#session=fixture")!))
        }
        XCTAssertFalse(MacModel.isSignIn(URL(string: "bridgitportal://signin#session=fixture")!))
    }
}
