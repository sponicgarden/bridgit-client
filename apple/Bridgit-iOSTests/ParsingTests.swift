import XCTest
import Combine
@testable import Bridgit_iOS
import BridgitCore

final class ParsingTests: XCTestCase {
    @MainActor func testLinkedAccountOSUsesReportedPlatformAndClearsOmittedVersion() throws {
        let model = AppModel(loadStored: false)
        func phone(_ json: String) throws -> Phone {
            try JSONDecoder().decode(Phone.self, from: Data(json.utf8))
        }
        let first = try phone("""
            {"phone":"fixture","name":"Kitchen tablet","platform":"ios","device_type":"phone",
             "status":{"device":{"platform":" ","os_version":" 26.2 "}}}
            """)
        let initial = try XCTUnwrap(PortalDevice.list(phones: [first], pairings: []).first)
        let live = PhoneLive(device: initial, model: model)
        XCTAssertEqual(live.facts.operatingSystem?.label, "iOS")
        XCTAssertEqual(live.facts.operatingSystem?.summary, "iOS 26.2")

        let legacy = try phone("""
            {"phone":"fixture","name":"Mac-shaped nickname","status":{"device":{"android":"15"}}}
            """)
        live.update(try XCTUnwrap(PortalDevice.list(phones: [legacy], pairings: []).first))
        XCTAssertEqual(live.facts.operatingSystem?.summary, "Android 15")

        let missing = try phone("""
            {"phone":"fixture","name":"Spare Mac","platform":"macos","device_type":"computer"}
            """)
        live.update(try XCTUnwrap(PortalDevice.list(phones: [missing], pairings: []).first))
        XCTAssertNil(live.facts.operatingSystem)
    }

    @MainActor func testLiveOSUsesOnlyReportedSystemAndClearsWhenDeviceFieldsAreOmitted() throws {
        let model = AppModel(loadStored: false)
        let live = PhoneLive(device: .init(id: "fixture", name: "Mac-looking nickname", cloud: nil, pairing: nil), model: model)
        live.applyPortalHome(["device": ["platform": "macos", "device_type": "computer", "os_version": "26.1"]])
        XCTAssertEqual(live.facts.operatingSystem?.summary, "macOS 26.1")
        live.applyPortalHome(["device": ["platform": "ios", "device_type": "tablet", "os_version": "26.3"]])
        XCTAssertEqual(live.facts.operatingSystem?.summary, "iPadOS 26.3")
        live.applyPortalHome(["device": ["platform": "future_os", "name": "Android phone", "os_version": "7.4"]])
        XCTAssertEqual(live.facts.operatingSystem?.summary, "OS version 7.4")
        live.applyPortalHome(["device": ["platform": "macos", "android": "15"]])
        XCTAssertNil(live.facts.operatingSystem, "A conflicting legacy Android field is not a Mac OS version.")
        live.applyPortalHome(["device": ["platform": "macos", "os_version": "26.1"]])
        live.applyPortalHome(["features": []])
        XCTAssertNil(live.facts.operatingSystem)
    }

    func testCloudActivityCopyDoesNotInventUnreportedTelemetry() throws {
        let event = try JSONDecoder().decode(WatchEvent.self, from: Data(
            #"{"id":19,"ts":1700000000000,"caption":"Synthetic event","source":"account"}"#.utf8))
        let entry = LogLine(event: event)
        let copied = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(entry.reportedJSONString().utf8)) as? [String: Any])
        XCTAssertEqual(Set(copied.keys), Set(["id", "ts", "caption", "source"]))
        XCTAssertEqual(entry.reportedText(for: "message"), "Synthetic event")
        XCTAssertEqual(entry.reportedText(for: "http"), "null")
        XCTAssertEqual(entry.reportedText(for: "params"), "null")
        XCTAssertEqual(entry.sceneJSON["kind"] as? String, "request")
        XCTAssertEqual(entry.sceneJSON["result"] as? String, "ok")
        XCTAssertEqual(entry.sceneJSON["channel"] as? String, "audit")
        XCTAssertFalse(entry.failed)
    }

