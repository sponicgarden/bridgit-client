import BridgitAppletRuntime
import CryptoKit
import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

final class BuildInstructionsTests: XCTestCase {
    @MainActor func testPayloadMatchesPinnedAndroid3347Prompt() {
        // Android applets_build_share_text after expanding ApiDocs.APPLET_URL.
        let bytes = Data(BuildInstructionsView.prompt.utf8)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "ff11e73190ac6879207b69cd4cb94f76364eb416d7e51df058e2a3de9f079082")
    }
}


@MainActor final class MaintainedHomeCatalogTests: XCTestCase {
    func testFiveTrustedBundledReviewsHavePinnedOrderAndRealDefinitions() throws {
        XCTAssertEqual(HomeBundledDefinition.all.map(\.id), ["dog-monitor", "a-sorta-fairytale-about-me", "chef-helper", "insurance-inventory", "exercise-check"])
        XCTAssertEqual(HomeBundledDefinition.all.first?.name, "Pet monitor")
        XCTAssertEqual(HomeBundledDefinition.available(installedKeys: ["chef-helper"]).count, 4)
        XCTAssertEqual(HomeBundledDefinition.available(installedKeys: ["tpl_same_name_fork"]).count, 5)
        for item in HomeBundledDefinition.all {
            let definition = try item.definition()
            XCTAssertEqual(definition["id"] as? String, item.id)
            XCTAssertNotNil(definition["version"] as? String)
            if !item.isPet { XCTAssertFalse(HomeBundledDefinition.outstandingControls(in: definition).isEmpty, "Missing card controls must be disclosed from the real package, not just synthetic DSL fixtures") }
            XCTAssertEqual((definition["permissions"] as? [String: Any])?["automatic_grants"] as? Bool, false)
        }
        let pet = try XCTUnwrap(HomeBundledDefinition.all.first).definition()
        let required = (pet["permissions"] as? [String: Any])?["required"] as? [String] ?? []
        XCTAssertTrue(required.contains("watches")); XCTAssertTrue(required.contains("sounds"))
    }
    func testSpendingDisclosureUsesSelectedEditionAndRejectsInvalidLimits() throws {
        let story = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "a-sorta-fairytale-about-me" })
        var definition = try story.definition()
        let declared = try XCTUnwrap(AppletReviewDetails.spendingSummary(for: definition, paidFlow: false))
        XCTAssertTrue(declared.contains("$2 per day"))
        XCTAssertTrue(declared.contains("$10 per month"))
        definition["budget"] = ["day_usd": -1]
        XCTAssertThrowsError(try AppletReviewDetails.spendingSummary(for: definition, paidFlow: false))
        XCTAssertNil(try AppletReviewDetails.spendingSummary(for: [:], paidFlow: false))
        let defaults = try XCTUnwrap(AppletReviewDetails.spendingSummary(for: [:], paidFlow: true))
        XCTAssertTrue(defaults.contains("$1 per day"))
    }
    func testSinglePaidWorkflowDisclosesDefaultsWithoutExplicitBudget() throws {
        let package: [String: Any] = [
            "processing": ["workflow": ["name": "Review a photo", "steps": [["type": "appraise", "table": "items"]]]],
            "permissions": ["required": ["camera", "applet_data", "workflows"]]
        ]
        let details = try JSONDecoder().decode(AppletReviewDetails.self, from: JSONSerialization.data(withJSONObject: package))
        XCTAssertTrue(details.hasPaidFlow)
        XCTAssertEqual(details.processing?.flows?.first?.id, "main")
        let disclosure = try XCTUnwrap(AppletReviewDetails.spendingSummary(for: package, paidFlow: details.hasPaidFlow))
        XCTAssertTrue(disclosure.contains("$1 per day"))
        XCTAssertTrue(disclosure.contains("$10 per month"))
    }
    func testAssetCardReviewDescribesPartialCapabilitiesForAnyPublisher() throws {
        var definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "insurance-inventory" }).definition()
        var ui = try XCTUnwrap(definition["ui"] as? [String: Any])
        var card = try XCTUnwrap(ui["card"] as? [String: Any])
        card["asset"] = "third-party.html"; ui["card"] = card; definition["ui"] = ui
        XCTAssertEqual(HomeBundledDefinition.outstandingControls(in: definition), ["Cards can use declared tables and request reviewed captures where authorized. Other actions may not be available on this device."])
    }
    func testOfflineMetadataCannotInventRegistryPinsOrPromoteSameNameFork() throws {
        let chef = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "chef-helper" })
        XCTAssertNil(chef.registryEntry(in: nil))
        func page(_ key: String, scope: String) throws -> CatalogPage {
            try JSONDecoder().decode(CatalogPage.self, from: JSONSerialization.data(withJSONObject: ["format": "bridgit-applet-catalog/1", "scope": scope, "applets": [["key": key, "id": "chef-helper", "revision": 4, "name": "Chef’s Helper", "summary": "Synthetic catalog"]]]))
        }
        XCTAssertNil(chef.registryEntry(in: try page("tpl_unrelated_publisher", scope: "public")))
        XCTAssertNil(chef.registryEntry(in: try page("chef-helper", scope: "owned")))
        XCTAssertEqual(chef.registryEntry(in: try page("chef-helper", scope: "public"))?.revision, 4)
        let wrong = try JSONSerialization.data(withJSONObject: ["format": "bridgit-template/1", "kind": "applet", "id": "other"])
        XCTAssertThrowsError(try HomeBundledDefinition.read(wrong, expectedID: chef.id))
        XCTAssertThrowsError(try HomeBundledDefinition.read(Data(repeating: 0, count: 131073), expectedID: chef.id))
    }
    func testOutstandingControlsAndCreationStayWithinMaintainedOwnerBoundary() throws {
        var definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "insurance-inventory" }).definition()
        let pieces = try XCTUnwrap(((definition["ui"] as? [String: Any])?["card"] as? [String: Any])?["pieces"] as? [[String: Any]])
        let supported = try AppletPackage(data: JSONSerialization.data(withJSONObject: definition))
        XCTAssertEqual(supported.cameraItems.count, 1)
        XCTAssertEqual(supported.csvButtons.count, 1)
        let pending = HomeBundledDefinition.outstandingControls(in: definition)
        for label in supported.cameraItems.compactMap({ $0["label"]?.text }) + supported.csvButtons.map(\.label) {
            XCTAssertFalse(pending.contains(label), label)
        }
        var ui = definition["ui"] as! [String: Any]
        var card = ui["card"] as! [String: Any]
        card["pieces"] = [["type": "buttons", "items": [
            ["label": "Invalid camera", "do": ["camera": ["table": "photos", "lens": "telephoto"]]],
            ["label": "Invalid CSV", "do": ["step": ["type": "share", "as": "csv", "table": "missing"]]],
            ["label": "Share PDF", "do": ["step": ["type": "share", "as": "pdf"]]]
        ]]]
        ui["card"] = card; definition["ui"] = ui
        XCTAssertEqual(HomeBundledDefinition.outstandingControls(in: definition), ["Invalid camera", "Invalid CSV"])
        card["pieces"] = pieces.map { piece in
            var conditional = piece; conditional["only_if"] = ["unsupported": true]; return conditional
        }
        ui["card"] = card; definition["ui"] = ui
        for label in supported.cameraItems.compactMap({ $0["label"]?.text }) + supported.csvButtons.map(\.label) {
            XCTAssertTrue(HomeBundledDefinition.outstandingControls(in: definition).contains(label), label)
        }
        let prompt = BuildInstructionDelivery.creationPrompt(device: "Synthetic Mac", maintainedLibrary: true)
        XCTAssertTrue(prompt.contains("Synthetic Mac"))
        XCTAssertTrue(prompt.contains("owner-authorized registry edition"))
        XCTAssertTrue(prompt.contains("exact edition key and revision"))
        XCTAssertTrue(prompt.contains("Do not create a legacy local installation"))
        XCTAssertTrue(prompt.contains("missing runtime support or publishing authority"))
        XCTAssertFalse(BuildInstructionDelivery.creationPrompt(device: "Synthetic", maintainedLibrary: false).contains("maintained applet library"))
    }
}
