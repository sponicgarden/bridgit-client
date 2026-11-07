import Foundation
import SwiftUI
import BridgitAppletRuntime

/// Everything shown for one phone: Home scene state, features, network devices, activity, agents and
/// applets. Live data comes through the relay (the phone's own answers); otherwise the cloud snapshot.
/// The relay allows few calls a day per pairing, so refreshes are on demand and spaced out.
@MainActor
final class PhoneLive: ObservableObject {
    enum Source: Equatable { case live(Date), stale(Date), snapshot(Date?), sample, none }

    private(set) var device: PortalDevice
    private unowned let model: AppModel
    private let relay: RelayClient?

    @Published var source: Source = .none
    @Published var facts = DeviceFacts()
    @Published var features: [FeatureRow] = []
    @Published var sceneOptions: [String: Any]?
    @Published var homeStatus: [String: Any]?
    @Published var paused = false
    @Published var listening: [String] = []
    @Published var categories: [LanCategory] = []
    @Published var lanAt: Date?
    /// The phone's own address on its network (`network.address` in GET /v1/portal/devices); nil until read.
    @Published var networkAddress: String?
    @Published var logs: [LogLine] = []
    @Published var hasOlder = false
    @Published var agents: [AgentRow] = []
    @Published var applets: [AppletRow] = []
    @Published var liveError: String?
    @Published var busy = false

    private var latestLogId: Int?
    private var lastLive: Date?

    /// Without the relay's portal allowance, at most one live Home refresh per this many seconds.
    /// The relay allows 200 calls per pairing per UTC day (docs/ios-portal-api.md), so one snapshot
    /// every 10 minutes while Home is open, plus pull-to-refresh.
    var minInterval: TimeInterval { relay == nil ? 30 : portalAllowance ? 15 : 600 }
    /// The phone marks portal agents (agents[].portal); their relay allowance is 20,000 calls a day.
    private var portalAllowance = false
    /// After a 429 the relay's daily allowance is used up until the next UTC midnight.
    private var quotaUntil: Date?

    init(device: PortalDevice, model: AppModel) {
        self.device = device
        self.model = model
        self.relay = device.pairing.map { RelayClient(pairing: $0) }
        applyCloud()
    }

    /// New cloud snapshot for the same phone and pairing.
    func update(_ d: PortalDevice) {
        device = d
        if relay == nil || source == .none { applyCloud() }
    }

    var canRefreshLive: Bool {
        guard relay != nil else { return false }
        guard let lastLive else { return true }
        return Date().timeIntervalSince(lastLive) >= minInterval
    }

    // MARK: refresh

    func refresh(force: Bool = false) async {
        if model.isSample { loadSample(); return }
        if relay == nil {
            await model.refresh()
            if let id = device.cloud?.phone, let fresh = model.phones.first(where: { $0.phone == id }) { device.cloud = fresh }
            applyCloud()
            await loadCloudLogs()
            return
        }
        guard !busy, force || canRefreshLive else { return }
        if let quotaUntil, Date() < quotaUntil { liveError = RelayError.quota.errorDescription; return }
        busy = true
        defer { busy = false }
        do {
            try await loadLiveSnapshot()
            lastLive = Date()
            source = .live(Date())
            liveError = nil
        } catch RelayError.revoked {
            // Revoked on the phone: drop the stored pairing (docs/ios-portal-api.md, Revoking).
            if let p = device.pairing { model.removePairing(p) }
            model.notice = .init(title: "\(device.name) removed this device", message: "The phone's owner disconnected this iPhone or iPad. Pair again from the phone to view it.")
        } catch {
            if case RelayError.quota = error {
                var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(identifier: "UTC")!
                quotaUntil = utc.nextDate(after: Date(), matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime)
            }
            liveError = error.localizedDescription
            // Keep what was read, but never call it live once a refresh failed.
            if let lastLive { source = .stale(lastLive) } else { applyCloud() }
            if logs.isEmpty { await loadCloudLogs() }
        }
    }

