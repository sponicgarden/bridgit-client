import BridgitCore
import Foundation

/// Clearly labelled sample data for "Preview with sample data" and screenshots. Never mixed with real data.
enum SampleData {
    static let now = Date().timeIntervalSince1970 * 1000

    static let phones: [Phone] = [
        Phone(phone: "bgi_sample_kitchen", name: "Kitchen phone", device_type: "phone", devices: nil, agent_history: nil,
              linked_at: now - 86_400_000 * 9, last_push_at: now - 60_000, status: nil, status_at: now - 60_000, dog_at: nil, dog_photo_at: nil),
        Phone(phone: "bgi_sample_garage", name: "Garage tablet", device_type: "tablet", devices: nil, agent_history: nil,
              linked_at: now - 86_400_000 * 3, last_push_at: now - 600_000, status: nil, status_at: now - 600_000, dog_at: nil, dog_photo_at: nil),
        // An old install that stopped reporting: listed last, with Remove instead of Ask.
        Phone(phone: "bgi_sample_old", name: "Old tablet", device_type: "tablet", devices: nil, agent_history: nil,
              linked_at: now - 86_400_000 * 30, last_push_at: now - 86_400_000 * 2, status: nil, status_at: now - 86_400_000 * 2, dog_at: nil, dog_photo_at: nil,
              outdated: true, likely_gone: true, last_seen_at: now - 86_400_000 * 2),
    ]

    #if DEBUG
    static let operatingSystemPeers: [Phone] = (try? JSONDecoder().decode([Phone].self, from: Data("""
        [
          {"phone":"bgi_fixture_os_iphone","name":"Office iPhone","platform":"ios","device_type":"phone",
           "status":{"device":{"os_version":"26.2","platform":"ios","device_type":"phone","model":"iPhone 17"}}},
          {"phone":"bgi_fixture_os_mac","name":"Desk Mac","platform":"macos","device_type":"computer",
           "status":{"device":{"os_version":"26.1","platform":"macos","device_type":"computer","model":"Mac mini"}}},
          {"phone":"bgi_fixture_os_unknown","name":"Spare Mac","status":{"device":{"os_version":"7.4"}}}
        ]
        """.utf8))) ?? []
    #endif

    static let pairings: [Pairing] = [
        Pairing(base: URL(string: "https://bridgit-relay.bridgit-app.workers.dev/d/sample-device-0001")!, deviceId: "sample-device-0001",
                token: "rly_sample_not_a_real_token", name: "Kitchen phone", install: "bgi_sample_kitchen", portal: true)
    ]

    static let applets: [AppletSummary] = [
        AppletSummary(phone: "bgi_sample_kitchen", applet: "dog", friendly_name: "Biscuit cam", tagline: "A little window into their day.",
                      avatar_data_url: nil, portal_number: 1, enabled_at: now - 86_400_000 * 5, paused: false, name: "Kitchen phone",
                      device_type: "phone", live_at: now - 30_000, role: "owner", running: true),
        AppletSummary(phone: "bgi_sample_friend", applet: "dog", friendly_name: "Luna's day", tagline: "Shared by Sam",
                      avatar_data_url: nil, portal_number: 2, enabled_at: now - 86_400_000 * 2, paused: true, name: "Sam's tablet",
                      device_type: "tablet", live_at: now - 3_600_000, role: "viewer", running: false),
    ]

    static let appletDetail = AppletDetail(friendly_name: "Biscuit cam", tagline: "A little window into their day.", role: "owner", paused: false,
                                           connected: true, last_connected_at: now - 20_000, capture_supported: true, current_image: nil,
                                           saved_photo_counts: ["resting": 14, "playing": 5, "eating": 3], timezone: nil, live_at: now - 30_000)

    static let facts = DeviceFacts(model: "Samsung SM-X230", operatingSystem: .init(version: nil, metadata: .init(), legacyAndroid: "15"), appVersion: "1.8.1697", battery: 82, charging: true,
                                   connections: ["tailscale": "connected", "relay": "connected", "adb": "disconnected"],
                                   services: ["jev": "ready", "clef": "ready", "weather": "on"])

