import CoreFoundation
import Foundation

/// Absence is not a disabled switch. A missing field never becomes an available capability.
public enum ReportedFeatureState: String, Codable, Sendable {
    case on, off, blocked, unavailable, unreported
    public init(feature: [String: Any]) {
        for key in ["implemented", "supported"] {
            if let value = feature[key] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID(), !value.boolValue {
                self = .unavailable; return
            }
        }
        self.init(reported: feature["state"] ?? feature["enabled"])
    }
    public init(reported value: Any?) {
        if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() {
            self = number.boolValue ? .on : .off
        } else if let text = value as? String {
            self = text == "unsupported" ? .unavailable : Self(rawValue: text) ?? .unreported
        } else { self = .unreported }
    }
}
