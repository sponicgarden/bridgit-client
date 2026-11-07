import Foundation
import SwiftUI
import UIKit

/// One Bridgit phone as the portal sees it: live through the relay when this iPhone/iPad is paired
/// with it, otherwise the snapshot the phone sends to the owner's account (remote view).
struct PortalDevice: Identifiable, Hashable {
    var id: String
    var name: String
    var cloud: Phone?
    var pairing: Pairing?

    var isLive: Bool { pairing != nil }

    static func list(phones: [Phone], pairings: [Pairing]) -> [PortalDevice] {
        var out: [PortalDevice] = []
        var used = Set<String>()
        for p in pairings {
            // Same install id, else (a reinstall gets a new id) the same name, so one phone is one row.
            let match = phones.first { $0.phone == p.install && !used.contains($0.phone) }
                ?? phones.first { !used.contains($0.phone) && $0.displayName.caseInsensitiveCompare(p.name) == .orderedSame }
            if let m = match { used.insert(m.phone) }
            out.append(PortalDevice(id: "relay:" + p.deviceId, name: match?.displayName ?? p.name, cloud: match, pairing: p))
        }
        // Phones that look gone go last, so the portal never opens on a dead install.
        let ordered = phones.filter { !$0.isLikelyGone } + phones.filter { $0.isLikelyGone }
        for ph in ordered where !used.contains(ph.phone) {
            out.append(PortalDevice(id: "cloud:" + ph.phone, name: ph.displayName, cloud: ph, pairing: nil))
        }
        return out
    }
}

/// One row of the phone's activity log, in the shape the Home scene's log takes
/// (DiagnosticsScene.entryJson on Android).
struct LogLine: Identifiable, Hashable {
    var id: Int
    var ts: Double
    var agent: String
    var kind: String
    var method: String
    var endpoint: String
    var http: Int
    var result: String
    var message: String
    var raw: [String: AnyHashable]

    var failed: Bool { http >= 400 || result == "error" || result == "denied" }

    init(raw r: [String: Any]) {
        id = (r["id"] as? Int) ?? Int((r["id"] as? Double) ?? 0)
        ts = (r["ts"] as? Double) ?? Double((r["ts"] as? Int) ?? 0)
        agent = r["agent"] as? String ?? ""
        kind = r["kind"] as? String ?? ""
        method = r["method"] as? String ?? ""
        endpoint = (r["endpoint"] as? String) ?? (r["path"] as? String) ?? ""
        http = (r["http"] as? Int) ?? (r["status"] as? Int) ?? 0
        result = r["result"] as? String ?? ""
        message = (r["message"] as? String) ?? (r["caption"] as? String) ?? ""
        var clean: [String: AnyHashable] = [:]
        for (k, v) in r { if let h = v as? AnyHashable, JSONSerialization.isValidJSONObject([k: v]) { clean[k] = h } }
        raw = clean
    }

    /// A cloud remote-view line, converted to the scene's entry shape.
    init(event e: WatchEvent) {
        self.init(raw: ["id": e.id, "ts": e.ts, "channel": "audit", "level": e.failed ? "warn" : "info",
                        "result": e.failed ? "error" : "ok", "kind": e.kind ?? "request", "method": e.method ?? "",
                        "endpoint": e.endpoint ?? "", "params": [String: Any](), "http": e.http ?? 0,
                        "message": e.caption ?? "", "data": [String: Any](), "agent": e.agent ?? ""])
    }

    var sceneJSON: [String: Any] {
        var d: [String: Any] = ["id": id, "ts": ts, "channel": "audit", "level": failed ? "warn" : "info", "result": result,
                                "kind": kind, "method": method, "endpoint": endpoint, "params": [String: Any](), "http": http,
                                "message": message, "data": [String: Any](), "agent": agent]
        for (k, v) in raw where d[k] == nil { d[k] = v }
        return d
    }

    var title: String {
        if !message.isEmpty && endpoint.isEmpty { return message }
        return [method, endpoint].filter { !$0.isEmpty }.joined(separator: " ")
    }
}

