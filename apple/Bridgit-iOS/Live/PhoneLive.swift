import BridgitCore
import Combine
import CoreFoundation
import Foundation
import SwiftUI

/// Everything shown for one phone: Home scene state, features, network devices, activity, agents and
/// applets. Live data comes through the relay (the phone's own answers); otherwise the cloud snapshot.
/// The relay allows few calls a day per pairing, so refreshes are on demand and spaced out.
@MainActor
final class PhoneLive: ObservableObject {
    enum Source: Equatable { case live(Date), stale(Date), snapshot(Date?), sample, none }

    private(set) var device: PortalDevice
    private unowned let model: AppModel
    private let relay: RelayClient?
    private weak var localInventory: LocalAppletModel?
    private var selectionObservation: AnyCancellable?
    private var selectionGeneration = 0

    @Published var source: Source = .none
    @Published var facts = DeviceFacts()
    @Published var features: [FeatureRow] = []
    @Published var sceneOptions: [String: Any]?
    @Published var homeStatus: [String: Any]?
    @Published var paused = false
    @Published var listening: [String] = []
    @Published var categories: [LanCategory] = []
    @Published var lanAt: Date?
    @Published private(set) var inventoryReported = false
    @Published private(set) var inventoryError: String?
    var canScanNetwork: Bool { localInventory?.lan != nil || device.isLive || model.isSample }
    @Published private(set) var inventorySource: Source = .none
    var isLocalInventory: Bool { localInventory != nil }
    var inventoryDescription: String {
        if localInventory != nil { return "This device’s network" }
        switch inventorySource {
        case .none: return "Network inventory not reported"
        case .sample: return "Sample network inventory"
        case .snapshot(let contact):
            return "Account inventory · last known results" + (contact.map { " · last account contact " + $0.formatted(date: .abbreviated, time: .shortened) } ?? " · contact time not reported")
        case .live(let read): return "Read from linked device · " + read.formatted(date: .abbreviated, time: .shortened)
        case .stale(let read): return "Last known network inventory · " + read.formatted(date: .abbreviated, time: .shortened)
        }
    }
    func acceptInventory(_ values: [LanCategory], source: Source, at: Date?) {
        categories = values; inventoryReported = true; inventorySource = source; lanAt = at
    }
    /// The phone's own address on its network (`network.address` in GET /v1/portal/devices); nil until read.
    @Published var networkAddress: String?
    @Published var logs: [LogLine] = []
    @Published var hasOlder = false
    @Published private(set) var loadingOlderLogs = false
    @Published private(set) var olderLogsError: String?
    @Published private(set) var activityReported = false
    @Published var agents: [AgentRow] = []
    @Published var applets: [AppletRow] = []
    @Published private(set) var appletsReported = false
    @Published var liveError: String?
    @Published var busy = false

    private var latestLogId: Int?
    private var oldestLogId: Int?
    private var retainingLoadedHistory = false
    private var lastLive: Date?

    /// Without the relay's portal allowance, at most one live Home refresh per this many seconds.
    /// The relay allows 200 calls per pairing per UTC day (docs/apple-api.md), so one snapshot
    /// every 10 minutes while Home is open, plus pull-to-refresh.
    var minInterval: TimeInterval { relay == nil ? 30 : portalAllowance ? 15 : 600 }
    /// The phone marks portal agents (agents[].portal); their relay allowance is 20,000 calls a day.
    private var portalAllowance = false
    /// After a 429 the relay's daily allowance is used up until the next UTC midnight.
    private var quotaUntil: Date?

    init(device: PortalDevice, model: AppModel, localInventory: LocalAppletModel? = nil) {
        self.device = device
        self.localInventory = localInventory
        self.model = model
        self.relay = device.pairing.map { RelayClient(pairing: $0, outboundAccess: DeviceOutboundPolicy.access) }
        selectionObservation = model.$selectedDeviceID.dropFirst().sink { [weak self] _ in
            self?.selectionGeneration += 1
        }
        if localInventory == nil { applyCloud() }
    }

