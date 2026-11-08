import XCTest
import CryptoKit
import ImageIO
import BridgitAppletRuntime
@testable import Bridgit_Portal

final class ParsingTests: XCTestCase {
    @MainActor func testLivePublicRegistryInstallAndOfflineReopen() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["BRIDGIT_LIVE_REGISTRY"] == "read-public")
        let install = DecisionInstall(key: P256.Signing.PrivateKey())
        let expected: Set<String> = ["room-change-watcher","situation-counter","external-device-dashboard","voice-notebook",
            "daily-briefing-panel","exercise-check","insurance-inventory","kitchen-cleanliness","chef-helper",
            "pet-monitor","a-sorta-fairytale-about-me","dog-monitor","receipt-scanner"]
        let http = URLSession(configuration: .ephemeral)
        defer { http.invalidateAndCancel() }
        func call(_ path: String, body: [String:JSONValue]? = nil, signed: Bool = true, accept: String? = nil) async throws -> Data {
            var req = URLRequest(url: try XCTUnwrap(URL(string: path, relativeTo: WatchClient.defaultBase)))
            req.setValue(accept, forHTTPHeaderField: "Accept")
            req.httpMethod = body == nil ? "GET" : "POST"
            if let body {
                req.httpBody = try JSONEncoder().encode(body)
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            if signed { try install.sign(&req, at: Int64(Date().timeIntervalSince1970*1000), nonce: UUID().uuidString) }
            let (data,response) = try await http.data(for: req)
            guard let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else {
                throw AppletRuntimeError.invalid("Registry refused " + path + " (HTTP " + String((response as? HTTPURLResponse)?.statusCode ?? 0) + ")")
            }
            return data
        }
        let challengeData = try await call("/v1/install/challenge", body: [:], signed: false)
        let challenge = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: challengeData)["challenge"])
        let registered = try await call("/v1/install/register", body: ["challenge": challenge, "attestation_chain": .array([]),
            "public_key": .string(install.key.publicKey.derRepresentation.base64EncodedString())])
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: registered)["install_id"], .string(install.id))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let schemaData = try await call("/v1/templates/schema")
        let schema = try JSONDecoder().decode(JSONValue.self, from: schemaData)
        XCTAssertEqual(schema["max_detached_media_bytes"], .number(4194304))
        let assetPath = "/v1/templates/quick-question/1/assets/card.html"
        let descriptorData = try await call(assetPath)
        let descriptor = try JSONDecoder().decode(JSONValue.self, from: descriptorData)
        let asset = try await call(assetPath, accept: "application/octet-stream")
        XCTAssertEqual(String(data:asset,encoding:.utf8), descriptor["text"]?.text)
        XCTAssertEqual(SHA256.hash(data:asset).map { String(format:"%02x",$0) }.joined(), descriptor["sha256"]?.text)
        let file = dir.appendingPathComponent("test.sqlite")
        let testOwner = SHA256.hash(data: Data(install.id.utf8)).map { String(format:"%02x",$0) }.joined()
        let library = try LocalAppletLibrary(database: file, account: testOwner)
        var seen = Set<String>(), cursor = "", cursors = Set<String>()
        var installed = [CatalogEntry]()
        repeat {
            let pageData = try await call("/v1/templates/catalog?kind=applet&scope=public&limit=50&after=" + cursor)
            let page = try JSONDecoder().decode(CatalogPage.self, from: pageData)
            for entry in page.applets where expected.contains(entry.key) {
                XCTAssertTrue(seen.insert(entry.key).inserted)
                let data = try await call("/v1/templates/" + entry.key + "/" + String(entry.revision))
                switch try CatalogRelease(response: data, selection: entry) {
                case .androidMonitor(let package, _): XCTFail("Published catalog entry still requires its legacy adapter: " + package.id)
                case .local(let release):
                    try await library.install(release)
                    installed.append(entry)
                }
            }
            cursor = page.next ?? ""
            if !cursor.isEmpty && !cursors.insert(cursor).inserted { throw AppletRuntimeError.invalid("Catalog repeated a cursor") }
        } while !cursor.isEmpty && seen != expected
        XCTAssertEqual(seen, expected)
        XCTAssertEqual(installed.count, expected.count)
        let offline = try LocalAppletLibrary(database: file, account: testOwner)
        for entry in installed {
            let read = try await offline.installed(entry.key)
            let edition = try XCTUnwrap(read)
            XCTAssertEqual(edition.revision, entry.revision)
            let instance = try await offline.open(edition)
            XCTAssertEqual(instance.package.id, entry.id)
        }
    }

    @MainActor func testWatchCardReadsOnlyItsDeclaredAliasAndWatchKey() throws {
        let data = Data(#"{"template":"counter","revision":1,"watches":[{"key":"main","state":"scheduled","checks":3,"tally":{"today":{"watched_minutes":6.5},"episodes":[]},"recent_checks":[{"at":"now","outcome":"error"}]}]}"#.utf8)
        let watch = try XCTUnwrap(PhoneLive.decodeMonitorWatches(data, template: "counter", revision: 1).first)
        let sources: JSONValue = .object([
            "count": .object(["read": .string("tally"), "watch": .object(["$watch": .string("main")])]),
            "status": .object(["read": .string("status"), "watch": .object(["$watch": .string("main")])]),
            "other": .object(["read": .string("status"), "watch": .object(["$watch": .string("different")])])])
        func field(_ path: String) -> JSONValue? {
            MonitorCard.binding(.object(["$source":.string(path)]), sources: sources)?.resolve(in: watch)
        }
        XCTAssertEqual(field("count.today.watched_minutes"), .number(6.5))
        XCTAssertEqual(field("count.episodes"), .array([]))
        XCTAssertEqual(field("status.checks"), .number(3))
        XCTAssertNil(field("other.checks"))
        XCTAssertNil(field("missing.episodes"))
    }

    @MainActor func testMonitorActivityRequiresSelectedEditionAndKnownState() throws {
        let payload = #"{"template":"room","revision":4,"watches":[{"key":"main","state":"budget_paused","checks":2,"matches":0,"last_check_at":"2026-11-06T11:00:00Z","last_outcome":"error"}]}"#
        let data = Data(payload.utf8)
        let watch = try XCTUnwrap(PhoneLive.decodeMonitorWatches(data, template: "room", revision: 4).first)
        XCTAssertEqual(watch.state.label, "Paused by budget")
        XCTAssertEqual(watch.last_outcome, "error")
        XCTAssertThrowsError(try PhoneLive.decodeMonitorWatches(data, template: "room", revision: 3))
        XCTAssertThrowsError(try PhoneLive.decodeMonitorWatches(Data(payload.replacingOccurrences(of: "budget_paused", with: "unknown").utf8),
                                                               template: "room", revision: 4))
    }

    @MainActor func testEnrollmentResponseMustMatchApprovedPinAndAvailability() throws {
        let json = #"{"template":"devices","revision":3,"devices":[{"id":"d","name":"Lamp","adapter":"shelly","availability":"not_checked","verified_at":1000}]}"#
        let data = Data(json.utf8)
        XCTAssertEqual(try PhoneLive.decodeEnrollments(data, template: "devices", revision: 3).first?.name, "Lamp")
        XCTAssertThrowsError(try PhoneLive.decodeEnrollments(data, template: "fork", revision: 3))
        XCTAssertThrowsError(try PhoneLive.decodeEnrollments(data, template: "devices", revision: 2))
        let invalid = Data(json.replacingOccurrences(of: "not_checked", with: "online").utf8)
        XCTAssertThrowsError(try PhoneLive.decodeEnrollments(invalid, template: "devices", revision: 3))
    }

    func testServiceSignatureCoversPathBodyAndInstallWithoutSessionBearer() throws {
        let key = P256.Signing.PrivateKey()
        let identity = DecisionInstall(key:key)
        var req = URLRequest(url:URL(string:"https://registry.invalid/v1/clef?a=1")!)
        req.httpMethod = "POST"; req.httpBody = Data("reviewed".utf8)
        req.setValue("Bearer test-session",forHTTPHeaderField:"Authorization")
        try identity.sign(&req,at:1234,nonce:"example-nonce")
        XCTAssertNil(req.value(forHTTPHeaderField:"Authorization"))
        XCTAssertEqual(req.value(forHTTPHeaderField:"X-Bridgit-Install"), identity.id)
        XCTAssertEqual(identity.id.count,28)
        let header = try XCTUnwrap(req.value(forHTTPHeaderField:"X-Bridgit-Signature"))
        let b64 = try XCTUnwrap(header.components(separatedBy:"sig=").last)
        let signature = try P256.Signing.ECDSASignature(rawRepresentation:XCTUnwrap(Data(base64Encoded:b64)))
        let hash = SHA256.hash(data:Data("reviewed".utf8)).map { String(format:"%02x",$0) }.joined()
        let signed = ["bridgit-install-v1",identity.id,"POST","/v1/clef?a=1","1234","example-nonce",hash].joined(separator:"\n")
        XCTAssertTrue(key.publicKey.isValidSignature(signature,for:Data(signed.utf8)))
    }

    @MainActor func testClefAnswersAndConfiguredLimits() throws {
        let questions: [String:JSONValue] = ["lamp":.object(["type":.string("choice"),"criteria":.object(["lit":.string("On")])])]
        func reply(_ choice:String) -> JSONValue {
            .object(["model":.string("clef-omni"),"cost_usd":.number(0.0001),"answers":.object([
                "lamp":.object(["type":.string("choice"),"choice":.string(choice),"confidence":.number(0.9)])])])
        }
        let result = try WatchClient.decisionResult(reply("lit"),questions:questions)
        XCTAssertEqual(result["answers"]?["lamp"],.string("lit"))
        XCTAssertEqual(result["confidence"]?["lamp"],.number(0.9))
        XCTAssertThrowsError(try WatchClient.decisionResult(reply("foreign"),questions:questions))
        let limits = try DecisionInstall.limits(.object(["day_usd":.number(0.5),"month_usd":.number(5)]))
        XCTAssertEqual(limits.map { $0["usd"] },[.number(0.5),.number(5)])
        XCTAssertThrowsError(try DecisionInstall.limits(.object(["limits":.array([])])))
        let limit: JSONValue = .object(["usd":.number(2),"per":.string("month"),"check_at":.number(0.5)])
        XCTAssertEqual(try DecisionInstall.limits(.array([limit])).first?["check_at"],.array([.number(0.5)]))
        XCTAssertThrowsError(try DecisionInstall.limits(.object(["limits":.array([limit]),"typo":.bool(true)])))
    }

    func testPairLink() throws {
        let url = URL(string: "bridgitportal://pair#v=1&relay=https%3A%2F%2Fbridgit-relay.bridgit-app.workers.dev&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=Kitchen%20phone&install=bgi_x&portal=1")!
        let p = try XCTUnwrap(Pairing.parse(link: url))
        XCTAssertEqual(p.base.absoluteString, "https://bridgit-relay.bridgit-app.workers.dev/d/dev_ABCDEFGH12")
        XCTAssertEqual(p.name, "Kitchen phone")
        XCTAssertEqual(p.install, "bgi_x")
        XCTAssertTrue(p.portal)
    }

    func testPairLinkRejectsHttpAndBadToken() {
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgitportal://pair#relay=http%3A%2F%2Fevil.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123")!))
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgitportal://pair#relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=secret")!))
        XCTAssertNil(Pairing.parse(link: URL(string: "bridgitportal://pair#relay=https%3A%2F%2Fr.example&device=x&token=rly_0123456789abcdef0123")!))
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
        let r = try XCTUnwrap(SignIn.parse(URL(string: "bridgitportal://signin#session=bws_abcdefghijklmnopqrstuvwxyz012345&email=a%40b.c")!))
        XCTAssertEqual(r.email, "a@b.c")
        XCTAssertNil(SignIn.parse(URL(string: "bridgitportal://signin#session=nope")!))
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
        let base = "bridgitportal://pair#v=1&relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=P"
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

        // During Network Diagnostics: only what the scan saw, each drawn as seen with its address.
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
        XCTAssertTrue(a.matches(live))
        XCTAssertFalse(a.matches(PortalDevice(id: "cloud:other", name: "x", cloud: SampleData.phones[0], pairing: nil)))
    }
}

