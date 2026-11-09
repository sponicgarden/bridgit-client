import BridgitCore
import XCTest
@testable import Bridgit_macOS

private actor SpeechRegistrationGate {
    var waiter: CheckedContinuation<Void, Never>?
    var ready = false
    func wait() async { if ready { return }; await withCheckedContinuation { waiter = $0 } }
    func release() { ready = true; waiter?.resume(); waiter = nil }
    func isWaiting() -> Bool { waiter != nil }
}
final class SpeechOwnerTests: XCTestCase {
    @MainActor func testBackgroundDuringRegistrationCannotCreateALateSpeechGrant() async throws {
        let gate = SpeechRegistrationGate(), client = InstallClient(signer: try .ephemeral())
        let speech = LocalSpeechModel(registration: { await gate.wait(); return client })
        let enable = Task { await speech.enable(applet: "fixture", hearing: false) }
        while !(await gate.isWaiting()) { await Task.yield() }
        speech.foreground = false
        await gate.release(); await enable.value
        XCTAssertTrue(speech.enabled.isEmpty); XCTAssertTrue(speech.cloudAllowed.isEmpty)
    }
    @MainActor func testCloudPermissionIsScopedToTheChosenApplet() async throws {
        let client = InstallClient(signer: try .ephemeral()), speech = LocalSpeechModel(registration: { client })
        await speech.enable(applet: "first", hearing: false); await speech.enable(applet: "second", hearing: false)
        speech.setCloud(applet: "first", allowed: true)
        XCTAssertTrue(speech.cloudAllowed.contains("first")); XCTAssertFalse(speech.cloudAllowed.contains("second"))
        speech.disable(applet: "first")
        XCTAssertFalse(speech.cloudAllowed.contains("first")); XCTAssertTrue(speech.enabled.contains("second"))
    }
    @MainActor func testSpeechGrantsSurviveSuspensionAndRelaunchUntilRevoked() async throws {
        let suite = "bridgit-speech-grants-" + UUID().uuidString, saved = UserDefaults(suiteName: suite)!
        defer { saved.removePersistentDomain(forName: suite) }
        let client = InstallClient(signer: try .ephemeral())
        let speech = LocalSpeechModel(registration: { client }, settings: saved)
        await speech.enable(applet: "first", hearing: false); speech.setCloud(applet: "first", allowed: true)
        speech.foreground = false
        XCTAssertTrue(speech.enabled.contains("first")); XCTAssertTrue(speech.cloudAllowed.contains("first"))
        let reopened = LocalSpeechModel(registration: { client }, settings: saved)
        XCTAssertTrue(reopened.enabled.contains("first")); XCTAssertTrue(reopened.cloudAllowed.contains("first"))
        reopened.disable(applet: "first")
        let revoked = LocalSpeechModel(registration: { client }, settings: saved)
        XCTAssertTrue(revoked.enabled.isEmpty); XCTAssertTrue(revoked.cloudAllowed.isEmpty)
    }

}
