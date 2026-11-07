import CommonCrypto
import CryptoKit
import Foundation

/// Linked Bridgits (docs/spec-linked-bridgits.md). On Apple, P3: this device is only ever the **user** —
/// it uses an Android Bridgit (direction `out`); nothing uses it (direction `in` stays off until P4).

/// LNK-03: a direction's state.
enum DirectionState: String, Codable, CaseIterable {
    case off, waiting_for_target, waiting_for_user, on, paused

    var isWaiting: Bool { self == .waiting_for_target || self == .waiting_for_user }
}

/// LNK-13: service IDs that pass a link, with the words the UI shows.
enum LinkService {
    static let order = ["view", "camera", "mic", "speaker", "interactions", "location", "gallery", "sounds"]
    static func label(_ id: String) -> String {
        switch id {
        case "mic": "microphone"
        case "view": "view"
        default: id
        }
    }
    static func list(_ ids: [String]) -> String {
        ids.sorted { (order.firstIndex(of: $0) ?? 99) < (order.firstIndex(of: $1) ?? 99) }.map(label).joined(separator: ", ")
    }
}

/// LNK-06: who made a change.
struct Granter: Codable, Equatable, Hashable {
    enum Kind: String, Codable { case person, agent, rule, migrated }
    var kind: Kind
    /// The device it was made on (its name).
    var device: String?
    var agentId: String?
    var agentName: String?

    static func person(on device: String?) -> Granter { Granter(kind: .person, device: device) }

    init(kind: Kind, device: String? = nil, agentId: String? = nil, agentName: String? = nil) {
        self.kind = kind; self.device = device; self.agentId = agentId; self.agentName = agentName
    }

    init?(json: Any?) {
        guard let o = json as? [String: Any], let k = (o["kind"] as? String).flatMap(Kind.init(rawValue:)) else { return nil }
        self.init(kind: k, device: o["device"] as? String, agentId: o["agent_id"] as? String, agentName: o["agent_name"] as? String)
    }

    var json: [String: Any] {
        var o: [String: Any] = ["kind": kind.rawValue]
        if let device { o["device"] = device }
        if let agentId { o["agent_id"] = agentId }
        if let agentName { o["agent_name"] = agentName }
        return o
    }

    /// "You on rahuls-ipad", "Muse on kitchen-tab", "Before linking".
    var label: String {
        switch kind {
        case .person: device.map { "Person on \($0)" } ?? "Person"
        case .agent: [agentName ?? agentId ?? "Agent", device.map { "on \($0)" }].compactMap { $0 }.joined(separator: " ")
        case .rule: "Automatic rule" + (device.map { " on \($0)" } ?? "")
        case .migrated: "Paired before links"
        }
    }
}

/// One direction of a link, as this device knows it.
struct LinkDirection: Codable, Equatable, Hashable {
    var state: DirectionState
    var services: [String]
    var grantedBy: Granter?
    var grantedAt: Date?

    static let off = LinkDirection(state: .off, services: [], grantedBy: nil, grantedAt: nil)

    init(state: DirectionState, services: [String], grantedBy: Granter?, grantedAt: Date?) {
        self.state = state; self.services = services; self.grantedBy = grantedBy; self.grantedAt = grantedAt
    }

    init(json: Any?) {
        let o = json as? [String: Any] ?? [:]
        state = (o["state"] as? String).flatMap(DirectionState.init(rawValue:)) ?? .off
        services = o["services"] as? [String] ?? []
        grantedBy = Granter(json: o["granted_by"])
        grantedAt = (o["granted_at"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) }
    }
}

/// A device further out, seen through a linked Bridgit (LNK-43): names and directions only.
struct LinkPeer: Codable, Equatable, Hashable {
    var name: String
    /// The linked Bridgit uses this peer.
    var uses: Bool
    /// This peer uses the linked Bridgit.
    var usedBy: Bool
}