    /// New cloud snapshot for the same phone and pairing.
    func update(_ d: PortalDevice) {
        if device.id != d.id { selectionGeneration += 1 }
        device = d
        if relay == nil || source == .none { applyCloud() }
        else { applyAccountInventory() }
    }

    /// Read only the currently selected peer. A late reply cannot follow a selection change.
    func readAbilityProfile() async throws -> DeviceProfile {
        guard !model.isSample, let relay else { throw RelayError.offline }
        let selection = model.selectedDeviceID
        let identity = device.id
        let generation = selectionGeneration
        let accountIdentity = device.cloud?.phone ?? device.pairing?.install
        guard selection == identity || accountIdentity.map({ selection == "cloud:" + $0 }) == true else {
            throw CancellationError()
        }
        let response = try await relay.get("/v1/device/profile")
        try Task.checkCancellation()
        guard model.selectedDeviceID == selection, device.id == identity, selectionGeneration == generation else { throw CancellationError() }
        return try DeviceProfile.decode(response.body)
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
            // Revoked on the phone: drop the stored pairing (docs/apple-api.md, Revoking).
            if let p = device.pairing { model.removePairingIfCurrent(p) }
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

    func refreshDevices() async {
        if let localInventory {
            guard !busy else { return }
            guard let service = localInventory.lan else {
                inventoryError = "Network inventory is unavailable on this device."
                return
            }
            busy = true
            defer { busy = false }
            do {
                let response = try await service.ownerInventory()
                let object = try JSONSerialization.jsonObject(with: response.data)
                acceptInventory(Self.parseLan(object), source: .live(Date()), at: Date())
                networkAddress = Self.parseNetwork(object)
                added = []
                inventoryError = nil
                liveError = nil
            } catch {
                inventoryError = error.localizedDescription
                liveError = error.localizedDescription
                if case .live(let read) = inventorySource { inventorySource = .stale(read) }
            }
            return
        }

        if model.isSample { acceptInventory(SampleData.categories, source: .sample, at: Date()); added = SampleData.added; networkAddress = "192.168.1.23"; inventoryError = nil; return }
        guard let relay else { applyCloud(); return }
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let obj: Any
            do { obj = try await relay.getJSON("/v1/portal/devices", query: ["client": Self.client, "client_type": Self.clientType, "client_platform": Self.clientPlatform]) }
            catch let e as RelayError where Self.isMissingRoute(e) { obj = try await relay.getJSON("/v1/lan/discovered") }
            acceptInventory(Self.parseLan(obj), source: .live(Date()), at: Date())
            added = Self.parseAdded(obj) ?? []
            networkAddress = Self.parseNetwork(obj) ?? networkAddress
            lanAt = Date()
            inventoryError = nil
            liveError = nil
        } catch {
            inventoryError = error.localizedDescription
            liveError = error.localizedDescription
            if case .live(let read) = inventorySource { inventorySource = .stale(read) }
        }
    }

    @Published var ownerScanConfirmation = false
    private var ownerScanTask: Task<Void, Never>?
    func ownerCoverage() async throws -> Data {
        guard let service = localInventory?.lan else {
            throw NSError(domain: "NetworkCoverage", code: 1, userInfo: [NSLocalizedDescriptionKey: "Local discovery coverage is unavailable."])
        }
        return try await service.ownerCoverage().data
    }

    func requestNetworkScan() {
        if localInventory != nil { ownerScanConfirmation = true }
        else { Task { await runLevel2() } }
    }
    func confirmOwnerScan() {
        guard localInventory != nil, ownerScanTask == nil else { return }
        ownerScanTask = Task { [weak self] in
            guard let self else { return }
            defer { self.ownerScanTask = nil }
            await self.runLevel2()
        }
    }
    func cancelOwnerScan() { ownerScanTask?.cancel() }
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
        if let localInventory {
            guard !scanning, let service = localInventory.lan else { return }
            scanning = true; scanFound = nil; scanNote = "Looking for devices…"
            defer { scanning = false }
            do {
                let response = try await service.ownerScan(authorize: {
                    try Task.checkCancellation()
                    guard await MainActor.run(body: { UIApplication.shared.applicationState == .active }) else { throw CancellationError() }
                })
                let object = try JSONSerialization.jsonObject(with: response.data)
                acceptInventory(Self.parseLan(object), source: .live(Date()), at: Date())
                networkAddress = Self.parseNetwork(object)
                scanFound = Self.devices(in: categories).filter(\.connected).map(\.id)
                let report = object as? [String: Any] ?? [:]
                let methods = report["methods"] as? [[String: Any]] ?? []
                scanNote = methods.contains { $0["status"] as? String != "completed" }
                    ? "Some services could not be checked. This list may be incomplete." : nil
                liveError = nil
            } catch { scanNote = error.localizedDescription }
            return
        }