    func testOlderActivityPreservesRowsBeyondLiveWindowAndDeduplicates() {
        let recent = (101...2100).map { LogLine(raw: ["id": $0]) }
        let older = (1...101).map { LogLine(raw: ["id": $0]) }
        let combined = PhoneLive.mergingLogs(recent, page: older, limit: nil)
        XCTAssertEqual(combined.count, 2100)
        XCTAssertEqual(combined.first?.id, 2100)
        XCTAssertEqual(combined.last?.id, 1)
        XCTAssertEqual(Set(combined.map(\.id)).count, combined.count)
        let refreshed = PhoneLive.mergingLiveLogs(combined, page: [LogLine(raw: ["id": 2101])], retainingLoadedHistory: true)
        XCTAssertEqual(refreshed.count, 2101)
        XCTAssertEqual(refreshed.last?.id, 1)
        XCTAssertEqual(refreshed.first?.id, 2101)
        let passive = PhoneLive.mergingLiveLogs(recent, page: [LogLine(raw: ["id": 2101])], retainingLoadedHistory: false)
        XCTAssertEqual(passive.count, 2000)
        XCTAssertEqual(passive.last?.id, 102)
        XCTAssertEqual(PhoneLive.mergingLogs(recent, page: [LogLine(raw: ["id": 2101])], limit: 2000).count, 2000)
    }

    @MainActor func testOlderActivityUsesStandardLogFallbackWithCursor() async throws {
        var calls: [(String, [String: String])] = []
        let response = try await PhoneLive.logPage(query: ["until": "42", "limit": "100"]) { path, query in
            calls.append((path, query))
            if path == "/v1/portal/logs" { throw RelayError.failed(404, "missing") }
            return ["entries": [["id": 41, "ts": "2023-11-14T22:13:20Z"]]]
        }
        XCTAssertEqual(calls.map { $0.0 }, ["/v1/portal/logs", "/v1/log"])
        XCTAssertEqual(calls[1].1, ["until": "42", "limit": "100"])
        XCTAssertEqual(PhoneLive.entries(response).map(\.id), [41])
        calls = []
        do {
            _ = try await PhoneLive.logPage(query: ["until": "42"]) { path, query in
                calls.append((path, query)); throw RelayError.offline
            }
            XCTFail("An offline read must fail, not become empty history")
        } catch { XCTAssertEqual(calls.count, 1) }
    }

    func testPortalLogCursorsAdvanceAcrossHiddenRows() {
        let page: [String: Any] = ["entries": [], "next_since": 2300, "oldest_id": 1200, "has_older": true]
        XCTAssertTrue(PhoneLive.entries(page).isEmpty)
        XCTAssertEqual(PhoneLive.logCursor(page, key: "next_since"), 2300)
        XCTAssertEqual(PhoneLive.logCursor(page, key: "oldest_id"), 1200)
        XCTAssertEqual(PhoneLive.olderLogCursor(page, current: 1500), 1200)
        XCTAssertEqual(PhoneLive.olderLogCursor(page, current: 900), 900)
        XCTAssertEqual(PhoneLive.olderLogCursor(["entries": [["id": 700]]], current: 900), 700)
        XCTAssertEqual(PhoneLive.olderLogCursor(["entries": []], current: 700), 700)
        for invalid: Any in [true, -1, 1.5, NSNull(), "42"] {
            XCTAssertNil(PhoneLive.logCursor(["oldest_id": invalid], key: "oldest_id"))
        }
        XCTAssertNil(PhoneLive.logCursor(["entries": []], key: "oldest_id"))
    }

