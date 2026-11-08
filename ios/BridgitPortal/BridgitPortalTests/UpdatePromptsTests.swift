import XCTest
@testable import Bridgit_Portal

@MainActor
final class UpdatePromptsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testNewerComparesBuildNumbers() {
        XCTAssertTrue(UpdatePrompts.newer("2611072030", than: "2611071200"))
        XCTAssertFalse(UpdatePrompts.newer("2611071200", than: "2611071200"))
        XCTAssertFalse(UpdatePrompts.newer("2611072030", than: "1.0"))
        XCTAssertFalse(UpdatePrompts.newer("-1", than: "1"))
    }

    func testDecide() {
        let i = "2611071200", l = "2611072030"
        XCTAssertEqual(UpdatePrompts.decide(installed: l, latest: l, enabled: true, force: true, laterUntil: nil, now: now), "up_to_date")
        XCTAssertEqual(UpdatePrompts.decide(installed: i, latest: l, enabled: true, force: false, laterUntil: nil, now: now), "prompted")
        XCTAssertEqual(UpdatePrompts.decide(installed: i, latest: l, enabled: false, force: false, laterUntil: nil, now: now), "off")
        XCTAssertEqual(UpdatePrompts.decide(installed: i, latest: l, enabled: true, force: false, laterUntil: now.addingTimeInterval(60), now: now), "update_ready")
        XCTAssertEqual(UpdatePrompts.decide(installed: i, latest: l, enabled: true, force: false, laterUntil: now.addingTimeInterval(-60), now: now), "prompted")
        // An agent's request prompts even when the switch is off or the owner chose Later.
        XCTAssertEqual(UpdatePrompts.decide(installed: i, latest: l, enabled: false, force: true, laterUntil: now.addingTimeInterval(60), now: now), "prompted")
    }

    func testManifest() {
        let ok = Data(#"{"bundle_id":"cc.bridgit.portal","version":"0.1.0","build":"2611072030"}"#.utf8)
        XCTAssertEqual(UpdatePrompts.parseManifest(ok, bundleId: "cc.bridgit.portal"), "2611072030")
        XCTAssertNil(UpdatePrompts.parseManifest(ok, bundleId: "cc.other"))
        XCTAssertNil(UpdatePrompts.parseManifest(Data(#"{"bundle_id":"cc.bridgit.portal","build":"1.0"}"#.utf8), bundleId: "cc.bridgit.portal"))
        XCTAssertNil(UpdatePrompts.parseManifest(Data("nope".utf8), bundleId: "cc.bridgit.portal"))
    }

    func testRequestAppliesAndReportsOnlyChanges() async {
        let defaults = UserDefaults(suiteName: "UpdatePromptsTests")!
        defaults.removePersistentDomain(forName: "UpdatePromptsTests")
        let u = UpdatePrompts(defaults: defaults, arguments: [])
        XCTAssertTrue(u.enabled)
        let id = await u.apply(["id": "pur_1", "prompts": false, "prompt_now": false])
        XCTAssertEqual(id, "pur_1")
        XCTAssertFalse(u.enabled)
        let first = u.report(for: "relay:a", ack: id)
        XCTAssertEqual(first?["ack"] as? String, "pur_1")
        XCTAssertEqual(first?["prompts_enabled"] as? Bool, false)
        XCTAssertNil(u.report(for: "relay:a", ack: nil), "unchanged state is not sent again")
        XCTAssertNotNil(u.report(for: "relay:b", ack: nil), "each phone gets its own report")
        u.forgetReport(for: "relay:a")
        XCTAssertNotNil(u.report(for: "relay:a", ack: nil))
        let none = await u.apply(nil)
        XCTAssertNil(none)
        u.setEnabled(true)
        UserDefaults.standard.removeObject(forKey: "update-prompts")
    }

    func testPreviewPromptsForANewerBuild() async {
        let u = UpdatePrompts(arguments: ["-updatePromptPreview"])
        XCTAssertTrue(u.supported)
        await u.check(force: true)
        XCTAssertEqual(u.lastResult, "prompted")
        XCTAssertTrue(u.showPrompt)
        u.later()
        XCTAssertFalse(u.showPrompt)
        XCTAssertEqual(u.lastResult, "later")
        UserDefaults.standard.removeObject(forKey: "update-later")
    }
}
