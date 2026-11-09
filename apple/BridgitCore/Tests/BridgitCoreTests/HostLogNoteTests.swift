import Foundation
import SQLite3
import XCTest
@testable import BridgitCore

final class HostLogNoteTests: XCTestCase {
    private func fixture() throws -> (URL, AppletStore, HostAuthority, HostDispatcher) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        return (directory, store, authority, dispatcher(store, authority))
    }

    private func dispatcher(_ store: AppletStore, _ authority: HostAuthority) -> HostDispatcher {
        HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                       profile: { _ in throw CancellationError() })
    }

    private func request(_ message: String, key: String? = nil, data: [String: Any]? = nil, level: String? = nil) -> HostRequest {
        var body: [String: Any] = ["message": message]
        body["data"] = data; body["level"] = level
        return .init(method: "POST", path: "/v1/log", body: body, idempotencyKey: key)
    }

    private func job(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation,
                     deviceID: "fixture", deadline: now + 20_000, createdAt: now, request: request)
    }

    private func object(_ data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testScopedNotesPersistRedactedDetailAndExportsAndReplayAfterReopen() async throws {
        let (directory, store, authority, host) = try fixture()
        let (author, _) = try await authority.create(name: "Authorized author", operations: [.log], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let token = "brg_" + String(repeating: "S", count: 43)
        let relay = "rly_" + String(repeating: "U", count: 43)
        let message = "Checked milk, \"low\"\n" + token
        let note = request(message, key: "notes-once", data: [
            "api_token": "never-retain", "photo_base64": "raw-media-fixture",
            "count": 2, "checked": true, "other": relay, "nested": ["level": "shelf", "deep": ["key": "never-retain"]],
            "list": [relay, ["key": "never-retain"], 3]
        ], level: "error")
        let first = await host.dispatch(job(author, 1, note))
        XCTAssertEqual(first.status, 201)
        XCTAssertEqual(try object(first.data)["channel"] as? String, "agent")
        XCTAssertEqual(try object(first.data)["ok"] as? Bool, true)
        let changed = await host.dispatch(job(author, 2, request("Changed", key: "notes-once")))
        XCTAssertEqual(changed.status, 409)

        let reopened = try AppletStore(directory: directory)
        let current = HostAuthority(store: reopened, deviceID: "fixture")
        await current.setState(enabled: true, awake: true, foreground: true)
        var equivalent = note
        equivalent.body["ignored"] = "ignored future field"
        equivalent.query["ignored"] = "ignored query field"
        let replay = await dispatcher(reopened, current).dispatch(job(author, 3, equivalent))
        XCTAssertEqual(replay.status, 201)
        XCTAssertEqual(replay.data, first.data)

        let notes = try await reopened.hostActivity(agentFilter: .identified(author.id), errorsOnly: true, kinds: [.note])
        XCTAssertEqual(notes.count, 1)
        let entry = try XCTUnwrap(notes.first)
        XCTAssertEqual(entry.agentID, author.id)
        XCTAssertEqual(entry.agent, "Authorized author")
        XCTAssertEqual(entry.noteLevel, .error)
        let detail = HostActivityDetail(entry)
        XCTAssertEqual(detail.text(for: "level"), "error")
        XCTAssertEqual(detail.text(for: "http"), "null")
        XCTAssertEqual(detail.text(for: "message"), "Checked milk, \"low\"\nbrg_[redacted]")
        let payload = try object(Data(detail.text(for: "data").utf8))
        XCTAssertEqual(payload["api_token"] as? String, "[redacted]")
        XCTAssertEqual(payload["photo_base64"] as? String, "[17 chars]")
        XCTAssertEqual(payload["count"] as? Int, 2)
        XCTAssertEqual(payload["checked"] as? Bool, true)
        XCTAssertEqual(payload["other"] as? String, "rly_[redacted]")
        XCTAssertEqual((payload["nested"] as? [String: String])?["deep"], "[object]")
        XCTAssertEqual((payload["list"] as? [Any])?.first as? String, "rly_[redacted]")

        let export = try await reopened.hostActivityExport(kinds: [.note])
        let json = try export.data(format: .jsonl)
        XCTAssertEqual(try object(json)["agent_id"] as? String, author.id)
        let csv = String(decoding: try export.data(format: .csv), as: UTF8.self)
        XCTAssertTrue(csv.contains(",agent,error,note,Authorized author,"))
        XCTAssertTrue(csv.contains("\"Checked milk, \"\"low\"\"\nbrg_[redacted]\""))
        for secret in [token, relay, "never-retain", "raw-media-fixture"] {
            XCTAssertFalse(csv.contains(secret))
            XCTAssertFalse(String(decoding: json, as: UTF8.self).contains(secret))
        }
        let snapshot = try await store.hostActivity(kinds: [.note], systemOnly: true)
        XCTAssertTrue(snapshot.isEmpty)
    }

    func testHTTPAndMCPDiscoveryKeepLogGrantIndependentAndRejectPausedOrRevokedAuthors() async throws {
        let (_, store, authority, host) = try fixture()
        let (author, _) = try await authority.create(name: "Author", operations: [.deviceRead, .log], applets: [])
        let (reader, _) = try await authority.create(name: "Reader", operations: [.deviceRead], applets: [])
        let (appletManager, _) = try await authority.create(name: "Applet manager", operations: [.deviceRead, .appletsManage], applets: [])
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        await authority.setState(enabled: true, awake: true, foreground: true)
        for denied in [reader, appletManager, viewer] {
            XCTAssertFalse(HostAPIContract.allowed(denied).contains { $0.name == "log_note" })
            let response = await host.dispatch(job(denied, 1, request("Unapproved")))
            XCTAssertEqual(response.status, 403)
        }
        let paths = try XCTUnwrap(HostAPIContract.openAPI(author)["paths"] as? [String: Any])
        let log = try XCTUnwrap((paths["/v1/log"] as? [String: Any])?["post"] as? [String: Any])
        let parameters = try XCTUnwrap(log["parameters"] as? [[String: Any]])
        XCTAssertEqual(parameters.first?["required"] as? Bool, false)
        let tool = try XCTUnwrap(HostAPIContract.tools(author).first { $0["name"] as? String == "log_note" })
        XCTAssertEqual((tool["inputSchema"] as? [String: Any])?["required"] as? [String], ["body"])

        let initBody: [String: Any] = ["jsonrpc": "2.0", "id": 1, "method": "initialize", "params": [
            "protocolVersion": "2025-11-25", "capabilities": [:], "clientInfo": ["name": "Fixture", "version": "1"]
        ]]
        _ = await host.dispatch(job(author, 1, .init(method: "POST", path: "/v1/mcp", body: initBody)))
        let call: [String: Any] = ["jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": [
            "name": "log_note", "arguments": ["body": ["message": "MCP no-key note"]]
        ]]
        let mcp = await host.dispatch(job(author, 2, .init(method: "POST", path: "/v1/mcp", body: call)))
        let result = try XCTUnwrap(try object(mcp.data)["result"] as? [String: Any])
        XCTAssertEqual(result["isError"] as? Bool, false)

        try await authority.pause(author.id, paused: true)
        let paused = await host.dispatch(job(author, 3, request("Paused")))
        XCTAssertEqual(paused.status, 403)
        try await authority.remove(author.id)
        let removed = await host.dispatch(job(author, 4, request("Removed")))
        XCTAssertEqual(removed.status, 403)
        let entries = try await store.hostActivity(kinds: [.note])
        XCTAssertEqual(entries.map(\.operation), ["MCP no-key note"])
        XCTAssertEqual(entries.first?.noteLevel, .info)
    }

    func testAdministratorCanGrantAndRevokeNotesThroughAgentConfiguration() async throws {
        let (_, store, authority, host) = try fixture()
        let (admin, _) = try await authority.create(name: "Owner administrator", operations: [.administrator], applets: [])
        let (reader, _) = try await authority.create(name: "Agent", operations: [.deviceRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        func change(_ operations: [String]) async throws -> HostAgent {
            _ = try await authority.administer(.init(method: "PATCH", path: "/v1/agents/" + reader.id,
                body: ["operations": operations]), caller: admin)
            let current = try await authority.agent(reader.id)
            return try XCTUnwrap(current)
        }
        let writer = try await change(["device.read", "log"])
        XCTAssertTrue(writer.allows(.log))
        let written = await host.dispatch(job(writer, 1, request("Granted note")))
        XCTAssertEqual(written.status, 201)
        let removed = try await change(["device.read"])
        XCTAssertFalse(removed.allows(.log))
        let denied = await host.dispatch(job(removed, 2, request("Revoked note")))
        XCTAssertEqual(denied.status, 403)
        let rows = try await store.hostActivity(kinds: [.note])
        XCTAssertEqual(rows.map(\.operation), ["Granted note"])
    }

    func testBoundsCountUTF8AndRejectInvalidShapesBeforeTheyReachStorage() throws {
        let exact = String(repeating: "é", count: 512)
        XCTAssertEqual(try HostLogNote(request(exact)).message, exact)
        XCTAssertThrowsError(try HostLogNote(request(exact + "a")))
        XCTAssertThrowsError(try HostLogNote(request(" \n ")))
        XCTAssertThrowsError(try HostLogNote(request("Note", level: "fatal")))
        XCTAssertThrowsError(try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "Note", "data": ["value"]])))
        XCTAssertNoThrow(try HostLogNote(request("Note", data: ["x": String(repeating: "a", count: 4088)])))
        XCTAssertThrowsError(try HostLogNote(request("Note", data: ["x": String(repeating: "é", count: 2045)])))
        XCTAssertEqual(try HostLogNote(request("Message\0suffix")).message, "Message�suffix")
    }

    func testRedactorKeepsUTF16CharacterBudgetsAndBoundsEncodedData() throws {
        let atBoundary = String(repeating: "é", count: 200)
        let keyAtBoundary = String(repeating: "é", count: 40)
        let note = try HostLogNote(request("Note", data: [
            "accented": atBoundary,
            "longer": atBoundary + "é",
            "emoji": String(repeating: "🧪", count: 101),
            keyAtBoundary + "é": "value"
        ]))
        let data = try object(Data(note.data.utf8))
        XCTAssertEqual(data["accented"] as? String, atBoundary)
        XCTAssertEqual(data["longer"] as? String, String(repeating: "é", count: 199) + "…")
        XCTAssertEqual(data["emoji"] as? String, String(repeating: "🧪", count: 99) + "…")
        XCTAssertEqual(data[String(repeating: "é", count: 39) + "…"] as? String, "value")
        XCTAssertLessThanOrEqual(note.data.utf8.count, HostLogNote.dataBytes)
    }

    func testKeyedRateRefusalReplaysAndMissingReservationRollsBackTheNote() async throws {
        let (directory, store, authority, host) = try fixture()
        let (author, _) = try await authority.create(name: "Author", operations: [.log], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let note = try HostLogNote(request("Saved fixture"))
        do {
            _ = try await store.recordHostNote(note, agent: author, reservation: ("missing", "fingerprint"))
            XCTFail("A note with an unavailable replay reservation must roll back.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "outcome_unknown") }
        let empty = try await store.hostActivity(kinds: [.note])
        XCTAssertTrue(empty.isEmpty)
        for _ in 0..<60 { _ = try await store.recordHostNote(note, agent: author) }
        let limited = request("Rate limited", key: "rate-limit-fixture")
        let first = await host.dispatch(job(author, 1, limited))
        XCTAssertEqual(first.status, 429)
        XCTAssertNotNil(first.headers["Retry-After"])

        let reopened = try AppletStore(directory: directory)
        let current = HostAuthority(store: reopened, deviceID: "fixture")
        await current.setState(enabled: true, awake: true, foreground: true)
        let retry = await dispatcher(reopened, current).dispatch(job(author, 2, limited))
        XCTAssertEqual(retry.status, 429)
        XCTAssertEqual(retry.data, first.data)
        XCTAssertEqual(retry.headers["Retry-After"], first.headers["Retry-After"])
        let notes = try await reopened.hostActivity(kinds: [.note])
        XCTAssertEqual(notes.count, 60)
    }

    func testRetentionPrunesOldAndExcessActivityButPreservesDiagnosticsAndRateLedger() async throws {
        let (directory, store, authority, _) = try fixture()
        let (agent, _) = try await authority.create(name: "Notes fixture", operations: [.log], applets: [])
        let defaultLimits = try await store.hostActivityRetention()
        XCTAssertEqual(defaultLimits, [.entries: 0, .days: 0])
        try await store.setHostActivityRetention([.entries: 2, .days: 1])

        let now = Date()
        let old = now.addingTimeInterval(-2 * 86_400)
        let note = try HostLogNote(request("Retention fixture"))
        try await store.recordRelayState(.connected, at: old)
        try await store.recordCallbackAttempt(agent: agent, host: "example.com", eventID: 1, eventType: "fixture", status: 200, at: old)
        for _ in 0..<60 {
            let reply = try await store.recordHostNote(note, agent: agent, at: now)
            XCTAssertEqual(reply.status, 201)
        }
        // Writes count every channel. Land exactly on the pinned 200-entry
        // maintenance boundary, with one recent and one stale audit at the end.
        let prior = try await store.hostLogLatestID()
        XCTAssertLessThan(prior, 198)
        for _ in prior..<198 { try await store.recordHostActivity(agent: agent, operation: .log, status: 200, at: now) }
        try await store.recordHostActivity(agent: agent, operation: .log, status: 200, at: old)
        try await store.recordHostActivity(agent: agent, operation: .log, status: 200, at: now)
        let kept = try await store.hostActivity()
        XCTAssertEqual(kept.prefix(2).map(\.id), [200, 198])
        XCTAssertEqual(kept.count, 4)
        XCTAssertFalse(kept.contains { $0.kind == .note })
        XCTAssertEqual(kept.filter { $0.at < now.addingTimeInterval(-86_400) }.count, 2)

        let reopened = try AppletStore(directory: directory)
        let reopenedLimits = try await reopened.hostActivityRetention()
        XCTAssertEqual(reopenedLimits, [.entries: 2, .days: 1])
        let denied = try await reopened.recordHostNote(note, agent: agent, at: now.addingTimeInterval(1))
        XCTAssertEqual(denied.status, 429)
        let expired = try await reopened.recordHostNote(note, agent: agent, at: now.addingTimeInterval(60))
        XCTAssertEqual(expired.status, 201)
    }

    func testVersionEightMigrationRetainsRecentNoteAdmissionHistory() async throws {
        let (directory, store, authority, _) = try fixture()
        let (agent, _) = try await authority.create(name: "Migration fixture", operations: [.log], applets: [])
        let now = Date()
        let note = try HostLogNote(request("Pre-upgrade note"))
        for _ in 0..<60 { _ = try await store.recordHostNote(note, agent: agent, at: now) }

        func restorePriorSchema() throws {
            var db: OpaquePointer?
            let path = directory.appendingPathComponent("applets.sqlite").path
            guard sqlite3_open(path, &db) == SQLITE_OK else { throw CocoaError(.fileReadUnknown) }
            defer { sqlite3_close(db) }
            let ddl = "DROP TRIGGER host_activity_retention; DROP TABLE host_note_rate; PRAGMA user_version=8;"
            XCTAssertEqual(sqlite3_exec(db, ddl, nil, nil, nil), SQLITE_OK)
        }
        try restorePriorSchema()
        let migrated = try AppletStore(directory: directory)
        let denied = try await migrated.recordHostNote(note, agent: agent, at: now.addingTimeInterval(1))
        XCTAssertEqual(denied.status, 429)
        let accepted = try await migrated.recordHostNote(note, agent: agent, at: now.addingTimeInterval(60))
        XCTAssertEqual(accepted.status, 201)
    }

    func testRollingLimitSurvivesReopenAndUsesStableCallerID() async throws {
        let (directory, store, authority, _) = try fixture()
        let (agent, _) = try await authority.create(name: "Same name", operations: [.log], applets: [])
        let (other, _) = try await authority.create(name: "Same name", operations: [.log], applets: [])
        let note = try HostLogNote(request("Synthetic fixture"))
        let started = Date(timeIntervalSince1970: 100)
        for _ in 0..<60 {
            let response = try await store.recordHostNote(note, agent: agent, at: started)
            XCTAssertEqual(response.status, 201)
        }
        let reopened = try AppletStore(directory: directory)
        let denied = try await reopened.recordHostNote(note, agent: agent, at: started.addingTimeInterval(59.1))
        XCTAssertEqual(denied.status, 429)
        XCTAssertEqual(denied.headers["Retry-After"], "1")
        XCTAssertEqual(try object(denied.data)["retry_after_s"] as? Int, 1)
        let separate = try await reopened.recordHostNote(note, agent: other, at: started.addingTimeInterval(59.1))
        XCTAssertEqual(separate.status, 201)
        let resumed = try await reopened.recordHostNote(note, agent: agent, at: started.addingTimeInterval(60))
        XCTAssertEqual(resumed.status, 201)
        try await authority.pause(agent.id, paused: true)
        do {
            _ = try await reopened.recordHostNote(note, agent: agent, at: started.addingTimeInterval(120))
            XCTFail("A stale grant reached the persistence boundary.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }
}
