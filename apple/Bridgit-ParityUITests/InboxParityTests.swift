import XCTest
import UIKit

/// Attach both observations to the same running application and XCTest destination.
extension XCTestCase {
    func capture(_ name: String, app: XCUIApplication, markers: [String]) {
        let tree = app.debugDescription
        for marker in markers { XCTAssertTrue(tree.contains(marker), "Missing screen marker: " + marker) }
        let image = XCTAttachment(screenshot: app.screenshot())
        let hierarchy = XCTAttachment(string: tree)
        for (attachment, suffix) in [(image, "screen"), (hierarchy, "hierarchy")] {
            attachment.name = name + "-" + suffix
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

}

final class InboxParityTests: XCTestCase {
    func testIPadLinkedActivityFailureFilterAndReturn() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-tab", "applets"]
        app.launch()
        let linked = app.buttons["Linked devices"].firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 10)); linked.tap()
        let detail = app.buttons["linked-device-details-cloud:bgi_sample_garage"]
        XCTAssertTrue(detail.waitForExistence(timeout: 10)); detail.tap()
        XCTAssertTrue(app.navigationBars["Garage tablet"].waitForExistence(timeout: 10))
        app.buttons["wide-sidebar-Activity"].tap()
        let refused = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "HTTP 403")).firstMatch
        let success = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "HTTP 200"))
        XCTAssertTrue(refused.waitForExistence(timeout: 10))
        XCTAssertTrue(success.firstMatch.exists)
        capture("ipad-linked-activity-before-filter", app: app, markers: ["HTTP 403", "HTTP 200"])
        let filter = app.switches["Only refused or failed"]
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        XCTAssertEqual(filter.value as? String, "0")
        print("DIAGNOSTIC switch: \(filter.debugDescription)")
        filter.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        capture("ipad-linked-activity-knob-tap", app: app, markers: ["Only refused or failed"])
        let toggled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "1"), object: filter)
        XCTAssertEqual(XCTWaiter.wait(for: [toggled], timeout: 5), .completed, "Filter switch must turn on")
        XCTAssertTrue(refused.exists)
        capture("ipad-linked-activity-after-toggle-diagnostic", app: app, markers: ["HTTP 403", "Only refused or failed"])
        XCTAssertTrue(success.firstMatch.waitForNonExistence(timeout: 5))
        capture("ipad-linked-activity-failures-only", app: app, markers: ["HTTP 403", "Only refused or failed"])
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "linked-activity-entry-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5)); row.tap()
        XCTAssertTrue(app.navigationBars["Entry details"].waitForExistence(timeout: 5))
        let copy = app.buttons["linked-activity-copy"]
        XCTAssertTrue(copy.isHittable); copy.tap()
        XCTAssertEqual(copy.label, "Copied")
        capture("ipad-linked-activity-entry-details", app: app, markers: ["Entry details", "Copied", "Done"])
        let fields = app.descendants(matching: .any)["linked-activity-detail-fields"].firstMatch
        let lastField = app.descendants(matching: .any)["linked-activity-field-data"].firstMatch
        XCTAssertTrue(fields.exists)
        func finalFieldIsVisible() -> Bool {
            guard lastField.exists, lastField.isHittable else { return false }
            let frame = lastField.frame
            let top = app.navigationBars["Entry details"].frame.maxY
            return frame.height > 0 && frame.minY >= top && frame.maxY <= copy.frame.minY
        }
        for _ in 0..<12 {
            if finalFieldIsVisible() { break }
            fields.swipeUp()
        }
        XCTAssertTrue(finalFieldIsVisible(), "Final standard field must be fully visible between the title and Copy")
        XCTAssertTrue(copy.isHittable, "Copy must stay available while reading long details")
        XCTAssertTrue(app.buttons["linked-activity-done"].isHittable)
        capture("ipad-linked-activity-detail-scrolled", app: app, markers: ["Entry details", "Copied", "Done"])
        app.buttons["linked-activity-done"].tap()
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        XCTAssertEqual(filter.value as? String, "1")
        XCTAssertFalse(success.firstMatch.exists)
        filter.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertTrue(success.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(refused.exists)
        capture("ipad-linked-activity-filter-cleared", app: app, markers: ["HTTP 403", "HTTP 200"])
    }

    func testIPadActivityHeadingForLocalAndLinkedDevice() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-tab", "applets"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 10)); activity.tap()
        let heading = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label == %@", "wide-pane-heading", "Activity"))
        XCTAssertEqual(heading.count, 1)
        XCTAssertFalse(app.navigationBars["Activity"].exists)
        capture("ipad-activity-local-heading", app: app, markers: ["Activity"])
        let linked = app.buttons["Linked devices"].firstMatch
        linked.tap()
        let detail = app.buttons["linked-device-details-cloud:bgi_sample_garage"]
        XCTAssertTrue(detail.waitForExistence(timeout: 10)); detail.tap()
        XCTAssertTrue(app.navigationBars["Garage tablet"].waitForExistence(timeout: 10))
        activity.tap()
        XCTAssertEqual(heading.count, 1)
        XCTAssertFalse(app.navigationBars["Activity"].exists)
        capture("ipad-activity-linked-heading", app: app, markers: ["Activity"])
    }

    func testIPadLinkedDetailAndSeparateAppletScope() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "applets"]
        app.launch()
        let linked = app.buttons["Linked devices"].firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 10)); linked.tap()
        let detail = app.buttons["linked-device-details-cloud:bgi_sample_garage"]
        let applets = app.buttons["linked-device-applets-cloud:bgi_sample_garage"]
        XCTAssertTrue(detail.waitForExistence(timeout: 10))
        XCTAssertTrue(applets.exists)
        let heading = app.staticTexts.matching(NSPredicate(format: "identifier == %@ AND label == %@", "wide-pane-heading", "Linked devices"))
        XCTAssertEqual(heading.count, 1)
        XCTAssertFalse(app.navigationBars["Linked devices"].exists)
        capture("ipad-linked-list", app: app, markers: ["Garage tablet", "View applets"])
        detail.tap()
        let nav = app.navigationBars["Garage tablet"]
        XCTAssertTrue(nav.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["linked-device-detail-applets-cloud:bgi_sample_garage"].exists)
        capture("ipad-linked-detail-first", app: app, markers: ["Garage tablet", "View applets"])
        XCTAssertTrue(app.staticTexts["Device Diagnostics Complete"].waitForExistence(timeout: 15))
        capture("ipad-linked-detail-ready", app: app, markers: ["Garage tablet", "Device Diagnostics Complete"])
        sleep(2)
        capture("ipad-linked-detail-later", app: app, markers: ["Garage tablet", "Device Diagnostics Complete"])
        nav.buttons.firstMatch.tap()
        XCTAssertTrue(applets.waitForExistence(timeout: 8)); applets.tap()
        let title = app.navigationBars["Garage tablet · Applets"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Pet monitor"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Create an applet"].exists)
        XCTAssertFalse(app.buttons["Install on this device"].exists)
        capture("ipad-linked-applets", app: app, markers: ["Garage tablet", "Pet monitor"])
        title.buttons.firstMatch.tap()
        XCTAssertTrue(detail.waitForExistence(timeout: 8))
        XCTAssertEqual(heading.count, 1)
        XCTAssertFalse(app.navigationBars["Linked devices"].exists)
        capture("ipad-linked-applets-return", app: app, markers: ["Garage tablet", "View applets"])
    }

    // Screen cases and transition cases are distinct. Portrait/landscape and hiding
    // a sidebar do not demonstrate a compact iPad window.
    private enum IPadWindowMode: String {
        case regular, compact
        func contains(_ width: CGFloat) -> Bool {
            self == .regular ? width >= 700 : width > 0 && width <= 600
        }
    }

    private func launchIPadWidthFixture(device: String) throws -> XCUIApplication {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad simulator.")
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "applets", "-device", device]
        app.launch()
        XCTAssertTrue(app.buttons["home-section-tab-Applets"].waitForExistence(timeout: 10))
        return app
    }

    private func requireIPadWindow(_ mode: IPadWindowMode, app: XCUIApplication) throws {
        let width = app.windows.firstMatch.frame.width
        try XCTSkipUnless(mode.contains(width), "Requires an actual \(mode.rawValue) iPad window; observed \(width) pt. Rotation or a hidden sidebar is not a compact-window receipt.")
    }

    private func assertIPadShell(_ mode: IPadWindowMode, app: XCUIApplication) {
        XCTAssertTrue(mode.contains(app.windows.firstMatch.frame.width))
        if mode == .compact {
            XCTAssertTrue(app.tabBars.firstMatch.exists, "Actual compact layout must expose native tabs")
            XCTAssertTrue(app.tabBars.buttons["Home"].isSelected)
        } else {
            XCTAssertFalse(app.tabBars.firstMatch.exists, "Regular layout must return to the sidebar")
            XCTAssertTrue(app.buttons["Home"].firstMatch.isSelected)
        }
        XCTAssertTrue(app.buttons["home-section-tab-Applets"].exists)
    }

    private func assertIPadDeviceScope(linked: Bool, app: XCUIApplication) {
        let garage = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "Garage tablet.", "Switch device.")).firstMatch
        let anyLinkedView = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Switch device.")).firstMatch
        if linked {
            XCTAssertTrue(garage.waitForExistence(timeout: 8), "The exact selected Garage installation must survive")
            XCTAssertTrue(app.buttons["Local tablet"].exists)
        } else {
            XCTAssertFalse(anyLinkedView.exists, "Local or missing selection must never fall back to another linked device")
            XCTAssertFalse(app.buttons["Local tablet"].exists)
        }
    }

    private func captureIPadWindow(_ name: String, app: XCUIApplication) {
        capture(name, app: app, markers: ["Home"])
        let geometry = XCTAttachment(string: "Actual application window: \(app.windows.firstMatch.frame)")
        geometry.name = name + "-window-geometry"; geometry.lifetime = .keepAlways; add(geometry)
        let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screen.name = name + "-full-screen"; screen.lifetime = .keepAlways; add(screen)
    }

    private func requireActualWidthDriver() throws {
        // A coordinator may resize the actual Simulator app window while XCTest
        // waits. No launch argument or app-side size-class override is accepted.
        try XCTSkipUnless(ProcessInfo.processInfo.environment["BRIDGIT_IPAD_WIDTH_ACCEPTANCE"] == "1",
                          "Requires a coordinated real regular→compact→regular window resize. No transition is verified by this skip.")
    }

    private func awaitIPadWindow(_ mode: IPadWindowMode, app: XCUIApplication) {
        print("IPAD_WIDTH_REQUEST \(mode.rawValue)")
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            mode.contains(app.windows.firstMatch.frame.width)
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 45), .completed,
                       "The real iPad window must become \(mode.rawValue); hiding the sidebar and rotation alone cannot satisfy this transition")
    }

    private func checkIPadScreen(_ mode: IPadWindowMode, device: String, linked: Bool, name: String) throws {
        let app = try launchIPadWidthFixture(device: device)
        try requireIPadWindow(mode, app: app)
        assertIPadShell(mode, app: app)
        assertIPadDeviceScope(linked: linked, app: app)
        captureIPadWindow(name, app: app)
    }

    func testIPadRegularLinkedSelectionScreen() throws {
        try checkIPadScreen(.regular, device: "cloud:bgi_sample_garage", linked: true, name: "ipad-regular-linked-selection")
    }

    func testIPadCompactLinkedSelectionScreen() throws {
        try checkIPadScreen(.compact, device: "cloud:bgi_sample_garage", linked: true, name: "ipad-compact-linked-selection")
    }

    func testIPadRegularExplicitLocalScreen() throws {
        try checkIPadScreen(.regular, device: "local", linked: false, name: "ipad-regular-explicit-local")
    }

    func testIPadCompactExplicitLocalScreen() throws {
        try checkIPadScreen(.compact, device: "local", linked: false, name: "ipad-compact-explicit-local")
    }

    func testIPadRegularMissingLinkedSelectionFallsBackLocally() throws {
        try checkIPadScreen(.regular, device: "cloud:missing-fixture-install", linked: false, name: "ipad-regular-missing-selection")
    }

    func testIPadCompactMissingLinkedSelectionFallsBackLocally() throws {
        try checkIPadScreen(.compact, device: "cloud:missing-fixture-install", linked: false, name: "ipad-compact-missing-selection")
    }

    private func checkIPadWidthTransition(device: String, linked: Bool, name: String) throws {
        try requireActualWidthDriver()
        let app = try launchIPadWidthFixture(device: device)
        try requireIPadWindow(.regular, app: app)
        assertIPadShell(.regular, app: app)
        assertIPadDeviceScope(linked: linked, app: app)
        captureIPadWindow(name + "-regular-before", app: app)
        awaitIPadWindow(.compact, app: app)
        assertIPadShell(.compact, app: app)
        assertIPadDeviceScope(linked: linked, app: app)
        captureIPadWindow(name + "-compact", app: app)
        awaitIPadWindow(.regular, app: app)
        assertIPadShell(.regular, app: app)
        assertIPadDeviceScope(linked: linked, app: app)
        captureIPadWindow(name + "-regular-after", app: app)
    }

    func testIPadLinkedSelectionSurvivesRegularCompactRegular() throws {
        try checkIPadWidthTransition(device: "cloud:bgi_sample_garage", linked: true, name: "ipad-linked-width-transition")
    }

    func testIPadExplicitLocalSurvivesRegularCompactRegularWithoutLinkedFallback() throws {
        try checkIPadWidthTransition(device: "local", linked: false, name: "ipad-local-width-transition")
    }

    func testIPadUnsavedEditSurvivesRegularCompactRegular() throws {
        try requireActualWidthDriver()
        let app = try launchIPadWidthFixture(device: "cloud:bgi_sample_garage")
        try requireIPadWindow(.regular, app: app)
        assertIPadDeviceScope(linked: true, app: app)
        app.buttons["More"].firstMatch.tap()
        let feature = app.buttons["Request a feature"].firstMatch
        XCTAssertTrue(feature.waitForExistence(timeout: 8)); feature.tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 8))
        let draft = "Isolated iPad width-transition draft — keep this exact unsaved text."
        editor.tap(); editor.typeText(draft)
        XCTAssertEqual(editor.value as? String, draft)
        capture("ipad-unsaved-regular-before", app: app, markers: [draft, "Request a feature"])
        awaitIPadWindow(.compact, app: app)
        XCTAssertTrue(editor.exists, "Changing width must not silently dismiss the edit")
        XCTAssertEqual(editor.value as? String, draft)
        capture("ipad-unsaved-compact", app: app, markers: [draft, "Request a feature"])
        awaitIPadWindow(.regular, app: app)
        XCTAssertTrue(editor.exists)
        XCTAssertEqual(editor.value as? String, draft)
        capture("ipad-unsaved-regular-after", app: app, markers: [draft, "Request a feature"])
        app.buttons["Cancel"].firstMatch.tap()
        let keep = app.alerts["Keep this draft?"].buttons["Keep"]
        XCTAssertTrue(keep.waitForExistence(timeout: 5)); keep.tap()
        XCTAssertTrue(feature.waitForExistence(timeout: 5)); feature.tap()
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, draft, "Native reopen must retain the unsaved draft")
        // Only this isolated fixture draft is discarded; Submit is never tapped.
        app.buttons["Cancel"].firstMatch.tap()
        app.alerts["Keep this draft?"].buttons["Discard"].tap()
    }


    func testCompactLinkedDeviceDetailBackUsesNativeStack() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Requires an iPhone simulator.")
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "more"]
        app.launch()
        let linked = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Linked devices")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 10), "Compact More must expose linked-device management, including its metrics label"); linked.tap()
        let garage = app.buttons["linked-device-details-cloud:bgi_sample_garage"]
        XCTAssertTrue(garage.waitForExistence(timeout: 8)); garage.tap()
        let detail = app.navigationBars["Garage tablet"]
        XCTAssertTrue(detail.waitForExistence(timeout: 8))
        capture("compact-linked-garage-detail", app: app, markers: ["Garage tablet"])
        detail.buttons.firstMatch.tap()
        XCTAssertTrue(garage.waitForExistence(timeout: 8), "Back must restore the compact linked-device list")
        app.navigationBars["Linked devices"].buttons.firstMatch.tap()
        XCTAssertTrue(linked.waitForExistence(timeout: 8), "Back must restore More")
        capture("compact-linked-back-to-more", app: app, markers: ["Linked devices", "More"])
    }

    func testIPadSidebarHomeFromPushedLinkedDetail() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad simulator.")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-tab", "applets"]
        app.launch()
        let linked = app.buttons["Linked devices"].firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 10)); linked.tap()
        let garage = app.buttons["linked-device-details-cloud:bgi_sample_garage"]
        XCTAssertTrue(garage.waitForExistence(timeout: 8)); garage.tap()
        XCTAssertTrue(app.navigationBars["Garage tablet"].waitForExistence(timeout: 8))
        let home = app.buttons["Home"].firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 8)); XCTAssertTrue(home.isHittable)
        capture("ipad-sidebar-home-before", app: app, markers: ["Garage tablet", "Home"])
        home.tap()
        capture("ipad-sidebar-home-after", app: app, markers: ["Home"])
        XCTAssertTrue(home.isSelected, "The sidebar Home row must be selected")
        XCTAssertTrue(app.buttons["home-section-tab-Applets"].waitForExistence(timeout: 8), "Home tabs must replace the pushed detail")
        XCTAssertFalse(app.navigationBars["Garage tablet"].exists, "Pushed Garage detail must be dismissed")

        // Ordinary Back still returns to the list, and explicit local selection stays local.
        linked.tap()
        XCTAssertTrue(garage.waitForExistence(timeout: 8)); garage.tap()
        let detail = app.navigationBars["Garage tablet"]
        XCTAssertTrue(detail.waitForExistence(timeout: 8))
        detail.buttons.firstMatch.tap()
        XCTAssertTrue(garage.waitForExistence(timeout: 8), "Back must return to Linked devices")
        let local = app.buttons["This device"].firstMatch
        XCTAssertTrue(local.waitForExistence(timeout: 8)); local.tap()
        XCTAssertTrue(local.isSelected, "Explicit local selection must be retained")
        home.tap()
        XCTAssertTrue(app.buttons["home-section-tab-Applets"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["Local tablet"].exists, "Local Home must not retain a linked-device selector")
        capture("ipad-sidebar-local-home", app: app, markers: ["Home", "Applets"])
    }


    func testCompactHomeBrandAndTabsStaySeparateWhenScrolled() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Requires iPhone")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-appletsTab", "Applets", "-device", "local"]; app.launch()
        let cards = app.buttons["home-section-tab-Cards"]; XCTAssertTrue(cards.waitForExistence(timeout: 12))
        let brand = app.images["Bridgit"].firstMatch
        let brandAny = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Bridgit")).firstMatch
        XCTAssertTrue(brandAny.waitForExistence(timeout: 8)); capture("compact-home-before-scroll", app: app, markers: ["Bridgit", "Cards"])
        app.swipeUp(); XCTAssertTrue(cards.isHittable); XCTAssertTrue(brandAny.exists)
        XCTAssertLessThanOrEqual(brandAny.frame.maxY, cards.frame.minY + 1, "Brand header must not overlap the section tabs")
        _ = brand.exists
        capture("compact-home-after-scroll", app: app, markers: ["Bridgit", "Cards"])
    }

    func testStandaloneHomeTabLabelsOrderAndSelectionMatchAPK3347() {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Applets"]
        app.launch()
        let tabs = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "home-section-tab-"))
        XCTAssertTrue(tabs.firstMatch.waitForExistence(timeout: 8))
        let expected = ["Portals", "Applets", "Cards"]
        let visible = tabs.allElementsBoundByIndex.sorted { $0.frame.minX < $1.frame.minX }
        XCTAssertEqual(visible.map(\.label), expected)
        XCTAssertEqual(visible.filter(\.isSelected).map(\.label), ["Applets"])
        for tab in visible {
            tab.tap()
            XCTAssertEqual(visible.filter(\.isSelected).map(\.label), [tab.label])
            capture("home-tab-" + tab.label, app: app, markers: expected)
        }
    }

    private func waitForPaintedPreview(_ app: XCUIApplication) -> Bool {
        let preview = app.webViews.firstMatch
        guard preview.waitForExistence(timeout: 5) else { return false }
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            if let image = preview.screenshot().image.cgImage,
               let crop = image.cropping(to: CGRect(x: image.width / 10, y: image.height * 2 / 5, width: 1, height: 1)),
               let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                context.draw(crop, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                if let bytes = context.data?.assumingMemoryBound(to: UInt8.self), bytes[0] < 140 && bytes[1] < 140 && bytes[2] < 170 { return true }
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return false
    }

    func testBuildInstructionsRoutesToActiveRecipient() {
        continueAfterFailure = false
        for multiple in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["-fixtureStandalone", multiple ? "-fixtureTwoCreators" : "-fixtureOneCreator", "-appletsTab", "Applets"]
            app.launch()
            let instructions = app.buttons["Build instructions"]
            XCTAssertTrue(instructions.waitForExistence(timeout: 8))
            instructions.tap()
            let send = app.buttons["Send to agent"]
            XCTAssertTrue(send.waitForExistence(timeout: 8))
            let copyFrame = app.buttons["Copy prompt"].frame
            send.tap()
            if multiple {
                let recipient = app.buttons["Fixture Beta"]
                XCTAssertTrue(recipient.waitForExistence(timeout: 5))
                capture("choose-agent", app: app, markers: ["Fixture Alpha", "Fixture Beta", "Send to agent"])
                if app.buttons["Cancel"].exists {
                    app.buttons["Cancel"].tap()
                } else {
                    // Compact system popovers dismiss when the user taps outside them.
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)).tap()
                }
                XCTAssertTrue(recipient.waitForNonExistence(timeout: 3))
                XCTAssertFalse(app.staticTexts["Queued for Fixture Beta"].exists)
                send.tap()
                recipient.tap()
            }
            let confirmation = multiple ? "Queued for Fixture Beta" : "Queued for Fixture Alpha"
            XCTAssertTrue(app.staticTexts[confirmation].waitForExistence(timeout: 5))
            capture(multiple ? "queued-selected-agent" : "queued-only-agent", app: app, markers: [confirmation, "Copy prompt"])
            XCTAssertEqual(app.buttons["Copy prompt"].frame, copyFrame)
            XCTAssertTrue(app.staticTexts[confirmation].waitForNonExistence(timeout: 6))
            XCTAssertTrue(send.isEnabled)
            capture(multiple ? "selected-agent-return" : "only-agent-return", app: app, markers: ["Send to agent", "Copy prompt"])
            app.terminate()
        }
    }

    func testBuildInstructionsCopyAndReturnToCatalog() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Applets"]
        app.launch()
        let instructions = app.buttons["Build instructions"]
        XCTAssertTrue(instructions.waitForExistence(timeout: 8))
        instructions.tap()
        let copy = app.buttons["Copy prompt"]
        XCTAssertTrue(copy.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Share"].exists)
        XCTAssertFalse(app.buttons["Send to agent"].exists)
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
        capture("instructions", app: app, markers: ["Help me build a useful Bridgit applet.", "Share", "Copy prompt"])
        copy.tap()
        let confirmation = app.staticTexts["Prompt copied"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 2))
        capture("copy-confirmation", app: app, markers: ["Prompt copied", "Copy prompt"])
        XCTAssertTrue(confirmation.waitForNonExistence(timeout: 5))
        app.scrollViews.firstMatch.swipeUp()
        app.scrollViews.firstMatch.swipeUp()
        capture("instructions-end", app: app, markers: ["Sent from Bridgit", "WHEN YOU REPORT BACK"])
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(instructions.waitForExistence(timeout: 5))
        capture("instructions-return", app: app, markers: ["Tools", "Available", "Build instructions"])
    }

    func testFairytaleReviewPermissionsAndDecline() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Applets"]
        app.launch()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Review A Sorta Fairytale About Me.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.tap()
        XCTAssertTrue(app.staticTexts["Install applet"].waitForExistence(timeout: 5))
        let scroll = app.scrollViews.firstMatch
        let spending = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Bridgit’s default:")).firstMatch
        XCTAssertTrue(spending.waitForExistence(timeout: 8))
        for _ in 0..<4 where !spending.isHittable { scroll.swipeUp() }
        XCTAssertTrue(spending.label.contains("$1 per day"))
        XCTAssertTrue(spending.label.contains("$10 per month"))
        XCTAssertTrue(spending.label.contains("Linked devices on the same account share this applet’s budget."))
        capture("fairytale-spending", app: app, markers: ["Spending limit", "$1 per day", "$10 per month"])
        for _ in 0..<4 { scroll.swipeUp() }
        XCTAssertTrue(app.staticTexts["What it can use"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Approve"].isEnabled)
        capture("fairytale-review-end", app: app, markers: ["What it can use", "Decline", "Approve"])
        app.buttons["Decline"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        capture("fairytale-review-declined", app: app, markers: ["A Sorta Fairytale About Me", "Available"])
    }

    func testReturnFromLinkedAppletsToLocalCatalog() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001", "-tab", "applets", "-appletsTab", "Applets"]
        app.launch()
        let backToLocal = app.buttons["Local phone"]
        XCTAssertTrue(backToLocal.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Available"].exists)
        XCTAssertGreaterThanOrEqual(backToLocal.frame.height, 44)
        let catalog = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Review Insurance inventory.")).firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 8))
        let packages = ["A Sorta Fairytale About Me", "Chef’s Helper", "Insurance inventory", "Exercise check"]
        capture("linked-applets-catalog", app: app, markers: packages + ["Kitchen phone", "Local phone", "Installed on this iPhone"])
        if !catalog.isHittable { app.swipeUp() }
        catalog.tap()
        XCTAssertTrue(app.staticTexts["Install applet"].waitForExistence(timeout: 5))
        capture("linked-catalog-review", app: app, markers: ["Install applet", "Insurance inventory", "Decline"])
        let records = app.staticTexts["Saves a row in Items for each item (at most 50)."]
        let scroll = app.scrollViews.containing(.staticText, identifier: "Workflow · Read one photo").firstMatch
        func reveal(_ element: XCUIElement) {
            let viewport = scroll.frame.insetBy(dx: 0, dy: 1)
            let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.68))
            let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.42))
            for _ in 0..<18 where !viewport.contains(element.frame) {
                start.press(forDuration: 0.08, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.05)
            }
            XCTAssertTrue(viewport.contains(element.frame), "Text must fit inside the visible scroll viewport: " + element.label)
        }
        let prerequisite = app.staticTexts["This applet requires a camera. This device has none available."]
        reveal(prerequisite)
        capture("linked-catalog-prerequisite", app: app, markers: ["This applet requires a camera", "Before you can install"])
        let trigger = app.staticTexts["It runs when you tap Create inventory, once for each matching entry in Photos (at most 200 per run)."]
        reveal(trigger)
        capture("linked-batch-limit", app: app, markers: ["200 per run", "Create inventory"])
        reveal(records)
        capture("linked-catalog-workflow", app: app, markers: ["Saves a row in Items", "Approve", "Decline"])
        let retention = app.staticTexts.matching(identifier: "Keeps up to 1000 rows on this device for 3650 days.")
        XCTAssertEqual(retention.count, 2)
        for index in 0..<2 {
            reveal(retention.element(boundBy: index))
            capture("linked-retention-" + String(index), app: app, markers: ["Keeps up to 1000 rows", "3650 days"])
        }
        let budget = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "Bridgit’s default:")).firstMatch
        reveal(budget)
        capture("linked-budget", app: app, markers: ["$1 per day", "$10 per month"])
        reveal(app.staticTexts["Read one photo · Up to 300 attempts on this device per UTC day."])
        capture("linked-daily-limit", app: app, markers: ["Up to 300 attempts", "Decline"])
        XCTAssertGreaterThanOrEqual(app.buttons["Decline"].frame.height, 44)
        XCTAssertGreaterThanOrEqual(app.buttons["Approve"].frame.height, 44)
        app.buttons["Decline"].tap()
        XCTAssertTrue(backToLocal.isHittable)
        backToLocal.tap()
        XCTAssertTrue(catalog.waitForExistence(timeout: 8))
        XCTAssertFalse(backToLocal.exists)
        XCTAssertGreaterThanOrEqual(app.buttons["Create an applet"].frame.height, 44)
        capture("linked-applets-local-return", app: app, markers: ["A Sorta Fairytale About Me", "Chef’s Helper", "Insurance inventory", "Exercise check"])
        catalog.tap()
        XCTAssertTrue(app.staticTexts["Install applet"].waitForExistence(timeout: 5))
        app.buttons["Decline"].tap()
        XCTAssertTrue(catalog.waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Switch device")).firstMatch.tap()
        app.buttons["Kitchen phone"].firstMatch.tap()
        XCTAssertTrue(backToLocal.waitForExistence(timeout: 5))
    }

    func testBundledAppletListOpensEachReviewAndReturns() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Applets"]
        app.launch()
        let packages = ["A Sorta Fairytale About Me", "Chef’s Helper", "Insurance inventory", "Exercise check"]
        for (index, name) in packages.enumerated() {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Review " + name + ".")).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 8))
            if !row.isHittable { app.swipeUp() }
            if index == 0 { capture("available-applets", app: app, markers: packages) }
            row.tap()
            XCTAssertTrue(app.staticTexts["Install applet"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts[name].exists)
            capture("catalog-review-" + name, app: app, markers: ["Install applet", name, "Decline"])
            app.buttons["Decline"].tap()
            XCTAssertTrue(row.waitForExistence(timeout: 5))
            capture("catalog-return-" + name, app: app, markers: ["Available", name])
        }
    }

    func testPendingHTMLCardCanBeFoundThroughTheInboxFilters() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let card = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Bridgit connection check")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        for filter in ["Questions", "Open"] {
            let button = app.buttons[filter]
            button.tap()
            XCTAssertTrue(button.isSelected)
            XCTAssertTrue(card.exists)
            capture("pending-" + filter.lowercased(), app: app, markers: ["NEEDS YOUR ATTENTION · 1", "Design fixture", "Waiting for your response"])
        }
        card.tap()
        XCTAssertTrue(app.staticTexts["From Design fixture · custom card"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.webViews.buttons["Test received"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Dismiss"].exists)
        XCTAssertTrue(app.buttons["Play aloud"].isHittable)
        capture("pending-detail", app: app, markers: ["From Design fixture · custom card", "Test received", "Skip", "Waiting"])
        app.buttons["Done"].tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Open"].isSelected)
        capture("pending-detail-return", app: app, markers: ["Bridgit connection check", "Waiting for your response", "Open"])
        app.buttons["Notices"].tap()
        XCTAssertFalse(card.exists)
        XCTAssertTrue(app.staticTexts["No notices."].exists)
        capture("pending-notices", app: app, markers: ["No matching cards", "Clear filter"])
        app.buttons["Clear filter"].tap()
        XCTAssertTrue(app.buttons["All"].isSelected)
        XCTAssertTrue(card.exists)
        capture("pending-cleared", app: app, markers: ["NEEDS YOUR ATTENTION · 1", "Waiting for your response"])
        app.buttons["Open"].tap()
        card.tap()
        app.buttons["Dismiss"].tap()
        XCTAssertTrue(app.staticTexts["Design fixture was told."].waitForExistence(timeout: 3))
        capture("dismiss-confirmation", app: app, markers: ["Closed", "Design fixture was told."])
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["Done"])
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 8), .completed)
        XCTAssertTrue(app.buttons["Open"].isSelected)
        XCTAssertTrue(app.staticTexts["Nothing open."].exists)
        capture("open-after-dismiss", app: app, markers: ["No matching cards", "Nothing open.", "Clear filter"])
        app.buttons["All"].tap()
        XCTAssertTrue(app.staticTexts["COMPLETED"].exists)
        capture("pending-dismiss-return", app: app, markers: ["COMPLETED", "Closed", "Design fixture"])
        card.tap()
        let closedNote = app.staticTexts["You closed this. Design fixture was told."]
        XCTAssertTrue(closedNote.waitForExistence(timeout: 5))
        let reply = app.webViews.buttons["Test received"]
        XCTAssertTrue(reply.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Dismiss"].exists)
        capture("closed-history", app: app, markers: ["Bridgit connection check", "Test received", "Closed", "You closed this."])
        reply.tap()
        XCTAssertTrue(closedNote.exists)
        XCTAssertFalse(app.buttons["Send answer"].exists)
        XCTAssertTrue(app.webViews.staticTexts["Sending your reply…"].waitForExistence(timeout: 3))
        capture("closed-history-tapped", app: app, markers: ["Sending your reply", "You closed this.", "Closed"])
        let retry = app.webViews.staticTexts["Not confirmed yet. Tap again, or close the card."]
        XCTAssertTrue(retry.waitForExistence(timeout: 12))
        capture("closed-history-reply", app: app, markers: ["Not confirmed yet.", "You closed this.", "Closed"])
        app.buttons["Done"].tap()
        XCTAssertTrue(app.staticTexts["COMPLETED"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["All"].isSelected)
        capture("closed-history-return", app: app, markers: ["COMPLETED", "Closed", "Design fixture"])
    }

    func testStepSkipGeometryAndConnectionAcknowledgement() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]; app.launch()
        let pending = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Bridgit connection check")).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 10)); app.buttons["Library"].tap()
        let targets: [(String, CGFloat, CGFloat)] = [("center", 0.5, 0.5), ("lower", 0.5, 0.971), ("left", 0.052, 0.5)]
        for (name,x,y) in targets {
            let card=app.staticTexts["Step card"]; XCTAssertTrue(card.waitForExistence(timeout: 8)); card.tap()
            let skip=app.webViews.buttons["Skip"]; XCTAssertTrue(skip.waitForExistence(timeout: 40)); XCTAssertTrue(app.webViews.staticTexts["Flash the light"].waitForExistence(timeout: 10))
            capture("card-step-"+name+"-before", app: app, markers: ["Step card", "Skip"])
            skip.coordinate(withNormalizedOffset: CGVector(dx:x,dy:y)).tap()
            XCTAssertTrue(app.webViews.staticTexts["Watch the light"].waitForExistence(timeout: 8)); XCTAssertFalse(skip.isEnabled)
            skip.coordinate(withNormalizedOffset: CGVector(dx:x,dy:y)).tap(); XCTAssertTrue(app.webViews.staticTexts["Watch the light"].exists); XCTAssertFalse(skip.isEnabled)
            capture("card-step-"+name+"-after", app: app, markers: ["Watch the light", "Skip"])
            let back=app.navigationBars.buttons["BackButton"].firstMatch; XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap(); XCTAssertTrue(card.waitForExistence(timeout: 8))
            capture("card-step-"+name+"-return", app: app, markers: ["Step card", "Library"])
        }
        for choice in ["Test received", "Skip"] {
            let connection=app.staticTexts["Connection test"]; XCTAssertTrue(connection.waitForExistence(timeout: 5)); connection.tap()
            let action=app.webViews.buttons[choice]; XCTAssertTrue(action.waitForExistence(timeout: 40)); XCTAssertTrue(app.webViews.staticTexts["We’re connected"].waitForExistence(timeout: 10))
            capture("card-connection-"+choice+"-before", app: app, markers: ["Connection test", choice]); action.tap()
            XCTAssertTrue(app.webViews.staticTexts["Reply sent"].waitForExistence(timeout: 8)); XCTAssertFalse(app.webViews.buttons["Test received"].isEnabled); XCTAssertFalse(app.webViews.buttons["Skip"].isEnabled)
            capture("card-connection-"+choice+"-ack", app: app, markers: ["Reply sent", "Connection test"])
            action.coordinate(withNormalizedOffset: CGVector(dx:0.5,dy:0.5)).tap(); XCTAssertTrue(app.webViews.staticTexts["Reply sent"].exists); XCTAssertFalse(app.webViews.buttons["Test received"].isEnabled); XCTAssertFalse(app.webViews.buttons["Skip"].isEnabled)
            let back=app.navigationBars.buttons["BackButton"].firstMatch; XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap(); XCTAssertTrue(connection.waitForExistence(timeout: 8)); capture("card-connection-"+choice+"-return", app: app, markers: ["Connection test", "Library"])
        }
        app.buttons["Inbox"].tap()
        XCTAssertTrue(pending.waitForExistence(timeout: 8)); capture("card-pending-preserved", app: app, markers: ["Bridgit connection check", "Inbox"])
    }

    func testStepAndWrapPreviewChoicesReturnToLibrary() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Cards"]
        app.launch()
        let browse = app.buttons["Browse library"]
        XCTAssertTrue(browse.waitForExistence(timeout: 10))
        browse.tap()
        let cases: [(String, [String], String)] = [
            ("Step card", ["Do it", "Skip", "End demo"], "Watch the light"),
            ("Wrap-up", ["Show me more", "Set one idea up", "That's enough"], "Thanks!")
        ]
        for (card, choices, confirmation) in cases {
            for (index, choice) in choices.enumerated() {
                let row = app.staticTexts[card].firstMatch
                XCTAssertTrue(row.waitForExistence(timeout: 5))
                if !row.isHittable { app.swipeUp() }
                row.tap()
                let action = app.webViews.buttons[choice]
                XCTAssertTrue(action.waitForExistence(timeout: 8))
                XCTAssertTrue(waitForPaintedPreview(app), "Preview background did not render")
                capture("preview-\(card)-\(index)-initial", app: app, markers: [card, choice])
                action.tap()
                XCTAssertTrue(app.webViews.staticTexts[confirmation].waitForExistence(timeout: 5))
                let expectedCopy = card == "Wrap-up" ? "Your answer stays on this device." : "Look at your phone."
                XCTAssertTrue(app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expectedCopy)).firstMatch.waitForExistence(timeout: 5))
                for label in choices { XCTAssertFalse(app.webViews.buttons[label].isEnabled) }
                capture("preview-\(card)-\(index)-answered", app: app, markers: [card, confirmation])
                action.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                XCTAssertTrue(app.webViews.staticTexts[confirmation].exists)
                for label in choices { XCTAssertFalse(app.webViews.buttons[label].isEnabled) }
                app.navigationBars.buttons["BackButton"].firstMatch.tap()
                XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
                capture("preview-\(card)-\(index)-return", app: app, markers: ["Demo agenda", card])
            }
        }
    }

    func testPhotoPreviewAcknowledgesLocally() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Cards"]
        app.launch()
        XCTAssertTrue(app.buttons["Browse library"].waitForExistence(timeout: 10))
        app.buttons["Browse library"].tap()
        for choice in ["Not quite", "Spot on"] {
            let row = app.staticTexts["Photo with a guess"]
            XCTAssertTrue(row.waitForExistence(timeout: 5))
            if !row.isHittable { app.swipeUp() }
            row.tap()
            let action = app.webViews.buttons[choice]
            XCTAssertTrue(action.waitForExistence(timeout: 8))
            action.tap()
            let local = app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Your answer stays on this device.")).firstMatch
            XCTAssertTrue(local.waitForExistence(timeout: 5))
            XCTAssertFalse(app.webViews.buttons["Not quite"].isEnabled)
            XCTAssertFalse(app.webViews.buttons["Spot on"].isEnabled)
            capture("photo-local-\(choice)", app: app, markers: ["Your answer stays on this device."])
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
        }
    }

    func testFilesBackToFilteredPendingInbox() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let pending = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Bridgit connection check")).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 10))
        let home = app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Home")).firstMatch
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
        app.buttons["Notices"].tap()
        XCTAssertTrue(app.buttons["Notices"].isSelected)
        XCTAssertTrue(app.staticTexts["No notices."].exists)
        capture("files-inbox-before", app: app, markers: ["No notices.", "Clear filter", "1 interaction waiting"])
        app.buttons["More options"].tap()
        XCTAssertTrue(app.buttons["Files"].waitForExistence(timeout: 5))
        capture("files-inbox-menu", app: app, markers: ["Files", "Shared"])
        app.buttons["Files"].tap()
        XCTAssertTrue(app.navigationBars["Files and device access"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No files yet."].waitForExistence(timeout: 5))
        capture("files-inbox-destination", app: app, markers: ["Files and device access", "No files yet.", "Choose a file"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["Notices"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Inbox"].isSelected)
        XCTAssertTrue(app.buttons["Notices"].isSelected)
        XCTAssertTrue(app.staticTexts["No notices."].exists)
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
        capture("files-inbox-return", app: app, markers: ["No notices.", "Clear filter", "1 interaction waiting"])
        app.buttons["Clear filter"].tap()
        XCTAssertTrue(app.buttons["All"].isSelected)
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        capture("files-inbox-cleared", app: app, markers: ["Bridgit connection check", "Waiting for your response"])
    }

    func testSharedBackToPendingLibrary() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let pending = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Bridgit connection check")).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 10))
        app.buttons["Notices"].tap()
        XCTAssertTrue(app.buttons["Notices"].isSelected)
        app.buttons["Library"].tap()
        XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Library"].isSelected)
        let home = app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Home")).firstMatch
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
        capture("shared-library-before", app: app, markers: ["Demo agenda", "Library", "1 interaction waiting"])
        app.buttons["More options"].tap()
        XCTAssertTrue(app.buttons["Shared"].waitForExistence(timeout: 5))
        capture("shared-library-menu", app: app, markers: ["Files", "Shared"])
        app.buttons["Shared"].tap()
        XCTAssertTrue(app.navigationBars["Files and device access"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No files yet."].waitForExistence(timeout: 5))
        capture("shared-library-destination", app: app, markers: ["Files and device access", "No files yet.", "Choose a file"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Library"].isSelected)
        XCTAssertFalse(app.buttons["Notices"].exists)
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
        capture("shared-library-return", app: app, markers: ["Demo agenda", "Library", "1 interaction waiting"])
        app.buttons["Inbox"].tap()
        XCTAssertTrue(app.buttons["Notices"].isSelected)
        XCTAssertTrue(app.staticTexts["No notices."].exists)
        app.buttons["Clear filter"].tap()
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        capture("shared-inbox-return", app: app, markers: ["Bridgit connection check", "Waiting for your response"])
    }

    func testPendingLocalBadgeAndLibraryNavigation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let pending = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Bridgit connection check")).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 10))
        let home = app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Home")).firstMatch
        XCTAssertTrue(home.isSelected)
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
        capture("pending-library-inbox", app: app, markers: ["Bridgit connection check", "Library", "Home"])
        app.buttons["Library"].tap()
        let expected = ["Demo agenda", "Connection test", "Step card", "Photo with a guess", "Rock paper scissors round", "Wrap-up", "Checklist and slider mini app"]
        XCTAssertTrue(app.staticTexts[expected[0]].waitForExistence(timeout: 5))
        let actual = expected.map { app.staticTexts[$0] }
        XCTAssertTrue(actual.allSatisfy(\.exists))
        XCTAssertEqual(actual.sorted { $0.frame.minY < $1.frame.minY }.map(\.label), expected)
        XCTAssertFalse(app.buttons["Questions"].exists)
        XCTAssertFalse(app.buttons["Notices"].exists)
        XCTAssertFalse(app.buttons["Open"].exists)
        XCTAssertTrue(app.buttons["Library"].isSelected)
        XCTAssertTrue(home.isSelected)
        let inbox = app.buttons["Inbox"].frame, library = app.buttons["Library"].frame
        XCTAssertLessThan(abs((inbox.minX + library.maxX) / 2 - app.frame.midX), 5)
        capture("pending-library", app: app, markers: expected + ["Inbox", "Library", "Home"])
        app.staticTexts["Connection test"].tap()
        XCTAssertTrue(app.webViews.buttons["Test received"].waitForExistence(timeout: 8))
        capture("pending-library-preview", app: app, markers: ["Connection test", "Test received"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Library"].isSelected)
        XCTAssertTrue(home.isSelected)
        capture("pending-library-return", app: app, markers: expected + ["Library", "Home"])
        app.buttons["Inbox"].tap()
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        XCTAssertTrue(home.isSelected)
        XCTAssertEqual(home.label, "Home, 1 interaction waiting for you")
    }

    func testLargestTextLocalHomeTabsSwipeAndSelect() { checkLargestTextTabs(linked: false) }
    func testLargestTextLinkedHomeTabsSwipeAndSelect() { checkLargestTextTabs(linked: true) }
    private func checkLargestTextTabs(linked: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = (linked ? ["-fixtureAppraiser", "-device", "relay:sample-device-0001"] : ["-fixtureStandalone", "-device", "local"]) + ["-tab", "applets", "-appletsTab", "Applets", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let applets = app.buttons["home-section-tab-Applets"]; let cards = app.buttons["home-section-tab-Cards"]; let portals = app.buttons["home-section-tab-Portals"]
        XCTAssertTrue(applets.waitForExistence(timeout: 15)); XCTAssertTrue(applets.isSelected)
        func swipe(_ left: Bool) {
            let y = applets.frame.midY - app.frame.minY
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let from = origin.withOffset(CGVector(dx: app.frame.width * (left ? 0.85 : 0.15), dy: y))
            let to = origin.withOffset(CGVector(dx: app.frame.width * (left ? 0.15 : 0.85), dy: y))
            from.press(forDuration: 0.1, thenDragTo: to)
        }
        func fullyVisible(_ tab: XCUIElement) {
            XCTAssertTrue(tab.waitForExistence(timeout: 8)); XCTAssertTrue(tab.isHittable)
            XCTAssertGreaterThanOrEqual(tab.frame.minX, app.frame.minX - 1); XCTAssertLessThanOrEqual(tab.frame.maxX, app.frame.maxX + 1)
            XCTAssertGreaterThanOrEqual(tab.frame.width, 44); XCTAssertGreaterThanOrEqual(tab.frame.height, 44)
        }
        swipe(true); fullyVisible(cards); cards.tap(); XCTAssertTrue(cards.isSelected); capture((linked ? "linked" : "local") + "-largest-cards", app: app, markers: ["Cards"])
        swipe(false); fullyVisible(portals); portals.tap(); XCTAssertTrue(portals.isSelected); capture((linked ? "linked" : "local") + "-largest-portals", app: app, markers: ["Portals"])
        swipe(true); fullyVisible(applets); applets.tap(); XCTAssertTrue(applets.isSelected); capture((linked ? "linked" : "local") + "-largest-applets", app: app, markers: ["Applets"])
    }

    func testIPadLinkedDeviceMenuOffersLocalTablet() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001", "-tab", "applets", "-appletsTab", "Applets"]; app.launch()
        let device = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Kitchen phone.", "Switch device.")).firstMatch
        XCTAssertTrue(device.waitForExistence(timeout: 20)); device.tap()
        let local = app.buttons["This device"].firstMatch; XCTAssertTrue(local.waitForExistence(timeout: 10)); capture("ipad-linked-device-menu", app: app, markers: ["This device", "Kitchen phone"])
        local.tap(); XCTAssertTrue(app.buttons["home-section-tab-Applets"].waitForExistence(timeout: 10)); XCTAssertFalse(device.exists); capture("ipad-linked-device-menu-local", app: app, markers: ["Applets"])
    }

    func testLinkedDeviceHeaderStandardTextAndLocalAction() { checkLinkedDeviceHeader(large: false) }
    func testLinkedDeviceHeaderLargestTextAndLocalAction() { checkLinkedDeviceHeader(large: true) }
    private func checkLinkedDeviceHeader(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001", "-tab", "applets", "-appletsTab", "Applets"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }; app.launch()
        let device = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Kitchen phone.", "Switch device.")).firstMatch
        let local = app.buttons["Local phone"]; XCTAssertTrue(device.waitForExistence(timeout: 20)); XCTAssertTrue(local.waitForExistence(timeout: 10)); XCTAssertGreaterThanOrEqual(device.frame.height, 44); XCTAssertGreaterThanOrEqual(local.frame.height, 44)
        print("DEVICE_HEADER_FRAME", large, device.frame, local.frame)
        capture(large ? "device-header-largest" : "device-header-standard", app: app, markers: ["Kitchen phone", "Local phone"])
        device.tap(); XCTAssertTrue(app.buttons["This device"].waitForExistence(timeout: 8)); capture(large ? "device-header-largest-menu" : "device-header-standard-menu", app: app, markers: ["This device", "Kitchen phone"])
        app.buttons["This device"].tap(); XCTAssertTrue(app.buttons["home-section-tab-Applets"].waitForExistence(timeout: 10)); XCTAssertFalse(device.exists)
        capture(large ? "device-header-largest-local" : "device-header-standard-local", app: app, markers: ["Bridgit", "Applets"])
    }

    func testLinkedHomeStampAndCardPreviewReturn() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Requires iPhone")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001", "-tab", "applets", "-appletsTab", "Applets"]; app.launch()
        let device = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Kitchen phone.", "Switch device.")).firstMatch
        XCTAssertTrue(device.waitForExistence(timeout: 20)); let cards = app.buttons["home-section-tab-Cards"]; XCTAssertTrue(cards.waitForExistence(timeout: 10)); capture("linked-stamp-home", app: app, markers: ["Kitchen phone", "Cards"])
        app.swipeUp(); XCTAssertTrue(device.exists); XCTAssertTrue(cards.isHittable); capture("linked-stamp-scrolled", app: app, markers: ["Kitchen phone", "Cards"])
        cards.tap(); let browse = app.buttons["Browse library"]; XCTAssertTrue(browse.waitForExistence(timeout: 10)); browse.tap()
        let card = app.staticTexts["Connection test"]; XCTAssertTrue(card.waitForExistence(timeout: 10)); capture("linked-stamp-library", app: app, markers: ["Kitchen phone", "Connection test"]); card.tap()
        XCTAssertTrue(app.webViews.buttons["Test received"].waitForExistence(timeout: 30)); capture("linked-stamp-preview", app: app, markers: ["Connection test", "Test received"])
        let back = app.navigationBars.buttons["BackButton"].firstMatch; XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap(); XCTAssertTrue(card.waitForExistence(timeout: 10)); XCTAssertTrue(device.waitForExistence(timeout: 10)); capture("linked-stamp-return", app: app, markers: ["Kitchen phone", "Connection test"])
    }

    func testLocalHomeStampAndCardPreviewReturn() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Requires iPhone")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-appletsTab", "Cards", "-device", "local"]; app.launch()
        let browse = app.buttons["Browse library"]; XCTAssertTrue(browse.waitForExistence(timeout: 12)); browse.tap(); let card = app.staticTexts["Connection test"]; XCTAssertTrue(card.waitForExistence(timeout: 10)); capture("local-stamp-library", app: app, markers: ["Connection test", "Library"]); card.tap()
        XCTAssertTrue(app.webViews.buttons["Test received"].waitForExistence(timeout: 30)); capture("local-stamp-preview", app: app, markers: ["Connection test", "Test received"])
        let back = app.navigationBars.buttons["BackButton"].firstMatch; XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap(); XCTAssertTrue(card.waitForExistence(timeout: 10)); capture("local-stamp-return", app: app, markers: ["Bridgit", "Connection test"])
    }

    func testOriginalPackageCameraLabelAndDeclaredStyleAtLargestText() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "Requires iPhone")
        continueAfterFailure = false
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let original = try Data(contentsOf: root.appendingPathComponent("applets/insurance-inventory/applet.json"))
        var package = try XCTUnwrap(JSONSerialization.jsonObject(with: original) as? [String: Any])
        var ui = try XCTUnwrap(package["ui"] as? [String: Any]); var card = try XCTUnwrap(ui["card"] as? [String: Any]); var pieces = try XCTUnwrap(card["pieces"] as? [[String: Any]])
        let index = try XCTUnwrap(pieces.firstIndex { ($0["type"] as? String) == "buttons" }); var items = try XCTUnwrap(pieces[index]["items"] as? [[String: Any]])
        items[0]["label"] = "Photograph my things"; items[0]["style"] = "plain"; pieces[index]["items"] = items; card["pieces"] = pieces; ui["card"] = card; package["ui"] = ui
        let data = try JSONSerialization.data(withJSONObject: package, options: [.sortedKeys]); let app = XCUIApplication()
        app.launchEnvironment["BRIDGIT_ORIGINAL_CARD_FIXTURE"] = data.base64EncodedString()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureOriginalCard", "-tab", "applets", "-appletsTab", "Applets", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]; app.launch()
        let installed = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Insurance inventory", "Local")).firstMatch
        XCTAssertTrue(installed.waitForExistence(timeout: 15)); for _ in 0..<8 where !installed.isHittable { app.swipeUp() }; XCTAssertTrue(installed.isHittable); installed.tap()
        let custom = app.buttons["Photograph my things"].firstMatch
        XCTAssertTrue(custom.waitForExistence(timeout: 12))
        let cardScroll = app.scrollViews.containing(.button, identifier: "Photograph my things").firstMatch
        XCTAssertTrue(cardScroll.waitForExistence(timeout: 5))
        for _ in 0..<8 {
            let bounds = cardScroll.frame.insetBy(dx: 0, dy: 10)
            if custom.isHittable && custom.frame.minY >= bounds.minY && custom.frame.maxY <= bounds.maxY { break }
            let movingDown = custom.frame.minY < bounds.minY
            let start = cardScroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: movingDown ? 0.35 : 0.65))
            let end = cardScroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: movingDown ? 0.55 : 0.45))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        XCTAssertTrue(custom.isHittable)
        XCTAssertGreaterThanOrEqual(custom.frame.minY, cardScroll.frame.minY + 10)
        XCTAssertLessThanOrEqual(custom.frame.maxY, cardScroll.frame.maxY - 10)
        XCTAssertFalse(app.buttons["Take photos"].exists); XCTAssertTrue(app.buttons["Photo library"].exists); XCTAssertTrue(app.buttons["Import images"].exists)
        capture("original-card-largest-custom-camera", app: app, markers: ["Photograph my things", "Photo library", "Import images"])
    }

    func testLinkedLibraryAnswersStayInPreview() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001", "-tab", "applets", "-appletsTab", "Cards"]
        app.launch()
        let linkedInbox = app.buttons["Browse library"]
        XCTAssertTrue(linkedInbox.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(linkedInbox.frame.height, 44)
        linkedInbox.tap()
        let card = app.staticTexts["Connection test"]
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Library"].isSelected)
        XCTAssertFalse(app.buttons["Notices"].exists)
        capture("linked-library", app: app, markers: ["Connection test", "Demo agenda", "Library"])
        card.tap()
        let received = app.webViews.buttons["Test received"]
        XCTAssertTrue(received.waitForExistence(timeout: 8))
        XCTAssertTrue(waitForPaintedPreview(app))
        XCTAssertFalse(app.buttons["Local phone"].exists)
        capture("linked-library-preview", app: app, markers: ["Test received", "Skip"])
        received.tap()
        XCTAssertTrue(app.webViews.staticTexts["Reply sent"].waitForExistence(timeout: 5))
        capture("linked-library-answer", app: app, markers: ["Connection test", "Reply sent"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Library"].isSelected)
        card.tap()
        XCTAssertTrue(received.waitForExistence(timeout: 8))
        XCTAssertTrue(received.isEnabled)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["Library"].isSelected)
        capture("linked-preview-back", app: app, markers: ["Connection test", "Library", "Local phone"])
        for action in ["Files", "Shared"] {
            app.buttons["More options"].tap()
            if action == "Files" { capture("linked-library-menu", app: app, markers: ["Files", "Shared"]) }
            app.buttons[action].tap()
            XCTAssertTrue(app.navigationBars["Files and device access"].waitForExistence(timeout: 5))
            capture("linked-library-" + action.lowercased(), app: app, markers: ["Files and device access", "Choose a file"])
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.buttons["Library"].isSelected)
            XCTAssertTrue(app.buttons["Local phone"].exists)
        }
        app.buttons["Inbox"].tap()
        XCTAssertTrue(linkedInbox.waitForExistence(timeout: 5))
        capture("linked-library-inbox-return", app: app, markers: ["Browse library", "Notices", "Library", "Local phone"])
        app.buttons["Notices"].tap()
        app.buttons["Library"].tap()
        app.buttons["Local phone"].tap()
        XCTAssertTrue(app.buttons["Library"].isSelected)
        app.buttons["Inbox"].tap()
        XCTAssertTrue(app.buttons["Notices"].isSelected)
    }

    func testEmptyLocalInboxHasNoHomeBadgeAccessibility() {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Cards"]
        app.launch()
        XCTAssertTrue(app.buttons["Browse library"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.tabBars.buttons["Home"].label, "Home")
    }

    func testIPadHomeBadgeAccessibility() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad simulator.")
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let home = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Home")).firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 8))
        let labeled = NSPredicate(format: "label == %@", "Home, 1 interaction waiting for you")
        expectation(for: labeled, evaluatedWith: home)
        waitForExpectations(timeout: 10)
        capture("ipad-pending-home", app: app, markers: ["Home, 1 interaction waiting for you"])
    }

    func testLinkedDeviceRetainsLocalHomeBadgeAccessibility() {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureInbox", "-appletsTab", "Cards"]
        app.launch()
        let switcher = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Switch device")).firstMatch
        XCTAssertTrue(switcher.waitForExistence(timeout: 8))
        switcher.tap()
        let kitchen = app.buttons["Kitchen phone"].firstMatch
        XCTAssertTrue(kitchen.waitForExistence(timeout: 5))
        kitchen.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Kitchen phone")).firstMatch.waitForExistence(timeout: 5))
        let badge = app.tabBars.buttons["Home, 1 interaction waiting for you"]
        XCTAssertTrue(badge.waitForExistence(timeout: 8))
        capture("linked-home-local-badge", app: app, markers: ["Kitchen phone", "1 interaction waiting for you"])
        XCTAssertTrue(app.buttons["Local phone"].exists)
        XCTAssertTrue(app.staticTexts["Bridgit connection check"].waitForExistence(timeout: 8))
        app.staticTexts["Bridgit connection check"].tap()
        XCTAssertTrue(app.webViews.buttons["Test received"].waitForExistence(timeout: 8))
        capture("linked-pending-detail", app: app, markers: ["Bridgit connection check", "Test received"])
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["Local phone"].waitForExistence(timeout: 5))
        app.buttons["Local phone"].tap()
        XCTAssertTrue(app.staticTexts["Bridgit connection check"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Local phone"].exists)
        capture("local-pending-after-linked", app: app, markers: ["Bridgit connection check", "1 interaction waiting for you"])
        XCTAssertTrue(badge.exists)
    }

    func testEmptyInboxPreservesEachFilterThroughLibrary() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-appletsTab", "Cards"]
        app.launch()
        let browse = app.buttons["Browse library"]
        XCTAssertTrue(browse.waitForExistence(timeout: 10))
        for filter in ["All", "Questions", "Notices", "Open"] {
            let button = app.buttons[filter]
            button.tap()
            XCTAssertTrue(button.isSelected)
            XCTAssertTrue(app.staticTexts["No cards yet"].exists)
            browse.tap()
            XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
            XCTAssertFalse(button.exists)
            if filter == "Open" {
                capture("library", app: app, markers: ["Connection test", "Demo agenda", "Wrap-up"])
                app.buttons["Applets"].tap()
                app.buttons["Cards"].tap()
                XCTAssertTrue(app.staticTexts["Demo agenda"].waitForExistence(timeout: 5))
            }
            app.buttons["Inbox"].tap()
            XCTAssertTrue(browse.waitForExistence(timeout: 5))
            XCTAssertTrue(button.isSelected)
            capture("inbox-" + filter.lowercased(), app: app, markers: ["No cards yet", "Browse library", filter])
        }
    }
}
