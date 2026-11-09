import Foundation
import XCTest
@testable import BridgitCore

/// No live registration, model calls or credentials: exercise the actual URLSession requests.
private final class SpeechHTTPFixture: URLProtocol {
    private static let lock = NSLock()
    private static var captured: [URLRequest] = []
    static func reset() { lock.lock(); defer { lock.unlock() }; captured = [] }
    static func requests() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return captured }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var observed = request
        if observed.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096)
            var data = Data()
            while true {
                let count = stream.read(&bytes, maxLength: bytes.count)
                if count <= 0 { break }
                data.append(contentsOf: bytes.prefix(count))
            }
            observed.httpBody = data
        }
        Self.lock.lock(); Self.captured.append(observed); Self.lock.unlock()
        let reply: [String: Any]
        switch request.url!.path {
        case "/v1/install/challenge": reply = ["challenge": "offline-fixture"]
        case "/v1/install/register": reply = ["install_id": request.value(forHTTPHeaderField: "X-Bridgit-Install") ?? ""]
        case "/v1/watch/unlink": reply = ["linked": false, "deletion_pending": true]
        case "/v1/watch/events": reply = ["linked": false, "stored": 0]
        case "/v1/watch/link-code":
            let body = observed.httpBody.flatMap { try? JSONDecoder().decode([String: String].self, from: $0) }
            let expired = body?["phone_name"] == "Expired"
            reply = ["code": body?["phone_name"] == "Malformed" ? "!abcd123" : "ABCDEFGH",
                     "url": "https://unexpected.invalid/", "expires_at": Int64(Date().timeIntervalSince1970 * 1000) + (expired ? -1000 : 600000)]
        case "/v1/reports/updates":
            reply = ["cursor": 70, "cursor_id": 10, "more": false,
                     "updates": [["phone_report_id": 321, "status": "fixed", "team_note": "Fixture fix"]]]
        case "/v1/reports/456/history": reply = [:]
        case "/v1/reports": reply = ["id": 456]
        case "/v1/agent-keys":
            reply = ["key": "bgk_" + String(repeating: "x", count: 43), "agent_id": "offline-team-agent",
                     "base_url": "https://offline.invalid", "role": "reporter"]
        case "/v1/agent-keys/revoke": reply = ["revoked": true]
        case "/v1/budgets/sync":
            let body = try! JSONSerialization.jsonObject(with: observed.httpBody!) as! [String: Any]
            let applet = (body["applets"] as! [[String: Any]])[0]["applet"] as! String
            reply = ["applets": [["applet": applet, "source": ["missing", "mismatch"].contains(applet) ? "package" : (applet == "agent-missing" ? "agent" : "owner"), "state": applet == "spent" ? "out" : "ok",
                                  "limits": ["missing", "owner-missing", "agent-missing"].contains(applet) ? [] : [["id": "owner-cap", "per": "week", "usd": 0.25]]]]]
        case "/v1/speech/resolve": reply = ["model": "aura-1", "processing": "cloud"]
        case "/v1/speech/speak": reply = ["status": "completed"]
        default:
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: reply))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class InstallSpeechTests: XCTestCase {
    func testCurrentDeviceCanIssueSignedWebCodeAndCheckClaimWithoutSharingEvents() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let linked = try await client.webAccountLinked(appVersion: "test")
        XCTAssertFalse(linked)
        let code = try await client.issueWebLinkCode(name: "Fixture Mac", deviceType: "computer", platform: "macos", appVersion: "test")
        XCTAssertEqual(code.url.absoluteString, "https://bridgit.cc/myapps?code=ABCDEFGH")
        XCTAssertGreaterThan(code.expires, Date())
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.map { $0.url?.path }, ["/v1/install/challenge", "/v1/install/register", "/v1/watch/events", "/v1/watch/link-code"])
        for r in requests.suffix(2) {
            XCTAssertEqual(r.httpMethod, "POST")
            XCTAssertNotNil(r.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        }
        XCTAssertEqual(requests[2].httpBody, Data(#"{"events":[]}"#.utf8))
        let body = try JSONDecoder().decode([String: String].self, from: XCTUnwrap(requests.last?.httpBody))
        XCTAssertEqual(body, ["phone_name": "Fixture Mac", "device_type": "computer", "platform": "macos"])
        let deletionPending = try await client.turnOffWebView()
        XCTAssertTrue(deletionPending)
        XCTAssertEqual(SpeechHTTPFixture.requests().last?.url?.path, "/v1/watch/unlink")
        XCTAssertNotNil(SpeechHTTPFixture.requests().last?.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        for name in ["Expired", "Malformed"] {
            do {
                _ = try await client.issueWebLinkCode(name: name, deviceType: "phone", appVersion: "test")
                XCTFail("An invalid or expired reply must not produce a setup link")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_response") }
        }
    }

    func testReportedMetadataNormalizesBeforeFillingLegacyType() {
        XCTAssertEqual(ReportedDeviceMetadata(device: ["platform": " IOS ", "device_type": " ", "type": " TABLET "]), .init(platform: "ios", deviceType: "tablet"))
        XCTAssertTrue(ReportedDeviceMetadata(platform: " ", deviceType: "<bad>").isEmpty)
    }

    func testLinkCodeCarriesNormalizedDescriptiveMetadataForEveryAppleDevice() async throws {
        for (platform, type) in [("macos", "computer"), ("ios", "tablet"), ("ios", "phone"), ("future_os", "future_device")] {
            SpeechHTTPFixture.reset()
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [SpeechHTTPFixture.self]
            let session = URLSession(configuration: configuration)
            defer { session.invalidateAndCancel() }
            let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
            _ = try await client.issueWebLinkCode(name: "Misleading iPhone nickname", deviceType: " \(type.uppercased()) ", platform: " \(platform.uppercased()) ", appVersion: "test")
            let request = try XCTUnwrap(SpeechHTTPFixture.requests().last)
            XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
            let body = try JSONDecoder().decode([String: String].self, from: XCTUnwrap(request.httpBody))
            XCTAssertEqual(body["platform"], platform)
            XCTAssertEqual(body["device_type"], type)
            XCTAssertNil(body["capabilities"])
        }
    }

    func testFeedbackStatusReadsSignedCursorWithoutRegistering() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let page = try await client.feedbackUpdates(since: 70, sinceID: 7)
        XCTAssertEqual(page.cursor_id, 10)
        XCTAssertEqual(page.updates.first?.team_note, "Fixture fix")
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.count, 1)
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.url?.query, "since=70&since_id=7")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertNil(request.httpBody)
        XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
    }
    func testOwnerResolutionSendsSignedHistoryWithoutRegistering() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        try await client.sendFeedbackResolution(reportID: 456, historyID: 777, status: "reopened", at: Date(timeIntervalSince1970: 10))
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.map { $0.url!.path }, ["/v1/reports/456/history"])
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(body["actor"] as? String, "owner")
        XCTAssertEqual(body["phone_history_id"] as? Int, 777)
        XCTAssertEqual(body["at"] as? Int, 10000)
        XCTAssertEqual(body["status"] as? String, "reopened")
    }
    func testReportLogKeepsOlderPickedEntryAndDeduplicatesRecentSelection() {
        let recent = (1...50).map {
            HostActivityEntry(id: Int64($0), at: .distantPast, agent: "Fixture", operation: "device.read",
                              status: 200, isSetting: false)
        }.reversed()
        let window = Array(recent)
        let older = HostActivityEntry(id: 0, at: .distantPast, agent: "Fixture", operation: "files",
                                      status: 200, isSetting: false)
        XCTAssertEqual(older.reportLog(includingRecent: window).map(\.id), [0] + window.map(\.id))
        XCTAssertEqual(window[10].reportLog(includingRecent: window).map(\.id), window.map(\.id))
    }

    func testFeedbackRegistrationAndStableReportIdentityOnRetry() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let event = HostActivityEntry(id: 7, at: Date(timeIntervalSince1970: 100), agent: "Bridgit (system)",
                                      operation: "You turned Receive files on", status: 200, isSetting: true)
        for includeLog in [false, true, true] {
            let receipt = try await client.submitFeedback(reportID: includeLog ? 321 : 320, kind: "bug", description: "Fixture",
                                                         noAutofix: true, createdAt: 123, appVersion: "1.2", versionCode: 2026110622, buildNumber: "2026110622", registrationAppVersion: "9.9",
                                                         deviceModel: "Synthetic Model", osVersion: "Synthetic OS", buildRevision: "fixture-revision",
                                                         buildConfiguration: "debug", sourceScreen: "Activity",
                                                         logEntryID: event.id, attachedActivity: includeLog ? [event] : [])
            XCTAssertEqual(receipt, 456)
        }
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.map { $0.url!.path }, ["/v1/install/challenge", "/v1/install/register", "/v1/reports", "/v1/reports", "/v1/reports"])
        let registration = try JSONSerialization.jsonObject(with: XCTUnwrap(requests[1].httpBody)) as! [String: Any]
        XCTAssertEqual(registration["app_version"] as? String, "9.9", "Registration identifies the running app, not the filed report")
        for (index, request) in requests.suffix(3).enumerated() {
            XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
            let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
            let context = try XCTUnwrap(body["context"] as? [String: Any])
            XCTAssertEqual(context["app_version"] as? String, "1.2")
            XCTAssertEqual(context["version_code"] as? Int64, 2026110622)
            XCTAssertEqual(context["app_build"] as? String, "2026110622")
            XCTAssertNil(context["android_version"])
            XCTAssertNil(context["build_revision"])
            XCTAssertNil(context["build_configuration"])
            XCTAssertEqual(context["device_model"] as? String, "Synthetic Model")
            XCTAssertEqual(context["os_version"] as? String, "Synthetic OS")
            XCTAssertEqual(context["git_commit"] as? String, "fixture-revision")
            XCTAssertEqual(context["build_type"] as? String, "debug")
            XCTAssertEqual(context["log_entry_id"] as? Int, 7)
            XCTAssertEqual(context["screen"] as? String, "Activity")
            let log = try XCTUnwrap(body["log"] as? [[String: Any]])
            XCTAssertEqual(log.count, index == 0 ? 0 : 1)
            if index > 0 {
                XCTAssertEqual(log.first?["message"] as? String, event.operation)
                XCTAssertTrue(log.first?["agent"] is NSNull)
                XCTAssertEqual(log.first?["kind"] as? String, "setting")
            }
            XCTAssertEqual(body["phone_report_id"] as? Int, index == 0 ? 320 : 321)
            XCTAssertEqual(body["no_autofix"] as? Bool, true)
        }
    }
    func testFeedbackLegacyContextDoesNotInventMissingBuildOrAppleOS() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        for code: Int64? in [nil, -1, 9_007_199_254_740_992] {
            _ = try await client.submitFeedback(reportID: 123, kind: "bug", description: "Synthetic legacy",
                noAutofix: false, createdAt: 123, appVersion: "1.0", versionCode: code, registrationAppVersion: "9.9")
        }
        for request in SpeechHTTPFixture.requests().suffix(3) {
            let body = try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as! [String: Any]
            let context = try XCTUnwrap(body["context"] as? [String: Any])
            XCTAssertEqual(Set(context.keys), ["app_version"])
            XCTAssertEqual(context["app_version"] as? String, "1.0")
        }
    }

    func testPaidTransportRequiresConfirmedPackageLimits() async throws {
        SpeechHTTPFixture.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        _ = try await client.register(appVersion: "test")
        for (id, expected) in [("spent", "applet_budget"), ("missing", "budget_unconfirmed"), ("mismatch", "budget_unconfirmed"), ("owner-missing", "budget_unconfirmed"), ("agent-missing", "budget_unconfirmed")] {
            let package: [String: Any] = ["id": id, "budget": ["day_usd": 0.2, "month_usd": 3]]
            do {
                _ = try await client.appraise(jpeg: Data([1]), applet: id, package: package)
                XCTFail("Budget preflight allowed appraisal")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.code, expected) }
            do {
                _ = try await client.speech(operation: "hear", payload: ["allow_cloud": true], applet: id, package: package)
                XCTFail("Budget preflight allowed cloud hearing")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.code, expected) }
        }
        let requests = SpeechHTTPFixture.requests().dropFirst(2)
        XCTAssertEqual(requests.count, 10)
        for request in requests {
            XCTAssertEqual(request.url?.path, "/v1/budgets/sync")
            XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
            let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
            let entry = try XCTUnwrap((body["applets"] as? [[String: Any]])?.first)
            let limits = try XCTUnwrap((entry["package_budget"] as? [String: Any])?["limits"] as? [[String: Any]])
            XCTAssertEqual(limits.compactMap { $0["usd"] as? Double }, [0.2, 3])
        }
    }
    func testResolveAndSpeakKeepPackageBudgetContextAndRequireCloudConsent() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        _ = try await client.register(appVersion: "test")
        for package in ["chef-helper", "second-applet"] {
            _ = try await client.speech(operation: "resolve", payload: ["operation": "speak"], applet: package, package: ["id": package])
            _ = try await client.speech(operation: "speak", payload: ["text": "Fixture", "allow_cloud": true], applet: package, package: ["id": package])
        }
        let requests = SpeechHTTPFixture.requests().filter { $0.url!.path.hasPrefix("/v1/speech/") }
        XCTAssertEqual(requests.map { $0.url!.lastPathComponent }, ["resolve", "speak", "resolve", "speak"])
        XCTAssertEqual(requests.map { $0.value(forHTTPHeaderField: "X-Bridgit-Applet") }, ["chef-helper", "chef-helper", "second-applet", "second-applet"])
        for request in requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Bridgit-Action"), "main")
            XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        }
        let before = SpeechHTTPFixture.requests().count
        do {
            _ = try await client.speech(operation: "speak", payload: ["text": "Must stay local"], applet: "chef-helper", package: ["id": "chef-helper"])
            XCTFail("Speech without cloud consent reached transport")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "speech_cloud_consent_required") }
        XCTAssertEqual(SpeechHTTPFixture.requests().count, before)
    }
}

