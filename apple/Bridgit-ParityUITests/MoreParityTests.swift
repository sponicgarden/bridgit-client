import XCTest
import UIKit

final class MoreParityTests: XCTestCase {
    /// Sample the center of the rendered content; a selected picker label can change while its sheet stays dark.
    private func rendersDark(_ app: XCUIApplication) throws -> Bool {
        let image = try XCTUnwrap(app.screenshot().image.cgImage)
        let center = CGRect(x: image.width / 4, y: image.height / 4, width: image.width / 2, height: image.height / 2)
        let crop = try XCTUnwrap(image.cropping(to: center))
        let context = try XCTUnwrap(CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8,
                                               bytesPerRow: 1, space: CGColorSpaceCreateDeviceGray(),
                                               bitmapInfo: CGImageAlphaInfo.none.rawValue))
        context.interpolationQuality = .high
        context.draw(crop, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return try XCTUnwrap(context.data).load(as: UInt8.self) < 128
    }

    /// Above a native sheet only its dimmed background should paint, never scrolled report text.
    private func assertCoveredTitleDoesNotPaintAboveImageSheet(_ app: XCUIApplication, fullScreen: Bool = false) throws {
        let viewer = app.otherElements["feedback.screenshotViewer"]
        let window = app.windows.firstMatch.frame
        let top = viewer.frame.minY
        let band: CGRect
        if fullScreen {
            XCTAssertLessThanOrEqual(abs(top - window.minY), 1, "Accessibility viewer must cover the entire window")
            XCTAssertGreaterThanOrEqual(viewer.frame.maxY, window.maxY - 1)
            band = CGRect(x: window.minX + window.width * 0.3, y: window.minY + 24, width: window.width * 0.4, height: 10)
        } else {
            XCTAssertGreaterThan(top, window.minY + 16)
            band = CGRect(x: window.minX + 8, y: top - 12, width: window.width - 16, height: 10)
        }
        let image = try XCTUnwrap(app.screenshot().image.cgImage)
        let sx = CGFloat(image.width) / window.width, sy = CGFloat(image.height) / window.height
        let crop = try XCTUnwrap(image.cropping(to: CGRect(x: (band.minX - window.minX) * sx, y: (band.minY - window.minY) * sy,
                                                         width: band.width * sx, height: band.height * sy).integral))
        let context = try XCTUnwrap(CGContext(data: nil, width: crop.width, height: crop.height, bitsPerComponent: 8,
                                             bytesPerRow: crop.width, space: CGColorSpaceCreateDeviceGray(),
                                             bitmapInfo: CGImageAlphaInfo.none.rawValue))
        context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height))
        let data = try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self)
        let pixels = Array(UnsafeBufferPointer(start: data, count: crop.width * crop.height))
        let median = Int(pixels.sorted()[pixels.count / 2])
        let contrast = Double(pixels.filter { abs(Int($0) - median) > 46 }.count) / Double(pixels.count)
        XCTAssertLessThan(contrast, 0.03, "Covered report text must not protrude into the status area above the image sheet")
    }

    func testIPadLinkedAboutReportsAppleAndUnknownOperatingSystems() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication()
        for (id, name, type, os) in [
            ("iphone", "Office iPhone", "iPhone", "iOS, 26.2"),
            ("mac", "Desk Mac", "Mac", "macOS, 26.1"),
            ("unknown", "Spare Mac", "Device", "OS version, 7.4")
        ] {
            app.launchArguments = ["-fixtureAppraiser", "-fixtureDeviceOSFacts", "-device", "cloud:bgi_fixture_os_" + id]
            app.launch()
            let more = app.buttons["wide-sidebar-More"]
            XCTAssertTrue(more.waitForExistence(timeout: 10)); more.tap()
            let about = app.buttons["About this device"].firstMatch
            for _ in 0..<8 where !about.isHittable { app.swipeUp() }
            XCTAssertTrue(about.isHittable); about.tap()
            XCTAssertTrue(app.navigationBars["About this device"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Name, " + name].exists)
            XCTAssertTrue(app.staticTexts["Device type, " + type].exists)
            XCTAssertTrue(app.staticTexts[os].exists)
            XCTAssertFalse(app.staticTexts["Android"].exists)
            capture("ipad-remote-about-os-" + id, app: app, markers: ["About this device", name, os])
            app.terminate()
        }
    }

    func testIPadReportedAndUnknownDeviceAboutAndConnectCancel() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires iPad")
        continueAfterFailure = false
        let app = XCUIApplication()
        func launch(device: String, identityFixture: Bool) {
            app.launchArguments = ["-fixtureAppraiser", "-device", device]
            if identityFixture { app.launchArguments.append("-fixtureDeviceIdentity") }
            app.launch()
            let more = app.buttons["wide-sidebar-More"]
            XCTAssertTrue(more.waitForExistence(timeout: 10)); more.tap()
        }
        func reveal(_ element: XCUIElement) {
            for _ in 0..<8 where !element.isHittable { app.swipeUp() }
            XCTAssertTrue(element.isHittable)
        }
        func openAbout() {
            let about = app.buttons["About this device"].firstMatch
            reveal(about); about.tap()
            XCTAssertTrue(app.navigationBars["About this device"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Device type"].exists)
        }
        launch(device: "cloud:bgi_sample_garage", identityFixture: false)
        openAbout()
        capture("ipad-remote-about-known-tablet", app: app, markers: ["About this device", "Garage tablet", "Device type", "Tablet"])
        XCTAssertTrue(app.staticTexts["Name, Garage tablet"].exists)
        XCTAssertTrue(app.staticTexts["Device type, Tablet"].exists)
        app.terminate()

        launch(device: "cloud:bgi_fixture_unknown", identityFixture: true)
        openAbout()
        capture("ipad-remote-about-unreported-type", app: app, markers: ["About this device", "Workshop unit", "Device type"])
        XCTAssertTrue(app.staticTexts["Name, Workshop unit"].exists)
        XCTAssertTrue(app.staticTexts["Device type, Device"].exists)
        let back = app.navigationBars["About this device"].buttons.firstMatch
        XCTAssertTrue(back.isHittable); back.tap()
        let entry = app.buttons["Connect a device with a code"].firstMatch
        reveal(entry); entry.tap()
        XCTAssertTrue(app.navigationBars["Connect a device"].waitForExistence(timeout: 10))
        let field = app.textFields["Device link code"]
        XCTAssertTrue(field.exists)
        XCTAssertTrue(app.buttons["Connect device"].exists)
        XCTAssertFalse(app.buttons["Connect device"].isEnabled)
        capture("ipad-device-connect-code", app: app, markers: ["Connect a device", "Device link code", "Cancel"])
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.navigationBars["Connect a device"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(entry.exists)
        capture("ipad-device-connect-cancelled", app: app, markers: ["Connect a device with a code"])
    }

    func testSettingsLegalLinksOpenSafariAndPreserveSettings() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        for (title, path) in [("Privacy policy", "privacy"), ("Terms of use", "terms")] {
            let link = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", title)).firstMatch
            XCTAssertTrue(link.waitForExistence(timeout: 5))
            if !link.isHittable { app.scrollViews.firstMatch.swipeUp() }
            XCTAssertEqual(link.value as? String, "https://bridgit.cc/" + path + "/")
            link.tap()
            XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 10))
            let pageTitle = safari.webViews.descendants(matching: .any).matching(
                NSPredicate(format: "label BEGINSWITH[c] %@", title)
            ).firstMatch
            XCTAssertTrue(pageTitle.waitForExistence(timeout: 15), "Safari did not show " + title)
            capture("safari-" + path, app: safari, markers: [pageTitle.label])
            app.activate()
            XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
            XCTAssertTrue(link.exists)
        }
        app.buttons["Done"].tap()
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
    }

    func testSettingsAboutShowsBuildIdentityAndLegalLinks() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        let terms = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Terms of use")).firstMatch
        XCTAssertTrue(terms.waitForExistence(timeout: 5))
        if !terms.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(terms.isHittable)
        XCTAssertTrue(app.buttons["Check for updates"].exists)
        capture("settings-about", app: app, markers: ["Version", "debug", "Commit", "docs/bridgit-spec-v2.md", "Privacy policy", "Terms of use"])
        let update = app.buttons["Check for updates"]
        if !update.isHittable { app.scrollViews.firstMatch.swipeDown() }
        update.tap()
        let updates = app.alerts["Updates"]
        XCTAssertTrue(updates.waitForExistence(timeout: 5))
        capture("settings-simulator-update", app: app, markers: ["Open TestFlight on your iPhone or iPad", "OK"])
        updates.buttons["OK"].tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
    }

    func testThemeChooserFromMainSettingsRow() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        let picker = app.buttons["settings-theme"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        picker.tap()
        for name in ["System", "Light", "Dark"] { XCTAssertTrue(app.buttons[name].exists) }
        capture("theme-chooser", app: app, markers: ["System", "Light", "Dark"])
        app.buttons["System"].tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
    }

    func testOwnerCanChangeThemeAndReturnToSystem() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let field = app.textFields["Search More and settings"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        let systemDark = try rendersDark(app)
        field.tap()
        field.typeText("theme\n")
        let entry = app.buttons["more-search-result-Settings · Theme"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        let picker = app.buttons["settings-theme"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        for name in ["Dark", "Light", "System"] {
            picker.tap()
            let choice = app.buttons[name]
            XCTAssertTrue(choice.waitForExistence(timeout: 3))
            choice.tap()
            let expectDark = name == "Dark" || (name == "System" && systemDark)
            XCTAssertEqual(try rendersDark(app), expectDark, "Open Settings sheet should render " + name)
            capture("theme-" + name.lowercased(), app: app, markers: ["Theme", name, "Send bug reports"])
            app.buttons["Done"].tap()
            XCTAssertTrue(entry.waitForExistence(timeout: 5))
            XCTAssertEqual(try rendersDark(app), expectDark, "More should render " + name)
            capture("more-" + name.lowercased(), app: app, markers: ["Settings · Theme", "Search More and settings"])
            entry.tap()
            XCTAssertTrue(picker.waitForExistence(timeout: 3))
            XCTAssertTrue(picker.label.contains(name) || (picker.value as? String) == name, app.debugDescription)
        }
    }

    func testPairedCameraTargetsWithoutCameraHardware() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-fixtureTwoPhotoTargets", "-device", "relay:sample-device-0001", "-tab", "camera"]
        app.launch()
        let all = app.buttons["Checked by: all applets (2)"]
        XCTAssertTrue(all.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Take photo"].isEnabled)
        capture("camera-linked-target", app: app, markers: ["Checked by: all applets (2)", "No camera available", "Take photo"])
        all.tap()
        let beta = app.buttons["Fixture Beta"]
        XCTAssertTrue(beta.waitForExistence(timeout: 5))
        XCTAssertEqual(beta.value as? String, "Selected")
        XCTAssertEqual(app.buttons["Fixture Alpha"].value as? String, "Selected")
        for item in [beta, app.buttons["Cancel"], app.buttons["OK"]] {
            XCTAssertGreaterThanOrEqual(item.frame.width, 44)
            XCTAssertGreaterThanOrEqual(item.frame.height, 44)
        }
        capture("camera-linked-target-picker", app: app, markers: ["Which applets check these photos?", "Fixture Alpha", "Fixture Beta", "Cancel", "OK"])
        beta.tap()
        XCTAssertEqual(beta.value as? String, "Not selected")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(all.waitForExistence(timeout: 5))
        all.tap()
        beta.tap()
        app.buttons["OK"].tap()
        let alpha = app.buttons["Checked by: Fixture Alpha"]
        XCTAssertTrue(alpha.waitForExistence(timeout: 5))
        selectDestination("More", in: app)
        selectDestination("Camera", in: app)
        XCTAssertTrue(alpha.waitForExistence(timeout: 5))
        capture("camera-linked-persisted-subset", app: app, markers: ["Checked by: Fixture Alpha", "Take photo"])
        alpha.tap()
        XCTAssertEqual(beta.value as? String, "Not selected")
        app.buttons["Fixture Alpha"].tap()
        app.buttons["OK"].tap()
        XCTAssertTrue(all.waitForExistence(timeout: 5))
        capture("camera-linked-none-means-all", app: app, markers: ["Checked by: all applets (2)", "Take photo"])
    }

    private func selectDestination(_ name: String, in app: XCUIApplication) {
        let tab = app.tabBars.buttons[name]
        let destination = tab.isHittable ? tab : app.buttons["wide-sidebar-" + name]
        XCTAssertTrue(destination.waitForExistence(timeout: 5) && destination.isHittable, name)
        destination.tap()
    }

    func testLocalNetworkCoverageAndReturn() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-device", "local"]
        app.launch(); selectDestination("More", in: app)
        let route = app.buttons["Network device monitor"].firstMatch
        for _ in 0..<10 where !route.exists || !route.isHittable { app.swipeUp() }
        XCTAssertTrue(route.waitForExistence(timeout: 15)); XCTAssertTrue(route.isHittable); route.tap()
        let coverage = app.buttons["Discovery coverage"].firstMatch
        XCTAssertTrue(coverage.waitForExistence(timeout: 15)); XCTAssertTrue(coverage.isHittable)
        let stamps = app.descendants(matching: .any).matching(identifier: "fixture-build-stamp")
        XCTAssertTrue(stamps.firstMatch.waitForExistence(timeout: 5))
        capture("network-coverage-entry", app: app, markers: ["Discovery coverage", "Map", "List"])
        coverage.tap()
        let title = app.staticTexts["Discovery coverage"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        let done = app.buttons["Done"].firstMatch
        XCTAssertTrue(done.isHittable)
        XCTAssertTrue(app.staticTexts["Audio & video"].waitForExistence(timeout: 10))
        XCTAssertFalse(title.frame.intersects(done.frame), "Coverage title must not overlap Done")
        capture("network-coverage-open", app: app, markers: ["Discovery coverage", "Audio & video", "Done"])
        let other = app.staticTexts["Other discovery"].firstMatch
        for _ in 0..<12 where !other.exists || !other.isHittable { app.swipeUp() }
        XCTAssertTrue(other.exists); XCTAssertTrue(other.isHittable)
        capture("network-coverage-end", app: app, markers: ["Other discovery", "Done"])
        done.tap()
        XCTAssertTrue(coverage.waitForExistence(timeout: 10))
        capture("network-coverage-return", app: app, markers: ["Discovery coverage", "Map", "List"])
    }

    func testLocalNetworkInventoryAndCancelOwnerScan() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-sampleData", "-device", "local"]
        app.launch(); selectDestination("More", in: app)
        let route = app.buttons["Network device monitor"].firstMatch
        for _ in 0..<10 where !route.exists || !route.isHittable { app.swipeUp() }
        XCTAssertTrue(route.waitForExistence(timeout: 15)); XCTAssertTrue(route.isHittable); route.tap()
        let local = app.staticTexts["This device’s network"].firstMatch
        XCTAssertTrue(local.waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["Sample network inventory"].exists)
        XCTAssertTrue(app.buttons["Map"].exists); XCTAssertTrue(app.buttons["List"].exists)
        let core = UIDevice.current.userInterfaceIdiom == .pad ? "This iPad" : "This iPhone"
        let mapLabel = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Devices map: " + core)).firstMatch
        XCTAssertTrue(mapLabel.waitForExistence(timeout: 15), "The local map should identify its Apple device")
        capture("local-network-map", app: app, markers: ["This device’s network", "Map", "List", core])
        app.buttons["List"].tap()
        XCTAssertTrue(local.exists)
        capture("local-network-list", app: app, markers: ["This device’s network", "List"])
        let scan = app.buttons["Run Level 2 Diagnostic: look around this network"].firstMatch
        for _ in 0..<8 where !scan.isHittable { app.swipeUp() }
        XCTAssertTrue(scan.isHittable); scan.tap()
        XCTAssertTrue(app.staticTexts["Allow a network scan?"].waitForExistence(timeout: 10))
        capture("local-network-scan-confirm", app: app, markers: ["Allow a network scan?", "Allow scan"])
        let review = app.scrollViews["network.scan.review"].firstMatch
        let disclosure = app.staticTexts["network.scan.disclosure"].firstMatch
        XCTAssertTrue(review.exists); XCTAssertTrue(disclosure.exists)
        for _ in 0..<8 where disclosure.frame.maxY > review.frame.maxY + 1 { review.swipeUp() }
        XCTAssertLessThanOrEqual(disclosure.frame.maxY, review.frame.maxY + 1)
        XCTAssertTrue(app.buttons["Allow scan"].isHittable)
        capture("local-network-scan-disclosure-end", app: app, markers: ["Allow scan", "Cancel"])
        let cancel = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.exists); XCTAssertTrue(cancel.isHittable); cancel.tap()
        XCTAssertTrue(app.staticTexts["Allow a network scan?"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(local.exists)
        capture("local-network-scan-cancel", app: app, markers: ["This device’s network"])
    }

    func testLinkedSampleNetworkInventoryDoesNotUseLocalSource() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-device", "relay:sample-device-0001"]
        app.launch(); selectDestination("More", in: app)
        let route = app.buttons["Network device monitor"].firstMatch
        for _ in 0..<10 where !route.exists || !route.isHittable { app.swipeUp() }
        XCTAssertTrue(route.waitForExistence(timeout: 15)); XCTAssertTrue(route.isHittable); route.tap()
        let sample = app.staticTexts["Sample network inventory"].firstMatch
        XCTAssertTrue(sample.waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["This device’s network"].exists)
        XCTAssertTrue(app.buttons["Map"].exists); XCTAssertTrue(app.buttons["List"].exists)
        capture("linked-sample-network-map", app: app, markers: ["Sample network inventory", "Map", "List"])
        app.buttons["List"].tap(); XCTAssertTrue(sample.exists)
        capture("linked-sample-network-list", app: app, markers: ["Sample network inventory", "List"])
    }

    func testStandaloneCameraWithoutHardwareOrTargets() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "camera"]
        app.launch()
        let hardware = app.staticTexts["No camera available"]
        XCTAssertTrue(hardware.waitForExistence(timeout: 8))
        let shutter = app.buttons["Take photo"]
        XCTAssertTrue(shutter.exists)
        XCTAssertFalse(shutter.isEnabled)
        XCTAssertFalse(app.buttons["Switch between the rear and front camera"].exists)
        XCTAssertFalse(app.staticTexts["0 photos sent"].exists)
        capture("camera-without-hardware", app: app, markers: ["No camera available", "Photos are not connected to any Bridgit applet.", "Take photo"])
        selectDestination("More", in: app)
        selectDestination("Camera", in: app)
        XCTAssertTrue(hardware.waitForExistence(timeout: 5))
        XCTAssertFalse(shutter.isEnabled)
    }

    func testPersistentDispatchedHostActivity() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureOneCreator", "-fixtureHostActivity", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-request-"))
        let two = NSPredicate(format: "count == 2")
        expectation(for: two, evaluatedWith: rows)
        waitForExpectations(timeout: 15)
        XCTAssertTrue(app.staticTexts["Last 24 hours · 2 requests · 0 photos · 0 clips · 1 error"].waitForExistence(timeout: 5))
        capture("dispatched-activity", app: app, markers: ["Fixture Alpha", "interactions.read", "200", "422"])
        app.buttons["activity-agent"].tap()
        app.buttons["Fixture Alpha"].tap()
        XCTAssertEqual(rows.count, 2)
        app.buttons["activity-type"].tap()
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.buttons["Settings"].isSelected)
        app.buttons["Done"].tap()
        expectation(for: NSPredicate(format: "count == 0"), evaluatedWith: rows)
        waitForExpectations(timeout: 5)
        app.buttons["activity-type"].tap()
        app.buttons["Clear"].tap()
        expectation(for: two, evaluatedWith: rows)
        waitForExpectations(timeout: 5)
        app.descendants(matching: .any)["activity-errors"].firstMatch.tap()
        expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: rows)
        waitForExpectations(timeout: 5)
        capture("dispatched-errors", app: app, markers: ["Fixture Alpha", "interactions.read", "422"])
        rows.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Entry details"].waitForExistence(timeout: 5))
        capture("dispatched-detail", app: app, markers: ["Entry details", "Fixture Alpha", "interactions.read", "422", "ts"])
        let fields = app.descendants(matching: .any)["activity-detail-fields"].firstMatch
        let last = app.descendants(matching: .any)["activity-detail-field-data"].firstMatch
        let copy = app.buttons["Copy as JSON"]
        func lastFieldVisible() -> Bool {
            last.exists && last.isHittable && last.frame.height > 0 && last.frame.minY >= fields.frame.minY && last.frame.maxY <= min(fields.frame.maxY, copy.frame.minY)
        }
        for _ in 0..<12 where !lastFieldVisible() { fields.swipeUp() }
        XCTAssertTrue(lastFieldVisible(), "Final data field must be fully reachable above Copy")
        XCTAssertTrue(copy.isHittable)
        capture("dispatched-detail-scrolled", app: app, markers: ["Entry details", "Copy as JSON"])
        app.buttons["Fixture Alpha's activity"].tap()
        XCTAssertEqual(app.buttons["activity-agent"].label, "Fixture Alpha")
        XCTAssertEqual(rows.count, 1)
        XCTAssertTrue(rows.firstMatch.label.contains("422"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        expectation(for: two, evaluatedWith: rows)
        waitForExpectations(timeout: 10)
        capture("dispatched-reopen", app: app, markers: ["Fixture Alpha", "200", "422"])
    }

    func testActivityDuplicateAgentIdentitySurvivesDetailAndExport() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureDuplicateCreators", "-fixtureHostActivity", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 10))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        let requests = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-request-"))
        expectation(for: NSPredicate(format: "count == 4"), evaluatedWith: requests)
        waitForExpectations(timeout: 15)
        app.buttons["activity-agent"].tap()
        let choices = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Fixture Same · "))
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: choices)
        waitForExpectations(timeout: 5)
        let first = choices.element(boundBy: 0).label
        let second = choices.element(boundBy: 1).label
        XCTAssertNotEqual(first, second)
        choices.element(boundBy: 0).tap()
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: requests)
        waitForExpectations(timeout: 5)
        XCTAssertEqual(app.buttons["activity-agent"].label, first)
        capture("duplicate-agent-selected", app: app, markers: ["Fixture Same", "interactions.read"])
        requests.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Entry details"].waitForExistence(timeout: 5))
        app.buttons["Fixture Same's activity"].tap()
        XCTAssertEqual(app.buttons["activity-agent"].label, first)
        XCTAssertEqual(requests.count, 2)
        app.buttons["activity-overflow"].tap()
        app.buttons["activity-export-open"].tap()
        let count = app.descendants(matching: .any)["activity-export-count"].firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 5))
        XCTAssertTrue(count.label.contains("3 entries"), count.label)
        capture("duplicate-agent-export", app: app, markers: ["3 entries", "Export activity"])
        app.buttons["activity-export-cancel"].tap()
        XCTAssertEqual(app.buttons["activity-agent"].label, first)
        XCTAssertEqual(requests.count, 2)
        app.buttons["activity-agent"].tap()
        app.buttons[second].tap()
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: requests)
        waitForExpectations(timeout: 5)
        XCTAssertEqual(app.buttons["activity-agent"].label, second)
        capture("duplicate-agent-other", app: app, markers: ["Fixture Same", "interactions.read"])
    }

    func testAuthenticatedNotesFilterDetailAndIdentity() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureDuplicateCreators", "-fixtureHostNotes", "-fixtureExposeStampBounds", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 10))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        let stamps = app.descendants(matching: .any).matching(identifier: "fixture-build-stamp")
        let stamp = stamps.firstMatch
        let overflow = app.buttons["activity-overflow"]
        XCTAssertTrue(stamp.waitForExistence(timeout: 5)); XCTAssertEqual(stamps.count, 1); XCTAssertTrue(overflow.exists)
        XCTAssertTrue(stamp.label.contains("11061749"), "Use the representative eight-digit build stamp")
        XCTAssertFalse(stamp.frame.intersects(overflow.frame), "Build stamp must not overlap the More control")
        if app.windows.firstMatch.frame.width < 600 {
            let title = app.staticTexts["Activity"].firstMatch
            XCTAssertTrue(title.exists)
            XCTAssertFalse(stamp.frame.intersects(title.frame), "Build stamp must not overlap the title")
        }
        let agentControl = app.descendants(matching: .any)["activity-agent"].firstMatch
        let typeControl = app.buttons["activity-type"]
        let errorsControl = app.descendants(matching: .any)["activity-errors"].firstMatch
        let liveControl = app.descendants(matching: .any)["activity-live"].firstMatch
        let controls = [agentControl, typeControl, errorsControl, liveControl]
        for control in controls {
            XCTAssertTrue(control.exists)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
        }
        for i in controls.indices {
            for j in controls.indices where j > i {
                let a = controls[i].frame, b = controls[j].frame
                XCTAssertFalse(a.intersects(b), "Activity filter hit targets must not overlap")
                let gap = max(max(b.minX - a.maxX, a.minX - b.maxX), max(b.minY - a.maxY, a.minY - b.maxY))
                XCTAssertGreaterThanOrEqual(gap, 7, "Activity filter hit targets need eight points of separation")
            }
        }
        if app.windows.firstMatch.frame.width < 600 && agentControl.frame.height < 60 {
            XCTAssertLessThan(abs(agentControl.frame.midY - typeControl.frame.midY), 2)
            XCTAssertGreaterThanOrEqual(typeControl.frame.minX - agentControl.frame.maxX, 7)
            XCTAssertLessThan(abs(errorsControl.frame.midY - liveControl.frame.midY), 2)
            XCTAssertGreaterThanOrEqual(liveControl.frame.minX - errorsControl.frame.maxX, 7)
            XCTAssertGreaterThanOrEqual(errorsControl.frame.minY - agentControl.frame.maxY, 7)
        }
        let tabBar = app.tabBars.firstMatch
        if tabBar.exists {
            var scrolled = false
            for _ in 0..<6 where !liveControl.isHittable || liveControl.frame.maxY > tabBar.frame.minY {
                app.swipeUp(); scrolled = true
            }
            XCTAssertTrue(liveControl.isHittable, "Live must remain reachable at largest text")
            XCTAssertLessThanOrEqual(liveControl.frame.maxY, tabBar.frame.minY, "Live must scroll fully above the tab bar")
            if scrolled {
                capture("activity-controls-reachable", app: app, markers: ["Live"])
                app.swipeDown()
            }
        }
        let notes = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-note-"))
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: notes)
        waitForExpectations(timeout: 15)
        app.buttons["activity-type"].tap()
        app.buttons["activity-choice-Notes"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(notes.count, 2)
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-request-")).count, 0)
        capture("notes-filtered", app: app, markers: ["Fixture error note", "Fixture info note"])
        app.descendants(matching: .any)["activity-errors"].firstMatch.tap()
        expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: notes)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(notes.firstMatch.label.contains("Fixture error note"))
        notes.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Entry details"].waitForExistence(timeout: 5))
        let level = app.descendants(matching: .any)["activity-detail-field-level"].firstMatch
        XCTAssertTrue(level.exists)
        XCTAssertTrue(level.label.contains("error") || (level.value as? String ?? "").contains("error"))
        let fields = app.descendants(matching: .any)["activity-detail-fields"].firstMatch
        let data = app.descendants(matching: .any)["activity-detail-field-data"].firstMatch
        for _ in 0..<12 where !data.isHittable { fields.swipeUp() }
        XCTAssertTrue(data.isHittable)
        XCTAssertTrue(data.label.contains("first") || (data.value as? String ?? "").contains("first"))
        capture("notes-error-detail", app: app, markers: ["Entry details", "Copy as JSON"])
        app.buttons["Fixture Same's activity"].tap()
        XCTAssertTrue(app.buttons["activity-agent"].label.contains("Fixture Same"))
        XCTAssertEqual(notes.count, 1)
        capture("notes-agent-return", app: app, markers: ["Fixture error note"])
        let current = app.buttons["activity-agent"].label
        app.buttons["activity-agent"].tap()
        let choices = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND identifier != %@", "Fixture Same · ", "activity-agent"))
        XCTAssertEqual(choices.count, 2)
        let other = choices.allElementsBoundByIndex.first { $0.label != current }
        XCTAssertNotNil(other)
        other?.tap()
        XCTAssertEqual(notes.count, 0)
        app.descendants(matching: .any)["activity-errors"].firstMatch.tap()
        expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: notes)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(notes.firstMatch.label.contains("Fixture info note"))
        capture("notes-other-agent", app: app, markers: ["Fixture info note"])
    }

    func testAuthenticatedNoteShowsCurrentAgentNameAfterRename() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureOneCreator", "-fixtureHostNotes", "-fixtureRenameNotes", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 10))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        let note = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-note-")).firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 15))
        let renamed = NSPredicate(format: "label CONTAINS %@", "Fixture Renamed")
        expectation(for: renamed, evaluatedWith: note)
        waitForExpectations(timeout: 10)
        capture("notes-renamed-row", app: app, markers: ["Fixture error note", "Fixture Renamed"])
        note.tap()
        let agent = app.descendants(matching: .any)["activity-detail-field-agent"].firstMatch
        XCTAssertTrue(agent.waitForExistence(timeout: 5))
        XCTAssertTrue(agent.label.contains("Fixture Renamed") || (agent.value as? String ?? "").contains("Fixture Renamed"))
        XCTAssertTrue(app.buttons["Fixture Renamed's activity"].exists)
        capture("notes-renamed-detail", app: app, markers: ["Entry details", "Fixture Renamed"])
    }

    func testActivityTypeChooserRetainsAndClearsMultipleTypes() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 8))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        app.buttons["activity-type"].tap()
        let choices = ["Requests", "Settings", "Agents", "Diagnostics", "Notes"]
        for name in choices {
            XCTAssertTrue(app.buttons["activity-choice-" + name].exists)
            XCTAssertFalse(app.buttons["activity-choice-" + name].isSelected)
        }
        for button in choices.map({ app.buttons["activity-choice-" + $0] }) + [app.buttons["Clear"], app.buttons["Done"]] {
            XCTAssertGreaterThanOrEqual(button.frame.width, 44)
            XCTAssertGreaterThanOrEqual(button.frame.height, 44)
        }
        capture("activity-types-default", app: app, markers: ["Choose types", "Requests", "Settings", "Agents", "Diagnostics", "Notes", "Clear", "Done"])
        app.buttons["activity-choice-Requests"].tap()
        app.buttons["activity-choice-Settings"].tap()
        XCTAssertTrue(app.buttons["activity-choice-Requests"].isSelected)
        XCTAssertTrue(app.buttons["activity-choice-Settings"].isSelected)
        capture("activity-types-multiple", app: app, markers: ["Choose types", "Requests", "Settings", "Clear", "Done"])
        app.buttons["Done"].tap()
        XCTAssertEqual(app.buttons["activity-type"].label, "Types (2)")
        capture("activity-types-applied", app: app, markers: ["Types (2)", "No entries match", "Clear filters"])
        app.buttons["activity-type"].tap()
        XCTAssertTrue(app.buttons["activity-choice-Requests"].isSelected)
        XCTAssertTrue(app.buttons["activity-choice-Settings"].isSelected)
        capture("activity-types-reopened", app: app, markers: ["Choose types", "Requests", "Settings"])
        app.buttons["Clear"].tap()
        XCTAssertTrue(app.staticTexts["Nothing yet."].waitForExistence(timeout: 5))
        capture("activity-after-clear", app: app, markers: ["Nothing yet.", "Type"])
        app.buttons["activity-type"].tap()
        for name in choices { XCTAssertFalse(app.buttons["activity-choice-" + name].isSelected) }
        capture("activity-types-cleared", app: app, markers: ["Choose types", "Requests", "Notes", "Clear", "Done"])
        app.buttons["Done"].tap()
    }

    func testLocalFileFeatureSwitchesRetainOwnerSelections() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more"]
        app.launch()
        let features = app.buttons["Feature enablement"].firstMatch
        func openSettings() {
            XCTAssertTrue(features.waitForExistence(timeout: 8))
            if !features.isHittable { app.swipeUp() }
            features.tap()
            let loaded = app.switches["transfer-switch-files"]
            expectation(for: NSPredicate(format: "exists == true AND enabled == true"), evaluatedWith: loaded)
            waitForExpectations(timeout: 5)
        }
        openSettings()
        let switches = ["files", "shares", "downloads"].map { app.switches["transfer-switch-" + $0] }
        let labels = ["Receive files", "Share to a control agent", "Selected Downloads"]
        for (index, toggle) in switches.enumerated() {
            XCTAssertTrue(toggle.waitForExistence(timeout: 5))
            XCTAssertEqual(toggle.value as? String, "0")
            XCTAssertTrue(toggle.label.contains(labels[index]))
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: toggle)
            waitForExpectations(timeout: 5)
        }
        capture("local-files-enabled", app: app, markers: labels)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        openSettings()
        XCTAssertEqual(switches.map { $0.value as? String }, ["1", "1", "1"])
        let shares = switches[1]
        shares.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        expectation(for: NSPredicate(format: "value == '0'"), evaluatedWith: shares)
        waitForExpectations(timeout: 5)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        openSettings()
        XCTAssertEqual(switches.map { $0.value as? String }, ["1", "0", "1"])
        capture("local-files-share-disabled", app: app, markers: labels)
    }

    func testSystemSettingsFilterIncludesOwnerFileChanges() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more"]
        app.launch()
        let ownerSettings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(ownerSettings.waitForExistence(timeout: 8))
        if !ownerSettings.isHittable { app.swipeUp() }
        ownerSettings.tap()
        let sending = app.switches["Send bug reports to the Bridgit team"]
        XCTAssertTrue(sending.waitForExistence(timeout: 5))
        if sending.value as? String == "1" { sending.tap() }
        XCTAssertEqual(sending.value as? String, "0")
        app.buttons["Done"].tap()
        let features = app.buttons["Feature enablement"].firstMatch
        XCTAssertTrue(features.waitForExistence(timeout: 8))
        if !features.isHittable { app.swipeUp() }
        features.tap()
        let receive = app.switches["transfer-switch-files"].firstMatch
        let downloads = app.switches["transfer-switch-downloads"].firstMatch
        for (toggle, initial, expected) in [(receive, "0", "1"), (downloads, "0", "1"), (receive, "1", "0"), (downloads, "1", "0")] {
            XCTAssertEqual(toggle.value as? String, initial)
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            expectation(for: NSPredicate(format: "value == %@", expected), evaluatedWith: toggle)
            waitForExpectations(timeout: 5)
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 8))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        app.buttons["activity-type"].tap()
        app.buttons["activity-choice-Settings"].tap()
        XCTAssertTrue(app.buttons["activity-choice-Settings"].isSelected)
        capture("activity-types-settings", app: app, markers: ["Choose types", "Settings", "Clear", "Done"])
        app.buttons["Done"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "You turned Selected Downloads off")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        capture("system-transfer-settings", app: app, markers: ["Bridgit (system)", "You turned Selected Downloads off", "Settings"])
        let settingRows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity-request-"))
        XCTAssertEqual(settingRows.count, 4)
        row.tap()
        XCTAssertTrue(app.staticTexts["Entry details"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["You turned Selected Downloads off"].exists)
        capture("system-transfer-detail", app: app, markers: ["You turned Selected Downloads off", "Entry details", "channel", "audit", "level", "setting", "Copy as JSON"])
        for name in ["Copy as JSON", "Bridgit's activity", "Done"] {
            XCTAssertGreaterThanOrEqual(app.buttons[name].frame.height, 44)
            XCTAssertGreaterThanOrEqual(app.buttons[name].frame.width, 44)
        }
        app.buttons["Copy as JSON"].tap()
        XCTAssertTrue(app.buttons["Copied"].waitForExistence(timeout: 5))
        app.buttons["Bridgit's activity"].tap()
        XCTAssertEqual(app.buttons["activity-agent"].label, "Bridgit (system)")
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        app.buttons["activity-agent"].tap()
        app.buttons["All agents"].tap()
        app.buttons["activity-type"].tap()
        app.buttons["Clear"].tap()
        XCTAssertEqual(app.buttons["activity-type"].label, "Type")
        row.tap()
        app.buttons["Report a bug about this"].tap()
        XCTAssertTrue(app.staticTexts["What happened?"].waitForExistence(timeout: 5))
        capture("contextual-report-form", app: app, markers: ["Report a bug", "What happened?", "Activity, as you left it"])
        let preview = app.buttons["Preview"]
        for _ in 0..<4 where !preview.isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["4 log entries, plus the entry you picked"].exists)
        for button in [preview, app.buttons["Cancel"], app.buttons["Add screenshot"]] {
            XCTAssertGreaterThanOrEqual(button.frame.height, 44)
        }
        capture("contextual-report-disclosure", app: app, markers: ["4 log entries, plus the entry you picked", "Include recent log", "Preview"])
        preview.tap()
        let event = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "You turned Selected Downloads off")).firstMatch
        XCTAssertTrue(event.waitForExistence(timeout: 5))
        capture("contextual-report-log", app: app, markers: ["Recent log", "You turned Selected Downloads off", "You turned Receive files on", "Done"])
        app.buttons["Done"].tap()
        let logChoice = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Include recent log")).firstMatch
        logChoice.tap()
        XCTAssertEqual(logChoice.value as? String, "0")
        XCTAssertTrue(preview.isHittable)
        capture("contextual-report-opt-out", app: app, markers: ["Include recent log", "4 log entries, plus the entry you picked", "Preview"])
        app.buttons["Cancel"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertTrue(row.isHittable)
        XCTAssertFalse(app.alerts["Keep this draft?"].exists)
        capture("contextual-report-return", app: app, markers: ["Activity", "You turned Selected Downloads off", "You turned Receive files on"])
        for includeLog in [true, false] {
            row.tap()
            app.buttons["Report a bug about this"].tap()
            let editor = app.textViews.firstMatch
            XCTAssertTrue(editor.waitForExistence(timeout: 5))
            editor.tap()
            editor.typeText(includeLog ? "3347 saved with selected entry" : "3347 saved without attached log")
            let log = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Include recent log")).firstMatch
            if !includeLog {
                for _ in 0..<4 where !log.isHittable { app.swipeUp() }
                log.tap()
            }
            XCTAssertEqual(log.value as? String, includeLog ? "1" : "0")
            app.buttons["Submit"].tap()
            let saved = app.alerts["Report saved"]
            XCTAssertTrue(saved.waitForExistence(timeout: 8))
            XCTAssertTrue(app.staticTexts["Saved on this device. Sending reports to the Bridgit team was turned off, so it has no report number."].exists)
            saved.buttons["OK"].tap()
            XCTAssertTrue(row.waitForExistence(timeout: 5))
        }


    }

    func testLocalActivityShowsAndFiltersRealHostEvents() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureOneCreator", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        let event = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Host identity prepared locally.")).firstMatch
        XCTAssertTrue(event.waitForExistence(timeout: 10))
        capture("local-activity-event", app: app, markers: ["Host identity prepared locally.", "Search activity"])
        let field = app.textFields["Search activity"]
        field.tap()
        field.typeText("no such fixture")
        XCTAssertTrue(app.staticTexts["No entries match"].waitForExistence(timeout: 5))
        XCTAssertFalse(event.exists)
    }

    func testStandaloneDeviceReportsAvailableHardware() {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone"]
        app.launch()
        app.tabBars.buttons["Devices"].tap()
        XCTAssertTrue(app.staticTexts["This device"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Camera, No camera found"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Microphone, ")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Host local applets"].exists)
        XCTAssertTrue(app.staticTexts["Available locally"].exists)
        capture("standalone-device-hardware", app: app, markers: ["This device", "No camera found", "Microphone", "Host local applets", "Available locally"])
        let rerun = app.buttons["Rerun diagnostics"]
        if !rerun.isHittable { app.swipeUp() }
        XCTAssertTrue(rerun.waitForExistence(timeout: 5))
        rerun.tap()
        XCTAssertTrue(app.staticTexts["Camera, No camera found"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Checked ")).firstMatch.waitForExistence(timeout: 5))
        capture("device-diagnostics-rerun", app: app, markers: ["No camera found", "Microphone", "Rerun diagnostics"])
        let hardware = app.buttons["Hardware access"]
        if !hardware.isHittable { app.swipeUp() }
        XCTAssertTrue(hardware.waitForExistence(timeout: 5))
        hardware.tap()
        XCTAssertTrue(app.navigationBars["Hardware access"].waitForExistence(timeout: 5))
        capture("device-hardware-access", app: app, markers: ["Hardware access", "Camera", "Microphone"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["Rerun diagnostics"].waitForExistence(timeout: 5))
        capture("device-hardware-return", app: app, markers: ["Rerun diagnostics", "Hardware access"])
        let log = app.buttons["Open the full log"]
        if !log.isHittable { app.swipeUp() }
        XCTAssertTrue(log.waitForExistence(timeout: 5))
        log.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 5))
        capture("device-full-log", app: app, markers: ["Activity", "Search activity"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["This device"].waitForExistence(timeout: 5))
        capture("device-full-log-return", app: app, markers: ["This device", "Open the full log"])
    }

    func testHardwareMicrophoneAllow() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.resetAuthorizationStatus(for: .microphone)
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let row = app.buttons["Hardware access"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        if !row.isHittable { app.swipeUp() }
        row.tap()
        let grant = app.buttons["Grant microphone access"]
        XCTAssertTrue(grant.waitForExistence(timeout: 5))
        grant.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 5))
        allow.tap()
        XCTAssertTrue(app.staticTexts["All set"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["On"].exists)
        capture("hardware-microphone-allowed", app: app, markers: ["All set", "Microphone", "On"])
    }

    func testHardwareMicrophoneRevoked() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let row = app.buttons["Hardware access"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        if !row.isHittable { app.swipeUp() }
        row.tap()
        guard app.buttons["Open microphone settings"].waitForExistence(timeout: 5) else {
            throw XCTSkip("Requires microphone permission revoked on this disposable simulator before launch.")
        }
        XCTAssertTrue(app.staticTexts["Needs you"].exists)
        capture("hardware-microphone-revoked", app: app, markers: ["Needs you", "Microphone", "Off"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tabBars.buttons["Devices"].tap()
        XCTAssertTrue(app.staticTexts["Microphone, Needs access"].waitForExistence(timeout: 5))
        capture("device-microphone-revoked", app: app, markers: ["Microphone", "Needs access"])
    }

    private func captureNoticeAppearance(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureCaptureNotice", "-fixtureCaptureNoticeLong", "-fixtureExposeStampBounds", "-tab", "more", "-device", "local"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("software")
        let result = app.buttons["more-search-result-Software access"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
        let grant = app.buttons["software-grant-notifications"]
        if grant.waitForExistence(timeout: 3) {
            grant.tap()
            let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow"]
            XCTAssertTrue(allow.waitForExistence(timeout: 10)); allow.tap()
        }
        let send = app.buttons["fixture-capture-notice"]
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        for _ in 0..<6 where !send.isHittable { app.swipeUp() }
        XCTAssertTrue(send.isHittable); send.tap()
        let active = app.staticTexts["active-capture-notice"]
        XCTAssertTrue(active.waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 8) // Let the OS banner dismiss naturally while the fixture stays active.
        XCTAssertTrue(active.exists)
        capture(large ? "capture-notice-large-after-banner" : "capture-notice-normal-after-banner", app: app, markers: [])
        let stamp = app.descendants(matching: .any).matching(identifier: "fixture-build-stamp").firstMatch
        XCTAssertTrue(stamp.waitForExistence(timeout: 5))
        let frames = XCTAttachment(string: "active=\(active.frame) stamp=\(stamp.frame) window=\(app.frame)")
        frames.name = "capture-notice-frames"; frames.lifetime = .keepAlways; add(frames)
        XCTAssertFalse(active.frame.intersects(stamp.frame))
        let bar = app.navigationBars["Software access"]
        XCTAssertTrue(bar.exists)
        XCTAssertFalse(active.frame.intersects(bar.frame))
        let back = app.navigationBars.buttons["BackButton"]
        XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5)); XCTAssertTrue(active.exists)
        capture(large ? "capture-notice-large-return" : "capture-notice-normal-return", app: app, markers: [])
        app.terminate()
    }
    func testCaptureNoticeNormalAppearance() { captureNoticeAppearance(large: false) }
    func testCaptureNoticeLargestAppearance() { captureNoticeAppearance(large: true) }

    func testCaptureNotificationPermissionUsesSystemPrompt() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureCaptureNotice", "-tab", "more", "-device", "local"]
        app.launch()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("software")
        let result = app.buttons["more-search-result-Software access"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
        let grant = app.buttons["software-grant-notifications"]
        XCTAssertTrue(grant.waitForExistence(timeout: 10)); grant.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = system.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10))
        capture("capture-notification-system-prompt", app: system, markers: [])
        allow.tap()
        let status = app.descendants(matching: .any).matching(identifier: "software-notifications-status").firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "On", "On"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        XCTAssertFalse(grant.exists)
        capture("capture-notifications-allowed", app: app, markers: [])
        let send = app.buttons["fixture-capture-notice"]
        XCTAssertTrue(send.waitForExistence(timeout: 5)); send.tap()
        let active = app.staticTexts["active-capture-notice"]
        XCTAssertTrue(active.waitForExistence(timeout: 5))
        XCTAssertTrue(active.label.contains("Fixture agent"))
        capture("capture-notice-active", app: app, markers: [])
        let delivered = app.staticTexts["fixture-capture-result"]
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Notice delivered to notification center"), evaluatedWith: delivered)
        waitForExpectations(timeout: 10)
        capture("capture-notice-delivered", app: app, markers: [])
        XCTAssertTrue(active.waitForNonExistence(timeout: 10))
    }

    func testWiFiHistoryLocationPromptDenied() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more", "-device", "local"]
        app.launch()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("hardware")
        let result = app.buttons["more-search-result-Hardware access"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
        let grant = app.buttons["wifi-history-grant"]
        for _ in 0..<6 where !grant.isHittable { app.swipeUp() }
        XCTAssertTrue(grant.isHittable); grant.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deny = system.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Don’t Allow", "Don't Allow")).firstMatch
        XCTAssertTrue(deny.waitForExistence(timeout: 10))
        capture("wifi-history-system-prompt", app: system, markers: [])
        deny.tap()
        let status = app.staticTexts["wifi-history-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Location denied"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        let settingsButton = app.buttons["wifi-history-settings"]
        XCTAssertTrue(settingsButton.exists)
        XCTAssertFalse(grant.exists)
        capture("wifi-history-denied", app: app, markers: [])
        settingsButton.tap()
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        capture("wifi-history-system-settings", app: settings, markers: [])
        let reachedAppSettings = settings.navigationBars["Bridgit"].waitForExistence(timeout: 10)
        app.activate()
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        XCTAssertTrue(status.label.contains("Location denied"))
        capture("wifi-history-settings-return", app: app, markers: [])
        XCTAssertTrue(reachedAppSettings, "Open location settings did not reach Bridgit's app settings")
    }

    func testWiFiHistoryReducedAccuracyFromSystemSettings() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more", "-device", "local"]
        app.launch()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("hardware")
        let result = app.buttons["more-search-result-Hardware access"]
        XCTAssertTrue(result.waitForExistence(timeout: 5)); result.tap()
        let grant = app.buttons["wifi-history-grant"]
        for _ in 0..<6 where !grant.isHittable { app.swipeUp() }
        XCTAssertTrue(grant.isHittable); grant.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = system.buttons["Allow While Using App"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10)); allow.tap()
        let status = app.staticTexts["wifi-history-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Allowed"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.activate()
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        let privacy = settings.buttons["Privacy & Security"].firstMatch
        for _ in 0..<8 where !privacy.isHittable { settings.swipeUp() }
        XCTAssertTrue(privacy.isHittable); privacy.tap()
        capture("wifi-history-system-privacy", app: settings, markers: [])
        let location = settings.descendants(matching: .any)["LOCATION"].firstMatch
        for _ in 0..<5 where !location.isHittable { settings.swipeUp() }
        XCTAssertTrue(location.isHittable); location.tap()
        capture("wifi-history-system-location", app: settings, markers: [])
        let bridgit = settings.cells["cc.bridgit.portal.debug"].firstMatch
        for _ in 0..<8 where !bridgit.isHittable { settings.swipeUp() }
        XCTAssertTrue(bridgit.isHittable); bridgit.tap()
        capture("wifi-history-system-app-location", app: settings, markers: [])
        let precise = settings.switches["Precise Location"]
        XCTAssertTrue(precise.waitForExistence(timeout: 10))
        if precise.value as? String == "1" { precise.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }
        capture("wifi-history-precise-after-tap", app: settings, markers: [])
        expectation(for: NSPredicate(format: "value == %@", "0"), evaluatedWith: precise)
        waitForExpectations(timeout: 10)
        capture("wifi-history-precise-disabled", app: settings, markers: [])
        app.activate()
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Precise location off"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.buttons["wifi-history-settings"].exists)
        capture("wifi-history-reduced", app: app, markers: [])
    }

    func testLocalPermissionHelpRoutesWithoutRequestingOSAccess() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more", "-device", "local"]
        app.launch()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 8))
        search.tap(); search.typeText("hardware")
        let hardware = app.buttons["more-search-result-Hardware access"]
        XCTAssertTrue(hardware.waitForExistence(timeout: 5)); hardware.tap()
        let hardwareHelp = app.buttons["hardware-access-help"]
        XCTAssertTrue(hardwareHelp.waitForExistence(timeout: 5)); hardwareHelp.tap()
        XCTAssertTrue(app.staticTexts["permission-help-camera"].waitForExistence(timeout: 5))
        for _ in 0..<5 {
            if app.staticTexts["permission-help-screenshots"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.staticTexts["permission-help-screenshots"].isHittable)
        XCTAssertFalse(XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch.exists)
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(hardwareHelp.waitForExistence(timeout: 5))
        app.navigationBars["Hardware access"].buttons.firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        app.buttons["Clear search"].tap()
        search.tap(); search.typeText("software")
        let software = app.buttons["more-search-result-Software access"]
        XCTAssertTrue(software.waitForExistence(timeout: 5)); software.tap()
        let softwareHelp = app.buttons["software-access-help"]
        XCTAssertTrue(softwareHelp.waitForExistence(timeout: 5)); softwareHelp.tap()
        XCTAssertTrue(app.staticTexts["permission-help-notifications"].waitForExistence(timeout: 5))
        for _ in 0..<5 {
            if app.staticTexts["permission-help-updates"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.staticTexts["permission-help-updates"].isHittable)
        XCTAssertFalse(app.buttons["Grant"].exists)
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(softwareHelp.waitForExistence(timeout: 5))
        app.navigationBars["Software access"].buttons.firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        capture("local-permission-help-return", app: app, markers: ["Software access"])
    }

    func testStandaloneHardwareAccessAndSearch() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.resetAuthorizationStatus(for: .microphone)
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let hardware = app.buttons["Hardware access"].firstMatch
        XCTAssertTrue(hardware.waitForExistence(timeout: 5))
        if !hardware.isHittable { app.swipeUp() }
        hardware.tap()
        XCTAssertTrue(app.navigationBars["Hardware access"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Camera"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Microphone"].exists)
        XCTAssertFalse(XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch.exists)
        capture("hardware-initial", app: app, markers: ["Hardware access", "Camera", "Microphone"])
        let grant = app.buttons["Grant microphone access"]
        if grant.exists {
            grant.tap()
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let dontAllow = springboard.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Don’t Allow", "Don't Allow")).firstMatch
            XCTAssertTrue(dontAllow.waitForExistence(timeout: 5))
            capture("hardware-prompt", app: springboard, markers: ["Bridgit"])
            dontAllow.tap()
            XCTAssertTrue(app.staticTexts["Microphone"].waitForExistence(timeout: 5))
            capture("hardware-after-permission", app: app, markers: ["Microphone", "Hardware access"])
        }
        let settings = app.buttons["Open app settings"]
        XCTAssertTrue(settings.exists)
        if !settings.isHittable { app.swipeUp() }
        settings.tap()
        let systemSettings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(systemSettings.wait(for: .runningForeground, timeout: 8))
        Thread.sleep(forTimeInterval: 5)
        capture("hardware-system-settings", app: systemSettings, markers: [])
        app.activate()
        XCTAssertTrue(app.navigationBars["Hardware access"].waitForExistence(timeout: 5))
        capture("hardware-settings-return", app: app, markers: ["Hardware access", "Camera", "Microphone"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let field = app.textFields["Search More and settings"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        if !field.isHittable { app.swipeDown() }
        field.tap()
        field.typeText("hardware permissions\n")
        let result = app.buttons["more-search-result-Hardware access"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        capture("hardware-search", app: app, markers: ["Hardware access", "hardware permissions"])
        result.tap()
        XCTAssertTrue(app.navigationBars["Hardware access"].waitForExistence(timeout: 5))
        capture("hardware-search-open", app: app, markers: ["Hardware access", "Camera", "Microphone"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertEqual(field.value as? String, "hardware permissions")
    }

    func testStandaloneLocalActivityAndSearchReturn() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-tab", "more"]
        app.launch()
        let activity = app.buttons["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        if !activity.isHittable { app.swipeUp() }
        activity.tap()
        XCTAssertTrue(app.staticTexts["Nothing yet."].waitForExistence(timeout: 5))
        capture("local-activity-empty", app: app, markers: ["Nothing yet", "Search activity"])
        for id in ["activity-agent", "activity-type", "activity-errors", "activity-live"] {
            let control = app.descendants(matching: .any)[id].firstMatch
            XCTAssertTrue(control.exists, id)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44, "Touch target too short: " + id)
        }
        let live = app.switches["activity-live"]
        XCTAssertTrue(live.exists); live.tap()
        let refresh = app.buttons["activity-refresh"]
        XCTAssertTrue(refresh.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(refresh.frame.height, 44, "Refresh must have a 44-point hit target")
        live.tap()
        XCTAssertTrue(refresh.waitForNonExistence(timeout: 5))
        let field = app.textFields["Search activity"]
        field.tap()
        field.typeText("fixture")
        XCTAssertTrue(app.staticTexts["No entries match"].waitForExistence(timeout: 5))
        capture("local-activity-search", app: app, markers: ["No entries match", "Search activity"])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        if !search.isHittable { app.swipeDown() }
        search.tap()
        search.typeText("activity\n")
        let result = app.buttons["more-search-result-Activity"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.tap()
        XCTAssertTrue(app.staticTexts["Nothing yet."].waitForExistence(timeout: 5))
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertEqual(search.value as? String, "activity")
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        capture("local-activity-return", app: app, markers: ["more-search-result-Activity"])
        app.buttons["Clear search"].tap()
        XCTAssertFalse(result.exists)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        capture("activity-one-tap-cleared", app: app, markers: ["Device and setup", "Feature enablement"])
    }

    func testPairPasteRejectsInvalidInputAndReturns() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let linked = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Linked devices")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 5))
        linked.tap()
        app.buttons["Pair a device"].tap()
        XCTAssertTrue(app.navigationBars["Pair a device"].waitForExistence(timeout: 5))
        capture("pair-scanner", app: app, markers: ["Pair a device", "Paste a link or setup text instead"])
        app.buttons["Paste a link or setup text instead"].tap()
        let use = app.buttons["Use it"]
        XCTAssertTrue(use.waitForExistence(timeout: 5))
        XCTAssertFalse(use.isEnabled)
        let editor = app.textViews.firstMatch
        editor.tap()
        editor.typeText("invalid fixture")
        XCTAssertTrue(use.isEnabled)
        use.tap()
        XCTAssertTrue(app.staticTexts["That isn't a Bridgit sign-in link, pairing link or connector setup."].waitForExistence(timeout: 5))
        capture("pair-invalid", app: app, markers: ["Paste", "That isn't a Bridgit sign-in link, pairing link or connector setup."])
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Pair a device"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Linked devices"].waitForExistence(timeout: 5))
        capture("pair-return", app: app, markers: ["Nothing is linked to this phone yet.", "Pair a device"])
    }

    func testDeviceMapSeparatesPairedHubsFromAccountViews() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureAppraiser", "-tab", "more"]
        app.launch()
        let linked = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Linked devices")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 5))
        linked.tap()
        let garage = app.buttons["Garage tablet"]
        XCTAssertTrue(garage.waitForExistence(timeout: 5))
        capture("device-roles", app: app, markers: ["View satellite", "Kitchen phone", "Hub", "Account view", "Garage tablet"])
        garage.tap()
        XCTAssertTrue(app.navigationBars["Garage tablet"].waitForExistence(timeout: 5))
        capture("account-peer", app: app, markers: ["Access", "Account view"])
        XCTAssertFalse(app.buttons["Remove"].exists)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Kitchen phone"].tap()
        XCTAssertTrue(app.navigationBars["Kitchen phone"].waitForExistence(timeout: 5))
        capture("paired-peer", app: app, markers: ["Role", "Hub", "Paired"])
    }

    func testRegularFeedbackPreviewsActivityWithLogOnAndOff() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureTransfers", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        if !settings.isHittable { app.swipeUp() }
        settings.tap()
        let sending = app.switches["Send bug reports to the Bridgit team"]
        XCTAssertTrue(sending.waitForExistence(timeout: 5))
        if sending.value as? String == "1" { sending.tap() }
        XCTAssertEqual(sending.value as? String, "0")
        app.buttons["Done"].tap()
        let report = app.buttons["Report a bug"].firstMatch
        XCTAssertTrue(report.waitForExistence(timeout: 8))
        if !report.isHittable { app.swipeUp() }
        report.tap()
        let preview = app.buttons["Preview"]
        for _ in 0..<5 where !preview.isHittable { app.swipeUp() }
        let log = app.switches["Include recent log"]
        XCTAssertTrue(log.exists)
        for include in [true, false] {
            if !include { log.tap() }
            XCTAssertEqual(log.value as? String, include ? "1" : "0")
            preview.tap()
            XCTAssertTrue(app.staticTexts["Recent log"].waitForExistence(timeout: 5))
            capture(include ? "regular-activity-included" : "regular-activity-excluded", app: app,
                    markers: ["You turned Selected Downloads on", "You turned Receive files on", "Done"])
            app.buttons["Done"].tap()
        }
        capture("regular-activity-opt-out-return", app: app, markers: ["Include recent log", "Preview"])
        app.buttons["Cancel"].tap()
        XCTAssertTrue(report.waitForExistence(timeout: 5))
        for includeLog in [true, false] {
            report.tap()
            if !includeLog {
                for _ in 0..<5 where !log.isHittable { app.swipeUp() }
                log.tap()
            }
            XCTAssertEqual(log.value as? String, includeLog ? "1" : "0")
            let editor = app.textViews.firstMatch
            for _ in 0..<5 where !editor.isHittable { app.swipeDown() }
            editor.tap()
            editor.typeText(includeLog ? "Regular Activity log included" : "Regular Activity log excluded")
            app.buttons["Submit"].tap()
            let saved = app.alerts["Report saved"]
            XCTAssertTrue(saved.waitForExistence(timeout: 8))
            XCTAssertTrue(app.staticTexts["Saved on this device. Sending reports to the Bridgit team was turned off, so it has no report number."].exists)
            saved.buttons["OK"].tap()
            XCTAssertTrue(report.waitForExistence(timeout: 5))
        }

    }

    func testLocalOnlyBugSaveAndHistory() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        if !settings.isHittable { app.swipeUp() }
        settings.tap()
        let sending = app.switches["Send bug reports to the Bridgit team"]
        XCTAssertTrue(sending.waitForExistence(timeout: 5))
        if sending.value as? String == "1" { sending.tap() }
        XCTAssertEqual(sending.value as? String, "0")
        capture("local-bug-sending-off", app: app, markers: ["Send bug reports to the Bridgit team"])
        app.buttons["Done"].tap()
        let report = app.buttons["Report a bug"].firstMatch
        XCTAssertTrue(report.waitForExistence(timeout: 5))
        report.tap()
        XCTAssertTrue(app.staticTexts["What happened?"].waitForExistence(timeout: 5))
        let editor = app.textViews.firstMatch
        editor.tap()
        editor.typeText("3347 local parity fixture")
        // Scroll from the form margin so the multiline editor does not consume the drag.
        let margin = app.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.52))
        margin.press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.20)))
        for label in ["Include last screen", "Include recent log"] {
            let toggle = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
            XCTAssertTrue(toggle.waitForExistence(timeout: 5))
            if toggle.value as? String == "1" {
                let keyboard = app.keyboards.firstMatch
                if !toggle.isHittable || (keyboard.exists && toggle.frame.maxY >= keyboard.frame.minY) { app.swipeUp() }
                toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            }
            XCTAssertEqual(toggle.value as? String, "0")
        }
        let submit = app.buttons["Submit"]
        XCTAssertTrue(submit.isEnabled)
        submit.tap()
        let acknowledgement = app.alerts["Report saved"]
        XCTAssertTrue(acknowledgement.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Saved on this device. Sending reports to the Bridgit team was turned off, so it has no report number."].exists)
        capture("local-bug-saved", app: app, markers: ["Report saved", "no report number"])
        acknowledgement.buttons["OK"].tap()
        let history = app.buttons["My bug reports"]
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        history.tap()
        XCTAssertTrue(app.staticTexts["My reports"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "3347 local parity fixture")).firstMatch.waitForExistence(timeout: 5))
        capture("local-bug-history", app: app, markers: ["My reports", "3347 local parity fixture"])
        app.buttons["Done"].tap()
        XCTAssertTrue(report.waitForExistence(timeout: 5))
    }

    func testScreenshotPickerVisibleFromBothReportForms() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        for (entry, name) in [("Report a bug", "bug"), ("Request a feature", "feature")] {
            let row = app.buttons[entry]
            XCTAssertTrue(row.waitForExistence(timeout: 8))
            row.tap()
            let add = app.buttons["Add screenshot"]
            XCTAssertTrue(add.waitForExistence(timeout: 5))
            XCTAssertTrue(add.isHittable)
            XCTAssertTrue(app.frame.contains(add.frame))
            capture(name + "-screenshot-action", app: app, markers: [entry, "Add screenshot", "Description"])
            add.tap()
            XCTAssertTrue(app.descendants(matching: .any)["Photos"].firstMatch.waitForExistence(timeout: 8))
            capture(name + "-screenshot-picker", app: app, markers: ["Photos"])
            #if targetEnvironment(simulator)
            selectSimulatorPhoto(app)
            let remove = app.buttons["Remove screenshot 1"]
            for _ in 0..<5 where !remove.isHittable { app.swipeUp() }
            XCTAssertTrue(remove.isHittable)
            capture(name + "-screenshot-attached", app: app, markers: ["Add another", "Remove screenshot 1"])
            remove.tap()
            #else
            app.navigationBars["Photos"].buttons["Cancel"].tap()
            #endif
            XCTAssertTrue(add.waitForExistence(timeout: 5))
            app.buttons["Cancel"].tap()
        }
    }

    #if targetEnvironment(simulator)
    private func selectSimulatorPhoto(_ app: XCUIApplication) {
        let notice = app.staticTexts["Private Access to Photos"].firstMatch
        if notice.exists {
            let close = app.buttons["Close"].firstMatch
            XCTAssertTrue(close.isHittable); close.tap()
            XCTAssertTrue(notice.waitForNonExistence(timeout: 5))
        }
        let image = app.images.matching(NSPredicate(format: "label BEGINSWITH %@", "Photo,")).firstMatch
        for _ in 0..<8 where !image.isHittable { app.swipeUp() }
        XCTAssertTrue(image.waitForExistence(timeout: 5))
        let tile = image.frame
        print("PHOTOS_TILE frame=\(tile) window=\(app.frame) hittable=\(image.isHittable)")
        capture("photos-visible-tile", app: app, markers: [])
        XCTAssertTrue(app.frame.contains(tile), "Photo tile must be wholly on screen before touch")
        XCTAssertGreaterThan(tile.width, 0); XCTAssertGreaterThan(tile.height, 0)
        XCTAssertLessThan(tile.width, app.frame.width / 2, "Target must be a tile rather than the entire grid")
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: tile.midX - app.frame.minX, dy: tile.midY - app.frame.minY)).tap()
        XCTAssertTrue(app.buttons["Add another"].waitForExistence(timeout: 8))
    }

    func testPickedScreenshotSurvivesPrivateReportRelaunch() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        settings.tap()
        let sending = app.switches["Send bug reports to the Bridgit team"]
        XCTAssertTrue(sending.waitForExistence(timeout: 5))
        if sending.value as? String == "1" { sending.tap() }
        XCTAssertEqual(sending.value as? String, "0")
        app.buttons["Done"].tap()
        app.buttons["Request a feature"].tap()
        let add = app.buttons["Add screenshot"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        selectSimulatorPhoto(app)
        let lastScreen = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Include last screen")).firstMatch
        for _ in 0..<5 where !lastScreen.isHittable { app.swipeUp() }
        if lastScreen.value as? String == "1" { lastScreen.tap() }
        XCTAssertEqual(lastScreen.value as? String, "0")
        let editor = app.textViews.firstMatch
        for _ in 0..<5 where !editor.isHittable { app.swipeDown() }
        let description = "Saved screenshot " + UUID().uuidString.prefix(8)
        editor.tap()
        editor.typeText(description)
        app.buttons["Submit"].tap()
        let saved = app.alerts["Report saved"]
        XCTAssertTrue(saved.waitForExistence(timeout: 8))
        saved.buttons["OK"].tap()
        app.terminate()
        app.launch()
        let history = app.buttons["My feature requests"]
        XCTAssertTrue(history.waitForExistence(timeout: 8))
        history.tap()
        let row = app.staticTexts[description].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let thumbnail = app.buttons["Screenshot 1 of 1"]
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 5))
        XCTAssertEqual(thumbnail.frame.width, 96, accuracy: 1)
        XCTAssertEqual(thumbnail.frame.height, 96, accuracy: 1)
        capture("private-picked-screenshot-detail", app: app, markers: ["Screenshots", "Screenshot 1 of 1", description])
        thumbnail.tap()
        XCTAssertTrue(app.images["Screenshot 1"].waitForExistence(timeout: 5))
        capture("private-picked-screenshot-preview", app: app, markers: ["Screenshot 1", "Close"])
        let close = app.buttons["Close"]
        XCTAssertGreaterThanOrEqual(close.frame.width, 44)
        XCTAssertGreaterThanOrEqual(close.frame.height, 44)
        close.tap()
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 5))
    }
    #endif

    func testIPadCatalogShowsSelectedTransportFactsWithoutAssumingRemote() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Requires an iPad")
        continueAfterFailure = false
        let app = XCUIApplication()
        for (args, title, details, suffix) in [
            (["-fixtureStandalone", "-device", "local", "-tab", "more"], "Direct Tailscale access", "Direct Tailscale is off", "local"),
            (["-fixtureAppraiser", "-device", "cloud:bgi_sample_garage", "-tab", "more"], "Only over Tailscale", "Connection details have not been reported by this linked device.", "linked")
        ] {
            app.launchArguments = args; app.launch(); selectDestination("More", in: app)
            let search = app.textFields["Search More and settings"]
            XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("capabilities\n")
            let result = app.buttons["more-search-result-What can Bridgit do?"]
            XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
            let share = app.buttons["Share the API details for " + title].firstMatch
            func visible() -> Bool { share.exists && share.isHittable && app.windows.firstMatch.frame.contains(share.frame) }
            for _ in 0..<100 where !visible() {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.74))
                    .press(forDuration: 0.08, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.52)))
            }
            XCTAssertTrue(visible(), title)
            capture("ipad-catalog-direct-" + suffix, app: app, markers: [title, details, "Share the API details for " + title])
            app.terminate()
        }
    }

    func testLocalCatalogAgentAndFeedbackDetails() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "more", "-device", "local"]
        app.launch(); selectDestination("More", in: app)
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("capabilities\n")
        let result = app.buttons["more-search-result-What can Bridgit do?"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        XCTAssertTrue(app.staticTexts["For This device"].waitForExistence(timeout: 8))
        func viewport() -> CGRect {
            var safe = app.scrollViews.firstMatch.frame.intersection(app.windows.firstMatch.frame)
            let top = max(safe.minY, app.navigationBars["What can Bridgit do?"].frame.maxY)
            safe.size.height = max(0, safe.maxY - top); safe.origin.y = top
            return safe
        }
        func valid(_ rect: CGRect) -> Bool {
            rect.origin.x.isFinite && rect.origin.y.isFinite && rect.width.isFinite && rect.height.isFinite && rect.width > 0 && rect.height > 0
        }
        func move(down: Bool) {
            let from: CGFloat = down ? 0.74 : 0.52, to: CGFloat = down ? 0.52 : 0.74
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: from))
                .press(forDuration: 0.08, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: to)))
        }
        func captureFull(_ identifier: String) {
            let element = app.staticTexts[identifier].firstMatch
            for _ in 0..<40 {
                guard element.exists else { move(down: true); continue }
                let r = element.frame, safe = viewport()
                guard valid(r), valid(safe) else { move(down: true); continue }
                if r.height <= safe.height && safe.contains(r) {
                    capture("ability-local-" + identifier, app: app, markers: [identifier]); return
                }
                if r.height > safe.height && r.minY >= safe.minY && r.minY < safe.maxY { break }
                move(down: r.minY >= safe.maxY || (r.height <= safe.height && r.maxY > safe.maxY))
            }
            guard element.exists, valid(element.frame), valid(viewport()), element.frame.height > viewport().height else {
                XCTFail("Could not fully reveal " + identifier); return
            }
            let height = element.frame.height
            var covered: CGFloat = 0
            for part in 0..<50 {
                let r = element.frame, safe = viewport()
                guard valid(r), valid(safe), r.minX >= safe.minX, r.maxX <= safe.maxX else {
                    XCTFail("Invalid or horizontally clipped frame " + identifier); return
                }
                let start = max(0, safe.minY - r.minY), end = min(height, safe.maxY - r.minY)
                guard start <= covered + 1, end > covered else { XCTFail("Coverage gap " + identifier); return }
                capture("ability-local-" + identifier + "-part-" + String(part), app: app, markers: [identifier])
                covered = end
                if covered >= height - 1 { return }
                move(down: true)
            }
            XCTFail("Incomplete coverage " + identifier)
        }
        for (anchor, count) in [("add-an-agent-in-a-minute", 2), ("one-key-per-agent", 2), ("report-a-bug-or-ask-for-a-feature", 4), ("use-bridgit-over-mcp", 2)] {
            captureFull("ability-card-title-" + anchor)
            captureFull("ability-card-body-" + anchor)
            for index in 0..<count { captureFull("ability-card-option-" + anchor + "-" + String(index)) }
            captureFull("ability-card-example-" + anchor)
        }
    }

    func testLocalCatalogMicrophoneAndActivityDetails() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-tab", "more", "-device", "local"]
        app.launch(); selectDestination("More", in: app)
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("capabilities\n")
        let result = app.buttons["more-search-result-What can Bridgit do?"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        XCTAssertTrue(app.staticTexts["For This device"].waitForExistence(timeout: 8))
        func viewport() -> CGRect {
            var safe = app.scrollViews.firstMatch.frame.intersection(app.windows.firstMatch.frame)
            let top = max(safe.minY, app.navigationBars["What can Bridgit do?"].frame.maxY)
            safe.size.height = max(0, safe.maxY - top); safe.origin.y = top
            return safe
        }
        func valid(_ rect: CGRect) -> Bool {
            rect.origin.x.isFinite && rect.origin.y.isFinite && rect.width.isFinite && rect.height.isFinite && rect.width > 0 && rect.height > 0
        }
        func move(down: Bool) {
            let from: CGFloat = down ? 0.74 : 0.52, to: CGFloat = down ? 0.52 : 0.74
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: from))
                .press(forDuration: 0.08, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: to)))
        }
        func captureFull(_ identifier: String) {
            let element = app.staticTexts[identifier].firstMatch
            var lastProgress = ""
            var unchangedSamples = 0
            for step in 0..<120 {
                if step > 0 && step % 20 == 0 {
                    let safe = viewport()
                    let headings = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ability-card-title-"))
                    let visible = headings.allElementsBoundByIndex.filter { valid($0.frame) && safe.intersects($0.frame) }
                        .map { $0.identifier + ":" + String(Int($0.frame.minY)) }.joined(separator: "|")
                    capture("ability-local-progress-" + identifier + "-" + String(step), app: app, markers: [])
                    unchangedSamples = !visible.isEmpty && visible == lastProgress ? unchangedSamples + 1 : 0
                    lastProgress = visible
                    if unchangedSamples >= 2 { XCTFail("No catalog movement for " + identifier); return }
                }
                guard element.exists else { move(down: true); continue }
                let r = element.frame, safe = viewport()
                guard valid(r), valid(safe) else { move(down: true); continue }
                if r.height <= safe.height && safe.contains(r) {
                    capture("ability-local-" + identifier, app: app, markers: [identifier]); return
                }
                if r.height > safe.height && r.minY >= safe.minY && r.minY < safe.maxY { break }
                move(down: r.minY >= safe.maxY || (r.height <= safe.height && r.maxY > safe.maxY))
            }
            guard element.exists, valid(element.frame), valid(viewport()), element.frame.height > viewport().height else {
                capture("ability-local-unrevealed-" + identifier, app: app, markers: [])
                XCTFail("Could not fully reveal " + identifier + "; frame: " + String(describing: element.frame) + "; viewport: " + String(describing: viewport())); return
            }
            let height = element.frame.height
            var covered: CGFloat = 0
            for part in 0..<50 {
                let r = element.frame, safe = viewport()
                guard valid(r), valid(safe), r.minX >= safe.minX, r.maxX <= safe.maxX else {
                    XCTFail("Invalid or horizontally clipped frame " + identifier); return
                }
                let start = max(0, safe.minY - r.minY), end = min(height, safe.maxY - r.minY)
                guard start <= covered + 1, end > covered else { XCTFail("Coverage gap " + identifier); return }
                capture("ability-local-" + identifier + "-part-" + String(part), app: app, markers: [identifier])
                covered = end
                if covered >= height - 1 { return }
                move(down: true)
            }
            XCTFail("Incomplete coverage " + identifier)
        }
        for (anchor, count) in [("record-a-short-clip", 3), ("everything-is-logged", 3), ("agent-notes", 2), ("the-api-explains-itself", 2)] {
            captureFull("ability-card-title-" + anchor)
            captureFull("ability-card-body-" + anchor)
            for index in 0..<count { captureFull("ability-card-option-" + anchor + "-" + String(index)) }
            captureFull("ability-card-example-" + anchor)
        }
    }

    func testAbilityCatalogLinkedCameraShareCopiesForSelectedDevice() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureAppraiser", "-device", "cloud:bgi_sample_garage", "-tab", "more"]
        app.launch(); selectDestination("More", in: app)
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("capabilities\n")
        let result = app.buttons["more-search-result-What can Bridgit do?"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        XCTAssertTrue(app.staticTexts["For Garage tablet"].waitForExistence(timeout: 8))
        let share = app.buttons["Share the API details for Take a photo"]
        for _ in 0..<8 where !share.isHittable { app.swipeUp() }
        XCTAssertTrue(share.isHittable)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(share.frame), "Share control must be fully on screen")
        capture("ability-linked-camera-scrolled", app: app, markers: ["Take a photo", "Share the API details for Take a photo"])
        share.tap()
        let sheet = app.otherElements["ActivityListView"].firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 8))
        let copy = app.cells.matching(NSPredicate(format: "label == %@", "Copy")).firstMatch
        XCTAssertTrue(copy.waitForExistence(timeout: 15))
        capture("ability-linked-camera-share", app: app, markers: ["ActivityListView", "Copy"])
        XCTAssertTrue(copy.isHittable)
        copy.tap()
        expectation(for: NSPredicate(format: "exists == NO"), evaluatedWith: sheet)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(share.waitForExistence(timeout: 5))
        XCTAssertTrue(share.isHittable)
        capture("ability-linked-camera-share-return", app: app, markers: ["Take a photo", "Share the API details for Take a photo"])
    }

    func testAbilityCatalogScrolledCameraShareOpensAndCancels() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-fixtureStandalone", "-device", "local", "-tab", "more"]
        app.launch(); selectDestination("More", in: app)
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("capabilities\n")
        let result = app.buttons["more-search-result-What can Bridgit do?"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        let share = app.buttons["Share the API details for Take a photo"]
        for _ in 0..<8 where !share.isHittable { app.swipeUp() }
        XCTAssertTrue(share.isHittable)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(share.frame), "Share control must be fully on screen")
        capture("ability-camera-scrolled", app: app, markers: ["Take a photo", "Share the API details for Take a photo"])
        share.tap()
        let sheet = app.otherElements["ActivityListView"].firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 8))
        capture("ability-camera-share", app: app, markers: ["ActivityListView"])
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        expectation(for: NSPredicate(format: "exists == NO"), evaluatedWith: sheet)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(share.waitForExistence(timeout: 5))
        capture("ability-camera-share-return", app: app, markers: ["Take a photo", "Share the API details for Take a photo"])
    }

    func testAbilityCatalogLargestTextInDarkAppearance() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-device", "local", "-tab", "more", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch(); selectDestination("More", in: app)
        let search = app.textFields["Search More and settings"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap(); search.typeText("theme\n")
        let theme = app.buttons["more-search-result-Settings · Theme"]
        XCTAssertTrue(theme.waitForExistence(timeout: 8)); theme.tap()
        let picker = app.buttons["settings-theme"]
        XCTAssertTrue(picker.waitForExistence(timeout: 8)); picker.tap()
        XCTAssertTrue(app.buttons["Dark"].waitForExistence(timeout: 5)); app.buttons["Dark"].tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        app.buttons["Clear search"].tap(); search.tap(); search.typeText("capabilities\n")
        let result = app.buttons["more-search-result-What can Bridgit do?"]
        XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
        XCTAssertTrue(app.staticTexts["For This device"].waitForExistence(timeout: 8))
        XCTAssertTrue(try rendersDark(app))
        XCTAssertTrue(app.navigationBars.buttons["BackButton"].firstMatch.isHittable)
        capture("ability-catalog-dark-largest", app: app, markers: ["For This device", "Supported control agent requests"])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
    }

    func testAbilityCatalogUsesLocalAndSelectedLinkedScope() {
        continueAfterFailure = false
        let app = XCUIApplication()
        for (arguments, heading, suffix) in [
            (["-fixtureStandalone", "-device", "local", "-tab", "more"], "For This device", "local"),
            (["-fixtureAppraiser", "-device", "cloud:bgi_sample_garage", "-tab", "more"], "For Garage tablet", "linked")
        ] {
            app.launchArguments = arguments
            app.launch()
            selectDestination("More", in: app)
            let search = app.textFields["Search More and settings"]
            XCTAssertTrue(search.waitForExistence(timeout: 10))
            search.tap(); search.typeText("capabilities\n")
            let result = app.buttons["more-search-result-What can Bridgit do?"]
            XCTAssertTrue(result.waitForExistence(timeout: 8)); result.tap()
            XCTAssertTrue(app.staticTexts[heading].waitForExistence(timeout: 8))
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Android:")).firstMatch.exists)
            capture("ability-catalog-" + suffix, app: app, markers: [heading, "Supported control agent requests"])
            app.navigationBars.buttons["BackButton"].firstMatch.tap()
            XCTAssertTrue(search.waitForExistence(timeout: 5))
            app.terminate()
        }
    }

    func testMoreSearchPlaceholderAndActivityReturn() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureExposeSearchBounds", "-tab", "more"]
        app.launch()
        let field = app.textFields["Search More and settings"].firstMatch
        let bounds = app.descendants(matching: .any)["fixture-more-search-bounds"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15)); XCTAssertTrue(bounds.waitForExistence(timeout: 10))
        let window = app.windows.firstMatch
        let bar = app.tabBars.firstMatch
        let nav = app.navigationBars.firstMatch
        func fullyVisible() -> Bool {
            let f = bounds.frame, w = window.frame
            let top = nav.exists ? max(w.minY, nav.frame.maxY) : w.minY
            let bottom = bar.exists ? min(w.maxY, bar.frame.minY) : w.maxY
            return f.width > 0 && f.height >= 44 && f.minX >= w.minX && f.maxX <= w.maxX && f.minY >= top && f.maxY <= bottom
        }
        for _ in 0..<16 where !fullyVisible() {
            let f = bounds.frame, w = window.frame
            print("MORE_SEARCH_BOUNDS field=\(f) window=\(w) nav=\(nav.exists ? nav.frame : .zero) tab=\(bar.exists ? bar.frame : .zero) hittable=\(field.isHittable)")
            let scrollDown = f.minY < (nav.exists ? nav.frame.maxY : w.minY)
            let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: scrollDown ? 0.45 : 0.55))
            let end = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: scrollDown ? 0.55 : 0.45))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        if !fullyVisible() { capture("more-search-bounds-failure", app: app, markers: ["Search"]) }
        XCTAssertTrue(fullyVisible(), "The full More search capsule must be visible, not merely hittable")
        capture("more-search-placeholder", app: app, markers: ["Search", "Device and setup"])
        // Interaction is checked separately on the production-equivalent AX tree.

    }

    func testMoreSearchProductionAXInteraction() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let field = app.textFields["Search More and settings"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        for _ in 0..<8 where !field.isHittable { app.swipeUp() }
        XCTAssertTrue(field.isHittable)
        capture("more-search-production-placeholder", app: app, markers: ["Search", "Device and setup"])
        field.tap(); field.typeText("activity\n")
        let result = app.buttons["more-search-result-Activity"].firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 8))
        result.tap()
        XCTAssertTrue(app.staticTexts["Nothing yet."].waitForExistence(timeout: 10))
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertEqual(field.value as? String, "activity")
        XCTAssertTrue(result.exists)
        app.buttons["Clear search"].tap()
        XCTAssertFalse(result.exists)
        capture("more-search-production-cleared", app: app, markers: ["Search", "Device and setup"])
    }

    func testMoreFeedbackActionsRemainSeparateAtLargeText() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let window = app.windows.firstMatch
        let tab = app.tabBars.firstMatch
        func reveal(_ element: XCUIElement) {
            for _ in 0..<15 {
                let frame = element.frame
                if frame.width > 0 && frame.minY >= window.frame.minY + 90 && frame.maxY <= tab.frame.minY {
                    print("FEEDBACK_REVEAL frame=\(frame) hittable=\(element.isHittable)")
                    capture("feedback-reveal", app: app, markers: [])
                    return
                }
                let down = frame.minY < window.frame.minY + 90
                window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.45 : 0.55))
                    .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.55 : 0.45)))
            }
            XCTFail("Action must be fully above tab bar: \(element)")
        }
        for (title, historyName, prompt, kind) in [("Report a bug", "My bug reports", "What happened?", "Bugs"),
                                                   ("Request a feature", "My feature requests", "What would you like to see?", "Features")] {
            let compose = app.buttons[title].firstMatch
            let history = app.buttons[historyName].firstMatch
            XCTAssertTrue(compose.waitForExistence(timeout: 15)); XCTAssertTrue(history.exists)
            XCTAssertFalse(compose.frame.intersects(history.frame))
            // Allow subpixel floating-point rounding while requiring the designed eight-point gap.
            if compose.frame.maxY <= history.frame.minY { XCTAssertGreaterThanOrEqual(history.frame.minY - compose.frame.maxY, 7.999) }
            XCTAssertGreaterThanOrEqual(compose.frame.height, 44); XCTAssertGreaterThanOrEqual(history.frame.height, 44)
            for edge in [false, true] {
                reveal(compose)
                capture("feedback-compose-before-tap", app: app, markers: [title])
                if edge { compose.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() } else { compose.tap() }
                XCTAssertTrue(app.staticTexts[prompt].waitForExistence(timeout: 8))
                XCTAssertFalse(history.isHittable, "Covered history action must not receive input")
                app.buttons["Cancel"].tap()
                XCTAssertTrue(app.staticTexts[prompt].waitForNonExistence(timeout: 10))
                reveal(history)
                XCTAssertFalse(app.staticTexts[prompt].exists, "Composer must remain dismissed after reveal")
                print("FEEDBACK_HISTORY_TARGET frame=\(history.frame) tab=\(tab.frame)")
                capture("feedback-history-before-tap", app: app, markers: [])
                if edge { history.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap() } else { history.tap() }
                let opened = app.staticTexts["My reports"].firstMatch.waitForExistence(timeout: 10)
                capture("feedback-history-after-tap", app: app, markers: [])
                XCTAssertTrue(opened, "History action must open My reports")
                XCTAssertTrue(app.buttons[kind].isSelected, "History must select the requested kind")
                app.buttons["Done"].tap()
                XCTAssertTrue(app.staticTexts["My reports"].firstMatch.waitForNonExistence(timeout: 10))
            }
        }
    }

    func testFeedbackKindAndScreenshotAtLargeText() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let report = app.buttons["Report a bug"].firstMatch
        XCTAssertTrue(report.waitForExistence(timeout: 15))
        for _ in 0..<10 where !report.isHittable { app.swipeUp() }
        XCTAssertTrue(report.isHittable); report.tap()
        let bug = app.buttons["Bug"].firstMatch, feature = app.buttons["Feature"].firstMatch
        XCTAssertTrue(bug.waitForExistence(timeout: 10)); XCTAssertTrue(feature.exists)
        for _ in 0..<8 where !feature.isHittable { app.swipeUp() }
        XCTAssertTrue(feature.isHittable)
        capture("feedback-kind-bug", app: app, markers: ["Bug", "Feature"])
        feature.tap(); XCTAssertTrue(feature.isSelected)
        XCTAssertTrue(app.staticTexts["What would you like to see?"].waitForExistence(timeout: 8))
        capture("feedback-kind-feature", app: app, markers: ["Bug", "Feature"])
        bug.tap(); XCTAssertTrue(bug.isSelected)
        let window = app.windows.firstMatch
        let submit = app.buttons["Submit"].firstMatch
        let screenToggle = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Include last screen")).firstMatch
        XCTAssertTrue(screenToggle.waitForExistence(timeout: 10))
        for _ in 0..<15 {
            let f = screenToggle.frame
            if screenToggle.isHittable && f.minY >= submit.frame.maxY && f.maxY <= window.frame.maxY { break }
            let down = f.minY < submit.frame.maxY
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.45 : 0.55))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.55 : 0.45)))
        }
        XCTAssertTrue(screenToggle.isHittable)
        XCTAssertGreaterThanOrEqual(screenToggle.frame.minY, submit.frame.maxY)
        XCTAssertLessThanOrEqual(screenToggle.frame.maxY, window.frame.maxY)
        capture("feedback-last-screen-inline", app: app, markers: ["Include last screen"])
        XCTAssertEqual(screenToggle.value as? String, "1"); screenToggle.tap()
        XCTAssertEqual(screenToggle.value as? String, "0"); screenToggle.tap()
        XCTAssertEqual(screenToggle.value as? String, "1")
        let add = app.buttons["Add screenshot"].firstMatch
        for _ in 0..<15 {
            let f = add.frame
            if add.isHittable && f.minY >= submit.frame.maxY && f.maxY <= window.frame.maxY { break }
            let down = f.minY < submit.frame.maxY
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.45 : 0.55))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.55 : 0.45)))
        }
        XCTAssertTrue(add.isHittable)
        XCTAssertGreaterThanOrEqual(add.frame.minY, submit.frame.maxY)
        XCTAssertLessThanOrEqual(add.frame.maxY, window.frame.maxY)
        capture("feedback-screenshot-reachable", app: app, markers: ["Add screenshot"])
        add.tap()
        XCTAssertTrue(app.descendants(matching: .any)["Photos"].firstMatch.waitForExistence(timeout: 10))
        app.navigationBars["Photos"].buttons["Cancel"].tap()
        XCTAssertTrue(add.waitForExistence(timeout: 10))
        for _ in 0..<10 where !add.isHittable { app.swipeUp() }
        XCTAssertTrue(add.isHittable); add.tap()
        XCTAssertTrue(app.descendants(matching: .any)["Photos"].firstMatch.waitForExistence(timeout: 10))
        selectSimulatorPhoto(app)
        let remove = app.buttons["Remove screenshot 1"].firstMatch
        for _ in 0..<10 where !remove.isHittable { app.swipeUp() }
        XCTAssertTrue(remove.isHittable)
        capture("feedback-screenshot-attached", app: app, markers: ["Remove screenshot 1"])
        // The selected image survives a type change in the same draft.
        for _ in 0..<10 where !feature.isHittable { app.swipeDown() }
        XCTAssertTrue(feature.isHittable); feature.tap(); XCTAssertTrue(feature.isSelected)
        for _ in 0..<10 where !remove.isHittable { app.swipeUp() }
        XCTAssertTrue(remove.isHittable); remove.tap()
        XCTAssertFalse(remove.exists)
        XCTAssertTrue(add.exists)
        app.buttons["Cancel"].tap()
    }

    func testAccessibleFeedbackHeaderAndLocalSave() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureExposeStampBounds", "-tab", "more"]
        app.launch()
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        for _ in 0..<10 where !settings.isHittable { app.swipeUp() }
        XCTAssertTrue(settings.isHittable); settings.tap()
        let sending = app.switches["Send bug reports to the Bridgit team"].firstMatch
        XCTAssertTrue(sending.waitForExistence(timeout: 10))
        if sending.value as? String == "1" { sending.tap() }
        XCTAssertEqual(sending.value as? String, "0")
        capture("feedback-settings-before-done", app: app, markers: ["Settings", "Done"])
        app.buttons["Done"].tap()
        XCTAssertTrue(sending.waitForNonExistence(timeout: 10), "Settings must dismiss before opening a report")
        let report = app.buttons["Report a bug"].firstMatch
        let window = app.windows.firstMatch, tab = app.tabBars.firstMatch
        let rootStamp = app.descendants(matching: .any)["fixture-build-stamp"].firstMatch
        for _ in 0..<15 {
            let frame = report.frame
            if report.isHittable && frame.minY >= rootStamp.frame.maxY && frame.maxY <= tab.frame.minY { break }
            let down = frame.minY < rootStamp.frame.maxY
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.45 : 0.55))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.55 : 0.45)))
        }
        XCTAssertTrue(report.isHittable)
        XCTAssertGreaterThanOrEqual(report.frame.minY, rootStamp.frame.maxY)
        XCTAssertLessThanOrEqual(report.frame.maxY, tab.frame.minY)
        print("HEADER_REPORT_TARGET frame=\(report.frame) stamp=\(rootStamp.frame) tab=\(tab.frame)")
        capture("feedback-after-settings-before-report", app: app, markers: ["Report a bug"])
        report.tap()
        let cancel = app.buttons["Cancel"].firstMatch, submit = app.buttons["Submit"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); XCTAssertTrue(submit.exists)
        let stamp = app.descendants(matching: .any)["fixture-build-stamp"].firstMatch
        XCTAssertTrue(stamp.exists); XCTAssertFalse(cancel.frame.intersects(submit.frame))
        XCTAssertFalse(stamp.frame.intersects(cancel.frame)); XCTAssertFalse(stamp.frame.intersects(submit.frame))
        XCTAssertGreaterThanOrEqual(cancel.frame.height, 44); XCTAssertGreaterThanOrEqual(submit.frame.height, 44)
        XCTAssertTrue(app.frame.contains(cancel.frame)); XCTAssertTrue(app.frame.contains(submit.frame))
        capture("feedback-compact-header", app: app, markers: ["Cancel", "Submit"])
        let editor = app.textViews.firstMatch
        for _ in 0..<12 where !editor.isHittable { app.swipeUp() }
        XCTAssertTrue(editor.isHittable); editor.tap(); editor.typeText("Local header verification")
        XCTAssertTrue(submit.isEnabled); submit.tap()
        XCTAssertTrue(app.staticTexts["Report saved"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Sending reports to the Bridgit team was turned off")).firstMatch.exists)
        app.buttons["OK"].tap()
        XCTAssertTrue(report.waitForExistence(timeout: 10))
        report.tap(); XCTAssertTrue(cancel.waitForExistence(timeout: 10)); cancel.tap()
    }

    func testMoreStampRemainsClearWhileScrollingAndPresentingFeedback() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureExposeStampBounds", "-tab", "more"]
        app.launch()
        let stamps = app.descendants(matching: .any).matching(identifier: "fixture-build-stamp")
        XCTAssertTrue(stamps.firstMatch.waitForExistence(timeout: 15))
        XCTAssertEqual(stamps.count, 1)
        let heading = app.staticTexts["More"].firstMatch
        XCTAssertTrue(heading.exists)
        XCTAssertFalse(stamps.firstMatch.frame.intersects(heading.frame))
        capture("more-header-initial", app: app, markers: ["More"])
        let before = stamps.firstMatch.frame
        app.swipeUp()
        XCTAssertEqual(stamps.count, 1)
        XCTAssertEqual(stamps.firstMatch.frame, before)
        capture("more-stamp-scrolled", app: app, markers: [])
        let report = app.buttons["Report a bug"].firstMatch
        let window = app.windows.firstMatch
        let tab = app.tabBars.firstMatch
        for _ in 0..<15 {
            let frame = report.frame
            if report.isHittable && frame.minY >= stamps.firstMatch.frame.maxY && frame.maxY <= tab.frame.minY { break }
            let down = frame.minY < stamps.firstMatch.frame.maxY
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.45 : 0.55))
                .press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.55 : 0.45)))
        }
        XCTAssertTrue(report.isHittable)
        XCTAssertGreaterThanOrEqual(report.frame.minY, stamps.firstMatch.frame.maxY)
        XCTAssertLessThanOrEqual(report.frame.maxY, tab.frame.minY)
        XCTAssertFalse(stamps.firstMatch.frame.intersects(report.frame))
        capture("more-stamp-visible-action", app: app, markers: [])
        report.tap()
        XCTAssertTrue(app.staticTexts["What happened?"].waitForExistence(timeout: 10))
        XCTAssertEqual(stamps.count, 1, "Only the presented surface's stamp should be exposed")
        capture("more-stamp-feedback", app: app, markers: ["What happened?"])
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["What happened?"].waitForNonExistence(timeout: 10))
        XCTAssertEqual(stamps.count, 1)
    }

    func testMoreAccessibilityRowsStayWithinWindow() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let window = app.windows.firstMatch
        for title in ["Check for updates", "Linked devices", "Settings", "Feature enablement", "Hardware access", "Software access"] {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 15))
            for _ in 0..<8 where !row.isHittable { app.swipeUp() }
            XCTAssertTrue(row.isHittable, title)
            XCTAssertGreaterThanOrEqual(row.frame.minX, window.frame.minX, title)
            XCTAssertLessThanOrEqual(row.frame.maxX, window.frame.maxX, title)
            capture("more-row-" + title.replacingOccurrences(of: " ", with: "-"), app: app, markers: [title])
        }
    }

    func testSearchAndReturnToFeedbackAndLinkedDevices() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone"]
        app.launch()
        selectDestination("More", in: app)
        let field = app.textFields["Search More and settings"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("bug\n")
        let setting = app.buttons["more-search-result-Settings · Send bug reports to the Bridgit team"]
        XCTAssertTrue(setting.waitForExistence(timeout: 5))
        capture("feedback-results", app: app, markers: ["Report a bug", "Request a feature", "Settings · Send bug reports to the Bridgit team"])
        app.buttons["more-search-result-Report a bug"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["What happened?"].waitForExistence(timeout: 5))
        capture("bug-form", app: app, markers: ["What happened?", "Attempt Auto-fix", "Cancel"])
        let reportPreview = app.buttons["Preview"]
        for _ in 0..<5 where !reportPreview.isHittable { app.swipeUp() }
        XCTAssertTrue(reportPreview.isHittable)
        capture("bug-form-attachments", app: app, markers: ["Add screenshot", "Include recent log", "Preview"])
        reportPreview.tap()
        XCTAssertTrue(app.staticTexts["Recent log"].waitForExistence(timeout: 5))
        capture("regular-feedback-log", app: app, markers: ["Recent log", "Done"])
        app.buttons["Done"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertEqual(field.value as? String, "bug")
        setting.tap()
        XCTAssertTrue(app.switches["Send bug reports to the Bridgit team"].waitForExistence(timeout: 5))
        capture("feedback-setting", app: app, markers: ["Send bug reports to the Bridgit team", "keep new reports on this device"])
        app.buttons["Done"].tap()
        XCTAssertEqual(field.value as? String, "bug")
        XCTAssertTrue(setting.exists)
        capture("feedback-return", app: app, markers: ["Settings · Send bug reports to the Bridgit team", "Report a bug"])
        app.buttons["Clear search"].tap()
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        field.tap()
        field.typeText("zznonexistent\n")
        XCTAssertTrue(app.staticTexts["No results"].exists)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        capture("no-results", app: app, markers: ["No results", "Request a feature", "My bug reports"])
        app.buttons["Clear search"].tap()
        XCTAssertFalse(app.staticTexts["No results"].exists)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        field.tap()
        field.typeText("linked devices\n")
        let linked = app.buttons["more-search-result-Linked devices"]
        XCTAssertTrue(linked.waitForExistence(timeout: 5))
        capture("linked-results", app: app, markers: ["more-search-result-Linked devices", "My bug reports"])
        linked.tap()
        XCTAssertTrue(app.buttons["Pair a device"].waitForExistence(timeout: 5))
        let emptyState = app.staticTexts.matching(NSPredicate(format: "label IN %@", ["Nothing is linked to this phone yet.", "Nothing is linked to this tablet yet."])).firstMatch
        XCTAssertTrue(emptyState.exists)
        capture("linked-map", app: app, markers: [emptyState.label, "Pair a device"])
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertEqual(field.value as? String, "linked devices")
        XCTAssertTrue(linked.exists)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        capture("linked-return", app: app, markers: ["more-search-result-Linked devices", "Request a feature"])
        app.buttons["Clear search"].tap()
        XCTAssertFalse(linked.exists)
        XCTAssertFalse(app.staticTexts["No results"].exists)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        capture("search-cleared", app: app, markers: ["Settings", "Feature enablement", "Device and setup"])
        app.buttons["Request a feature"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["What would you like to see?"].waitForExistence(timeout: 5))
        capture("feature-form", app: app, markers: ["What would you like to see?", "Description", "Cancel"])
        for _ in 0..<5 where !reportPreview.isHittable { app.swipeUp() }
        XCTAssertTrue(reportPreview.isHittable)
        capture("feature-form-attachments", app: app, markers: ["Include recent log", "Preview"])
        app.buttons["Cancel"].tap()
        XCTAssertTrue(field.waitForExistence(timeout: 5))
    }
    /// The wrapper seeds the existing synthetic stored report in its owned app container.
    func testSavedScreenshotViewerFillsCompactSurfaceAndReturns() throws {
        guard ProcessInfo.processInfo.environment["BRIDGIT_SAVED_REPORT_FIXTURE"] == "1" else {
            throw XCTSkip("Requires the isolated stored-report fixture; do not use personal reports")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        let large = ProcessInfo.processInfo.environment["BRIDGIT_UI_LARGE"] == "1"
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertLessThanOrEqual(window.frame.width, 600, "This regression requires a compact native window")
        let history = app.buttons["My feature requests"]
        XCTAssertTrue(history.waitForExistence(timeout: 10)); history.tap()
        let report = app.staticTexts["PRIVATE picked screenshot fixture 3347"].firstMatch
        XCTAssertTrue(report.waitForExistence(timeout: 8)); report.tap()
        let title = app.staticTexts["Feature request · Saved on this device"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 8))
        let initialDone = app.buttons["feedback.savedReport.done"]
        XCTAssertTrue(initialDone.waitForExistence(timeout: 5))
        XCTAssertLessThan(initialDone.frame.width, window.frame.width * 0.6, "The action must expose its own hit target, not the header spacer")
        XCTAssertTrue(initialDone.isHittable)
        let thumbnail = app.buttons["Screenshot 1 of 1"]
        for _ in 0..<8 where !thumbnail.isHittable { app.swipeUp() }
        XCTAssertTrue(thumbnail.isHittable); thumbnail.tap()
        let image = app.images["Screenshot 1"]
        XCTAssertTrue(image.waitForExistence(timeout: 5))
        let close = app.buttons["Close"]
        XCTAssertTrue(close.isHittable)
        XCTAssertLessThan(close.frame.minY, window.frame.minY + window.frame.height * 0.25, "Close must stay at the top, not at the intrinsic image's center")
        XCTAssertGreaterThan(image.frame.width, window.frame.width * 0.5)
        XCTAssertLessThanOrEqual(image.frame.maxY, window.frame.maxY + 1)
        XCTAssertFalse(title.isHittable, "The presenting title must not peek above the native viewer")
        XCTAssertFalse(initialDone.isHittable, "Covered report Done must not be actionable through the viewer")
        capture("saved-image-viewer-top", app: app, markers: ["Screenshot 1", "Close"])
        try assertCoveredTitleDoesNotPaintAboveImageSheet(app, fullScreen: large)
        close.tap()
        XCTAssertTrue(thumbnail.waitForExistence(timeout: 5))
        XCTAssertTrue(thumbnail.isHittable, "Closing the viewer must restore the screenshot's scroll position")
        XCTAssertTrue(image.waitForNonExistence(timeout: 5))
        let done = app.buttons["feedback.savedReport.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        capture("saved-image-return-detail", app: app, markers: ["Screenshot 1 of 1", "Done"])
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: done)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 8), .completed, "Done must become actionable after the viewer dismisses")
        done.tap()
        XCTAssertTrue(app.staticTexts["My reports"].waitForExistence(timeout: 5))
        XCTAssertTrue(report.waitForExistence(timeout: 5)); report.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        capture("saved-image-return-and-reopen", app: app, markers: ["Feature request · Saved on this device", "Done"])
    }

}

