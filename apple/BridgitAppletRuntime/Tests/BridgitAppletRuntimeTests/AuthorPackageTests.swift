import XCTest
import ImageIO
@testable import BridgitAppletRuntime

private struct TrialHost: AppletHost {
    let supportedSteps: Set<String> = ["ask_owner", "record", "photo", "ask_model", "notice"]
    func authorize(instance: String) async throws {}
    func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue {
        switch type {
        case "ask_owner": return .object(["choice": .string("Dry")])
        case "record": return try XCTUnwrap(arguments["row"])
        case "photo":
            XCTAssertEqual(arguments["camera"], .string("front"))
            return .object(["photo": .string("test-photo")])
        case "ask_model":
            XCTAssertEqual(arguments["photo"], .string("test-photo"))
            XCTAssertEqual(arguments["model"], .string("clef"))
            return .object(["answers": .object(["lamp": .string("lit")])])
        case "notice":
            XCTAssertEqual(arguments["text"], .array([.string("The photo check returned: "), .string("lit"), .string(".")]))
            return .null
        default: throw AppletRuntimeError.unavailable(type)
        }
    }
}

final class AuthorPackageTests: XCTestCase {
    private func definition(_ author: String) throws -> Data {
        // Load the original authoring artifacts directly, outside the client bundle.
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try Data(contentsOf: root.appendingPathComponent("docs/applet-authoring-trials/2026-11-06/" + author + "/applet.json"))
    }

    func testNamedJournalFlowUsesInstallerValuesAndStepResult() async throws {
        let package = try AppletPackage(data: definition("sol"))
        let steps = try package.steps(for: "check", configuration: ["plant": .string("Mint")])
        let result = try await FlowRuntime(host: TrialHost()).run(instance: "journal", steps: steps)
        XCTAssertEqual(result.results["save"], .object(["plant": .string("Mint"), "moisture": .string("Dry")]))
        XCTAssertThrowsError(try package.steps(for: "check", configuration: [:]))
    }

    func testSingleWorkflowCallsSelectedModel() async throws {
        let package = try AppletPackage(data: definition("terra"))
        let steps = try package.steps(for: "main", configuration: ["camera": .string("front")])
        let result = try await FlowRuntime(host: TrialHost()).run(instance: "lamp", steps: steps)
        XCTAssertTrue(result.skipped.isEmpty)
        XCTAssertEqual(result.results["look"]?["answers"]?["lamp"], .string("lit"))
    }

    func testPackageIconReturnsVerifiedPNGBytes() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("docs/applet-authoring-examples/png-icon.json"))
        let package = try AppletPackage(data: data)
        let bytes = try XCTUnwrap(package.iconPNG)
        XCTAssertEqual(bytes.count, 69)
        let image = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
        XCTAssertEqual(CGImageSourceCreateImageAtIndex(image, 0, nil)?.width, 1)
        var broken = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        broken["ui"] = ["icon": "missing.png"]
        XCTAssertThrowsError(try AppletPackage(data: JSONSerialization.data(withJSONObject: broken)))
    }

    func testWatchCardIntegrity() throws {
        let package = try AppletPackage(data: definition("luna/final"))
        XCTAssertEqual(package.id, "luna-pet-activity")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: definition("luna/final")) as? [String: Any])
        var assets = try XCTUnwrap(object["assets"] as? [String: [String: Any]])
        assets["card.html"]?["text"] = "modified"
        object["assets"] = assets
        XCTAssertThrowsError(try AppletPackage(data: JSONSerialization.data(withJSONObject: object))) {
            XCTAssertEqual($0 as? AppletRuntimeError, .invalid("Asset checksum mismatch: card.html"))
        }
    }
}