extension InstallSpeechTests {
    func testAgentFeedbackUsesSignedTopLevelAttributionAndTypedHistory() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let agent = Data(#"{"id":"agent-fixture","name":"Reporter"}"#.utf8)
        let recorded = HostActivityEntry(id: 55, at: Date(timeIntervalSince1970: 2), agent: "Bridgit",
                                         operation: "Fixture transport stopped", status: 500, isSetting: false, kind: .service)
        let receipt = try await client.submitFeedback(reportID: 93, kind: "feature", description: "Synthetic feature",
            noAutofix: true, createdAt: 2000, appVersion: "1.2", attachedActivity: [recorded], filingAgentContext: agent)
        XCTAssertEqual(receipt, 456)
        try await client.sendFeedbackHistory(reportID: receipt, historyID: 778, status: "seen",
            at: Date(timeIntervalSince1970: 20), kind: "comment", actor: .reporter, author: "Reporter", note: "Follow-up")
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.compactMap { $0.url?.path }, ["/v1/install/challenge", "/v1/install/register", "/v1/reports", "/v1/reports/456/history"])
        for request in requests.suffix(2) { XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature")) }
        let report = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[2].httpBody)) as? [String: Any])
        XCTAssertEqual(report["capture_source"] as? String, "agent")
        XCTAssertEqual((report["agent"] as? [String: String])?["id"], "agent-fixture")
        XCTAssertNil((report["context"] as? [String: Any])?["agent"])
        let log = try XCTUnwrap((report["log"] as? [[String: Any]])?.first)
        XCTAssertEqual(log["kind"] as? String, "service")
        XCTAssertEqual(log["channel"] as? String, "diagnostic")
        XCTAssertTrue(log["http"] is NSNull)
        let history = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[3].httpBody)) as? [String: Any])
        XCTAssertEqual(history["phone_history_id"] as? Int, 778)
        XCTAssertEqual(history["status"] as? String, "seen")
        XCTAssertEqual(history["kind"] as? String, "comment")
        XCTAssertEqual(history["actor"] as? String, "reporter")
        XCTAssertEqual(history["by"] as? String, "Reporter")
        XCTAssertEqual(history["note"] as? String, "Follow-up")
    }
}


