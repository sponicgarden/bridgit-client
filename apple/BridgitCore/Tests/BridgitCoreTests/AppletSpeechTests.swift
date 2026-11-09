import Foundation
import XCTest
@testable import BridgitCore
private actor SpeechCount {
    var calls: [String] = []
    func respond(_ operation: String) -> [String: Any] { calls.append(operation); return operation == "hear" ? ["transcript": "fixture words"] : ["status": "completed"] }
}
final class AppletSpeechTests: XCTestCase {
    private func setup() throws -> (AppletStore, AppletEngine) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        return (store, AppletEngine(store: store) { _, _, _ in [:] })
    }
    private func package() throws -> Data {
        try JSONSerialization.data(withJSONObject: ["format": "bridgit-template/1", "id": "speech-fixture", "version": "1", "runtime": ["adapter": "bridgit.package.v1"], "permissions": ["required": ["mic", "speaker"]], "processing": ["flows": [["id": "turn", "trigger": ["type": "called"], "steps": [["id": "listen", "type": "hear", "duration_seconds": 3], ["id": "reply", "type": "speak", "text": ["$step": "listen.transcript"]]]]]]])
    }
    func testSpeechTurnsCacheRetriesButNewInvocationsRunAgain() async throws {
        let (_, engine) = try setup(), count = SpeechCount()
        await engine.setSpeechHandler { operation, _, applet in
            XCTAssertEqual(applet, "speech-fixture", "Speech must use the installed package ID")
            return await count.respond(operation)
        }
        try await engine.install(package())
        let first = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "call-1")
        XCTAssertEqual((first["listen"] as? [String: Any])?["transcript"] as? String, "fixture words")
        _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "call-1")
        let cached = await count.calls; XCTAssertEqual(cached, ["hear", "speak"])
        _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "call-2")
        let fresh = await count.calls; XCTAssertEqual(fresh, ["hear", "speak", "hear", "speak"])
    }
    func testCallerWithoutSpeechGrantCannotEnterTheHandler() async throws {
        let (_, engine) = try setup(), count = SpeechCount()
        await engine.setSpeechHandler { operation, _, _ in await count.respond(operation) }; try await engine.install(package())
        do { _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], authorizeSpeech: { _ in throw DeviceAPIError(403, "agent_not_allowed", "Missing speech grant") }); XCTFail() }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_not_allowed") }
        let calls = await count.calls; XCTAssertTrue(calls.isEmpty)
    }
    func testInterruptedSpeechCannotRunAgainWithoutOwnerReview() async throws {
        let (_, engine) = try setup(), count = SpeechCount()
        await engine.setSpeechHandler { operation, _, _ in _ = await count.respond(operation); throw URLError(.networkConnectionLost) }; try await engine.install(package())
        do { _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:]); XCTFail() } catch { }
        do { _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:]); XCTFail() }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "action_outcome_unknown") }
        let calls = await count.calls; XCTAssertEqual(calls, ["hear"])
    }
    func testCloudSpeechEnforcesPerRunAndDailyLimitsWithoutRepeatingCachedWork() async throws {
        let (_, engine) = try setup(), count = SpeechCount()
        await engine.setSpeechHandler { operation, _, _ in await count.respond(operation) }
        var definition = try JSONSerialization.jsonObject(with: package()) as! [String: Any], processing = definition["processing"] as! [String: Any]
        var flow: [String: Any] = ["id": "turn", "trigger": ["type": "called"], "max_paid_per_day": 1,
            "steps": (0..<5).map { ["id": "say\($0)", "type": "speak", "text": "Fixture", "allow_cloud": true] as [String: Any] }]
        processing["flows"] = [flow]; definition["processing"] = processing
        do { try await engine.install(JSONSerialization.data(withJSONObject: definition)); XCTFail("Five paid speech steps accepted") } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 422) }
        flow["steps"] = [["id": "say", "type": "speak", "text": "Fixture", "allow_cloud": true]]
        processing["flows"] = [flow]; definition["processing"] = processing
        try await engine.install(JSONSerialization.data(withJSONObject: definition))
        _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "first")
        _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "first")
        do { _ = try await engine.run(applet: "speech-fixture", flow: "turn", with: [:], invocationID: "second"); XCTFail("Daily speech cap bypassed") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "paid_cap") }
        let calls = await count.calls; XCTAssertEqual(calls, ["speak"])
    }

}