/// Each case owns a separate explicit DEBUG journey store. Existing fixture launches keep their routes.
final class FirstRunParityTests: XCTestCase {
    private func launch(id: UUID = UUID(), large: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-firstRunFixture", id.uuidString]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        return app
    }
    private func assertStep(_ number: Int, app: XCUIApplication) {
        XCTAssertTrue(app.staticTexts["firstRun.progress"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.staticTexts["firstRun.progress"].label, "Step \(number) of 4")
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testFourStepsHardwareReviewBackSkipAndDiagnosticPersist() {
        continueAfterFailure = false
        let app = launch()
        // Keep this launch's exact isolated suite across relaunch.
        let arguments = app.launchArguments
        assertStep(1, app: app)
        XCTAssertEqual(app.staticTexts["firstRun.heading"].label, "Bridgit enables super-human utilization of your device.")
        XCTAssertTrue(app.staticTexts["Things to try"].exists)
        capture("first-run-intro", app: app)
        app.buttons["firstRun.next"].tap(); assertStep(2, app: app)
        XCTAssertEqual(app.staticTexts["firstRun.heading"].label, "What Bridgit can do")
        capture("first-run-capabilities", app: app)
        let hardware = app.buttons["firstRun.hardware"]
        if !hardware.isHittable { app.scrollViews.firstMatch.swipeUp() }
        hardware.tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap(); assertStep(2, app: app)
        app.buttons["firstRun.next"].tap(); assertStep(3, app: app)
        capture("first-run-privacy", app: app)
        app.buttons["firstRun.back"].tap(); assertStep(2, app: app)
        app.buttons["firstRun.back"].tap(); assertStep(1, app: app)
        app.buttons["firstRun.skip"].tap(); assertStep(4, app: app)
        capture("first-run-warning", app: app)
        app.buttons["firstRun.diagnostic"].tap()
        XCTAssertFalse(app.staticTexts["firstRun.progress"].waitForExistence(timeout: 1))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch.waitForExistence(timeout: 10))
        capture("first-run-diagnostic", app: app)
        app.terminate(); app.launchArguments = arguments; app.launch()
        XCTAssertFalse(app.staticTexts["firstRun.progress"].waitForExistence(timeout: 2))
    }
    func testWarningAddControlAgentOpensRealSetupAtLargestText() {
        continueAfterFailure = false
        let app = launch(large: true)
        app.buttons["firstRun.skip"].tap(); assertStep(4, app: app)
        let add = app.buttons["firstRun.addAgent"]
        XCTAssertTrue(add.isHittable); capture("first-run-warning-largest", app: app)
        add.tap()
        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["Close"].exists)
        capture("first-run-add-agent-largest", app: app)
        app.buttons["Close"].tap()
        XCTAssertFalse(app.staticTexts["firstRun.progress"].exists)
    }
    func testDirectControlAgentShowsUnavailableTailnetAndCanReturnToRelay() {
        continueAfterFailure = false
        let app = launch()
        app.buttons["firstRun.skip"].tap()
        app.buttons["firstRun.addAgent"].tap()
        let name = app.textFields.firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 8))
        name.tap(); name.typeText("Direct fixture")
        app.buttons["Next"].tap()
        let direct = app.buttons["agent-connection-direct"]
        XCTAssertTrue(direct.waitForExistence(timeout: 8))
        direct.tap()
        capture("direct-connection-choice", app: app)
        app.buttons["Next"].tap()
        XCTAssertTrue(app.staticTexts["Check Tailscale connection"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["No Tailscale address found. Connect Tailscale, then check again."].exists)
        let sheet = app.descendants(matching: .any).matching(identifier: "owner-direct.setup").firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        let refresh = sheet.buttons["owner-direct.refresh"]
        XCTAssertTrue(refresh.waitForExistence(timeout: 5))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: refresh)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed)
        XCTAssertGreaterThanOrEqual(refresh.frame.height, 44)
        let allRefresh = app.buttons.matching(identifier: "owner-direct.refresh").allElementsBoundByIndex
        let refreshFacts = allRefresh.map { "\($0.frame) hittable=\($0.isHittable) enabled=\($0.isEnabled)" }
        let hierarchy = XCTAttachment(string: refreshFacts.joined(separator: "\n") + "\n" + app.debugDescription)
        hierarchy.name = "direct-modal-accessibility"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
        XCTAssertEqual(allRefresh.filter(\.isHittable).count, 1)
        refresh.tap()
        XCTAssertFalse(sheet.buttons["Next"].isEnabled)
        XCTAssertFalse(sheet.buttons["Start direct listener"].exists)
        capture("direct-tailnet-unavailable", app: app)
        let back = sheet.buttons["Back"], cancel = sheet.buttons["Cancel"]
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertGreaterThanOrEqual(cancel.frame.height, 44)
        XCTAssertFalse(back.frame.intersects(cancel.frame))
        back.tap()
        let relay = app.buttons["agent-connection-relay"]
        XCTAssertTrue(relay.waitForExistence(timeout: 8))
        relay.tap()
        app.buttons["Next"].tap()
        XCTAssertTrue(app.staticTexts["Control agent permissions"].waitForExistence(timeout: 8))
        app.buttons["Cancel"].tap()
        XCTAssertFalse(app.staticTexts["Agent setup · shown once"].exists)
        let inlineRefresh = app.buttons["owner-direct.refresh"]
        XCTAssertTrue(inlineRefresh.waitForExistence(timeout: 5))
        XCTAssertTrue(inlineRefresh.isHittable)
        capture("direct-return-to-agents", app: app)
    }

    func testAllStepNavigationRemainsReachableAtLargestText() {
        continueAfterFailure = false
        let app = launch(large: true)
        for step in 1...3 {
            assertStep(step, app: app)
            XCTAssertTrue(app.buttons["firstRun.next"].isHittable)
            XCTAssertTrue(app.buttons["firstRun.skip"].isHittable)
            if step > 1 { XCTAssertTrue(app.buttons["firstRun.back"].isHittable) }
            capture("first-run-step-\(step)-largest", app: app)
            app.buttons["firstRun.next"].tap()
        }
        assertStep(4, app: app)
        for step in [3, 2, 1] {
            XCTAssertTrue(app.buttons["firstRun.back"].isHittable)
            app.buttons["firstRun.back"].tap(); assertStep(step, app: app)
        }
        app.buttons["firstRun.skip"].tap(); assertStep(4, app: app)
        XCTAssertTrue(app.buttons["firstRun.diagnostic"].isHittable)
        XCTAssertTrue(app.buttons["firstRun.addAgent"].isHittable)
    }
    func testPrivacyLinkReturnsToIncompletePrivacyStep() {
        continueAfterFailure = false
        let app = launch()
        app.buttons["firstRun.next"].tap(); app.buttons["firstRun.next"].tap(); assertStep(3, app: app)
        let link = app.descendants(matching: .any).matching(identifier: "firstRun.privacy").firstMatch
        if !link.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(link.isHittable); link.tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 10))
        app.activate(); assertStep(3, app: app)
        app.terminate(); app.launch(); assertStep(3, app: app)
    }
    func testFreshInstallWithoutFixtureGatesThenOpensLocalDiagnostic() throws {
        guard ProcessInfo.processInfo.environment["BRIDGIT_FRESH_FIRST_RUN"] == "1" else { throw XCTSkip("Requires an owned fresh simulator; no real owner defaults are reset") }
        let app = XCUIApplication(); app.launchArguments = []; app.launch()
        assertStep(1, app: app)
        app.buttons["firstRun.skip"].tap(); app.buttons["firstRun.diagnostic"].tap()
        XCTAssertFalse(app.staticTexts["firstRun.progress"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Device details, ")).firstMatch.waitForExistence(timeout: 10))
        capture("fresh-first-run-local-diagnostic", app: app)
        app.terminate(); app.launch()
        XCTAssertFalse(app.staticTexts["firstRun.progress"].waitForExistence(timeout: 2))
    }
}

final class DevicePairingParityTests: XCTestCase {
    private func openPair() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-tab", "more"]
        app.launch()
        let linked = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Linked devices")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 8)); linked.tap()
        let pair = app.buttons["Pair a device"].firstMatch
        XCTAssertTrue(pair.waitForExistence(timeout: 8)); pair.tap()
        XCTAssertTrue(app.buttons["pair.close"].waitForExistence(timeout: 8))
        return app
    }
    private func tap(_ id: String, app: XCUIApplication) {
        let button = app.buttons[id]
        for _ in 0..<6 where !button.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(button.isHittable, id); button.tap()
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testAllFourRolesAndActualAPKFormatReviewWithoutGrantOrClaim() {
        continueAfterFailure = false
        let app = openPair()
        for id in ["pair.joinSatellite", "pair.addSatellite", "pair.issueViewer", "pair.pasteViewer"] {
            XCTAssertTrue(app.buttons[id].exists)
            XCTAssertTrue(app.buttons[id].isEnabled)
        }
        capture("pair-four-actions", app: app)
        tap("pair.pasteViewer", app: app)
        let input = app.textViews["pair.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5)); input.tap()
        // An explicitly synthetic, non-issued fixture value. No claim is made by review.
        input.typeText("bridgitportal://pair#v=1&relay=https%3A%2F%2Fpairing-fixture.invalid&device=dev_fixture_123&token=rly_fixture_0123456789abcdef&name=Fixture%20Host&portal=1&claim=1")
        tap("pair.review", app: app)
        XCTAssertTrue(app.staticTexts["Review host link"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["pair.confirm"].isHittable)
        XCTAssertFalse(app.debugDescription.contains("rly_fixture_0123456789abcdef"), "Review must not expose the credential")
        capture("pair-apk-viewer-review", app: app)
        app.buttons["pair.close"].tap()
        XCTAssertTrue(app.buttons["Pair a device"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Fixture Host"].exists, "Cancel must not create a saved link")
    }
    func testPairingRejectsAccountAndSatelliteInputsBeforeAnyClaim() {
        continueAfterFailure = false
        for text in ["ABCD-EFGH", "bridgit://satellite#v=1&host=100.70.1.2&port=8080&claim=sct_fixture", "bridgitportal://signin#session=not-an-account"] {
            let app = openPair()
            tap("pair.pasteViewer", app: app)
            let input = app.textViews["pair.input"]
            XCTAssertTrue(input.waitForExistence(timeout: 5)); input.tap(); input.typeText(text)
            tap("pair.review", app: app)
            XCTAssertTrue(app.otherElements["pair.problem"].exists || app.staticTexts["pair.problem"].exists)
            XCTAssertFalse(app.buttons["pair.confirm"].exists)
            app.buttons["pair.close"].tap(); app.terminate()
        }
    }
    func testCameraChoiceIsExplicitAndSimulatorHasPasteFallback() {
        continueAfterFailure = false
        let app = openPair()
        tap("pair.scanViewer", app: app)
        XCTAssertTrue(app.staticTexts["No camera here. Paste the link or choose a QR image instead."].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Paste a link"].exists)
        XCTAssertTrue(app.buttons["Choose a QR image"].exists)
        capture("pair-no-camera-fallback", app: app)
    }
    func testIssuerReviewRequiresExplicitStartAndOfflineFixtureCannotGrant() {
        continueAfterFailure = false
        let app = openPair()
        tap("pair.issueViewer", app: app)
        XCTAssertTrue(app.staticTexts["Share this device’s Home"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["pair.createViewer"].exists)
        XCTAssertFalse(app.buttons["pair.copyIssued"].exists)
        capture("pair-issuer-owner-review", app: app)
        tap("pair.createViewer", app: app)
        let problem = app.descendants(matching: .any).matching(identifier: "pair.problem").firstMatch
        XCTAssertTrue(problem.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["pair.copyIssued"].exists, "Offline fixture cannot invent a grant or code")
        app.buttons["pair.close"].tap()
        XCTAssertTrue(app.buttons["Pair a device"].firstMatch.waitForExistence(timeout: 5))
    }

    func testBecomeSatelliteShowsRealPrerequisitesAndCannotIssueOffline() {
        continueAfterFailure = false
        let app = openPair()
        tap("pair.joinSatellite", app: app)
        XCTAssertTrue(app.staticTexts["Make this device a satellite"].waitForExistence(timeout: 6))
        XCTAssertTrue(app.staticTexts["satellite.addressUnavailable"].exists)
        XCTAssertTrue(app.buttons["satellite.issue"].exists)
        XCTAssertFalse(app.buttons["satellite.issue"].isEnabled)
        XCTAssertFalse(app.buttons["satellite.copy"].exists)
        capture("satellite-issuer-requires-tailnet", app: app)
        tap("satellite.back", app: app)
        XCTAssertTrue(app.buttons["pair.joinSatellite"].waitForExistence(timeout: 5))
    }
    func testSatelliteAddReviewsPinnedFormatAndCannotInventOfflineClaim() {
        continueAfterFailure = false
        let app = openPair()
        tap("pair.addSatellite", app: app)
        XCTAssertTrue(app.staticTexts["Add another satellite"].waitForExistence(timeout: 6))
        let input = app.textViews["satellite.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5)); input.tap()
        let token = "sct_" + String(repeating: "a", count: 43) // Synthetic and unissued.
        input.typeText("bridgit://satellite#v=1&host=100.80.1.2&port=18471&claim=" + token + "&name=Fixture%20Satellite")
        tap("satellite.review", app: app)
        XCTAssertTrue(app.buttons["satellite.confirm"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["satellite.confirm"].isEnabled)
        XCTAssertFalse(app.debugDescription.contains(token), "Review must not expose the claim credential")
        capture("satellite-add-owner-review-offline", app: app)
        tap("satellite.back", app: app)
        tap("pair.manageSatellites", app: app)
        XCTAssertTrue(app.staticTexts["No saved satellite or hub links."].waitForExistence(timeout: 5))
    }

}

final class AgentKeyRotationParityTests: XCTestCase {
    func testDirectRotationDismissAndReopen() { replayRotation(large: false) }
    func testDirectRotationDismissAndReopenAtLargestText() { replayRotation(large: true) }

    private func replayRotation(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        let rotation = app.buttons["Rotate key for Rotation fixture"]
        XCTAssertTrue(rotation.waitForExistence(timeout: 12))
        XCTAssertTrue(app.staticTexts["Viewer fixture"].exists)
        XCTAssertFalse(app.buttons["Rotate key for Viewer fixture"].exists)
        XCTAssertFalse(app.switches["Superpowers for Viewer fixture"].exists)
        XCTAssertFalse(app.switches["Fixer Mode for Viewer fixture"].exists)
        XCTAssertTrue(app.switches["Superpowers for Rotation fixture"].exists)
        XCTAssertTrue(app.switches["Fixer Mode for Rotation fixture"].exists)
        func reveal(_ item: XCUIElement) {
            for _ in 0..<8 where !item.isHittable { app.swipeUp() }
            XCTAssertTrue(item.isHittable)
        }
        func waitHittable(_ item: XCUIElement) {
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: item)
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 8), .completed)
        }
        func openAndCloseAdd() {
            let add = app.buttons["Add control agent"]
            for _ in 0..<8 where !add.isHittable { app.swipeDown() }
            XCTAssertTrue(add.isHittable)
            add.tap()
            XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 8))
            let close = app.buttons["Close"]
            waitHittable(close)
            close.tap()
            XCTAssertTrue(close.waitForNonExistence(timeout: 8))
            waitHittable(add)
        }
        openAndCloseAdd()
        let done = app.buttons["host.setup.done"]
        func capture(_ name: String) {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = name
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }
        for index in 0..<2 {
            reveal(rotation)
            XCTAssertGreaterThanOrEqual(rotation.frame.height, 44)
            rotation.tap()
            let confirm = app.buttons["Rotate key"]
            XCTAssertTrue(confirm.waitForExistence(timeout: 5))
            confirm.tap()
            waitHittable(done)
            XCTAssertGreaterThanOrEqual(done.frame.height, 44)
            XCTAssertFalse(rotation.isHittable)
            let content = app.scrollViews["host.setup.content"]
            let warning = app.staticTexts["host.setup.warning"]
            XCTAssertTrue(warning.waitForExistence(timeout: 5))
            XCTAssertTrue(warning.label.hasSuffix("Rotate if exposed."))
            for _ in 0..<4 where warning.frame.maxY > content.frame.maxY {
                let start = content.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
                let end = content.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.68))
                start.press(forDuration: 0.12, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
            }
            XCTAssertGreaterThanOrEqual(warning.frame.minY, content.frame.minY - 1)
            XCTAssertLessThanOrEqual(warning.frame.maxY, content.frame.maxY + 1)
            if index == 0 { capture("rotation-warning") }
            let unavailable = app.staticTexts["No Tailscale address found. Connect Tailscale, then check again."]
            let refreshes = app.buttons.matching(identifier: "owner-direct.refresh")
            for _ in 0..<8 where !refreshes.allElementsBoundByIndex.contains(where: \.isHittable) { content.swipeUp() }
            let foreground = refreshes.allElementsBoundByIndex.filter(\.isHittable)
            XCTAssertEqual(foreground.count, 1)
            foreground.first?.tap()
            XCTAssertTrue(unavailable.exists)
            XCTAssertFalse(app.buttons["Start direct listener"].exists)
            if index == 0 { capture("rotation-listener-unavailable") }
            done.tap()
            XCTAssertTrue(done.waitForNonExistence(timeout: 8))
            waitHittable(rotation)
            if index == 0 { capture("rotation-restored-agents") }
            openAndCloseAdd()
        }
    }
}


final class OwnerControlAgentDetailParityTests: XCTestCase {
    private func launch(large: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-fixtureHideOneTimeSecrets", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        return app
    }

    private func reveal(_ element: XCUIElement, in scroll: XCUIElement) {
        for _ in 0..<8 where !element.isHittable { scroll.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }

    private func scrollOuterForm(_ form: XCUIElement) {
        form.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.85))
            .press(forDuration: 0.12, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.25)),
                   withVelocity: .slow, thenHoldForDuration: 0)
    }

    private func openDetails(_ app: XCUIApplication, name: String = "Rotation fixture") {
        let button = app.buttons["Details for " + name]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        reveal(button, in: app)
        button.tap()
        XCTAssertTrue(app.staticTexts["Control agent details"].waitForExistence(timeout: 8))
    }

    private func capture(_ name: String, app: XCUIApplication) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testOwnerCallbackCopyClosesOneTimeKey() {
        let app = launch()
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        let edit = app.buttons["host.callback.edit"]
        reveal(edit, in: form); edit.tap()
        let field = app.textFields["host.callback.url"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap(); field.typeText("http://100.64.0.1/fixture")
        app.buttons["host.callback.next"].tap()
        let confirmation = app.alerts.firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5)); confirmation.buttons["Save"].tap()
        let copy = app.buttons["host.callback.secret.copy"]
        XCTAssertTrue(copy.waitForExistence(timeout: 10)); XCTAssertGreaterThanOrEqual(copy.frame.height, 44)
        copy.tap()
        XCTAssertTrue(app.staticTexts["host.callback.status"].waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["host.callback.secret"])
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.staticTexts["Signing key copied."].waitForExistence(timeout: 10))
        let diagnostic = app.staticTexts["host.callback.fixture-copy-diagnostic"]
        reveal(diagnostic, in: form)
        XCTAssertEqual(diagnostic.label, "Fixture copy verified")
    }

    private func revealInLargeForm(_ target: XCUIElement, form: XCUIElement, app: XCUIApplication) {
        for _ in 0..<40 {
            let viewport = form.frame
            let frame = target.exists ? target.frame : .zero
            if frame.width > 0, frame.height > 0, viewport.contains(CGPoint(x: frame.midX, y: frame.midY)), target.isHittable { return }
            let down = frame.isEmpty || frame.midY > viewport.midY
            let from = down ? 0.64 : 0.46
            let to = down ? 0.46 : 0.64
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: from))
                .press(forDuration: 0.1, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: to)),
                       withVelocity: .slow, thenHoldForDuration: 0)
        }
        capture("callback-target-failure", app: app)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "callback-target-failure-ax"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        XCTFail("Callback target could not be reached")
    }

    func testOwnerAgentDetailsLargeIPadSheetSize() {
        let app = launch(large: true)
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        XCTAssertTrue(form.waitForExistence(timeout: 10))
        let frames = XCTAttachment(string: "window=\(app.frame) form=\(form.frame)")
        frames.name = "agent-detail-sheet-frames"; frames.lifetime = .keepAlways; add(frames)
        capture("agent-detail-page-top", app: app)
        XCTAssertGreaterThan(form.frame.height, 0)
        scrollOuterForm(form)
        capture("agent-detail-page-scrolled", app: app)
    }

    func testOwnerCallbackEditAndCopyAtLargestText() {
        let app = launch(large: true)
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        let edit = app.buttons["host.callback.edit"]
        revealInLargeForm(edit, form: form, app: app); edit.tap()
        let field = app.textFields["host.callback.url"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap()
        let first = "http://100.64.0.1/fixture"
        field.typeText(first); app.buttons["host.callback.next"].tap()
        let confirm = app.alerts.firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["host.callback.secret"].waitForExistence(timeout: 10))
        app.buttons["host.callback.secret.done"].tap()
        let status = app.staticTexts["host.callback.status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "100.64.0.1"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        reveal(edit, in: form); edit.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10)); XCTAssertEqual(field.value as? String, first)
        field.tap(); field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: first.count))
        field.typeText("http://100.64.0.2/updated"); app.buttons["host.callback.next"].tap()
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.buttons["Save"].tap()
        let copy = app.buttons["host.callback.secret.copy"]
        XCTAssertTrue(copy.waitForExistence(timeout: 10)); XCTAssertGreaterThanOrEqual(copy.frame.height, 44); copy.tap()
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "100.64.0.2"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        let copied = app.staticTexts["Signing key copied."]
        revealInLargeForm(copied, form: form, app: app)
        let diagnostic = app.staticTexts["host.callback.fixture-copy-diagnostic"]; revealInLargeForm(diagnostic, form: form, app: app)
        XCTAssertEqual(diagnostic.label, "Fixture copy verified")
        capture("callback-updated-largest", app: app)
        let remove = app.buttons["host.callback.remove"]
        revealInLargeForm(remove, form: form, app: app); remove.tap()
        XCTAssertTrue(app.alerts.buttons["Cancel"].waitForExistence(timeout: 5)); app.alerts.buttons["Cancel"].tap()
        XCTAssertTrue(remove.isHittable); remove.tap(); app.alerts.buttons["Remove"].tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: remove)
        waitForExpectations(timeout: 10)
        capture("callback-removed-largest", app: app)
    }

    func testOwnerCallbackSetReviewKeyAndRemove() {
        let app = launch()
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        let edit = app.buttons["host.callback.edit"]
        reveal(edit, in: form); capture("callback-before-open", app: app); edit.tap()
        let field = app.textFields["host.callback.url"]
        let opened = field.waitForExistence(timeout: 10)
        capture("callback-after-open", app: app)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "callback-after-open-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        XCTAssertTrue(opened)
        let cancel = app.buttons["host.callback.cancel"], next = app.buttons["host.callback.next"]
        XCTAssertGreaterThanOrEqual(cancel.frame.height, 44); XCTAssertGreaterThanOrEqual(next.frame.height, 44)
        field.tap(); field.typeText("http://100.64.0.1/fixture")
        app.buttons["host.callback.next"].tap()
        let confirmation = app.alerts.firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "http://100.64.0.1/fixture")).firstMatch.exists)
        confirmation.buttons["Save"].tap()
        let key = app.staticTexts["host.callback.secret"]
        XCTAssertTrue(key.waitForExistence(timeout: 10)); XCTAssertEqual(key.label, "Signing key hidden for fixture")
        app.buttons["host.callback.secret.done"].tap()
        let status = app.staticTexts["host.callback.status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "100.64.0.1"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        capture("callback-configured", app: app)
        reveal(edit, in: form); edit.tap()
        let reopenedURL = app.textFields["host.callback.url"]
        XCTAssertTrue(reopenedURL.waitForExistence(timeout: 5))
        XCTAssertEqual(reopenedURL.value as? String, "http://100.64.0.1/fixture")
        XCTAssertFalse(app.staticTexts["host.callback.secret"].exists)
        app.buttons["host.callback.cancel"].tap()
        app.buttons["Cancel"].firstMatch.tap()
        let rotate = app.buttons["Rotate key for Rotation fixture"]
        XCTAssertTrue(rotate.waitForExistence(timeout: 8)); reveal(rotate, in: app); rotate.tap()
        let confirmRotate = app.buttons["Rotate key"]
        XCTAssertTrue(confirmRotate.waitForExistence(timeout: 5)); confirmRotate.tap()
        XCTAssertTrue(app.staticTexts["host.setup.callback-key-present"].waitForExistence(timeout: 10))
        app.buttons["host.setup.done"].tap()
        openDetails(app)
        let remove = app.buttons["host.callback.remove"]
        reveal(remove, in: form); remove.tap()
        XCTAssertTrue(app.alerts.buttons["Cancel"].waitForExistence(timeout: 5)); app.alerts.buttons["Cancel"].tap()
        XCTAssertTrue(remove.exists)
        remove.tap(); app.alerts.buttons["Remove"].tap()
        XCTAssertTrue(app.staticTexts["host.callback.status"].waitForExistence(timeout: 5))
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.buttons["host.callback.remove"])
        waitForExpectations(timeout: 10)
        capture("callback-removed", app: app)
    }

    func testOwnerDetailSavesNameNotesAndGrantAndExcludesViewer() { checkSaving(large: false) }
    func testOwnerDetailSavesNameNotesAndGrantAtLargestText() { checkSaving(large: true) }

    private func checkSaving(large: Bool) {
        let app = launch(large: large)
        XCTAssertTrue(app.staticTexts["Viewer fixture"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Details for Viewer fixture"].exists)
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        let name = app.textFields["host.agent.detail.name"]
        reveal(name, in: form)
        XCTAssertEqual(name.value as? String, "Rotation fixture")
        name.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        name.typeText(" revised")
        let notes = app.textViews["host.agent.detail.notes"]
        reveal(notes, in: form)
        notes.tap()
        notes.typeText("Owner fixture notes")
        XCTAssertTrue(form.exists)
        scrollOuterForm(form)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        let grant = app.switches["host.agent.detail.grant.log"]
        reveal(grant, in: form)
        XCTAssertEqual(grant.value as? String, "0")
        grant.tap()
        XCTAssertEqual(grant.value as? String, "1")
        capture("control-agent-grant-selected", app: app)
        let save = app.buttons["host.agent.detail.save"]
        XCTAssertTrue(save.isEnabled)
        XCTAssertTrue(save.isHittable)
        save.tap()
        XCTAssertTrue(form.waitForNonExistence(timeout: 8))
        openDetails(app, name: "Rotation fixture revised")
        let reopenedNotes = app.textViews["host.agent.detail.notes"]
        reveal(reopenedNotes, in: form)
        XCTAssertEqual(reopenedNotes.value as? String, "Owner fixture notes")
        capture("control-agent-saved-details", app: app)
        if large { scrollOuterForm(form) }
        reveal(grant, in: form)
        XCTAssertEqual(grant.value as? String, "1")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["Rotate key for Rotation fixture revised"].exists)
    }

    func testMaximumGrantDraftRequiresConfirmationAndCanBeCancelled() { checkMaximum(large: false) }
    func testMaximumGrantDraftAtLargestTextCanBeCancelled() { checkMaximum(large: true) }

    private func checkMaximum(large: Bool) {
        let app = launch(large: large)
        openDetails(app)
        let form = app.descendants(matching: .any).matching(identifier: "host.agent.detail.form").firstMatch
        let maximum = app.buttons["host.agent.detail.maximum"]
        reveal(maximum, in: form)
        maximum.tap()
        let confirm = app.alerts.buttons["Grant"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.staticTexts["Superpowers and Fixer Mode."].exists)
        capture("control-agent-maximum-confirmation", app: app)
        confirm.tap()
        let state = app.staticTexts["host.agent.detail.powers"]
        for _ in 0..<4 where !state.isHittable {
            let start = form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))
            let end = form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.47))
            start.press(forDuration: 0.12, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
        }
        XCTAssertTrue(state.isHittable)
        XCTAssertEqual(state.label, "Maximum agent grants")
        XCTAssertTrue(app.buttons["host.agent.detail.save"].isEnabled)
        capture("control-agent-maximum-draft", app: app)
        app.buttons["Cancel"].tap()
        XCTAssertTrue(form.waitForNonExistence(timeout: 8))
        openDetails(app)
        reveal(state, in: form)
        XCTAssertEqual(state.label, "Limited agent grants")
        XCTAssertFalse(app.buttons["host.agent.detail.save"].isEnabled)
        capture("control-agent-limited-after-cancel", app: app)
    }
}