/// LNK-01: another Bridgit this device has a link with.
struct LinkedBridgit: Codable, Identifiable, Equatable, Hashable {
    /// `lnk_…`, the same on both devices. Migrated Portal pairings get a local `lnk_local_…` until the
    /// phone reports its own.
    var id: String
    var name: String
    var platform: String
    var deviceType: String
    /// Which stored relay pairing carries the `out` direction.
    var relayDeviceId: String
    /// This device uses it.
    var out: LinkDirection
    /// It uses this device. Always off on Apple until the target role (P4).
    var inbound: LinkDirection = .off
    var migrated: Bool = false
    /// Whether the far device answers the device-to-device `/v1/link/*` routes (false for old Portal pairings).
    var linkRoutes: Bool = true
    var linkedAt: Date
    var peers: [LinkPeer] = []
    /// Paused on this device (the far device may pause its half too).
    var pausedHere: Bool?

    var isTwoWay: Bool { out.state != .off && inbound.state != .off }
}

/// LNK-22: one entry in the grants log.
struct LinkEvent: Codable, Identifiable, Equatable {
    var id: String
    var at: Date
    var linkId: String
    var peer: String
    var direction: String
    var event: String
    var before: String?
    var after: String?
    var granter: Granter
    /// The device it happened on.
    var device: String
    /// Set for an event the far device reported (LNK-23).
    var reportedBy: String?
    var late: Bool = false
    /// Whether the far device has been told.
    var sent: Bool = false

    var json: [String: Any] {
        var o: [String: Any] = ["id": id, "at": Int(at.timeIntervalSince1970 * 1000), "link_id": linkId, "direction": direction,
                                "event": event, "granter": granter.json, "device": device]
        if let before { o["before"] = before }
        if let after { o["after"] = after }
        if late { o["late"] = true }
        return o
    }

    /// The log's words: "Linked", "Paused", ….
    var title: String {
        switch event {
        case "code_made": "Code created"
        case "code_claimed": "Linked"
        case "agents_changed": "Agent access changed"
        case "auto_accepted": "Accepted automatically"
        case "rule_changed": "Rule changed"
        case "code_refused": "Code refused"
        case "opt_in": "Opted in"
        case "services_changed": "Services changed"
        case "paused": "Paused"
        case "resumed": "Resumed"
        case "withdrawn": "Withdrawn"
        case "disconnected": "Disconnected"
        case "disconnect_undone": "Disconnect undone"
        case "undone": "Disconnect undone"
        case "marked_off": "Marked off"
        case "opt_in_far": "Opted in"
        case "code_voided": "Code voided"
        case "removed": "Removed"
        case "introduced": "Introduced"
        case "introduction_allowed": "Introduction allowed"
        case "introduction_denied": "Introduction denied"
        case "migrated": "Paired before links"
        case "expired": "Expired"
        default: event.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }
}

/// LNK-36: an agent asks this device to accept a link with a third Bridgit. On Apple (user role only) it
/// arrives from a linked Bridgit: "kitchen-tab offers porch-cam for camera, microphone, introduced by Muse".
struct Introduction: Codable, Identifiable, Equatable {
    var id: String
    /// The Bridgit this device would use.
    var fromName: String
    var code: String
    var services: [String]
    var agentName: String?
    /// The linked Bridgit it came through.
    var via: String
    var viaLink: String
    var note: String?
    var at: Date

    init(id: String, fromName: String, code: String, services: [String], agentName: String?, via: String, viaLink: String, note: String?, at: Date) {
        self.id = id; self.fromName = fromName; self.code = code; self.services = services; self.agentName = agentName
        self.via = via; self.viaLink = viaLink; self.note = note; self.at = at
    }

