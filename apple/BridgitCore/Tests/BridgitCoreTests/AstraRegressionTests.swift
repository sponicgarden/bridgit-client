import Foundation
import XCTest
@testable import BridgitCore

private actor ReviewAppraisals {
    var count = 0
    func lost() throws -> [String: Any] { count += 1; throw URLError(.networkConnectionLost) }
}
final class AstraRegressionTests: XCTestCase {
    private func directory() -> URL {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }; return path
    }
    private func inventory() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures")))) as! [String: Any]
    }
    private func plainPackage() -> [String: Any] {
        ["format": "bridgit-template/1", "id": "plain", "version": "1", "runtime": ["adapter": "bridgit.package.v1"], "processing": ["tables": [["id": "rows", "max_rows": 10000, "columns": ["text": ["type": "text"]]]]]]
    }
    func testTextScalarsCannotRaiseFoundationException() async throws {
        let store = try AppletStore(directory: directory()); try await store.install(plainPackage())
        let rows = try await store.addRows(applet: "plain", table: "rows", rows: [["text": 42], ["text": true]])
        XCTAssertEqual(rows[0]["text"] as? String, "42"); XCTAssertEqual(rows[1]["text"] as? String, "true")
        let row = try await store.update(applet: "plain", table: "rows", rowID: rows[0]["id"] as! String, values: ["text": false])
        XCTAssertEqual(row["text"] as? String, "false")
    }
    func testUpdateCannotBypassUTF8AggregateQuota() async throws {
        let store = try AppletStore(directory: directory()); try await store.install(plainPackage())
        let small = try await store.addRows(applet: "plain", table: "rows", rows: [["text": "small"]])[0]
        let large = ["text": String(repeating: "🦦", count: 1000)]
        for _ in 0..<6 {
            do { _ = try await store.addRows(applet: "plain", table: "rows", rows: Array(repeating: large, count: 200)) }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "table_storage_full"); break }
        }
        for _ in 0..<201 {
            do { _ = try await store.addRows(applet: "plain", table: "rows", rows: [large]) }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "table_storage_full"); break }
        }
        for _ in 0..<100 {
            do { _ = try await store.addRows(applet: "plain", table: "rows", rows: [["text": "x"]]) }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "table_storage_full"); break }
        }
        do { _ = try await store.update(applet: "plain", table: "rows", rowID: small["id"] as! String, values: large); XCTFail("Update bypassed aggregate quota") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "table_storage_full") }
        let rows = try await store.rows(applet: "plain", table: "rows", limit: 10000)
        XCTAssertEqual(rows.first { $0["id"] as? String == small["id"] as? String }?["text"] as? String, "small")
    }
    func testFreshIdempotencyKeyCannotRepeatUncertainPaidIntent() async throws {
        let path = directory(), store = try AppletStore(directory: path), calls = ReviewAppraisals()
        let engine = AppletEngine(store: store) { _, _, _ in try await calls.lost() }
        try await engine.install(JSONSerialization.data(withJSONObject: inventory()))
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let inputs = ["row": photo["id"]!, "photo": photo["photo"]!]
        do { _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: inputs, invocationID: "request-one"); XCTFail() } catch { }
        let restarted = AppletEngine(store: try AppletStore(directory: path)) { _, _, _ in try await calls.lost() }
        do { _ = try await restarted.run(applet: "insurance-inventory", flow: "read", with: inputs, invocationID: "request-two"); XCTFail("Fresh key repeated a possibly charged call") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "appraisal_outcome_unknown") }
        let count = await calls.count; XCTAssertEqual(count, 1)
        try await restarted.allowUncertainRetry(applet: "insurance-inventory")
        do { _ = try await restarted.run(applet: "insurance-inventory", flow: "read", with: inputs, invocationID: "request-three") } catch { }
        let after = await calls.count; XCTAssertEqual(after, 2)
    }
    func testDistinctRowsAndSameVersionPackageChangesHaveDistinctCheckpoints() async throws {
        let store = try AppletStore(directory: directory()), engine = AppletEngine(store: store) { _, _, _ in XCTFail("Unexpected paid action"); return [:] }
        let tables: [[String: Any]] = ["input", "output"].map { ["id": $0, "columns": ["label": ["type": "text"]]] }
        var flow: [String: Any] = ["id": "copy", "trigger": ["type": "called", "with": ["label": "text"]], "steps": [["id": "save", "type": "record", "table": "output", "row": ["label": ["$trigger": "with.label"]]]]]
        let action: [String: Any] = ["flow": ["$flow": "copy"], "for_each": ["$source": "input"], "with": ["label": ["$item": "label"]]]
        let card: [String: Any] = ["sources": ["input": ["table": "input"]], "pieces": [["type": "buttons", "items": [["id": "copy", "do": action]]]]]
        var package: [String: Any] = ["format": "bridgit-template/1", "id": "copy", "version": "1", "runtime": ["adapter": "bridgit.package.v1"], "processing": ["tables": tables, "flows": [flow]], "ui": ["card": card]]
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        _ = try await store.addRows(applet: "copy", table: "input", rows: [["label": "same"], ["label": "same"]])
        try await engine.startBatch(applet: "copy", button: "copy"); await engine.waitForBatch(applet: "copy")
        var rows = try await store.rows(applet: "copy", table: "output"); XCTAssertEqual(rows.count, 2)
        try await engine.startBatch(applet: "copy", button: "copy"); await engine.waitForBatch(applet: "copy")
        rows = try await store.rows(applet: "copy", table: "output"); XCTAssertEqual(rows.count, 2, "Recovery duplicated completed source rows")
        flow["steps"] = [["id": "save", "type": "record", "table": "output", "row": ["label": "changed"]]]
        package["processing"] = ["tables": tables, "flows": [flow]]
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        try await engine.startBatch(applet: "copy", button: "copy"); await engine.waitForBatch(applet: "copy")
        rows = try await store.rows(applet: "copy", table: "output")
        XCTAssertEqual(rows.count, 4); XCTAssertEqual(rows.filter { $0["label"] as? String == "changed" }.count, 2)
    }
    func testStaticInvalidOperandsFailBeforeAnyPaidWork() async throws {
        let calls = ReviewAppraisals(), store = try AppletStore(directory: directory())
        let engine = AppletEngine(store: store) { _, _, _ in try await calls.lost() }
        for step: [String: Any] in [
            ["id": "bad", "type": "record", "table": "items"],
            ["id": "bad", "type": "appraise", "photo": 42],
            ["id": "bad", "type": "update", "table": "photos", "row": 42, "set": ["status": "done"]],
            ["id": "bad", "type": "record", "table": "items", "for_each": 42, "row": [:]],
            ["id": "bad", "type": "update", "table": "absent", "row": "id", "set": [:]],
            ["id": "bad", "type": "record", "table": "items", "row": ["undeclared": 1]],
            ["id": "bad", "type": "record", "table": "items", "row": ["label": ["$step": "future.value"]]]
        ] {
            var package = try inventory(), processing = package["processing"] as! [String: Any], flows = processing["flows"] as! [[String: Any]], steps = flows[0]["steps"] as! [[String: Any]]
            steps.append(step); flows[0]["steps"] = steps; processing["flows"] = flows; package["processing"] = processing
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Accepted static invalid operand") } catch { }
            try await store.install(package)
            let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
            do { _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": photo["id"]!, "photo": photo["photo"]!]); XCTFail("Executed saved invalid flow") } catch { }
        }
        let count = await calls.count; XCTAssertEqual(count, 0)
    }
    func testDevelopmentStorageNeverUsesExistingReleaseDirectory() {
        let base = URL(fileURLWithPath: "/tmp/application-support")
        let release = AppletStorageLocation.directory(applicationSupport: base, bundleIdentifier: "cc.bridgit.macos.local", development: false)
        let debug = AppletStorageLocation.directory(applicationSupport: base, bundleIdentifier: "cc.bridgit.macos.local.debug", development: true)
        XCTAssertEqual(release.path, "/tmp/application-support/Bridgit/Applets")
        XCTAssertNotEqual(debug, release); XCTAssertTrue(debug.path.contains("/Development/cc.bridgit.macos.local.debug/"))
    }
}
