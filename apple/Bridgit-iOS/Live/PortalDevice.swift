import BridgitCore
import Foundation
import SwiftUI
import UIKit

/// One row of the phone's activity log, in the shape the Home scene's log takes
/// (DiagnosticsScene.entryJson on Android).
struct LogLine: Identifiable, Hashable {
    var id: Int
    var ts: Double
    var reportedAt: Date?
    var agent: String
    var kind: String
    var method: String
    var endpoint: String
    var http: Int
    var result: String
    var message: String
    var raw: [String: AnyHashable]
    private var rawJSON: Data

    var failed: Bool { http >= 400 || raw["level"] as? String == "error" || result == "error" || result == "denied" }

    init(raw r: [String: Any]) {
        id = (r["id"] as? Int) ?? Int((r["id"] as? Double) ?? 0)
        let timestamp = Self.timestamp(r["ts"])
        ts = timestamp ?? 0
        reportedAt = timestamp.map { Date(timeIntervalSince1970: $0 / 1000) }
        agent = r["agent"] as? String ?? ""
        kind = r["kind"] as? String ?? ""
        method = r["method"] as? String ?? ""
        endpoint = (r["endpoint"] as? String) ?? (r["path"] as? String) ?? ""
        http = (r["http"] as? Int) ?? (r["status"] as? Int) ?? 0
        result = r["result"] as? String ?? ""
        message = (r["message"] as? String) ?? (r["caption"] as? String) ?? ""
        var clean: [String: AnyHashable] = [:]
        var json: [String: Any] = [:]
        for (k, v) in r where JSONSerialization.isValidJSONObject([k: v]) {
            json[k] = v
            if let h = v as? AnyHashable { clean[k] = h }
        }
        raw = clean
        // Nested JSON objects are not necessarily AnyHashable. Keep their exact
        // JSON values for the scene, independently of scalar filtering metadata.
        rawJSON = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("{}".utf8)
    }

    // Android /v1/log serializes ISO-8601; portal snapshots use milliseconds.
    private static func timestamp(_ raw: Any?) -> Double? {
        if let number = raw as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { return nil }
            return number.doubleValue
        }
        guard let text = raw as? String else { return nil }
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = parser.date(from: text)
        if date == nil {
            parser.formatOptions = [.withInternetDateTime]
            date = parser.date(from: text)
        }
        return date.map { $0.timeIntervalSince1970 * 1000 }
    }

    /// A cloud remote-view line, converted to the scene's entry shape.
    init(event e: WatchEvent) {
        var reported: [String: Any] = ["id": e.id, "ts": e.ts]
        if let value = e.agent { reported["agent"] = value }
        if let value = e.kind { reported["kind"] = value }
        if let value = e.method { reported["method"] = value }
        if let value = e.endpoint { reported["endpoint"] = value }
        if let value = e.http { reported["http"] = value }
        if let value = e.caption { reported["caption"] = value }
        if let value = e.source { reported["source"] = value }
        self.init(raw: reported)
        // Visual fallbacks belong only to the scene, not copied telemetry.
        kind = e.kind ?? "request"
        result = e.failed ? "error" : "ok"
    }

    /// Details and clipboard use reported data, never the scene's visual defaults.
    var reportedFields: [String: Any] {
        (try? JSONSerialization.jsonObject(with: rawJSON)) as? [String: Any] ?? [:]
    }

    func reportedJSONString() throws -> String {
        let data = try JSONSerialization.data(withJSONObject: reportedFields, options: [.prettyPrinted, .sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    func reportedText(for key: String) -> String {
        let fields = reportedFields
        let aliases = ["endpoint": "path", "http": "status", "message": "caption"]
        let reported = fields[key] ?? aliases[key].flatMap { fields[$0] }
        guard let value = reported, !(value is NSNull) else { return "null" }
        if let text = value as? String { return text }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        return text
    }

    var sceneJSON: [String: Any] {
        var d = (try? JSONSerialization.jsonObject(with: rawJSON)) as? [String: Any] ?? [:]
        d["id"] = id
        if reportedAt != nil { d["ts"] = ts } else { d.removeValue(forKey: "ts") }
        d["channel"] = d["channel"] ?? "audit"
        d["level"] = d["level"] ?? (failed ? "warn" : "info")
        d["result"] = result; d["kind"] = kind; d["method"] = method; d["endpoint"] = endpoint
        d["params"] = d["params"] ?? [String: Any]()
        d["http"] = http; d["message"] = message
        d["data"] = d["data"] ?? [String: Any]()
        d["agent"] = agent
        return d
    }

    var title: String {
        if !message.isEmpty && endpoint.isEmpty { return message }
        return [method, endpoint].filter { !$0.isEmpty }.joined(separator: " ")
    }
}

struct FeatureRow: Identifiable, Hashable {
    typealias State = ReportedFeatureState
    var id: String
    var name: String
    var group: String
    var state: State
    var summary: String?
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
    /// A Bridgit viewer (an iPhone or iPad like this one), not a control agent.
    var portal = false
    var statusReported = true
    var accountSnapshot = false
    var reportedState: String { !statusReported ? "Status not reported" : paused ? "Paused" : "Not paused" }

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
        if accountSnapshot || !statusReported { return reportedState + (lastUsed.map { " · last recorded use " + Age.text(ms: $0) } ?? "") }
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

    var isActive: Bool { !accountSnapshot && (lastUsed.map { Date().timeIntervalSince1970 * 1000 - $0 <= 10 * 60_000 } ?? false) }
}

struct AppletRow: Identifiable, Hashable {
    var id: String
    var name: String
    var kind: String
    var running: Bool
    var lastAt: Double?
    var runningReported = true
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
    var operatingSystem: ReportedDeviceOS?
    var appVersion: String?
    var battery: Int?
    var charging: Bool?
    var connections: [String: String] = [:]
    var services: [String: String] = [:]
}

/// The OS version and its label come from the same reported device facts.
struct ReportedDeviceOS: Hashable {
    let label: String
    let version: String

    init?(version: String?, metadata: ReportedDeviceMetadata, legacyAndroid: String?) {
        func nonblank(_ value: String?) -> String? {
            guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
            return value
        }
        let legacy = metadata.platform == nil || metadata.platform == "android" ? nonblank(legacyAndroid) : nil
        guard let version = nonblank(version) ?? legacy else { return nil }
        self.version = version
        switch metadata.platform ?? (legacy == nil ? nil : "android") {
        case "android": label = String(localized: "Android")
        case "ios": label = metadata.deviceType == "tablet" ? String(localized: "iPadOS") : String(localized: "iOS")
        case "ipados": label = String(localized: "iPadOS")
        case "macos": label = String(localized: "macOS")
        case "watchos": label = String(localized: "watchOS")
        case "tvos": label = String(localized: "tvOS")
        case "visionos": label = String(localized: "visionOS")
        case "windows": label = String(localized: "Windows")
        case "linux": label = String(localized: "Linux")
        case "chromeos": label = String(localized: "ChromeOS")
        default: label = String(localized: "OS version")
        }
    }

    var summary: String { label + " " + version }
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