    func testLinkedActivityDetailsKeepReportedJSONWithoutSceneDefaults() throws {
        let entry = LogLine(raw: ["id": 17, "path": "/v1/camera", "status": 403,
                                  "params": ["nested": ["enabled": true]], "data": NSNull()])
        let copied = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(entry.reportedJSONString().utf8)) as? [String: Any])
        XCTAssertEqual(copied["path"] as? String, "/v1/camera")
        XCTAssertEqual(copied["status"] as? Int, 403)
        XCTAssertNil(copied["ts"])
        XCTAssertNil(copied["channel"])
        XCTAssertNil(copied["http"])
        XCTAssertEqual(entry.reportedText(for: "http"), "403")
        XCTAssertEqual(entry.reportedText(for: "endpoint"), "/v1/camera")
        XCTAssertEqual(entry.reportedText(for: "request_id"), "null")
        XCTAssertEqual(entry.reportedText(for: "params"), "{\"nested\":{\"enabled\":true}}")
        XCTAssertTrue(copied["data"] is NSNull)
        XCTAssertEqual(entry.sceneJSON["http"] as? Int, 403)
    }

    func testActivityParsesAndroidISOAndPortalMillisecondTimes() {
        XCTAssertEqual(LogLine(raw: ["ts": "2023-11-14T22:13:20.123Z"]).ts, 1700000000123, accuracy: 0.01)
        XCTAssertEqual(LogLine(raw: ["ts": "2023-11-14T23:13:20+01:00"]).ts, 1700000000000, accuracy: 0.01)
        XCTAssertEqual(LogLine(raw: ["ts": 1700000000123.0]).ts, 1700000000123)
        XCTAssertEqual(LogLine(raw: ["ts": "2023-11-14T22:13:20Z"]).sceneJSON["ts"] as? Double, 1700000000000)
    }

    func testActivityDoesNotInventTimeForMissingOrMalformedTimestamps() {
        for payload: [String: Any] in [[:], ["ts": "not a time"], ["ts": Double.infinity], ["ts": NSNull()], ["ts": true], ["ts": false]] {
            let line = LogLine(raw: payload)
            XCTAssertNil(line.reportedAt)
            XCTAssertNil(line.sceneJSON["ts"])
        }
        XCTAssertEqual(LogLine(raw: ["ts": 0.0]).reportedAt, Date(timeIntervalSince1970: 0))
    }

    func testActivityScenePreservesNestedPayloadAndDiagnosticChannel() throws {
        let payload: [String: Any] = [
            "id": 7, "ts": "2023-11-14T22:13:20Z", "channel": "diagnostic", "level": "warn",
            "params": ["setting": ["enabled": true], "values": [1, 2]],
            "data": ["reason": NSNull(), "changes": [["before": false, "after": true]]],
            "extra": ["nested": ["value": "preserved"]]
        ]
        let scene = LogLine(raw: payload).sceneJSON
        XCTAssertEqual(scene["channel"] as? String, "diagnostic")
        XCTAssertEqual(scene["level"] as? String, "warn")
        for field in ["params", "data", "extra"] {
            XCTAssertEqual(try JSONSerialization.data(withJSONObject: XCTUnwrap(scene[field]), options: .sortedKeys),
                           try JSONSerialization.data(withJSONObject: XCTUnwrap(payload[field]), options: .sortedKeys), field)
        }
        XCTAssertEqual(scene["ts"] as? Double, 1700000000000)
        XCTAssertTrue(JSONSerialization.isValidJSONObject(scene))
        XCTAssertEqual(Set([LogLine(raw: payload), LogLine(raw: payload)]).count, 1)
    }

    func testActivityRecognizesAndroidErrorLevelWithoutHTTPFailure() {
        let error = LogLine(raw: ["id": 1, "level": "error", "http": 200, "message": "Synthetic service failure"])
        XCTAssertTrue(error.failed)
        XCTAssertEqual(error.sceneJSON["level"] as? String, "error")
        XCTAssertFalse(LogLine(raw: ["level": "warn", "http": 200]).failed)
        XCTAssertTrue(LogLine(raw: ["http": 403]).failed)
        XCTAssertTrue(LogLine(raw: ["result": "denied"]).failed)
        XCTAssertFalse(LogLine(raw: ["http": 200, "result": "ok"]).failed)
        let sampleFailure = SampleData.events.map(LogLine.init(event:)).filter { $0.http == 403 }
        XCTAssertEqual(sampleFailure.count, 1)
        XCTAssertTrue(sampleFailure.allSatisfy(\.failed))
    }

    func testExplicitLocalDeviceSelectionNeverFallsBackToLinkedDevice() {
        let devices = PortalDevice.list(phones: SampleData.phones, pairings: SampleData.pairings)
        XCTAssertFalse(devices.isEmpty)
        XCTAssertNil(PortalDevice.current(in: devices, selectedID: PortalDevice.localID, attached: { _ in true }))
        XCTAssertNil(PortalDevice.current(in: devices, selectedID: nil, attached: { _ in false }))
        XCTAssertNil(PortalDevice.current(in: devices, selectedID: "missing", attached: { _ in true }))
        XCTAssertNil(PortalDevice.current(in: devices, selectedID: nil, attached: { _ in true }))
        XCTAssertEqual(PortalDevice.current(in: devices, selectedID: devices[0].id, attached: { _ in false })?.id, devices[0].id)
        XCTAssertNil(PortalDevice.current(in: [], selectedID: nil, attached: { _ in false }))
    }

    func testMainTabTitlesMatchAndroid3347() {
        XCTAssertEqual(Tab.allCases.map(\.title), ["Home", "Devices", "Camera", "Agents", "More"])
    }
    @MainActor func testLocalDeviceLinkDoesNotAddOrSignIn() {
        let model = AppModel()
        let mode = model.mode
        let pairings = model.relays.pairings
        model.handle(url: URL(string: "bridgit://device")!)
        XCTAssertTrue(model.showThisDevice)
        XCTAssertEqual(model.mode, mode)
        XCTAssertEqual(model.relays.pairings, pairings)
    }
    func testNewSignInSchemeAndRouteBoundaries() throws {
        XCTAssertNotNil(SignIn.parse(URL(string: "https://bridgit.cc/app-signin/complete/ios#state=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&session=bws_abcdefghijklmnopqrstuvwxyz012345")!))
        for route in ["pair", "satellite", "signin/extra", "evil@signin", "signin:22"] {
            XCTAssertNil(SignIn.parse(URL(string: "bridgit://" + route + "#state=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&session=bws_abcdefghijklmnopqrstuvwxyz012345")!))
        }
    }

    func testPairLink() throws {
        let url = URL(string: "bridgit://pair#v=1&relay=https%3A%2F%2Fbridgit-relay.bridgit-app.workers.dev&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=Kitchen%20phone&install=bgi_x&portal=1")!
        let p = try XCTUnwrap(Pairing.parse(link: url))
        XCTAssertEqual(p.base.absoluteString, "https://bridgit-relay.bridgit-app.workers.dev/d/dev_ABCDEFGH12")
        XCTAssertEqual(p.name, "Kitchen phone")
        XCTAssertEqual(p.install, "bgi_x")
        XCTAssertTrue(p.portal)
    }

    func testPairLinkRejectsHttpAndBadToken() {
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgit://pair#relay=http%3A%2F%2Fevil.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123")!))
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgit://pair#relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=secret")!))
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgit://pair#relay=https%3A%2F%2Fr.example&device=x&token=rly_0123456789abcdef0123")!))
    }

    func testRelaySetupText() throws {
        let text = """
        Use this phone through the relay. POST …/jobs?wait=30
        {"base":"https://bridgit-relay.bridgit-app.workers.dev/d/dev_ABCDEFGH12","device_id":"dev_ABCDEFGH12","mode":"connector","token":"rly_0123456789abcdef0123","agent_id":"a1","generation":1}
        """
        let p = try XCTUnwrap(Pairing.parse(text))
        XCTAssertEqual(p.base.absoluteString, "https://bridgit-relay.bridgit-app.workers.dev/d/dev_ABCDEFGH12")
        XCTAssertNil(Pairing.parse(text.replacingOccurrences(of: "connector", with: "sealed")))
    }

    func testSignInLink() throws {
        XCTAssertNil(SignIn.parse(URL(string: "bridgitportal://signin#state=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&session=bws_abcdefghijklmnopqrstuvwxyz012345")!))
        let r = try XCTUnwrap(SignIn.parse(URL(string: "https://bridgit.cc/app-signin/complete/ios#state=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&session=bws_abcdefghijklmnopqrstuvwxyz012345&email=a%40b.c")!))
        XCTAssertEqual(r.email, "a@b.c")
        XCTAssertNil(SignIn.parse(URL(string: "https://bridgit.cc/app-signin/complete/ios#session=nope")!))
    }

    func testRelayCompletionDecodesV1AndErrors() throws {
        let body = Data("{\"ok\":true}".utf8).base64EncodedString()
        let ok = try RelayClient.decodeCompletion(status: 200, data: Data("{\"job_id\":\"j\",\"response\":{\"status\":200,\"body_base64\":\"\(body)\"}}".utf8))
        XCTAssertEqual(String(data: ok.body, encoding: .utf8), "{\"ok\":true}")
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 429, data: Data("{\"error\":\"rate_limited\"}".utf8))) {
            guard case RelayError.quota = $0 else { return XCTFail("\($0)") }
        }
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 503, data: Data("{\"error\":\"phone_unreachable\"}".utf8))) {
            guard case RelayError.offline = $0 else { return XCTFail("\($0)") }
        }
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 200, data: Data("{\"response\":{\"status\":404,\"body\":{\"error\":\"not_found\"}}}".utf8))) {
            guard case RelayError.failed(404, _) = $0 else { return XCTFail("\($0)") }
        }
    }

    func testLanParsing() {
        let obj: [String: Any] = ["devices": [["id": "d1", "name": "Cam", "type_label": "Camera", "connected": true, "services": [["id": "rtsp", "label": "RTSP"]]],
                                              ["id": "d2", "name": "TV", "connected": false]],
                                  "categories": [["id": "camera", "title": "Cameras", "count": 2, "connected_count": 1, "device_ids": ["d2", "d1"]]]]
        let cats = PhoneLive.parseLan(obj)
        XCTAssertEqual(cats.count, 1)
        XCTAssertEqual(cats[0].devices.map(\.id), ["d1", "d2"]) // connected first
        XCTAssertEqual(cats[0].devices[0].services, ["RTSP"])
    }

    func testPairLinkWithClaim() throws {
        let base = "bridgit://pair#v=1&relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=P"
        let claim = try XCTUnwrap(Pairing.parse(link: URL(string: base + "&claim=1")!))
        XCTAssertEqual(claim.needsClaim, true)
        XCTAssertEqual(claim.token, "rly_0123456789abcdef0123")
        XCTAssertNil(try XCTUnwrap(Pairing.parse(link: URL(string: base)!)).needsClaim)
        XCTAssertNil(try XCTUnwrap(Pairing.parse(link: URL(string: base + "&claim=0")!)).needsClaim)
        // The copied relay-setup JSON never needs a claim.
        XCTAssertNil(try XCTUnwrap(Pairing.parse("{\"base\":\"https://r.example\",\"device_id\":\"dev_ABCDEFGH12\",\"token\":\"rly_0123456789abcdef0123\"}")).needsClaim)
        // Pairings saved before this field existed still decode.
        let old = Data("[{\"base\":\"https://r.example/d/dev_ABCDEFGH12\",\"deviceId\":\"dev_ABCDEFGH12\",\"token\":\"rly_0123456789abcdef0123\",\"name\":\"P\",\"portal\":false,\"addedAt\":0}]".utf8)
        XCTAssertNil(try JSONDecoder().decode([Pairing].self, from: old)[0].needsClaim)
    }

    func testClaimTokenFormat() {
        let a = PairingClaim.newToken(), b = PairingClaim.newToken()
        XCTAssertEqual(a.count, 47) // "rly_" + 43
        XCTAssertTrue(a.hasPrefix("rly_"))
        XCTAssertTrue(Pairing.validToken(a))
        XCTAssertNil(a.dropFirst(4).first { !($0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")) })
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(PairingClaim.base64URL(Data([0xfb, 0xff, 0xfe])), "-__-")
    }

    func testClaimTokenHash() {
        XCTAssertEqual(PairingClaim.tokenHash("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(PairingClaim.tokenHash("rly_x").count, 64)
    }

    func testClaimReplies() throws {
        XCTAssertNoThrow(try PairingClaim.checkClaimed(RelayResponse(status: 200, body: Data("{\"claimed\":true}".utf8))))
        XCTAssertThrowsError(try PairingClaim.checkClaimed(RelayResponse(status: 200, body: Data("{}".utf8))))
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 200, data: Data("{\"response\":{\"status\":409,\"body\":{\"error\":\"already_claimed\"}}}".utf8))) {
            guard case RelayError.failed(409, "already_claimed") = $0 else { return XCTFail("\($0)") }
        }
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 200, data: Data("{\"response\":{\"status\":410,\"body\":{\"error\":\"link_expired\"}}}".utf8))) {
            guard case RelayError.failed(410, _) = $0 else { return XCTFail("\($0)") }
        }
    }

    /// GET /v1/portal/devices as lan/DiscoveryResult.kt builds it: brand is an object or null, categories carry brands.
    static let portalDevices: [String: Any] = [
        "total_devices": 3, "connected_devices": 2, "unconnected_devices": 1, "total_categories": 2,
        "categories": [
            ["id": "cameras", "title": "Cameras", "connected_count": 1, "unconnected_count": 1, "count": 2, "expanded_by_default": true,
             "device_ids": ["192.168.1.32", "192.168.1.31"],
             "brands": [["id": "tapo", "name": "Tapo", "logo_url": "/v1/portal/brands/tapo/logo?raw=1", "mime": "image/svg+xml", "background": "light",
                         "source": "https://static.tapo.com/res/new-home/tapo.svg", "sha256": "1fb9", "count": 1, "suggested": true]]],
            ["id": "audio", "title": "Audio", "connected_count": 1, "unconnected_count": 0, "count": 1, "expanded_by_default": true,
             "device_ids": ["192.168.1.41"], "brands": [Any]()],
        ],
        "devices": [
            ["id": "192.168.1.31", "host": "192.168.1.31", "name": "Tapo C210", "type": "camera", "type_label": "Camera", "type_suggested": true,
             "category": "cameras", "model": "C210", "model_identifier": "C210", "identifier": "", "advertised_name": "Tapo_C210_ABCD",
             "services": [["id": "_onvif._tcp", "label": "ONVIF"]],
             "brand": ["id": "tapo", "name": "Tapo", "logo_url": "/v1/portal/brands/tapo/logo?raw=1", "mime": "image/svg+xml", "background": "light",
                       "source": "https://static.tapo.com/res/new-home/tapo.svg", "sha256": "1fb9"],
             "brand_suggested": true, "bridgit_interfaced": true,
             "interface": ["adapter": "tapo", "label": "Tapo camera", "setup_required": true, "verified": false, "enrollment": "/v1/lan/devices/enroll"],
             "connected": true, "connection_state": "observed", "last_seen": 1_762_000_000_000 as Int64, "verified": false, "enrolled": false,
             "control_status": "not_checked"],
            ["id": "192.168.1.32", "host": "192.168.1.32", "name": "Camera", "type": "camera", "type_label": "Camera", "category": "cameras",
             "model": "", "identifier": "", "services": [Any](), "brand": NSNull(), "bridgit_interfaced": false, "interface": NSNull(),
             "connected": false, "last_seen": 0],
            // An older phone sent the maker as a plain name.
            ["id": "192.168.1.41", "host": "192.168.1.41", "name": "WiiM Mini", "type": "speaker", "type_label": "Speaker", "category": "audio",
             "brand": "WiiM", "connected": true, "last_seen": 1_762_000_000_000 as Int64],
        ],
        "added": [
            ["id": "lan:door", "name": "Front door", "host": "192.168.1.31", "kind": "camera", "state": "on", "applets": ["Pet monitor"],
             "model": "C210", "verified_at": 1_762_000_000_000 as Int64,
             "checks": ["audio_in": "available", "audio_out": "unavailable", "tapo_talk": "not_checked"]],
            ["id": "net:plug", "name": "Desk plug", "host": "192.168.1.70", "kind": "smart", "state": "blocked", "applets": [Any](), "adapter": "shelly"],
        ],
    ]

    func testPortalDevicesBrandsAndCategories() throws {
        let cats = PhoneLive.parseLan(Self.portalDevices)
        XCTAssertEqual(cats.map(\.id), ["cameras", "audio"])
        XCTAssertEqual(cats[0].brands, [LanBrand(id: "tapo", name: "Tapo")])
        XCTAssertEqual(cats[0].devices.map(\.id), ["192.168.1.31", "192.168.1.32"]) // linkable and connected first
        let tapo = cats[0].devices[0]
        XCTAssertEqual(tapo.brand, LanBrand(id: "tapo", name: "Tapo"))
        XCTAssertEqual(tapo.kind, "camera")
        XCTAssertEqual(tapo.category, "cameras")
        XCTAssertEqual(tapo.lastSeen, 1_762_000_000_000)
        XCTAssertNil(tapo.identifier) // empty strings are dropped
        XCTAssertEqual(tapo.interfaceLabel, "Tapo camera")
        XCTAssertNil(cats[0].devices[1].brand) // null brand
        XCTAssertEqual(cats[1].devices[0].brand, LanBrand(id: "wiim", name: "WiiM")) // older phone: a plain name
    }

    @MainActor func testPortalDevicesAdded() throws {
        let added = try XCTUnwrap(PhoneLive.parseAdded(Self.portalDevices))
        XCTAssertEqual(added.map(\.id), ["lan:door", "net:plug"])
        XCTAssertEqual(added[0].applets, ["Pet monitor"])
        XCTAssertEqual(added[0].checks["audio_out"], "unavailable")
        XCTAssertEqual(added[0].verifiedAt, 1_762_000_000_000)
        XCTAssertEqual(added[1].state, "blocked")
        XCTAssertEqual(added[1].adapter, "shelly")
        XCTAssertNil(PhoneLive.parseAdded(["devices": [Any]()])) // older phones send no `added`
        XCTAssertEqual(AddedCard(device: added[0]).line,
                       "192.168.1.31 · Included in: Pet monitor · Microphone: available · ONVIF speaker: unavailable · Tapo talk: not_checked. A discovered track does not prove audible pickup or output.")
        XCTAssertEqual(AddedCard(device: added[1]).line, "192.168.1.70 · Not added to any applets")
    }

    /// The map names the phone it shows under the mark (never "This phone" on the portal).
    func testMapNamesTheShownPhone() {
        XCTAssertEqual(DevicesMapOptions.build(categories: [], added: [], core: "A9 Cream Tab")["core"] as? String, "A9 Cream Tab")
        XCTAssertNil(DevicesMapOptions.build(categories: [], added: [])["core"])
    }

    /// The map's options mirror DevicesScene.options(): added first and linked, observed hosts that are added left out.
    func testMapOptions() throws {
        let cats = PhoneLive.parseLan(Self.portalDevices)
        let added = try XCTUnwrap(PhoneLive.parseAdded(Self.portalDevices))
        let now = Date(timeIntervalSince1970: 1_762_000_000 + 600)
        let o = DevicesMapOptions.build(categories: cats, added: added, now: now)
        let devices = try XCTUnwrap(o["devices"] as? [[String: Any]])
        XCTAssertEqual(devices.map { $0["id"] as? String }, ["lan:door", "net:plug", "seen:192.168.1.41", "seen:192.168.1.32"])
        XCTAssertEqual(devices[0]["group"] as? String, "CAMERAS")
        XCTAssertEqual(devices[0]["linked"] as? Bool, true)
        XCTAssertEqual(devices[0]["sub"] as? String, "192.168.1.31")
        XCTAssertEqual(devices[1]["group"] as? String, "SMART")
        XCTAssertEqual(devices[1]["logo"] as? String, "../device-brands/shelly.svg") // matched from the adapter, as DevicesScene.brandOf
        XCTAssertEqual(devices[2]["group"] as? String, "AUDIO")
        XCTAssertEqual(devices[2]["state"] as? String, "seen")
        XCTAssertEqual(devices[2]["kind"] as? String, "speaker")
        XCTAssertEqual(devices[2]["logo"] as? String, "../device-brands/wiim.svg")
        XCTAssertEqual(devices[3]["state"] as? String, "off")
        XCTAssertEqual(devices[3]["sub"] as? String, "Not yet seen")
        XCTAssertEqual((o["groups"] as? [[String: String]])?.map { $0["id"]! },
                       ["COMPUTERS", "NETWORK", "CAMERAS", "AUDIO", "MEDIA", "PRINTERS", "SMART", "MOBILE", "OTHER"])
        let counts = try XCTUnwrap(o["counts"] as? [String: Int])
        XCTAssertEqual(counts, ["total": 4, "added": 2, "unchecked": 1, "seen": 1, "offline": 1, "online": 2])
        XCTAssertEqual(o["more"] as? Int, 0)

        // During Level 2: only what the scan saw, each drawn as seen with its address.
        let scan = DevicesMapOptions.build(categories: cats, added: [], found: ["192.168.1.32"], now: now)
        let found = try XCTUnwrap(scan["devices"] as? [[String: Any]])
        XCTAssertEqual(found.map { $0["id"] as? String }, ["seen:192.168.1.32"])
        XCTAssertEqual(found[0]["state"] as? String, "seen")
        XCTAssertEqual(found[0]["sub"] as? String, "192.168.1.32")
    }

    func testNetworkAddress() {
        XCTAssertEqual(PhoneLive.parseNetwork(["network": ["address": "192.168.1.23", "prefix_length": 24]]), "192.168.1.23/24")
        XCTAssertEqual(PhoneLive.parseNetwork(["network": ["address": "10.0.0.5"]]), "10.0.0.5")
        XCTAssertNil(PhoneLive.parseNetwork(["categories": []]))
    }

    /// An account-only phone attached before live access keeps its attachment once the relay pairing arrives.
    @MainActor func testAttachmentSurvivesLiveAccess() {
        let phone = SampleData.phones[1]
        let cloudOnly = PortalDevice(id: "cloud:" + phone.phone, name: phone.displayName, cloud: phone, pairing: nil)
        let a = AppModel.Attachment(cloudOnly)
        let pairing = Pairing(base: URL(string: "https://r.example/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12",
                              token: "rly_0123456789abcdef0123", name: "Garage", install: phone.phone)
        let live = PortalDevice.list(phones: [phone], pairings: [pairing])[0]
        XCTAssertEqual(live.id, "relay:dev_ABCDEFGH12")
        XCTAssertEqual(PortalDevice.current(in: [live], selectedID: cloudOnly.id, attached: { _ in false })?.id, live.id)
        XCTAssertTrue(a.matches(live))
        XCTAssertFalse(a.matches(PortalDevice(id: "cloud:other", name: "x", cloud: SampleData.phones[0], pairing: nil)))
    }
}


@MainActor
final class AccountInventoryRefreshTests: XCTestCase {
    private func phone(_ devices: Any? = nil) throws -> Phone {
        var value: [String: Any] = ["phone": "inventory-fixture", "name": "Garage", "last_push_at": 2000]
        if let devices { value["devices"] = devices }
        return try JSONDecoder().decode(Phone.self, from: JSONSerialization.data(withJSONObject: value))
    }
    private func device(_ phone: Phone, pairing: Pairing? = nil) throws -> PortalDevice {
        try XCTUnwrap(PortalDevice.list(phones: [phone], pairings: pairing.map { [$0] } ?? []).first)
    }
    private let rows: [[String: Any]] = [
        ["id": "lan:up", "name": "Up", "type": "camera", "online": true, "connection_checked_at": 1000, "last_connected_at": 1000],
        ["id": "lan:down", "name": "Down", "type": "camera", "online": false, "connection_checked_at": 1500, "last_connected_at": 500],
        ["id": "lan:unknown", "name": "Unknown", "type": "camera", "online": NSNull(), "connection_checked_at": 2000, "last_connected_at": 250]
    ]

    func testSameInstallAccountRefreshPublishesAuthoritativeEmptyInventoryAndMissingStatus() throws {
        let model = AppModel(loadStored: false)
        let live = model.live(for: try device(phone(rows)))
        XCTAssertEqual(PhoneLive.devices(in: live.categories).count, 3)
        XCTAssertTrue(live.inventoryReported)
        var changes = 0
        let observation = live.objectWillChange.sink { changes += 1 }
        let refreshed = model.live(for: try device(phone([] as [Any])))
        XCTAssertTrue(refreshed === live)
        XCTAssertGreaterThan(changes, 0)
        XCTAssertEqual(live.categories, [])
        XCTAssertTrue(live.inventoryReported)
        XCTAssertNil(live.lanAt, "Account contact is not a network observation timestamp")
        XCTAssertTrue(live.inventoryDescription.contains("last account contact"))
        withExtendedLifetime(observation) {}
    }

    func testOmittedInventoryBecomesUnreportedInsteadOfCarryingFreshRows() throws {
        let model = AppModel(loadStored: false)
        let live = model.live(for: try device(phone(rows)))
        _ = model.live(for: try device(phone()))
        XCTAssertEqual(live.categories, [])
        XCTAssertFalse(live.inventoryReported)
        XCTAssertEqual(live.inventorySource, .none)
        XCTAssertEqual(live.inventoryDescription, "Network inventory not reported")
    }

    func testUnprobedCameraIsNeitherOfflineNorConnectedAndKeepsActualLastConnection() throws {
        let model = AppModel(loadStored: false)
        let live = model.live(for: try device(phone(rows)))
        let devices = PhoneLive.devices(in: live.categories)
        XCTAssertEqual(ListMode.connected.visible(devices).map(\.id), ["lan:up"])
        XCTAssertEqual(ListMode.unconnected.visible(devices).map(\.id), ["lan:down"])
        let unknown = try XCTUnwrap(devices.first { $0.id == "lan:unknown" })
        XCTAssertFalse(unknown.connectionReported)
        XCTAssertFalse(unknown.disconnected)
        XCTAssertEqual(unknown.connectionDescription, "Not checked")
        XCTAssertEqual(unknown.lastSeen, 250)
        XCTAssertEqual(unknown.lastSeenLabel, "Last connected")
        XCTAssertEqual(devices.first?.connectionDescription, "Last known connected")
        let options = DevicesMapOptions.build(categories: live.categories, added: [])
        let counts = try XCTUnwrap(options["counts"] as? [String: Int])
        XCTAssertEqual(counts["offline"], 1)
        XCTAssertEqual(counts["seen"], 1)
        XCTAssertEqual(counts["unchecked"], 1)
        let mapRows = try XCTUnwrap(options["devices"] as? [[String: Any]])
        XCTAssertEqual(mapRows.first { ($0["id"] as? String) == "seen:lan:unknown" }?["state"] as? String, "blocked")
    }

    func testPairedRelayInventoryIsNotOverwrittenByAccountRefresh() throws {
        let model = AppModel(loadStored: false)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_0123456789abcdef0123", name: "Garage", install: "inventory-fixture")
        let live = model.live(for: try device(phone(rows), pairing: pairing))
        let read = Date(timeIntervalSince1970: 10)
        let relay = PhoneLive.parseLan(["devices": [["id": "relay-camera", "name": "Relay", "connected": false]]])
        live.acceptInventory(relay, source: .live(read), at: read)
        _ = model.live(for: try device(phone([] as [Any]), pairing: pairing))
        live.applyCloud()
        XCTAssertEqual(PhoneLive.devices(in: live.categories).map(\.id), ["relay-camera"])
        XCTAssertEqual(live.inventorySource, .live(read))
        XCTAssertEqual(live.lanAt, read)
        XCTAssertTrue(PhoneLive.devices(in: live.categories)[0].disconnected)
    }

    func testPairingWithoutRelayReadStillReceivesAuthoritativeAccountRefresh() throws {
        let model = AppModel(loadStored: false)
        let pairing = Pairing(base: URL(string: "https://relay.invalid/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12", token: "rly_0123456789abcdef0123", name: "Garage", install: "inventory-fixture")
        let live = model.live(for: try device(phone(rows), pairing: pairing))
        XCTAssertEqual(PhoneLive.devices(in: live.categories).count, 3)
        _ = model.live(for: try device(phone([] as [Any]), pairing: pairing))
        XCTAssertEqual(live.categories, [])
        XCTAssertTrue(live.inventoryReported)
    }

    func testRelayMissingReachabilityStaysUnknownButExplicitFalseRemainsOffline() throws {
        let cats = PhoneLive.parseLan(["devices": [["id": "unknown", "name": "Unknown"], ["id": "off", "name": "Off", "connected": false]]])
        let all = PhoneLive.devices(in: cats)
        XCTAssertFalse(try XCTUnwrap(all.first { $0.id == "unknown" }).connectionReported)
        XCTAssertEqual(ListMode.unconnected.visible(all).map(\.id), ["off"])
    }
}