final class DeviceCapabilityReductionParityTests: XCTestCase {
    func testCapabilityReductionConfirmation() { replayConfirmation(large: false) }
    func testCapabilityReductionConfirmationAtLargestText() { replayConfirmation(large: true) }

    private func replayConfirmation(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        let action = app.buttons["Turn off device capabilities"]
        XCTAssertTrue(app.staticTexts["Control agents"].firstMatch.waitForExistence(timeout: 12))
        for _ in 0..<12 where !action.isHittable { app.swipeUp() }
        XCTAssertTrue(action.isHittable)
        XCTAssertGreaterThanOrEqual(action.frame.height, 44)
        action.tap()
        let message = app.staticTexts["host.capabilities.off.scope"]
        XCTAssertTrue(message.waitForExistence(timeout: 8))
        XCTAssertTrue(message.label.contains("agent network discovery"))
        XCTAssertTrue(message.label.contains("Internet callback destinations"))
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "device-capabilities-off-confirmation"
        capture.lifetime = .keepAlways
        add(capture)
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.isHittable)
        XCTAssertGreaterThanOrEqual(cancel.frame.height, 44)
        cancel.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: action)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 8), .completed)
        action.tap()
        let turnOff = app.buttons["Turn off"]
        XCTAssertTrue(turnOff.waitForExistence(timeout: 8))
        XCTAssertGreaterThanOrEqual(turnOff.frame.height, 44)
        turnOff.tap()
        let restoredAgain = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: action)
        XCTAssertEqual(XCTWaiter.wait(for: [restoredAgain], timeout: 8), .completed)
    }
}

