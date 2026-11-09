import Foundation
import XCTest
@testable import BridgitCore

final class FirstRunProgressTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "FirstRunTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        return defaults
    }
    func testAllFourStepsBackAndSkipDoNotFinish() {
        let store = defaults()
        var progress = FirstRunProgress(defaults: store, hasOwnerData: false)
        XCTAssertTrue(progress.isRequired)
        XCTAssertEqual(progress.step, .introduction)
        XCTAssertFalse(progress.finish(.diagnostic))
        progress.back(); XCTAssertEqual(progress.step, .introduction)
        progress.next(); XCTAssertEqual(progress.step, .capabilities)
        progress.next(); XCTAssertEqual(progress.step, .privacy)
        progress.next(); XCTAssertEqual(progress.step, .warning)
        progress.back(); XCTAssertEqual(progress.step, .privacy)
        progress.back(); XCTAssertEqual(progress.step, .capabilities)
        progress.back(); XCTAssertEqual(progress.step, .introduction)
        progress.skip(); XCTAssertEqual(progress.step, .warning)
        XCTAssertTrue(progress.isRequired); XCTAssertNil(progress.completedAction)
        XCTAssertNil(store.string(forKey: "firstRun.v1.action"))
    }
    func testBothChosenActionsPersistExactlyOnce() {
        for action in [FirstRunProgress.Action.diagnostic, .addControlAgent] {
            let store = defaults()
            var progress = FirstRunProgress(defaults: store, hasOwnerData: false)
            progress.skip(); XCTAssertTrue(progress.finish(action))
            XCTAssertFalse(progress.finish(.diagnostic))
            let reopened = FirstRunProgress(defaults: store, hasOwnerData: true)
            XCTAssertFalse(reopened.isRequired); XCTAssertEqual(reopened.completedAction, action)
            XCTAssertFalse(reopened.returningInstall)
        }
    }
    func testEmptyDatabaseCreatedDuringIncompleteLaunchDoesNotBecomeReturning() {
        let store = defaults()
        var progress = FirstRunProgress(defaults: store, hasOwnerData: false)
        progress.next()
        let reopened = FirstRunProgress(defaults: store, hasOwnerData: true)
        XCTAssertTrue(reopened.isRequired); XCTAssertEqual(reopened.step, .capabilities)
    }
    func testMigrationPreservesOwnerDataWithoutInventingCompletion() {
        let store = defaults(); store.set("existing", forKey: "owner.data")
        var progress = FirstRunProgress(defaults: store, hasOwnerData: true)
        XCTAssertFalse(progress.isRequired); XCTAssertTrue(progress.returningInstall)
        progress.skip(); XCTAssertFalse(progress.finish(.addControlAgent))
        XCTAssertNil(progress.completedAction)
        XCTAssertEqual(store.string(forKey: "owner.data"), "existing")
        XCTAssertFalse(FirstRunProgress(defaults: store, hasOwnerData: false).isRequired)
    }
    func testAgentCreatedOutsideIntroductionYieldsWithoutInventingChosenAction() {
        let store = defaults()
        var progress = FirstRunProgress(defaults: store, hasOwnerData: false)
        progress.recognizeSavedAgent()
        XCTAssertFalse(progress.isRequired)
        XCTAssertNil(progress.completedAction)
        XCTAssertNil(store.string(forKey: "firstRun.v1.action"))
        XCTAssertFalse(FirstRunProgress(defaults: store, hasOwnerData: false).isRequired)
    }

    func testUIKitSplitRestorationDoesNotTurnFreshInstallIntoReturningOwner() {
        let framework = ["com.apple.UIKit.UISplitViewController.Root", "NSLanguages", "AppleLanguages", "firstRun.v1.initialized"]
        XCTAssertFalse(FirstRunProgress.hasOwnerPreferences(framework))
        for owner in ["appearance", "host.enabled", "viewer.selection", "attached-phone"] {
            XCTAssertTrue(FirstRunProgress.hasOwnerPreferences(framework + [owner]), owner)
        }
        let store = defaults()
        XCTAssertTrue(FirstRunProgress(defaults: store, hasOwnerData: FirstRunProgress.hasOwnerPreferences(framework)).isRequired)
    }

}
