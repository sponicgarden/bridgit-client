import Foundation

/// Presentation of Android 3347 discovery observations; neither names nor IPs
/// establish durable identity or permission to control a device.
struct LanDiscoveredDevice: Codable, Equatable {
    let host: String
    let name: String
    let services: [String]
    let cameraCandidate: Bool
    let modelHints: [String]
    var connected = true
    var lastSeen: Int64 = 0
    var present = true

    static func devices(_ observations: [LanDiscoveryObservation]) -> [Self] {
        let groups = Dictionary(grouping: observations.prefix(256).filter {
            LanDiscoveryRules.privateIPv4($0.host) != nil
        }, by: \.host)
        return groups.map { host, entries in
            let names = unique(entries.compactMap(\.name)).sorted { left, right in
                let lhs = left.range(of: "^[0-9a-fA-F]{8,}.*$", options: .regularExpression) != nil
                let rhs = right.range(of: "^[0-9a-fA-F]{8,}.*$", options: .regularExpression) != nil
                if lhs != rhs { return !lhs }
                return left.caseInsensitiveCompare(right) == .orderedAscending
            }
            return Self(host: host, name: names.first ?? host,
                services: unique(entries.map(\.service)).sorted(),
                cameraCandidate: entries.contains { $0.service == "tapo_camera" || $0.source == .onvif || $0.service.contains("_rtsp._tcp") },
                modelHints: unique(entries.compactMap(\.advertisedModel)))
        }.sorted {
            let order = $0.name.caseInsensitiveCompare($1.name)
            // Stable tie-breaking avoids dictionary iteration changing row order.
            return order == .orderedSame ? $0.host < $1.host : order == .orderedAscending
        }
    }
    static func serviceLabel(_ service: String) -> String {
        if let known = LanDiscoveryCatalog.services.first(where: { $0.type == service }) { return known.label }
        switch service {
        case "tapo_camera": return "Tapo camera"
        case "tplink_router": return "Router management"
        case "tplink_smart_device": return "Smart device"
        case "onvif_candidate": return "ONVIF camera"
        default: break
        }
        if service.contains("_linkplay.") { return "WiiM / LinkPlay audio" }
        if service.range(of: "InternetGatewayDevice", options: .caseInsensitive) != nil { return "Router service" }
        for (fragment, label) in [("_googlecast.", "Google Cast"), ("_spotify-connect.", "Spotify Connect"),
                                  ("_airplay.", "AirPlay"), ("_raop.", "AirPlay"), ("_sonos.", "Sonos"),
                                  ("_rtsp.", "Camera / video stream"), ("_shelly.", "Shelly smart device"),
                                  ("_ipp.", "Printer"), ("_http.", "Web service"), ("_https.", "Web service")] {
            if service.contains(fragment) { return label }
        }
        if service.range(of: "MediaRenderer", options: .caseInsensitive) != nil { return "Media player / TV" }
        if service.range(of: "MediaServer", options: .caseInsensitive) != nil { return "Media server" }
        return "Network service"
    }
    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.map(LanDiscoveryObservation.cleanName).filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

/// Bounded history reducer. Persistence and network-scope identity are supplied
/// by the host adapter, never by caller-provided IP/name guesses.
struct LanDiscoveryHistory: Codable {
    struct Scope: Codable { var updated: Int64; var devices: [LanDiscoveredDevice] }
    private(set) var scopes: [String: Scope] = [:]
    static let freshMillis: Int64 = 120_000

    func read(scope: String, now: Int64) -> [LanDiscoveredDevice] {
        (scopes[scope]?.devices ?? []).prefix(256).compactMap { saved in
            guard LanDiscoveryRules.privateIPv4(saved.host) != nil else { return nil }
            var device = saved
            device.connected = saved.present && saved.lastSeen >= 0 && now >= saved.lastSeen && now - saved.lastSeen <= Self.freshMillis
            return device
        }
    }
    mutating func update(scope: String, observed: [LanDiscoveredDevice], reliable: Bool, now: Int64) -> [LanDiscoveredDevice] {
        var old = Dictionary(read(scope: scope, now: now).map { ($0.host, $0) }, uniquingKeysWith: { first, _ in first })
        for var device in observed.prefix(256) where LanDiscoveryRules.privateIPv4(device.host) != nil {
            device.connected = true; device.present = true; device.lastSeen = now
            old[device.host] = device
        }
        let seen = Set(observed.map(\.host))
        let merged = old.values.sorted {
            $0.lastSeen == $1.lastSeen ? $0.host < $1.host : $0.lastSeen > $1.lastSeen
        }.prefix(256).map { saved in
            var device = saved
            if reliable && !seen.contains(device.host) { device.connected = false; device.present = false }
            return device
        }
        scopes[scope] = Scope(updated: now, devices: merged)
        for key in scopes.keys.sorted(by: {
            scopes[$0]!.updated == scopes[$1]!.updated ? $0 < $1 : scopes[$0]!.updated > scopes[$1]!.updated
        }).dropFirst(8) { scopes.removeValue(forKey: key) }
        return merged
    }
}
