import XCTest
import CryptoKit
import CoreText
import SQLite3
import ImageIO
import UniformTypeIdentifiers
@testable import BridgitAppletRuntime

final class LocalAppletLibraryTests: XCTestCase {
    private func fixture(revision: Int = 1, title: String = "Blue note", steps: [[String: Any]] = [], change: (inout [String: Any]) -> Void = { _ in }) throws -> RegistryPackage {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        var doc = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("docs/applet-authoring-examples/png-icon.json"))) as? [String: Any])
        doc["name"] = title
        if !steps.isEmpty {
            doc["processing"] = ["workflow":["trigger":["type":"button"],"name":"Say hello","steps":steps] as [String:Any]]
            doc["permissions"] = ["required":["workflows","interactions"], "automatic_grants":false] as [String:Any]
        }
        change(&doc)
        let row: [String: Any] = ["key":"tpl_note","id":"blue-note","revision":revision,"name":title,"summary":"A local note"]
        let entry = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: row))
        let envelope: [String: Any] = ["key":entry.key,"revision":revision,"state":"published","definition":doc]
        return try RegistryPackage(response: JSONSerialization.data(withJSONObject: envelope), selection: entry)
    }

    func testVersionSixLibraryKeepsInstalledEditionAfterGalleryInputMigration() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let url = dir.appendingPathComponent("existing.sqlite"), account = String(repeating:"d",count:64)
        let release = try fixture()
        do {
            let previous = try LocalAppletLibrary(database:url,account:account)
            try await previous.install(release)
        }
        var raw: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path,&raw),SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(raw,"DROP TRIGGER remove_history_archive; DROP TABLE applet_history_archives; DROP TRIGGER remove_package_media; DROP TABLE applet_package_media; DROP TRIGGER remove_gallery_inputs; DROP TABLE applet_gallery_inputs; PRAGMA user_version=6;",nil,nil,nil),SQLITE_OK)
        sqlite3_close(raw)
        let upgraded = try LocalAppletLibrary(database:url,account:account)
        let installed = try await upgraded.installed(release.selection.key)!
        let opened = try await upgraded.open(installed)
        XCTAssertEqual(opened.data,release.data)
        XCTAssertEqual(sqlite3_open(url.path,&raw),SQLITE_OK)
        defer { sqlite3_close(raw) }
        var query: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(raw,"SELECT * FROM applet_gallery_inputs",-1,&query,nil),SQLITE_OK)
        defer { sqlite3_finalize(query) }
        XCTAssertEqual(sqlite3_step(query),SQLITE_DONE)
    }

    func testReceiptOCRReadsImageAndSavesReviewedTextWithOriginalInput() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let bytes = try Data(contentsOf:root.appendingPathComponent("site-content/public/recipes/receipt-scanner/applet.json"))
        var doc = try XCTUnwrap(JSONSerialization.jsonObject(with:bytes) as? [String:Any])
        doc["id"] = "blue-note"
        let release = try fixture { $0 = doc }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let url = dir.appendingPathComponent("receipt.sqlite"), account = String(repeating:"e",count:64)
        let db = try LocalAppletLibrary(database:url,account:account)
        try await db.install(release)
        let edition = try await db.installed("tpl_note")!
        let snapshot = try await db.open(edition)
        guard case .array(let pieces)? = release.package.definition["ui"]?["card"]?["pieces"] else { return XCTFail() }
        let buttons = pieces.flatMap { piece -> [JSONValue] in
            if case .array(let items)? = piece["items"] { return items }
            return []
        }
        let scan = try XCTUnwrap(buttons.first { release.package.ocrButton($0) != nil })
        let save = try XCTUnwrap(buttons.first(where:release.package.isDraftSave))
        let canvas = try XCTUnwrap(CGContext(data:nil,width:900,height:230,bitsPerComponent:8,bytesPerRow:0,
                                             space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue))
        canvas.setFillColor(CGColor(gray:1,alpha:1)); canvas.fill(CGRect(x:0,y:0,width:900,height:230))
        canvas.textPosition = CGPoint(x:40,y:100)
        let line = NSAttributedString(string:"RECEIPT TOTAL 42.50",attributes:[
            NSAttributedString.Key(kCTFontAttributeName as String):CTFontCreateWithName("Helvetica" as CFString,62,nil)])
        CTLineDraw(CTLineCreateWithAttributedString(line),canvas)
        let image = try XCTUnwrap(canvas.makeImage()), png = NSMutableData()
        let writer = try XCTUnwrap(CGImageDestinationCreateWithData(png,UTType.png.identifier as CFString,1,nil))
        CGImageDestinationAddImage(writer,image,nil)
        XCTAssertTrue(CGImageDestinationFinalize(writer))
        let (review,replies) = try await db.scanSelectedImage(png as Data,with:scan,replacing:snapshot)
        let text = try XCTUnwrap(replies["scan.text"]?.text)
        XCTAssertTrue(text.contains("42.50"),text)
        let untouched = try await db.rows(in:edition,table:"receipts",limit:5)
        XCTAssertTrue(untouched.isEmpty)
        let (repeatReview,_) = try await db.scanSelectedImage(png as Data,with:scan,replacing:review)
        XCTAssertEqual(repeatReview.config["gallery_id"],review.config["gallery_id"])
        try await db.recordDraft(save,in:edition,drafts:["receipt":"Verified total 42.50"],replies:replies,operation:UUID(),reviewed:repeatReview)
        let offline = try LocalAppletLibrary(database:url,account:account)
        let rows = try await offline.rows(in:edition,table:"receipts",limit:5)
        XCTAssertEqual(rows.first?["text"],.string("Verified total 42.50"))
        XCTAssertEqual(rows.first?["gallery_id"],review.config["gallery_id"])
        try await offline.saveSettings(["gallery_id":.number(99)],replacing:repeatReview)
        do {
            try await db.recordDraft(save,in:edition,drafts:["receipt":"stale"],replies:replies,operation:UUID(),reviewed:repeatReview)
            XCTFail("Draft saved against another image")
        } catch AppletRuntimeError.invalid {}
        let latest = try await db.open(edition)
        do {
            _ = try await db.scanSelectedImage(Data([0,1]),with:scan,replacing:latest)
            XCTFail("Invalid image accepted")
        } catch AppletRuntimeError.invalid {}
        let cardTable = try XCTUnwrap(pieces.first { $0["type"] == .string("table") })
        let rowID = try XCTUnwrap(rows.first?["id"]?.text)
        do {
            try await db.deleteDisplayedRow(rowID,from:scan,in:edition)
            XCTFail("Deletion accepted without table owner_delete")
        } catch AppletRuntimeError.unavailable {}
        try await db.deleteDisplayedRow(rowID,from:cardTable,in:edition)
        let afterDelete = try await offline.rows(in:edition,table:"receipts",limit:5)
        XCTAssertTrue(afterDelete.isEmpty)
    }

    func testLunaNamedHearingFlowResolvesDefaultButtonAndRecordsTranscript() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("docs/applet-authoring-trials/2026-11-06/luna/voice/applet.json"))
        var doc = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        let entryData = try JSONSerialization.data(withJSONObject:["key":"tpl_luna","id":doc["id"]!,"revision":1,"name":"Voice","summary":"Notes"])
        let entry = try JSONDecoder().decode(CatalogEntry.self,from:entryData)
        func pin() throws -> RegistryPackage {
            try RegistryPackage(response:JSONSerialization.data(withJSONObject:["key":entry.key,"revision":1,"state":"published","definition":doc]),selection:entry)
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let db = try LocalAppletLibrary(database:dir.appendingPathComponent("luna.sqlite"),account:String(repeating:"b",count:64))
        let release = try pin()
        let button = try XCTUnwrap(release.package.runButtons.first { $0.onCard })
        XCTAssertEqual(button.flow,"capture")
        try await db.install(release)
        let installed = try await db.installed(entry.key)!
        _ = try await db.runButton(button.flow,in:installed,hear:{ seconds,language in
            XCTAssertEqual(seconds,10)
            XCTAssertEqual(language,"en-US")
            return "Bring seeds"
        }) { _ in XCTFail("Unexpected notice") }
        let rows = try await db.rows(in:installed,table:"notes",limit:10)
        XCTAssertEqual(rows.first?["text"],.string("Bring seeds"))
        try await db.remove(installed)
        doc["permissions"] = ["required":["workflows","interactions"],"automatic_grants":false] as [String:Any]
        try await db.install(pin())
        let denied = try await db.installed(entry.key)!
        do {
            _ = try await db.runButton(button.flow,in:denied,hear:{ _,_ in XCTFail("Microphone opened"); return "" }) { _ in }
            XCTFail("Expected missing grant")
        } catch AppletRuntimeError.unavailable {}
    }

    func testVoiceNotebookReviewsDraftBeforeSavingAndDiscardsRevokedCapture() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("site-content/public/recipes/voice-notebook/applet.json"))
        var definition = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        definition["id"] = "blue-note" // An author's independently named edition
        let release = try fixture { $0 = definition }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let url = dir.appendingPathComponent("voice.sqlite")
        let account = String(repeating:"a",count:64)
        let db = try LocalAppletLibrary(database:url,account:account)
        try await db.install(release)
        let edition = try await db.installed("tpl_note")!
        guard case .array(let pieces)? = release.package.definition["ui"]?["card"]?["pieces"] else { return XCTFail() }
        let buttons = pieces.flatMap { piece -> [JSONValue] in
            if case .array(let items)? = piece["items"] { return items }
            return []
        }
        let mic = try XCTUnwrap(buttons.first { release.package.microphoneButton($0) != nil })
        let save = try XCTUnwrap(buttons.first(where: release.package.isDraftSave))
        let result = try await db.captureTranscript(mic,in:edition) { seconds in
            XCTAssertEqual(seconds,10)
            return "unreviewed text"
        }
        let beforeSave = try await db.rows(in:edition,table:"notes",limit:10)
        XCTAssertTrue(beforeSave.isEmpty)
        XCTAssertEqual(result["clip.transcript"],.string("unreviewed text"))
        let operation = UUID()
        for _ in 0..<2 {
            try await db.recordDraft(save,in:edition,drafts:["note":"edited words"],replies:result,operation:operation)
        }
        let reopened = try LocalAppletLibrary(database:url,account:account)
        let rows = try await reopened.rows(in:edition,table:"notes",limit:10)
        XCTAssertEqual(rows.count,1)
        XCTAssertEqual(rows[0]["text"],.string("edited words"))
        XCTAssertEqual(rows[0]["source_at"],result["clip.recorded_at"])
        do {
            _ = try await db.captureTranscript(mic,in:edition) { _ in
                try await reopened.remove(edition)
                return "discard me"
            }
            XCTFail("Returned a result for a removed edition")
        } catch AppletRuntimeError.revoked {}
        definition["permissions"] = ["automatic_grants":false,"required":["interactions"]] as [String:Any]
        let denied = try fixture { $0 = definition }
        XCTAssertNil(denied.package.microphoneButton(mic))
    }

    func testEditionServiceSelectorsCompileToReviewedHostOperations() throws {
        func compile(_ service: String, _ operation: String, extra: [String:JSONValue] = [:]) throws -> FlowStep {
            var fields = extra
            fields["type"] = .string("service")
            fields["service"] = .string(service)
            fields["operation"] = .string(operation)
            return try FlowStep(.object(fields))
        }
        let photo = JSONValue.object(["$trigger":.string("with.photo")])
        let scan = try compile("chef.signed-edition-budget.v2","scan",extra:["photo":photo])
        XCTAssertEqual(scan.type,"kitchen")
        XCTAssertEqual(scan.arguments,["mode":.string("scan"),"photo":photo])
        XCTAssertEqual(try compile("appraise.signed-edition-budget.v2","appraise").type,"appraise")
        XCTAssertThrowsError(try compile("appraise.signed-edition-budget.v2","scan"))
        XCTAssertThrowsError(try compile("chef.signed-edition-budget.v2","scan",extra:["mode":.string("observe")]))
        XCTAssertThrowsError(try compile("https://example.invalid","scan"))
    }

    func testCookingIntervalHonorsForkedCadenceAndRejectsMissingCameraGrantBeforeCapture() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("applets/chef-helper/applet.json"))
        let chef = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        var processing = try XCTUnwrap(chef["processing"] as? [String:Any])
        var flows = try XCTUnwrap(processing["flows"] as? [[String:Any]])
        let index = try XCTUnwrap(flows.firstIndex { ($0["id"] as? String) == "watch" })
        flows[index]["trigger"] = ["type":"interval","seconds":90] as [String:Any]
        processing["flows"] = flows
        let release = try fixture { doc in
            doc["processing"] = processing
            doc["permissions"] = ["required":["workflows","decisions","audio.speak"],"remote":["Send a reviewed frame."],"automatic_grants":false] as [String:Any]
            doc["data_policy"] = chef["data_policy"]
        }
        XCTAssertEqual(release.package.cookingInterval("watch"),90)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let db = try LocalAppletLibrary(database:dir.appendingPathComponent("library.sqlite"),account:String(repeating:"a",count:64))
        try await db.install(release)
        let installed = try await db.installed("tpl_note")
        let edition = try XCTUnwrap(installed)
        do {
            try await db.observeCooking("watch",in:edition,camera:"rear",goals:"",
                capture:{ _ in XCTFail("Camera opened without grant"); throw CancellationError() },
                decide:{ _,_ in XCTFail("Requested model without grant"); throw CancellationError() },
                observed:{ _,_ in XCTFail("Unexpected observation") })
            XCTFail("Expected refusal")
        } catch AppletRuntimeError.unavailable {}
        XCTAssertEqual(release.package.cookingTiming("watch"),.init(interval:90,session:3600,tipGap:45))
        flows[index]["steps"] = [["type":"service","service":"chef.signed-edition-budget.v2","operation":"observe","session_s":60,"tip_gap_s":20] as [String:Any]]
        processing["flows"] = flows
        let short = try fixture { $0["processing"] = processing }
        XCTAssertEqual(short.package.cookingTiming("watch"),.init(interval:90,session:60,tipGap:20))
        for invalid in [9,3601,10.5] {
            flows[index]["steps"] = [["type":"kitchen","mode":"observe","session_s":invalid] as [String:Any]]
            processing["flows"] = flows
            let rejected = try fixture { $0["processing"] = processing }
            XCTAssertNil(rejected.package.cookingTiming("watch"))
        }
        flows[index]["steps"] = [["type":"kitchen","mode":"observe"],["type":"notice","text":"Done"]]
        processing["flows"] = flows
        let complex = try fixture { $0["processing"] = processing }
        XCTAssertNil(complex.package.cookingInterval("watch"))
    }

    func testFairytaleCardPersistsScopedPaginatedStories() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("applets/a-sorta-fairytale-about-me/applet.json"))
        let definition = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        let key = "tpl_story"
        let row: [String:Any] = ["key":key,"id":definition["id"]!,"revision":1,"name":"My story","summary":""]
        let entry = try JSONDecoder().decode(CatalogEntry.self,from:JSONSerialization.data(withJSONObject:row))
        let release = try RegistryPackage(response:JSONSerialization.data(withJSONObject:[
            "key":key,"revision":1,"state":"published","definition":definition]),selection:entry)
        XCTAssertTrue(release.package.cardHTML?.contains("/v1/applet/tables/stories/rows") == true)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let url = dir.appendingPathComponent("stories.sqlite")
        let db = try LocalAppletLibrary(database:url,account:String(repeating:"a",count:64))
        try await db.install(release)
        let installed = try await db.installed(key)
        let edition = try XCTUnwrap(installed)
        actor Captures {
            var available = true
            func delete() { available = false }
            func read(_ id: String) throws -> Data {
                guard available else { throw AppletRuntimeError.invalid("Capture was deleted") }
                return Data([0xff,0xd8,0xff,0xd9])
            }
        }
        let captures = Captures()
        let story = JSONValue.object(["method":.string("POST"),"path":.string("/v1/story/generate"),
            "body":.object(["mode":.string("kind"),"frame_capture_ids":.array([.string("frame1"),.string("frame2")]),
                            "portrait_capture_id":.string("portrait")])])
        do {
            _ = try await db.cardStory(story,in:edition,capture:{ try await captures.read($0) }) { _,admit in
                await captures.delete() // A capture disappears while the owner is reviewing the request.
                try await admit()
                XCTFail("Admitted an upload after capture deletion")
                return .null
            }
            XCTFail("Unexpected story result")
        } catch is AppletRuntimeError {}
        let lookup = JSONValue.object(["path":.string("/v1/services/context"),"method":.string("POST"),
                                       "body":.object(["service":.string("places"),"latitude":.number(52),"longitude":.number(21)])])
        do {
            _ = try await db.cardContext(lookup,in:edition) { decision,_ in
                XCTAssertEqual(decision.packageID,entry.id)
                XCTAssertEqual(decision.flow,"places")
                throw CancellationError() // owner declined
            }
            XCTFail("Declined lookup succeeded")
        } catch is CancellationError {}
        let injected = JSONValue.object(["path":.string("/v1/services/context"),"method":.string("POST"),
                                         "body":.object(["service":.string("places"),"latitude":.number(52),
                                                         "longitude":.number(21),"url":.string("https://example.org")])])
        do {
            _ = try await db.cardContext(injected,in:edition) { _,_ in XCTFail("Accepted arbitrary destination"); return .null }
            XCTFail("Invalid request succeeded")
        } catch is AppletRuntimeError {}
        let path = JSONValue.string("/v1/applet/tables/stories/rows")
        var ids = [JSONValue]()
        for title in ["First tale","Second tale"] {
            let story = JSONValue.object(["status":.string("done"),"result":.object(["title":.string(title)])])
            let saved = try await db.cardTableAction(.object(["path":path,"method":.string("POST"),"body":.object(["row":story])]),in:edition)
            XCTAssertEqual(saved["by"]?["type"],.string("card"))
            ids.append(try XCTUnwrap(saved["id"]))
        }
        let reopened = try LocalAppletLibrary(database:url,account:String(repeating:"a",count:64))
        let first = try await reopened.cardTableAction(.object(["path":path,"query":.object(["limit":.number(1)])]),in:edition)
        XCTAssertEqual(first["count"],.number(2))
        XCTAssertEqual(first["next"],ids[0])
        let second = try await reopened.cardTableAction(.object(["path":path,"query":.object(["since":ids[0]])]),in:edition)
        XCTAssertEqual(second["next"],.null)
        guard case .array(let items)? = second["items"] else { return XCTFail("Missing story page") }
        XCTAssertEqual(items.map { $0["id"] },[ids[1]])
        let stranger = try LocalAppletLibrary(database:url,account:String(repeating:"b",count:64))
        for (store,route) in [(stranger,path),(db,.string("/v1/applet/tables/private/rows")),(db,.string("/v1/location"))] {
            do { _ = try await store.cardTableAction(.object(["path":route]),in:edition); XCTFail("Unauthorized card action") }
            catch is AppletRuntimeError {}
        }
    }

    func testOfflineButtonExecutionAndUnsupportedServicePreflight() async throws {
        actor Messages {
            var texts = [String]()
            func add(_ notice: LocalAppletLibrary.Notice) { texts.append(notice.text) }
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("applet.sqlite")
        let store = try LocalAppletLibrary(database:url,account:String(repeating:"c",count:64))
        let messages = Messages()
        let notice = ["id":"greeting","type":"notice","text":["Hello, ","owner"]] as [String:Any]
        try await store.install(fixture(steps:[notice]))
        let rows = try await store.editions()
        let edition = try XCTUnwrap(rows.first)
        let result = try await store.runButton("main",in:edition) { await messages.add($0) }
        XCTAssertNotNil(result.results["greeting"]?["interaction_id"]?.text)
        try await store.remove(edition)
        try await store.install(fixture(revision:2,steps:[notice,["type":"ask_model"]]))
        let nextRows = try await store.editions()
        let next = try XCTUnwrap(nextRows.first)
        do {
            _ = try await store.runButton("main",in:next) { await messages.add($0) }
            XCTFail("Unsupported service ran")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.unavailable("ask_model")) }
        let delivered = await messages.texts
        XCTAssertEqual(delivered,["Hello, owner"])
    }

    func testTerraCardExposesItsLabeledButtonAndRequiresItsHostServices() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("docs/applet-authoring-trials/2026-11-06/terra/applet.json"))
        let package = try AppletPackage(data:data)
        XCTAssertEqual(package.runButtons.map(\.label),["Check lamp"])
        XCTAssertEqual(package.runButtons.map(\.flow),["main"])
        XCTAssertTrue(try XCTUnwrap(package.runButtons.first).onCard)
        let stages = try package.steps(for:"main",configuration:package.defaults)
        XCTAssertEqual(stages.map(\.type),["photo","ask_model","notice"])
    }

    func testDetachedMediaInstallAndUpdateAreAtomicAndAccountScoped() async throws {
        let jpeg = try reviewedJPEG()
        let digest = SHA256.hash(data:jpeg).map { String(format:"%02x",$0) }.joined()
        func release(_ revision:Int, name:String) throws -> RegistryPackage {
            try fixture(revision:revision) { doc in
                var assets = doc["assets"] as? [String:Any] ?? [:]
                assets[name] = ["media_type":"image/jpeg","storage":"revision","size":jpeg.count,"sha256":digest] as [String:Any]
                doc["assets"] = assets
                if revision > 1 {
                    doc["version"] = "1.0.1"
                    doc["migration"] = ["from":"1.0.0","strategy":"explicit_owner_opt_in","rules":[]]
                } else { doc["version"] = "1.0.0" }
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let owner = String(repeating:"a",count:64)
        let store = try LocalAppletLibrary(database:url,account:owner)
        let first = try release(1,name:"guide.jpg")
        do { try await store.install(first); XCTFail("Missing media installed") } catch is AppletRuntimeError {}
        let absent = try await store.installed(first.selection.key)
        XCTAssertNil(absent)
        try await store.install(first,media:["guide.jpg":jpeg])
        let reopened = try LocalAppletLibrary(database:url,account:owner)
        let installed = try await reopened.installed(first.selection.key)!
        let oldBytes = try await reopened.media(named:"guide.jpg",in:installed)
        XCTAssertEqual(oldBytes,jpeg)
        let foreign = try LocalAppletLibrary(database:url,account:String(repeating:"b",count:64))
        do { _ = try await foreign.media(named:"guide.jpg",in:installed); XCTFail("Cross-account read") } catch is AppletRuntimeError {}
        let plan = try await reopened.reviewUpdate(release(2,name:"new.jpg"),replacing:installed)
        do { try await reopened.apply(plan,media:["new.jpg":Data(repeating:0,count:jpeg.count)]); XCTFail("Corruption accepted") } catch is AppletRuntimeError {}
        let preserved = try await reopened.media(named:"guide.jpg",in:installed)
        XCTAssertEqual(preserved,jpeg)
        try await reopened.apply(plan,media:["new.jpg":jpeg])
        let updated = try await reopened.installed(first.selection.key)!
        XCTAssertEqual(updated.revision,2)
        let replacement = try await reopened.media(named:"new.jpg",in:updated)
        XCTAssertEqual(replacement,jpeg)
        try await reopened.remove(updated)
        do { _ = try await reopened.media(named:"new.jpg",in:updated); XCTFail("Removed media visible") } catch is AppletRuntimeError {}
    }

    private func reviewedJPEG() throws -> Data {
        let context = try XCTUnwrap(CGContext(data:nil,width:2,height:2,bitsPerComponent:8,bytesPerRow:8,
                                               space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue))
        let bytes = NSMutableData()
        let writer = try XCTUnwrap(CGImageDestinationCreateWithData(bytes, UTType.jpeg.identifier as CFString,1,nil))
        CGImageDestinationAddImage(writer, try XCTUnwrap(context.makeImage()), nil)
        XCTAssertTrue(CGImageDestinationFinalize(writer))
        return bytes as Data
    }

    func testChefAndInventoryDeclaredCamerasStoreReviewedPhotosInTheirOwnTables() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let db = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:db) }
        let store = try LocalAppletLibrary(database:db,account:String(repeating:"c",count:64))
        let jpeg = try reviewedJPEG()
        for (id,expectedButtons) in [("chef-helper",3),("insurance-inventory",1)] {
            let data = try Data(contentsOf:root.appendingPathComponent("applets/" + id + "/applet.json"))
            let doc = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
            let listing: [String:Any] = ["key":id,"id":id,"name":doc["name"]!,"summary":doc["summary"]!,"revision":1]
            let entry = try JSONDecoder().decode(CatalogEntry.self,from:JSONSerialization.data(withJSONObject:listing))
            let envelope: [String:Any] = ["key":id,"revision":1,"state":"published","definition":doc]
            try await store.install(RegistryPackage(response:JSONSerialization.data(withJSONObject:envelope),selection:entry))
            let selected = try await store.installed(id)
            let edition = try XCTUnwrap(selected)
            let package = try await store.open(edition).package
            guard case .array(let pieces)? = package.definition["ui"]?["card"]?["pieces"] else { return XCTFail("Missing card") }
            let items = pieces.flatMap { piece -> [JSONValue] in
                guard case .array(let entries)? = piece["items"] else { return [] }
                return entries.filter { package.cameraButton($0) != nil }
            }
            XCTAssertEqual(items.count,expectedButtons)
            for item in items {
                let rowID = try await store.captureCardPhoto(item,in:edition) { lens,size in
                    XCTAssertEqual(lens,"rear"); XCTAssertEqual(size,.large)
                    return .init(jpeg:jpeg,camera:lens)
                }
                let rows = try await store.rows(in:edition,table:"photos",limit:1)
                let row = try XCTUnwrap(rows.first)
                XCTAssertEqual(row["id"],.string(rowID))
                XCTAssertEqual(row["status"],.string("new"))
                XCTAssertEqual(row["by"]?["type"],.string("camera"))
                if id == "insurance-inventory" { XCTAssertNotNil(row["taken_at"]?.text) }
                else { XCTAssertNil(row["taken_at"]) }
                if id == "chef-helper" { XCTAssertEqual(row["area"],item["do"]?["camera"]?["row"]?["area"]) }
                let ref = try XCTUnwrap(row["photo"]?.text)
                let retained = try await store.photo(ref,in:edition)
                XCTAssertEqual(retained,jpeg)
                if id == "insurance-inventory" {
                    let piece = try XCTUnwrap(pieces.first { $0["type"] == .string("gallery") })
                    let gallery = try await store.gallery(piece,in:edition)
                    XCTAssertEqual(gallery.map(\.reference),[ref])
                    XCTAssertEqual(gallery.map(\.caption),["New"])
                }
            }
            let batchItem = try XCTUnwrap(pieces.compactMap { piece -> JSONValue? in
                guard case .array(let entries)? = piece["items"] else { return nil }
                return entries.first { package.batchButton($0) != nil }
            }.first)
            let other = try LocalAppletLibrary(database:db,account:String(repeating:"c",count:64))
            let judge: LocalAppletLibrary.Decide = { request,reserve in
                XCTAssertEqual(request.service,id == "chef-helper" ? .chef : .appraise)
                XCTAssertEqual(request.jpeg,jpeg)
                do {
                    _ = try await other.runCardBatch(batchItem,in:edition,decide:nil) { _ in XCTFail() }
                    XCTFail("Concurrent batch was admitted")
                } catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Another applet batch is running")) }
                try await reserve()
                return .object(["items":.array([.object(["label":.string("Bowl"),"category":.string("Cupboard"),
                    "box":.object(["x0":.number(0),"x1":.number(1),"y0":.number(0),"y1":.number(1)])])])])
            }
            // Interrupt the status update after the items have committed. Reopening must reuse
            // both the paid response and the item IDs when the owner runs the button again.
            var fault: OpaquePointer?
            XCTAssertEqual(sqlite3_open(db.path,&fault),SQLITE_OK)
            XCTAssertEqual(sqlite3_exec(fault,"CREATE TRIGGER deny_mark BEFORE UPDATE ON applet_rows BEGIN SELECT RAISE(ABORT,'interrupted'); END",nil,nil,nil),SQLITE_OK)
            do {
                _ = try await store.runCardBatch(batchItem,in:edition,decide:judge) { _ in XCTFail() }
                XCTFail("Expected the status write to fail")
            } catch { XCTAssertTrue(error.localizedDescription.contains("interrupted"),error.localizedDescription) }
            XCTAssertEqual(sqlite3_exec(fault,"DROP TRIGGER deny_mark",nil,nil,nil),SQLITE_OK)
            sqlite3_close(fault)
            actor Count {
                var calls = 0
                func add() { calls += 1 }
            }
            let count = Count()
            let completed = try await other.runCardBatch(batchItem,in:edition,decide:{ request,reserve in
                await count.add()
                return try await judge(request,reserve)
            }) { _ in XCTFail() }
            XCTAssertEqual(completed,expectedButtons)
            let calls = await count.calls
            XCTAssertEqual(calls,expectedButtons-1)
            let saved = try await store.rows(in:edition,table:id == "chef-helper" ? "ingredients" : "items",limit:10)
            XCTAssertEqual(saved.count,expectedButtons)
            XCTAssertTrue(saved.allSatisfy { $0["label"] == .string("Bowl") })
            let captured = try await store.rows(in:edition,table:"photos",limit:10)
            XCTAssertTrue(captured.allSatisfy { $0["status"] == .string("done") && $0["items"] == .number(1) })
            let alreadyDone = try await store.runCardBatch(batchItem,in:edition,decide:nil) { _ in XCTFail() }
            XCTAssertEqual(alreadyDone,0)
            let freshPhoto = try await store.captureCardPhoto(items[0],in:edition) { lens,_ in .init(jpeg:jpeg,camera:lens) }
            do {
                _ = try await store.runCardBatch(batchItem,in:edition,decide:{ _,reserve in
                    var external: OpaquePointer?
                    XCTAssertEqual(sqlite3_open(db.path,&external),SQLITE_OK)
                    defer { sqlite3_close(external) }
                    var expire: OpaquePointer?
                    XCTAssertEqual(sqlite3_prepare_v2(external,"UPDATE applet_rows SET expires=0 WHERE lower(replace(replace(effect,'-',''),':',''))=?",-1,&expire,nil),SQLITE_OK)
                    defer { sqlite3_finalize(expire) }
                    _ = freshPhoto.withCString { sqlite3_bind_text(expire,1,$0,-1,unsafeBitCast(-1,to:sqlite3_destructor_type.self)) }
                    XCTAssertEqual(sqlite3_step(expire),SQLITE_DONE)
                    try await reserve()
                    XCTFail("Charged for a photo that expired during owner approval")
                    return .null
                }) { _ in XCTFail() }
                XCTFail("Stale source was processed")
            } catch { XCTAssertEqual(error as? AppletRuntimeError,.revoked) }
            let first = try XCTUnwrap(items.first)
            do {
                _ = try await store.captureCardPhoto(first,in:edition) { _,_ in
                    try await store.remove(edition)
                    return .init(jpeg:jpeg,camera:"rear")
                }
                XCTFail("Saved a capture after uninstall")
            } catch { XCTAssertEqual(error as? AppletRuntimeError,.revoked) }
        }
    }

    func testReviewedCaptureKeepsOnlyAcceptedInstancePhotos() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let owner = String(repeating:"a", count:64)
        let library = try LocalAppletLibrary(database:url, account:owner)
        let release = try fixture(steps:[
            ["id":"shot","type":"photo","keep":true,"camera":"front"],
            ["id":"save","type":"record","table":"photos","row":["image":["$step":"shot.photo"]]]
        ]) { doc in
            doc["permissions"] = ["required":["workflows","camera.snapshot"],"automatic_grants":false] as [String:Any]
            var processing = doc["processing"] as! [String:Any]
            processing["tables"] = [["id":"photos","columns":["image":["type":"photo"]]] as [String:Any]]
            doc["processing"] = processing
        }
        try await library.install(release)
        let installed = try await library.installed(release.selection.key)
        let edition = try XCTUnwrap(installed)
        let jpeg = try reviewedJPEG()
        do {
            _ = try await library.runButton("main", in:edition, capture:{ _ in throw CancellationError() }) { _ in }
            XCTFail("Cancelled camera completed")
        } catch is CancellationError {}
        let outcome = try await library.runButton("main",in:edition,capture:{ lens in
            XCTAssertEqual(lens,"front")
            return .init(jpeg:jpeg,camera:"rear") // the owner flipped it in the viewfinder
        }) { _ in }
        let ref = try XCTUnwrap(outcome.results["shot"]?["kept"]?.text)
        XCTAssertEqual(outcome.results["shot"]?["camera"],.string("rear"))
        let fresh = try LocalAppletLibrary(database:url,account:owner)
        let rows = try await fresh.rows(in:edition,table:"photos",limit:20)
        XCTAssertEqual(rows.map { $0["image"] },[.string(ref)])
        var storage: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path,&storage),SQLITE_OK)
        defer { sqlite3_close(storage) }
        XCTAssertEqual(sqlite3_exec(storage, "UPDATE applet_photos SET created=unixepoch()-604801", nil,nil,nil), SQLITE_OK)
        // The retention sweep must keep an old JPEG while an unexpired row still points to it.
        _ = try LocalAppletLibrary(database:url,account:owner)
        let reopened = try await fresh.photo(ref,in:edition)
        XCTAssertEqual(reopened,jpeg)
        let other = try LocalAppletLibrary(database:url,account:String(repeating:"b",count:64))
        do { _ = try await other.photo(ref,in:edition); XCTFail("Cross-owner photo read") }
        catch { XCTAssertEqual(error as? AppletRuntimeError,.revoked) }
        XCTAssertEqual(sqlite3_exec(storage, "UPDATE applet_rows SET expires=unixepoch()-1",nil,nil,nil),SQLITE_OK)
        do { _ = try await fresh.photo(ref,in:edition); XCTFail("Expired row retained access") }
        catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Photo unavailable")) }
        _ = try LocalAppletLibrary(database:url,account:owner)
        var count: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(storage,"SELECT count(*) FROM applet_photos",-1,&count,nil),SQLITE_OK)
        XCTAssertEqual(sqlite3_step(count),SQLITE_ROW)
        XCTAssertEqual(sqlite3_column_int(count,0),0)
        sqlite3_finalize(count)
        do {
            _ = try await fresh.runButton("main",in:edition,capture:{ _ in
                try await library.remove(edition)
                return .init(jpeg:jpeg,camera:"front")
            }) { _ in }
            XCTFail("Saved a capture after removal during review")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.revoked) }
        try await library.install(release)
        let next = try await library.installed(release.selection.key)
        do { _ = try await library.photo(ref,in:XCTUnwrap(next)); XCTFail("Exposed removed photo") }
        catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Photo unavailable")) }
    }

    func testCameraPreflightBeforeViewfinderOrEarlierEffects() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let library = try LocalAppletLibrary(database:url, account:String(repeating:"c", count:64))
        let capture: LocalAppletLibrary.CapturePhoto = { _ in
            XCTFail("Opened a camera for a flow that cannot run")
            throw CancellationError()
        }
        func check(_ extra: [[String:Any]], scopes: [String], expected: AppletRuntimeError) async throws {
            let package = try fixture(steps:[["type":"notice","title":"Ready"], ["type":"photo"]] + extra) {
                $0["permissions"] = ["required":scopes,"automatic_grants":false] as [String:Any]
            }
            try await library.install(package)
            let installed = try await library.installed(package.selection.key)
            let edition = try XCTUnwrap(installed)
            do {
                _ = try await library.runButton("main", in:edition, capture:capture) { _ in XCTFail("Unexpected notice") }
                XCTFail("Preflight succeeded")
            } catch { XCTAssertEqual(error as? AppletRuntimeError,expected) }
            try await library.remove(edition)
        }
        try await check([], scopes:["workflows","interactions"], expected:.unavailable("photo"))
        try await check([["type":"ask_model","model":"clef"]], scopes:["workflows","interactions","camera.snapshot","decisions"],
                        expected:.unavailable("ask_model"))
    }

    func testPetRecipeRunsPackageClassificationAndSavesTheOwnerCheck() async throws {
        try await exercisePetPackage(path:"site-content/public/recipes/pet-monitor/applet.json", key:"pet-monitor")
    }

    func testPortableDogEditionRunsAndPersistsOwnerChecks() async throws {
        try await exercisePetPackage(path:"applets/dog-monitor/applet.json", key:"dog-monitor")
    }

    private func exercisePetPackage(path: String, key: String) async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent(path))
        let doc = try JSONSerialization.jsonObject(with:data)
        let selectionData = try JSONSerialization.data(withJSONObject:["id":key,"key":key,"revision":3,"name":"Pet activity monitor","summary":"Check activity"])
        let entry = try JSONDecoder().decode(CatalogEntry.self,from:selectionData)
        let envelope: [String:Any] = ["key":entry.key,"revision":entry.revision,"state":"published","definition":doc]
        let release = try RegistryPackage(response:JSONSerialization.data(withJSONObject:envelope),selection:entry)
        let db = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:db) }
        let owner = String(repeating:"c",count:64)
        let store = try LocalAppletLibrary(database:db,account:owner)
        try await store.install(release)
        let selection = try await store.installed(entry.key)
        let installed = try XCTUnwrap(selection)
        if key == "dog-monitor" {
            let archive: [String:Any] = [
                "format":"bridgit-dog-monitor-history/1","package_id":key,"instance_id":"ai123",
                "package_sha256":String(repeating:"a",count:64),"settings":["pan_search":true],
                "sound_episodes":["episodes":2],"sound_readings":[],
                "tracker":["readings":[],"spans":[[100,200]],"episodes":[["kind":"eating","start":100,"last_seen":200,"frames":2]]]
            ]
            let bytes = try JSONSerialization.data(withJSONObject:archive)
            _ = try await store.importDogHistory(bytes,into:installed)
            let episodes = try await store.importDogHistory(bytes,into:installed)
            XCTAssertEqual(episodes.first?.lastSeen,200)
            let reopened = try LocalAppletLibrary(database:db,account:owner)
            let original = try await reopened.dogHistoryBytes(in:installed)
            XCTAssertEqual(original,bytes)
            do {
                _ = try await reopened.importDogHistory(bytes + Data([32]),into:installed)
                XCTFail("A different archive replaced the original")
            } catch is AppletRuntimeError {}
            let other = try LocalAppletLibrary(database:db,account:String(repeating:"a",count:64))
            do { _ = try await other.dogHistoryBytes(in:installed); XCTFail("Cross-account history read") }
            catch is AppletRuntimeError {}
        }
        let button = try XCTUnwrap(release.package.runButtons.first { $0.label == "Check pet activity" })
        let before = try await store.open(installed)
        var settings = before.config
        settings["camera"] = .string("front")
        settings["interval_s"] = .number(0)
        do { try await store.saveSettings(settings,replacing:before); XCTFail("Accepted invalid interval") }
        catch is AppletRuntimeError {}
        let afterInvalid = try await store.open(installed)
        XCTAssertEqual(afterInvalid.config,before.config)
        settings["interval_s"] = .number(60)
        try await store.saveSettings(settings,replacing:before)
        let secondEditor = try LocalAppletLibrary(database:db,account:owner)
        let reopenedSettings = try await secondEditor.open(installed)
        XCTAssertEqual(reopenedSettings.config,settings)
        do { try await secondEditor.saveSettings(before.config,replacing:before); XCTFail("Stale editor discarded the camera change") }
        catch is AppletRuntimeError {}
        let stranger = try LocalAppletLibrary(database:db,account:String(repeating:"b",count:64))
        do { try await stranger.saveSettings(before.config,replacing:reopenedSettings); XCTFail("Cross-account write") }
        catch is AppletRuntimeError {}
        let jpeg = try reviewedJPEG()
        for activity in ["playing","resting"] {
            _ = try await store.runButton(button.flow,in:installed,capture:{ lens in
                XCTAssertEqual(lens,"front")
                return .init(jpeg:jpeg,camera:lens)
            },decide:{ request,reserve in
                XCTAssertEqual(request.jpeg,jpeg)
                if key == "dog-monitor" {
                    XCTAssertEqual(Set(request.questions.keys), ["activity", "mood", "dog_present"])
                    XCTAssertEqual(request.questions["activity"]?["criteria"]?["playing"],.string("Playing with a toy, a person or another animal."))
                } else {
                    XCTAssertEqual(request.questions["activity"]?["criteria"]?["playing"],.string("Visible play with an object, person or another animal."))
                }
                try await reserve()
                return .object(["answers":.object(["activity":.string(activity), "mood":.string("relaxed"), "dog_present":.number(0.9)]),
                                "confidence":.object(["activity":.number(0.85)])])
            }) { notice in XCTAssertEqual(notice.text,"Observed activity: " + activity) }
        }
        let fresh = try LocalAppletLibrary(database:db,account:owner)
        let rows = try await fresh.rows(in:installed,table:"checks",limit:10)
        XCTAssertEqual(rows.map { $0["activity"] },[.string("resting"),.string("playing")])
        if key == "dog-monitor" {
            XCTAssertEqual(rows.first?["mood"],.string("relaxed"))
            XCTAssertEqual(rows.first?["dog_present"],.number(0.9))
            XCTAssertEqual(rows.first?["activity_confidence"],.number(0.85))
        }
        guard case .array(let pieces)? = release.package.definition["ui"]?["card"]?["pieces"] else { return XCTFail("Missing card") }
        let history = try XCTUnwrap(pieces.first { $0["items"]?["$source"] == .string("owner_checks.items") })
        let entries = try await fresh.timeline(history,in:installed)
        XCTAssertEqual(entries.map(\.label),["Resting","Playing"])
        var limited = try XCTUnwrap(history.fields)
        limited["limit"] = .number(1)
        let newest = try await fresh.timeline(.object(limited),in:installed)
        XCTAssertEqual(newest.map(\.id),[entries[0].id])
        XCTAssertEqual(entries.first?.id,rows.first?["id"]?.text)
        let scheduled = try XCTUnwrap(pieces.first { $0["items"]?["$source"] == .string("activity.episodes") })
        do { _ = try await fresh.timeline(scheduled,in:installed); XCTFail("Watch coverage presented as local history") }
        catch { XCTAssertEqual(error as? AppletRuntimeError,.unavailable("this timeline source or format")) }
    }

    func testInstalledTerraFlowBindsReviewedPhotoToOnePaidAdmission() async throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let source = root.appendingPathComponent("docs/applet-authoring-trials/2026-11-06/terra/reviewed/applet.json")
        let doc = try JSONSerialization.jsonObject(with:Data(contentsOf:source))
        let key = "tpl_terra"
        let entry = try JSONDecoder().decode(CatalogEntry.self,from:Data(#"{"key":"tpl_terra","id":"desk-lamp-check","revision":1,"name":"Lamp","summary":"Check lamp"}"#.utf8))
        let envelope: [String:Any] = ["key":key,"revision":1,"state":"published","definition":doc]
        let release = try RegistryPackage(response:JSONSerialization.data(withJSONObject:envelope),selection:entry)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let owner = String(repeating:"a",count:64)
        let store = try LocalAppletLibrary(database:url,account:owner)
        try await store.install(release)
        let installed = try await store.installed(key)
        let edition = try XCTUnwrap(installed)
        let jpeg = try reviewedJPEG()
        let capture: LocalAppletLibrary.CapturePhoto = { camera in .init(jpeg:jpeg,camera:camera) }
        let judge: LocalAppletLibrary.Decide = { request, reserve in
            XCTAssertEqual(request.owner,owner)
            XCTAssertEqual(request.jpeg,jpeg)
            XCTAssertEqual(request.questions["lamp"]?["type"],.string("choice"))
            try await reserve()
            return .object(["answers":.object(["lamp":.string("lit")])])
        }
        do {
            _ = try await store.runButton("main",in:edition,capture:capture,decide:{ _,_ in throw CancellationError() }) { _ in XCTFail() }
            XCTFail("Owner declined")
        } catch is CancellationError {}
        for _ in 0..<10 {
            let reopened = try LocalAppletLibrary(database:url,account:owner)
            let result = try await reopened.runButton("main",in:edition,capture:capture,decide:judge) { notice in
                XCTAssertEqual(notice.title,"Lamp appears lit")
            }
            XCTAssertTrue(result.skipped.isEmpty)
        }
        do {
            _ = try await store.runButton("main",in:edition,capture:capture,decide:judge) { _ in XCTFail() }
            XCTFail("Exceeded the pinned workflow's daily limit")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Workflow daily paid-call limit reached")) }
    }

    func testBatchRecordingResolvesItemCellsAndRollsBackInvalidLaterItem() async throws {
        let db = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:db) }
        let store = try LocalAppletLibrary(database:db,account:String(repeating:"d",count:64))
        let release = try fixture(steps:[
            ["id":"look","type":"ask_model","model":"clef","questions":["seen":["type":"noul","instructions":"Visible?"]]],
            ["id":"save","type":"record","table":"ingredients","for_each":["$step":"look.items"],"row":["label":["$item":"label"]]]
        ]) { doc in
            var processing = doc["processing"] as! [String:Any]
            processing["tables"] = [["id":"ingredients","columns":["label":["type":"text"]]] as [String:Any]]
            doc["processing"] = processing
            doc["permissions"] = ["required":["workflows","decisions"],"remote":["Review items"],"automatic_grants":false] as [String:Any]
            doc["data_policy"] = ["device_only":["remote_models":true]]
        }
        try await store.install(release)
        let selection = try await store.installed(release.selection.key)
        let edition = try XCTUnwrap(selection)
        func run(_ labels: [JSONValue]) async throws -> FlowOutcome {
            try await store.runButton("main",in:edition,decide:{ _,_ in
                .object(["items":.array(labels.map { .object(["label":$0]) })])
            }) { _ in XCTFail() }
        }
        let empty = try await run([])
        XCTAssertEqual(empty.results["save"]?["count"],.number(0))
        let good = try await run([.string("Salt"),.string("Flour")])
        XCTAssertEqual(good.results["save"]?["count"],.number(2))
        guard case .array(let ids)? = good.results["save"]?["row_ids"] else { return XCTFail("Expected row IDs") }
        XCTAssertEqual(Set(ids.compactMap(\.text)).count,2)
        do {
            _ = try await run([.string("Uncommitted"),.bool(false)])
            XCTFail("Accepted invalid second row")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Unsupported value for table column label")) }
        let rows = try await store.rows(in:edition,table:"ingredients",limit:10)
        XCTAssertEqual(rows.compactMap { $0["label"]?.text },["Flour","Salt"])
    }

    func testInstalledFlowUpdatesOnlyItsOwnLiveRow() async throws {
        let db = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:db) }
        let store = try LocalAppletLibrary(database:db,account:String(repeating:"a",count:64))
        func package(_ steps: [[String:Any]]) throws -> RegistryPackage {
            try fixture(steps:steps) { doc in
                var processing = doc["processing"] as! [String:Any]
                processing["tables"] = [["id":"photos","columns":["label":["type":"text"],"status":["type":"text"],"image":["type":"photo"]]] as [String:Any]]
                doc["processing"] = processing
            }
        }
        let create: [String:Any] = ["id":"save","type":"record","table":"photos","row":["label":"Cupboard","status":"queued"]]
        let edit: [String:Any] = ["id":"mark","type":"update","table":"photos","row":["$step":"save.row_id"],"set":["status":"done"]]
        let release = try package([create,edit])
        try await store.install(release)
        let installed = try await store.installed(release.selection.key)
        let edition = try XCTUnwrap(installed)
        let result = try await store.runButton("main",in:edition) { _ in XCTFail() }
        let id = try XCTUnwrap(result.results["save"]?["row_id"]?.text)
        XCTAssertEqual(result.results["mark"]?["row_id"],.string(id))
        let reopened = try LocalAppletLibrary(database:db,account:String(repeating:"a",count:64))
        let rows = try await reopened.rows(in:edition,table:"photos",limit:10)
        XCTAssertEqual(rows.count,1)
        XCTAssertEqual(rows.first?["label"],.string("Cupboard"))
        XCTAssertEqual(rows.first?["status"],.string("done"))

        let stranger = try LocalAppletLibrary(database:db,account:String(repeating:"b",count:64))
        var cross = edit
        cross["row"] = id
        let other = try package([cross])
        try await stranger.install(other)
        let selection = try await stranger.installed(other.selection.key)
        let privateEdition = try XCTUnwrap(selection)
        do {
            _ = try await stranger.runButton("main",in:privateEdition) { _ in XCTFail() }
            XCTFail("Updated another instance’s row")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.invalid("Row no longer available")) }
    }

    func testSolJournalCardQuestionAndPrivatePersistentRecord() async throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf:root.appendingPathComponent("docs/applet-authoring-trials/2026-11-06/sol/applet.json"))
        let package = try AppletPackage(data:data)
        let catalog: [String:Any] = ["key":"tpl_sol","id":package.id,"name":"Journal","summary":"Soil","revision":1]
        let entry = try JSONDecoder().decode(CatalogEntry.self,from:JSONSerialization.data(withJSONObject:catalog))
        let envelope: [String:Any] = ["key":entry.key,"revision":1,"state":"published","definition":try JSONSerialization.jsonObject(with:data)]
        let release = try RegistryPackage(response:JSONSerialization.data(withJSONObject:envelope),selection:entry)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let owner = String(repeating:"a",count:64)
        let library = try LocalAppletLibrary(database:url,account:owner)
        try await library.install(release)
        let installed = try await library.installed(entry.key)
        let edition = try XCTUnwrap(installed)
        let button = try XCTUnwrap(package.runButtons.first)
        XCTAssertEqual(button.label,"Check soil")
        let outcome = try await library.runButton(button.flow,in:edition,ask:{ prompt in
            XCTAssertEqual(prompt.title,"Check the soil")
            XCTAssertEqual(prompt.text,"What does the soil of Kitchen basil feel like?")
            XCTAssertEqual(prompt.choices,["Dry","Damp","Unsure"])
            return "Damp"
        }) { _ in XCTFail("Unexpected notice") }
        let fresh = try LocalAppletLibrary(database:url,account:owner)
        let rows = try await fresh.rows(in:edition,table:"observations",limit:20)
        XCTAssertEqual(rows.count,1)
        XCTAssertEqual(rows.first?["id"], outcome.results["save"]?["row_id"])
        XCTAssertEqual(rows.first?["plant"],.string("Kitchen basil"))
        XCTAssertEqual(rows.first?["moisture"],.string("Damp"))
        let other = try LocalAppletLibrary(database:url,account:String(repeating:"b",count:64))
        let concealed = try await other.rows(in:edition,table:"observations",limit:20)
        XCTAssertTrue(concealed.isEmpty)
        do {
            _ = try await fresh.runButton(button.flow,in:edition,ask:{ _ in "unknown" }) { _ in }
            XCTFail("Accepted an answer outside the package choices")
        } catch is AppletRuntimeError {}
        let remaining = try await fresh.rows(in:edition,table:"observations",limit:20)
        XCTAssertEqual(remaining,rows)
        do {
            _ = try await fresh.runButton(button.flow,in:edition,ask:{ _ in
                try await library.remove(edition)
                return "Dry"
            }) { _ in }
            XCTFail("Recorded after uninstall")
        } catch { XCTAssertEqual(error as? AppletRuntimeError,.revoked) }
        try await fresh.install(release)
        let next = try await fresh.installed(entry.key)
        let newRows = try await fresh.rows(in:XCTUnwrap(next),table:"observations",limit:20)
        XCTAssertTrue(newRows.isEmpty)
    }

    func testUnansweredOwnerQuestionTimesOutAndSkipsDependentStep() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let library = try LocalAppletLibrary(database:url,account:String(repeating:"a",count:64))
        let release = try fixture(steps:[
            ["id":"ask","type":"ask_owner","choices":["Yes","No"],"title":"Continue?","text":"Choose","wait_s":30],
            ["id":"notify","type":"notice","only_if":["step":"ask.choice","exists":true],"text":"Answered"]
        ])
        try await library.install(release)
        let installed = try await library.installed(release.selection.key)
        let result = try await library.runButton("main",in:XCTUnwrap(installed),ask:{ _ in
            try await Task.sleep(nanoseconds:120_000_000_000)
            XCTFail("The timeout should cancel the pending question")
            return "Yes"
        }) { _ in XCTFail("Timed-out question triggered a dependent step") }
        XCTAssertEqual(result.results["ask"], .object(["timed_out":.bool(true)]))
        XCTAssertEqual(result.skipped,[1])
    }

    func testStructuredStoryColumnPreservesObjectsAndEnforcesAndroidLimits() throws {
        let release = try fixture { doc in
            doc["processing"] = ["tables":[["id":"stories","columns":["result":["type":"json","description":"Story title and narration"]]]]]
        }
        let table = try XCTUnwrap(LocalRecordTable.declarations(release.package)["stories"])
        let story = JSONValue.object(["title":.string("A raven"),"narration":.string("Once upon a time")])
        let encoded = try table.payload(["result":story])
        XCTAssertEqual(try JSONDecoder().decode([String:JSONValue].self,from:encoded)["result"],story)
        var nested = JSONValue.string("leaf")
        for _ in 1...8 { nested = .array([nested]) }
        XCTAssertNoThrow(try table.payload(["result":nested]))
        for invalid in [JSONValue.array([nested]), .string("{}"), .array([.number(.infinity)]),
                        .object(["long":.string(String(repeating:"a",count:4096))])] {
            XCTAssertThrowsError(try table.payload(["result":invalid]))
        }
    }

    func testAdditiveTableUpdatePreservesRowsAndRejectsDestructiveChanges() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:url) }
        let owner = String(repeating:"d",count:64)
        let store = try LocalAppletLibrary(database:url,account:owner)
        let notes: [String:Any] = ["id":"notes","columns":["text":["type":"text"],"image":["type":"photo"]],"keep_days":100]
        let tags: [String:Any] = ["id":"tags","share_with_installer":true,"columns":["name":["type":"text"]]]
        let save: [String:Any] = ["id":"save","type":"record","table":"notes","row":["text":"First","image":["$step":"shot.photo"] as [String:Any]] as [String:Any]]
        func edition(_ revision:Int, _ tables:[[String:Any]], _ step:[String:Any]) throws -> RegistryPackage {
            let capture: [String:Any] = ["id":"shot","type":"photo","keep":true,"camera":"rear"]
            return try fixture(revision:revision, steps:revision == 1 ? [capture,step] : [step]) { doc in
                doc["version"] = "1.0." + String(revision - 1)
                var processing = doc["processing"] as! [String:Any]
                processing["tables"] = tables
                doc["processing"] = processing
                doc["permissions"] = ["required":["workflows","applet_data","camera.snapshot"],"automatic_grants":false] as [String:Any]
            }
        }
        let first = try edition(1,[notes],save)
        try await store.install(first)
        let firstPin = try await store.installed(first.selection.key)
        let pin = try XCTUnwrap(firstPin)
        let jpeg = try reviewedJPEG()
        let run = try await store.runButton("main",in:pin,capture:{ _ in .init(jpeg:jpeg,camera:"rear") }) { _ in XCTFail() }
        let id = try XCTUnwrap(run.results["save"]?["row_id"]?.text)
        let photo = try XCTUnwrap(run.results["shot"]?["kept"]?.text)
        var extended = notes
        extended["columns"] = ["text":["type":"text"],"image":["type":"photo"],"tag":["type":"text"]]
        let annotate: [String:Any] = ["id":"mark","type":"update","table":"notes","row":id,"set":["tag":"keep"]]
        let second = try edition(2,[tags,extended],annotate)
        let plan = try await store.reviewUpdate(second,replacing:pin)
        XCTAssertEqual(plan.addedStorage,["notes.tag","tags (shared with installer)"])
        try await store.apply(plan)
        let reopened = try LocalAppletLibrary(database:url,account:owner)
        let newPin = try await reopened.installed(first.selection.key)
        let current = try XCTUnwrap(newPin)
        _ = try await reopened.runButton("main",in:current) { _ in XCTFail() }
        let rows = try await reopened.rows(in:current,table:"notes",limit:10)
        XCTAssertEqual(rows.count,1)
        XCTAssertEqual(rows[0]["id"],.string(id))
        XCTAssertEqual(rows[0]["image"],.string(photo))
        let restoredPhoto = try await reopened.photo(photo,in:current)
        XCTAssertEqual(restoredPhoto,jpeg)
        XCTAssertEqual(rows[0]["text"],.string("First"))
        XCTAssertEqual(rows[0]["tag"],.string("keep"))
        for changed in [[tags], [tags,notes], [tags,extended.merging(["keep_days":1]) { _,v in v }],
                        [tags,extended.merging(["share_with_installer":true]) { _,v in v }],
                        [tags,extended.merging(["columns":["text":["type":"number"],"image":["type":"photo"],"tag":["type":"text"]]]) { _,v in v }]] {
            do {
                _ = try await reopened.reviewUpdate(edition(3,changed,save),replacing:current)
                XCTFail("An incompatible table change was accepted")
            } catch is AppletRuntimeError {}
        }
        let stillInstalled = try await reopened.installed(first.selection.key)
        XCTAssertEqual(stillInstalled?.revision,2)
    }

    func testReviewedUpgradeIsAtomicPreservesSettingsAndRejectsStaleInstances() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("upgrade.sqlite")
        let account = String(repeating:"a",count:64)
        let store = try LocalAppletLibrary(database:url,account:account)
        let second = try LocalAppletLibrary(database:url,account:account)
        let old = try fixture { doc in
            doc["config_schema"] = ["type":"object","properties":["plant":["type":"string"]],"required":["plant"],"additionalProperties":false] as [String:Any]
            doc["defaults"] = ["plant":"Mint"]
        }
        let new = try fixture(revision:2) { doc in
            doc["version"] = "1.1.0"
            doc["migration"] = ["from":"1.0.0","rules":[]] as [String:Any]
            doc["config_schema"] = ["type":"object","properties":["plant":["type":"string"],"days":["type":"integer"]],"required":["plant","days"],"additionalProperties":false] as [String:Any]
            doc["defaults"] = ["plant":"Basil","days":3] as [String:Any]
            doc["permissions"] = ["automatic_grants":false,"required":["interactions"],"remote":["Narration service"]] as [String:Any]
        }
        try await store.install(old)
        let installed = try await store.installed(old.selection.key)
        let original = try XCTUnwrap(installed)
        let obsolete = try await store.reviewUpdate(new,replacing:original)
        try await second.remove(original)
        try await second.install(old)
        do { try await store.apply(obsolete); XCTFail("Stale review replaced a reinstallation") } catch is AppletRuntimeError {}
        let reinstalled = try await second.installed(old.selection.key)
        let current = try XCTUnwrap(reinstalled)
        let beforeEdit = try await second.open(current)
        let staleReview = try await store.reviewUpdate(new,replacing:current)
        try await second.saveSettings(["plant":.string("Rosemary")],replacing:beforeEdit)
        do { try await store.apply(staleReview); XCTFail("Upgrade lost a newer owner setting") } catch is AppletRuntimeError {}
        let latestEdit = try await second.open(current)
        let plan = try await store.reviewUpdate(new,replacing:current)
        XCTAssertEqual(plan.newPermissions,["interactions"])
        XCTAssertEqual(plan.remoteDisclosures,["Narration service"])
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path,&db),SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db,"CREATE TRIGGER fail_update AFTER UPDATE ON local_applets BEGIN SELECT RAISE(ABORT,'simulated write failure'); END",nil,nil,nil),SQLITE_OK)
        do { try await store.apply(plan); XCTFail("Expected failed transaction") } catch is AppletRuntimeError {}
        let intact = try await second.open(current)
        XCTAssertEqual(intact.data,old.data)
        XCTAssertEqual(intact.config,["plant":.string("Rosemary")])
        XCTAssertEqual(sqlite3_exec(db,"DROP TRIGGER fail_update",nil,nil,nil),SQLITE_OK)
        try await second.apply(plan)
        do { try await store.apply(plan); XCTFail("Applied stale update twice") } catch is AppletRuntimeError {}
        do { try await second.saveSettings(["plant":.string("Sage")],replacing:latestEdit); XCTFail("An old settings form changed the new revision") } catch is AppletRuntimeError {}
        let latest = try await store.installed(old.selection.key)
        let updated = try await store.open(XCTUnwrap(latest))
        XCTAssertEqual(updated.data,new.data)
        XCTAssertEqual(updated.config,["plant":.string("Rosemary"),"days":.number(3)])
    }

    func testLegacyDatabaseRetainsPersonalSettingAndRefusesIncompatibleUpdate() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let account = String(repeating: "a", count: 64)
        let old = try fixture { doc in
            doc["config_schema"] = ["type":"object","properties":["plant":["type":"string"]],"additionalProperties":false] as [String:Any]
            doc["defaults"] = ["plant":"Mint"]
        }
        // Reproduce the pre-migration schema and a setting edited after installation.
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        let bytes = old.data.map { String(format:"%02x", $0) }.joined()
        let sql = """
            CREATE TABLE local_applets (account TEXT NOT NULL,key TEXT NOT NULL,revision INTEGER NOT NULL,
                name TEXT NOT NULL,definition BLOB NOT NULL,config BLOB NOT NULL,PRIMARY KEY(account,key));
            INSERT INTO local_applets VALUES ('\(account)','tpl_note',1,'Blue note',X'\(bytes)',CAST('{"plant":"Fern"}' AS BLOB));
            """
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        let store = try LocalAppletLibrary(database:url, account:account)
        let installed = try await store.installed("tpl_note")
        let entry = try XCTUnwrap(installed)
        let local = try await store.open(entry)
        XCTAssertEqual(local.data, old.data)
        XCTAssertEqual(local.config["plant"], .string("Fern"))
        let incompatible = try fixture(revision:2) { doc in
            doc["version"] = "1.1.0"
            doc["config_schema"] = ["type":"object","properties":["plant":["type":"integer"]],"additionalProperties":false] as [String:Any]
            doc["defaults"] = ["plant":1]
        }
        do {
            _ = try await store.reviewUpdate(incompatible, replacing:entry)
            XCTFail("An incompatible type would discard the installed setting")
        } catch is AppletRuntimeError {}
        let preserved = try await store.open(entry)
        XCTAssertEqual(preserved.config, local.config)
        XCTAssertEqual(preserved.data, old.data)
    }

    func testOfflineReopenIsOwnerScopedAndRetriesPreservePin() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("library.sqlite")
        let account = String(repeating:"a",count:64)
        let first = try LocalAppletLibrary(database:url,account:account)
        let nextSession = try LocalAppletLibrary(database:url,account:account)
        let other = try LocalAppletLibrary(database:url,account:String(repeating:"b",count:64))
        let original = try fixture()
        async let initial: Void = first.install(original)
        async let retry: Void = nextSession.install(original)
        _ = try await (initial,retry)
        let installed = try await nextSession.editions()
        XCTAssertEqual(installed.count,1)
        let entry = try XCTUnwrap(installed.first)
        let local = try await nextSession.open(entry)
        XCTAssertEqual(local.data, original.data)
        XCTAssertEqual(local.config, original.package.defaults)
        XCTAssertEqual(local.package.iconPNG, original.package.iconPNG)
        let hidden = try await other.editions()
        XCTAssertTrue(hidden.isEmpty)
        do { _ = try await other.open(entry); XCTFail("Opened another owner’s package") } catch is AppletRuntimeError {}
        for candidate in [try fixture(revision:2), try fixture(title:"Changed")] {
            do { try await first.install(candidate); XCTFail("Installation overwrote an existing pin") } catch is AppletRuntimeError {}
        }
        try await other.remove(entry)
        let preserved = try await first.open(entry)
        XCTAssertEqual(preserved.data,original.data)
        try await nextSession.remove(entry)
        let removed = try await first.editions()
        XCTAssertTrue(removed.isEmpty)
    }
}
