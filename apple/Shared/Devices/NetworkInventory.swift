import Foundation

struct LanCategory: Identifiable, Hashable {
    /// The phone's category id, lowercase (cameras, media, audio, network, printers, smart, computers, mobile, other).
    var id: String
    var title: String
    var connected: Int
    var total: Int
    var expanded: Bool
    var devices: [LanDevice]
    /// The makers shared by this group's devices, most common first (categories[].brands[]): the header's logo strip.
    var brands: [LanBrand] = []
}

/// A camera or smart device added to Bridgit on the phone (`added` in GET /v1/portal/devices).
struct AddedDevice: Identifiable, Hashable {
    /// "lan:…" for a camera, "net:…" for a smart device.
    var id: String
    var name: String
    var host: String
    /// camera, speaker, computer or smart.
    var kind: String
    /// "on" once checked, "blocked" while not checked yet.
    var state: String
    /// The applets this device is included in, by name.
    var applets: [String]
    var model: String? = nil
    var verifiedAt: Double? = nil
    /// Camera checks: audio_in, audio_out, tapo_talk → available, unavailable or not_checked.
    var checks: [String: String] = [:]
    var adapter: String? = nil
}

/// A device maker Bridgit recognised (lan/DeviceBrands.kt). The logo itself is bundled (device-brands/catalog.json).
struct LanBrand: Hashable {
    var id: String
    var name: String
}

struct LanDevice: Identifiable, Hashable {
    /// The phone's id for the device; in the real payload it is the device's address.
    var id: String
    var name: String
    var typeLabel: String
    var host: String?
    var model: String?
    var brand: LanBrand?
    var services: [String]
    var connected: Bool
    var lastSeen: Double?
    var interfaced: Bool
    var interfaceLabel: String?
    var identifier: String? = nil
    /// Category id, lowercase, as the phone sends it.
    var category: String? = nil
    /// The suggested kind, lowercase (camera, tv, speaker, audio, computer, mobile, printer, router, smart, cast, unknown).
    var kind: String? = nil
    /// The raw model code when the phone shows a friendlier model name (model_identifier).
    var modelIdentifier: String? = nil
    /// A missing/unprobed result is neither connected nor disconnected.
    var connectionReported = true
    var connectionCheckedAt: Double? = nil
    var accountSnapshot = false
    var disconnected: Bool { connectionReported && !connected }
    var connectionDescription: String {
        guard connectionReported else { return "Not checked" }
        return (accountSnapshot ? "Last known " : "") + (connected ? (accountSnapshot ? "connected" : "Connected") : (accountSnapshot ? "disconnected" : "Disconnected"))
    }
    var lastSeenLabel: String { accountSnapshot ? "Last connected" : "Last seen" }
}

/// The APK's last-seen wording (lan/DeviceLastSeen.kt).
enum LastSeen {
    static func relative(_ ms: Double?, now: Date = Date()) -> String {
        guard let ms, ms > 0 else { return "Not yet seen" }
        let minutes = max(0, Int((now.timeIntervalSince1970 * 1000 - ms) / 60_000))
        switch minutes {
        case 0: return "Just now"
        case ..<60: return "\(minutes) min ago"
        case ..<1440: return "\(minutes / 60) hr ago"
        default: return "\(minutes / 1440) d ago"
        }
    }
    static func full(_ ms: Double?) -> String {
        guard let ms, ms > 0 else { return "Not recorded" }
        return Date(timeIntervalSince1970: ms / 1000).formatted(date: .numeric, time: .shortened)
    }
}

