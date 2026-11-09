import Foundation
import XCTest
@testable import BridgitCore

private actor LostAppraisal {
    var calls = 0
    func send() throws -> [String: Any] { calls += 1; throw URLError(.networkConnectionLost) }
}
final class AppletRecoveryTests: XCTestCase {
    func testLostPaidReplyBlocksAutomaticRetryAcrossRestart() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), cloud = LostAppraisal()
        let data = try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures")))
        let engine = AppletEngine(store: store) { _, _, _ in try await cloud.send() }
        try await engine.install(data)
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let args = ["row": photo["id"]!, "photo": photo["photo"]!]
        do { _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: args); XCTFail() } catch { }
        let restarted = AppletEngine(store: try AppletStore(directory: directory)) { _, _, _ in try await cloud.send() }
        do { _ = try await restarted.run(applet: "insurance-inventory", flow: "read", with: args); XCTFail() }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "appraisal_outcome_unknown") }
        let before = await cloud.calls; XCTAssertEqual(before, 1)
        try await restarted.allowUncertainRetry(applet: "insurance-inventory")
        do { _ = try await restarted.run(applet: "insurance-inventory", flow: "read", with: args); XCTFail() } catch { }
        let after = await cloud.calls; XCTAssertEqual(after, 2)
    }
    func testCollectionKeepsSharedReferencesAndReclaimsOnlyUnreferencedPhotos() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let data = try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures")))
        let engine = AppletEngine(store: store) { _, _, _ in [:] }; try await engine.install(data)
        let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let ref = row["photo"] as! String
        let shared = try await store.addRows(applet: "insurance-inventory", table: "items", rows: [["label": "Fixture", "photo": ref]])[0]
        try await store.delete(applet: "insurance-inventory", table: "photos", rowID: row["id"] as! String)
        try await store.collectMedia(applet: "insurance-inventory", grace: 0, now: Date().addingTimeInterval(1))
        let kept = try await store.photo(applet: "insurance-inventory", ref: ref); XCTAssertEqual(kept, Data([1]))
        try await store.delete(applet: "insurance-inventory", table: "items", rowID: shared["id"] as! String)
        try await store.collectMedia(applet: "insurance-inventory", grace: 0, now: Date().addingTimeInterval(1))
        do { _ = try await store.photo(applet: "insurance-inventory", ref: ref); XCTFail() }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "photo_not_found") }
    }
    func testTableCSVPreservesDeclaredOrderAndExcludesNonScalarFields() {
        let fields: [String: [String: Any]] = [
            "z_title": ["type": "text", "label": "First field"],
            "a_detail": ["type": "text", "label": "Second field"],
            "photo": ["type": "photo"], "object": ["type": "json"],
            "unknown": ["type": "future"], "missing_type": [:],
            "active": ["type": "boolean"], "count": ["type": "integer"]
        ]
        let columns = AppletPresentation.csvColumns(
            order: ["z_title", "photo", "a_detail", "z_title", "absent"], definitions: fields)
        XCTAssertEqual(columns.map(\.key), ["z_title", "a_detail", "active", "count"])
        let data = AppletExport.csv(rows: [["z_title": "Zulu", "a_detail": "Alpha", "active": true,
                                            "count": 2, "photo": "kept:private"]],
                                    columns: columns.map { ($0.key, $0.label) })
        XCTAssertEqual(String(decoding: data.dropFirst(3), as: UTF8.self),
                       "First field,Second field,active,count\r\nZulu,Alpha,Yes,2\r\n")
        XCTAssertTrue(AppletPresentation.csvColumns(order: [], definitions: ["photo": ["type": "photo"]]).isEmpty)
    }
    func testOwnerCSVIncludesEveryRowAndEscapesTypedValues() {
        var rows: [[String: Any]] = [["text": "Line 1, \"quoted\"\nLine 2", "count": 12, "enabled": true, "empty": NSNull()]]
        rows += (1...600).map { ["text": "row-\($0)", "count": $0, "enabled": false, "empty": NSNull()] }
        let csv = String(decoding: AppletExport.csv(rows: rows, columns: [("text", "Text"), ("count", "Count"), ("enabled", "Enabled"), ("empty", "Empty")]), as: UTF8.self)
        XCTAssertTrue(csv.hasPrefix("\u{feff}Text,Count,Enabled,Empty\r\n\"Line 1, \"\"quoted\"\"\nLine 2\",12,Yes,\r\n"))
        XCTAssertTrue(csv.hasSuffix("row-600,600,No,\r\n"))
        XCTAssertEqual(csv.components(separatedBy: "\r\n").count, 603)
    }

    func testCSVQuotesAndNeutralizesUntrustedFormulaText() {
        let rows: [[String: Any]] = [
            ["label": "=HYPERLINK(\"bad\")", "insurable": true, "price": -3.25],
            ["label": "Line 1,\r\nLine 2", "insurable": false, "price": "-42"]
        ]
        let bytes = AppletExport.csv(rows: rows, columns: [("label", "Item"), ("insurable", "Insurable"), ("price", "Price")])
        XCTAssertEqual(bytes.prefix(3), Data([0xef, 0xbb, 0xbf]))
        XCTAssertEqual(String(decoding: bytes.dropFirst(3), as: UTF8.self),
                       "Item,Insurable,Price\r\n\"'=HYPERLINK(\"\"bad\"\")\",Yes,-3.25\r\n\"Line 1,\r\nLine 2\",No,-42\r\n")
    }
}

private actor UpdateRaceGate {
    var waiting = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async { waiting = true; await withCheckedContinuation { continuation = $0 } }
    func release() { continuation?.resume(); continuation = nil }
}
extension AppletRecoveryTests {
    func testPackageUpdateAndRetryReviewCannotInterleaveWithPaidWork() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory), gate = UpdateRaceGate()
        let engine = AppletEngine(store: store) { _, _, _ in await gate.wait(); return ["items": []] }
        let url = try XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures"))
        let data = try Data(contentsOf: url); try await engine.install(data)
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let task = Task { try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": photo["id"]!, "photo": photo["photo"]!]) }
        while !(await gate.waiting) { await Task.yield() }
        do { try await engine.install(data); XCTFail("Updated a package while its old flow was suspended") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "applet_busy") }
        do { try await engine.allowUncertainRetry(applet: "insurance-inventory"); XCTFail("Cleared a live paid attempt") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "batch_busy") }
        await gate.release(); _ = try await task.value
        try await engine.install(data)
        let rows = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(rows.first?["status"] as? String, "done")
    }
}