    static let features: [FeatureRow] = [
        ("camera", "on"), ("mic", "on"), ("wakewords", "on"), ("sounds", "off"), ("gallery", "on"), ("screen", "blocked"),
        ("location", "on"), ("weather", "on"), ("notifications", "blocked"), ("sensors", "on"), ("phone", "on"),
        ("speaker", "on"), ("flashlight", "on"), ("ring", "on"), ("controls", "off"), ("settings", "on"), ("adb", "off"),
        ("interactions", "on"), ("feedback", "on"), ("watches", "on"), ("video", "off"), ("ocr", "on"),
    ].map { FeatureRow(id: $0.0, name: PhoneLive.title(for: $0.0), group: PhoneLive.group(for: $0.0), state: FeatureRow.State(rawValue: $0.1)!, summary: nil) }

    static let agentRows: [AgentRow] = [
        AgentRow(name: "Claude", detail: nil, lastUsed: now - 120_000, paused: false, transport: "direct", model: "Claude Opus"),
        AgentRow(name: "Cursor", detail: nil, lastUsed: now - 3_600_000, paused: false, transport: "relay", mode: "connector"),
        AgentRow(name: "Home helper", detail: nil, lastUsed: nil, paused: true, transport: "userspace"),
        AgentRow(name: "Bridgit", detail: nil, lastUsed: now - 5_000, paused: false, transport: "relay", mode: "connector", portal: true),
    ]

    static let appletRows: [AppletRow] = [
        AppletRow(id: "dog", name: "Pet monitor", kind: "dog", running: true, lastAt: now - 30_000, summary: "Watching",
                  sensors: ["camera"], actions: []),
        AppletRow(id: "w1", name: "Package at the door", kind: "watch", running: true, lastAt: now - 600_000, summary: "Watching",
                  sensors: ["camera"], actions: ["notify"]),
    ]

