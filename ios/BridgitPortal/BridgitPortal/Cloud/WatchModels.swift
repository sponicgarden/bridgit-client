import Foundation

// Shapes returned by the Bridgit feedback Worker's /v1/watch routes (bridgit-cloud/feedback/src/watch.mjs, applets.mjs).
// Every field is optional where the server may leave it out, so an older or newer server still decodes.

struct PhonesResponse: Decodable {
    var email: String?
    var phones: [Phone]
}

struct Phone: Decodable, Identifiable, Hashable {
    var phone: String
    var name: String?
    var device_type: String?
    var devices: [InventoryDevice]?
    var agent_history: [AgentHistory]?
    var linked_at: Double?
    var last_push_at: Double?
    var status: PhoneStatus?
    var status_at: Double?
    var dog_at: Double?
    var dog_photo_at: Double?
    /// Signs from the server (phoneHealth in watch.mjs) that this phone is gone or stuck: an old install,
    /// a wiped or replaced phone. Missing on older servers, which reads as "fine".
    var outdated: Bool? = nil
    var likely_gone: Bool? = nil
    var last_seen_at: Double? = nil

    var id: String { phone }
    var isLikelyGone: Bool { likely_gone == true }
    /// "Not seen since 2 Nov · Bridgit 1.8.811", or nil when the phone looks fine.
    var goneLine: String? {
        guard isLikelyGone || outdated == true else { return nil }
        var parts: [String] = []
        if isLikelyGone, let t = last_seen_at ?? last_push_at {
            let f = DateFormatter(); f.dateFormat = "d MMM"
            parts.append("Not seen since \(f.string(from: Date(timeIntervalSince1970: t / 1000)))")
        }
        if outdated == true { parts.append("old Bridgit \(status?.app_version ?? "")".trimmingCharacters(in: .whitespaces)) }
        return parts.joined(separator: " · ")
    }
    var displayName: String { (name?.isEmpty == false ? name : nil) ?? "Bridgit device" }

    static func == (a: Phone, b: Phone) -> Bool { a.phone == b.phone && a.last_push_at == b.last_push_at && a.status_at == b.status_at }
    func hash(into h: inout Hasher) { h.combine(phone) }
}

struct PhoneStatus: Decodable {
    var caps: [String: String]?
    var listening: [String]?
    var agents: [String]?
    var app_version: String?
    var device: DeviceInfo?
    var connections: [String: String]?
    var services: [String: String]?
    var agent_status: [AgentStatus]?
    var applets: [PhoneApplet]?
}

struct DeviceInfo: Decodable {
    var manufacturer: String?
    var model: String?
    var android: String?
    var api_level: Int?
    var ram_mb: Int?
    var storage_mb: Int?
}

struct AgentStatus: Decodable, Identifiable {
    var name: String
    var last_used_at: Double?
    var paused: Bool?
    var id: String { name }
}

struct AgentHistory: Decodable, Identifiable {
    var name: String
    var last_used_at: Double?
    var id: String { name }
}

struct PhoneApplet: Decodable, Identifiable {
    var id: String
    var name: String?
    var kind: String
    var running: Bool?
    var sensors: [String]?
    var actions: [String]?
    var last_at: Double?
    var last_action: String?
}

struct InventoryDevice: Decodable, Identifiable {
    var id: String
    var type: String?
    var name: String
    var model: String?
    var verified_at: Double?
    var state: String?
    var online: Bool?
    var connection_checked_at: Double?
    var last_connected_at: Double?
    var applets: [String]?
}

struct EventsResponse: Decodable {
    var events: [WatchEvent]
    var server_time: Double?
}

struct WatchEvent: Decodable, Identifiable, Hashable {
    var id: Int
    var ts: Double
    var agent: String?
    var kind: String?
    var method: String?
    var endpoint: String?
    var http: Int?
    var caption: String?
    var source: String?

    var failed: Bool { (http ?? 200) >= 400 }
}

struct SessionResponse: Decodable {
    var session: String
    var email: String?
    var expires_at: Double?
}

struct AppletsResponse: Decodable {
    var applets: [AppletSummary]
}

struct AppletSummary: Decodable, Identifiable, Hashable {
    var phone: String
    var applet: String
    var friendly_name: String?
    var tagline: String?
    var avatar_data_url: String?
    var portal_number: Int?
    var enabled_at: Double?
    var paused: Bool?
    var name: String?
    var device_type: String?
    var live_at: Double?
    var role: String?
    var running: Bool?

    var id: String { phone + ":" + applet }
    var title: String { friendly_name ?? (applet == "dog" ? "Pet monitor" : applet.capitalized) }
    var isOwner: Bool { role == "owner" }
}

/// GET /v1/watch/applet: a large object. Only the parts the app shows are decoded.
struct AppletDetail: Decodable {
    var friendly_name: String?
    var tagline: String?
    var role: String?
    var paused: Bool?
    var connected: Bool?
    var last_connected_at: Double?
    var capture_supported: Bool?
    var current_image: AppletImage?
    var saved_photo_counts: [String: Int]?
    var timezone: String?
    var live_at: Double?
}

struct AppletImage: Decodable, Identifiable, Hashable {
    var id: Int
    var captured_at: Double?
    var camera: String?
    var reading: Reading?

    struct Reading: Decodable, Hashable {
        var dog: Bool?
        var activity: String?
        var mood: String?
        var dog_pct: Int?
        var model: String?
    }

    var words: String {
        guard let r = reading else { return "No observation available" }
        guard r.dog == true else { return "No pet seen" }
        var parts = [(r.activity == "sleeping" ? "resting" : r.activity) ?? "Activity unclear"]
        if let m = r.mood { parts.append(m) }
        if let p = r.dog_pct { parts.append("\(p)% pet probability") }
        let s = parts.joined(separator: " · ")
        return s.prefix(1).uppercased() + s.dropFirst()
    }
}

struct GalleryResponse: Decodable {
    var total: Int?
    var images: [AppletImage]
    var next: Double?
}

struct SummaryResult: Decodable {
    var summary: String
    var images: Int?
    var cached: Bool?
}

struct LinkPreview: Decodable {
    var name: String?
    var device_type: String?
}

struct ServerError: Decodable {
    var error: String?
    var message: String?
}

struct PortalRequest: Decodable {
    var request: String
    var expires_at: Double?
}

/// The pairing the phone hands over after its owner taps Allow (same fields as the bridgitportal://pair link).
struct PortalPairingReply: Decodable {
    var status: String?
    var relay: String?
    var device: String?
    var token: String?
    var name: String?
    var portal: Bool?
    var install: String?

    func pairing(install fallback: String) -> Pairing? {
        guard let relay, let device, let token, Pairing.validToken(token), let base = Pairing.makeBase(relay: relay, device: device) else { return nil }
        return Pairing(base: base, deviceId: device, token: token, name: name ?? "Bridgit phone", install: install ?? fallback, portal: portal ?? true)
    }
}
