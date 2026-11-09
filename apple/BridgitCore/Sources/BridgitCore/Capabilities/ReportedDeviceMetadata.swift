import Foundation

/// Descriptive, self-reported facts. These never imply a role, capability or grant.
/// Preserve unknown wire values for future peers; presentation can use a generic label.
public struct ReportedDeviceMetadata: Codable, Equatable, Hashable {
    public private(set) var platform: String?
    public private(set) var deviceType: String?
    enum CodingKeys: String, CodingKey { case platform; case deviceType = "device_type" }

    public init(platform: String? = nil, deviceType: String? = nil) {
        self.platform = Self.clean(platform)
        self.deviceType = Self.clean(deviceType)
    }

    public init(device: [String: Any]) {
        self.init(platform: device["platform"] as? String,
                  deviceType: Self.clean(device["device_type"] as? String) ?? Self.clean(device["type"] as? String))
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(platform: try values.decodeIfPresent(String.self, forKey: .platform),
                  deviceType: try values.decodeIfPresent(String.self, forKey: .deviceType))
    }

    public var isEmpty: Bool { platform == nil && deviceType == nil }

    /// Use only when both records belong to the same proven installation.
    public func fillingMissing(from fallback: Self) -> Self {
        Self(platform: platform ?? fallback.platform, deviceType: deviceType ?? fallback.deviceType)
    }

    private static func clean(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              value.range(of: "^[a-z][a-z0-9_-]{0,47}$", options: .regularExpression) != nil else { return nil }
        return value
    }
}

extension DeviceIdentity {
    public var reportedMetadata: ReportedDeviceMetadata { .init(platform: platform, deviceType: deviceType) }
}
