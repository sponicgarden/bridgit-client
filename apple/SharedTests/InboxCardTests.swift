import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

final class InboxCardTests: XCTestCase {
    func testSeenNoticeIsOpenButBelongsInCompleted() {
        let notice = InboxCard(body: ["kind": "notice", "status": "seen"])
        XCTAssertTrue(notice.waiting)
        XCTAssertFalse(notice.needsAttention)
        XCTAssertEqual(notice.statusLine, "Seen")
    }

    func testExpiredQuestionCanStillBeAnswered() {
        for kind in ["question", "html"] {
            let question = InboxCard(body: ["kind": kind, "status": "expired"])
            XCTAssertTrue(question.waiting)
            XCTAssertTrue(question.needsAttention)
            XCTAssertEqual(question.statusLine, "Expired, you can still answer")
        }
        let notice = InboxCard(body: ["kind": "notice", "status": "expired"])
        XCTAssertFalse(notice.waiting)
        XCTAssertEqual(notice.statusLine, "Missed")
    }

    func testAnswerLineUsesSelectedLabelAndHTMLResponse() {
        let question = InboxCard(body: ["kind": "question", "status": "answered",
                                        "controls": [["id": "later", "label": "Ask later"]],
                                        "answer": ["button": "later"]])
        XCTAssertEqual(question.statusLine, "✓ You: Ask later")
        let card = InboxCard(body: ["kind": "html", "status": "answered",
                                    "answer": ["data": ["button": "Ready"]]])
        XCTAssertEqual(card.statusLine, "✓ You: Ready")
        XCTAssertFalse(card.needsAttention)
    }
}
