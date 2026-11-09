import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import BridgitCore

final class HostFeedbackPolicyTests: XCTestCase {
    func testPinnedStatusTransitionsByActorAndCurrentState() {
        typealias Status = HostFeedbackStatus
        let cases: [(HostFeedbackActor, [Status: Set<Status>])] = [
            (.auto, [.new: [.seen], .reopened: [.seen]]),
            (.developer, [.new: [.inProgress, .wontFix, .duplicate], .seen: [.inProgress, .wontFix, .duplicate],
                          .reopened: [.inProgress, .wontFix, .duplicate], .inProgress: [.fixed, .wontFix]]),
            (.owner, [.fixed: [.confirmed, .reopened], .wontFix: [.confirmed, .reopened], .duplicate: [.confirmed, .reopened]]),
            (.reporter, [.fixed: [.confirmed, .reopened], .wontFix: [.confirmed, .reopened], .duplicate: [.confirmed, .reopened]]),
            (.team, [.new: [.seen, .inProgress, .fixed, .wontFix, .duplicate], .seen: [.inProgress, .fixed, .wontFix, .duplicate],
                     .inProgress: [.fixed, .wontFix, .duplicate], .fixed: [.inProgress, .wontFix, .duplicate],
                     .wontFix: [.inProgress, .fixed, .duplicate], .duplicate: [.inProgress, .fixed, .wontFix],
                     .reopened: [.seen, .inProgress, .fixed, .wontFix, .duplicate]])
        ]
        for (actor, rows) in cases {
            for from in Status.allCases {
                let actual = Set(Status.allCases.filter { from.allows($0, actor: actor) })
                XCTAssertEqual(actual, rows[from, default: []], "\(actor.rawValue) from \(from.rawValue)")
            }
        }
        XCTAssertEqual(Status.fixed.reporterOutcomes, ["works", "still_broken"])
        XCTAssertEqual(Status.seen.reporterOutcomes, [])
        XCTAssertEqual(Status.outcome("works"), .confirmed)
        XCTAssertEqual(Status.outcome("still_broken"), .reopened)
        XCTAssertNil(Status.outcome("fixed"))
    }
}

private actor FeedbackReplyGate {
    private(set) var entered = false
    private var released = false
    private var waiter: CheckedContinuation<Void, Never>?

    func hold() async {
        entered = true
        if !released { await withCheckedContinuation { waiter = $0 } }
    }

    func release() {
        released = true
        waiter?.resume()
        waiter = nil
    }
}

