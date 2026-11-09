import XCTest
@testable import BridgitAppletRuntime

final class RegistryPackageTests: XCTestCase {
    func testColumnOrderReadsEscapedKeysAndIgnoresNestedLookalikes() throws {
        let response = Data(#"{"extra":{"definition":{"processing":{"tables":[]}}},"definition":{"processing":{"tables":[{"id":"notes","columns":{"z\u0065ta":{"type":"text","label":"Quote: \" [ }"},"alpha":{"type":"number","default":-1.5e2}}},{"id":"other","columns":{"value":{"type":"text"}}}]}}}"#.utf8)
        _ = try JSONSerialization.jsonObject(with: response)
        XCTAssertEqual(try RegistryColumnOrder.read(response), ["notes": ["zeta", "alpha"], "other": ["value"]])
    }

    func testColumnOrderRejectsAmbiguousDuplicateNames() {
        let response = Data(#"{"definition":{"processing":{"tables":[{"id":"notes","columns":{"x":{},"x":{}}}]}}}"#.utf8)
        XCTAssertThrowsError(try RegistryColumnOrder.read(response))
    }

    private let row: [String: Any] = ["key":"tpl_owner", "id":"lamp", "revision":1, "name":"Lamp", "summary":"Check lamp"]

    private func page(_ rows: [[String: Any]], next: String? = nil) throws -> CatalogPage {
        var obj: [String: Any] = ["format":"bridgit-applet-catalog/1", "applets":rows]
        if let next { obj["next"] = next }
        return try JSONDecoder().decode(CatalogPage.self, from: JSONSerialization.data(withJSONObject: obj))
    }

    func testRejectsDuplicateKeysUnsafePathsAndWrongCursor() {
        XCTAssertThrowsError(try page([row,row]))
        XCTAssertThrowsError(try page([row.merging(["key":"../private"]) { _, new in new }]))
        XCTAssertThrowsError(try page([row], next:"unrelated"))
    }

    func testInstallReviewLinkContainsOnlyTheSelectedPin() throws {
        let entry = try XCTUnwrap(page([row]).applets.first)
        XCTAssertEqual(entry.installReviewURL?.absoluteString,
                       "https://bridgit.cc/install/?template=tpl_owner&revision=1")
    }

    func testLongLivedEditionRevisionRange() throws {
        for revision in [1461,Int(Int32.max)] {
            XCTAssertEqual(try page([row.merging(["revision":revision]) { _, new in new }]).applets.first?.revision,revision)
        }
        XCTAssertThrowsError(try page([row.merging(["revision":Int(Int32.max)+1]) { _, new in new }]))
    }

    func testAndroidMonitorForkCanBeReviewedAndExportedOnlyForItsExactPin() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let source = try Data(contentsOf: root.appendingPathComponent("applets/dog-monitor/editions/1.5.0/applet.json"))
        var definition = try XCTUnwrap(JSONSerialization.jsonObject(with: source) as? [String: Any])
        definition["id"] = "my-monitor"
        let entry = try XCTUnwrap(page([row.merging(["id":"my-monitor"]) { _, new in new }]).applets.first)
        func envelope(_ fields: [String: Any]) throws -> Data {
            try JSONSerialization.data(withJSONObject: ["state":"published", "key":entry.key,
                                                        "revision":entry.revision, "definition":definition].merging(fields) { _, new in new })
        }
        guard case .androidMonitor(let reviewed, let bytes) = try CatalogRelease(response: envelope([:]), selection: entry) else {
            return XCTFail("Monitor was offered for local execution")
        }
        XCTAssertEqual(reviewed.id, "my-monitor")
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: bytes), reviewed.definition)
        XCTAssertThrowsError(try AppletPackage(data: bytes))
        XCTAssertThrowsError(try RegistryPackage(response: envelope([:]), selection: entry))
        for fields: [String: Any] in [["key":"other"], ["state":"draft"], ["revision":2]] {
            XCTAssertThrowsError(try CatalogRelease(response: envelope(fields), selection: entry))
        }
        var assets = try XCTUnwrap(definition["assets"] as? [String: [String: Any]])
        assets["model.txt"]?["text"] = "modified"
        definition["assets"] = assets
        XCTAssertThrowsError(try CatalogRelease(response: envelope([:]), selection: entry))
    }

    func testRejectsWrongKeyRevisionPackageAndDraft() throws {
        let entry = try XCTUnwrap(page([row]).applets.first)
        var def: [String: Any] = ["format":"bridgit-template/1","id":"lamp","kind":"applet","version":"1.0.0","defaults":[:],
                                   "runtime":["adapter":"bridgit.package.v1","supports_dynamic_execution":false],
                                   "permissions":["automatic_grants":false]]
        let base: [String: Any] = ["key":"tpl_owner","revision":1,"state":"deprecated","definition":def]
        func open(_ changes: [String: Any]) throws -> RegistryPackage {
            try RegistryPackage(response: JSONSerialization.data(withJSONObject: base.merging(changes) { _, new in new }), selection: entry)
        }
        XCTAssertEqual(try open([:]).package.id, "lamp")
        def["id"] = "other"
        for changes: [String: Any] in [["revision":2],["revision":true],["key":"tpl_other"],["state":"draft"],["definition":def]] {
            XCTAssertThrowsError(try open(changes))
        }
    }
}
