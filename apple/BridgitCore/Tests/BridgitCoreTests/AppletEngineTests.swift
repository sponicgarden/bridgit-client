import Foundation
import XCTest
@testable import BridgitCore

private actor AppraisalCount {
    var count = 0
    func reply() -> [String: Any] {
        count += 1
        return ["items": [["label": "Desk lamp", "category": "Furniture", "brand": NSNull(), "model": NSNull(), "quantity": 1,
                           "condition": "good", "new_usd": 80, "used_usd": 35, "insurable": true, "insurable_note": "Fixture",
                           "confidence": 0.9, "box": ["x0": 0.1, "y0": 0.1, "x1": 0.8, "y1": 0.8]]], "model": "fixture", "cost_usd": 0]
    }
}
final class AppletEngineTests: XCTestCase {
    private func definition() throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures")))
    }
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-applet-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    func testInstallRechecksRequiredPermissionsAfterReview() async throws {
        actor Gate {
            var cameraAvailable = true
            func revoke() { cameraAvailable = false }
            func check(_ required: [String]) throws {
                if required.contains("camera") && !cameraAvailable {
                    throw DeviceAPIError(403, "camera_permission_required", "Camera access is off.")
                }
            }
        }
        let gate = Gate()
        let store = try AppletStore(directory: directory())
        let engine = AppletEngine(store: store, checkPrerequisites: { try await gate.check($0) }) { _, _, _ in [:] }
        let data = try definition()
        _ = try await engine.validate(data)
        await gate.revoke()
        do {
            try await engine.install(data)
            XCTFail("Installation must recheck the device after review.")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.code, "camera_permission_required")
        }
        let installed = try await store.packages()
        XCTAssertTrue(installed.isEmpty)
    }

    func testInventoryThreePhotosAndLostReplyDoNotChargeOrRecordTwice() async throws {
        let store = try AppletStore(directory: directory()), count = AppraisalCount()
        let engine = AppletEngine(store: store) { _, _, _ in await count.reply() }
        try await engine.install(definition())
        var first: [String: Any]?
        for _ in 0..<3 {
            let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data("fixture-image".utf8))
            if first == nil { first = row }
            _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": row["id"]!, "photo": row["photo"]!])
        }
        _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": first!["id"]!, "photo": first!["photo"]!])
        let calls = await count.count
        let items = try await store.rows(applet: "insurance-inventory", table: "items")
        let photos = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(calls, 3); XCTAssertEqual(items.count, 3)
        XCTAssertEqual(items.compactMap { $0["new_usd"] as? Int }.reduce(0,+), 240)
        XCTAssertEqual(items.compactMap { $0["used_usd"] as? Int }.reduce(0,+), 105)
        XCTAssertTrue(photos.allSatisfy { $0["status"] as? String == "done" })
        XCTAssertEqual(items.first?["photo"] as? String != nil, true)
    }
    func testDeclaredCreateButtonRunsBatchAndSkipsDonePhotos() async throws {
        let store = try AppletStore(directory: directory()), count = AppraisalCount()
        let engine = AppletEngine(store: store) { _, _, _ in await count.reply() }
        try await engine.install(definition())
        for _ in 0..<3 { _ = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1])) }
        try await engine.startBatch(applet: "insurance-inventory"); await engine.waitForBatch(applet: "insurance-inventory")
        let first = await engine.batch(applet: "insurance-inventory")
        XCTAssertEqual(first.state, "done"); XCTAssertEqual(first.completed, 3); XCTAssertEqual(first.failed, 0)
        try await engine.startBatch(applet: "insurance-inventory"); await engine.waitForBatch(applet: "insurance-inventory")
        let second = await engine.batch(applet: "insurance-inventory"), calls = await count.count
        XCTAssertEqual(second.total, 0); XCTAssertEqual(calls, 3)
    }
    func testBudgetErrorStopsBatchBeforeAnotherPhoto() async throws {
        let store = try AppletStore(directory: directory())
        let engine = AppletEngine(store: store) { _, _, _ in throw DeviceAPIError(429, "applet_budget", "Fixture budget reached") }
        try await engine.install(definition())
        for _ in 0..<3 { _ = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1])) }
        try await engine.startBatch(applet: "insurance-inventory"); await engine.waitForBatch(applet: "insurance-inventory")
        let batch = await engine.batch(applet: "insurance-inventory")
        XCTAssertEqual(batch.state, "blocked"); XCTAssertEqual(batch.failed, 1); XCTAssertEqual(batch.completed, 0)
        let photos = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(photos.filter { $0["status"] as? String == "new" }.count, 3)
    }
    func testReopenKeepsDataPaidCacheAndReplayHighWater() async throws {
        let url = try directory(), count = AppraisalCount()
        let store = try AppletStore(directory: url)
        let engine = AppletEngine(store: store) { _, _, _ in await count.reply() }
        try await engine.install(definition())
        let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": row["id"]!, "photo": row["photo"]!])
        let claimed = try await store.claimSequence(agent: "agent", generation: "generation", sequence: 5)
        XCTAssertTrue(claimed)
        let reopened = try AppletStore(directory: url)
        let resumed = AppletEngine(store: reopened) { _, _, _ in await count.reply() }
        _ = try await resumed.run(applet: "insurance-inventory", flow: "read", with: ["row": row["id"]!, "photo": row["photo"]!])
        let calls = await count.count, items = try await reopened.rows(applet: "insurance-inventory", table: "items")
        let replay = try await reopened.claimSequence(agent: "agent", generation: "generation", sequence: 5)
        XCTAssertEqual(calls, 1); XCTAssertEqual(items.count, 1); XCTAssertFalse(replay)
    }
    func testOtherAppletCannotReadPhotoAndSchemaChangeCannotEraseRows() async throws {
        let store = try AppletStore(directory: directory())
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        try await engine.install(definition())
        let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        do { _ = try await store.photo(applet: "another-applet", ref: row["photo"] as! String); XCTFail("Cross-applet photo access") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "photo_not_found") }
        var changed = try JSONSerialization.jsonObject(with: definition()) as! [String: Any]
        var processing = changed["processing"] as! [String: Any]
        var tables = processing["tables"] as! [[String: Any]]
        tables.removeFirst(); processing["tables"] = tables; changed["processing"] = processing
        do { try await store.install(changed); XCTFail("Destructive update accepted") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "migration_required") }
        let kept = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(kept.count, 1)
    }
    func testDifferentBatchButtonsKeepIndependentSavedProgress() async throws {
        let store = try AppletStore(directory: directory()), count = AppraisalCount()
        let engine = AppletEngine(store: store) { _, _, _ in await count.reply() }
        var package = try JSONSerialization.jsonObject(with: definition()) as! [String: Any], ui = package["ui"] as! [String: Any], card = ui["card"] as! [String: Any]
        var pieces = card["pieces"] as! [[String: Any]]
        let create = try XCTUnwrap(pieces.flatMap { $0["items"] as? [[String: Any]] ?? [] }.first { $0["id"] as? String == "create" })
        var second = create; second["id"] = "again"
        pieces.append(["type": "buttons", "items": [second]])
        card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        _ = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        try await engine.startBatch(applet: "insurance-inventory", button: "create"); await engine.waitForBatch(applet: "insurance-inventory")
        try await engine.startBatch(applet: "insurance-inventory", button: "again"); await engine.waitForBatch(applet: "insurance-inventory")
        let reopened = AppletEngine(store: store) { _, _, _ in XCTFail("Reading batch history triggered paid work"); return [:] }
        let states = try await reopened.batchStates(applet: "insurance-inventory")
        XCTAssertEqual(states["create"]?.state, "done"); XCTAssertEqual(states["create"]?.completed, 1)
        XCTAssertEqual(states["again"]?.state, "done"); XCTAssertEqual(states["again"]?.completed, 0)
    }

    func testBatchPrevalidatesAllInputsWithoutInventingStatusColumnWrites() async throws {
        let store = try AppletStore(directory: directory())
        let engine = AppletEngine(store: store) { _, _, _ in XCTFail("Data-only flow invoked model"); return [:] }
        var package = try JSONSerialization.jsonObject(with: definition()) as! [String: Any], processing = package["processing"] as! [String: Any]
        processing["flows"] = [["id": "read", "trigger": ["type": "called", "with": ["value": "boolean"]], "steps": [["id": "save", "type": "record", "table": "photos", "row": ["status": "done"]]]]]
        package["processing"] = processing
        let action: [String: Any] = ["flow": ["$flow": "read"], "for_each": ["$source": "input"], "with": ["value": ["$item": "label"]]]
        let button: [String: Any] = ["id": "create", "do": action]
        let card: [String: Any] = ["sources": ["input": ["table": "items"]], "pieces": [["type": "buttons", "items": [button]]]]
        package["ui"] = ["card": card]
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        _ = try await store.addRows(applet: "insurance-inventory", table: "items", rows: [[:], [:]])
        let ordered = try await store.rows(applet: "insurance-inventory", table: "items")
        _ = try await store.update(applet: "insurance-inventory", table: "items", rowID: ordered.last!["id"] as! String, values: ["label": "Wrong boolean type"])
        let checked = try await store.rows(applet: "insurance-inventory", table: "items")
        XCTAssertTrue(checked.first?["label"] == nil || checked.first?["label"] is NSNull)
        XCTAssertEqual(checked.last?["label"] as? String, "Wrong boolean type")
        do { try await engine.startBatch(applet: "insurance-inventory"); XCTFail("Batch accepted a later mistyped input") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "bad_with") }
        let unchanged = try await store.rows(applet: "insurance-inventory", table: "photos"); XCTAssertTrue(unchanged.isEmpty)
        // A valid batch source does not need a status=reading/failed column.
        var flow = (processing["flows"] as! [[String: Any]])[0]; flow["trigger"] = ["type": "called", "with": ["value": "text"]]
        processing["flows"] = [flow]; package["processing"] = processing
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        try await engine.startBatch(applet: "insurance-inventory"); await engine.waitForBatch(applet: "insurance-inventory")
        let result = await engine.batch(applet: "insurance-inventory"); XCTAssertEqual(result.completed, 2); XCTAssertEqual(result.failed, 0)
    }

}
