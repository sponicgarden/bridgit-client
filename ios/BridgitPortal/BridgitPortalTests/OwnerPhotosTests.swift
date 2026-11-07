import UIKit
import XCTest
@testable import Bridgit_Portal

/// The Camera tab's wire format (POST /v1/portal/photo, GET /v1/portal/photo/targets) and photo shrinking.
final class OwnerPhotosTests: XCTestCase {
    func testTargetsParse() {
        let obj: [String: Any] = ["targets": [["id": "ag1", "name": "Kitchen cleanliness", "watches": [["watch_id": "w1", "label": "Kitchen cleanliness", "kind": "check"], ["watch_id": "w2", "label": "People", "kind": "people"]]],
                                              ["name": "no id"]],
                                  "chosen": ["ag1"]]
        let r = OwnerPhotos.parseTargets(obj)
        XCTAssertEqual(r.targets, [PhotoTarget(id: "ag1", name: "Kitchen cleanliness", watches: ["Kitchen cleanliness", "People"])])
        XCTAssertEqual(r.chosen, ["ag1"])
        XCTAssertTrue(OwnerPhotos.parseTargets([:]).targets.isEmpty)
    }

    func testResultsHidePeopleUnlessAlone() {
        let obj: [String: Any] = ["targets": [
            ["id": "ag1", "name": "Kitchen cleanliness", "checks": [["status": "answered", "summary": "state: messy"], ["status": "people_checked", "summary": "Looked for people."]]],
            ["id": "ag2", "name": "Who's home", "checks": [["status": "people_checked", "summary": "Looked for people."]]],
            ["id": "ag3", "name": "Plants", "checks": [["status": "skipped", "summary": "The watch is paused."]]],
            ["id": "ag4", "name": "Hall", "checks": [["kind": "check", "status": "answered", "summary": "door: open"], ["kind": "people", "status": "skipped", "summary": "People spotting is off."]]],
        ]]
        XCTAssertEqual(OwnerPhotos.parseResults(obj).map(\.text),
                       ["Kitchen cleanliness: state: messy", "Who's home: Looked for people.", "Plants: Not checked: The watch is paused.", "Hall: door: open"])
    }

    func testBodyAndShrink() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4000, height: 3000)).image { c in UIColor.red.setFill(); c.fill(CGRect(x: 0, y: 0, width: 4000, height: 3000)) }
        let jpeg = try XCTUnwrap(OwnerPhotos.shrink(image))
        let back = try XCTUnwrap(UIImage(data: jpeg))
        XCTAssertEqual(max(back.size.width * back.scale, back.size.height * back.scale), 1280)
        let b = OwnerPhotos.body(jpeg: jpeg, front: true, targets: [])
        XCTAssertEqual(b["camera"] as? String, "front")
        XCTAssertNil(b["targets"])
        XCTAssertEqual(Data(base64Encoded: b["data_base64"] as? String ?? ""), jpeg)
        XCTAssertTrue(JSONSerialization.isValidJSONObject(OwnerPhotos.body(jpeg: jpeg, front: false, targets: ["ag1"])))
        // Far under the relay's 2 MiB body limit.
        XCTAssertLessThan((b["data_base64"] as? String ?? "").utf8.count, 1_000_000)
    }
}
