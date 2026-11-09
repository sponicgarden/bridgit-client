import XCTest
@testable import BridgitCore

final class HostLanRequestTests: XCTestCase {
    func testDeclaredRoutesAndDefaultDiscovery() throws {
        XCTAssertEqual(try HostLanRequest(.init(method: "GET", path: "/v1/lan/catalog")), .catalog)
        XCTAssertEqual(try HostLanRequest(.init(method: "GET", path: "/v1/lan/discovered")), .remembered)
        XCTAssertEqual(try HostLanRequest(.init(method: "POST", path: "/v1/lan/discover")), .discover(hosts: [], subnet: false))
        XCTAssertEqual(try HostLanRequest(.init(method: "POST", path: "/v1/lan/discover", body: ["hosts": ["192.168.1.2"], "subnet": true])), .discover(hosts: ["192.168.1.2"], subnet: true))
        XCTAssertTrue(JSONSerialization.isValidJSONObject(HostLanRequest.discoverSchema))
    }
    func testCallerCannotSelectScopeInterfacePortsOrRoutedTargets() {
        for body: [String: Any] in [["scope": "other"], ["interface": "en0"], ["ports": [22]], ["hosts": ["8.8.8.8"]], ["hosts": ["router.local"]], ["hosts": ["010.0.0.1"]], ["hosts": [42]], ["hosts": Array(repeating: "192.168.1.2", count: 255)], ["subnet": 1]] {
            XCTAssertThrowsError(try HostLanRequest(.init(method: "POST", path: "/v1/lan/discover", body: body)))
        }
        XCTAssertThrowsError(try HostLanRequest(.init(method: "GET", path: "/v1/lan/discovered", query: ["scope": "other"])))
    }
    func testPassiveLogoRequestsRejectPathsAndAmbiguousFlags() throws {
        for raw: Any in [true, 1, "1", "true"] {
            XCTAssertEqual(try HostLanRequest(.init(method: "GET", path: "/v1/lan/brands/tapo/logo", query: ["raw": raw])), .logo(id: "tapo", raw: true))
        }
        XCTAssertThrowsError(try HostLanRequest(.init(method: "GET", path: "/v1/lan/brands/../logo")))
        XCTAssertThrowsError(try HostLanRequest(.init(method: "GET", path: "/v1/lan/brands/tapo/logo", query: ["raw": "yes"])))
        XCTAssertThrowsError(try HostLanRequest(.init(method: "GET", path: "/v1/lan/catalog", body: ["scan": true])))
    }
}
