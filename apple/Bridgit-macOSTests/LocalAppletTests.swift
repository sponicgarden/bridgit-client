import BridgitCore
import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import Bridgit_macOS

final class LocalAppletTests: XCTestCase {
    func testImportNormalizesLargeImagesAndRejectsInvalidBytes() throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 2400, height: 1200, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 2400, height: 1200))
        let image = try XCTUnwrap(context.makeImage()), input = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(input, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil); XCTAssertTrue(CGImageDestinationFinalize(destination))
        let jpeg = try LocalAppletModel.jpeg(input as Data)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(decoded.width, 1024); XCTAssertEqual(decoded.height, 512)
        XCTAssertEqual(CGImageSourceGetType(source) as String?, "public.jpeg")
        XCTAssertThrowsError(try LocalAppletModel.jpeg(Data("not an image".utf8)))
    }
    @MainActor func testCameraTargetsUseInstalledButtonAndTrackPauseAndRemoval() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let local = LocalAppletModel(directory: directory)
        let store = try XCTUnwrap(local.store)
        let url = try XCTUnwrap(Bundle.main.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Applets"))
        let package = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        try await store.install(package)
        await local.reload()
        let target = try XCTUnwrap(local.cameraTargets.first)
        XCTAssertEqual(local.cameraTargets.count, 1)
        XCTAssertEqual(target.id, "insurance-inventory:camera")
        XCTAssertEqual(target.action["table"] as? String, "photos")
        await local.pause(applet: target.applet, paused: true)
        XCTAssertTrue(local.cameraTargets.isEmpty)
        await local.pause(applet: target.applet, paused: false)
        XCTAssertEqual(local.cameraTargets.first?.id, target.id)
        await local.remove(applet: target.applet)
        XCTAssertTrue(local.cameraTargets.isEmpty)
    }

    @MainActor func testDataOnlyNativeBatchDoesNotRequireCloudAppraisalConsent() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let local = LocalAppletModel(directory: directory)
        let tables: [[String: Any]] = [
            ["id": "input", "columns": ["label": ["type": "text"]]],
            ["id": "output", "columns": ["label": ["type": "text"]]]
        ]
        let flow: [String: Any] = ["id": "copy", "trigger": ["type": "called", "with": ["label": "text"]], "steps": [["id": "save", "type": "record", "table": "output", "row": ["label": ["$trigger": "with.label"]]]]]
        let action: [String: Any] = ["flow": ["$flow": "copy"], "for_each": ["$source": "input"], "with": ["label": ["$item": "label"]]]
        let card: [String: Any] = ["sources": ["input": ["table": "input"]], "pieces": [["type": "buttons", "items": [["id": "copy", "do": action]]]]]
        let package: [String: Any] = ["format": "bridgit-template/1", "id": "data-only", "version": "1", "runtime": ["adapter": "bridgit.package.v1"], "processing": ["tables": tables, "flows": [flow]], "ui": ["card": card]]
        await local.installPackage(try JSONSerialization.data(withJSONObject: package))
        let store = try XCTUnwrap(local.store), engine = try XCTUnwrap(local.engine)
        _ = try await store.addRows(applet: "data-only", table: "input", rows: [["label": "Saved locally"]])
        XCTAssertFalse(local.modelEnabled)
        await local.startBatch(applet: "data-only", button: "copy"); await engine.waitForBatch(applet: "data-only")
        let rows = try await store.rows(applet: "data-only", table: "output")
        XCTAssertEqual(rows.first?["label"] as? String, "Saved locally"); XCTAssertFalse(local.modelEnabled)
    }

}