struct FeatureRow: Identifiable, Hashable {
    enum State: String { case on, off, blocked }
    var id: String
    var name: String
    var group: String
    var state: State
    var summary: String?
}

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

struct AgentRow: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var detail: String?
    var lastUsed: Double?
    var paused: Bool
    /// From /v1/portal/agents: "direct" (Tailscale), "userspace" or "relay"; relay mode "connector" or "sealed".
    var transport: String? = nil
    var mode: String? = nil
    var model: String? = nil
    /// A Bridgit Portal viewer (an iPhone or iPad like this one), not a control agent.
    var portal = false

    /// The APK's connection line under the name (AgentsFragment: relay disclosure, else the agent's model).
    var connectionText: String? {
        if transport == "relay" {
            switch mode {
            case "sealed": return "Sealed relay · end-to-end encrypted"
            case "connector", nil: return "Connector: the relay can read and control permitted traffic"
            default: return "Relay · setup required in Relay settings"
            }
        }
        return model ?? detail
    }

    /// The APK's second line: why it can't reach the phone, else how it connects; then when it was last seen.
    func statusLine(now: Date = Date()) -> String {
        let ways = ["relay": "Relay", "userspace": "Userspace", "direct": "Tailscale"]
        let via: String? = paused ? "Paused" : transport.flatMap { ways[$0] }
        let seen: String
        if let t = lastUsed, t > 0 {
            seen = now.timeIntervalSince1970 * 1000 - t <= 10 * 60_000 ? "active now" : "seen " + Age.text(ms: t)
        } else {
            seen = "never connected"
        }
        return [via, seen].compactMap { $0 }.joined(separator: " · ")
    }

    var isActive: Bool { lastUsed.map { Date().timeIntervalSince1970 * 1000 - $0 <= 10 * 60_000 } ?? false }
}

struct AppletRow: Identifiable, Hashable {
    var id: String
    var name: String
    var kind: String
    var running: Bool
    var lastAt: Double?
    /// The phone's one-word state ("Watching", "On tour", "Paused"); missing on older phones.
    var summary: String? = nil
    var sensors: [String] = []
    var actions: [String] = []
    var icon: String {
        switch kind { case "dog": "🐕"; case "tour": "🗺️"; case "watch": "👁️"; default: "◈" }
    }
}

struct DeviceFacts: Hashable {
    var model: String?
    var android: String?
    var appVersion: String?
    var battery: Int?
    var charging: Bool?
    var connections: [String: String] = [:]
    var services: [String: String] = [:]
}

/// The marketing name of this iPhone or iPad ("iPhone 16 Pro"), from its hardware identifier. Unknown or newer
/// identifiers fall back to the kind plus the identifier, so a new model still reads sensibly.
enum DeviceModel {
    static var identifier: String {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        // The simulator reports x86_64/arm64; it says what it simulates in its environment.
        return ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? id
    }

    static var name: String {
        let id = identifier
        if let n = names[id] { return n }
        let kind = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        return id.hasPrefix(kind) ? "\(kind) (\(id))" : kind
    }

    private static let names: [String: String] = [
        "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,6": "iPhone SE", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,5": "iPhone 16e",
        "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
        "iPad13,18": "iPad (10th generation)", "iPad13,19": "iPad (10th generation)",
        "iPad14,8": "iPad Air 11-inch (M2)", "iPad14,9": "iPad Air 11-inch (M2)", "iPad14,10": "iPad Air 13-inch (M2)", "iPad14,11": "iPad Air 13-inch (M2)",
        "iPad16,3": "iPad Pro 11-inch (M4)", "iPad16,4": "iPad Pro 11-inch (M4)", "iPad16,5": "iPad Pro 13-inch (M4)", "iPad16,6": "iPad Pro 13-inch (M4)",
        "iPad16,1": "iPad mini (A17 Pro)", "iPad16,2": "iPad mini (A17 Pro)",
    ]
}
