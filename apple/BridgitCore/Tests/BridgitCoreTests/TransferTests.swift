import Foundation
import XCTest
@testable import BridgitCore

final class TransferTests: XCTestCase {
    private func workspace() throws -> AppletStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return try AppletStore(directory: dir)
    }

    func testOwnerDeletionLogsOneSettingWithoutFileContents() async throws {
        let store = try workspace()
        let authority = HostAuthority(store: store, deviceID: "host")
        let (agent, _) = try await authority.create(name: "Sender", operations: [.files], applets: [])
        try await store.setTransferEnabled(.files, enabled: true)
        let row = try await store.saveTransfer(for: agent, kind: .files, name: "private.txt", mime: "text/plain", bytes: Data("confidential".utf8))
        let id = try XCTUnwrap(row["id"] as? String)
        try await store.deleteTransfer(id: id, for: nil, kind: .files)
        do {
            try await store.deleteTransfer(id: id, for: nil, kind: .files)
            XCTFail("Second delete should fail")
        } catch let failure as DeviceAPIError { XCTAssertEqual(failure.status, 404) }
        let settings = try await store.hostActivity(kinds: [.setting], includeRequests: false)
        XCTAssertEqual(settings.map(\.operation), ["Owner deleted a transfer", "You turned Receive files on"])
        XCTAssertTrue(settings.allSatisfy(\.isSetting))
        let detail = try XCTUnwrap(SettingActivityDetail(XCTUnwrap(settings.first)))
        let json = try detail.json()
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(Set(fields.keys), Set(SettingActivityDetail.keys))
        XCTAssertEqual(fields["id"] as? Int64, settings.first?.id)
        XCTAssertEqual(fields["message"] as? String, "Owner deleted a transfer")
        XCTAssertEqual(fields["channel"] as? String, "audit")
        XCTAssertTrue(fields["agent"] is NSNull)
        XCTAssertTrue(fields["http"] is NSNull)
        XCTAssertFalse(json.contains("private.txt"))
        let totals = try await store.hostActivityTotals(since: .distantPast)
        XCTAssertEqual(totals.requests, 0)
    }

    func testScopedUploadReadRetryDeleteAndRemoval() async throws {
        let store = try workspace()
        let authority = HostAuthority(store: store, deviceID: "host")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (owner, _) = try await authority.create(name: "Sender", operations: [.files,.interactionsRead], applets: [])
        let (other, _) = try await authority.create(name: "Another sender", operations: [.files], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }) { _ in throw CancellationError() }
        func call(_ agent: HostAgent, _ seq: Int64, _ method: String, _ path: String, body: [String: Any] = [:], query: [String: Any] = [:], key: String? = nil) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: seq + 1, agentID: agent.id,
                generation: agent.generation, deviceID: "host", deadline: now + 10_000, createdAt: now,
                request: .init(method: method, path: path, query: query, body: body, idempotencyKey: key)))
        }
        let disabled = await call(owner, 0, "GET", "/v1/files")
        XCTAssertEqual(disabled.status, 403)
        let denial = try XCTUnwrap(JSONSerialization.jsonObject(with: disabled.data) as? [String: String])
        XCTAssertEqual(denial["error"], "capability_disabled")
        try await store.setTransferEnabled(.files, enabled: true)
        let bytes = Data([0, 1, 255, 3])
        let body = ["name": "fixture.bin", "data_base64": bytes.base64EncodedString()]
        let upload = await call(owner, 1, "POST", "/v1/files", body: body, key: "upload")
        XCTAssertEqual(upload.status, 201)
        let repeatUpload = await call(owner, 2, "POST", "/v1/files", body: body, key: "upload")
        XCTAssertEqual(upload.data, repeatUpload.data)
        let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: upload.data) as? [String: Any])
        let id = try XCTUnwrap(metadata["id"] as? String)
        let path = "/v1/files/" + id
        let read = await call(owner, 3, "GET", path)
        XCTAssertEqual(read.data, bytes)
        let chunk = await call(owner, 4, "GET", path, query: ["offset": 1, "length": 2])
        XCTAssertEqual(chunk.status, 206)
        XCTAssertEqual(chunk.data, Data([1,255]))
        let hidden = await call(other, 1, "GET", path)
        XCTAssertEqual(hidden.status, 404)
        let denied = await call(owner, 5, "GET", "/v1/shares")
        XCTAssertEqual(denied.status, 403)
        let blockedDelete = await call(other, 2, "DELETE", path, key: "other-delete")
        XCTAssertEqual(blockedDelete.status, 404)
        let events = try await store.interactionEvents(owner: owner.eventOwner, types: ["file.received"])
        XCTAssertEqual((events["events"] as? [[String: Any]])?.count, 1)
        let deleted = await call(owner, 6, "DELETE", path, key: "delete")
        XCTAssertEqual(deleted.status, 200)
        let gone = await call(owner, 7, "GET", path)
        XCTAssertEqual(gone.status, 404)
        _ = try await store.saveTransfer(for: owner, kind: .files, name: "empty.txt", mime: "text/plain", bytes: Data())
        try await authority.remove(owner.id)
        let remaining = try await store.transfers()
        XCTAssertTrue(remaining.isEmpty)
        do {
            _ = try await store.saveTransfer(for: owner, kind: .files, name: "late.txt", mime: "text/plain", bytes: Data())
            XCTFail("Removed agent accepted a new transfer")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 403) }
    }

    func testSwitchRevocationPreservesOwnerAccessAndOtherKinds() async throws {
        let store = try workspace()
        let authority = HostAuthority(store: store, deviceID: "host")
        let (agent, _) = try await authority.create(name: "Receiver", operations: [.files, .downloads], applets: [])
        let defaults = try await store.enabledTransfers()
        XCTAssertTrue(defaults.isEmpty)
        for kind in [TransferKind.files, .downloads] { try await store.setTransferEnabled(kind, enabled: true) }
        let file = try await store.saveTransfer(for: agent, kind: .files, name: "sample.txt", mime: "text/plain", bytes: Data())
        let id = try XCTUnwrap(file["id"] as? String)
        try await store.setTransferEnabled(.files, enabled: false)
        do {
            _ = try await store.transferBytes(id: id, for: agent, kind: .files)
            XCTFail("Agent read a disabled file kind")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "capability_disabled") }
        do {
            _ = try await store.transfers(for: agent)
            XCTFail("Agent listed across disabled kinds")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_parameter") }
        let ownerFile = try await store.transferBytes(id: id, for: nil, kind: .files)
        XCTAssertEqual(ownerFile.mime, "text/plain")
        let enabled = try await store.enabledTransfers()
        XCTAssertEqual(enabled, [.downloads])
    }

    func testQuotaUnsafeNamesAndRetention() async throws {
        let store = try workspace(), now = Date(timeIntervalSince1970: 100)
        let authority = HostAuthority(store: store, deviceID: "host")
        let (agent, _) = try await authority.create(name: "Receiver", operations: [.files,.downloads], applets: [])
        try await store.setTransferEnabled(.files, enabled: true)
        try await store.setTransferEnabled(.downloads, enabled: true)
        for name in ["../secret", "folder/file", "bad.apk", "update.pkg", "file\nname", ".."] {
            do {
                _ = try await store.saveTransfer(for: agent, kind: .files, name: name, mime: "application/octet-stream", bytes: Data(), now: now)
                XCTFail("Accepted unsafe name")
            } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        }
        for n in 1...25 {
            _ = try await store.saveTransfer(for: agent, kind: .files, name: "\(n).txt", mime: "text/plain", bytes: Data(), now: now)
        }
        do {
            _ = try await store.saveTransfer(for: agent, kind: .files, name: "26.txt", mime: "text/plain", bytes: Data(), now: now)
            XCTFail("Exceeded agent quota")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 429) }
        let selected = try await store.saveTransfer(for: agent, kind: .downloads, name: "owner.txt", mime: "text/plain", bytes: Data(), now: now)
        let id = try XCTUnwrap(selected["id"] as? String)
        let empty = try await store.transferBytes(id: id, for: agent, kind: .downloads, now: now)
        XCTAssertTrue(empty.data.isEmpty)
        let expired = try await store.transfers(now: now.addingTimeInterval(31 * 86400))
        XCTAssertTrue(expired.isEmpty)
    }
}