    /// Shaped like GET /v1/portal/devices: ids are addresses, categories in the APK's order, brands from device-brands/catalog.json.
    static let categories: [LanCategory] = {
        func dev(_ host: String, _ name: String, _ kind: String, _ label: String, _ cat: String, brand: (String, String)? = nil, model: String? = nil,
                 services: [String] = [], ago: Double?, link: String? = nil) -> LanDevice {
            LanDevice(id: host, name: name, typeLabel: label, host: host, model: model, brand: brand.map { LanBrand(id: $0.0, name: $0.1) },
                      services: services, connected: (ago ?? .infinity) < 120_000, lastSeen: ago.map { now - $0 }, interfaced: link != nil,
                      interfaceLabel: link, category: cat, kind: kind)
        }
        let tapo = ("tapo", "Tapo"), google = ("google", "Google"), wiim = ("wiim", "WiiM"), apple = ("apple", "Apple"), sonos = ("sonos", "Sonos"),
            samsung = ("samsung", "Samsung"), hp = ("hp", "HP"), ubiquiti = ("ubiquiti", "Ubiquiti"), netgear = ("netgear", "NETGEAR"),
            shelly = ("shelly", "Shelly"), hik = ("hikvision", "Hikvision"), denon = ("denon", "Denon")
        let groups: [(String, String, Bool, [LanDevice])] = [
            ("cameras", "Cameras", true, [
                dev("192.168.1.31", "Tapo C210", "camera", "Camera", "cameras", brand: tapo, model: "C210", services: ["ONVIF", "RTSP"], ago: 40_000, link: "Tapo camera"),
                dev("192.168.1.32", "Hikvision camera", "camera", "Camera", "cameras", brand: hik, model: "DS-2CD2143G2", services: ["ONVIF", "RTSP"], ago: 86_400_000, link: "ONVIF camera"),
                dev("192.168.1.33", "Tapo C110", "camera", "Camera", "cameras", brand: tapo, model: "C110", services: ["ONVIF"], ago: 70_000, link: "Tapo camera")]),
            ("media", "TV & video", true, [
                dev("192.168.1.40", "Living room TV", "cast", "Cast device", "media", brand: google, model: "Chromecast", services: ["Google Cast"], ago: 20_000),
                dev("192.168.1.44", "Samsung TV", "tv", "TV", "media", brand: samsung, services: ["AirPlay", "DLNA"], ago: 3 * 3_600_000)]),
            ("audio", "Audio", true, [
                dev("192.168.1.41", "WiiM Mini", "speaker", "Speaker", "audio", brand: wiim, model: "WiiM Mini", services: ["AirPlay", "LinkPlay", "Spotify Connect"], ago: 25_000, link: "LinkPlay speaker"),
                dev("192.168.1.42", "Sonos One", "speaker", "Speaker", "audio", brand: sonos, services: ["AirPlay", "Sonos"], ago: 50_000),
                dev("192.168.1.43", "Denon AVR", "speaker", "Speaker", "audio", brand: denon, services: ["AirPlay", "HEOS"], ago: 2 * 86_400_000)]),
            ("network", "Routers & switches", true, [
                dev("192.168.1.1", "Dream Machine", "router", "Router", "network", brand: ubiquiti, model: "UDM Pro", services: ["HTTP", "SSH"], ago: 10_000),
                dev("192.168.1.2", "Office switch", "router", "Router", "network", brand: netgear, model: "GS308E", services: ["HTTP"], ago: 15_000)]),
            ("printers", "Printers", true, [
                dev("192.168.1.60", "HP LaserJet", "printer", "Printer", "printers", brand: hp, services: ["IPP", "IPPS"], ago: 45 * 60_000)]),
            ("smart", "Smart home", true, [
                dev("192.168.1.70", "Shelly Plug S", "smart", "Smart device", "smart", brand: shelly, services: ["Shelly", "HTTP"], ago: 30_000),
                dev("192.168.1.71", "Porch light", "smart", "Smart device", "smart", brand: shelly, services: ["Shelly"], ago: 5 * 86_400_000)]),
            ("computers", "Computers", false, [
                dev("192.168.1.80", "MacBook Pro", "computer", "Computer", "computers", brand: apple, services: ["SSH", "SMB"], ago: 35_000, link: "SSH"),
                dev("192.168.1.81", "Studio iMac", "computer", "Computer", "computers", brand: apple, services: ["SMB"], ago: 26 * 3_600_000)]),
            ("mobile", "Phones & tablets", false, [
                dev("192.168.1.90", "Galaxy Tab A11", "mobile", "Phone or tablet", "mobile", brand: samsung, ago: 12_000)]),
        ]
        return groups.map { id, title, open, devs in
            var counts: [String: (LanBrand, Int)] = [:]
            for d in devs { if let b = d.brand { counts[b.id] = (b, (counts[b.id]?.1 ?? 0) + 1) } }
            return LanCategory(id: id, title: title, connected: devs.filter(\.connected).count, total: devs.count, expanded: open, devices: devs,
                               brands: counts.values.sorted { ($1.1, $0.0.name) < ($0.1, $1.0.name) }.map(\.0))
        }
    }()

    /// Devices added to Bridgit on the phone: one checked camera, one not checked yet.
    static let added: [AddedDevice] = [
        AddedDevice(id: "lan:hallway", name: "Hallway camera", host: "192.168.1.31", kind: "camera", state: "on", applets: ["Pet monitor"],
                    model: "C210", verifiedAt: now - 3_600_000, checks: ["audio_in": "available", "audio_out": "unavailable", "tapo_talk": "available"]),
        AddedDevice(id: "lan:garden", name: "Garden camera", host: "192.168.1.32", kind: "camera", state: "blocked", applets: [],
                    model: nil, verifiedAt: nil, checks: [:]),
    ]

    static let events: [WatchEvent] = (0..<24).map { i in
        let paths = ["/v1/camera/snapshot", "/v1/phone/status", "/v1/interactions", "/v1/weather", "/v1/lan/discovered", "/v1/log"]
        let agents = ["Claude", "Cursor", "Bridgit"]
        return WatchEvent(id: 1000 + i, ts: now - Double(24 - i) * 95_000, agent: agents[i % 3], kind: "request",
                          method: i % 4 == 2 ? "POST" : "GET", endpoint: paths[i % paths.count], http: i == 17 ? 403 : 200, caption: nil, source: nil)
    }
}
