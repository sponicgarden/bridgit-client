import XCTest
@testable import Bridgit_Portal

/// The brand is Bridgit, never "Bridgit Portal" (owner, A0160, 9 Nov 2026).
final class BrandingTests: XCTestCase {
    func testAppNameIsBridgit() {
        // Hosted tests: Bundle.main is the app itself.
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String, "Bridgit")
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String, "Bridgit")
    }

    func testBrandBarSaysBridgit() {
        XCTAssertEqual(BrandBar.label, "Bridgit")
    }
}
