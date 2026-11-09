import CryptoKit
import Foundation

/// Pinned Android manufacturer metadata and passive assets, entirely bundled.
/// No lookup service is contacted; all brand matches remain suggestions.
struct LanDeviceCatalog {
    struct Brand: Decodable {
        let id: String
        let name: String
        let pattern: String
        let asset: String
        let mime: String
        let background: String
        let source: String
        let sha256: String
        let license: String
        var metadata: [String: Any] {
            ["id": id, "name": name, "logo_url": "/v1/lan/brands/\(id)/logo?raw=1",
             "mime": mime, "background": background, "source": source, "sha256": sha256]
        }
    }
    private struct Catalog: Decodable { let version: Int; let brands: [Brand] }
    let version: Int
    let brands: [Brand]
    private let assets: [String: Data]
    init() throws {
        guard let root = Bundle.module.url(forResource: "DeviceBrands", withExtension: nil) else { throw Self.invalid() }
        let catalog = try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: root.appendingPathComponent("catalog.json")))
        var assets: [String: Data] = [:]
        for brand in catalog.brands {
            guard brand.asset.range(of: "^[a-z0-9-]+\\.(svg|png)$", options: .regularExpression) != nil,
                  brand.id.range(of: "^[a-z0-9-]+$", options: .regularExpression) != nil,
                  ["image/svg+xml", "image/png"].contains(brand.mime), assets[brand.id] == nil else { throw Self.invalid() }
            _ = try NSRegularExpression(pattern: brand.pattern, options: .caseInsensitive)
            let data = try Data(contentsOf: root.appendingPathComponent(brand.asset))
            guard SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == brand.sha256 else { throw Self.invalid() }
            assets[brand.id] = data
        }
        version = catalog.version; brands = catalog.brands; self.assets = assets
    }
    func find(_ device: LanDiscoveredDevice) -> Brand? {
        let names = device.name + " " + LanDevicePresentation.describe(device).title + " " + device.modelHints.joined(separator: " ")
        return brands.first { names.range(of: $0.pattern, options: [.regularExpression, .caseInsensitive]) != nil }
    }
    func logo(id: String) -> Data? { assets[id] }
    func common(_ devices: [LanDiscoveredDevice]) -> [(Brand, Int)] {
        let grouped = Dictionary(grouping: devices.compactMap(find), by: \.id)
        return grouped.values.compactMap { values in values.first.map { ($0, values.count) } }.sorted {
            $0.1 == $1.1 ? $0.0.name < $1.0.name : $0.1 > $1.1
        }
    }
    private static func invalid() -> DeviceAPIError { .init(503, "lan_catalog_unavailable", "The bundled device catalog is unavailable.") }
}

enum LanDeviceModelNames {
    static let source = "https://support.apple.com/102869"
    static let known = [
        ("Mac14,2", "MacBook Air (M2, 2022)"),
        ("Mac14,15", "MacBook Air (15-inch, M2, 2023)"),
        ("MacBookAir10,1", "MacBook Air (M1, 2020)")
    ]
    static func label(_ identifier: String) -> String {
        known.first { $0.0.caseInsensitiveCompare(identifier) == .orderedSame }?.1 ?? identifier
    }
    static var metadata: [[String: String]] {
        known.map { ["identifier": $0.0, "name": $0.1, "brand": "apple", "source": source] }
    }
}