        if model.isSample {
            guard !scanning else { return }
            scanning = true; scanNote = "Looking for devices…"; scanFound = nil
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            acceptInventory(SampleData.categories, source: .sample, at: Date()); added = SampleData.added
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
            acceptInventory(Self.parseLan(obj), source: .live(Date()), at: Date())
            if let a = Self.parseAdded(obj) { added = a }
            networkAddress = Self.parseNetwork(obj) ?? networkAddress
            scanFound = Self.devices(in: categories).filter(\.connected).map(\.id)
            lanAt = Date()
            let d = obj as? [String: Any] ?? [:]
            let total = d["total_devices"] as? Int ?? categories.reduce(0) { $0 + $1.total }
            scanNote = total == 0 ? "No advertised devices found. Check that devices share this network. Some devices do not advertise themselves, and guest Wi-Fi may block discovery."
                : "\(total) devices in \(categories.count) categories · useful first" + ((d["complete"] as? Bool) == false ? "" : "")
        } catch RelayError.notAllowed {
            scanNote = "The phone doesn't let this pairing run network scans. On the phone, allow Network discovery for Bridgit."
        } catch {
            scanNote = error.localizedDescription
        }
    }

    // MARK: Camera (POST /v1/portal/photo)

    /// Whether photos can be sent: they need this phone's relay pairing (the cloud snapshot can't carry them).
    var canSendPhotos: Bool { relay != nil || model.isSample }

    /// The applets on the phone that check photos, and the owner's last pick on the phone's Camera tab.
    func photoTargets() async throws -> (targets: [PhotoTarget], chosen: [String]) {
        if model.isSample {
            var targets = [PhotoTarget(id: "sample-kitchen", name: "Kitchen cleanliness", watches: ["Kitchen cleanliness", "People"])]
            #if DEBUG
            if AppleFixtureWorkspace.enabled && CommandLine.arguments.contains("-fixtureTwoPhotoTargets") {
                targets = [PhotoTarget(id: "sample-alpha", name: "Fixture Alpha", watches: ["Alpha"]),
                           PhotoTarget(id: "sample-beta", name: "Fixture Beta", watches: ["Beta"])]
            }
            #endif
            return (targets, [])
        }
        guard let relay else { return ([], []) }
        return try await OwnerPhotos.targets(from: relay, client: Self.client, type: Self.clientType)
    }

    /// Sends one photo; the phone's photo watches check it and answer within about 25 seconds.
    func sendPhoto(_ jpeg: Data, front: Bool, targets: [String]) async throws -> [PhotoResult] {
        if model.isSample {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            return [PhotoResult(id: "sample-kitchen", name: "Kitchen cleanliness", lines: ["visible: yes · state: clean"])]
        }
        guard let relay else { throw RelayError.unknownPhone }
        return try await OwnerPhotos.send(jpeg, front: front, targets: targets, to: relay, client: Self.client, type: Self.clientType)
    }

    func loadOlderLogs() async {
        guard let relay, hasOlder, !loadingOlderLogs, let oldest = oldestLogId ?? logs.last?.id else { return }
        loadingOlderLogs = true; olderLogsError = nil
        defer { loadingOlderLogs = false }
        do {
            let obj = try await Self.logPage(query: ["until": String(oldest), "limit": "100"]) { path, query in
                try await relay.getJSON(path, query: query)
            }
            let page = Self.entries(obj)
            merge(page, keepOlder: true)
            retainOlderCursor(from: obj)
            hasOlder = (obj as? [String: Any])?["has_older"] as? Bool ?? (page.count >= 100)
        } catch {
            // A failed read is not proof that the device has no more history.
            if !Task.isCancelled { olderLogsError = error.localizedDescription }
        }
    }

    static func logPage(query: [String: String], get: (String, [String: String]) async throws -> Any) async throws -> Any {
        var portal = query
        portal["client"] = client; portal["client_type"] = clientType; portal["client_platform"] = clientPlatform
        do { return try await get("/v1/portal/logs", portal) }
        catch let error as RelayError where isMissingRoute(error) {
            var standard = query
            if standard["since"] != nil { standard["order"] = "asc" }
            return try await get("/v1/log", standard)
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
    nonisolated static var clientType: String { DeviceTypeDescription.local.deviceType ?? "device" }
    nonisolated static var clientPlatform: String { DeviceTypeDescription.local.platform ?? "ios" }
    nonisolated static var client: String { DeviceTypeDescription.label(DeviceTypeDescription.local) }
    /// "iPhone 16 Pro · iOS 26.1 · Bridgit 1.4": the phone's Linked devices map names this device with it.
    nonisolated static var clientDevice: String {
        let app = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        return [DeviceModel.name, "iOS " + UIDevice.current.systemVersion, app.isEmpty ? "" : "Bridgit " + app]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// One relay call for Home, agents, applets and the newest activity: GET /v1/portal/snapshot.
    private func loadLiveSnapshot() async throws {
        guard let relay else { return }
        let obj: Any
        do { obj = try await relay.getJSON("/v1/portal/snapshot", query: ["logs": "50", "client": Self.client, "client_type": Self.clientType, "client_platform": Self.clientPlatform, "client_device": Self.clientDevice]) }
        catch let e as RelayError where Self.isMissingRoute(e) {
            try await loadLiveHome()
            await loadLiveLogs(initial: logs.isEmpty)
            return
        }
        let s = obj as? [String: Any] ?? [:]
        // Clear once per relay response, preserving its scene/details merge below.
        agents = []
        applyPortalHome(s["home"] as? [String: Any] ?? [:])
        var extra: [String: Any] = [:]
        if let a = s["agents"] as? [String: Any] { extra["agents"] = a["agents"] } else if let a = s["agents"] { extra["agents"] = a }
        if let a = s["applets"] as? [String: Any] { extra["applets"] = a["applets"] } else if let a = s["applets"] { extra["applets"] = a }
        applyPortalHome(extra)
        let logsObj = s["logs"] ?? [:]
        if Self.hasActivityReport(logsObj) { activityReported = true }
        merge(Self.entries(logsObj))
        retainLiveCursor(from: logsObj)
        retainOlderCursor(from: logsObj)
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
            let obj = try await relay.getJSON("/v1/portal/home", query: ["client": Self.client, "client_type": Self.clientType, "client_platform": Self.clientPlatform, "client_device": Self.clientDevice])
            agents = []
            applyPortalHome(obj as? [String: Any] ?? [:])
        } catch let e as RelayError where Self.isMissingRoute(e) {
            // A phone without /v1/portal yet: the standard status routes give most of Home.
            let health = try await relay.getJSON("/v1/health") as? [String: Any] ?? [:]
            let reported = health["device"] as? [String: Any] ?? health
            facts.operatingSystem = .init(version: reported["os_version"] as? String,
                metadata: .init(device: reported), legacyAndroid: reported["android"] as? String)
            facts.appVersion = health["app_version"] as? String
            facts.battery = health["battery_level"] as? Int
            facts.charging = health["charging"] as? Bool
            if let caps = try? await relay.getJSON("/v1/capabilities") as? [String: Any] {
                applyCapabilities(caps["capabilities"] as? [String: Any] ?? [:])
            }
        }
    }

    func applyPortalHome(_ h: [String: Any]) {
        facts.operatingSystem = nil
        if let d = h["device"] as? [String: Any] {
            facts.model = [d["manufacturer"] as? String, d["model"] as? String].compactMap { $0 }.joined(separator: " ")
            let metadata = ReportedDeviceMetadata(device: d)
            if !metadata.isEmpty {
                device.reportedMetadata = metadata.fillingMissing(from: device.reportedMetadata)
                model.recordDeviceMetadata(device.reportedMetadata, for: device)
            }
            facts.operatingSystem = .init(version: d["os_version"] as? String, metadata: metadata,
                                          legacyAndroid: d["android"] as? String)
            facts.appVersion = d["app_version"] as? String
            // The phone's friendly name (set on the phone); a pairing keeps the name it had when paired otherwise.
            if let name = (d["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty, name != device.name {
                // Updating the remote report must not replace an owner's local alias.
                device.name = device.pairing?.localAlias ?? name
                if let p = device.pairing, p.name != name { model.relays.rename(p, to: name) }
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
            for f in list { if let id = f["id"] as? String { nodes[id] = ReportedFeatureState(feature: f).rawValue } }
            var opts = sceneOptions ?? [:]
            opts["nodeStates"] = nodes
            sceneOptions = opts
            features = list.compactMap { f in
                guard let id = f["id"] as? String else { return nil }
                return FeatureRow(id: id, name: f["name"] as? String ?? id, group: f["group"] as? String ?? Self.group(for: id),
                                  state: FeatureRow.State(feature: f), summary: f["summary"] as? String)
            }
        }
        if let s = h["scene"] as? [String: Any] {
            sceneOptions = s["options"] as? [String: Any]
            homeStatus = s["home_status"] as? [String: Any]
            paused = s["paused"] as? Bool ?? false
            listening = s["listening"] as? [String] ?? []
            if let hs = homeStatus {
                agents = (hs["agents"] as? [[String: Any]] ?? []).compactMap { a in
                    (a["name"] as? String).map { AgentRow(name: $0, detail: a["label"] as? String, lastUsed: nil, paused: (a["state"] as? String) == "Paused", statusReported: a["state"] as? String != nil) }
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
                             portal: a["portal"] as? Bool ?? false, statusReported: a["paused"] as? Bool != nil || scene[name]?.statusReported == true)
                }
            }
        }
        if let list = h["applets"] as? [[String: Any]] {
            appletsReported = true
            applets = list.compactMap { a in
                guard let id = a["id"] as? String else { return nil }
                let last = (a["last_at"] as? Double).flatMap { $0 > 0 ? $0 : nil }
                return AppletRow(id: id, name: a["name"] as? String ?? id, kind: a["kind"] as? String ?? "", running: Self.reportedFlag(a["running"]) ?? false,
                                 lastAt: last, runningReported: Self.reportedFlag(a["running"]) != nil, summary: (a["summary"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                                 sensors: a["sensors"] as? [String] ?? [], actions: a["actions"] as? [String] ?? [])
            }
            var status = homeStatus ?? [:]
            status["applets"] = applets.filter(\.running).map { ["name": $0.name, "state": "Running", "label": "Running", "description": "Running · " + $0.name, "icon": $0.icon] }
            status["emptyApplets"] = list.contains { Self.reportedFlag($0["running"]) == nil } ? "Applet status not reported" : "No applets running"
            homeStatus = status
        }
    }

    private func applyCapabilities(_ caps: [String: Any]) {
        var states: [String: String] = [:]
        features = caps.compactMap { (id, v) -> FeatureRow? in
            guard let m = v as? [String: Any] else { return nil }
            let reason = m["blocked_reason"] as? String
            let reported = ReportedFeatureState(feature: m)
            let state: FeatureRow.State = reported == .on && reason != nil && reason != "listener_off" && reason != "agent_not_allowed" ? .blocked : reported
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
            let obj = try await Self.logPage(query: q) { path, query in
                try await relay.getJSON(path, query: query)
            }
            let page = Self.entries(obj)
            if Self.hasActivityReport(obj) { activityReported = true }
            merge(page)
            retainLiveCursor(from: obj)
            if initial { retainOlderCursor(from: obj) }
            if initial { hasOlder = (obj as? [String: Any])?["has_older"] as? Bool ?? (page.count >= 100) }
        } catch {
            if logs.isEmpty { await loadCloudLogs() }
        }
    }

    // Portal cursors include redacted rows, so they can advance even on an empty page.
    // Never use next_since from an older-page response: it points at the server's
    // current latest ID, whose intervening visible entries have not been fetched.
    private func retainLiveCursor(from response: Any) {
        if let next = Self.logCursor(response, key: "next_since") {
            latestLogId = max(latestLogId ?? 0, next)
        }
    }

    private func retainOlderCursor(from response: Any) {
        oldestLogId = Self.olderLogCursor(response, current: oldestLogId)
    }

    nonisolated static func olderLogCursor(_ response: Any, current: Int?) -> Int? {
        guard let next = logCursor(response, key: "oldest_id") ?? entries(response).map(\.id).min() else { return current }
        return min(current ?? next, next)
    }

    nonisolated static func logCursor(_ response: Any, key: String) -> Int? {
        guard let value = (response as? [String: Any])?[key] as? NSNumber,
              CFGetTypeID(value) != CFBooleanGetTypeID(),
              let cursor = Int(value.stringValue), cursor >= 0 else { return nil }
        return cursor
    }

    private func merge(_ page: [LogLine], keepOlder: Bool = false) {
        if keepOlder { retainingLoadedHistory = true }
        if !page.isEmpty { activityReported = true }
        guard !page.isEmpty else { return }
        logs = Self.mergingLiveLogs(logs, page: page, retainingLoadedHistory: retainingLoadedHistory)
        latestLogId = max(latestLogId ?? 0, logs.first?.id ?? 0)
    }

    nonisolated static func mergingLiveLogs(_ existing: [LogLine], page: [LogLine], retainingLoadedHistory: Bool) -> [LogLine] {
        // Bound passive live polling, but never evict history explicitly requested by the user.
        mergingLogs(existing, page: page, limit: retainingLoadedHistory ? nil : 2000)
    }

    nonisolated static func mergingLogs(_ existing: [LogLine], page: [LogLine], limit: Int?) -> [LogLine] {
        var byID = Dictionary(existing.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for line in page { byID[line.id] = line }
        let sorted = byID.values.sorted { $0.id > $1.id }
        return limit.map { Array(sorted.prefix(max(0, $0))) } ?? sorted
    }

    nonisolated static func reportedFlag(_ raw: Any?) -> Bool? {
        guard let value = raw as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
        return value.boolValue
    }
    nonisolated static func hasActivityReport(_ obj: Any) -> Bool {
        if obj is [[String: Any]] { return true }
        guard let d = obj as? [String: Any] else { return false }
        return d["entries"] is [[String: Any]] || d["events"] is [[String: Any]]
    }

    nonisolated static func entries(_ obj: Any) -> [LogLine] {
        if let list = obj as? [[String: Any]] { return list.map(LogLine.init(raw:)) }
        let d = obj as? [String: Any] ?? [:]
        let list = (d["entries"] as? [[String: Any]]) ?? (d["events"] as? [[String: Any]]) ?? []
        return list.map(LogLine.init(raw:))
    }

    // MARK: cloud snapshot

    func applyCloud() {
        facts.operatingSystem = nil
        guard let p = device.cloud else { applyAccountInventory(); return }
        agents = []
        homeStatus?["agents"] = []
        sceneOptions?.removeValue(forKey: "agents")
        source = .snapshot(p.status_at.map { Date(timeIntervalSince1970: $0 / 1000) })
        if let s = p.status {
            facts.appVersion = s.app_version
            facts.model = [s.device?.manufacturer, s.device?.model].compactMap { $0 }.joined(separator: " ")
            facts.operatingSystem = .init(version: s.device?.os_version, metadata: p.reportedMetadata,
                                          legacyAndroid: s.device?.android)
            facts.connections = s.connections ?? [:]
            facts.services = s.services ?? [:]
            listening = s.listening ?? []
            features = (s.caps ?? [:]).map { id, st in
                FeatureRow(id: id, name: Self.title(for: id), group: Self.group(for: id), state: FeatureRow.State(reported: st), summary: nil)
            }.sorted { $0.name < $1.name }
            sceneOptions = ["capStates": s.caps ?? [:]]
            if let names = s.agents {
                sceneOptions?["agents"] = names.map { name -> [String: Any] in
                    var row: [String: Any] = ["name": name]
                    if let status = s.agent_status?.first(where: { $0.name == name }), let used = status.last_used_at { row["called"] = used > 0 }
                    return row
                }
            }
            let roster = AccountAgentRoster(phone: p)
            agents = roster.current.map { AgentRow(name: $0.name, detail: nil, lastUsed: $0.lastUsed, paused: $0.paused ?? false, statusReported: $0.paused != nil, accountSnapshot: true) }
            appletsReported = s.applets != nil
            applets = (s.applets ?? []).map { AppletRow(id: $0.id, name: $0.name ?? $0.kind, kind: $0.kind, running: $0.running ?? false, lastAt: $0.last_at, runningReported: $0.running != nil,
                                                       sensors: $0.sensors ?? [], actions: $0.actions ?? []) }
            homeStatus = ["agents": agents.map { ["name": $0.name, "state": $0.reportedState,
                                                  "label": $0.reportedState,
                                                  "description": $0.reportedState + " · " + $0.name] },
                          "applets": applets.filter(\.running).map { ["name": $0.name, "state": "Running", "label": "Running",
                                                                       "description": "Running · " + $0.name, "icon": $0.icon] },
                          "emptyApplets": s.applets == nil || s.applets?.contains(where: { $0.running == nil }) == true ? "Applet status not reported" : "No applets running", "position": "{index} of {count}", "rotate": !UIAccessibility.isVoiceOverRunning]
        }
        applyAccountInventory()
    }

    private func applyAccountInventory() {
        // Account refreshes never replace a network read obtained from this relay pairing.
        if relay != nil {
            switch inventorySource { case .live, .stale: return; default: break }
        }
        added = []; networkAddress = nil; scanFound = nil
        guard let p = device.cloud, let inv = p.devices else {
            categories = []; lanAt = nil; inventoryReported = false; inventorySource = .none
            return
        }
        let devs = inv.map { d in
            LanDevice(id: d.id, name: d.name, typeLabel: (d.type ?? "device").capitalized, host: nil, model: d.model, brand: nil, services: [],
                      connected: d.online == true, lastSeen: d.last_connected_at, interfaced: true, interfaceLabel: "Bridgit interfaced",
                      category: "cameras", kind: d.type?.lowercased(), connectionReported: d.online != nil,
                      connectionCheckedAt: d.connection_checked_at, accountSnapshot: true)
        }
        let values = devs.isEmpty ? [] : [LanCategory(id: "cameras", title: "Cameras", connected: devs.filter(\.connected).count, total: devs.count, expanded: true, devices: devs)]
        // The account contact time is not a probe/inventory observation time.
        acceptInventory(values, source: .snapshot(p.last_push_at.map { Date(timeIntervalSince1970: $0 / 1000) }), at: nil)
    }

    private func loadCloudLogs() async {
        guard let p = device.cloud else { return }
        if let events = try? await model.events(for: p, since: nil) {
            activityReported = true
            merge(events.map(LogLine.init(event:)))
        }
    }

    private func loadSample() {
        source = .sample
        activityReported = true
        if !AppleFixtureWorkspace.deviceOSFacts || device.cloud?.phone.hasPrefix("bgi_fixture_os_") != true {
            facts = SampleData.facts
        }
        features = SampleData.features
        agents = SampleData.agentRows
        appletsReported = true
        applets = SampleData.appletRows
        acceptInventory(SampleData.categories, source: .sample, at: Date()); added = SampleData.added
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

    nonisolated static func parseLan(_ value: Any) -> [LanCategory] { NetworkInventory.parseLan(value) }
    nonisolated static func brand(_ value: Any?) -> LanBrand? { NetworkInventory.brand(value) }
    nonisolated static func parseNetwork(_ value: Any) -> String? { NetworkInventory.parseNetwork(value) }
    nonisolated static func parseAdded(_ value: Any) -> [AddedDevice]? { NetworkInventory.parseAdded(value) }
    nonisolated static func devices(in categories: [LanCategory]) -> [LanDevice] { NetworkInventory.devices(in: categories) }

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
