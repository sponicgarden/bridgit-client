import Foundation
import XCTest
@testable import BridgitCore

private actor ValidationCalls {
    var count = 0
    func reply() -> [String: Any] { count += 1; return ["items": []] }
}
final class AppletExecutionValidationTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures"))
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }
    private func setup() throws -> (AppletStore, AppletEngine, ValidationCalls) {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), calls = ValidationCalls()
        return (store, AppletEngine(store: store) { _, _, _ in await calls.reply() }, calls)
    }
    private func changed(_ field: String, value: Any, level: String) throws -> [String: Any] {
        var package = try fixture(), processing = package["processing"] as! [String: Any]
        var flows = processing["flows"] as! [[String: Any]]
        if level == "processing" { processing[field] = value }
        else {
            if level == "flow" { flows[0][field] = value }
            else {
                var steps = flows[0]["steps"] as! [[String: Any]]
                steps[0][field] = value; flows[0]["steps"] = steps
            }
            processing["flows"] = flows
        }
        package["processing"] = processing
        return package
    }
    func testUnsupportedControlsRejectInstallationWithoutReplacingSavedPackage() async throws {
        let (store, engine, calls) = try setup()
        try await engine.install(JSONSerialization.data(withJSONObject: fixture()))
        let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        let cases: [(String, Any, String)] = [
            ("only_if", ["equals": false], "flow"), ("only_if", NSNull(), "step"),
            ("on_error", "skip", "step"), ("on_error", "retry", "step"),
            ("retries", 2, "step"), ("retries", 0, "flow"),
            ("cooldown_s", 30, "flow"), ("max_runs_per_day", 1, "flow"),
            ("watches", [["id": "watch"]], "processing"), ("future_control", true, "step")
        ]
        for (field, value, level) in cases {
            var package = try changed(field, value: value, level: level)
            package["version"] = "rejected-update"
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Accepted \(level).\(field)") }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
            let saved = try await store.package("insurance-inventory")
            XCTAssertNotEqual(saved["version"] as? String, "rejected-update")
        }
        let rows = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows.first?["id"] as? String, row["id"] as? String)
        let count = await calls.count; XCTAssertEqual(count, 0)
    }
    func testPersistedUnsupportedFlowCannotExecuteOrStartBatch() async throws {
        let (store, engine, calls) = try setup()
        // Reproduce a package accepted by an older build, bypassing new-import validation.
        try await store.install(changed("only_if", value: ["equals": false], level: "flow"))
        let row = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        do { _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: ["row": row["id"]!, "photo": row["photo"]!]); XCTFail("Ran legacy unsupported flow") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
        do { try await engine.startBatch(applet: "insurance-inventory"); XCTFail("Started legacy unsupported batch") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
        let rows = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(rows.first?["status"] as? String, "new")
        let count = await calls.count; XCTAssertEqual(count, 0)
    }
    func testMalformedFlowCollectionsCannotInstallAsAnEmptyPackage() async throws {
        let (store, engine, _) = try setup()
        for value: Any in [NSNull(), "not-an-array", [NSNull()], [["id": "broken"]]] {
            var package = try fixture(), processing = package["processing"] as! [String: Any]
            processing["flows"] = value; package["processing"] = processing
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Malformed flows installed") } catch { }
        }
        let packages = try await store.packages(); XCTAssertTrue(packages.isEmpty)
    }
    func testUnsupportedBatchConditionsRejectImportAndLegacyExecutionBeforePaidWork() async throws {
        let (store, engine, calls) = try setup()
        for condition: Any in [NSNull(), ["item": "status", "is": "new"], ["item": "status", "is_not": "done", "extra": true], ["item": "status", "is_not": 0]] {
            var package = try fixture(), ui = package["ui"] as! [String: Any], card = ui["card"] as! [String: Any]
            var pieces = card["pieces"] as! [[String: Any]]
            let index = try XCTUnwrap(pieces.firstIndex { piece in
                (piece["items"] as? [[String: Any]])?.contains { $0["id"] as? String == "create" } == true
            })
            var buttons = pieces[index]["items"] as! [[String: Any]]
            var action = buttons[0]["do"] as! [String: Any]
            action["only_if"] = condition; buttons[0]["do"] = action
            pieces[index]["items"] = buttons; card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Accepted unsupported button condition") }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
            try await store.install(package)
            _ = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
            do { try await engine.startBatch(applet: "insurance-inventory"); XCTFail("Ran legacy batch") }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
        }
        let count = await calls.count; XCTAssertEqual(count, 0)
        let rows = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertTrue(rows.allSatisfy { $0["status"] as? String == "new" })
    }
    func testHiddenNonButtonItemsCannotShadowTheRenderedBatchButton() async throws {
        let (store, engine, calls) = try setup()
        var package = try fixture(), ui = package["ui"] as! [String: Any], card = ui["card"] as! [String: Any]
        var pieces = card["pieces"] as! [[String: Any]]
        let buttons = pieces.flatMap { $0["items"] as? [[String: Any]] ?? [] }
        var shadow = try XCTUnwrap(buttons.first { $0["id"] as? String == "create" })
        var action = shadow["do"] as! [String: Any]; action["only_if"] = NSNull(); shadow["do"] = action
        pieces.insert(["type": "note", "text": "Fixture", "items": [shadow]], at: 0)
        card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
        do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Accepted a hidden action on a note") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "unsupported_execution_field") }
        let count = await calls.count; XCTAssertEqual(count, 0)
        let packages = try await store.packages(); XCTAssertTrue(packages.isEmpty)
    }
    func testConditionNamedDataIsNotConfusedWithExecutionControls() throws {
        var package = try fixture(), processing = package["processing"] as! [String: Any]
        var flows = processing["flows"] as! [[String: Any]], steps = flows[0]["steps"] as! [[String: Any]]
        steps[1]["row"] = ["only_if": "ordinary data", "retries": 3]
        flows[0]["steps"] = steps; processing["flows"] = flows; package["processing"] = processing
        var tables = processing["tables"] as! [[String: Any]]
        let tableIndex = tables.firstIndex { $0["id"] as? String == "items" }!
        var columns = tables[tableIndex]["columns"] as! [String: [String: Any]]
        columns["only_if"] = ["type": "text"]; columns["retries"] = ["type": "integer"]
        // Keep the table within the portable column limit while testing these ordinary data names.
        columns.removeValue(forKey: "brand"); columns.removeValue(forKey: "model")
        tables[tableIndex]["columns"] = columns; processing["tables"] = tables; package["processing"] = processing
        XCTAssertNoThrow(try AppletExecutionValidator.validate(package))
    }
    func testMalformedBudgetsAndTriggerDeclarationsRejectBeforeReplacingPackageOrPaidWork() async throws {
        let (store, engine, calls) = try setup()
        try await engine.install(JSONSerialization.data(withJSONObject: fixture()))
        for (field, value, level): (String, Any, String) in [
            ("max_paid_per_day", true, "flow"), ("max_paid_per_day", 0, "flow"), ("max_paid_per_day", 501, "flow"),
            ("max_paid_per_day", "30", "flow"), ("max_paid_per_day", 2.5, "flow"),
            ("max_items", false, "step"), ("max_items", 0, "step"), ("max_items", 31, "step"),
            ("trigger", ["type": "called", "with": ["photo": "object"]], "flow"),
            ("trigger", ["type": "called", "with": NSNull()], "flow")
        ] {
            var package = try changed(field, value: value, level: level); package["version"] = "invalid-update"
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Accepted invalid " + field) } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
            let saved = try await store.package("insurance-inventory"); XCTAssertNotEqual(saved["version"] as? String, "invalid-update")
        }
        let count = await calls.count; XCTAssertEqual(count, 0)
    }
    func testWrongOrUndeclaredCalledInputsCannotRunPaidStep() async throws {
        let (store, engine, calls) = try setup()
        try await engine.install(JSONSerialization.data(withJSONObject: fixture()))
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1]))
        for values: [String: Any] in [
            ["photo": photo["photo"]!, "row": true],
            ["photo": photo["photo"]!, "row": photo["id"]!, "extra": "not declared"],
            ["photo": photo["photo"]!, "row": String(repeating: "x", count: 4097)]
        ] {
            do { _ = try await engine.run(applet: "insurance-inventory", flow: "read", with: values); XCTFail("Ran with invalid input") }
            catch let error as DeviceAPIError { XCTAssertEqual(error.code, "bad_with") }
        }
        let count = await calls.count; XCTAssertEqual(count, 0)
        let rows = try await store.rows(applet: "insurance-inventory", table: "photos"); XCTAssertEqual(rows.first?["status"] as? String, "new")
        let normalized = try AppletExecutionValidator.inputs(["flag": true, "number": 1], trigger: ["with": ["text": "text", "flag": "boolean", "number": "number"]])
        XCTAssertTrue(normalized["text"] is NSNull)
        XCTAssertThrowsError(try AppletExecutionValidator.inputs(["number": true], trigger: ["with": ["number": "number"]]))
        XCTAssertThrowsError(try AppletExecutionValidator.inputs(["flag": 1], trigger: ["with": ["flag": "boolean"]]))
    }

    func testPDFAndUnknownCardActionsAreRefusedBeforeInstallation() async throws {
        let (store, engine, calls) = try setup()
        for action: [String: Any] in [
            ["step": ["type": "share", "table": "items", "as": "pdf"]],
            ["step": ["type": "share", "table": "items", "as": "csv", "photos": "thumbs"]],
            ["future_action": true]
        ] {
            var package = try fixture(), ui = package["ui"] as! [String: Any], card = ui["card"] as! [String: Any]
            var pieces = card["pieces"] as! [[String: Any]]
            pieces.append(["type": "buttons", "items": [["id": "unsupported", "do": action]]])
            card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
            do { try await engine.install(JSONSerialization.data(withJSONObject: package)); XCTFail("Installed unsupported card action") } catch { }
        }
        let packages = try await store.packages(), count = await calls.count
        XCTAssertTrue(packages.isEmpty); XCTAssertEqual(count, 0)
    }
    func testDeclaredColumnsDriveNonInventoryPresentation() throws {
        let columns = AppletPresentation.columns([["key": "temperature", "label": "Water temperature"], ["key": "ready", "format": "yes_no"]], definitions: ["temperature": ["type": "number"], "ready": ["type": "boolean"]])
        XCTAssertEqual(columns.map(\.key), ["temperature", "ready"])
        XCTAssertEqual(columns.first?.label, "Water temperature")
        XCTAssertEqual(columns.last?.text(false), "No")
        XCTAssertEqual(AppletPresentation.totals([["temperature": 3], ["temperature": 4]], keys: ["temperature"], columns: columns), "Water temperature 7")
    }

}
