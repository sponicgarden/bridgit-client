import Foundation
import XCTest
import SwiftUI
import AppKit
@testable import Bridgit_macOS

final class MacUpdatesTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("mac-updates-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: folder) }

    @discardableResult
    private func app(_ name: String, id: String = "cc.bridgit.macos.local", build: String) throws -> URL {
        let url = folder.appendingPathComponent(name + ".app", isDirectory: true)
        let contents = url.appendingPathComponent("Contents", isDirectory: true)
        try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: true)
        let info: NSDictionary = ["CFBundleIdentifier": id, "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": build]
        XCTAssertTrue(info.write(to: contents.appendingPathComponent("Info.plist"), atomically: true))
        return url
    }

    func testPicksNewestInstalledBuildOfThisApp() throws {
        let running = try app("Bridgit-2611081200", build: "2611081200")
        try app("Bridgit-2611071200", build: "2611071200")
        try app("Bridgit-2611091400", build: "2611091400")
        try app("Bridgit-2611091300", build: "2611091300")
        try app("Other", id: "com.example.other", build: "2701010000")
        let current = MacUpdates.Build(url: running, version: "0.1.0", build: "2611081200")
        let newest = MacUpdates.newest(in: [folder], bundleID: "cc.bridgit.macos.local", newerThan: current)
        XCTAssertEqual(newest?.build, "2611091400")
        XCTAssertEqual(newest?.label, "v0.1.0 (2611091400)")
    }

    func testNothingNewerMeansUpToDate() throws {
        let running = try app("Bridgit-2611091400", build: "2611091400")
        try app("Bridgit-2611071200", build: "2611071200")
        let current = MacUpdates.Build(url: running, version: "0.1.0", build: "2611091400")
        XCTAssertNil(MacUpdates.newest(in: [folder], bundleID: "cc.bridgit.macos.local", newerThan: current))
        XCTAssertNil(MacUpdates.newest(in: [folder.appendingPathComponent("missing")], bundleID: "cc.bridgit.macos.local", newerThan: current))
    }

    func testBuildNumbersCompareAsNumbers() {
        XCTAssertTrue(MacUpdates.isNewer("2611091400", than: "2611081200"))
        XCTAssertFalse(MacUpdates.isNewer("1", than: "2611081200"))
        XCTAssertTrue(MacUpdates.isNewer("10", than: "9"))
    }
}

/// A0156/A0158: the window header shows two complete lines, the version and the build number.
final class BuildStampTests: XCTestCase {
    func testLinesAreVersionThenShortBuild() {
        XCTAssertTrue(BuildStamp.lines(version: "0.1.0", build: "2611071810") == ("v0.1.0", "11071810"))
        XCTAssertTrue(BuildStamp.lines(version: "0.1.0", build: "2611090910") == ("v0.1.0", "11090910"))
        // Anything that is not a 26xxxxxxxx stamp passes through whole.
        XCTAssertTrue(BuildStamp.lines(version: "0.1.0", build: "2701010000") == ("v0.1.0", "2701010000"))
        // An unstamped build (project.yml default) is what showed as a lone "1" under v0.1.0.
        XCTAssertTrue(BuildStamp.lines(version: "0.1.0", build: "1") == ("v0.1.0", "1"))
    }

    /// The sidebar header (235 pt column, 22 pt padding) fits the wordmark and both stamp lines unwrapped.
    @MainActor func testSidebarHeaderFitsBothLines() throws {
        let header = WideBrandHeader(stampInfo: ("0.1.0", "2611071810"))
        let host = NSHostingView(rootView: header)
        let fit = host.fittingSize
        XCTAssertLessThanOrEqual(fit.width, 235 - 44, "Header must fit the sidebar column without wrapping")
        // Two text lines at 9 pt: taller than one line, so the build line is really there.
        let stamp = NSHostingView(rootView: BuildStamp(info: ("0.1.0", "2611071810")))
        let oneLine = NSHostingView(rootView: Text(verbatim: "v0.1.0").font(.system(size: 9).monospacedDigit()).fixedSize())
        XCTAssertGreaterThan(stamp.fittingSize.height, oneLine.fittingSize.height * 1.6)
        XCTAssertGreaterThanOrEqual(stamp.fittingSize.width, NSHostingView(rootView: Text(verbatim: "11071810").font(.system(size: 9).monospacedDigit()).fixedSize()).fittingSize.width)
        host.frame = NSRect(origin: .zero, size: fit)
        host.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: bitmap)
        let shot = XCTAttachment(data: try XCTUnwrap(bitmap.representation(using: .png, properties: [:])), uniformTypeIdentifier: "public.png")
        shot.name = "sidebar-header-version-build"; shot.lifetime = .keepAlways; add(shot)
    }
}
