import BridgitCore
import Foundation
import XCTest

final class InboundSharingTests: XCTestCase {
    private func require(_ condition: Bool, _ label: String) throws {
        if !condition { throw NSError(domain: "InboundProbe", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func testAtomicDeliveryRecoveryAndValidation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("BridgitInboundProbe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try AppletStore(directory: root.appendingPathComponent("private"))
        let inbox = try InboundShareInbox(root: root.appendingPathComponent("shared"))
        let authority = HostAuthority(store: store, deviceID: "synthetic-inbound-probe")
        let (alpha, _) = try await authority.create(name: "Synthetic Alpha", operations: [.files, .shares, .interactionsRead], applets: [])
        let (beta, _) = try await authority.create(name: "Synthetic Beta", operations: [.files, .shares], applets: [])
        try await store.setTransferEnabled(.files, enabled: true); try await store.setTransferEnabled(.shares, enabled: true)
        let catalog = try await store.inboundShareCatalog(); try inbox.publish(catalog)
        let a = catalog.recipients.first { $0.id == alpha.id }!, b = catalog.recipients.first { $0.id == beta.id }!
        func enqueue(_ recipient: InboundShareRecipient, mode: InboundShareMode = .transfer, text: String = "Synthetic OS share") throws -> InboundShareRequest {
            let draft = try inbox.beginDraft(); let item = try inbox.stageText(text, draft: draft, mode: mode)
            return try inbox.enqueue(draft: draft, recipient: recipient, mode: mode, items: [item])
        }
        var passed: [String] = []
        let first = try enqueue(a)
        let receipt = try await store.consumeInboundShare(inbox, id: first.id)
        try require(receipt.status == "delivered" && receipt.transferIDs.count == 1, "single delivery")
        let alphaRows = try await store.transfers(for: alpha, kind: .shares), betaRows = try await store.transfers(for: beta, kind: .shares)
        try require(alphaRows.count == 1 && betaRows.isEmpty, "recipient isolation")
        do { _ = try await store.transferBytes(id: receipt.transferIDs[0], for: beta, kind: .shares); throw NSError(domain: "cross-agent-read", code: 1) }
        catch let e as DeviceAPIError { try require(e.status == 404, "cross-agent read must fail") }
        try require(try await store.transferBytes(id: receipt.transferIDs[0], for: alpha, kind: .shares).data == Data("Synthetic OS share".utf8), "exact recipient content")
        passed.append("single transfer and cross-agent isolation")
        // Recreate only the interrupted shared receipt, never the private delivery record.
        var interrupted = receipt; interrupted.status = "delivering"
        let manifest = inbox.root.appendingPathComponent("requests").appendingPathComponent(first.id).appendingPathComponent("request.json")
        try JSONEncoder().encode(interrupted).write(to: manifest, options: .atomic)
        let recovered = try await store.consumeInboundShare(inbox, id: first.id)
        try require(recovered.transferIDs == receipt.transferIDs, "stable crash recovery IDs")
        try require(try await store.transfers(for: alpha, kind: .shares).count == 1, "no duplicated recovery")
        passed.append("interrupted shared receipt recovery without payload or duplication")
        let draft = try inbox.beginDraft(); let replayItem = try inbox.stageText("Synthetic OS share", draft: draft, mode: .transfer)
        do { _ = try inbox.enqueue(draft: draft, requestID: first.id, recipient: b, mode: .transfer, items: [replayItem]); throw NSError(domain: "retarget-accepted", code: 1) }
        catch let e as DeviceAPIError { try require(e.status == 409, "retarget replay rejected") }; inbox.discardDraft(draft)
        let repeatDraft = try inbox.beginDraft()
        let repeatItem = try inbox.stageText("Synthetic OS share", draft: repeatDraft, mode: .transfer)
        let repeatReceipt = try inbox.enqueue(draft: repeatDraft, requestID: first.id.uppercased(), recipient: a, mode: .transfer, items: [repeatItem])
        try require(repeatReceipt.id == first.id && repeatReceipt.transferIDs == receipt.transferIDs, "UUID case cannot duplicate a delivery")
        passed.append("stable request cannot be retargeted")
        let revoked = try enqueue(b)
        try await authority.pause(beta.id, paused: true)
        let denied = try await store.consumeInboundShare(inbox, id: revoked.id)
        try require(denied.status == "rejected", "delivery rechecks actual pause after catalog selection")
        try require(try await store.transfers(for: beta, kind: .shares).isEmpty, "paused agent received nothing")
        try await authority.pause(beta.id, paused: false); _ = try inbox.retry(revoked.id)
        try require(try await store.consumeInboundShare(inbox, id: revoked.id).status == "delivered", "explicit retry after restore")
        passed.append("fresh private authorization and explicit same-recipient retry")
        let cancelled = try enqueue(a); _ = try inbox.cancel(cancelled.id)
        try require(try await store.consumeInboundShare(inbox, id: cancelled.id).status == "cancelled", "pending cancellation")
        passed.append("cancel before delivery leaves no transfer")
        let batchDraft = try inbox.beginDraft()
        let x = try inbox.stageBytes(Data("first".utf8), name: "first.txt", mime: "text/plain", draft: batchDraft, mode: .transfer)
        let y = try inbox.stageBytes(Data("second".utf8), name: "second.txt", mime: "text/plain", draft: batchDraft, mode: .transfer)
        let batch = try inbox.enqueue(draft: batchDraft, recipient: a, mode: .transfer, items: [x,y])
        try Data("tampered".utf8).write(to: inbox.root.appendingPathComponent("requests").appendingPathComponent(batch.id).appendingPathComponent(y.file))
        try require(try await store.consumeInboundShare(inbox, id: batch.id).status == "rejected", "tampered batch rejected")
        try require(try await store.transfers(for: alpha, kind: .shares).count == 1, "whole batch rolls back")
        passed.append("batch validation and atomic rollback")
        let big = Data(repeating: 65, count: 512 * 1024 + 1), bigDraft = try inbox.beginDraft()
        do { _ = try inbox.stageBytes(big, name: "too-large.txt", mime: "text/plain", draft: bigDraft, mode: .fileImport); throw NSError(domain: "limit-not-enforced", code: 1) }
        catch let e as DeviceAPIError { try require(e.status == 413, "inbound import size") }; inbox.discardDraft(bigDraft)
        _ = try await store.saveTransfer(for: beta, kind: .files, name: "generic-larger.txt", mime: "text/plain", bytes: big)
        let textImport = try enqueue(a, mode: .fileImport, text: String(repeating: "x", count: 40000))
        let imported = try await store.consumeInboundShare(inbox, id: textImport.id)
        let bytes = try await store.transferBytes(id: imported.transferIDs[0], for: alpha, kind: .files)
        try require(bytes.data.count == 32768, "file-import text clipping")
        passed.append("512KiB inbound limit, generic API unchanged, text clipping")
        let report: [String: Any] = ["passed": passed, "count": passed.count, "real_account_or_network": false]
        let output = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted,.sortedKeys])
        print(String(decoding: output, as: UTF8.self))
    }
    func testDraftCapacityAndAbandonedDraftRecovery() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("BridgitDraftProbe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let inbox = try InboundShareInbox(root: root)
        var drafts: [String] = []
        for _ in 0..<100 { drafts.append(try inbox.beginDraft()) }
        XCTAssertThrowsError(try inbox.beginDraft()) { XCTAssertEqual(($0 as? DeviceAPIError)?.status, 429) }
        let abandoned = root.appendingPathComponent("drafts").appendingPathComponent(drafts[0])
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-90000)], ofItemAtPath: abandoned.path)
        _ = try inbox.beginDraft()
        XCTAssertFalse(FileManager.default.fileExists(atPath: abandoned.path))
    }
    func testAggregateQuotaIncludesUnsubmittedDraftBytes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("BridgitByteProbe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let inbox = try InboundShareInbox(root: root)
        let bytes = Data(repeating: 65, count: 25 * 1024 * 1024)
        for _ in 0..<4 {
            let draft = try inbox.beginDraft()
            _ = try inbox.stageBytes(bytes, name: "bounded.bin", mime: "application/octet-stream", draft: draft, mode: .transfer)
        }
        let full = try inbox.beginDraft()
        XCTAssertThrowsError(try inbox.stageBytes(Data([1]), name: "over.bin", mime: "application/octet-stream", draft: full, mode: .transfer)) {
            XCTAssertEqual(($0 as? DeviceAPIError)?.status, 429)
        }
    }

    func testFeatureRevocationAndCanonicalOwnerRoutes() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("BridgitFeatureProbe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try AppletStore(directory: root.appendingPathComponent("private"))
        let inbox = try InboundShareInbox(root: root.appendingPathComponent("shared"))
        let authority = HostAuthority(store: store, deviceID: "synthetic-feature-revocation")
        let (agent, _) = try await authority.create(name: "Synthetic recipient", operations: [.shares], applets: [])
        try await store.setTransferEnabled(.shares, enabled: true)
        let catalog = try await store.inboundShareCatalog(); try inbox.publish(catalog)
        let draft = try inbox.beginDraft()
        let item = try inbox.stageText("Never delivered", draft: draft, mode: .transfer)
        let request = try inbox.enqueue(draft: draft, recipient: catalog.recipients[0], mode: .transfer, items: [item])
        try await store.setTransferEnabled(.shares, enabled: false)
        let denied = try await store.consumeInboundShare(inbox, id: request.id)
        XCTAssertEqual(denied.status, "rejected"); XCTAssertTrue(denied.transferIDs.isEmpty)
        try await store.setTransferEnabled(.shares, enabled: true)
        let rows = try await store.transfers(for: agent, kind: .shares)
        XCTAssertTrue(rows.isEmpty)
        let base = "/v1/owner/configuration/inbound-shares"
        XCTAssertEqual(HostRequest(method: "GET", path: base).route?.0, .configurationRead)
        XCTAssertEqual(HostRequest(method: "POST", path: base).route?.0, .configurationWrite)
        XCTAssertEqual(HostRequest(method: "DELETE", path: base + "/" + request.id).route?.0, .configurationWrite)
        XCTAssertEqual(HostRequest(method: "POST", path: base + "/" + request.id + "/retry").route?.0, .configurationWrite)
        XCTAssertNil(HostRequest(method: "PATCH", path: base).route)
        XCTAssertNil(HostRequest(method: "GET", path: base + "/invalid-id").route)
    }

    func testImportMIMEScopeDoesNotNarrowGenericFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("BridgitMIMEProbe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let inbox = try InboundShareInbox(root: root.appendingPathComponent("shared"))
        let draft = try inbox.beginDraft()
        let bytes = Data([1, 2, 3])
        XCTAssertThrowsError(try inbox.stageBytes(bytes, name: "binary.bin", mime: "application/octet-stream", draft: draft, mode: .fileImport)) {
            XCTAssertEqual(($0 as? DeviceAPIError)?.status, 422)
        }
        _ = try inbox.stageBytes(bytes, name: "binary.bin", mime: "application/octet-stream", draft: draft, mode: .transfer)
        let store = try AppletStore(directory: root.appendingPathComponent("private"))
        let authority = HostAuthority(store: store, deviceID: "synthetic-mime-probe")
        let (agent, _) = try await authority.create(name: "Synthetic recipient", operations: [.files], applets: [])
        try await store.setTransferEnabled(.files, enabled: true)
        let row = try await store.saveTransfer(for: agent, kind: .files, name: "binary.bin", mime: "application/octet-stream", bytes: bytes)
        let actual = try await store.transferBytes(id: row["id"] as! String, for: agent, kind: .files)
        XCTAssertEqual(actual.data, bytes)
    }

}
