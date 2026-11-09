import CryptoKit
import Network
import XCTest

final class AppletPageTests: XCTestCase {
    func testDirectHomeBundledReviewsPetRouteAndCreationChoice() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "applets", "-appletsTab", "Applets", "-device", "local"]
        app.launch()
        let create = app.buttons["Create an applet"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        let ids = ["dog-monitor", "a-sorta-fairytale-about-me", "chef-helper", "insurance-inventory", "exercise-check"]
        for id in ids { XCTAssertTrue(app.buttons["home-available-" + id].exists, "Direct Home must expose the real bundled catalog") }
        create.tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Next"].isEnabled)
        capture("home-create-agent-name", app: app, markers: ["Add control agent", "Name"])
        app.buttons["Close"].tap()
        XCTAssertTrue(name.waitForNonExistence(timeout: 5))
        let pet = app.buttons["home-available-dog-monitor"]
        XCTAssertTrue(pet.waitForExistence(timeout: 5)); pet.tap()
        let linked = app.buttons["home-pet-linked-devices"]
        for _ in 0..<6 where !linked.isHittable { app.swipeUp() }
        XCTAssertTrue(linked.isHittable); linked.tap()
        let title = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label == %@", "wide-pane-heading", "Linked devices")).firstMatch
        let compactTitle = app.navigationBars["Linked devices"]
        let reached = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in title.exists || compactTitle.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [reached], timeout: 8), .completed)
        capture("home-pet-linked-devices", app: app, markers: ["Linked devices"])
        XCTAssertFalse(app.buttons["Approve"].exists, "Review must not grant or install an applet")
    }


    func testInstalledFairytaleOpensFromHomeAndReturnsToItsEdition() {
        openFairytale(expectedDailyLimit: "$2 per day", expectedCardText: "A little magic, starring you")
    }

    func testFairytaleFromPinnedAPKOpensWithItsDefaultBudget() throws {
        let key = "BRIDGIT_3347_FAIRYTALE_PACKAGE"
        guard let encoded = ProcessInfo.processInfo.environment[key] else {
            throw XCTSkip("Requires the package extracted from the pinned APK.")
        }
        let bytes = try XCTUnwrap(Data(base64Encoded: encoded))
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "f7381ed42bd85819fac9f11baef783eade845cf70a7688974a0e08e7d5160361")
        openFairytale(expectedDailyLimit: "$1 per day", expectedCardText: "Record 360° video", environment: [key: encoded])
    }

    private func openFairytale(expectedDailyLimit: String, expectedCardText: String, environment: [String: String] = [:]) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-fixtureFairytaleRegistry", "-tab", "applets", "-appletsTab", "Applets"]
        app.launchEnvironment = environment
        app.launch()
        // The registry-only note appears once Home has finished loading the synthetic catalog.
        XCTAssertTrue(app.buttons["home-registry-tpl_native_note"].waitForExistence(timeout: 10))
        app.buttons["home-available-a-sorta-fairytale-about-me"].tap()
        let list = app.collectionViews["portable-release-review-list"]
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        func reveal(_ element: XCUIElement) {
            let viewport = list.frame.intersection(app.frame).insetBy(dx: 0, dy: 30)
            func visible() -> Bool {
                element.exists && !element.frame.isEmpty && viewport.contains(element.frame)
            }
            for _ in 0..<8 where !visible() {
                if element.exists && element.frame.midY < viewport.midY { list.swipeDown() }
                else { list.swipeUp() }
            }
            XCTAssertTrue(visible(), "Reveal " + element.debugDescription)
        }
        let retention = app.staticTexts["Keeps up to 200 rows on this device for 365 days."]
        reveal(retention)
        capture("installed-fairytale-storage", app: app, markers: ["My stories", "200 rows", "365 days"])
        let spending = app.staticTexts["portable-review-spending"]
        reveal(spending)
        XCTAssertTrue(spending.label.contains(expectedDailyLimit))
        XCTAssertTrue(spending.label.contains("$10 per month"))
        capture("installed-fairytale-spending", app: app, markers: ["Spending limits", expectedDailyLimit, "$10 per month"])
        app.buttons["Done"].tap()
        XCTAssertTrue(list.waitForNonExistence(timeout: 5))
        let installed = app.buttons["home-maintained-installed-a-sorta-fairytale-about-me"]
        XCTAssertFalse(installed.exists)
        capture("fairytale-cancel-home", app: app, markers: ["No installed applets"])
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["home-registry-tpl_native_note"].waitForExistence(timeout: 10))
        XCTAssertFalse(installed.exists, "Cancel must leave no persisted installation")
        app.buttons["home-available-a-sorta-fairytale-about-me"].tap()
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        reveal(spending)
        XCTAssertTrue(spending.label.contains(expectedDailyLimit))
        for id in ["portable-review-install", "portable-review-open-installed", "portable-installed-html-card"] {
            let action = app.buttons[id]
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id)
            action.tap()
        }
        let card = app.webViews.firstMatch
        XCTAssertTrue(card.descendants(matching: .any).matching(NSPredicate(format: "label == %@", expectedCardText)).firstMatch.waitForExistence(timeout: 30))
        capture("installed-fairytale-card", app: app, markers: ["A Sorta Fairytale About Me", expectedCardText])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(app.buttons["portable-installed-html-card"].waitForExistence(timeout: 5))
        let remove = app.buttons["portable-installed-remove"]
        for _ in 0..<8 where !remove.isHittable { app.swipeUp() }
        XCTAssertTrue(remove.isHittable)
        remove.tap()
        let confirmation = app.alerts["Remove A Sorta Fairytale About Me?"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.buttons["Cancel"].exists)
        XCTAssertTrue(confirmation.buttons["portable-confirm-remove"].exists)
        capture("fairytale-remove-confirmation", app: app, markers: ["Remove A Sorta Fairytale About Me?", "saved records and photos"])
        confirmation.buttons["Cancel"].tap()
        XCTAssertTrue(confirmation.waitForNonExistence(timeout: 5))
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        remove.tap()
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["portable-confirm-remove"].firstMatch.tap()
        XCTAssertTrue(app.buttons["portable-review-install"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["portable-review-open-installed"].exists)
        app.buttons["Done"].tap()
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["home-registry-tpl_native_note"].waitForExistence(timeout: 10))
        XCTAssertFalse(installed.exists, "Confirmed removal must survive restarting")
        capture("fairytale-removed-home", app: app, markers: ["No installed applets"])
    }

    func testMaintainedOwnerPausesAndResumesInstalledApplet() { maintainedOwnerPause(largest: false) }
    func testMaintainedOwnerPauseAndStampsLargestText() { maintainedOwnerPause(largest: true) }

    private func maintainedOwnerPause(largest: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-fixtureSavedAppletPhoto", "-tab", "applets", "-appletsTab", "Applets"]
        if largest { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        for id in ["home-registry-tpl_native_note", "portable-review-install", "portable-review-open-installed"] {
            let action = app.buttons[id]
            if id == "home-registry-tpl_native_note" { XCTAssertTrue(action.waitForExistence(timeout: 10), id) }
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id)
            action.tap()
            if id == "home-registry-tpl_native_note" {
                XCTAssertTrue(app.collectionViews["portable-release-review-list"].waitForExistence(timeout: 10))
                capture("maintained-stamp-review", app: app, markers: ["Native note"])
            }
        }
        let detailList = app.collectionViews["portable-installed-list"]
        XCTAssertTrue(detailList.waitForExistence(timeout: 10))
        let pause = detailList.buttons["portable-pause-tpl_native_note"]
        let status = detailList.staticTexts["portable-status-tpl_native_note"]
        let run = detailList.buttons["portable-run-main"]
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        XCTAssertTrue(run.waitForExistence(timeout: 10)); XCTAssertTrue(run.isEnabled)
        XCTAssertEqual(status.label, "Running")
        run.tap()
        let notice = app.staticTexts["portable-flow-notice"]
        for _ in 0..<8 where !notice.exists { detailList.swipeUp() }
        XCTAssertTrue(notice.waitForExistence(timeout: 10))
        for _ in 0..<8 where !pause.isHittable { detailList.swipeDown() }
        capture("maintained-owner-running", app: app, markers: ["Running", "Pause Native note"])
        pause.tap()
        let paused = NSPredicate(format: "label == %@", "Paused")
        expectation(for: paused, evaluatedWith: status)
        waitForExpectations(timeout: 10)
        for _ in 0..<8 where !run.exists { detailList.swipeUp() }
        XCTAssertTrue(run.exists); XCTAssertFalse(run.isEnabled)
        let camera = app.buttons["portable-camera-take"]
        for _ in 0..<8 where !camera.exists { detailList.swipeDown() }
        XCTAssertTrue(camera.exists); XCTAssertFalse(camera.isEnabled)
        XCTAssertEqual(pause.label, "Resume Native note")
        capture("maintained-owner-paused", app: app, markers: ["Paused", "Resume Native note"])
        let records = detailList.buttons["portable-records-open-notes"]
        for _ in 0..<8 where !records.isHittable { detailList.swipeUp() }
        XCTAssertTrue(records.isHittable)
        records.tap()
        let savedRow = app.staticTexts["portable-record-notes"].firstMatch
        XCTAssertTrue(savedRow.waitForExistence(timeout: 10))
        XCTAssertTrue(savedRow.label.contains("maintained-native-flow-complete"))
        capture("maintained-owner-paused-record", app: app, markers: ["maintained-native-flow-complete"])
        app.buttons["portable-record-open-notes"].tap()
        XCTAssertTrue(app.staticTexts["Message"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["maintained-native-flow-complete"].exists)
        let saved = app.staticTexts["portable-record-saved"]
        XCTAssertTrue(saved.exists); XCTAssertTrue(saved.label.contains("workflow"))
        capture("maintained-record-detail", app: app, markers: ["Message", "maintained-native-flow-complete", "Saved"])
        let detail = app.collectionViews["portable-record-detail-list"]
        let technical = app.buttons["Technical details"]
        for _ in 0..<8 where !technical.isHittable { detail.swipeUp() }
        XCTAssertTrue(technical.isHittable); technical.tap()
        for _ in 0..<8 where !app.debugDescription.contains("run_id") { detail.swipeUp() }
        XCTAssertTrue(app.debugDescription.contains("run_id"))
        capture("maintained-record-technical", app: app, markers: ["run_id"])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(savedRow.waitForExistence(timeout: 10))
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(detailList.waitForExistence(timeout: 10))
        for _ in 0..<8 where !pause.exists { detailList.swipeDown() }
        XCTAssertTrue(pause.exists)
        let photo = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier BEGINSWITH %@", "Photo, New", "portable-gallery-photo-")).firstMatch
        for _ in 0..<8 where !photo.isHittable { detailList.swipeUp() }
        XCTAssertTrue(photo.isHittable); photo.tap()
        XCTAssertTrue(app.images["portable-photo-expanded"].waitForExistence(timeout: 10))
        capture("maintained-stamp-photo", app: app, markers: ["Done", "New"])
        app.buttons["portable-photo-close"].tap()
        XCTAssertTrue(detailList.waitForExistence(timeout: 10))
        for _ in 0..<8 where !pause.exists { detailList.swipeDown() }
        XCTAssertTrue(pause.exists)
        capture("maintained-stamp-photo-return", app: app, markers: ["Paused"])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(app.buttons["portable-review-open-installed"].waitForExistence(timeout: 10))
        app.buttons["Done"].tap()
        let installed = app.buttons["home-maintained-installed-tpl_native_note"]
        XCTAssertTrue(installed.waitForExistence(timeout: 10))
        let homeStatus = app.staticTexts["portable-status-tpl_native_note"].firstMatch
        XCTAssertEqual(homeStatus.label, "Paused")
        capture("maintained-owner-home-paused", app: app, markers: ["Paused", "Resume Native note"])
        installed.tap()
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        pause.tap()
        let running = NSPredicate(format: "label == %@", "Running")
        expectation(for: running, evaluatedWith: status)
        waitForExpectations(timeout: 10)
        for _ in 0..<8 where !run.exists { detailList.swipeUp() }
        XCTAssertTrue(run.exists); XCTAssertTrue(run.isEnabled)
        capture("maintained-owner-resumed", app: app, markers: ["Running", "Pause Native note"])
    }

    func testMaintainedRecordUsesRegistryColumnOrderAfterRelaunch() {
        continueAfterFailure = false
        let response = #"""
        {"key":"tpl_native_note","revision":7,"state":"published","definition":{
          "format":"bridgit-template/1","kind":"applet","id":"native-note","version":"1.0.0","name":"Native note",
          "runtime":{"adapter":"bridgit.package.v1","applet_key":"native-note","supports_dynamic_execution":false},
          "config_schema":{"type":"object","properties":{},"additionalProperties":false},"defaults":{},
          "permissions":{"required":["workflows","applet_data","interactions"],"optional":[],"remote":[],"automatic_grants":false},
          "ui":{"card":{"pieces":[]}},
          "processing":{"tables":[{"id":"notes","label":"Notes","columns":{
             "z_title":{"type":"text","label":"First field"},"a_detail":{"type":"text","label":"Second field"}},
             "max_rows":1000,"keep_days":90}],
             "workflow":{"name":"Save a note","trigger":{"type":"button"},"steps":[
               {"id":"save","type":"record","table":"notes","row":{"z_title":"Zulu first value","a_detail":"Alpha second value"}},
               {"id":"done","type":"notice","text":"Saved locally"}]}}}}
        """#
        let app = XCUIApplication()
        app.launchEnvironment["BRIDGIT_ORDERED_REGISTRY_FIXTURE"] = Data(response.utf8).base64EncodedString()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-tab", "applets", "-appletsTab", "Applets"]
        app.launch()
        for id in ["home-registry-tpl_native_note", "portable-review-install", "portable-review-open-installed"] {
            let action = app.buttons[id]
            if id == "home-registry-tpl_native_note" { XCTAssertTrue(action.waitForExistence(timeout: 10)) }
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id); action.tap()
        }
        let run = app.buttons["portable-run-main"]
        XCTAssertTrue(run.waitForExistence(timeout: 10)); run.tap()
        XCTAssertTrue(app.staticTexts["portable-flow-notice"].waitForExistence(timeout: 10))
        app.terminate(); app.launch()
        let installed = app.buttons["home-maintained-installed-tpl_native_note"]
        XCTAssertTrue(installed.waitForExistence(timeout: 10)); installed.tap()
        let records = app.buttons["portable-records-open-notes"]
        for _ in 0..<8 where !records.isHittable { app.swipeUp() }
        XCTAssertTrue(records.isHittable); records.tap()
        let row = app.staticTexts["portable-record-notes"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10)); XCTAssertEqual(row.label, "Zulu first value")
        capture("maintained-ordered-record-list", app: app, markers: ["Zulu first value", "Open"])
        app.buttons["portable-record-open-notes"].tap()
        let first = app.staticTexts["First field"], second = app.staticTexts["Second field"]
        XCTAssertTrue(first.waitForExistence(timeout: 10)); XCTAssertTrue(second.exists)
        XCTAssertLessThan(first.frame.minY, second.frame.minY)
        XCTAssertTrue(app.staticTexts["Zulu first value"].exists); XCTAssertTrue(app.staticTexts["Alpha second value"].exists)
        capture("maintained-ordered-record-detail", app: app, markers: ["First field", "Second field"])
    }

    private func openOwnerRecordsFixture(rowCount: Int, manyRows: Bool = false, delayPage: Bool = false, largest: Bool = false) -> XCUIApplication {
        let response = #"""
        {"key":"tpl_native_note","revision":7,"state":"published","definition":{
          "format":"bridgit-template/1","kind":"applet","id":"native-note","version":"1.0.0","name":"Native note",
          "runtime":{"adapter":"bridgit.package.v1","applet_key":"native-note","supports_dynamic_execution":false},
          "config_schema":{"type":"object","properties":{},"additionalProperties":false},"defaults":{},
          "permissions":{"required":["workflows","applet_data","interactions"],"optional":[],"remote":[],"automatic_grants":false},
          "ui":{"card":{"pieces":[]}},
          "processing":{"tables":[{"id":"notes","label":"Notes","columns":{
             "z_text":{"type":"text","label":"Text"},"n_count":{"type":"number","label":"Count"},
             "b_enabled":{"type":"boolean","label":"Enabled"},"a_empty":{"type":"text","label":"Empty"},"p_photo":{"type":"photo","label":"Photo"}},
             "max_rows":1000,"keep_days":90}],
             "workflow":{"name":"Save a note","trigger":{"type":"button"},"steps":[
               {"id":"save","type":"record","table":"notes","row":{"z_text":"Line 1, \"quoted\"\nLine 2","n_count":12.5,"b_enabled":true}},
               {"id":"done","type":"notice","text":"Saved locally"}]}}}}
        """#
        let app = XCUIApplication()
        app.launchEnvironment["BRIDGIT_ORDERED_REGISTRY_FIXTURE"] = Data(response.utf8).base64EncodedString()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-tab", "applets", "-appletsTab", "Applets"]
        if manyRows { app.launchArguments.append("-fixtureOwnerCSVRows") }
        if delayPage { app.launchArguments.append("-fixtureDelayRecordsPage") }
        if largest { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        for id in ["home-registry-tpl_native_note", "portable-review-install", "portable-review-open-installed"] {
            let action = app.buttons[id]
            if id == "home-registry-tpl_native_note" { XCTAssertTrue(action.waitForExistence(timeout: 10)) }
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id); action.tap()
        }
        let run = app.buttons["portable-run-main"]
        XCTAssertTrue(run.waitForExistence(timeout: 10))
        for _ in 0..<rowCount {
            XCTAssertTrue(run.isHittable); run.tap()
            expectation(for: NSPredicate(format: "isEnabled == YES"), evaluatedWith: run)
            waitForExpectations(timeout: 10)
        }
        let records = app.buttons["portable-records-open-notes"]
        for _ in 0..<8 where !records.isHittable { app.swipeUp() }
        XCTAssertTrue(records.isHittable); records.tap()
        let firstRecord = app.staticTexts["portable-record-notes"].firstMatch
        if largest { for _ in 0..<8 where !firstRecord.exists { app.swipeUp() } }
        XCTAssertTrue(firstRecord.waitForExistence(timeout: 10))
        return app
    }

    func testOwnerRecordsDesignStandard() { ownerRecordsDesign(largest: false) }
    func testOwnerRecordsDesignLargest() { ownerRecordsDesign(largest: true) }

    private func ownerRecordsDesign(largest: Bool) {
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: largest ? 0 : 3, manyRows: largest, largest: largest)
        let firstRecord = app.staticTexts.matching(identifier: "portable-record-notes").firstMatch
        if largest { XCTAssertEqual(firstRecord.label, "row-0600") }
        let delete = app.buttons.matching(identifier: "portable-record-delete-notes").firstMatch
        let open = app.buttons.matching(identifier: "portable-record-open-notes").firstMatch
        for _ in 0..<8 where !delete.isHittable || !open.isHittable { app.swipeUp() }
        XCTAssertTrue(delete.isHittable); XCTAssertTrue(open.isHittable)
        let d = delete.frame, o = open.frame
        let rects: [String: Any] = ["delete": ["x":d.minX,"y":d.minY,"width":d.width,"height":d.height], "open": ["x":o.minX,"y":o.minY,"width":o.width,"height":o.height], "largest":largest]
        let data = try! JSONSerialization.data(withJSONObject: rects, options: [.sortedKeys])
        let bounds = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        bounds.name = "owner-record-action-frames"; bounds.lifetime = .keepAlways; add(bounds)
        capture("owner-record-design-list", app: app, markers: ["Open", "Delete"])
        XCTAssertGreaterThanOrEqual(d.width, 44); XCTAssertGreaterThanOrEqual(d.height, 44)
        XCTAssertGreaterThanOrEqual(o.width, 44); XCTAssertGreaterThanOrEqual(o.height, 44)
        XCTAssertFalse(d.intersects(o), "Delete and Open must remain separate controls")
        delete.tap()
        let dialog = app.alerts["Delete this row?"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        capture("owner-record-delete-confirmation", app: app, markers: ["Delete this row?"])
        dialog.buttons["Cancel"].tap()
        XCTAssertTrue(open.isHittable); open.tap()
        let detailDelete = app.buttons["portable-record-detail-delete"]
        for _ in 0..<12 where !detailDelete.exists { app.swipeUp() }
        XCTAssertTrue(detailDelete.waitForExistence(timeout: 10))
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(delete.waitForExistence(timeout: 10)); delete.tap()
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        dialog.buttons.matching(identifier: "portable-record-confirm-delete").firstMatch.tap()
        if largest {
            XCTAssertTrue(dialog.waitForNonExistence(timeout: 10))
            expectation(for: NSPredicate(format: "label == %@", "row-0599"), evaluatedWith: firstRecord)
        } else {
            expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: app.buttons.matching(identifier: "portable-record-delete-notes"))
        }
        waitForExpectations(timeout: 10)
        if largest { XCTAssertFalse(app.staticTexts["row-0600"].exists) }
    }

    func testOwnerRecordsDeleteCancelConfirmAndPersist() {
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: 3)
        let deletes = app.buttons.matching(identifier: "portable-record-delete-notes")
        XCTAssertEqual(deletes.count, 3)
        deletes.element(boundBy: 0).tap()
        let one = app.alerts["Delete this row?"]
        XCTAssertTrue(one.waitForExistence(timeout: 5)); one.buttons["Cancel"].tap()
        XCTAssertEqual(deletes.count, 3)
        deletes.element(boundBy: 0).tap(); XCTAssertTrue(one.waitForExistence(timeout: 5)); one.buttons.matching(identifier: "portable-record-confirm-delete").firstMatch.tap()
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: deletes); waitForExpectations(timeout: 10)
        capture("owner-record-row-deleted", app: app, markers: ["Open"])
        app.terminate(); app.launch()
        let installed = app.buttons["home-maintained-installed-tpl_native_note"]
        XCTAssertTrue(installed.waitForExistence(timeout: 10)); installed.tap()
        let records = app.buttons["portable-records-open-notes"]
        for _ in 0..<8 where !records.isHittable { app.swipeUp() }
        XCTAssertTrue(records.isHittable); records.tap()
        XCTAssertTrue(deletes.element(boundBy: 1).waitForExistence(timeout: 10)); XCTAssertEqual(deletes.count, 2)
        app.buttons.matching(identifier: "portable-record-open-notes").firstMatch.tap()
        let detailDelete = app.buttons["portable-record-detail-delete"]
        for _ in 0..<8 where !detailDelete.isHittable { app.swipeUp() }
        XCTAssertTrue(detailDelete.isHittable); detailDelete.tap()
        let detailConfirm = app.alerts["Delete this row?"]
        XCTAssertTrue(detailConfirm.waitForExistence(timeout: 5)); detailConfirm.buttons["Cancel"].tap()
        XCTAssertTrue(detailDelete.exists); detailDelete.tap()
        XCTAssertTrue(detailConfirm.waitForExistence(timeout: 5))
        detailConfirm.buttons.matching(identifier: "portable-record-detail-confirm-delete").firstMatch.tap()
        expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: deletes); waitForExpectations(timeout: 10)
        let all = app.buttons["portable-record-delete-all-notes"]
        for _ in 0..<8 where !all.isHittable { app.swipeUp() }
        XCTAssertTrue(all.isHittable); all.tap()
        let confirm = app.alerts["Delete all rows of Notes?"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.buttons["Cancel"].tap()
        XCTAssertEqual(deletes.count, 1)
        all.tap(); XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.buttons.matching(identifier: "portable-record-confirm-delete-all").firstMatch.tap()
        XCTAssertTrue(app.staticTexts["No rows yet."].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["portable-record-export-notes"].exists)
        capture("owner-record-all-deleted", app: app, markers: ["No rows yet."])
        app.terminate(); app.launch(); XCTAssertTrue(installed.waitForExistence(timeout: 10)); installed.tap()
        for _ in 0..<8 where !records.isHittable { app.swipeUp() }
        XCTAssertTrue(records.isHittable); records.tap()
        XCTAssertTrue(app.staticTexts["No rows yet."].waitForExistence(timeout: 10))
    }

    func testOwnerRecordsCannotDeleteWhileDelayedPageIsPending() {
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: 0, manyRows: true, delayPage: true)
        let more = app.buttons["portable-records-more"]
        for _ in 0..<140 where !more.isHittable { app.swipeUp(velocity: .fast) }
        XCTAssertTrue(more.isHittable); more.tap()
        let deleteAll = app.buttons["portable-record-delete-all-notes"]
        let deleteRow = app.buttons.matching(identifier: "portable-record-delete-notes").firstMatch
        for _ in 0..<6 where !deleteAll.exists { app.swipeDown() }
        XCTAssertTrue(deleteAll.exists); XCTAssertTrue(deleteRow.exists)
        XCTAssertFalse(deleteAll.isEnabled)
        XCTAssertFalse(deleteRow.isEnabled)
        XCTAssertFalse(app.buttons.matching(identifier: "portable-record-open-notes").firstMatch.isEnabled)
        capture("owner-records-pending-page", app: app, markers: ["Show more"])
        expectation(for: NSPredicate(format: "isEnabled == YES"), evaluatedWith: deleteAll)
        waitForExpectations(timeout: 20)
        for _ in 0..<140 where !deleteAll.isHittable { app.swipeDown(velocity: .fast) }
        XCTAssertTrue(deleteAll.isHittable); deleteAll.tap()
        let confirm = app.buttons.matching(identifier: "portable-record-confirm-delete-all").firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
        for _ in 0..<6 where !more.exists { app.swipeUp() }
        XCTAssertTrue(more.exists)
        XCTAssertFalse(more.isEnabled, "Paging must remain disabled while deletion is pending")
        XCTAssertTrue(app.staticTexts["No rows yet."].waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["portable-record-notes"].firstMatch.exists)
        capture("owner-records-after-pending-page-delete", app: app, markers: ["No rows yet."])
    }

    func testOwnerRecordsExportCancelOnIPad() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad simulator")
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: 0, manyRows: true)
        let export = app.buttons["portable-record-export-notes"]
        for _ in 0..<8 where !export.isHittable { app.swipeUp() }
        XCTAssertTrue(export.isHittable); export.tap()
        let save = app.buttons["Save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 15))
        capture("ipad-owner-export-before-cancel", app: app, markers: ["Save", "Cancel"])
        let cancel = app.buttons.matching(identifier: "Cancel").firstMatch
        XCTAssertTrue(cancel.isHittable); cancel.tap()
        XCTAssertTrue(save.waitForNonExistence(timeout: 10))
        XCTAssertTrue(export.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["portable-record-notes"].firstMatch.exists)
        capture("ipad-owner-export-after-cancel", app: app, markers: ["Export CSV"])
    }

    func testOwnerRecordsExportSaveOnIPad() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad simulator")
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: 0, manyRows: true)
        let export = app.buttons["portable-record-export-notes"]
        for _ in 0..<8 where !export.isHittable { app.swipeUp() }
        XCTAssertTrue(export.isHittable); export.tap()
        let save = app.buttons["Save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 15)); XCTAssertTrue(save.isHittable)
        capture("ipad-owner-export-save", app: app, markers: ["Save"])
        save.tap()
        XCTAssertTrue(export.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["portable-record-notes"].firstMatch.exists)
    }

    func testOwnerRecordsExportOpensShareAndReturnsOnCancel() {
        continueAfterFailure = false
        let app = openOwnerRecordsFixture(rowCount: 0, manyRows: true)
        let export = app.buttons["portable-record-export-notes"]
        for _ in 0..<8 where !export.isHittable { app.swipeUp() }
        XCTAssertTrue(export.isHittable); export.tap()
        let pickerSave = app.buttons["Save"].firstMatch
        XCTAssertTrue(pickerSave.waitForExistence(timeout: 15))
        capture("owner-record-export-picker", app: app, markers: ["Save"])
        let nativeCancel = app.buttons.matching(identifier: "Cancel").firstMatch
        if nativeCancel.exists && nativeCancel.isHittable {
            nativeCancel.tap()
        } else {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.09))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        XCTAssertTrue(pickerSave.waitForNonExistence(timeout: 10))
        XCTAssertTrue(export.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["portable-record-notes"].firstMatch.exists)
        capture("owner-record-share-cancel", app: app, markers: ["Export CSV"])
        export.tap()
        let save = app.buttons["Save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 15)); XCTAssertTrue(save.isHittable)
        capture("owner-record-export-save", app: app, markers: ["Save"])
        save.tap()
        XCTAssertTrue(export.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["portable-record-notes"].firstMatch.exists)
    }

    func testInstalledGalleryOpensSavedPhotoAndReturnsToItsCaption() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-fixtureSavedAppletPhoto", "-tab", "applets", "-appletsTab", "Applets"]
        app.launch()
        for id in ["home-registry-tpl_native_note", "portable-review-install", "portable-review-open-installed"] {
            let action = app.buttons[id]
            XCTAssertTrue(action.waitForExistence(timeout: 10), id)
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id)
            action.tap()
        }
        let photo = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier BEGINSWITH %@", "Photo, New", "portable-gallery-photo-")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 5))
        capture("installed-gallery-caption", app: app, markers: ["Photo, New"])
        photo.tap()
        XCTAssertTrue(app.images["portable-photo-expanded"].waitForExistence(timeout: 5))
        capture("installed-gallery-expanded", app: app, markers: ["portable-photo-expanded", "Done"])
        let done = app.buttons["portable-photo-close"]
        XCTAssertTrue(done.isHittable)
        done.tap()
        XCTAssertTrue(photo.waitForExistence(timeout: 5))
        XCTAssertFalse(app.images["portable-photo-expanded"].exists)
    }

    func testInstalledCameraActionReturnsWithoutSavingWhenSimulatorHasNoCamera() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixturePortableLibrary", "-fixturePortableRun", UUID().uuidString,
                               "-tab", "applets", "-appletsTab", "Applets"]
        app.launch()
        for id in ["home-registry-tpl_native_note", "portable-review-install",
                   "portable-review-open-installed", "portable-camera-take"] {
            let action = app.buttons[id]
            XCTAssertTrue(action.waitForExistence(timeout: 10), id)
            for _ in 0..<8 where !action.isHittable { app.swipeUp() }
            XCTAssertTrue(action.isHittable, id)
            action.tap()
        }
        XCTAssertTrue(app.staticTexts["No camera available"].waitForExistence(timeout: 8))
        capture("installed-camera-unavailable", app: app, markers: ["No camera available", "Done"])
        app.buttons["Done"].tap()
        let records = app.buttons["portable-records-open-photos"]
        XCTAssertTrue(records.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No photos yet."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Photo saved."].exists)
        records.tap()
        XCTAssertTrue(app.navigationBars["photos"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["portable-record-photos"].exists)
        capture("installed-camera-empty-photos", app: app, markers: ["photos"])
    }

    func testEmptyLocalPagesOpenAppletsAndWebSetupAndReturn() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "applets", "-appletsTab", "Portals"]
        app.launch()
        let go = app.buttons["Go to Applets"]
        let setup = app.buttons["Link this phone to the web"]
        XCTAssertTrue(go.waitForExistence(timeout: 8))
        XCTAssertTrue(setup.exists)
        XCTAssertFalse(app.buttons["Reload page"].exists)
        XCTAssertFalse(app.buttons["Open in browser"].exists)
        capture("standalone-empty-pages", app: app, markers: ["No portals yet", "Go to Applets", "Link this phone to the web"])
        go.tap()
        XCTAssertTrue(app.buttons["home-section-tab-Applets"].isSelected)
        XCTAssertTrue(app.buttons["Build instructions"].waitForExistence(timeout: 5))
        capture("standalone-go-to-applets", app: app, markers: ["Tools", "Build instructions"])
        app.buttons["home-section-tab-Portals"].tap()
        XCTAssertTrue(setup.waitForExistence(timeout: 5))
        setup.tap()
        XCTAssertTrue(app.staticTexts["Link this device to the web"].waitForExistence(timeout: 5))
        capture("standalone-web-link", app: app, markers: ["Get link code", "Set up on another device"])
        app.buttons["Done"].tap()
        XCTAssertTrue(go.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["home-section-tab-Portals"].isSelected)
        capture("standalone-empty-pages-return", app: app, markers: ["No portals yet", "Link this phone to the web"])
    }

    func testEmptyLinkedPagesOfferWebSetupForThisPhone() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "applets", "-appletsTab", "Portals"]
        app.launch()
        let link = try XCTUnwrap(URL(string: "bridgit://pair#v=1&relay=https%3A%2F%2F127.0.0.1%3A1&device=kitchen-fixture&token=rly_00000000000000000000000000000000&name=Kitchen%20phone"))
        app.open(link)
        app.buttons["Add Kitchen phone"].tap()
        let confirmation = app.alerts["Added Kitchen phone"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["OK"].tap()
        XCTAssertTrue(confirmation.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Local phone"].waitForExistence(timeout: 8))
        let offline = app.buttons["Kitchen phone. Unavailable. Switch device."]
        XCTAssertTrue(offline.waitForExistence(timeout: 12))
        let setup = app.buttons["Link this phone to the web"]
        XCTAssertTrue(setup.waitForExistence(timeout: 5))
        capture("linked-empty-pages", app: app, markers: ["No portals yet", "Link this phone to the web", "Local phone"])
        setup.tap()
        XCTAssertTrue(app.staticTexts["Link this device to the web"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Web linking is offline in this test workspace."].waitForExistence(timeout: 5))
        capture("linked-web-setup", app: app, markers: ["Link this device to the web", "Get link code", "Set up on another device"])
        app.buttons["Done"].tap()
        XCTAssertTrue(setup.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Local phone"].exists)
        capture("linked-web-setup-return", app: app, markers: ["Go to Applets", "Link this phone to the web", "Local phone"])
        app.buttons["Go to Applets"].tap()
        XCTAssertTrue(app.buttons["Create an applet"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Local phone"].exists)
    }

    func testOpenSelectedAppletInSafari() throws {
        try verifyBrowserHandoff(openDetail: false)
    }

    func testReloadAndOpenCurrentAppletPageInSafari() throws {
        try verifyBrowserHandoff(openDetail: true)
    }

    private func localPageServer() throws -> NWListener {
        let resource = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "applet-page-fixture", withExtension: "html"))
        let html = try String(contentsOf: resource, encoding: .utf8)
        let ready = expectation(description: "Loopback page server ready")
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let server = try NWListener(using: parameters)
        let queue = DispatchQueue(label: "applet-page-test")
        server.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        server.newConnectionHandler = { connection in
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { _, _, _, _ in
                let reply = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: \(html.utf8.count)\r\nConnection: close\r\n\r\n" + html
                connection.send(content: Data(reply.utf8), completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        server.start(queue: queue)
        wait(for: [ready], timeout: 5)
        return server
    }

    private func verifyBrowserHandoff(openDetail: Bool) throws {
        continueAfterFailure = false
        let port: UInt16
        let server: NWListener?
        if let configured = ProcessInfo.processInfo.environment["BRIDGIT_BROWSER_TEST_PORT"] {
            port = try XCTUnwrap(UInt16(configured))
            XCTAssertGreaterThan(port, 0)
            server = nil
        } else {
            let local = try localPageServer()
            server = local
            port = try XCTUnwrap(local.port).rawValue
        }
        defer { server?.cancel() }
        let app = XCUIApplication()
        app.launchEnvironment["BRIDGIT_FIXTURE_APPLET_PAGE_PORT"] = String(port)
        app.launchArguments = ["-fixtureAppraiser", "-fixturePortalPicker", "-device", "relay:sample-device-0001", "-tab", "applets"]
        app.launch()
        let chooser = app.buttons["Page: Pet monitor"]
        XCTAssertTrue(chooser.waitForExistence(timeout: 10))
        chooser.tap()
        app.buttons["Alpha Test"].tap()
        let page = app.webViews.firstMatch
        let base = "/myapps/?device=bgi_sample_kitchen"
        XCTAssertTrue(page.staticTexts[base + "#pkg.alpha-test"].waitForExistence(timeout: 10))
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        let path: String
        if openDetail {
            page.links["Open details"].tap()
            path = base + "&detail=1#pkg.alpha-test"
            XCTAssertTrue(page.staticTexts[path].waitForExistence(timeout: 10))
            app.buttons["Reload page"].tap()
            XCTAssertTrue(page.staticTexts["reload"].waitForExistence(timeout: 10))
            XCTAssertTrue(page.staticTexts[path].exists)
            capture("selected-applet-reloaded", app: app, markers: [path, "reload", "Open in browser"])
        } else {
            path = base + "#pkg.alpha-test"
        }
        app.buttons["Open in browser"].tap()
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(safari.webViews.staticTexts[path].waitForExistence(timeout: 10))
        capture(openDetail ? "selected-applet-in-browser" : "selected-applet-in-browser-root", app: safari, markers: [path, "Alpha Test"])
        app.activate()
        XCTAssertTrue(page.staticTexts[path].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Page: Alpha Test"].exists)
        capture(openDetail ? "selected-applet-return" : "selected-applet-root-return", app: app, markers: [path, "Page: Alpha Test"])
    }
}