final class ActivityRetentionParityTests: XCTestCase {
    func testRetentionShowsRangeErrorAndPersistsValidValue() {
        let (app, entry) = openEditor()
        let days = app.textFields["log_retention_days"]
        XCTAssertTrue(days.waitForExistence(timeout: 8))
        days.tap()
        days.typeText(XCUIKeyboardKey.delete.rawValue + "366")
        XCTAssertTrue(app.staticTexts["Enter 0–365 days."].waitForExistence(timeout: 8))
        let save = app.buttons["Save"]
        XCTAssertFalse(save.isEnabled)
        days.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + "30")
        XCTAssertTrue(save.isEnabled)
        save.tap()
        XCTAssertTrue(waitHittable(entry))
        entry.tap()
        XCTAssertTrue(days.waitForExistence(timeout: 8))
        XCTAssertEqual(days.value as? String, "30")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(waitHittable(entry))
    }

    func testRetentionLargestTextReachesBothActionsAndReturns() {
        let (app, entry) = openEditor(large: true)
        let cancel = app.buttons["Cancel"]
        let save = app.buttons["Save"]
        XCTAssertTrue(waitHittable(cancel))
        XCTAssertTrue(save.exists)
        XCTAssertGreaterThanOrEqual(cancel.frame.height, 44)
        XCTAssertGreaterThanOrEqual(save.frame.height, 44)
        XCTAssertLessThan(save.frame.maxY, cancel.frame.minY)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "activity-retention-largest"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        cancel.tap()
        XCTAssertTrue(waitHittable(entry))
    }

    func testRetentionLargestTextScrollsEditsAndReopens() {
        let (app, entry) = openEditor(large: true)
        let form = app.scrollViews["activity.retention.form"]
        let entries = app.textFields["log_retention_entries"]
        reveal(entries, in: form)
        retainScreenshot("activity-retention-largest-field", app: app)
        entries.tap()
        entries.typeText(XCUIKeyboardKey.delete.rawValue + "100001")
        let invalid = app.staticTexts["Enter 0–100,000 entries."]
        XCTAssertTrue(invalid.waitForExistence(timeout: 8))
        let save = app.buttons["Save"]
        XCTAssertFalse(save.isEnabled)
        reveal(invalid, in: form)
        retainScreenshot("activity-retention-largest-invalid", app: app)
        entries.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "1000")
        XCTAssertTrue(save.isEnabled)
        XCTAssertTrue(waitHittable(save))
        save.tap()
        XCTAssertTrue(waitHittable(entry))
        entry.tap()
        XCTAssertTrue(form.waitForExistence(timeout: 8))
        reveal(entries, in: form)
        XCTAssertEqual(entries.value as? String, "1000")
        retainScreenshot("activity-retention-largest-reopened", app: app)
        app.buttons["Cancel"].tap()
        XCTAssertTrue(waitHittable(entry))
    }

    private func reveal(_ item: XCUIElement, in form: XCUIElement) {
        for _ in 0..<6 where !item.isHittable {
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.80))
                .press(forDuration: 0.05, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.25)))
        }
        XCTAssertTrue(item.isHittable)
    }

    private func retainScreenshot(_ name: String, app: XCUIApplication) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func openEditor(large: Bool = false) -> (XCUIApplication, XCUIElement) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        XCTAssertTrue(app.staticTexts["Control agents"].firstMatch.waitForExistence(timeout: 12))
        let entry = app.buttons["Activity retention"]
        for _ in 0..<15 where !entry.isHittable { app.swipeUp() }
        XCTAssertTrue(entry.isHittable)
        entry.tap()
        XCTAssertTrue(app.scrollViews["activity.retention.form"].waitForExistence(timeout: 8))
        return (app, entry)
    }

    private func waitHittable(_ item: XCUIElement) -> Bool {
        let predicate = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == YES"), object: item)
        return XCTWaiter.wait(for: [predicate], timeout: 8) == .completed
    }
}

