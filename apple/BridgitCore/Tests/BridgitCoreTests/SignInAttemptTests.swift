import Foundation
import XCTest
@testable import BridgitCore

final class SignInAttemptTests: XCTestCase {
    private func callback(_ state: String, extra: String = "") -> URL {
        URL(string: "https://bridgit.cc/app-signin/complete/ios#state=" + state + "&session=bws_abcdefghijklmnopqrstuvwxyz012345&email=a%40example.test" + extra)!
    }
    func testOnlyCurrentInitiatedCallbackIsConsumedOnce() throws {
        var attempt = SignInAttempt()
        XCTAssertNil(attempt.consume(callback(String(repeating: "a", count: 64))))
        let start = attempt.begin(platform: "ios")
        let first = try XCTUnwrap(attempt.state)
        XCTAssertEqual(URLComponents(url: start, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "state" })?.value, first)
        XCTAssertNil(attempt.consume(callback(String(repeating: "b", count: 64))))
        XCTAssertEqual(attempt.consume(callback(first))?.email, "a@example.test")
        XCTAssertNil(attempt.consume(callback(first)))
        _ = attempt.begin(platform: "macos"); let next = try XCTUnwrap(attempt.state)
        XCTAssertNotEqual(first, next)
        XCTAssertNil(attempt.consume(callback(first)))
        attempt.cancel(); XCTAssertNil(attempt.consume(callback(next)))
    }
    func testCallbackRejectsAmbiguousOrExposedCredentials() {
        let state = String(repeating: "a", count: 64)
        for extra in ["&state=" + state, "&session=bws_abcdefghijklmnopqrstuvwxyz012345", "&unknown=x"] {
            XCTAssertNil(SignInAttempt.parse(callback(state, extra: extra)))
        }
        XCTAssertNil(SignInAttempt.parse(URL(string: "https://bridgit.cc/app-signin/complete/ios?state="+state+"&session=bws_abcdefghijklmnopqrstuvwxyz012345")!))
        XCTAssertNil(SignInAttempt.parse(URL(string: "bridgitportal://signin#state="+state+"&session=bws_abcdefghijklmnopqrstuvwxyz012345")!))
        XCTAssertNil(SignInAttempt.parse(callback("bad")))
    }
}
