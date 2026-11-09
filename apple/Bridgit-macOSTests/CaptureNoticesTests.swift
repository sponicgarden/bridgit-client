import XCTest
import BridgitCore
import CoreLocation
@testable import Bridgit_macOS

@MainActor final class CaptureNoticesTests: XCTestCase {
    func testOverlappingNoticesRemainActiveUntilTheirOwnEnd() async throws {
        var sent: [String] = []
        let notices = CaptureNotices(read: { .allowed }, send: { _, title in sent.append(title) })
        let first = try await notices.begin("Agent A is taking a photo")
        let second = try await notices.begin("Agent B is recording audio")
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(sent, ["Agent A is taking a photo", "Agent B is recording audio"])
        XCTAssertEqual(notices.active.map(\.id), [first, second])
        notices.end(first)
        XCTAssertEqual(notices.active.map(\.id), [second])
        notices.end(second)
        XCTAssertTrue(notices.active.isEmpty)
    }

    func testDeniedOrFailedSchedulingCannotStartCapture() async {
        var calls = 0
        let denied = CaptureNotices(read: { .unavailable }, send: { _, _ in calls += 1 })
        do { _ = try await denied.begin("capture"); XCTFail("Denied notice accepted") } catch { }
        XCTAssertEqual(calls, 0)
        XCTAssertTrue(denied.active.isEmpty)
        let failing = CaptureNotices(read: { .allowed }, send: { _, _ in throw CancellationError() })
        do { _ = try await failing.begin("capture"); XCTFail("Failed notice accepted") } catch { }
        XCTAssertTrue(failing.active.isEmpty)
    }
    func testRevocationWhileSchedulingDoesNotBeginCapture() async {
        var reads = 0, sends = 0
        let notices = CaptureNotices(read: {
            reads += 1
            return reads == 1 ? .allowed : .unavailable
        }, send: { _, _ in sends += 1 })
        do { _ = try await notices.begin("capture"); XCTFail("Revoked notice accepted") } catch { }
        XCTAssertEqual(sends, 1)
        XCTAssertTrue(notices.active.isEmpty)
    }

    func testMonitorStopsOnceAfterRevocationAndCanBeCancelled() async throws {
        var allowed = true, stopped = 0
        let notices = CaptureNotices(read: { allowed ? .allowed : .unavailable }, send: { _, _ in })
        let monitor = notices.monitor(interval: 5_000_000) { stopped += 1 }
        allowed = false
        await monitor.value
        XCTAssertEqual(stopped, 1)
        allowed = true
        let cancelled = notices.monitor(interval: 100_000_000) { stopped += 1 }
        cancelled.cancel()
        await cancelled.value
        XCTAssertEqual(stopped, 1)
    }

    func testPermissionPromptRequiresActiveAppAndReportsCurrentAuthorization() async throws {
        var active = false, prompts = 0, allowed = false
        let notices = CaptureNotices(read: { allowed ? .allowed : .unavailable },
                                     send: { _, _ in }, prompt: { prompts += 1; allowed = true },
                                     isActive: { active })
        do { try await notices.request(); XCTFail("Inactive app prompted") } catch { }
        XCTAssertEqual(prompts, 0)
        active = true
        try await notices.request()
        XCTAssertEqual(prompts, 1)
        XCTAssertEqual(notices.availability, .allowed)
    }

}

@MainActor final class WiFiHistoryAccessTests: XCTestCase {
    func testPromptDispositionRequiresOwnerActionWhenDeniedOrReduced() throws {
        XCTAssertTrue(try WiFiHistoryAccess.promptNeeded(status: "not_determined"))
        XCTAssertFalse(try WiFiHistoryAccess.promptNeeded(status: "allowed"))
        for status in ["denied", "reduced_accuracy", "restricted", "unknown"] {
            XCTAssertThrowsError(try WiFiHistoryAccess.promptNeeded(status: status)) { error in
                XCTAssertEqual((error as? DeviceAPIError)?.code, "os_permission_required")
            }
        }
    }

    func testOnlyPreciseAuthorizedLocationIsReportedAllowed() {
        XCTAssertEqual(WiFiHistoryAccess.status(authorization: .notDetermined, accuracy: .fullAccuracy), "not_determined")
        XCTAssertEqual(WiFiHistoryAccess.status(authorization: .denied, accuracy: .fullAccuracy), "denied")
        XCTAssertEqual(WiFiHistoryAccess.status(authorization: .restricted, accuracy: .fullAccuracy), "restricted")
        XCTAssertEqual(WiFiHistoryAccess.status(authorization: .authorizedAlways, accuracy: .reducedAccuracy), "reduced_accuracy")
        XCTAssertEqual(WiFiHistoryAccess.status(authorization: .authorizedAlways, accuracy: .fullAccuracy), "allowed")
    }
}