final class ExternalCallsParityTests: XCTestCase {
    func testExternalCallsPauseAndResume() { replay(large: false) }
    func testExternalCallsPauseAndResumeAtLargestText() { replay(large: true) }

    private func replay(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        XCTAssertTrue(app.staticTexts["Control agents"].firstMatch.waitForExistence(timeout: 12))
        let toggle = app.switches["host.external-calls"]
        for _ in 0..<16 where !toggle.isHittable { app.swipeUp() }
        XCTAssertTrue(toggle.isHittable)
        XCTAssertEqual(toggle.value as? String, "1")
        toggle.tap()
        assertValue("0", of: toggle)
        let paused = XCTAttachment(screenshot: app.screenshot())
        paused.name = "external-calls-paused"
        paused.lifetime = .keepAlways
        add(paused)
        toggle.tap()
        assertValue("1", of: toggle)
    }

    private func assertValue(_ expected: String, of toggle: XCUIElement) {
        let predicate = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", expected), object: toggle)
        XCTAssertEqual(XCTWaiter.wait(for: [predicate], timeout: 8), .completed)
    }
}


final class DeviceNameParityTests: XCTestCase {
    func testDeviceNameSaveReopenAndClear() { replay(large: false) }
    func testDeviceNameSaveReopenAndClearAtLargestText() { replay(large: true) }

