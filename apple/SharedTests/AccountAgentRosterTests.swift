import Foundation
import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

final class AccountAgentRosterTests: XCTestCase {
    private func roster(_ json: String) throws -> AccountAgentRoster {
        AccountAgentRoster(phone: try JSONDecoder().decode(Phone.self, from: Data(json.utf8)))
    }
    func testAllNamesSurviveLimitedDetail() throws {
        let names = (1...25).map { "Agent \($0)" }
        let details: [[String: Any]] = names.prefix(20).map { ["name": $0, "paused": false] }
        let data = try JSONSerialization.data(withJSONObject: ["phone": "target", "status_at": 1000, "status": ["agents": names, "agent_status": details]])
        let result = AccountAgentRoster(phone: try JSONDecoder().decode(Phone.self, from: data))
        XCTAssertEqual(result.current.map(\.name), names)
        XCTAssertEqual(result.current[19].paused, false)
        XCTAssertNil(result.current[20].paused)
        XCTAssertEqual(result.current[20].state, "Status not reported")
        XCTAssertTrue(result.reported)
        XCTAssertEqual(result.reportedAt, 1000)
    }
    func testExplicitEmptyNeverRevivesHistoryOrStaleDetails() throws {
        let result = try roster(#"{"phone":"target","status":{"agents":[],"agent_status":[{"name":"Removed","paused":false}]},"agent_history":[{"name":"Removed","last_used_at":123}]}"#)
        XCTAssertTrue(result.reported)
        XCTAssertTrue(result.current.isEmpty)
        XCTAssertEqual(result.history.map(\.name), ["Removed"])
        XCTAssertEqual(result.emptyText, "No current agents reported.")
    }
    func testMissingRosterKeepsHistorySeparate() throws {
        let result = try roster(#"{"phone":"target","agent_history":[{"name":"Historical","last_used_at":123}]}"#)
        XCTAssertFalse(result.reported)
        XCTAssertTrue(result.current.isEmpty)
        XCTAssertEqual(result.history.map(\.name), ["Historical"])
        XCTAssertEqual(result.emptyText, "Current agent roster not reported.")
        XCTAssertTrue(result.sourceText.contains("time unavailable"))
    }
    func testPartialDetailsDoNotClaimCompleteRoster() throws {
        let result = try roster(#"{"phone":"target","status":{"agent_status":[{"name":"Known","paused":true},{"name":"Unknown"}]},"agent_history":[{"name":"Known"},{"name":"Removed"}]}"#)
        XCTAssertFalse(result.reported)
        XCTAssertEqual(result.current.map(\.name), ["Known", "Unknown"])
        XCTAssertEqual(result.current.first?.state, "Paused")
        XCTAssertEqual(result.current.last?.state, "Status not reported")
        XCTAssertEqual(result.history.map(\.name), ["Removed"])
    }
    func testAnotherTargetNeverContributesNames() throws {
        let first = try roster(#"{"phone":"first","status":{"agents":["First"]}}"#)
        let second = try roster(#"{"phone":"second","status":{"agents":["Second"]}}"#)
        XCTAssertEqual(first.current.map(\.name), ["First"])
        XCTAssertEqual(second.current.map(\.name), ["Second"])
    }
}
