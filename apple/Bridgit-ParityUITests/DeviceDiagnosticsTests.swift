import XCTest
import UIKit

final class DeviceDiagnosticsTests: XCTestCase {
    func testLocalDiagnosticsAndDirectDeviceSwitching() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "home", "-device", "local"]
        app.launch()
        let center = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
        let foundCenter = center.waitForExistence(timeout: 10)
        if !foundCenter { print("DEVICE_DIAGNOSTICS_TREE", app.debugDescription); capture("missing-center", app) }
        XCTAssertTrue(foundCenter)
        XCTAssertFalse(app.staticTexts["Hardware diagnostics"].exists)
        XCTAssertFalse(app.buttons["Refresh from the phone"].exists)
        let log = app.webViews.buttons["Open the full log"]
        XCTAssertTrue(log.waitForExistence(timeout: 5))
        XCTAssertTrue(log.isHittable, "Log must appear above the bottom navigation")
        let bottom = app.tabBars.firstMatch.exists ? app.tabBars.firstMatch.frame.minY : app.frame.maxY
        XCTAssertLessThanOrEqual(log.frame.maxY, bottom + 1, "The whole log preview must fit above navigation")
        capture("local-diagnostics", app)
        center.tap()
        let local = app.buttons["diagnostics-device-local"]
        XCTAssertTrue(local.waitForExistence(timeout: 5))
        let remote = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier != %@", "diagnostics-device-", "diagnostics-device-local")).firstMatch
        XCTAssertTrue(remote.exists)
        let remoteID = remote.identifier
        remote.tap()
        XCTAssertTrue(center.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Done"].exists, "Selecting a device dismisses its chooser")
        capture("remote-diagnostics", app)
        center.tap()
        XCTAssertTrue(app.buttons[remoteID].waitForExistence(timeout: 5))
        app.buttons["diagnostics-device-local"].tap()
        XCTAssertTrue(center.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Hardware diagnostics"].exists)
        capture("returned-local-diagnostics", app)
    }
    func testLocalChooserOpensLinkedDevices() {
        checkLinkedDevicesRoute(remote: false)
    }

    func testRemoteChooserOpensLinkedDevicesWithoutChangingTarget() {
        checkLinkedDevicesRoute(remote: true)
    }

    private func checkLinkedDevicesRoute(remote: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "home", "-device", "local"]
        app.launch()
        let center = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
        XCTAssertTrue(center.waitForExistence(timeout: 10))
        center.tap()
        var selectedID = "diagnostics-device-local"
        if remote {
            let target = app.buttons["diagnostics-device-cloud:bgi_sample_garage"]
            XCTAssertTrue(target.waitForExistence(timeout: 5))
            selectedID = target.identifier
            target.tap()
            let garage = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, Garage tablet")).firstMatch
            XCTAssertTrue(garage.waitForExistence(timeout: 10))
            center.tap()
        }
        XCTAssertTrue(app.buttons[selectedID].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons[selectedID].isSelected)
        let linked = app.buttons["diagnostics-linked-devices"]
        XCTAssertTrue(linked.waitForExistence(timeout: 5))
        linked.tap()
        XCTAssertTrue(app.buttons["linked-device-details-cloud:bgi_sample_garage"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["diagnostics-linked-devices"].exists, "Chooser must dismiss")
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["Linked devices"].firstMatch.isSelected, "Wide routing must select the actual sidebar destination")
            if remote {
                let target = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Garage tablet.", "Switch device.")).firstMatch
                XCTAssertTrue(target.waitForExistence(timeout: 5), "Management navigation must preserve the selected remote target")
            }
        } else {
            let back = app.navigationBars["Linked devices"].buttons.firstMatch
            XCTAssertTrue(back.exists, "Compact routing must retain native Back")
            back.tap()
            XCTAssertTrue(center.waitForExistence(timeout: 8))
            center.tap()
            XCTAssertTrue(app.buttons[selectedID].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons[selectedID].isSelected, "Management navigation must preserve the selected target")
        }
        capture(remote ? "remote-chooser-linked-route" : "local-chooser-linked-route", app)
    }

    func testIPadLocalDeviceMotionAndSettingsRestore() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "home", "-device", "local"]; app.launch()
        let local = app.buttons["wide-sidebar-Devices"]; XCTAssertTrue(local.waitForExistence(timeout: 12)); local.tap()
        let center = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
        XCTAssertTrue(center.waitForExistence(timeout: 30)); XCTAssertTrue(local.isSelected); let identity = center.label
        print("LOCAL_IDENTITY", identity); sleep(5)
        for n in 1...3 { captureMotion("local-active-\(n)", app, tree: n == 3); if n < 3 { sleep(1) } }
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences"); settings.launch()
        let accessibility = settings.buttons["Accessibility"].firstMatch
        for _ in 0..<5 where !accessibility.exists { settings.swipeUp() }
        XCTAssertTrue(accessibility.waitForExistence(timeout: 10)); accessibility.tap()
        let motion = settings.buttons["Motion"].firstMatch; XCTAssertTrue(motion.waitForExistence(timeout: 10)); motion.tap()
        let reduce = settings.switches["Reduce Motion"].firstMatch; XCTAssertTrue(reduce.waitForExistence(timeout: 10)); let wasOn = (reduce.value as? String) == "1"
        if !wasOn { reduce.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }; XCTAssertEqual(reduce.value as? String, "1")
        app.activate(); XCTAssertTrue(center.waitForExistence(timeout: 15)); XCTAssertEqual(center.label, identity); XCTAssertTrue(local.isSelected); sleep(3)
        for n in 1...3 { captureMotion("local-reduced-\(n)", app, tree: n == 3); if n < 3 { sleep(1) } }
        settings.activate(); if !reduce.waitForExistence(timeout: 5) { if accessibility.waitForExistence(timeout: 5) { accessibility.tap() }; XCTAssertTrue(motion.waitForExistence(timeout: 8)); motion.tap() }; XCTAssertTrue(reduce.waitForExistence(timeout: 8)); if !wasOn { reduce.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }; XCTAssertEqual(reduce.value as? String, wasOn ? "1" : "0")
        app.activate(); XCTAssertTrue(center.waitForExistence(timeout: 15)); XCTAssertEqual(center.label, identity); XCTAssertTrue(local.isSelected); captureMotion("local-restored", app)
    }
    func testIPadLocalDeviceChooserBackAndSidebarReturn() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "home", "-device", "local"]; app.launch()
        let local = app.buttons["wide-sidebar-Devices"]; XCTAssertTrue(local.waitForExistence(timeout: 12)); local.tap()
        let center = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
        XCTAssertTrue(center.waitForExistence(timeout: 30)); let identity = center.label; XCTAssertTrue(local.isSelected); captureMotion("local-back-before", app)
        center.tap(); let selected = app.buttons["diagnostics-device-local"]; XCTAssertTrue(selected.waitForExistence(timeout: 8)); XCTAssertTrue(selected.isSelected); captureMotion("local-back-chooser", app)
        app.buttons["Done"].tap(); XCTAssertTrue(center.waitForExistence(timeout: 10)); XCTAssertEqual(center.label, identity); XCTAssertTrue(local.isSelected)
        let home = app.buttons["wide-sidebar-Home"]; XCTAssertTrue(home.waitForExistence(timeout: 8)); home.tap(); XCTAssertTrue(home.isSelected)
        local.tap(); XCTAssertTrue(center.waitForExistence(timeout: 15)); XCTAssertEqual(center.label, identity); XCTAssertTrue(local.isSelected); captureMotion("local-back-return", app)
    }

    func testIPadLocalDeviceSurvivesActualWidthTransition() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        try XCTSkipUnless(ProcessInfo.processInfo.environment["BRIDGIT_IPAD_WIDTH_ACCEPTANCE"] == "1", "Requires a coordinated real iPad window resize; rotation is not a compact-width transition")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "home", "-device", "local"]; app.launch()
        let local = app.buttons["wide-sidebar-Devices"]; XCTAssertTrue(local.waitForExistence(timeout: 12)); local.tap()
        let center = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
        XCTAssertTrue(center.waitForExistence(timeout: 30)); let identity = center.label; XCTAssertTrue(local.isSelected)
        XCTAssertGreaterThanOrEqual(app.windows.firstMatch.frame.width, 700); captureMotion("local-width-regular-before", app)
        for (name, compact) in [("compact", true), ("regular-after", false)] {
            print("IPAD_WIDTH_REQUEST \(name)")
            let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in let w = app.windows.firstMatch.frame.width; return compact ? w > 0 && w <= 600 : w >= 700 }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 45), .completed)
            XCTAssertTrue(center.waitForExistence(timeout: 15)); XCTAssertEqual(center.label, identity)
            if compact { XCTAssertTrue(app.tabBars.firstMatch.exists) } else { XCTAssertTrue(local.isSelected) }
            captureMotion("local-width-" + name, app)
        }
    }

    private func captureMotion(_ name: String, _ app: XCUIApplication, tree: Bool = true) {
        let screen = XCTAttachment(screenshot: app.screenshot()); screen.name = name + "-screen"; screen.lifetime = .keepAlways; add(screen)
        if tree { let hierarchy = XCTAttachment(string: app.debugDescription); hierarchy.name = name + "-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy) }
    }

    func testIPadSidebarSymbolsSelectionAndCollapse() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "applets", "-device", "local"]; app.launch()
        let sections = ["Home", "Devices", "Linked devices", "Camera", "Agents", "Activity", "More"]
        let home = app.buttons["wide-sidebar-Home"]; XCTAssertTrue(home.waitForExistence(timeout: 15))
        for section in sections {
            let button = app.buttons["wide-sidebar-" + section]; XCTAssertTrue(button.waitForExistence(timeout: 10)); button.tap(); XCTAssertTrue(button.isSelected)
            for other in sections where other != section { XCTAssertFalse(app.buttons["wide-sidebar-" + other].isSelected) }
            if section == "Devices" {
                let device = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch
                XCTAssertTrue(device.waitForExistence(timeout: 30), "Selection alone does not prove the device scene loaded")
                XCTAssertTrue(app.staticTexts["No activity yet. Agent requests show up here."].waitForExistence(timeout: 30))
            }
            capture("sidebar-symbol-" + section.replacingOccurrences(of: " ", with: "-"), app: app, markers: ["Bridgit", "Home", "This device", "Linked devices", "Camera", "Agents", "Activity", "More"])
        }
        let collapse = app.buttons["Hide Sidebar"].firstMatch; XCTAssertTrue(collapse.waitForExistence(timeout: 8)); collapse.tap()
        let expand = app.buttons["Show Sidebar"].firstMatch; XCTAssertTrue(expand.waitForExistence(timeout: 8)); XCTAssertFalse(home.isHittable)
        capture("sidebar-collapsed", app: app, markers: ["Bridgit", "More"])
        expand.tap(); XCTAssertTrue(home.waitForExistence(timeout: 8)); XCTAssertTrue(app.buttons["wide-sidebar-More"].isSelected)
        capture("sidebar-reopened", app: app, markers: ["Bridgit", "Home", "This device", "Linked devices", "Camera", "Agents", "Activity", "More"])
    }

    func testIPadSidebarAtLargestAccessibilityText() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "applets", "-device", "local",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let entries = [("Home", "Home"), ("Devices", "This device"), ("Linked devices", "Linked devices"),
                       ("Camera", "Camera"), ("Agents", "Agents"), ("Activity", "Activity"), ("More", "More")]
        let home = app.buttons["wide-sidebar-Home"]
        XCTAssertTrue(home.waitForExistence(timeout: 15))
        XCTAssertTrue(home.isSelected)
        captureMotion("sidebar-largest-top", app)

        let window = app.windows.firstMatch
        let upper = window.coordinate(withNormalizedOffset: CGVector(dx: 0.13, dy: 0.37))
        let lower = window.coordinate(withNormalizedOffset: CGVector(dx: 0.13, dy: 0.83))
        func revealBottom(_ row: XCUIElement) {
            for _ in 0..<5 {
                if row.exists, row.isHittable, row.frame.maxY <= window.frame.maxY { return }
                lower.press(forDuration: 0.05, thenDragTo: upper)
            }
            XCTFail("Could not scroll the full sidebar row into view")
        }
        for (id, label) in entries {
            let row = app.buttons["wide-sidebar-" + id]
            revealBottom(row)
            XCTAssertTrue(row.isHittable, "Scroll to reach \(label)")
            XCTAssertEqual(row.label, label)
            row.tap()
            XCTAssertTrue(row.isSelected)
            if id == "Linked devices" { captureMotion("sidebar-largest-linked", app) }
        }
        captureMotion("sidebar-largest-bottom", app)
        let collapse = app.buttons["Hide Sidebar"].firstMatch
        XCTAssertTrue(collapse.waitForExistence(timeout: 8))
        collapse.tap()
        let expand = app.buttons["Show Sidebar"].firstMatch
        XCTAssertTrue(expand.waitForExistence(timeout: 8))
        expand.tap()

        let more = app.buttons["wide-sidebar-More"]
        revealBottom(more)
        XCTAssertTrue(more.isHittable)
        XCTAssertTrue(more.isSelected)
        upper.press(forDuration: 0.05, thenDragTo: lower)
        XCTAssertTrue(home.isHittable)
        home.tap()
        XCTAssertTrue(home.isSelected)
        captureMotion("sidebar-largest-return-home", app)
        revealBottom(more)
        XCTAssertTrue(more.isHittable)
        XCTAssertFalse(more.isSelected)
    }

    private func capture(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
