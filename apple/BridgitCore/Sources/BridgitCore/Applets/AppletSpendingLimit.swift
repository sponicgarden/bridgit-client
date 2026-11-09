import CoreFoundation
import Foundation

/// Monetary limits sent to the shared budget service. Local workflow attempt limits are separate.
public struct AppletSpendingLimit: Equatable, Sendable, Encodable {
    public let id: String
    public let usd: Double
    public let per: String
    public let check_at: [Double]?

    public static func limits(for package: [String: Any]) throws -> [Self] {
        let adapter = (package["runtime"] as? [String: Any])?["adapter"] as? String
        return try packageLimits(package["budget"], petMonitor: adapter == "android.dog.v1")
    }

    static func packageLimits(_ value: Any?, petMonitor: Bool = false) throws -> [Self] {
        func invalid(_ field: String) -> DeviceAPIError {
            DeviceAPIError(422, "invalid_applet_budget", "Invalid applet spending limit: " + field + ".")
        }
        func number(_ raw: Any?) -> Double? {
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
            return n.doubleValue
        }
        func dollars(_ raw: Any?) throws -> Double {
            guard let n = number(raw), (0.01...100).contains(n) else { throw invalid("use $0.01 to $100") }
            return n
        }
        let fallback = [Self(id: "l1", usd: 1, per: "day", check_at: nil),
                        Self(id: "l2", usd: 10, per: "month", check_at: nil)]
        guard let value, !(value is NSNull) else {
            return petMonitor ? [Self(id: "l1", usd: 3, per: "month", check_at: [0.5])] : fallback
        }
        var rawLimits: Any = value
        if let object = value as? [String: Any] {
            if let list = object["limits"] {
                guard Set(object.keys).isSubset(of: ["limits", "on_forecast"]) else { throw invalid("unknown field") }
                rawLimits = list
            } else {
                guard Set(object.keys).isSubset(of: ["day_usd", "month_usd"]) else { throw invalid("unknown field") }
                let day = try dollars(object["day_usd"] ?? 1)
                let month = try dollars(object["month_usd"] ?? max(10, day))
                guard month >= day else { throw invalid("monthly limit is below daily limit") }
                return [Self(id: "l1", usd: day, per: "day", check_at: nil),
                        Self(id: "l2", usd: month, per: "month", check_at: nil)]
            }
        }
        guard let entries = rawLimits as? [[String: Any]], (1...8).contains(entries.count) else { throw invalid("use 1–8 limits") }
        var ids = Set<String>()
        return try entries.enumerated().map { index, entry in
            guard Set(entry.keys).isSubset(of: ["id", "unit", "usd", "per", "check_at"]),
                  entry["unit"] == nil || entry["unit"] as? String == "usd",
                  let per = entry["per"] as? String, ["hour", "day", "week", "month"].contains(per),
                  let id = (entry["id"] ?? "l\(index + 1)") as? String,
                  id.range(of: #"^[a-z0-9][a-z0-9_-]{0,23}\z"#, options: .regularExpression) != nil,
                  ids.insert(id).inserted else { throw invalid("check limit names, periods and units") }
            var checks: [Double]?
            if let raw = entry["check_at"], !(raw is NSNull) {
                let values = raw as? [Any] ?? [raw]
                let fractions = values.compactMap(number)
                guard values.count == fractions.count, fractions.count <= 4,
                      fractions.allSatisfy({ $0 > 0 && $0 < 1 }) else { throw invalid("check_at must contain fractions between zero and one") }
                if !fractions.isEmpty { checks = fractions }
            }
            return Self(id: id, usd: try dollars(entry["usd"]), per: per, check_at: checks)
        }
    }
}
