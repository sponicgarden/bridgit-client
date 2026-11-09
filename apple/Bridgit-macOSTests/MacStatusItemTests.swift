import AppKit
import BridgitCore
import XCTest
@testable import Bridgit_macOS
final class MacStatusItemTests: XCTestCase {
    @MainActor func testFullSizeLogoBecomesAnEighteenPointColourStatusImage() throws {
        let source = NSImage(size: NSSize(width: 512, height: 512), flipped: false) { rect in NSColor.purple.setFill(); rect.fill(); return true }
        let image = MacStatusItem.image(from: source)
        XCTAssertEqual(image.size, NSSize(width: 18, height: 18)); XCTAssertFalse(image.isTemplate)
        let raster = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        XCTAssertLessThanOrEqual(raster.width, 36); XCTAssertLessThanOrEqual(raster.height, 36)
    }
    @MainActor func testConfigurationCreatesOneSquareStatusItem() {
        let status = MacStatusItem(), model = MacModel(cloud: WatchClient(), loadStored: false)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: path)
        defer { status.remove(); try? FileManager.default.removeItem(at: path) }
        status.configure(model: model, local: local, open: {})
        let first = status.item
        status.configure(model: model, local: local, open: {})
        XCTAssertTrue(first === status.item); XCTAssertEqual(status.item?.length, NSStatusItem.squareLength)
        XCTAssertEqual(status.item?.button?.image?.size.width, 18)
        XCTAssertEqual(status.item?.button?.imageScaling, .scaleProportionallyDown)
        let width = status.item?.button?.bounds.width ?? 0
        XCTAssertGreaterThan(width, 0); XCTAssertLessThanOrEqual(width, NSStatusBar.system.thickness + 8)
        print("Bridgit status item runtime: " + status.diagnostics)
    }
    @MainActor func testWindowlessActivationObserverIsRemovedWithTheStatusController() async throws {
        let workspace = NotificationCenter(), application = NotificationCenter()
        let status = MacStatusItem(workspaceCenter: workspace, applicationCenter: application)
        let model = MacModel(cloud: WatchClient(), loadStored: false)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: path)
        defer { status.remove(); try? FileManager.default.removeItem(at: path) }
        let host = try XCTUnwrap(local.host), store = try XCTUnwrap(local.store)
        let authority = BridgitCore.HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.deviceRead], applets: [])
        status.configure(model: model, local: local, open: {})
        // No window is created by this test. The app-owned status controller receives events.
        for _ in 0..<5 { await Task.yield() }
        application.post(name: NSApplication.didResignActiveNotification, object: nil)
        for _ in 0..<20 {
            if !host.profile(for: agent).availability.inForeground { break }
            await Task.yield()
        }
        XCTAssertFalse(host.profile(for: agent).availability.inForeground)
        status.remove()
        application.post(name: NSApplication.didBecomeActiveNotification, object: nil)
        for _ in 0..<5 { await Task.yield() }
        XCTAssertFalse(host.profile(for: agent).availability.inForeground, "Removed controller must stop receiving lifecycle events")
    }

}