    private func replay(large: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-fixtureStandalone", "-fixtureFreshStore", "-fixtureRotationAgent", "-tab", "agents"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        XCTAssertTrue(app.staticTexts["Control agents"].firstMatch.waitForExistence(timeout: 12))
        let entry = app.buttons["host.device-name.edit"]
        for _ in 0..<16 where !entry.isHittable { app.swipeUp() }
        XCTAssertTrue(entry.isHittable)
        let originalLabel = entry.label
        let field = app.textFields["host.device-name.value"]
        let form = app.scrollViews["host.device-name.form"]
        let save = app.buttons["Save"]

        func revealField() {
            XCTAssertTrue(form.waitForExistence(timeout: 8))
            for _ in 0..<6 where !field.isHittable {
                form.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.8))
                    .press(forDuration: 0.05, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.25)))
            }
            XCTAssertTrue(field.isHittable)
        }

        func waitForEntry(containing name: String) {
            let predicate = NSPredicate(format: "hittable == YES AND label CONTAINS %@", name)
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: entry)], timeout: 8), .completed)
        }

        entry.tap()
        revealField()
        field.tap()
        field.typeText("Workshop")
        XCTAssertTrue(save.isEnabled)
        save.tap()
        waitForEntry(containing: "Workshop")
        entry.tap()
        revealField()
        XCTAssertEqual(field.value as? String, "Workshop")
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = large ? "device-name-largest-reopened" : "device-name-reopened"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "Workshop".count))
        XCTAssertTrue(save.isEnabled)
        save.tap()
        waitForEntry(containing: originalLabel)
    }
}
