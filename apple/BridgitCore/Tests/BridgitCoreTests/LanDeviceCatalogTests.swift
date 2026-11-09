import CryptoKit
import XCTest
@testable import BridgitCore

final class LanDeviceCatalogTests: XCTestCase {
    private func device(_ name: String, model: String = "") -> LanDiscoveredDevice {
        .init(host: "192.168.1.2", name: name, services: [], cameraCandidate: false, modelHints: model.isEmpty ? [] : [model])
    }
    func testEveryPinnedAssetLoadsWithItsDeclaredHashAndProvenance() throws {
        let catalog = try LanDeviceCatalog()
        XCTAssertEqual(catalog.version, 1)
        XCTAssertEqual(catalog.brands.count, 16)
        for brand in catalog.brands {
            let data = try XCTUnwrap(catalog.logo(id: brand.id))
            XCTAssertFalse(data.isEmpty)
            XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), brand.sha256)
            XCTAssertFalse(brand.source.isEmpty); XCTAssertFalse(brand.license.isEmpty)
        }
        XCTAssertNil(catalog.logo(id: "../catalog.json"))
        XCTAssertNil(catalog.logo(id: "unknown"))
    }
    func testBrandSelectionAndCommonOrderingMatchPinnedCatalog() throws {
        let catalog = try LanDeviceCatalog()
        XCTAssertEqual(catalog.find(device("Tapo C210"))?.id, "tapo")
        XCTAssertEqual(catalog.find(device("TP-Link Deco M4R"))?.id, "tplink")
        XCTAssertNil(catalog.find(device("Unidentified device")))
        let common = catalog.common([device("Tapo C210"), device("Tapo C200"), device("WiiM Amp")])
        XCTAssertEqual(common.map { $0.0.id }, ["tapo", "wiim"])
        XCTAssertEqual(common.map { $0.1 }, [2,1])
    }
    func testPinnedModelNamesHaveCaseInsensitiveLookupAndHonestFallback() {
        XCTAssertEqual(LanDeviceModelNames.label("mac14,15"), "MacBook Air (15-inch, M2, 2023)")
        XCTAssertEqual(LanDeviceModelNames.label("Mac14,2"), "MacBook Air (M2, 2022)")
        XCTAssertEqual(LanDeviceModelNames.label("MacBookAir10,1"), "MacBook Air (M1, 2020)")
        XCTAssertEqual(LanDeviceModelNames.label("Unknown42,1"), "Unknown42,1")
        XCTAssertEqual(LanDeviceModelNames.metadata.count, 3)
    }
}