extension HostFeedbackPolicyTests {
    func testOrdinaryAndFixerRepliesReauthorizeAfterExistingDeliveryHook() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        for fixer in [false, true] {
            let (agent, _) = try await authority.create(name: fixer ? "Fixer" : "Reporter",
                operations: fixer ? [.deviceRead, .feedbackFixer] : [.deviceRead], applets: [])
            let gate = FeedbackReplyGate()
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                profile: { _ in throw CancellationError() }, feedback: { _, _ in
                    var reply = try HostResponse.json(["description": "private fixture report"])
                    reply.beforeDelivery = { await gate.hold() }
                    return (reply, fixer)
                })
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let work = Task { await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: 1, agentID: agent.id,
                generation: agent.generation, deviceID: "fixture", deadline: now + 30_000, createdAt: now,
                request: .init(method: "GET", path: "/v1/feedback/1"))) }
            for _ in 0..<100 {
                if await gate.entered { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard await gate.entered else {
                await gate.release()
                work.cancel()
                _ = await work.value
                XCTFail("The report did not reach its delivery hook")
                return
            }
            if fixer { try await authority.setFeedbackFixer(agent.id, enabled: false) }
            else { try await authority.pause(agent.id, paused: true) }
            await gate.release()
            let response = await work.value
            XCTAssertEqual(response.status, 403)
            XCTAssertFalse(String(decoding: response.data, as: UTF8.self).contains("private fixture report"))
        }
    }

    func testSuperpowerDoesNotImplicitlyMakeFixerAndViewerIsExcluded() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (admin, _) = try await authority.create(name: "Superpowers", operations: [.administrator], applets: [])
        let (reporter, _) = try await authority.create(name: "Reporter", operations: [.deviceRead], applets: [])
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        XCTAssertTrue(admin.allows(.feedback))
        XCTAssertFalse(admin.allows(.feedbackFixer))
        XCTAssertFalse(viewer.allows(.feedback))
        let own = HostRequest(method: "PATCH", path: "/v1/agents/me", body: ["operations": ["device.read", "feedback.fixer"]])
        do {
            _ = try await authority.administer(own, caller: reporter)
            XCTFail("An ordinary reporter must never grant itself Fixer Mode")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        for path in ["/v1/agents/me", "/v1/agents/\(admin.id)"] {
            let attempt = HostRequest(method: "PATCH", path: path, body: ["operations": ["agents.admin", "feedback.fixer"]])
            do {
                _ = try await authority.administer(attempt, caller: admin)
                XCTFail("An administrator must not grant themselves Fixer Mode through either API path")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        }
        let initial = HostRequest(method: "POST", path: "/v1/agents", body: ["name": "Synthetic new agent",
            "token_hash": String(repeating: "a", count: 64), "operations": ["device.read", "feedback.fixer"]])
        do {
            _ = try await authority.administer(initial, caller: admin)
            XCTFail("Delegation requires a different agent that is already paired")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
        let delegated = HostRequest(method: "PATCH", path: "/v1/agents/\(reporter.id)", body: ["operations": ["device.read", "feedback.fixer"]])
        _ = try await authority.administer(delegated, caller: admin)
        let stored = try await authority.agent(reporter.id)
        XCTAssertTrue(stored?.allows(.feedbackFixer) == true)
        XCTAssertTrue(HostAPIContract.allowed(try XCTUnwrap(stored)).contains { $0.name == "feedback_status" })
        XCTAssertFalse(HostAPIContract.allowed(admin).contains { $0.name == "feedback_status" })
        try await authority.setFeedbackFixer(admin.id, enabled: true)
        let ownerGranted = try await authority.agent(admin.id)
        XCTAssertTrue(ownerGranted?.allows(.feedbackFixer) == true)
    }
}

extension HostFeedbackPolicyTests {
    func testDirectReportCarriesFiveImagesWhileRelayAndMCPEnforceActualEnvelope() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (_, directKey) = try await authority.createOwnerDirect(name: "Direct Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let (relay, _) = try await authority.create(name: "Relay Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let pixels = try XCTUnwrap(CGContext(data: nil, width: 32, height: 32, bitsPerComponent: 8,
            bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(pixels.makeImage())
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let thumb = output as Data
        var original = Data(thumb.prefix(2))
        // Legal JPEG APP markers create deterministic, escape-heavy payloads
        // without relying on a camera or checking in sensitive image fixtures.
        for _ in 0..<14 {
            original.append(contentsOf: [0xFF, 0xEF, 0xFF, 0xFE])
            original.append(Data(repeating: 0xFF, count: 65_532))
        }
        original.append(thumb.dropFirst(2))
        XCTAssertLessThan(original.count, 1_000_000)
        let decoded = try XCTUnwrap(CGImageSourceCreateWithData(original as CFData, nil))
        XCTAssertNotNil(CGImageSourceCreateImageAtIndex(decoded, 0, nil))
        let fullImage = original.base64EncodedString(), thumbnail = thumb.base64EncodedString()
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, feedback: { request, _ in
                let photo = request.query["size"] as? String == "thumb" ? thumbnail : fullImage
                return (try .json(["images": Array(repeating: ["data_base64": photo], count: 5)]), true)
            })
        let direct = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let source = "100.81.0.2"
        func directCall(_ method: String, path: String, body: [String: Any] = [:]) async throws -> HostResponse {
            let data = body.isEmpty ? Data() : try JSONSerialization.data(withJSONObject: body)
            return await direct.handle(.init(method: method, target: path,
                headers: ["authorization": "Bearer " + directKey], body: data), source: source)
        }
        let complete = try await directCall("GET", path: "/v1/feedback/7")
        XCTAssertEqual(complete.status, 200)
        let full = try XCTUnwrap(JSONSerialization.jsonObject(with: complete.data) as? [String: Any])
        let photos = try XCTUnwrap(full["images"] as? [[String: String]])
        XCTAssertEqual(photos.count, 5)
        XCTAssertTrue(photos.allSatisfy { $0["data_base64"].flatMap { Data(base64Encoded: $0) } == original })

        func relayRead(_ size: String, sequence: Int64) async throws -> [String: Any] {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let job = HostJob(id: UUID().uuidString, sequence: sequence, agentID: relay.id, generation: relay.generation,
                deviceID: "fixture", deadline: now + 30_000, createdAt: now,
                request: .init(method: "GET", path: "/v1/feedback/7", query: ["size": size]))
            let response = await dispatcher.dispatch(job)
            let encoded = try RelayHostConnection.encodedResult(response, identity: .ephemeral(), peer: nil, job: job)
            return try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        }
        let oversized = try await relayRead("original", sequence: 1)
        XCTAssertEqual(oversized["status"] as? Int, 413)
        let encodedError = try XCTUnwrap(oversized["body_base64"] as? String)
        let relayError = try XCTUnwrap(Data(base64Encoded: encodedError))
        XCTAssertTrue(String(decoding: relayError, as: UTF8.self).contains("size=thumb"))
        let bounded = try await relayRead("thumb", sequence: 2)
        XCTAssertEqual(bounded["status"] as? Int, 200)

        let initialize = try await directCall("POST", path: "/v1/mcp", body: [
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": ["protocolVersion": "2025-11-25", "capabilities": [String: Any](),
                       "clientInfo": ["name": "Fixture", "version": "1"]]
        ])
        XCTAssertEqual(initialize.status, 200)
        func tool(_ size: String, id: Int) async throws -> [String: Any] {
            let response = try await directCall("POST", path: "/v1/mcp", body: [
                "jsonrpc": "2.0", "id": id, "method": "tools/call",
                "params": ["name": "feedback_get", "arguments": ["id": "7", "query": ["size": size]]]
            ])
            return try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        }
        let largeRPC = try await tool("original", id: 2)
        XCTAssertNil(largeRPC["result"])
        XCTAssertTrue(((largeRPC["error"] as? [String: Any])?["message"] as? String)?.contains("size=thumb") == true)
        let thumbRPC = try await tool("thumb", id: 3)
        XCTAssertEqual((thumbRPC["result"] as? [String: Any])?["isError"] as? Bool, false)
    }
}

actor FeedbackWaitGate {
    private(set) var count = 0
    private var released = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func hold() async {
        count += 1
        if !released { await withCheckedContinuation { waiters.append($0) } }
    }

    func release() {
        released = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

extension HostFeedbackPolicyTests {
    func testHostResponseRejectsNonJSONWithoutEchoingPrivateValues() throws {
        let privateText = "synthetic private text"
        let lazy = [1, 2].lazy.map { _ in ["description": privateText] }
        for invalid: [String: Any] in [["reports": lazy], ["reports": [["measurement": Double.nan, "description": privateText]]]] {
            XCTAssertThrowsError(try HostResponse.json(invalid)) { error in
                XCTAssertEqual((error as? DeviceAPIError)?.code, "invalid_response")
                XCTAssertFalse(error.localizedDescription.contains(privateText))
            }
        }
        let valid = try HostResponse.json(["reports": Array(lazy)])
        XCTAssertEqual(valid.status, 200)
        XCTAssertTrue(String(decoding: valid.data, as: UTF8.self).contains(privateText))
    }

    func testFourFeedbackWaitsKeepOrdinaryJobsAvailableAndRejectFifth() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "fixture")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (reporter, _) = try await authority.create(name: "Reporter", operations: [.deviceRead], applets: [])
        let gate = FeedbackWaitGate()
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, feedback: { _, _ in
                await gate.hold()
                return (try .json(["reports": []]), false)
            })
        let request = HostRequest(method: "GET", path: "/v1/feedback", query: ["since": "2026-11-07T12:00:00Z", "wait": 20])
        func job(_ sequence: Int64, request: HostRequest) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return HostJob(id: UUID().uuidString, sequence: sequence, agentID: reporter.id, generation: reporter.generation,
                           deviceID: "fixture", deadline: now + 30_000, createdAt: now, request: request)
        }
        var held: [Task<HostResponse, Never>] = []
        for sequence in 1...4 {
            let pending = job(Int64(sequence), request: request)
            held.append(Task { await dispatcher.dispatch(pending) })
            for _ in 0..<100 {
                if await gate.count == sequence { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard await gate.count == sequence else {
                await gate.release()
                for task in held { _ = await task.value }
                XCTFail("Expected feedback wait \(sequence) to enter.")
                return
            }
        }
        let denied = await dispatcher.dispatch(job(5, request: request))
        XCTAssertEqual(denied.status, 429)
        XCTAssertTrue(String(decoding: denied.data, as: UTF8.self).contains("too_many_waits"))
        let ordinary = await dispatcher.dispatch(job(6, request: .init(method: "GET", path: "/v1/health")))
        XCTAssertEqual(ordinary.status, 200)
        await gate.release()
        for task in held {
            let finished = await task.value
            XCTAssertEqual(finished.status, 200)
        }
        let later = await dispatcher.dispatch(job(7, request: request))
        XCTAssertEqual(later.status, 200)
    }
}

extension HostFeedbackPolicyTests {
    func testFeedbackWaitBudgetIncludesSlowFreshenAndNeverRestarts() {
        let started = ContinuousClock.now
        let budget = HostFeedbackWaitBudget(seconds: 55, started: started)
        XCTAssertEqual(budget.remaining(at: started), .seconds(55))
        XCTAssertEqual(budget.remaining(at: started.advanced(by: .seconds(20))), .seconds(35))
        XCTAssertEqual(budget.remaining(at: started.advanced(by: .milliseconds(54_950))), .milliseconds(50))
        XCTAssertEqual(budget.remaining(at: started.advanced(by: .seconds(55))), .zero)
        XCTAssertEqual(budget.remaining(at: started.advanced(by: .seconds(60))), .zero)
    }
}

extension HostFeedbackPolicyTests {
    func testAuthenticatedLoopbackFeedbackFilingUsesExactDirectJSONBoundary() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "feedback-size")
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (_, key) = try await authority.createOwnerDirect(name: "Direct Fixer", operations: [.deviceRead, .feedbackFixer, .log], applets: [])
        let (relay, _) = try await authority.create(name: "Relay Fixer", operations: [.deviceRead, .feedbackFixer], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, feedback: { request, agent in
                XCTAssertEqual(request.path, "/v1/feedback")
                XCTAssertTrue(agent.allows(.feedbackFixer))
                return (try .json(["received": true], status: 201), true)
            })
        let direct = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, socket in
            XCTAssertEqual(socket, "127.0.0.1")
            return await direct.handle(request, source: "100.81.0.2")
        })
        addTeardownBlock {
            await listener.stop()
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let http = URLSession(configuration: .ephemeral)
        defer { http.invalidateAndCancel() }
        let maximum = 2 * 1_024 * 1_024
        let filing: [String: Any] = ["kind": "bug", "description": "Offline fixture"]
        func send(_ path: String, _ body: [String: Any], size: Int? = nil,
                  idempotency: String = UUID().uuidString, token: String? = nil) async throws -> (Int, Data) {
            let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)" + path))
            var request = URLRequest(url: url, timeoutInterval: 15)
            request.httpMethod = "POST"
            request.setValue("Bearer " + (token ?? key), forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(idempotency, forHTTPHeaderField: "Idempotency-Key")
            var data = try JSONSerialization.data(withJSONObject: body)
            if let size {
                XCTAssertLessThanOrEqual(data.count, size)
                data.append(Data(repeating: 0x20, count: size - data.count))
                XCTAssertEqual(data.count, size)
            }
            request.httpBody = data
            let (reply, response) = try await http.data(for: request)
            return (try XCTUnwrap((response as? HTTPURLResponse)?.statusCode), reply)
        }
        for (bytes, status) in [(maximum - 1, 201), (maximum, 201), (maximum + 1, 413)] {
            let result = try await send("/v1/feedback", filing, size: bytes)
            XCTAssertEqual(result.0, status)
        }
        let badToken = "brg_" + String(repeating: "x", count: 43)
        let refused = try await send("/v1/feedback", filing, size: maximum + 1, token: badToken)
        XCTAssertEqual(refused.0, 401)
        let initialized = try await send("/v1/mcp", [
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": ["protocolVersion": "2025-11-25", "capabilities": [String: Any](),
                       "clientInfo": ["name": "Fixture", "version": "1"]]
        ])
        XCTAssertEqual(initialized.0, 200)
        let tool: [String: Any] = ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": [
            "name": "feedback_create", "arguments": ["body": filing, "idempotency_key": "fixture-tool"]
        ]]
        let nested = try await send("/v1/mcp", tool, size: maximum)
        XCTAssertEqual(nested.0, 200)
        let rpc = try XCTUnwrap(JSONSerialization.jsonObject(with: nested.1) as? [String: Any])
        XCTAssertEqual((rpc["result"] as? [String: Any])?["isError"] as? Bool, false)
        let excess = try await send("/v1/mcp", tool, size: maximum + 1)
        XCTAssertEqual(excess.0, 413)

        let padding = String(repeating: "x", count: 1_024 * 1_024)
        let unrelated = try await send("/v1/log", ["message": padding])
        XCTAssertEqual(unrelated.0, 413)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let relayResponse = await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: 1, agentID: relay.id,
            generation: relay.generation, deviceID: "feedback-size", deadline: now + 30_000, createdAt: now,
            request: .init(method: "POST", path: "/v1/feedback", body: ["kind": "bug", "description": padding], idempotencyKey: "relay-fixture")))
        XCTAssertEqual(relayResponse.status, 413)
    }
}