/// One inventory interpretation shared by native Mac, iPad and iPhone surfaces.
enum NetworkInventory {
    nonisolated static func parseLan(_ obj: Any) -> [LanCategory] {
        let d = obj as? [String: Any] ?? [:]
        func text(_ v: Any?) -> String? { (v as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
        let devices = (d["devices"] as? [[String: Any]] ?? []).compactMap { x -> LanDevice? in
            guard let id = x["id"] as? String else { return nil }
            let iface = x["interface"] as? [String: Any]
            let rawModel = text(x["model_identifier"])
            let model = text(x["model"]) ?? rawModel
            return LanDevice(id: id, name: text(x["name"]) ?? text(x["advertised_name"]) ?? text(x["host"]) ?? "Device",
                             typeLabel: text(x["type_label"]) ?? (text(x["type"]) ?? "Device").capitalized,
                             host: text(x["host"]) ?? (id.contains(".") || id.contains(":") ? id : nil), model: model, brand: brand(x["brand"]),
                             services: (x["services"] as? [[String: Any]] ?? []).compactMap { $0["label"] as? String },
                             connected: x["connected"] as? Bool ?? false, lastSeen: (x["last_seen"] as? NSNumber)?.doubleValue,
                             interfaced: x["bridgit_interfaced"] as? Bool ?? false, interfaceLabel: iface?["label"] as? String,
                             identifier: text(x["identifier"]), category: text(x["category"])?.lowercased(),
                             kind: text(x["type"])?.lowercased(), modelIdentifier: rawModel == model ? nil : rawModel, connectionReported: x["connected"] is Bool)
        }
        let byId = Dictionary(devices.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let cats = (d["categories"] as? [[String: Any]] ?? []).compactMap { c -> LanCategory? in
            guard let id = c["id"] as? String else { return nil }
            // The phone sends each group already in the APK's order (added, then linkable, then reachable, …);
            // only lift Bridgit-linkable and connected devices up for an older phone, keeping the phone's order otherwise.
            let members = (c["device_ids"] as? [String] ?? []).compactMap { byId[$0] }.enumerated()
                .sorted { ($0.element.interfaced ? 0 : 1, $0.element.connected ? 0 : 1, $0.offset) < ($1.element.interfaced ? 0 : 1, $1.element.connected ? 0 : 1, $1.offset) }
                .map(\.element)
            return LanCategory(id: id.lowercased(), title: c["title"] as? String ?? id.capitalized, connected: c["connected_count"] as? Int ?? members.filter(\.connected).count,
                               total: c["count"] as? Int ?? members.count, expanded: c["expanded_by_default"] as? Bool ?? true, devices: members,
                               brands: (c["brands"] as? [Any] ?? []).compactMap(brand))
        }
        if cats.isEmpty && !devices.isEmpty {
            return [LanCategory(id: "all", title: "Devices", connected: devices.filter(\.connected).count, total: devices.count, expanded: true, devices: devices)]
        }
        return cats
    }

    /// A device's maker: an object {id, name, logo_url, …} (lan/DiscoveryResult.kt) or null; an older phone may send a plain name.
    nonisolated static func brand(_ v: Any?) -> LanBrand? {
        if let o = v as? [String: Any], let id = (o["id"] as? String) ?? (o["name"] as? String), !id.isEmpty {
            return LanBrand(id: id.lowercased(), name: (o["name"] as? String) ?? id)
        }
        if let s = v as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty {
            return LanBrand(id: s.lowercased().filter { $0.isLetter || $0.isNumber }, name: s)
        }
        return nil
    }

    /// The devices added to Bridgit (`added` in GET /v1/portal/devices); nil when the phone doesn't send them.
    /// The phone's address and prefix on its own network, e.g. "192.168.1.23/24".
    nonisolated static func parseNetwork(_ obj: Any) -> String? {
        guard let n = (obj as? [String: Any])?["network"] as? [String: Any],
              let a = (n["address"] as? String)?.trimmingCharacters(in: .whitespaces), !a.isEmpty else { return nil }
        return (n["prefix_length"] as? Int).map { "\(a)/\($0)" } ?? a
    }

    nonisolated static func parseAdded(_ obj: Any) -> [AddedDevice]? {
        guard let list = (obj as? [String: Any])?["added"] as? [[String: Any]] else { return nil }
        return list.compactMap { x in
            guard let id = x["id"] as? String else { return nil }
            let checks = (x["checks"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            return AddedDevice(id: id, name: (x["name"] as? String) ?? (x["host"] as? String) ?? "Device", host: x["host"] as? String ?? "",
                               kind: (x["kind"] as? String)?.lowercased() ?? "smart", state: (x["state"] as? String) == "blocked" ? "blocked" : "on",
                               applets: x["applets"] as? [String] ?? [], model: x["model"] as? String,
                               verifiedAt: (x["verified_at"] as? NSNumber)?.doubleValue, checks: checks, adapter: x["adapter"] as? String)
        }
    }

    /// Every device once, in list order.
    nonisolated static func devices(in categories: [LanCategory]) -> [LanDevice] {
        var seen = Set<String>()
        return categories.flatMap(\.devices).filter { seen.insert($0.id).inserted }
    }

}

enum ListMode: String, CaseIterable {
    case all, groups, connected, unconnected
    func visible(_ devices: [LanDevice]) -> [LanDevice] {
        switch self {
        case .connected: return devices.filter(\.connected)
        case .unconnected: return devices.filter(\.disconnected)
        default: return devices
        }
    }
}
