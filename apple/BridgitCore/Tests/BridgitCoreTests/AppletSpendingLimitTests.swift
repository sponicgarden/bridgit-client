import XCTest
@testable import BridgitCore

final class AppletSpendingLimitTests: XCTestCase {
    func testDefaultsAndExplicitLimits() throws {
        XCTAssertEqual(try AppletSpendingLimit.limits(for: [:]).map(\.usd), [1, 10])
        let pet = try AppletSpendingLimit.limits(for: ["runtime": ["adapter": "android.dog.v1"]])
        XCTAssertEqual(pet.map(\.usd), [3])
        XCTAssertEqual(pet.first?.check_at, [0.5])
        let value: [String: Any] = ["limits": [["usd": 1.2, "per": "hour", "check_at": 0.75]]]
        let limits = try AppletSpendingLimit.limits(for: ["budget": value])
        XCTAssertEqual(limits.first?.id, "l1")
        XCTAssertEqual(limits.first?.check_at, [0.75])
        XCTAssertEqual(limits.first?.usd, 1.2)
    }

    func testMalformedBudgetsFailBeforeUse() {
        let invalid: [Any] = [[], ["day_usd": true], ["day_usd": 2, "month_usd": 1],
                              [["per": "day", "usd": 0]], [["per": "year", "usd": 10]],
                              [["per": "day", "usd": 4, "check_at": [0.5, false]]],
                              [["id": "duplicate", "usd": 1, "per": "week"], ["id": "duplicate", "usd": 1, "per": "day"]]]
        for value in invalid {
            XCTAssertThrowsError(try AppletSpendingLimit.limits(for: ["budget": value]))
        }
    }
}