private final class CatalogStub: URLProtocol {
    static let jpeg: Data = {
        let context = CGContext(data:nil,width:2,height:2,bitsPerComponent:8,bytesPerRow:8,
                                space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!
        let data = NSMutableData()
        let destination = CGImageDestinationCreateWithData(data,"public.jpeg" as CFString,1,nil)!
        CGImageDestinationAddImage(destination,context.makeImage()!,nil)
        precondition(CGImageDestinationFinalize(destination))
        let original = data as Data
        let comment = Data([255,254,255,255]) + Data(repeating:65,count:65533)
        return original.prefix(2) + comment + comment + original.dropFirst(2)
    }()
    static let checksum = SHA256.hash(data:jpeg).map { String(format:"%02x",$0) }.joined()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let url = request.url!
        let reply: String
        var status = 200
        if request.value(forHTTPHeaderField: "Authorization") != "Bearer test-owner" {
            status = 401; reply = #"{"message":"Sign in"}"#
        } else if url.path == "/v1/templates/catalog" {
            let parameters = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertTrue(parameters.contains(URLQueryItem(name: "kind", value: "applet")))
            XCTAssertTrue(parameters.contains(URLQueryItem(name: "q", value: "Blue & gold")))
            XCTAssertTrue(parameters.contains(URLQueryItem(name: "after", value: "tpl_before")))
            reply = #"{"format":"bridgit-applet-catalog/1","applets":[{"key":"tpl_edition","id":"blue-note","name":"Blue note","summary":"Notes","revision":3}],"next":null}"#
        } else if ["/v1/templates/tpl_edition/3/assets/manual.jpg","/v1/templates/tpl_edition/3/assets/incomplete.jpg"].contains(url.path) {
            XCTAssertEqual(request.value(forHTTPHeaderField:"Accept"),"application/octet-stream")
            let response = HTTPURLResponse(url:url,statusCode:200,httpVersion:nil,headerFields:["Content-Type":"application/octet-stream"])!
            client?.urlProtocol(self,didReceive:response,cacheStoragePolicy:.notAllowed)
            client?.urlProtocol(self,didLoad:url.lastPathComponent == "incomplete.jpg" ? Self.jpeg.prefix(100) : Self.jpeg)
            client?.urlProtocolDidFinishLoading(self)
            return
        } else if url.path == "/v1/templates/tpl_edition/3" {
            reply = #"{"key":"tpl_edition","revision":3,"state":"published","definition":{"format":"bridgit-template/1","kind":"applet","id":"blue-note","version":"2.0.0","assets":{"manual.jpg":{"media_type":"image/jpeg","size":\#(Self.jpeg.count),"sha256":"\#(Self.checksum)","storage":"revision"}},"config_schema":{"type":"object","properties":{},"required":[],"additionalProperties":false},"runtime":{"adapter":"bridgit.package.v1","supports_dynamic_execution":false},"permissions":{"automatic_grants":false,"required":["workflows","interactions"]},"defaults":{},"processing":{"workflow":{"name":"Greeting","trigger":{"type":"button"},"steps":[{"id":"hello","type":"notice","text":"Hello from the local library"}]}}}}"#
        } else {
            status = 404; reply = #"{"message":"Missing pinned release"}"#
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

extension ParsingTests {
    @MainActor func testCatalogUsesAccountAuthAndFetchesThePinnedEdition() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CatalogStub.self]
        let client = WatchClient(base: URL(string: "https://registry.invalid")!, session: "test-owner", http: URLSession(configuration: config))
        let page = try await client.catalog(query: "Blue & gold", after: "tpl_before")
        XCTAssertNil(page.next)
        let entry = try XCTUnwrap(page.applets.first)
        let verified = try await client.release(entry)
        XCTAssertEqual(verified.package.id, "blue-note")
        XCTAssertEqual(verified.package.version, "2.0.0")
        let exported = try XCTUnwrap(JSONSerialization.jsonObject(with: verified.data) as? [String: Any])
        XCTAssertEqual(exported["format"] as? String, "bridgit-template/1")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("library.sqlite")
        let account = String(repeating: "d", count: 64)
        let store = try LocalAppletLibrary(database: url, account: account)
        var partial = try XCTUnwrap(JSONSerialization.jsonObject(with:verified.data) as? [String:Any])
        var descriptors = try XCTUnwrap(partial["assets"] as? [String:Any])
        descriptors["incomplete.jpg"] = descriptors.removeValue(forKey:"manual.jpg")
        partial["assets"] = descriptors
        let envelope:[String:Any] = ["key":entry.key,"revision":entry.revision,"state":"published","definition":partial]
        let interrupted = try RegistryPackage(response:JSONSerialization.data(withJSONObject:envelope),selection:entry)
        do { _ = try await client.packageMedia(interrupted); XCTFail("Incomplete download accepted") } catch is AppletRuntimeError {}
        XCTAssertGreaterThan(CatalogStub.jpeg.count,131072)
        let assets = try await client.packageMedia(verified)
        try await store.install(verified,media:assets)
        client.session = nil
        let offlineStore = try LocalAppletLibrary(database: url, account: account)
        let rows = try await offlineStore.editions()
        let local = try XCTUnwrap(rows.first)
        let localBytes = try await offlineStore.media(named:"manual.jpg",in:local)
        XCTAssertEqual(localBytes,CatalogStub.jpeg)
        let outcome = try await offlineStore.runButton("main", in: local) { notice in
            XCTAssertEqual(notice.text, "Hello from the local library")
        }
        XCTAssertNotNil(outcome.results["hello"]?["interaction_id"])
        do { _ = try await client.release(entry); XCTFail("Expected signed-out error") }
        catch let error as APIError { XCTAssertTrue(error.isSignedOut) }
    }
}
