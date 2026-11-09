import Foundation
import XCTest
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
