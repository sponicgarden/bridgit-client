import Foundation
import XCTest
@testable import BridgitCore

final class RelayPermissionMessageTests: XCTestCase {
    func testDeniedResponseDoesNotSpliceServerSentenceIntoReadObject() throws {
        let data = try JSONSerialization.data(withJSONObject: ["response": ["status": 403, "body": ["message": "Activity is unavailable."]]])
        XCTAssertThrowsError(try RelayClient.decodeCompletion(status: 200, data: data)) { error in
            guard case RelayError.notAllowed(let detail) = error else { return XCTFail("Expected permission denial") }
            XCTAssertEqual(detail, "Activity is unavailable.")
            XCTAssertEqual(error.localizedDescription, "This pairing doesn't have permission for this request. Review its access on the linked device.")
        }
    }
}
