import WebKit
import BridgitCore
import CryptoKit
import Foundation
import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

private final class PhoneMembershipProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "fixture.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-session")
        let path = request.url!.path
        let output: String
        switch (request.httpMethod, path) {
        case ("GET", "/v1/watch/phones"):
            output = #"{"phones":[{"phone":"test-phone","name":"Kitchen","agent_history":[]}]}"#
        case ("POST", "/v1/watch/link"), ("POST", "/v1/watch/unlink"):
            var bytes = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 256)
                while true {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    bytes.append(contentsOf: buffer.prefix(count))
                }
            }
            XCTAssertEqual((try? JSONDecoder().decode([String: String].self, from: bytes)), path.hasSuffix("/unlink") ? ["phone": "test-phone"] : ["code": "ABCD2345"])
            output = path.hasSuffix("/unlink") ? #"{"unlinked":true,"deletion_pending":false}"# : #"{"linked":true}"#
        default: XCTFail("Unexpected account route"); output = "{}"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: path == "/v1/watch/link" && request.httpMethod == "POST" ? 201 : 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(output.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor private final class ViewerFixture: ViewerConfiguration {
    var configuredCloud = WatchClient(base: URL(string: "https://fixture.invalid")!)
    func refreshConfiguredAccount() async {}
    var configuredPairings: [Pairing] = []
    var configuredAttachment: String?
    var configuredAccountEmail: String? { nil }
    var configuredSignedIn = false
    var configuredSigningIn = false
    var configuredViewsPaused: Bool? = false
    var configuredDevices: [ViewerDeviceDescription] { configuredPairings.map { .init(id: "relay:" + $0.id, name: $0.name, installID: $0.install, relayDeviceID: $0.deviceId) } }
    func beginConfiguredSignIn() { configuredSigningIn = true }
    func cancelConfiguredSignIn() { configuredSigningIn = false }
    func configuredSignOut() async { configuredSigningIn = false; configuredSignedIn = false }
    func saveConfiguredPairing(_ value: Pairing) throws { configuredPairings.removeAll { $0.id == value.id }; configuredPairings.append(value) }
    func removeConfiguredPairing(_ value: Pairing) throws { configuredPairings.removeAll { $0.id == value.id } }
    func renameConfiguredPairing(_ value: Pairing, name: String?) throws { if let index = configuredPairings.firstIndex(where: { $0.id == value.id }) { configuredPairings[index].localAlias = name } }
    func selectConfiguredDevice(_ id: String?) throws { configuredAttachment = id }
    func setConfiguredViewsPaused(_ value: Bool) throws { configuredViewsPaused = value }
}
final class ViewerConfigurationTests: XCTestCase {
    @MainActor func testSignedInPhoneMembershipThroughAccountClient() async throws {
        let viewer = ViewerFixture()
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.protocolClasses = [PhoneMembershipProtocol.self]
        let session = URLSession(configuration: sessionConfig)
        defer { session.invalidateAndCancel() }
        viewer.configuredCloud = WatchClient(base: URL(string: "https://fixture.invalid")!, session: "fixture-session", http: session)
        let list = HostRequest(method: "GET", path: "/v1/account/phones")
        do {
            _ = try await ViewerConfigurationRouter.execute(ViewerConfigurationAction(list), viewer: viewer, authorize: {})
            XCTFail("Requires a signed-in account")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "account_sign_in_required") }
        viewer.configuredSignedIn = true
        for request in [list, HostRequest(method: "POST", path: list.path, body: ["code": "abcd-2345"]), HostRequest(method: "DELETE", path: list.path + "/test-phone")] {
            XCTAssertEqual(request.route?.0, request.method == "GET" ? .configurationRead : .configurationWrite)
            let result = try await ViewerConfigurationRouter.execute(ViewerConfigurationAction(request), viewer: viewer, authorize: {})
            XCTAssertEqual(result.status, 200)
            let text = String(decoding: result.data, as: UTF8.self)
            if request.method == "GET" { XCTAssertTrue(text.contains("Kitchen")); XCTAssertFalse(text.contains("agent_history")) }
            XCTAssertFalse(text.contains("fixture-session")); XCTAssertFalse(text.contains("ABCD2345"))
        }
        XCTAssertThrowsError(try ViewerConfigurationAction(.init(method: "POST", path: list.path, body: ["code": "x"])))
        do {
            _ = try await ViewerConfigurationRouter.execute(.linkPhone("ABCD2345"), viewer: viewer, authorize: { viewer.configuredCloud.session = "replacement" })
            XCTFail("Must reject a change of account during authorization")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "account_changed") }
    }
    @MainActor func testAdministratorControlsPeerGrantsAndTransferSettings() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: path)
        await local.host?.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(local.host?.authority)
        let store = try XCTUnwrap(local.store)
        let engine = try XCTUnwrap(local.engine)
        let (admin, _) = try await authority.create(name: "Control", operations: [.administrator], applets: [])
        let (ordinary, _) = try await authority.create(name: "Scoped", operations: [.configurationRead, .configurationWrite], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: local.configuration)
        await authority.setState(enabled: true, awake: true, foreground: true)
        var sequence: Int64 = 0
        func send(_ agent: HostAgent, _ method: String, _ route: String, _ body: [String: Any] = [:], key: String? = nil) async -> HostResponse {
            sequence += 1
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: authority.deviceID, deadline: now + 30000, createdAt: now,
                request: .init(method: method, path: route, body: body, idempotencyKey: key ?? UUID().uuidString)))
        }
        let catalog = await send(admin, "GET", "/v1/openapi.json")
        let api = try XCTUnwrap(JSONSerialization.jsonObject(with: catalog.data) as? [String: Any])
        let routes = try XCTUnwrap(api["paths"] as? [String: Any])
        for path in ["/v1/agents", "/v1/agents/{id}", "/v1/agents/me", "/v1/linked-devices", "/v1/settings"] {
            XCTAssertNotNil(routes[path], path)
        }
        let elevate = ["operations": ["agents.admin"]]
        let peerPath = "/v1/agents/" + ordinary.id
        let denied = await send(ordinary, "PATCH", peerPath, elevate)
        XCTAssertEqual(denied.status, 403)
        let deniedSelf = await send(ordinary, "PATCH", "/v1/agents/me", elevate)
        XCTAssertEqual(deniedSelf.status, 403)
        let renamed = await send(ordinary, "PATCH", "/v1/agents/me", ["name": "Personal"])
        XCTAssertEqual(renamed.status, 200)
        let own = await send(ordinary, "GET", "/v1/agents/me")
        XCTAssertEqual(own.status, 200)
        XCTAssertFalse(String(decoding: own.data, as: UTF8.self).contains("Control"))
        let add: [String: Any] = ["name": "Second", "operations": ["settings.read"], "token_hash": String(repeating: "b", count: 64)]
        let created = await send(admin, "POST", "/v1/agents", add, key: "new-agent")
        XCTAssertEqual(created.status, 201)
        let retry = await send(admin, "POST", "/v1/agents", add, key: "new-agent")
        XCTAssertEqual(created.data, retry.data)
        let list = await send(admin, "GET", "/v1/agents")
        XCTAssertEqual(list.status, 200)
        XCTAssertFalse(String(decoding: list.data, as: UTF8.self).contains("token_hash"))
        let response = await send(ordinary, "POST", "/v1/settings", ["field": "files_enabled", "value": true])
        XCTAssertEqual(response.status, 202)
        let reduced = await send(admin, "PATCH", peerPath, ["operations": ["settings.read"]])
        XCTAssertEqual(reduced.status, 200)
        await response.afterDelivery?()
        let before = try await store.enabledTransfers()
        XCTAssertFalse(before.contains(.files), "An accepted action must recheck the current grant before applying.")
        let change = await send(admin, "POST", "/v1/settings", ["field": "files_enabled", "value": true])
        XCTAssertEqual(change.status, 202)
        await change.afterDelivery?()
        let after = try await store.enabledTransfers()
        XCTAssertTrue(after.contains(.files))
        try await authority.setAdministrator(ordinary.id, enabled: true)
        let restarted = HostAuthority(store: store, deviceID: await authority.deviceID)
        let persisted = try await restarted.agent(ordinary.id)
        XCTAssertTrue(persisted?.allows(.administrator) == true)
        XCTAssertTrue(persisted?.allowsApplet("another-applet", operation: .workflowsRun) == true)
        XCTAssertEqual(persisted?.tokenHash, ordinary.tokenHash)
        try await authority.setAdministrator(ordinary.id, enabled: false)
        let demoted = await send(ordinary, "GET", "/v1/agents")
        XCTAssertEqual(demoted.status, 403)
        let promoted = await send(admin, "PATCH", peerPath, elevate)
        XCTAssertEqual(promoted.status, 200)
        let delegated = await send(ordinary, "GET", "/v1/agents")
        XCTAssertEqual(delegated.status, 200)
        let paused = await send(admin, "PATCH", peerPath, ["paused": true])
        XCTAssertEqual(paused.status, 200)
        let stopped = await send(ordinary, "GET", "/v1/settings")
        XCTAssertEqual(stopped.status, 403)
        let resumed = await send(admin, "PATCH", peerPath, ["paused": false])
        XCTAssertEqual(resumed.status, 200)
        let removed = await send(admin, "DELETE", peerPath)
        XCTAssertEqual(removed.status, 200)
        let revoked = await send(ordinary, "GET", "/v1/agents")
        XCTAssertEqual(revoked.status, 403)
    }

    @MainActor func testAppearanceAPIAdvertisesAppliedThemeAndReset() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let defaults = AppleFixtureWorkspace.defaults
        let saved = defaults.object(forKey: "appearance")
        defer {
            defaults.set(saved, forKey: "appearance")
            try? FileManager.default.removeItem(at: path)
        }
        let local = LocalAppletModel(directory: path)
        await local.host?.prepare(identityOverride: try .ephemeral())
        let authority = try XCTUnwrap(local.host?.authority)
        let (agent, _) = try await authority.create(name: "Theme fixture", operations: [.configurationRead, .configurationWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        for (request, expected) in [(HostRequest(method: "POST", path: "/v1/settings", body: ["field": "appearance", "value": "Dark"]), "Dark"),
                                    (HostRequest(method: "DELETE", path: "/v1/settings/appearance"), "System")] {
            let response = try await local.configuration.configuration(request, agent: agent)
            XCTAssertEqual(response.status, 202)
            await response.afterDelivery?()
            let read = try await local.configuration.configuration(.init(method: "GET", path: "/v1/settings"), agent: agent)
            let result = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
            let supported = try XCTUnwrap(result["supported_fields"] as? [String])
            XCTAssertTrue(supported.contains("appearance"))
            let values = try XCTUnwrap(result["values"] as? [String: Any])
            XCTAssertEqual(values["appearance"] as? String, expected)
            XCTAssertEqual(defaults.string(forKey: "appearance"), expected)
        }
    }

    @MainActor func testPairingCredentialsAreWriteOnlyAndNeverStoredInOperationReplies() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: path) }
        let local = LocalAppletModel(directory: path), viewer = ViewerFixture()
        await local.host?.prepare(identityOverride: try .ephemeral()); local.configuration.viewer = viewer
        let authority = try XCTUnwrap(local.host?.authority), store = try XCTUnwrap(local.store), engine = try XCTUnwrap(local.engine)
        let (agent, _) = try await authority.create(name: "Fixture control", operations: [.configurationRead, .configurationWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, configuration: local.configuration)
        func job(_ sequence: Int64, _ request: HostRequest) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: local.host!.deviceID!, deadline: now + 30000, createdAt: now, request: request)
        }
        let secret = "rly_" + String(repeating: "fixture", count: 8)
        let setup = String(decoding: try JSONSerialization.data(withJSONObject: ["base": "https://relay.invalid/d/device_fixture_123", "device_id": "device_fixture_123", "token": secret, "name": "Fixture device"]), as: UTF8.self)
        let request = HostRequest(method: "POST", path: "/v1/linked-devices", body: ["setup": setup], idempotencyKey: "add-link")
        let response = await dispatcher.dispatch(job(1, request)), retried = await dispatcher.dispatch(job(2, request))
        XCTAssertEqual(response.status, 201); XCTAssertEqual(response.data, retried.data)
        XCTAssertEqual(viewer.configuredPairings.count, 1); XCTAssertEqual(viewer.configuredPairings.first?.token, secret)
        XCTAssertFalse(String(decoding: response.data, as: UTF8.self).contains(secret))
        let read = await dispatcher.dispatch(job(3, .init(method: "GET", path: "/v1/linked-devices")))
        XCTAssertEqual(read.status, 200); XCTAssertFalse(String(decoding: read.data, as: UTF8.self).contains(secret))
        let key = "host-idempotency:" + agent.id + ":" + agent.generation + ":" + SHA256.hash(data: Data("add-link".utf8)).map { String(format: "%02x", $0) }.joined()
        let savedValue = try await store.state(key)
        let saved = try XCTUnwrap(savedValue), envelope = try JSONSerialization.jsonObject(with: Data(saved.utf8)) as! [String: Any]
        let body = try XCTUnwrap(Data(base64Encoded: envelope["body_base64"] as! String))
        XCTAssertFalse(String(decoding: body, as: UTF8.self).contains(secret))
        let pending = try await store.ownerRequests(); XCTAssertTrue(pending.isEmpty, "Credentials must not enter configuration proposal payloads")
        let agents = try await authority.list(); XCTAssertEqual(agents.count, 1, "A view link cannot create host authority")
        let renamed = await dispatcher.dispatch(job(4, .init(method: "PATCH", path: "/v1/linked-devices/pairings/device_fixture_123", body: ["name": "Renamed fixture"], idempotencyKey: "rename")))
        XCTAssertEqual(renamed.status, 200); XCTAssertEqual(viewer.configuredPairings.first?.displayName, "Renamed fixture")
        let attached = await dispatcher.dispatch(job(5, .init(method: "POST", path: "/v1/linked-devices/attachment", body: ["id": "relay:device_fixture_123"], idempotencyKey: "attach")))
        XCTAssertEqual(attached.status, 200); XCTAssertEqual(viewer.configuredAttachment, "relay:device_fixture_123")
        let removed = await dispatcher.dispatch(job(6, .init(method: "DELETE", path: "/v1/linked-devices/pairings/device_fixture_123", idempotencyKey: "remove")))
        XCTAssertEqual(removed.status, 200); XCTAssertTrue(viewer.configuredPairings.isEmpty)
    }
    func testOnlyRelayPairingsEstablishViewRoles() {
        let snapshot = ViewerDeviceDescription(id: "cloud:test", name: "Kitchen", installID: "test")
        XCTAssertEqual(snapshot.json["access"] as? String, "account_snapshot")
        XCTAssertNil(snapshot.json["remote_role"])
        XCTAssertNil(snapshot.json["local_role"])
        let paired = ViewerDeviceDescription(id: "relay:peer", name: "Kitchen", installID: "test", relayDeviceID: "peer")
        XCTAssertEqual(paired.json["access"] as? String, "relay_view")
        XCTAssertEqual(paired.json["local_role"] as? String, "satellite")
        XCTAssertEqual(paired.json["remote_role"] as? String, "hub")
    }

    @MainActor func testViewPauseIsAdvertisedSeparatelyFromRoleConnectionControls() async throws {
        let viewer = ViewerFixture()
        for paused: Bool? in [nil, false, true] {
            viewer.configuredViewsPaused = paused
            let response = try await ViewerConfigurationRouter.execute(.linkedDevices, viewer: viewer, authorize: {})
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
            XCTAssertEqual(body["supports_views_pause"] as? Bool, paused != nil)
            XCTAssertEqual(body["views_paused"] as? Bool, paused)
            XCTAssertEqual(body["supports_hub_connections_pause"] as? Bool, false)
            XCTAssertEqual(body["supports_satellite_connections_pause"] as? Bool, false)
        }
    }

    @MainActor func testAccountInitiationUsesNativeHumanFlowWithoutReturningCredentials() async throws {
        let viewer = ViewerFixture()
        let started = try await ViewerConfigurationRouter.execute(.signIn, viewer: viewer, authorize: {})
        XCTAssertEqual(started.status, 202); XCTAssertTrue(viewer.configuredSigningIn); XCTAssertFalse(viewer.configuredSignedIn)
        _ = try await ViewerConfigurationRouter.execute(.cancelSignIn, viewer: viewer, authorize: {})
        XCTAssertFalse(viewer.configuredSigningIn)
        do { _ = try await ViewerConfigurationRouter.execute(.signIn, viewer: viewer, authorize: { throw CancellationError() }); XCTFail("Revoked caller initiated sign-in") } catch {}
        XCTAssertFalse(viewer.configuredSigningIn)
    }
}

@MainActor final class AppletPageBrowserTests: XCTestCase {
    func testOfflineNavigationReportsFailureAndANewNavigationClearsIt() {
        let browser = AppletPageBrowser()
        let view = WKWebView()
        browser.view = view
        browser.started(view)
        XCTAssertTrue(browser.loading)
        browser.failed(view, error: URLError(.cancelled), fallback: nil)
        XCTAssertNil(browser.problem)
        browser.failed(view, error: URLError(.notConnectedToInternet), fallback: AppletLists.buildGuide)
        XCTAssertFalse(browser.loading)
        XCTAssertEqual(browser.problem, "Check your internet connection, then reload this page.")
        browser.started(view)
        XCTAssertNil(browser.problem)
        browser.finished(view)
        XCTAssertFalse(browser.loading)
    }
}
