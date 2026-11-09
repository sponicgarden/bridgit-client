import Foundation
import XCTest
@testable import BridgitCore

final class AppletDataContractTests: XCTestCase {
    private func setup() async throws -> (AppletStore, AppletEngine, HostAuthority, HostDispatcher, HostAgent) {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), engine = AppletEngine(store: store) { _, _, _ in XCTFail("Existing data triggered paid work"); return [:] }
        let data = try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Fixtures")))
        try await engine.install(data)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Table reader", operations: [.deviceRead, .appletsRead], applets: ["insurance-inventory"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        return (store, engine, authority, dispatcher, agent)
    }
    private func job(_ agent: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "fixture", deadline: now + 30000, createdAt: now, request: request)
    }
    private func object(_ response: HostResponse) throws -> [String: Any] { try JSONSerialization.jsonObject(with: response.data) as! [String: Any] }
    func testEveryRowIsDiscoverableBeyondFirstPageEvenIfCursorRowIsRemoved() async throws {
        let (store, _, _, dispatcher, agent) = try await setup()
        var all = Set<String>()
        for part in 0..<3 {
            let rows = try await store.addRows(applet: "insurance-inventory", table: "items", rows: (0..<100).map { ["label": "Fixture \(part)-\($0)"] })
            all.formUnion(rows.compactMap { $0["id"] as? String })
        }
        var seen = Set<String>(), next: String?, sequence: Int64 = 1
        repeat {
            var query: [String: Any] = ["limit": 100]
            if let next { query["since"] = next }
            let response = await dispatcher.dispatch(job(agent, sequence, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/tables/items/rows", query: query)))
            XCTAssertEqual(response.status, 200)
            let page = try object(response), rows = page["items"] as! [[String: Any]]
            XCTAssertFalse(rows.isEmpty)
            for row in rows { XCTAssertTrue(seen.insert(row["id"] as! String).inserted, "Duplicate row across pages") }
            next = page["next"] as? String
            if sequence == 1 { XCTAssertEqual(page["count"] as? Int, 300); try await store.delete(applet: "insurance-inventory", table: "items", rowID: rows.last!["id"] as! String) }
            sequence += 1
        } while next != nil && sequence < 10
        XCTAssertNil(next); XCTAssertEqual(seen, all)
        let invalid = await dispatcher.dispatch(job(agent, sequence, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/tables/items/rows", query: ["since": "bad-cursor"])))
        XCTAssertEqual(invalid.status, 422)
    }
    func testDataContractPreservesSchemasProcessingStatesAndSeparateMediaAuthority() async throws {
        let (store, engine, authority, dispatcher, reader) = try await setup()
        let photo = try await engine.addPhoto(applet: "insurance-inventory", jpeg: Data([1,2,3]))
        let ref = photo["photo"] as! String, key = String(ref.dropFirst(5)), base = "/v1/applets/installed/insurance-inventory"
        let discovered = try object(await dispatcher.dispatch(job(reader, 1, .init(method: "GET", path: base + "/data"))))
        XCTAssertEqual(discovered["contract"] as? String, "bridgit-applet-data/2")
        let table = (discovered["tables"] as! [[String: Any]]).first { $0["id"] as? String == "photos" }!
        XCTAssertEqual(table["cursor_parameter"] as? String, "since")
        let choices = table["choice_counts"] as! [String: [String: Any]]
        XCTAssertEqual((choices["status"]?["values"] as? [String: Int])?["new"], 1)
        XCTAssertEqual((discovered["photos"] as! [[String: Any]]).count, 0)
        XCTAssertEqual(discovered["media_complete"] as? Bool, false)
        let denied = await dispatcher.dispatch(job(reader, 2, .init(method: "GET", path: base + "/photos/" + key)))
        XCTAssertEqual(denied.status, 403, "Table readers must not silently gain image access")
        let (media, _) = try await authority.create(name: "Media reader", operations: [.appletsRead, .appletsMediaRead], applets: ["insurance-inventory"])
        let full = await dispatcher.dispatch(job(media, 1, .init(method: "GET", path: base + "/photos/" + key)))
        XCTAssertEqual(full.data, Data([1,2,3])); XCTAssertEqual(full.contentType, "image/jpeg")
        let chunk = await dispatcher.dispatch(job(media, 2, .init(method: "GET", path: base + "/photos/" + key, query: ["offset": 1, "length": 1])))
        XCTAssertEqual(chunk.status, 206); XCTAssertEqual(chunk.data, Data([2])); XCTAssertEqual(chunk.headers["Content-Range"], "bytes 1-1/3")
        let exported = try object(await dispatcher.dispatch(job(media, 3, .init(method: "GET", path: base + "/data/export"))))
        XCTAssertEqual(exported["media_complete"] as? Bool, true)
        let photos = exported["photos"] as! [[String: Any]]
        XCTAssertEqual(photos.first?["url"] as? String, base + "/photos/" + key)
        let deniedOther = await dispatcher.dispatch(job(media, 4, .init(method: "GET", path: "/v1/applets/installed/another/data")))
        XCTAssertEqual(deniedOther.status, 403)
        let unknown = await dispatcher.dispatch(job(media, 5, .init(method: "GET", path: base + "/data/export", query: ["format": "zip"])))
        XCTAssertEqual(unknown.status, 422)
        let stillThere = try await store.rows(applet: "insurance-inventory", table: "photos")
        XCTAssertEqual(stillThere.count, 1)
    }
    func testBatchDiscoveryUsesDeclaredNamesAndReportsInterruptedWorkAfterRelaunch() async throws {
        let (store, engine, _, dispatcher, reader) = try await setup()
        var package = try await store.package("insurance-inventory"), ui = package["ui"] as! [String: Any], card = ui["card"] as! [String: Any]
        var pieces = card["pieces"] as! [[String: Any]]
        for index in pieces.indices {
            guard var buttons = pieces[index]["items"] as? [[String: Any]] else { continue }
            for button in buttons.indices where buttons[button]["id"] as? String == "create" { buttons[button]["id"] = "inventory" }
            pieces[index]["items"] = buttons
        }
        var sources = card["sources"] as! [String: [String: Any]]
        sources["reading"] = ["batch": "inventory"]; card["sources"] = sources
        card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        let state: [String: Any] = ["last_button": "inventory", "states": ["inventory": ["state": "running", "total": 3, "completed": 1, "failed": 0]]]
        try await store.setState("applet-batch:insurance-inventory", value: String(decoding: JSONSerialization.data(withJSONObject: state), as: UTF8.self))
        let discovered = try object(await dispatcher.dispatch(job(reader, 1, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/data"))))
        let batches = discovered["batches"] as! [String: [String: Any]]
        XCTAssertEqual(Set(batches.keys), ["inventory"]); XCTAssertEqual(batches["inventory"]?["state"] as? String, "interrupted")
        XCTAssertEqual(batches["inventory"]?["completed"] as? Int, 1)
        card["pieces"] = []; sources.removeValue(forKey: "reading"); card["sources"] = sources; ui["card"] = card; package["ui"] = ui
        try await engine.install(JSONSerialization.data(withJSONObject: package))
        let empty = try object(await dispatcher.dispatch(job(reader, 2, .init(method: "GET", path: "/v1/applets/installed/insurance-inventory/data"))))
        XCTAssertTrue((empty["batches"] as! [String: Any]).isEmpty)
    }

}