    struct Enrollment: Identifiable, Decodable {
        let id: String
        let name: String
        let adapter: String
        let availability: String
        let verified_at: Int64
    }

    static func decodeEnrollments(_ data: Data, template: String, revision: Int) throws -> [Enrollment] {
        struct Inventory: Decodable {
            let template: String
            let revision: Int
            let devices: [Enrollment]
        }
        let inventory = try JSONDecoder().decode(Inventory.self, from: data)
        guard inventory.template == template, inventory.revision == revision,
              Set(inventory.devices.map(\.id)).count == inventory.devices.count,
              inventory.devices.allSatisfy({ !$0.id.isEmpty && $0.availability == "not_checked" }) else {
            throw RelayError.failed(409, "The phone returned a different applet edition or an invalid inventory.")
        }
        return inventory.devices
    }

    struct MonitoredWatch: Identifiable, Decodable {
        enum State: String, Decodable {
            case scheduled, paused, budget_paused, blocked, ended
            var label: String {
                switch self {
                case .scheduled: "Scheduled"
                case .paused: "Paused"
                case .budget_paused: "Paused by budget"
                case .blocked: "Data source unavailable"
                case .ended: "Ended"
                }
            }
        }
        let key: String
        var id: String { key }
        let state: State
        let checks: Int?
        let matches: Int?
        let last_check_at: String?
        let last_outcome: String?
        let recent_checks: [JSONValue]?
        let tally: JSONValue?

        func cardSource(read: String) -> JSONValue? {
            if read == "tally" { return tally }
            guard read == "status" else { return nil }
            var fields = [String: JSONValue]()
            if let checks { fields["checks"] = .number(Double(checks)) }
            if let matches { fields["matches"] = .number(Double(matches)) }
            if let recent_checks { fields["recent_checks"] = .array(recent_checks) }
            return .object(fields)
        }
    }

    static func decodeMonitorWatches(_ data: Data, template: String, revision: Int) throws -> [MonitoredWatch] {
        struct Snapshot: Decodable {
            let template: String
            let revision: Int
            let watches: [MonitoredWatch]
        }
        let result = try JSONDecoder().decode(Snapshot.self, from: data)
        guard result.template == template, result.revision == revision,
              Set(result.watches.map(\.key)).count == result.watches.count else {
            throw RelayError.failed(409, "The phone returned a different edition or an invalid watch list.")
        }
        return result.watches
    }

    private func readApplet(_ path: String, template: String, revision: Int) async throws -> Data {
        guard model.mode == .signedIn, let relay, let install = device.pairing?.install,
              device.cloud?.phone == install else { throw RelayError.revoked }
        return try await relay.get(path, query: ["template": template, "revision": String(revision)]).body
    }

    func enrollments(template: String, revision: Int) async throws -> [Enrollment] {
        let data = try await readApplet("/v1/portal/applets/inventory", template: template, revision: revision)
        return try Self.decodeEnrollments(data, template: template, revision: revision)
    }

    func monitorWatches(template: String, revision: Int) async throws -> [MonitoredWatch] {
        let data = try await readApplet("/v1/portal/applets/watches", template: template, revision: revision)
        return try Self.decodeMonitorWatches(data, template: template, revision: revision)
    }

