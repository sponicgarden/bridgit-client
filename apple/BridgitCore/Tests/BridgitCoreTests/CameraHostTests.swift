import Foundation
import XCTest
@testable import BridgitCore
private actor CameraCount { var calls = 0; func captured() -> Data { calls += 1; return Data([0xff, 0xd8, 0xff]) } }
final class CameraHostTests: XCTestCase {
    func testV1OptionsAreBoundedAndUnsupportedStorageCannotBeIgnored() throws {
        let defaults = try CameraRequestOptions(body: [:], query: [:]); XCTAssertFalse(defaults.raw); XCTAssertEqual(defaults.maxEdge, 2048)
        let thumb = try CameraRequestOptions(body: ["size": "thumb", "quality": 90], query: ["raw": "1"]); XCTAssertEqual(thumb.maxEdge, 512); XCTAssertEqual(thumb.quality, 0.9); XCTAssertTrue(thumb.raw)
        for body: [String: Any] in [["flash": true], ["zoom": 2], ["store": ["upload"]], ["max_width_px": true], ["max_width_px": 20000], ["size": "original"], ["camera": 42], ["upload_url": "https://invalid.test"]] { XCTAssertThrowsError(try CameraRequestOptions(body: body, query: [:])) }
    }
    func testCameraNeedsFreshCallerGrantAndCachedJPEGDoesNotCaptureAgain() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path), authority = HostAuthority(store: store, deviceID: "fixture-device"), count = CameraCount()
        let (agent, _) = try await authority.create(name: "Camera fixture", operations: [.cameraSnapshot], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, sensor: { _, _ in .init(contentType: "image/jpeg", data: await count.captured(), headers: ["X-Bridgit-Camera": "default"]) })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var job = HostJob(id: "photo-first", sequence: 1, agentID: agent.id, generation: agent.generation, deviceID: "fixture-device", deadline: now + 30000, createdAt: now - 6000, request: .init(method: "POST", path: "/v1/camera/snapshot", idempotencyKey: "once"))
        let stale = await dispatcher.dispatch(job); XCTAssertEqual(stale.status, 409)
        job.createdAt = now
        let first = await dispatcher.dispatch(job); XCTAssertEqual(first.contentType, "image/jpeg"); XCTAssertEqual(first.status, 200)
        job.sequence = 2; job.id = "photo-retry"
        let retry = await dispatcher.dispatch(job); XCTAssertEqual(retry.data, first.data); XCTAssertEqual(retry.headers, first.headers); XCTAssertEqual(retry.contentType, "image/jpeg")
        let calls = await count.calls; XCTAssertEqual(calls, 1)
        job.sequence = 3; job.request.query = ["raw": "1"]
        let conflict = await dispatcher.dispatch(job); XCTAssertEqual(conflict.status, 409)
        job.sequence = 4; job.request.query = ["target": "sat:garage"]; job.request.idempotencyKey = "new-target"
        let targeted = await dispatcher.dispatch(job); XCTAssertEqual(targeted.status, 422)
        let after = await count.calls; XCTAssertEqual(after, 1)
    }
}