extension InstallSpeechTests {
    func testFeedbackUploadChecksPrivacyAfterRegistrationBeforeSendingReport() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        do {
            _ = try await client.submitFeedback(reportID: 94, kind: "bug", description: "Not uploaded",
                noAutofix: true, createdAt: 2000, appVersion: "1.2", authorize: {
                    let paths = SpeechHTTPFixture.requests().compactMap { $0.url?.path }
                    if paths.isEmpty { return }
                    XCTAssertEqual(paths, ["/v1/install/challenge", "/v1/install/register"])
                    throw DeviceAPIError(403, "feedback_upload_disabled", "Fixture turned uploads off during registration.")
                })
            XCTFail("Upload off must refuse the report after registration")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "feedback_upload_disabled") }
        XCTAssertEqual(SpeechHTTPFixture.requests().compactMap { $0.url?.path }, ["/v1/install/challenge", "/v1/install/register"])
    }

    func testFeedbackTeamCredentialMintAndRevokeUseSignedDeviceIdentity() async throws {
        SpeechHTTPFixture.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SpeechHTTPFixture.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!, http: session)
        let teamKey = try await client.mintFeedbackTeamKey(agentID: "local-agent-fixture", name: "Fixture", fixer: true,
                                                          appVersion: "1.2", authorize: {})
        XCTAssertEqual(teamKey.agentID, "offline-team-agent")
        XCTAssertEqual(teamKey.docs, "https://offline.invalid/v1/agent")
        try await client.revokeFeedbackTeamKey(agentID: "local-agent-fixture")
        let requests = SpeechHTTPFixture.requests()
        XCTAssertEqual(requests.compactMap { $0.url?.path }, ["/v1/install/challenge", "/v1/install/register", "/v1/agent-keys", "/v1/agent-keys/revoke"])
        for request in requests.suffix(2) { XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Bridgit-Signature")) }
        let mint = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[2].httpBody)) as? [String: Any])
        XCTAssertEqual(mint["phone_agent_id"] as? String, "local-agent-fixture")
        XCTAssertEqual(mint["developer"] as? Bool, true)
        let revoke = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[3].httpBody)) as? [String: Any])
        XCTAssertEqual(revoke["phone_agent_id"] as? String, "local-agent-fixture")
    }
}
