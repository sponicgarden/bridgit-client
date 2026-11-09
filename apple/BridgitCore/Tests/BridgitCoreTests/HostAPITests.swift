import CryptoKit
import Foundation
import XCTest
@testable import BridgitCore
final class HostAPITests: XCTestCase {
    func testOperationCatalogReflectsRegisteredMethodAndPathOnly() {
        XCTAssertTrue(HostOperationCatalog.contains(method: "POST", path: "/v1/camera/snapshot"))
        XCTAssertTrue(HostOperationCatalog.contains(method: "GET", path: "/v1/settings"))
        XCTAssertTrue(HostOperationCatalog.contains(method: "POST", path: "/v1/mcp"))
        XCTAssertFalse(HostOperationCatalog.contains(method: "DELETE", path: "/v1/mcp"))
        XCTAssertFalse(HostOperationCatalog.contains(method: "GET", path: "/v1/camera/snapshot"))
        XCTAssertFalse(HostOperationCatalog.contains(method: "POST", path: "/v1/location"))
    }

    private func setup() async throws -> (AppletStore, HostAuthority, HostDispatcher, HostAgent) {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.deviceRead, .interactionsRead, .interactionsWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        return (store, authority, dispatcher, agent)
    }
    private func job(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "fixture", deadline: now + 120000, createdAt: now, request: request)
    }
    private func object(_ response: HostResponse) throws -> [String: Any] { try JSONSerialization.jsonObject(with: response.data) as! [String: Any] }
    private var initialize: [String: Any] { ["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": ["protocolVersion": "2025-11-25", "capabilities": [:], "clientInfo": ["name": "Fixture", "version": "1"]]] }
    func testCapabilitiesReportsActualCaptureNoticeState() async throws {
        let (store, authority, _, agent) = try await setup()
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let profile: HostDispatcher.Profile = { _ in
            DeviceProfile(profileRevision: 1, observedAt: Date(), expiresAt: Date().addingTimeInterval(60),
                device: .init(installID: nil, name: "Fixture", deviceType: "computer", platform: "macos", osVersion: "fixture", appVersion: "fixture", apiRevision: 1, services: [], hardware: [:]),
                availability: .init(state: "online", inForeground: true), capabilities: [:])
        }
        let on = HostDispatcher(authority: authority, store: store, engine: engine, profile: profile, captureNotifications: { true })
        let off = HostDispatcher(authority: authority, store: store, engine: engine, profile: profile, captureNotifications: { false })
        let request = HostRequest(method: "GET", path: "/v1/capabilities")
        let first = await on.dispatch(job(agent, 1, request))
        let second = await off.dispatch(job(agent, 2, request))
        XCTAssertEqual(first.status, 200); XCTAssertEqual(try object(first)["notifications"] as? Bool, true)
        XCTAssertEqual(second.status, 200); XCTAssertEqual(try object(second)["notifications"] as? Bool, false)
    }
    func testAPIChangesAcknowledgesOnlySuccessfulReadsPerAgentGeneration() async throws {
        let (store, authority, dispatcher, first) = try await setup()
        let (second, _) = try await authority.create(name: "Second", operations: [.deviceRead], applets: [])
        func get(_ agent: HostAgent, _ sequence: Int64, _ path: String, _ query: [String: Any] = [:]) async -> HostResponse {
            await dispatcher.dispatch(job(agent, sequence, HostRequest(method: "GET", path: path, query: query)))
        }
        try await store.setState(HostAPIChanges.key(first), value: "1")
        let health = await get(first, 1, "/v1/health")
        let op = try XCTUnwrap(HostAPIContract.allowed(first).first { $0.name == "api_changes" })
        let queryProperties = try XCTUnwrap(op.querySchema["properties"] as? [String: [String: Any]])
        let sinceSchema = try XCTUnwrap(queryProperties["since"])
        XCTAssertEqual(sinceSchema["type"] as? String, "integer")
        XCTAssertEqual(sinceSchema["minimum"] as? Int, 0)
        XCTAssertEqual(sinceSchema["default"] as? Int, 0)
        let mcpProperties = try XCTUnwrap(op.schema["properties"] as? [String: Any])
        let mcpQuery = try XCTUnwrap(mcpProperties["query"] as? [String: Any])
        let mcpQueryProperties = try XCTUnwrap(mcpQuery["properties"] as? [String: [String: Any]])
        XCTAssertEqual(mcpQueryProperties["since"]?["minimum"] as? Int, 0)
        let openAPI = HostAPIContract.openAPI(first)
        let paths = try XCTUnwrap(openAPI["paths"] as? [String: Any])
        let changesPath = try XCTUnwrap(paths["/v1/changes"] as? [String: Any])
        let getSpec = try XCTUnwrap(changesPath["get"] as? [String: Any])
        let params = try XCTUnwrap(getSpec["parameters"] as? [[String: Any]])
        XCTAssertTrue(params.contains { $0["name"] as? String == "since" })
        XCTAssertEqual(health.headers["X-Bridgit-API-Revision"], String(HostAPIChanges.current))
        XCTAssertEqual((try object(health)["whats_new"] as? [String: Any])?["since"] as? Int, 1)
        XCTAssertEqual(health.headers["X-Bridgit-Whats-New"], "/v1/changes?since=1")
        let invalid = await get(first, 2, "/v1/changes", ["since": "-1"])
        XCTAssertEqual(invalid.status, 422)
        XCTAssertEqual(invalid.headers["X-Bridgit-API-Revision"], String(HostAPIChanges.current))
        let stillUnknown = await get(first, 3, "/v1/health")
        XCTAssertEqual(stillUnknown.headers["X-Bridgit-Whats-New"], "/v1/changes?since=1")
        let changes = await get(first, 4, "/v1/changes")
        XCTAssertEqual(changes.status, 200)
        XCTAssertEqual(try object(changes)["api_revision"] as? Int, HostAPIChanges.current)
        XCTAssertEqual((try object(changes)["changes"] as? [[String: Any]])?.count, HostAPIChanges.current)
        var nextGeneration = first; nextGeneration.generation = UUID().uuidString
        let nextKnown = try await store.state(HostAPIChanges.key(nextGeneration))
        XCTAssertNil(nextKnown)
        let beforeDelivery = await get(first, 5, "/v1/health")
        XCTAssertEqual(beforeDelivery.headers["X-Bridgit-Whats-New"], "/v1/changes?since=1")
        await changes.afterOriginalDelivery?()
        let acknowledged = await get(first, 6, "/v1/health")
        let other = await get(second, 1, "/v1/health")
        XCTAssertNil(acknowledged.headers["X-Bridgit-Whats-New"])
        XCTAssertTrue(try object(acknowledged)["whats_new"] is NSNull)
        let newPermissionHistory = HostAPIChanges.document(since: 4, appVersion: "fixture")["changes"] as? [[String: Any]]
        XCTAssertEqual(newPermissionHistory?.map { $0["revision"] as? Int }, Array(5...HostAPIChanges.current))
        let currentChanges = await get(first, 8, "/v1/changes", ["since": String(HostAPIChanges.current)])
        XCTAssertEqual((try object(currentChanges)["changes"] as? [[String: Any]])?.count, 0)
        XCTAssertEqual(other.headers["X-Bridgit-Whats-New"], "/v1/changes?since=0")
        let restarted = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let persisted = await restarted.dispatch(job(first, 7, HostRequest(method: "GET", path: "/v1/health")))
        XCTAssertNil(persisted.headers["X-Bridgit-Whats-New"])
        _ = await restarted.dispatch(job(second, 2, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let mcpGuide: [String: Any] = ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": ["name": "guide", "arguments": [:]]]
        let guide = await restarted.dispatch(job(second, 3, .init(method: "POST", path: "/v1/mcp", body: mcpGuide)))
        XCTAssertFalse((try object(guide)["result"] as? [String: Any])?["isError"] as? Bool ?? true)
        await guide.afterOriginalDelivery?()
        let afterMCP = await restarted.dispatch(job(second, 4, .init(method: "GET", path: "/v1/health")))
        XCTAssertNil(afterMCP.headers["X-Bridgit-Whats-New"])
    }

    func testAPIChangesSurvivesStoreAndAuthorityRestartAndAgentReplacement() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Original", operations: [.deviceRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let reply = await dispatcher.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/changes")))
        XCTAssertEqual(reply.status, 200); await reply.afterOriginalDelivery?()
        let reopened = try AppletStore(directory: directory)
        let nextAuthority = HostAuthority(store: reopened, deviceID: "fixture")
        await nextAuthority.setState(enabled: true, awake: true, foreground: true)
        let nextDispatcher = HostDispatcher(authority: nextAuthority, store: reopened, engine: AppletEngine(store: reopened) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let existing = await nextDispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/health")))
        XCTAssertEqual(existing.status, 200); XCTAssertNil(existing.headers["X-Bridgit-Whats-New"])
        try await nextAuthority.remove(agent.id)
        let (replacement, _) = try await nextAuthority.create(name: "Replacement", operations: [.deviceRead], applets: [])
        let newHealth = await nextDispatcher.dispatch(job(replacement, 1, .init(method: "GET", path: "/v1/health")))
        XCTAssertEqual(newHealth.status, 200); XCTAssertEqual(newHealth.headers["X-Bridgit-Whats-New"], "/v1/changes?since=0")
    }

    func testAPIChangesRevokedBeforeDeliveryDoesNotAcknowledge() async throws {
        let (store, authority, dispatcher, agent) = try await setup()
        let reply = await dispatcher.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/guide")))
        XCTAssertEqual(reply.status, 200)
        try await authority.pause(agent.id, paused: true)
        await reply.afterOriginalDelivery?()
        let saved = try await store.state(HostAPIChanges.key(agent))
        XCTAssertNil(saved)
        try await authority.pause(agent.id, paused: false)
        let health = await dispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/health")))
        XCTAssertEqual(health.headers["X-Bridgit-Whats-New"], "/v1/changes?since=0")
    }

    func testActivityReadsScopeFiltersCursorRenameAndMCP() async throws {
        let (store, authority, dispatcher, denied) = try await setup()
        let (reader, _) = try await authority.create(name: "Reader", operations: [.deviceRead, .log], applets: [])
        let (other, _) = try await authority.create(name: "Other", operations: [.log], applets: [])
        let (admin, _) = try await authority.create(name: "Owner delegate", operations: [.administrator], applets: [])
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        func read(_ agent: HostAgent, _ seq: Int64, _ path: String = "/v1/log", _ query: [String: Any] = [:]) async -> HostResponse {
            await dispatcher.dispatch(job(agent, seq, .init(method: "GET", path: path, query: query)))
        }
        func entries(_ response: HostResponse) throws -> [[String: Any]] {
            try XCTUnwrap(object(response)["entries"] as? [[String: Any]])
        }
        let own = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "literal %_ and note", "level": "warn"]))
        let theirs = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "literal XY and note", "level": "error"]))
        _ = try await store.recordHostNote(own, agent: reader)
        _ = try await store.recordHostNote(theirs, agent: other)
        let noteRows = try await store.hostActivity(kinds: [.note])
        let oldest = try XCTUnwrap(noteRows.last), newest = try XCTUnwrap(noteRows.first)
        let deniedRead = await read(denied, 1), viewerRead = await read(viewer, 1)
        XCTAssertEqual(deniedRead.status, 403)
        XCTAssertEqual(viewerRead.status, 403)
        let first = await read(reader, 1, "/v1/log", ["channel": "agent", "kind": "note", "order": "asc", "limit": 1])
        XCTAssertEqual(try entries(first).first?["id"] as? Int64, oldest.id)
        let cursor = try XCTUnwrap(object(first)["next_cursor"] as? String)
        let second = await read(reader, 2, "/v1/log", ["kind": "note", "order": "asc", "cursor": cursor, "level": "error"])
        XCTAssertEqual(try entries(second).compactMap { $0["id"] as? Int64 }, [newest.id])
        let literal = await read(reader, 3, "/v1/log", ["q": "%_", "since": oldest.id - 1, "until": newest.id])
        XCTAssertEqual(try entries(literal).compactMap { $0["id"] as? Int64 }, [oldest.id])
        let prefix = String(repeating: "🧪", count: 100)
        let unicode = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": prefix + "tail"]))
        _ = try await store.recordHostNote(unicode, agent: reader)
        let capped = await read(reader, 4, "/v1/log", ["kind": "note", "q": prefix + "suffix outside UTF-16 cap"])
        XCTAssertEqual(try entries(capped).first?["message"] as? String, prefix + "tail")
        try await store.setHostLogVisibility(.own)
        let scoped = await read(reader, 5, "/v1/log", ["kind": "note", "agent": "me", "until": newest.id])
        let hidden = await read(reader, 6, "/v1/log/\(newest.id)")
        let adminRead = await read(admin, 1, "/v1/log", ["kind": "note"])
        XCTAssertEqual(try entries(scoped).compactMap { $0["id"] as? Int64 }, [oldest.id])
        XCTAssertEqual(hidden.status, 404)
        XCTAssertEqual(try entries(adminRead).count, 3)
        do { try await first.beforeDelivery?(); XCTFail("All-scope bytes must be withdrawn") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "log_visibility_changed") }

        let renamed = try await authority.administer(.init(method: "PATCH", path: "/v1/agents/\(reader.id)", body: ["name": "Reader now"]), caller: admin)
        XCTAssertEqual(renamed.status, 200)
        let current = try await store.hostActivity(kinds: [.note]).first { $0.id == oldest.id }
        XCTAssertEqual(current?.agent, "Reader")
        XCTAssertEqual(current?.displayAgentName, "Reader now")
        let nativeJSON = try HostActivityExport(entries: [try XCTUnwrap(current)]).data(format: .jsonl)
        XCTAssertTrue(String(decoding: nativeJSON, as: UTF8.self).contains("Reader now"))
        let detail = await read(reader, 7, "/v1/log/\(oldest.id)")
        XCTAssertEqual(try object(detail)["agent"] as? String, "Reader now")

        func mcp(_ seq: Int64, _ body: [String: Any]) async -> HostResponse {
            await dispatcher.dispatch(job(reader, seq, .init(method: "POST", path: "/v1/mcp", body: body)))
        }
        _ = await mcp(8, initialize)
        let tools = await mcp(9, ["jsonrpc": "2.0", "id": 2, "method": "tools/list"])
        let discovered = try XCTUnwrap((object(tools)["result"] as? [String: Any])?["tools"] as? [[String: Any]])
        XCTAssertTrue(Set(discovered.compactMap { $0["name"] as? String }).isSuperset(of: ["log_list", "log_entry", "log_stats", "log_export", "log_note"]))
        let result = await mcp(10, ["jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": ["name": "log_list", "arguments": ["query": ["kind": "note"]]]])
        let contents = try XCTUnwrap((object(result)["result"] as? [String: Any])?["content"] as? [[String: Any]])
        XCTAssertTrue(try XCTUnwrap(contents.first?["text"] as? String).contains("Reader now"))
        try await authority.pause(reader.id, paused: true)
        do { try await result.beforeDelivery?(); XCTFail("Paused scope must be withdrawn") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
    }

    func testActivityVisibilityRejectsStaleSettingsAuthority() async throws {
        let (store, authority, _, _) = try await setup()
        let (writer, _) = try await authority.create(name: "Settings writer", operations: [.configurationWrite], applets: [])
        let reset = try HostSettingChange(.init(method: "DELETE", path: "/v1/settings/log_visibility"), agent: writer)
        XCTAssertEqual(reset.value as? String, "all")
        XCTAssertThrowsError(try HostSettingChange(.init(method: "POST", path: "/v1/settings",
            body: ["field": "log_visibility", "value": true]), agent: writer))
        try await store.setHostLogVisibility(.own, requestedBy: writer)
        try await authority.pause(writer.id, paused: true)
        do { try await store.setHostLogVisibility(.all, requestedBy: writer); XCTFail("Paused caller widened Activity scope") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let persisted = try await store.hostLogVisibility()
        XCTAssertEqual(persisted, .own)
        try await authority.remove(writer.id)
        do { try await store.setHostLogVisibility(.all, requestedBy: writer); XCTFail("Removed caller widened Activity scope") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }

    func testActivityTailWakesCancelsAndPreservesOrdinaryJobCeiling() async throws {
        let (store, authority, dispatcher, _) = try await setup()
        let (reader, _) = try await authority.create(name: "Tail reader", operations: [.log], applets: [])
        let baseline = try await store.hostLogLatestID()
        let tail = HostRequest(method: "GET", path: "/v1/log", query: ["kind": "note", "order": "asc", "since": baseline, "wait": 55])
        let invalid = await dispatcher.dispatch(job(reader, 1, .init(method: "GET", path: "/v1/log", query: ["wait": 1])))
        XCTAssertEqual(invalid.status, 422)
        let a = Task { await dispatcher.dispatch(self.job(reader, 2, tail)) }
        try await Task.sleep(for: .milliseconds(60))
        let b = Task { await dispatcher.dispatch(self.job(reader, 3, tail)) }
        try await Task.sleep(for: .milliseconds(60))
        let excess = await dispatcher.dispatch(job(reader, 4, tail))
        XCTAssertEqual(excess.status, 429)
        a.cancel()
        let cancelled = await a.value
        XCTAssertEqual(cancelled.status, 409)
        let note = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "Wake the tail"]))
        _ = try await store.recordHostNote(note, agent: reader)
        let reply = await b.value
        let rows = try XCTUnwrap(object(reply)["entries"] as? [[String: Any]])
        XCTAssertEqual(rows.first?["message"] as? String, "Wake the tail")
    }

    func testActivityExportPagesAndStatisticsKeepLegacyMeasurementsAbsent() async throws {
        let (store, authority, dispatcher, _) = try await setup()
        let (reader, _) = try await authority.create(name: "Export reader", operations: [.log], applets: [])
        for _ in 0..<502 { try await store.recordHostActivity(agent: reader, operation: .deviceRead, status: 200) }
        let export = await dispatcher.dispatch(job(reader, 1, .init(method: "GET", path: "/v1/log/export", query: ["kind": "request", "format": "jsonl"])))
        XCTAssertEqual(export.status, 200)
        let lines = String(decoding: export.data, as: UTF8.self).split(separator: "\n")
        XCTAssertEqual(lines.count, 502)
        let ids = try lines.map { line -> Int64 in
            let row = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any])
            XCTAssertTrue(row["endpoint"] is NSNull)
            return try XCTUnwrap(row["id"] as? Int64)
        }
        XCTAssertEqual(ids, ids.sorted())
        let legacy = await dispatcher.dispatch(job(reader, 2, .init(method: "GET", path: "/v1/log/stats", query: ["agent": "me"])))
        let stats = try XCTUnwrap(object(legacy)["by_endpoint"] as? [[String: Any]])
        let unreported = try XCTUnwrap(stats.first { $0["key"] as? String == "(unreported)" })
        XCTAssertEqual(unreported["count"] as? Int, 502)
        XCTAssertTrue(unreported["p95_ms"] is NSNull)
        XCTAssertTrue(unreported["bytes_out"] is NSNull)
        let measured = try XCTUnwrap(stats.first { $0["key"] as? String == "GET /v1/log/export" })
        XCTAssertEqual(measured["bytes_out"] as? Int, export.data.count)
        XCTAssertNotNil(measured["p50_ms"] as? Int)
    }

    func testActivityExportEnforcesSerializedRelayLimitWithMCPTextEscaping() async throws {
        let (store, authority, dispatcher, _) = try await setup()
        let (reader, _) = try await authority.create(name: "Large export", operations: [.deviceRead, .log], applets: [])
        let text = String(repeating: "\\", count: 100)
        let data = Dictionary(uniqueKeysWithValues: (0..<17).map { ("value" + String($0), text) })
        let note = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "Escape fixture", "data": data]))
        let start = Date().addingTimeInterval(-7_200)
        for index in 0..<850 {
            _ = try await store.recordHostNote(note, agent: reader, at: start.addingTimeInterval(Double(index) * 2))
        }
        let identity = try RelayHostIdentity.ephemeral(), peer = P256.KeyAgreement.PrivateKey()
        func relayStatus(_ response: HostResponse, _ job: HostJob) throws -> Int? {
            let sealed = try RelayHostConnection.encodedResult(response, identity: identity, peer: peer.publicKey.x963Representation, job: job)
            XCTAssertLessThanOrEqual(sealed.count, 8 * 1024 * 1024)
            let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: sealed) as? [String: Any])
            let aad = RelaySealed.aad(device: job.deviceID, agent: job.agentID, generation: job.generation,
                requestID: job.id, deadline: job.deadline, sequence: job.sequence, direction: "response")
            let enc = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(envelope["enc"] as? String)))
            let ciphertext = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(envelope["ct"] as? String)))
            var recipient = try HPKE.Recipient(privateKey: peer,
                ciphersuite: .init(kem: .P256_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_128),
                info: Data("bridgit-relay-v1".utf8), encapsulatedKey: enc,
                authenticatedBy: P256.KeyAgreement.PublicKey(x963Representation: identity.hpkePublic))
            let opened = try recipient.open(ciphertext, authenticating: aad)
            return (try JSONSerialization.jsonObject(with: opened) as? [String: Any])?["status"] as? Int
        }
        let exported = job(reader, 1, .init(method: "GET", path: "/v1/log/export", query: ["kind": "note"]))
        let raw = await dispatcher.dispatch(exported)
        XCTAssertEqual(raw.status, 200)
        XCTAssertEqual(raw.data.filter { $0 == 0x0a }.count, 850)
        XCTAssertEqual(try relayStatus(raw, exported), 200)
        _ = await dispatcher.dispatch(job(reader, 2, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let wrapped = job(reader, 3, .init(method: "POST", path: "/v1/mcp", body: [
            "jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": ["name": "log_export", "arguments": ["query": ["kind": "note"]]]
        ]))
        let mcp = await dispatcher.dispatch(wrapped)
        XCTAssertEqual(mcp.status, 200)
        XCTAssertGreaterThan(mcp.data.count, raw.data.count)
        XCTAssertEqual(try relayStatus(mcp, wrapped), 413)
    }

    func testMicrophoneOptionsRequireWholeDurationAndReturnOnlyStorage() throws {
        func options(_ body: [String: Any], query: [String: Any] = [:]) throws -> MicrophoneClipRequest {
            try MicrophoneClipRequest(.init(method: "POST", path: "/v1/mic/record", query: query, body: body))
        }
        let plain = try options(["duration_seconds": 120])
        XCTAssertEqual(plain.seconds, 120)
        XCTAssertTrue(plain.returnAudio)
        XCTAssertFalse(plain.transcribe)
        let transcript = try options(["duration_seconds": 1, "return_audio": false, "transcribe": true, "language": "en-US", "store": []])
        XCTAssertFalse(transcript.returnAudio)
        XCTAssertTrue(transcript.transcribe)
        XCTAssertEqual(transcript.language, "en-US")
        XCTAssertNoThrow(try options(["duration_seconds": 5], query: ["store": "none"]))
        for bad: [String: Any] in [[:], ["duration_seconds": true], ["duration_seconds": 1.25],
                                   ["duration_seconds": "1"], ["duration_seconds": 121],
                                   ["duration_seconds": 1, "transcribe": 1],
                                   ["duration_seconds": 1, "return_audio": false],
                                   ["duration_seconds": 1, "store": ["files"]],
                                   ["duration_seconds": 1, "store": ["upload"]],
                                   ["duration_seconds": 1, "allow_cloud": true]] {
            XCTAssertThrowsError(try options(bad))
        }
    }

    func testMicrophoneGrantFreshnessDeadlineAndPrivateIdempotency() async throws {
        actor Capture {
            var calls = 0
            func record() { calls += 1 }
        }
        let (store, authority, _, _) = try await setup()
        let (hearingOnly, _) = try await authority.create(name: "Speech Only", operations: [.speechHear], applets: [])
        let (recorder, _) = try await authority.create(name: "Clip Recorder", operations: [.microphoneRecord], applets: [])
        let capture = Capture()
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() },
            sensor: { request, _ in
                let options = try MicrophoneClipRequest(request)
                await capture.record()
                return try .json(["duration_seconds": options.seconds, "format": "m4a", "data_base64": "fake_clip_only"])
            })
        let request = HostRequest(method: "POST", path: "/v1/mic/record", body: ["duration_seconds": 5], idempotencyKey: "clip-once")
        XCTAssertFalse(HostAPIContract.allowed(hearingOnly).contains { $0.name == "mic_record" })
        XCTAssertTrue(HostAPIContract.allowed(recorder).contains { $0.name == "mic_record" })
        let denied = await dispatcher.dispatch(job(hearingOnly, 1, request))
        XCTAssertEqual(denied.status, 403)
        var stale = job(recorder, 1, .init(method: "POST", path: request.path, body: request.body, idempotencyKey: "stale"))
        stale.createdAt -= 6_000
        let staleReply = await dispatcher.dispatch(stale)
        XCTAssertEqual(try object(staleReply)["error"] as? String, "capture_stale")
        var short = job(recorder, 2, .init(method: "POST", path: request.path, body: request.body, idempotencyKey: "short"))
        short.deadline = short.createdAt + 3_000
        let shortReply = await dispatcher.dispatch(short)
        XCTAssertEqual(shortReply.status, 408)
        let first = await dispatcher.dispatch(job(recorder, 3, request))
        XCTAssertEqual(try object(first)["data_base64"] as? String, "fake_clip_only")
        let repeatRequest = await dispatcher.dispatch(job(recorder, 4, request))
        XCTAssertEqual(try object(repeatRequest)["error"] as? String, "idempotency_result_expired")
        let calls = await capture.calls
        XCTAssertEqual(calls, 1)
        let totals = try await store.hostActivityTotals(since: .distantPast)
        XCTAssertEqual(totals.clips, 1)
        XCTAssertEqual(totals.photos, 0)
    }

    func testLongMicrophoneJobsAndMCPCountOnlyCompletedCaptures() async throws {
        actor Capture {
            var calls = 0
            func record() { calls += 1 }
        }
        let (store, authority, _, _) = try await setup()
        let (recorder, _) = try await authority.create(name: "Recorder", operations: [.deviceRead, .microphoneRecord], applets: [])
        let (reader, _) = try await authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        let capture = Capture()
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() },
            sensor: { request, _ in
                let options = try MicrophoneClipRequest(request)
                await capture.record()
                if request.idempotencyKey == "revoke-before-delivery" { try await authority.pause(recorder.id, paused: true) }
                return try .json(["duration_seconds": options.seconds, "format": "m4a"])
            })
        func timedJob(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest, lifetime: Int64 = 150_000) -> HostJob {
            var timed = job(agent, sequence, request)
            timed.deadline = timed.createdAt + lifetime
            return timed
        }
        let duration: [String: Any] = ["duration_seconds": 120, "transcribe": true, "return_audio": false]
        let clip = HostRequest(method: "POST", path: "/v1/mic/record", body: duration, idempotencyKey: "duration-120")
        let ungranted = await dispatcher.dispatch(timedJob(reader, 1, clip))
        XCTAssertNotEqual(ungranted.status, 200)
        let ordinary = await dispatcher.dispatch(timedJob(recorder, 1, .init(method: "GET", path: "/v1/health")))
        XCTAssertEqual(try object(ordinary)["error"] as? String, "deadline_expired")
        let direct = timedJob(recorder, 1, clip)
        var satellite = direct
        satellite.mode = "direct"
        XCTAssertEqual(satellite.timeoutMilliseconds(for: recorder), 120_000)
        let insufficient = await dispatcher.dispatch(timedJob(recorder, 2, clip, lifetime: 142_000))
        XCTAssertEqual(insufficient.status, 408)
        let long = timedJob(recorder, 3, .init(method: "POST", path: clip.path, body: clip.body, idempotencyKey: "direct-http"))
        let longResponse = await dispatcher.dispatch(long)
        XCTAssertEqual(try object(longResponse)["duration_seconds"] as? Int, 120)
        _ = try await authority.validateDelivery(long, now: long.createdAt + 140_000)

        _ = await dispatcher.dispatch(job(recorder, 4, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        func tool(_ name: String, args: [String: Any]) -> HostRequest {
            .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": ["name": name, "arguments": args]])
        }
        let hint = await dispatcher.dispatch(timedJob(recorder, 5, tool("health", args: ["body": duration])))
        XCTAssertEqual(try object(hint)["error"] as? String, "deadline_expired")
        let badBody = await dispatcher.dispatch(timedJob(recorder, 6, tool("mic_record", args: ["idempotency_key": "bad", "body": ["duration_seconds": 121]])))
        XCTAssertEqual(try object(badBody)["error"] as? String, "deadline_expired")
        let call = tool("mic_record", args: ["idempotency_key": "tool-clip", "body": duration])
        let success = await dispatcher.dispatch(timedJob(recorder, 7, call))
        XCTAssertEqual((try object(success)["result"] as? [String: Any])?["isError"] as? Bool, false)
        XCTAssertEqual(success.headers["Cache-Control"], "no-store")
        let retry = await dispatcher.dispatch(timedJob(recorder, 8, call))
        XCTAssertEqual((try object(retry)["result"] as? [String: Any])?["isError"] as? Bool, true)
        let revoked = await dispatcher.dispatch(timedJob(recorder, 9, tool("mic_record", args: ["idempotency_key": "revoke-before-delivery", "body": duration])))
        XCTAssertEqual(revoked.status, 403)
        let totals = try await store.hostActivityTotals(since: .distantPast)
        let calls = await capture.calls
        XCTAssertEqual(totals.clips, 2)
        XCTAssertEqual(calls, 3)
    }

    func testSavedPhotoRouteAcceptsBothStoreFormatsAndKeepsMediaGrant() throws {
        let operation = try XCTUnwrap(HostAPIContract.operations.first { $0.name == "applets_photo" })
        let properties = operation.schema["properties"] as! [String: Any]
        XCTAssertEqual((properties["photo"] as? [String: Any])?["pattern"] as? String, "^[a-z0-9]{1,40}$")

        for id in [String(repeating: "a", count: 32), String(repeating: "b", count: 32) + "x0", "retainedphoto1"] {
            let route = HostRequest(method: "GET", path: "/v1/applets/installed/library-test/photos/" + id).route
            XCTAssertEqual(route?.0, .appletsMediaRead)
            XCTAssertEqual(route?.1, "library-test")
            XCTAssertEqual(try operation.request(["applet": "library-test", "photo": id]).route?.0, .appletsMediaRead)
        }
        for id in ["../private", "%2e%2e", "photo.jpg", String(repeating: "a", count: 41)] {
            XCTAssertNil(HostRequest(method: "GET", path: "/v1/applets/installed/library-test/photos/" + id).route)
            XCTAssertThrowsError(try operation.request(["applet": "library-test", "photo": id]))
        }
    }

    func testMaintainedProviderKeepsAdmissionIdempotencyAndRevocation() async throws {
        actor Calls { var count = 0; func called() { count += 1 } }
        let (store, authority, _, denied) = try await setup()
        let (manager, _) = try await authority.create(name: "Manager", operations: [.appletsManage], applets: [])
        let id = "library-" + String(repeating: "a", count: 56), calls = Calls()
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() },
            additionalApplets: { agent in agent.allowsApplet(id, operation: .appletsRead) ? [["id": id, "source": "maintained_library"]] : [] },
            additionalAppletAction: { request, _ in
                guard request.method == "DELETE", request.path == "/v1/applets/installed/" + id else { return nil }
                await calls.called()
                return try .json(["removed": true, "id": id])
            })
        let listing = try object(await dispatcher.dispatch(job(manager, 1, .init(method: "GET", path: "/v1/applets/installed"))))
        XCTAssertEqual((listing["installed"] as? [[String: Any]])?.first?["id"] as? String, id)
        let request = HostRequest(method: "DELETE", path: "/v1/applets/installed/" + id, idempotencyKey: "remove-once")
        let forbidden = await dispatcher.dispatch(job(denied, 1, request))
        XCTAssertEqual(forbidden.status, 403)
        let first = await dispatcher.dispatch(job(manager, 2, request))
        let retry = await dispatcher.dispatch(job(manager, 3, request))
        XCTAssertEqual(first.status, 200); XCTAssertEqual(retry.data, first.data)
        let before = await calls.count; XCTAssertEqual(before, 1)
        try await authority.pause(manager.id, paused: true)
        let paused = await dispatcher.dispatch(job(manager, 4, request))
        XCTAssertEqual(paused.status, 403)
        let after = await calls.count; XCTAssertEqual(after, 1)
        let activity = try await store.hostActivity()
        XCTAssertTrue(activity.contains { $0.operation == HostOperation.appletsManage.rawValue && $0.status == 200 })
    }

    func testNestedMCPFailureRecordsExecutedRouteAndRedactedDeclaredParams() async throws {
        let (store, authority, dispatcher, agent) = try await setup()
        _ = await dispatcher.dispatch(job(agent, 1, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let call: [String: Any] = ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": [
            "name": "interactions_list", "arguments": ["query": ["status": "invalid-status", "undeclared_secret": "discard-this"]]
        ]]
        let mcpJob = job(agent, 2, .init(method: "POST", path: "/v1/mcp", body: call))
        let reply = await dispatcher.dispatch(mcpJob)
        XCTAssertEqual(reply.status, 200)
        XCTAssertEqual((try object(reply)["result"] as? [String: Any])?["isError"] as? Bool, true)
        let rows = try await store.hostActivity(kinds: [.request])
        XCTAssertEqual(rows.count, 2, "One audit event per MCP operation or handshake.")
        let entry = try XCTUnwrap(rows.first)
        let facts = try XCTUnwrap(entry.requestFacts)
        let detail = HostActivityDetail(entry)
        XCTAssertEqual(entry.operation, HostOperation.interactionsRead.rawValue)
        XCTAssertEqual(entry.status, 422)
        XCTAssertEqual(facts.method, "GET")
        XCTAssertEqual(facts.endpoint, "/v1/interactions")
        XCTAssertEqual(facts.requestID, mcpJob.id)
        XCTAssertEqual(facts.result, "invalid_parameter")
        XCTAssertNotNil(facts.durationMS)
        XCTAssertGreaterThan(facts.bytesOut ?? 0, 0)
        XCTAssertNil(facts.bytesIn)
        XCTAssertEqual(detail.text(for: "params"), "{\"status\":\"invalid-status\"}")
        XCTAssertFalse(try detail.json().contains("discard-this"))

        let (uploader, _) = try await authority.create(name: "Fixture uploader", operations: [.files], applets: [])
        let credential = "rly_" + String(repeating: "Q", count: 43)
        let wireBody = try JSONSerialization.data(withJSONObject: ["name": credential, "mime": "image/jpeg", "data_base64": "secret-image-bytes"])
        let wire = TailnetHTTPRequest(method: "POST", target: "/v1/files", headers: ["idempotency-key": "fixture-upload"], body: wireBody)
        let uploadJob = job(uploader, 1, try wire.hostRequest)
        let upload = await dispatcher.dispatch(uploadJob)
        XCTAssertGreaterThanOrEqual(upload.status, 400)
        let writes = try await store.hostActivity(agentFilter: .identified(uploader.id), kinds: [.request])
        let latest = try XCTUnwrap(writes.first)
        XCTAssertEqual(latest.requestFacts?.bytesIn, wireBody.count)
        XCTAssertEqual(latest.requestFacts?.endpoint, "/v1/files")
        let redacted = HostActivityDetail(latest).text(for: "params")
        XCTAssertTrue(redacted.contains("rly_[redacted]"))
        XCTAssertTrue(redacted.contains("[18 chars]"))
        XCTAssertFalse(redacted.contains(credential))
        XCTAssertFalse(redacted.contains("secret-image-bytes"))
    }

    func testActivityRecordsDispatchedOutcomeWithoutRequestData() async throws {
        let (store, _, dispatcher, agent) = try await setup()
        let valid = await dispatcher.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/interactions")))
        let invalid = await dispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/interactions", query: ["secret_fixture": "do-not-log"])))
        let rows = try await store.hostActivity(kinds: [.request])
        XCTAssertEqual([valid.status, invalid.status], [200, 422])
        XCTAssertEqual(rows.map(\.status), [422, 200])
        XCTAssertEqual(rows.map(\.agent), ["Fixture", "Fixture"])
        XCTAssertEqual(rows.map(\.operation), ["interactions.read", "interactions.read"])
        XCTAssertGreaterThan(rows[0].id, rows[1].id)
    }

    func testActivityKeepsAuthenticatedIdentityAcrossDuplicateNamesAndRename() async throws {
        let (store, authority, dispatcher, _) = try await setup()
        let grants: Set<HostOperation> = [.interactionsRead, .configurationWrite]
        let (first, _) = try await authority.create(name: "Same name", operations: grants, applets: [])
        let (second, _) = try await authority.create(name: "Same name", operations: grants, applets: [])
        let before = await dispatcher.dispatch(job(first, 1, .init(method: "GET", path: "/v1/interactions")))
        let other = await dispatcher.dispatch(job(second, 1, .init(method: "GET", path: "/v1/interactions")))
        let rename = try await authority.administer(.init(method: "PATCH", path: "/v1/agents/me", body: ["name": "Renamed"]), caller: first)
        let after = await dispatcher.dispatch(job(first, 3, .init(method: "GET", path: "/v1/interactions")))
        XCTAssertEqual([before.status, other.status, rename.status, after.status], [200, 200, 200, 200])
        let firstRows = try await store.hostActivity(agentFilter: .identified(first.id), kinds: [.request])
        let secondRows = try await store.hostActivity(agentFilter: .identified(second.id), kinds: [.request])
        XCTAssertEqual(firstRows.map(\.agentID), [first.id, first.id])
        XCTAssertEqual(firstRows.first?.agent, "Renamed")
        XCTAssertEqual(secondRows.map(\.agentID), [second.id])
        let firstAgentEvents = try await store.hostActivity(agentFilter: .identified(first.id), kinds: [.agent])
        XCTAssertEqual(firstAgentEvents.map(\.operation), ["Updated Renamed", "Added Same name"])
        let secondAgentEvents = try await store.hostActivity(agentFilter: .identified(second.id), kinds: [.agent])
        XCTAssertEqual(secondAgentEvents.map(\.operation), ["Added Same name"])
        let agentExport = try await store.hostActivityExport(agentFilter: .identified(first.id), kinds: [.agent], pageSize: 1)
        XCTAssertEqual(agentExport.entries.map(\.id), firstAgentEvents.map(\.id))
        let identities = try await store.hostActivityAgentIdentities()
        XCTAssertEqual(identities.first(where: { $0.id == .identified(first.id) })?.name, "Renamed")
        XCTAssertEqual(identities.first(where: { $0.id == .identified(second.id) })?.name, "Same name")
        let exported = try await store.hostActivityExport(agentFilter: .identified(first.id), kinds: [.request])
        XCTAssertEqual(exported.count, 2)
        let json = String(decoding: try exported.data(format: .jsonl), as: UTF8.self)
        XCTAssertTrue(json.contains(first.id)); XCTAssertFalse(json.contains(second.id))
    }

    func testRelayServiceEventsAreTypedAndFilterable() async throws {
        let (store, _, _, _) = try await setup()
        try await store.recordRelayState(.connected)
        try await store.recordRelayState(.failed)
        let events = try await store.hostActivity(kinds: [.service])
        XCTAssertEqual(events.map(\.operation), ["Relay host: failed", "Relay host: connected"])
        XCTAssertEqual(events.map(\.kind), [.service, .service])
        let fakeHTTPMatch = try await store.hostActivity(query: "503", kinds: [.service])
        XCTAssertTrue(fakeHTTPMatch.isEmpty, "Service failures have no HTTP response status")
        let failed = try await store.hostActivity(errorsOnly: true, kinds: [.service])
        XCTAssertEqual(failed.map(\.operation), ["Relay host: failed"])
        XCTAssertEqual(HostActivityDetail(failed[0]).text(for: "level"), "error")
        XCTAssertEqual(HostActivityDetail(failed[0]).text(for: "http"), "null")
        XCTAssertEqual(HostActivityDetail(failed[0]).text(for: "channel"), "diagnostic")
        let snapshot = try await store.hostActivityExport(kinds: [.service])
        let jsonl = String(decoding: try snapshot.data(format: .jsonl), as: UTF8.self)
        let csv = String(decoding: try snapshot.data(format: .csv), as: UTF8.self)
        XCTAssertTrue(jsonl.contains("\"channel\":\"diagnostic\""))
        XCTAssertTrue(csv.contains(",diagnostic,error,service,"))
    }

    func testActivityTotalsUseEntireTimeWindowAndSurviveReopen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.cameraSnapshot], applets: [])
        let cutoff = Date(timeIntervalSince1970: 100_000)
        try await store.recordHostActivity(agent: agent, operation: .deviceRead, status: 500, at: cutoff.addingTimeInterval(-1))
        try await store.recordHostActivity(agent: agent, operation: .cameraSnapshot, status: 200, at: cutoff)
        try await store.recordHostActivity(agent: agent, operation: .cameraSnapshot, status: 503, at: cutoff.addingTimeInterval(1))
        let reopened = try AppletStore(directory: directory)
        let latest = try await reopened.hostActivity(limit: 1, kinds: [.request])
        XCTAssertEqual(latest.map(\.status), [503])
        let older = try await reopened.hostActivity(limit: 1, before: latest[0].id, query: "CaMeRa", agentFilter: .identified(agent.id))
        XCTAssertEqual(older.map(\.status), [200])
        let oldError = try await reopened.hostActivity(limit: 1, before: latest[0].id, query: "500", errorsOnly: true)
        XCTAssertEqual(oldError.map(\.operation), [HostOperation.deviceRead.rawValue])
        let literal = try await reopened.hostActivity(query: "%")
        XCTAssertTrue(literal.isEmpty)
        let names = try await reopened.hostActivityAgentIdentities()
        XCTAssertEqual(names.filter { $0.id == .identified(agent.id) }.map(\.name), [agent.name])
        let totals = try await reopened.hostActivityTotals(since: cutoff)
        XCTAssertEqual(totals.requests, 2)
        XCTAssertEqual(totals.photos, 1)
        XCTAssertEqual(totals.errors, 1)
    }

    func testOpenAPIDiscoveryAndToolsShareCallerScopedOperations() async throws {
        let (_, _, dispatcher, agent) = try await setup()
        let discovery = try object(await dispatcher.dispatch(job(agent, 1, .init(method: "GET", path: "/v1/discover"))))
        let entries = discovery["capabilities"] as! [[String: Any]]
        XCTAssertEqual(Set(entries.compactMap { $0["runtime_id"] as? String }), ["device", "interactions", "feedback"])
        let openapi = try object(await dispatcher.dispatch(job(agent, 2, .init(method: "GET", path: "/v1/openapi.json"))))
        let paths = openapi["paths"] as! [String: Any]
        XCTAssertNotNil(paths["/v1/interactions"]); XCTAssertNil(paths["/v1/camera/snapshot"])
        XCTAssertNotNil(paths["/v1/feedback"])
        XCTAssertNil(paths["/v1/feedback/{id}/status"])
        _ = await dispatcher.dispatch(job(agent, 3, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let list = try object(await dispatcher.dispatch(job(agent, 4, .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 2, "method": "tools/list"]))))
        let tools = (list["result"] as! [String: Any])["tools"] as! [[String: Any]]
        let names = Set(tools.compactMap { $0["name"] as? String })
        XCTAssertEqual(names, Set(HostAPIContract.allowed(agent).map(\.name)))
        XCTAssertTrue(names.contains("feedback_create"))
        XCTAssertFalse(names.contains("feedback_status"))
        XCTAssertFalse(names.contains("camera_snapshot"))
    }
    func testMCPCallKeepsGrantAndIdempotencyChecks() async throws {
        let (store, _, dispatcher, agent) = try await setup()
        _ = await dispatcher.dispatch(job(agent, 1, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let body: [String: Any] = ["jsonrpc": "2.0", "id": "create", "method": "tools/call", "params": ["name": "interactions_send", "arguments": ["idempotency_key": "once", "body": ["kind": "notice", "title": "Fixture", "no_takeover": true]]]]
        let first = try object(await dispatcher.dispatch(job(agent, 2, .init(method: "POST", path: "/v1/mcp", body: body))))
        let retry = try object(await dispatcher.dispatch(job(agent, 3, .init(method: "POST", path: "/v1/mcp", body: body))))
        XCTAssertEqual(try JSONSerialization.data(withJSONObject: first, options: [.sortedKeys]), try JSONSerialization.data(withJSONObject: retry, options: [.sortedKeys]))
        let rows = try await store.interactions(); XCTAssertEqual(rows.count, 1)
        let denied: [String: Any] = ["jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": ["name": "camera_snapshot", "arguments": ["idempotency_key": "camera"]]]
        let result = try object(await dispatcher.dispatch(job(agent, 4, .init(method: "POST", path: "/v1/mcp", body: denied))))
        XCTAssertEqual((result["error"] as? [String: Any])?["code"] as? Int, -32602)
    }
    func testMCPCancellationReleasesWaitingTool() async throws {
        let (store, _, dispatcher, agent) = try await setup()
        let card = try await store.createInteraction(owner: agent.id + ":" + agent.generation, input: ["kind": "notice", "title": "Fixture", "no_takeover": true])
        _ = await dispatcher.dispatch(job(agent, 1, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let wait: [String: Any] = ["jsonrpc": "2.0", "id": "wait", "method": "tools/call", "params": ["name": "interactions_get", "arguments": ["id": card["interaction_id"]!, "query": ["wait": 55]]]]
        let task = Task { await dispatcher.dispatch(job(agent, 2, .init(method: "POST", path: "/v1/mcp", body: wait))) }
        defer { task.cancel() }
        let deadline = ContinuousClock.now + .seconds(2)
        while await dispatcher.runningMCPRequests() == 0, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(1))
        }
        let count = await dispatcher.runningMCPRequests()
        guard count == 1 else { return XCTFail("MCP wait did not register before the deadline") }
        let cancel = await dispatcher.dispatch(job(agent, 3, .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "method": "notifications/cancelled", "params": ["requestId": "wait"]])))
        XCTAssertEqual(cancel.status, 202); XCTAssertTrue(cancel.data.isEmpty)
        let result = try object(await task.value)
        XCTAssertEqual((result["error"] as? [String: Any])?["code"] as? Int, -32800)
    }
    func testMCPBadArgumentsAndNotificationsDoNotAcquireActions() async throws {
        let (_, _, dispatcher, agent) = try await setup()
        let before = try object(await dispatcher.dispatch(job(agent, 1, .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 2, "method": "tools/list"]))))
        XCTAssertEqual((before["error"] as? [String: Any])?["code"] as? Int, -32002)
        _ = await dispatcher.dispatch(job(agent, 2, .init(method: "POST", path: "/v1/mcp", body: initialize)))
        let bad = try object(await dispatcher.dispatch(job(agent, 3, .init(method: "POST", path: "/v1/mcp", body: ["jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": ["name": "interactions_list", "arguments": 1]]))))
        XCTAssertEqual((bad["error"] as? [String: Any])?["code"] as? Int, -32602)
        let get = await dispatcher.dispatch(job(agent, 4, .init(method: "GET", path: "/v1/mcp")))
        XCTAssertEqual(get.status, 405)
    }
    func testWriteToolSchemasRequireTheirRequestBodies() throws {
        for name in ["interactions_send", "applets_rows_add", "applets_rows_update"] {
            let operation = try XCTUnwrap(HostAPIContract.operations.first { $0.name == name })
            XCTAssertTrue((operation.schema["required"] as? [String] ?? []).contains("body"))
            var arguments: [String: Any] = ["idempotency_key": "fixture"]
            for variable in operation.variables { arguments[variable] = "fixture" }
            XCTAssertThrowsError(try operation.request(arguments))
        }
    }
    func testInteractionContractExamplesMatchRuntimeValidation() async throws {
        let (_, _, _, agent) = try await setup()
        let base: [String: Any] = ["kind": "notice", "title": "Fixture", "no_takeover": true]
        var cases: [[String: Any]] = []
        for (patch, expected): ([String: Any], Bool) in [
            ([:], true), (["speak": false], true), (["speak": true], false), (["priority": "urgent"], false),
            (["no_takeover": false], false), (["no_takeover": 1], false), (["only_if": [:]], false),
            (["kind": "html", "html": "<p>Fixture</p>"], true),
            (["kind": "question", "controls": [["id": "yes", "type": "button", "label": "Yes"]]], true),
            (["kind": "question", "controls": []], false), (["expires_in_seconds": 30], false),
            (["controls": [["id": "x", "type": "button", "label": "Not a notice link"]]], false)
        ] {
            var input = base; input.merge(patch) { _, value in value }
            let valid: Bool
            do { _ = try InteractionValidation.create(input, now: Date()); valid = true } catch { valid = false }
            XCTAssertEqual(valid, expected)
            cases.append(["input": input, "valid": valid])
        }
        if let directory = ProcessInfo.processInfo.environment["BRIDGIT_CONTRACT_EXPORT_DIR"] {
            let url = URL(fileURLWithPath: directory, isDirectory: true)
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            try JSONSerialization.data(withJSONObject: ["schema": InteractionValidation.schema, "cases": cases, "tools": HostAPIContract.operations.map { ["name": $0.name, "schema": $0.schema] }], options: [.prettyPrinted, .sortedKeys]).write(to: url.appendingPathComponent("interaction-cases.json"))
            try JSONSerialization.data(withJSONObject: HostAPIContract.openAPI(agent), options: [.prettyPrinted, .sortedKeys]).write(to: url.appendingPathComponent("openapi.json"))
        }
    }

}
