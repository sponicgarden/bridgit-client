import XCTest
@testable import BridgitAppletRuntime
final class AppletPracticesTests: XCTestCase {
 func testSharedContractFixtures() throws {
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
  let fixture=try JSONDecoder().decode(JSONValue.self,from:Data(contentsOf:root.appendingPathComponent("site-content/content/builder/practice-fixtures.json")))
  guard case .array(let cases)?=fixture["cases"] else { return XCTFail("Missing fixtures") }
  for row in cases {
   let candidate=try XCTUnwrap(row["candidate"])
   if row["error"] != nil {
    XCTAssertThrowsError(try AppletPractices.requireChange(candidate,previous:row["previous"]))
   } else {
    let report=try AppletPractices.report(candidate,previous:row["previous"])
    XCTAssertEqual(report["valid"],row["valid"],row["name"]?.text ?? "")
    if case .array(let results)?=report["results"],let statuses=row["statuses"]?.fields {
     for (id,status) in statuses { XCTAssertEqual(results.first(where:{$0["practice"]?.text==id})?["status"],status,id) }
    }
   }
  }
 }
}