    func refreshDevices() async {
        if model.isSample { categories = SampleData.categories; added = SampleData.added; networkAddress = "192.168.1.23"; lanAt = Date(); return }
        guard let relay else { applyCloud(); return }
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let obj: Any
            do { obj = try await relay.getJSON("/v1/portal/devices", query: ["client": Self.client]) }
            catch let e as RelayError where Self.isMissingRoute(e) { obj = try await relay.getJSON("/v1/lan/discovered") }
            categories = Self.parseLan(obj)
            added = Self.parseAdded(obj) ?? []
            networkAddress = Self.parseNetwork(obj) ?? networkAddress
            lanAt = Date()
            liveError = nil
        } catch {
            liveError = error.localizedDescription
        }
    }

    @Published var scanning = false
    @Published var scanNote: String?
    /// Ids of the devices the latest Level 2 scan saw (seen within two minutes); nil when it failed.
    /// The Devices map flies these in one by one, as the APK's map does while its own scan runs.
    @Published var scanFound: [String]?
    /// Cameras and smart devices added to Bridgit on the phone (the APK lists them first, "Added to Bridgit").
    /// GET /v1/portal/devices carries them as `added`; older phones leave it out, so it stays empty then.
    @Published var added: [AddedDevice] = []

    /// Level 2 Network Device Diagnostics: a bounded scan on the phone's own network (POST /v1/lan/discover,
    /// up to about 20 seconds), with the same grouping and counts as the APK's Network devices screen.
    func runLevel2() async {
        if model.isSample {
            guard !scanning else { return }
            scanning = true; scanNote = "Looking for devices…"; scanFound = nil
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            categories = SampleData.categories; added = SampleData.added; lanAt = Date()
            scanFound = Self.devices(in: categories).filter(\.connected).map(\.id)
            scanNote = "\(categories.reduce(0) { $0 + $1.total }) devices in \(categories.count) categories · useful first"
            scanning = false
            return
        }
        guard let relay, !scanning else { return }
        scanning = true
        scanNote = "Looking for devices…"
        scanFound = nil
        defer { scanning = false }
        do {
            let r = try await relay.call(method: "POST", path: "/v1/lan/discover", query: [:], body: [String: Any](), wait: 50)
            let obj = try JSONSerialization.jsonObject(with: r.body.isEmpty ? Data("{}".utf8) : r.body)
            categories = Self.parseLan(obj)
            if let a = Self.parseAdded(obj) { added = a }
            networkAddress = Self.parseNetwork(obj) ?? networkAddress
            scanFound = Self.devices(in: categories).filter(\.connected).map(\.id)
            lanAt = Date()
            let d = obj as? [String: Any] ?? [:]
            let total = d["total_devices"] as? Int ?? categories.reduce(0) { $0 + $1.total }
            scanNote = total == 0 ? "No advertised devices found. Check that devices share this network. Some devices do not advertise themselves, and guest Wi-Fi may block discovery."
                : "\(total) devices in \(categories.count) categories · useful first" + ((d["complete"] as? Bool) == false ? "" : "")
        } catch RelayError.notAllowed {
            scanNote = "The phone doesn't let this pairing run network scans. On the phone, allow Network discovery for Bridgit Portal."
        } catch {
            scanNote = error.localizedDescription
        }
    }

    // MARK: Camera (POST /v1/portal/photo)

    /// Whether photos can be sent: they need this phone's relay pairing (the cloud snapshot can't carry them).
    var canSendPhotos: Bool { relay != nil || model.isSample }

    /// The applets on the phone that check photos, and the owner's last pick on the phone's Camera tab.
    func photoTargets() async throws -> (targets: [PhotoTarget], chosen: [String]) {
        if model.isSample { return ([PhotoTarget(id: "sample-kitchen", name: "Kitchen cleanliness", watches: ["Kitchen cleanliness", "People"])], []) }
        guard let relay else { return ([], []) }
        return OwnerPhotos.parseTargets(try await relay.getJSON("/v1/portal/photo/targets", query: ["client": Self.client]))
    }

    /// Sends one photo; the phone's photo watches check it and answer within about 25 seconds.
    func sendPhoto(_ jpeg: Data, front: Bool, targets: [String]) async throws -> [PhotoResult] {
        if model.isSample {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            return [PhotoResult(id: "sample-kitchen", name: "Kitchen cleanliness", lines: ["visible: yes · state: clean"])]
        }
        guard let relay else { throw RelayError.unknownPhone }
        let r = try await relay.call(method: "POST", path: "/v1/portal/photo", query: ["client": Self.client],
                                     body: OwnerPhotos.body(jpeg: jpeg, front: front, targets: targets), wait: 50)
        return OwnerPhotos.parseResults(try JSONSerialization.jsonObject(with: r.body.isEmpty ? Data("{}".utf8) : r.body))
    }

    func loadOlderLogs() async {
        guard let relay, hasOlder, let oldest = logs.last?.id else { return }
        do {
            let obj = try await relay.getJSON("/v1/portal/logs", query: ["until": String(oldest), "limit": "100", "client": Self.client])
            let page = Self.entries(obj)
            logs.append(contentsOf: page)
            hasOlder = (obj as? [String: Any])?["has_older"] as? Bool ?? (page.count >= 100)
        } catch {
            hasOlder = false
        }
    }

    // MARK: live

    private static func isMissingRoute(_ e: RelayError) -> Bool {
        switch e {
        case .failed(let s, _): return s == 404 || s == 400 || s == 403
        case .notAllowed: return true
        default: return false
        }
    }

    /// Which kind of device is viewing; the phone's own Home draws it as the viewer (API revision 61).
    nonisolated static var client: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone" }
    /// "iPhone 16 Pro · iOS 26.1 · Portal 1.4": the phone's Linked devices map names this device with it.
    nonisolated static var clientDevice: String {
        let app = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        return [DeviceModel.name, "iOS " + UIDevice.current.systemVersion, app.isEmpty ? "" : "Portal " + app]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// One relay call for Home, agents, applets and the newest activity: GET /v1/portal/snapshot.
    private func loadLiveSnapshot() async throws {
        guard let relay else { return }
        let obj: Any
        do { obj = try await relay.getJSON("/v1/portal/snapshot", query: ["logs": "50", "client": Self.client, "client_device": Self.clientDevice]) }
        catch let e as RelayError where Self.isMissingRoute(e) {
            try await loadLiveHome()
            await loadLiveLogs(initial: logs.isEmpty)
            return
        }
        let s = obj as? [String: Any] ?? [:]
        applyPortalHome(s["home"] as? [String: Any] ?? [:])
        var extra: [String: Any] = [:]
        if let a = s["agents"] as? [String: Any] { extra["agents"] = a["agents"] } else if let a = s["agents"] { extra["agents"] = a }
        if let a = s["applets"] as? [String: Any] { extra["applets"] = a["applets"] } else if let a = s["applets"] { extra["applets"] = a }
        applyPortalHome(extra)
        let logsObj = s["logs"] ?? [:]
        merge(Self.entries(logsObj))
        if let next = (logsObj as? [String: Any])?["next_since"] as? Int { latestLogId = max(latestLogId ?? 0, next) }
        hasOlder = (logsObj as? [String: Any])?["has_older"] as? Bool ?? false
        // The phone states this pairing's relay allowance (1.8.1833+); older phones don't, so stay slow then.
        if let jobs = s["relay_daily_jobs"] as? Int {
            portalAllowance = jobs >= 5000
        } else if let list = (s["agents"] as? [String: Any])?["agents"] as? [[String: Any]] ?? s["agents"] as? [[String: Any]] {
            portalAllowance = list.contains { ($0["portal"] as? Bool) == true && ($0["transport"] as? String) == "relay" } && device.pairing?.portal == true
        }
    }

    private func loadLiveHome() async throws {
        guard let relay else { return }
        do {
            let obj = try await relay.getJSON("/v1/portal/home", query: ["client": Self.client, "client_device": Self.clientDevice])
            applyPortalHome(obj as? [String: Any] ?? [:])
        } catch let e as RelayError where Self.isMissingRoute(e) {
            // A phone without /v1/portal yet: the standard status routes give most of Home.
            let health = try await relay.getJSON("/v1/health") as? [String: Any] ?? [:]
            facts.appVersion = health["app_version"] as? String
            facts.battery = health["battery_level"] as? Int
            facts.charging = health["charging"] as? Bool
            if let caps = try? await relay.getJSON("/v1/capabilities") as? [String: Any] {
                applyCapabilities(caps["capabilities"] as? [String: Any] ?? [:])
            }
        }
    }

    private func applyPortalHome(_ h: [String: Any]) {
        if let d = h["device"] as? [String: Any] {
            facts.model = [d["manufacturer"] as? String, d["model"] as? String].compactMap { $0 }.joined(separator: " ")
            facts.android = d["android"] as? String
            facts.appVersion = d["app_version"] as? String
            // The phone's friendly name (set on the phone); a pairing keeps the name it had when paired otherwise.
            if let name = (d["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty, name != device.name {
                device.name = name
                if let p = device.pairing, p.name != name { model.relays.rename(p, to: name); model.links.rename(relayDevice: p.deviceId, to: name) }
            }
        }
        if let b = h["battery"] as? [String: Any] {
            facts.battery = b["percent"] as? Int
            facts.charging = b["charging"] as? Bool
        }
        if let c = h["connections"] as? [String: Any] { facts.connections = c.compactMapValues { "\($0)" } }
        if let p = h["paused"] as? Bool { paused = p }
        if let l = h["listening"] as? [String] { listening = l }
        if let list = h["features"] as? [[String: Any]] {
            // The ids are the Home scene's node ids, with the phone's own on/off/blocked decision (HomeStates).
            var nodes: [String: String] = [:]
            for f in list { if let id = f["id"] as? String, let st = f["state"] as? String { nodes[id] = st } }
            var opts = sceneOptions ?? [:]
            opts["nodeStates"] = nodes
            sceneOptions = opts
            features = list.compactMap { f in
                guard let id = f["id"] as? String else { return nil }
                return FeatureRow(id: id, name: f["name"] as? String ?? id, group: f["group"] as? String ?? Self.group(for: id),
                                  state: FeatureRow.State(rawValue: f["state"] as? String ?? "off") ?? .off, summary: f["summary"] as? String)
            }
        }
        if let s = h["scene"] as? [String: Any] {
            sceneOptions = s["options"] as? [String: Any]
            homeStatus = s["home_status"] as? [String: Any]
            paused = s["paused"] as? Bool ?? false
            listening = s["listening"] as? [String] ?? []
            if let hs = homeStatus {
                agents = (hs["agents"] as? [[String: Any]] ?? []).compactMap { a in
                    (a["name"] as? String).map { AgentRow(name: $0, detail: a["label"] as? String, lastUsed: nil, paused: (a["state"] as? String) == "Paused") }
                }
            }
        }
        if let list = h["agents"] as? [[String: Any]] {
            // Merge with the scene's agent states by name rather than replacing them.
            let scene = Dictionary(agents.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
            agents = list.compactMap { a in
                (a["name"] as? String).map { name in
                    AgentRow(name: name, detail: scene[name]?.detail,
                             lastUsed: (a["last_used_at"] as? Double).flatMap { $0 > 0 ? $0 : nil }, paused: a["paused"] as? Bool ?? scene[name]?.paused ?? false,
                             transport: a["transport"] as? String, mode: a["mode"] as? String, model: a["model"] as? String,
                             portal: a["portal"] as? Bool ?? false)
                }
            }
        }
        if let list = h["applets"] as? [[String: Any]] {
            applets = list.compactMap { a in
                guard let id = a["id"] as? String else { return nil }
                let last = (a["last_at"] as? Double).flatMap { $0 > 0 ? $0 : nil }
                return AppletRow(id: id, name: a["name"] as? String ?? id, kind: a["kind"] as? String ?? "", running: a["running"] as? Bool ?? false,
                                 lastAt: last, summary: (a["summary"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                                 sensors: a["sensors"] as? [String] ?? [], actions: a["actions"] as? [String] ?? [])
            }
        }
    }

    private func applyCapabilities(_ caps: [String: Any]) {
        var states: [String: String] = [:]
        features = caps.compactMap { (id, v) -> FeatureRow? in
            guard let m = v as? [String: Any] else { return nil }
            let enabled = m["enabled"] as? Bool ?? false
            let reason = m["blocked_reason"] as? String
            let state: FeatureRow.State = !enabled ? .off : (reason == nil || reason == "listener_off" || reason == "agent_not_allowed") ? .on : .blocked
            states[id] = state.rawValue
            return FeatureRow(id: id, name: m["title"] as? String ?? id, group: Self.group(for: id), state: state, summary: nil)
        }.sorted { $0.name < $1.name }
        sceneOptions = ["capStates": states, "agents": agents.map { ["name": $0.name, "called": true] }]
    }

    private func loadLiveLogs(initial: Bool) async {
        guard let relay else { return }
        do {
            var q = ["limit": "100"]
            if !initial, let latestLogId { q["since"] = String(latestLogId) }
            let obj: Any
            do { var pq = q; pq["client"] = Self.client; obj = try await relay.getJSON("/v1/portal/logs", query: pq) }
            catch let e as RelayError where Self.isMissingRoute(e) {
                var lq = q
                if lq["since"] != nil { lq["order"] = "asc" }
                obj = try await relay.getJSON("/v1/log", query: lq)
            }
            let page = Self.entries(obj)
            merge(page)
            if initial { hasOlder = (obj as? [String: Any])?["has_older"] as? Bool ?? (page.count >= 100) }
        } catch {
            if logs.isEmpty { await loadCloudLogs() }
        }
    }

    private func merge(_ page: [LogLine]) {
        guard !page.isEmpty else { return }
        var byId = Dictionary(logs.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for l in page { byId[l.id] = l }
        logs = byId.values.sorted { $0.id > $1.id }
        if logs.count > 2000 { logs.removeLast(logs.count - 2000) }
        latestLogId = max(latestLogId ?? 0, logs.first?.id ?? 0)
    }

    nonisolated static func entries(_ obj: Any) -> [LogLine] {
        let d = obj as? [String: Any] ?? [:]
        let list = (d["entries"] as? [[String: Any]]) ?? (d["events"] as? [[String: Any]]) ?? []
        return list.map(LogLine.init(raw:))
    }

    // MARK: cloud snapshot

    func applyCloud() {
        guard let p = device.cloud else { return }
        source = .snapshot(p.status_at.map { Date(timeIntervalSince1970: $0 / 1000) })
        if let s = p.status {
            facts.appVersion = s.app_version
            facts.model = [s.device?.manufacturer, s.device?.model].compactMap { $0 }.joined(separator: " ")
            facts.android = s.device?.android
            facts.connections = s.connections ?? [:]
            facts.services = s.services ?? [:]
            listening = s.listening ?? []
            features = (s.caps ?? [:]).map { id, st in
                FeatureRow(id: id, name: Self.title(for: id), group: Self.group(for: id), state: FeatureRow.State(rawValue: st) ?? .off, summary: nil)
            }.sorted { $0.name < $1.name }
            sceneOptions = ["capStates": s.caps ?? [:], "agents": (s.agents ?? []).map { ["name": $0, "called": true] }]
            agents = (s.agent_status ?? []).map { AgentRow(name: $0.name, detail: nil, lastUsed: $0.last_used_at, paused: $0.paused ?? false) }
            if agents.isEmpty { agents = (p.agent_history ?? []).map { AgentRow(name: $0.name, detail: nil, lastUsed: $0.last_used_at, paused: false) } }
            applets = (s.applets ?? []).map { AppletRow(id: $0.id, name: $0.name ?? $0.kind, kind: $0.kind, running: $0.running ?? false, lastAt: $0.last_at,
                                                       sensors: $0.sensors ?? [], actions: $0.actions ?? []) }
            homeStatus = ["agents": agents.map { ["name": $0.name, "state": $0.paused ? "Paused" : "Listening",
                                                  "label": $0.paused ? "Paused" : "Listening",
                                                  "description": ($0.paused ? "Paused · " : "Listening for ") + $0.name] },
                          "applets": applets.filter(\.running).map { ["name": $0.name, "state": "Running", "label": "Running",
                                                                       "description": "Running · " + $0.name, "icon": $0.icon] },
                          "emptyApplets": "No applets running", "position": "{index} of {count}", "rotate": !UIAccessibility.isVoiceOverRunning]
        }
        if let inv = p.devices, !inv.isEmpty {
            let devs = inv.map { d in
                LanDevice(id: d.id, name: d.name, typeLabel: (d.type ?? "device").capitalized, host: nil, model: d.model, brand: nil, services: [],
                          connected: d.online == true, lastSeen: d.last_connected_at, interfaced: true, interfaceLabel: "Bridgit interfaced",
                          category: "cameras", kind: d.type?.lowercased())
            }
            categories = [LanCategory(id: "cameras", title: "Cameras", connected: devs.filter(\.connected).count, total: devs.count, expanded: true, devices: devs)]
            lanAt = p.last_push_at.map { Date(timeIntervalSince1970: $0 / 1000) }
        }
    }

    private func loadCloudLogs() async {
        guard let p = device.cloud else { return }
        if let events = try? await model.events(for: p, since: nil) {
            merge(events.map(LogLine.init(event:)))
        }
    }

    private func loadSample() {
        source = .sample
        facts = SampleData.facts
        features = SampleData.features
        agents = SampleData.agentRows
        applets = SampleData.appletRows
        categories = SampleData.categories; added = SampleData.added
        lanAt = Date()
        logs = SampleData.events.map(LogLine.init(event:)).sorted { $0.id > $1.id }
        listening = ["voicerec"]
        sceneOptions = ["capStates": Dictionary(uniqueKeysWithValues: features.map { ($0.id, $0.state.rawValue) }),
                        "agents": agents.map { ["name": $0.name, "called": true] }]
        homeStatus = ["agents": agents.map { ["name": $0.name, "state": $0.paused ? "Paused" : "Listening", "label": $0.paused ? "Paused" : "Listening",
                                              "description": "Listening for " + $0.name] },
                      "applets": applets.filter(\.running).map { ["name": $0.name, "state": "Running", "label": "Running", "description": "Running · " + $0.name, "icon": $0.icon] },
                      "emptyApplets": "No applets running", "position": "{index} of {count}", "rotate": true]
    }

    // MARK: LAN parsing (GET /v1/lan/discovered, docs/network-discovery.md)

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
                             kind: text(x["type"])?.lowercased(), modelIdentifier: rawModel == model ? nil : rawModel)
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

    // MARK: names and groups (same grouping as bridgit.cc/myapps "Phone parts")

    nonisolated static func group(for id: String) -> String {
        switch id {
        case "wakewords", "mic", "camera", "video", "screen", "screen_share", "gallery", "gallery_video", "sounds", "ocr": "Senses"
        case "phone", "status", "location", "weather", "notifications", "sensors", "usage", "health_connect": "Awareness"
        case "adb", "speaker", "controls", "settings", "ring", "flashlight", "schedule", "lan_camera", "lan_control", "lan_discovery": "Controls"
        case "interactions", "text", "feedback": "Talks with you"
        case "judgment", "watches", "vision": "Decisions"
        default: "Other"
        }
    }

    nonisolated static func title(for id: String) -> String {
        let names: [String: String] = ["wakewords": "Wake words", "mic": "Microphone", "camera": "Camera", "video": "Video clips", "screen": "Screen",
                                       "gallery": "Photos and videos", "sounds": "Sound alerts", "ocr": "Text recognition", "phone": "Battery and status",
                                       "status": "Status", "location": "Location", "weather": "Weather", "notifications": "Notifications",
                                       "sensors": "Motion and sensors", "usage": "App usage", "health_connect": "Health records", "adb": "Taps and typing",
                                       "speaker": "Speaker", "controls": "Sound and display", "settings": "Bridgit settings", "ring": "Find my phone",
                                       "flashlight": "Flashlight", "schedule": "Scheduled actions", "interactions": "Questions and cards",
                                       "feedback": "Bug reports", "lan_discovery": "Network discovery", "lan_camera": "Network cameras",
                                       "lan_control": "Network control", "watches": "Watches", "judgment": "Judgment"]
        return names[id] ?? id.replacingOccurrences(of: "_", with: " ").capitalized
    }
}