    init?(json: Any?, via: LinkedBridgit) {
        guard let o = json as? [String: Any], let id = o["id"] as? String, let from = o["from_name"] as? String,
              let code = o["code"] as? String, LinkCode.normalize(code) != nil else { return nil }
        let by = o["introduced_by"] as? [String: Any]
        self.init(id: id, fromName: from, code: code, services: o["services"] as? [String] ?? [], agentName: by?["agent_name"] as? String,
                  via: via.name, viaLink: via.id, note: o["note"] as? String,
                  at: (o["at"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date())
    }
}

/// LNK-16: an 8-character Crockford base32 code shown as `K7QX-3MPA`.
enum LinkCode {
    private static let alphabet = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// Upper case, no dashes or spaces, O→0 and I/L→1; nil unless exactly 8 valid characters.
    static func normalize(_ text: String) -> String? {
        var out = ""
        for ch in text.uppercased() {
            switch ch {
            case "-", " ", "\u{2010}", "\u{2011}", "\u{2013}": continue
            case "O": out.append("0")
            case "I", "L": out.append("1")
            default:
                guard alphabet.contains(ch) else { return nil }
                out.append(ch)
            }
        }
        return out.count == 8 ? out : nil
    }

    static func display(_ code: String) -> String {
        guard let c = normalize(code) else { return code }
        return String(c.prefix(4)) + "-" + String(c.suffix(4))
    }

    /// The code keys both devices compute (docs/spec-linked-bridgits.md §4.1). `name` is the target's name as
    /// registered. dk = PBKDF2-HMAC-SHA256(code, "bridgit-link-v1\n" + name, 100000, 64 bytes).
    struct Keys: Equatable {
        let dk: Data
        /// Sent by the target in its offer; all the backend keeps.
        var codeHash: String { LinkCode.hex(dk.prefix(32)) }
        /// The first relay credential: bearer of the claim job (`POST /v1/link/claim`).
        var claimToken: String { "rly_" + LinkCode.hex(dk.suffix(32)) }

        /// Bound to the claiming install, so a proof seen in transit is useless to any other install.
        func proof(name: String, claimingInstall: String) -> String {
            let mac = HMAC<SHA256>.authenticationCode(for: Data(("bridgit-link-resolve-v1\n" + name + "\n" + claimingInstall).utf8),
                                                      using: SymmetricKey(data: dk.prefix(32)))
            return LinkCode.hex(Data(mac))
        }
    }

    /// Nil unless [code] is a valid code. Slow on purpose (100000 rounds): call off the main thread.
    static func keys(code: String, name: String) -> Keys? {
        guard let c = normalize(code) else { return nil }
        let password = Array(c.utf8).map { Int8(bitPattern: $0) }
        let salt = Array(("bridgit-link-v1\n" + name).utf8)
        var out = [UInt8](repeating: 0, count: 64)
        let status = CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password, password.count, salt, salt.count,
                                          CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 100_000, &out, out.count)
        return status == kCCSuccess ? Keys(dk: Data(out)) : nil
    }

    static func hex<D: Sequence>(_ d: D) -> String where D.Element == UInt8 { d.map { String(format: "%02x", $0) }.joined() }
}

/// What the person hands over (LNK-15): a name and a code, typed or scanned.
struct LinkOffer: Equatable {
    var name: String
    var code: String

    /// Accepts "kitchen-tab K7QX-3MPA", `bridgit://link?name=…&code=…` (or `bridgitportal://link…`), or `https://bridgit.cc/link#name=…&code=…`.
    static func parse(_ text: String) -> LinkOffer? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: t), let scheme = url.scheme?.lowercased(),
           ((scheme == "bridgit" || scheme == "bridgitportal") && url.host == "link") || (scheme == "https" && url.host == "bridgit.cc" && url.path.hasPrefix("/link")) {
            let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
            var items = c?.queryItems ?? []
            if let f = c?.fragment, let fc = URLComponents(string: "x:?" + f) { items += fc.queryItems ?? [] }
            func v(_ k: String) -> String? { items.first { $0.name == k }?.value }
            guard let n = v("name").flatMap(DeviceNameStore.parse), let code = v("code").flatMap(LinkCode.normalize) else { return nil }
            return LinkOffer(name: n, code: code)
        }
        let parts = t.split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" }).map(String.init)
        guard parts.count >= 2, let n = DeviceNameStore.parse(parts[0]), let code = LinkCode.normalize(parts[1...].joined()) else { return nil }
        return LinkOffer(name: n, code: code)
    }
}
