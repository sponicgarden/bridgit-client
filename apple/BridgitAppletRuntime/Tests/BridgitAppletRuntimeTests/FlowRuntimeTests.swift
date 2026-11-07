import XCTest
@testable import BridgitAppletRuntime

private actor Log { var calls = [String](); func add(_ s: String) { calls.append(s) } }
private struct Host: AppletHost {
    let log: Log
    let supportedSteps: Set<String> = ["ask_model", "notice", "event"]
    func authorize(instance: String) async throws {}
    func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue {
        await log.add(type)
        return .object(["answers": .object(["match": .string("yes")])])
    }
}
final class FlowRuntimeTests: XCTestCase {
    func steps(_ text: String) throws -> [FlowStep] { try JSONDecoder().decode([JSONValue].self, from: Data(text.utf8)).map(FlowStep.init) }
    func testModelResultControlsLaterAction() async throws {
        let log = Log(), runtime = FlowRuntime(host: Host(log: log))
        let plan = try steps(#"[{"id":"look","type":"ask_model","model":"glm"},{"type":"notice","only_if":{"step":"look.answers.match","is":"no"}},{"type":"notice","only_if":{"step":"look.answers.match","is":"yes"},"text":{"$step":"look.answers.match"}}]"#)
        let result = try await runtime.run(instance: "test", steps: plan)
        XCTAssertEqual(result.skipped, [1])
        let calls = await log.calls; XCTAssertEqual(calls, ["ask_model", "notice"])
    }
    func testUnsupportedHostPreventsAllSideEffects() async throws {
        let log = Log(), runtime = FlowRuntime(host: Host(log: log))
        do { _ = try await runtime.run(instance: "test", steps: steps(#"[{"type":"notice"},{"type":"photo"}]"#)); XCTFail("Expected unsupported capability") }
        catch { XCTAssertEqual(error as? AppletRuntimeError, .unavailable("photo")) }
        let calls = await log.calls; XCTAssertTrue(calls.isEmpty)
    }
    func testStopPreventsLaterStep() async throws {
        let log = Log(), runtime = FlowRuntime(host: Host(log: log))
        _ = try await runtime.run(instance: "test", steps: steps(#"[{"type":"stop"},{"type":"notice"}]"#))
        let calls = await log.calls; XCTAssertTrue(calls.isEmpty)
    }
    func testUnknownConditionFailsClosed() async throws {
        let runtime = FlowRuntime(host: Host(log: Log()))
        do { _ = try await runtime.run(instance: "test", steps: steps(#"[{"type":"notice","only_if":{"trigger":"x","execute":"anything"}}]"#)); XCTFail("Expected invalid condition") }
        catch { XCTAssertEqual(error as? AppletRuntimeError, .invalid("Unsupported condition test")) }
    }
}
