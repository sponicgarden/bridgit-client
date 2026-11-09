import BridgitAppletRuntime
import BridgitCore
import Combine
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The local applet runtime is independent of account snapshots and all saved view credentials.
@MainActor
final class LocalAppletModel: ObservableObject {
    @Published private(set) var host: LocalHostModel?
    @Published private(set) var packages: [[String: Any]] = []
    @Published private(set) var inboxBadgeCount = 0
    @Published private(set) var revision = 0
    @Published private(set) var modelEnabled = false
    @Published private(set) var registering = false
    @Published var problem: String?
    @Published private(set) var batches: [String: AppletBatch] = [:]
    lazy var configuration = OwnerConfigurationModel(local: self)
    let speech: LocalSpeechModel
    let fixtureMode: Bool
    let settings: UserDefaults?
    private(set) var store: AppletStore?
    private(set) var engine: AppletEngine?
    private(set) var lan: HostLanService?
    @Published private(set) var lanDiscoveryEnabled = false
    private(set) var connections: OutboundConnectionService?
    private(set) var inboundSharing: InboundSharingModel?
    private var install: InstallClient?
    private var modelConsentGeneration = UUID()
    private var watches: [String: Task<Void, Never>] = [:]
    private let directory: URL

    func setLanDiscoveryEnabled(_ value: Bool) {
        settings?.set(value, forKey: "lan.discovery.enabled")
        lanDiscoveryEnabled = value
    }

    private static var developmentBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
    init(directory: URL? = nil, hostCameraPermission: (() async -> Bool)? = nil, hostMicrophonePermission: (() async -> Bool)? = nil, settings: UserDefaults? = nil, inboundInbox: InboundShareInbox? = nil) {
        fixtureMode = AppleFixtureWorkspace.enabled
        self.settings = settings ?? (fixtureMode ? nil : AppleFixtureWorkspace.defaults)
        lanDiscoveryEnabled = self.settings?.bool(forKey: "lan.discovery.enabled") ?? false
        modelEnabled = self.settings?.bool(forKey: "cloud_appraisals.enabled") ?? false
        speech = fixtureMode ? LocalSpeechModel(registration: { InstallClient(signer: try .ephemeral(), base: URL(string: "https://offline.invalid")!) }, settings: self.settings) : LocalSpeechModel(settings: self.settings)
        self.directory = directory ?? (fixtureMode
            ? FileManager.default.temporaryDirectory.appendingPathComponent(AppleFixtureWorkspace.appletDirectoryName, isDirectory: true)
            : AppletStorageLocation.directory(applicationSupport: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0], bundleIdentifier: Bundle.main.bundleIdentifier ?? "cc.bridgit.local.debug", development: Self.developmentBuild))
        do {
            let store = try AppletStore(directory: self.directory); self.store = store
            inboundSharing = InboundSharingModel(store: store, inbox: inboundInbox)
            lan = try HostLanService(directory: self.directory.appendingPathComponent("lan"), outboundAccess: DeviceOutboundPolicy.access, enabled: { [weak self] in
                await MainActor.run { self?.lanDiscoveryEnabled == true }
            })
            connections = OutboundConnectionService(store: store, outboundAccess: DeviceOutboundPolicy.access, deviceName: { await MainActor.run { LocalHostModel.deviceName } })
            let speech = self.speech
            speech.packageForBudget = { try await store.package($0) }
            engine = AppletEngine(store: store, speech: { operation, step, applet in try await speech.run(operation: operation, step: step, applet: applet) }, checkPrerequisites: { required in
                guard required.contains("camera") else { return }
                try await MainActor.run {
                    guard AppleCamera.hardware else { throw DeviceAPIError(422, "camera_unavailable", "This applet requires a camera. This device has none available.") }
                    guard AppleCamera.permission else { throw DeviceAPIError(403, "camera_permission_required", "Allow camera access for Bridgit to install this applet.") }
                }
            }) { [weak self] bytes, max, applet in
                guard let self else { throw CancellationError() }
                return try await self.appraise(bytes, max: max, applet: applet)
            }
            host = LocalHostModel(local: self, cameraPermission: hostCameraPermission, microphonePermission: hostMicrophonePermission)
            Task {
                await reload()
                #if DEBUG
                if !AppleFixtureWorkspace.creatorNames.isEmpty {
                    await host?.prepareCreatorFixture()
                }
                if AppleFixtureWorkspace.standalone && CommandLine.arguments.contains("-fixtureRotationAgent") {
                    await host?.prepareRotationFixture()
                }
                if AppleFixtureWorkspace.transferFixture { await seedTransferFixture() }
                if CommandLine.arguments.contains("-fixtureInbox") { await seedInboxFixture() }
                if CommandLine.arguments.contains("-fixtureOriginalCard"),
                   let encoded = ProcessInfo.processInfo.environment["BRIDGIT_ORIGINAL_CARD_FIXTURE"],
                   let data = Data(base64Encoded: encoded), data.count <= 131_072,
                   let package = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                    do { try await store.install(package); await reload() }
                    catch { problem = error.localizedDescription }
                }
                #endif
            }
        } catch { problem = error.localizedDescription }
    }
    #if DEBUG
    private func seedTransferFixture() async {
        guard let store, AppleFixtureWorkspace.transferFixture else { return }
        do {
            if try await store.state("transfer-fixture-switches") == nil {
                for kind in TransferKind.allCases { try await store.setTransferEnabled(kind, enabled: true) }
                try await store.setState("transfer-fixture-switches", value: "1")
            }
            if try await store.state("transfer-fixture-ready") != nil { return }
            let authority = HostAuthority(store: store, deviceID: "offline-transfer-fixture")
            let agent: HostAgent
            if let existing = try await authority.list().first(where: { $0.name == "File review fixture" }) {
                agent = existing
            } else {
                (agent, _) = try await authority.create(name: "File review fixture", operations: [.files, .shares, .downloads], applets: [])
            }
            for kind in [TransferKind.files, .shares] {
                if try await store.transfers(for: agent, kind: kind).isEmpty {
                    _ = try await store.saveTransfer(for: agent, kind: kind, name: kind.rawValue + "-fixture.txt",
                                                     mime: "text/plain", bytes: Data("Offline Bridgit file review.".utf8))
                }
            }
            try await store.setState("transfer-fixture-ready", value: "1")
        } catch { problem = error.localizedDescription }
    }

    /// Isolated local UI acceptance: GET the canonical sample, then deliver it through
    /// the real scoped dispatcher. No relay enrollment, network or production data.
    private func seedInboxFixture() async {
        guard fixtureMode, let store, let engine else { return }
        do {
            let authority = HostAuthority(store: store, deviceID: "ui-fixture")
            let agent: HostAgent
            if let existing = try await authority.list().first(where: { $0.name == "Design fixture" && !$0.paused }) { agent = existing }
            else { (agent, _) = try await authority.create(name: "Design fixture", operations: [.deviceRead, .interactionsRead, .interactionsWrite], applets: []) }
            await authority.setState(enabled: true, awake: true, foreground: true)
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() }, samples: { try AppletLists.sample($0) })
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            func job(_ sequence: Int64, _ request: HostRequest) -> HostJob {
                .init(id: UUID().uuidString, sequence: now + sequence, agentID: agent.id, generation: agent.generation, deviceID: "ui-fixture", deadline: now + 30000, createdAt: now, request: request)
            }
            let sample = await dispatcher.dispatch(job(1, .init(method: "GET", path: "/v1/samples", query: ["id": "html-connection-test"])))
            guard sample.status == 200, let data = try JSONSerialization.jsonObject(with: sample.data) as? [String: Any], var body = data["request"] as? [String: Any] else { throw DeviceAPIError(500, "fixture_failed", "The canonical connection sample did not load.") }
            body["no_takeover"] = true
            let result = await dispatcher.dispatch(job(2, .init(method: "POST", path: "/v1/interactions", body: body, idempotencyKey: "fixture-connection")))
            guard result.status == 201 else { throw DeviceAPIError(500, "fixture_failed", String(decoding: result.data, as: UTF8.self)) }
        } catch { problem = error.localizedDescription }
    }
    #endif
    var inboxBadgeLabel: String? {
        guard inboxBadgeCount > 0 else { return nil }
        return inboxBadgeCount > 99 ? "99+" : String(inboxBadgeCount)
    }

    func observeInboxBadge() async {
        guard let store else { return }
        var expiryTask: Task<Void, Never>?
        defer { expiryTask?.cancel() }
        for await _ in await store.inboxChanges() {
            guard !Task.isCancelled else { break }
            expiryTask?.cancel()
            guard let badge = try? await store.inboxBadge(), !Task.isCancelled else { continue }
            inboxBadgeCount = badge.count
            if let expiry = badge.nextExpiry {
                expiryTask = Task {
                    var next: Date? = expiry
                    while let due = next {
                        do { try await Task.sleep(for: .seconds(max(0, due.timeIntervalSinceNow))) }
                        catch { return }
                        guard let updated = try? await store.inboxBadge(), !Task.isCancelled else { return }
                        inboxBadgeCount = updated.count
                        next = updated.nextExpiry
                    }
                }
            }
        }
    }

    func reload() async {
        guard let store else { return }
        do {
            var values = try await store.packages()
            for index in values.indices {
                guard let id = values[index]["id"] as? String else { continue }
                values[index]["installation_status"] = try await store.paused(id) ? "paused" : "active"
                if let engine { batches[id] = await engine.batch(applet: id) }
            }
            packages = values; revision += 1
        }
        catch { problem = error.localizedDescription }
    }
    func installInventory() async {
        guard let engine, let url = Bundle.main.url(forResource: "insurance-inventory", withExtension: "json", subdirectory: "Applets") else {
            problem = "The bundled Insurance inventory package did not load."; return
        }
        do { try await engine.install(Data(contentsOf: url), didInstall: { [weak self] id in self?.speech.disable(applet: id) }); await reload() }
        catch { problem = error.localizedDescription }
    }
    func installPackage(_ data: Data) async {
        guard let engine else { return }
        do {
            try await engine.install(data, didInstall: { [weak self] id in self?.speech.disable(applet: id) })
            await reload()
        }
        catch { problem = error.localizedDescription }
    }
    func enableModels(authorize: (() async throws -> Void)? = nil, commit: (() async throws -> Void)? = nil) async {
        guard !registering else { return }
        if fixtureMode { do { try await authorize?(); try await commit?(); modelEnabled = true; settings?.set(true, forKey: "cloud_appraisals.enabled") } catch { problem = error.localizedDescription }; return }
        let run = modelConsentGeneration
        registering = true; defer { registering = false }; problem = nil
        do {
            let signer = try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "cc.bridgit") + ".install-signing.v1")
            let client = InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access)
            _ = try await client.register(appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local")
            try await authorize?()
            guard speech.foreground, run == modelConsentGeneration else { return }
            try await commit?()
            guard speech.foreground, run == modelConsentGeneration else { throw CancellationError() }
            install = client; modelEnabled = true; settings?.set(true, forKey: "cloud_appraisals.enabled")
        } catch { problem = error.localizedDescription }
    }
    func disableModels() {
        modelEnabled = false; settings?.set(false, forKey: "cloud_appraisals.enabled")
        suspendModels()
    }
    func suspendModels() {
        modelConsentGeneration = UUID()
        for applet in watches.keys { Task { await engine?.cancelBatch(applet: applet) } }
    }
    private func appraise(_ bytes: Data, max: Int, applet: String) async throws -> [String: Any] {
        guard modelEnabled else { throw DeviceAPIError(403, "model_consent_required", "Enable cloud appraisals before sending a photo to the model.") }
        guard speech.foreground else { throw DeviceAPIError(409, "foreground_required", "Open Bridgit before running an appraisal.") }
        if fixtureMode {
            return ["model": "local-fixture", "cost_usd": 0, "currency": "USD", "items": [["label": "Fixture desk lamp", "category": "Furniture", "quantity": 1,
                "brand": NSNull(), "model": NSNull(), "condition": "good", "new_usd": 80, "used_usd": 35, "insurable": true,
                "insurable_note": "Local fixture, not a real appraisal", "confidence": 0.9, "box": ["x0": 0.05, "y0": 0.05, "x1": 0.95, "y1": 0.95]]]]
        }
        if install == nil { await enableModels() }
        guard modelEnabled, speech.foreground, let install else { throw DeviceAPIError(409, "install_not_registered", "This device is not registered for model calls.") }
        let generation = modelConsentGeneration
        guard let store else { throw DeviceAPIError(409, "budget_unconfirmed", "The applet store is unavailable.") }
        return try await install.appraise(jpeg: Self.jpeg(bytes), maxItems: max, applet: applet, package: store.package(applet)) {
            guard generation == self.modelConsentGeneration, self.modelEnabled, self.speech.foreground else {
                throw DeviceAPIError(403, "model_consent_required", "Cloud appraisal access changed.")
            }
        }
    }
    /// Decode first and apply EXIF orientation. Large imports never become unbounded decoded UI images.
    nonisolated static func jpeg(_ data: Data, maxEdge: Int = 1024, quality: Double = 0.85) throws -> Data {
        guard data.count <= 30 * 1024 * 1024, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: maxEdge] as CFDictionary) else {
            throw DeviceAPIError(422, "invalid_photo", "Choose a supported image no larger than 30 MB.")
        }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { throw DeviceAPIError(500, "image_failed", "The image encoder is unavailable.") }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: min(1, max(0.4, quality))] as CFDictionary)
        guard CGImageDestinationFinalize(destination), output.length <= 10 * 1024 * 1024 else { throw DeviceAPIError(413, "photo_too_large", "The resized photo is too large.") }
        return output as Data
    }
    func importPhoto(applet: String, data: Data, action: [String: Any] = [:], requestedBy: HostAgent? = nil) async throws -> [String: Any] {
        guard let engine else { throw DeviceAPIError(503, "storage_unavailable", "Applet storage is unavailable.") }
        let row = try await engine.addPhoto(applet: applet, jpeg: Self.jpeg(data, maxEdge: 2048), table: action["table"] as? String ?? "photos", column: action["photo"] as? String ?? "photo", initial: action["row"] as? [String: Any] ?? [:], requestedBy: requestedBy)
        revision += 1
        return row
    }
    func addPhoto(applet: String, data: Data, action: [String: Any] = [:]) async {
        do { _ = try await importPhoto(applet: applet, data: data, action: action) }
        catch { problem = error.localizedDescription }
    }
    func startBatch(applet: String, button: String = "create") async {
        guard let engine else { return }
        do {
            let package = try await store?.package(applet) ?? [:]
            let pieces = ((package["ui"] as? [String: Any])?["card"] as? [String: Any])?["pieces"] as? [[String: Any]] ?? []
            let selected = pieces.filter { $0["type"] as? String == "buttons" }.flatMap { $0["items"] as? [[String: Any]] ?? [] }.first { $0["id"] as? String == button }
            let flow = ((selected?["do"] as? [String: Any])?["flow"] as? [String: Any])?["$flow"] as? String
            guard !Self.requiresAppraisal(package: package, flow: flow) || modelEnabled else { problem = "Enable cloud appraisals first."; return }
            try await engine.startBatch(applet: applet, button: button)
            observeBatch(applet)
        } catch { problem = error.localizedDescription }
    }
    func observeBatch(_ applet: String) {
        guard let engine else { return }
        watches[applet]?.cancel()
        watches[applet] = Task { [weak self] in
            while !Task.isCancelled {
                let state = await engine.batch(applet: applet)
                guard let self else { return }
                self.batches[applet] = state; self.revision += 1
                if state.state != "running" { self.watches[applet] = nil; return }
                do { try await Task.sleep(nanoseconds: 500_000_000) } catch { return }
            }
        }
    }
    func appletChanged(_ id: String, event: String) async {
        if ["install", "update", "remove"].contains(event) { speech.disable(applet: id) }
        if event == "pause" { speech.stop(applet: id) }
        if event == "remove" { watches[id]?.cancel(); watches[id] = nil; batches[id] = nil }
        await reload()
        if event == "batch" { observeBatch(id) }
    }
    func pause(applet: String, paused: Bool) async {
        do { try await engine?.pause(applet: applet, paused: paused); await appletChanged(applet, event: "pause") }
        catch { problem = error.localizedDescription }
    }
    func remove(applet: String) async {
        do {
            let retained = try await engine?.remove(applet: applet, didRemove: { [weak self] id in self?.speech.disable(applet: id) }) ?? []
            await appletChanged(applet, event: "remove")
            if retained.contains(where: { $0.hasPrefix("kept:") }) { problem = "Some saved photos could not be removed." }
        } catch { problem = error.localizedDescription }
    }
    static func requiresAppraisal(package: [String: Any], flow: String? = nil) -> Bool {
        let flows = ((package["processing"] as? [String: Any])?["flows"] as? [[String: Any]]) ?? []
        return flows.filter { flow == nil || $0["id"] as? String == flow }.contains { ($0["steps"] as? [[String: Any]] ?? []).contains { $0["type"] as? String == "appraise" } }
    }
    func cancel(applet: String) { speech.stop(applet: applet); Task { await engine?.cancelBatch(applet: applet) } }
    func update(applet: String, table: String, row: String, values: [String: Any]) async throws {
        guard let store else { throw DeviceAPIError(503, "storage_unavailable", "Applet storage is unavailable.") }
        _ = try await store.update(applet: applet, table: table, rowID: row, values: values); revision += 1
    }
    func delete(applet: String, table: String, row: String) async {
        do { try await store?.delete(applet: applet, table: table, rowID: row); revision += 1 }
        catch { problem = error.localizedDescription }
    }
    func exportData(applet: String, table: String, declaration: [String: Any]? = nil) async throws -> Data {
        guard let store else { throw DeviceAPIError(503, "storage_unavailable", "Applet storage is unavailable.") }
        let package = try await store.package(applet)
        guard let definition = try AppletStore.tableDefinitions(package).first(where: { $0["id"] as? String == table }) else { throw DeviceAPIError(404, "table_not_found", "Choose a declared table.") }
        let definitions = definition["columns"] as? [String: [String: Any]] ?? [:]
        let columns = AppletPresentation.columns(declaration?["columns"], definitions: definitions)
        let rows = try await store.rows(applet: applet, table: table, limit: 10000)
        var selected = rows
        if let predicate = declaration?["where"] {
            let decoder = JSONDecoder()
            let filter = try CSVRowFilter(decoder.decode(BridgitAppletRuntime.JSONValue.self, from:
                JSONSerialization.data(withJSONObject: predicate, options: .fragmentsAllowed)), columns: Set(definitions.keys))
            selected = try rows.filter { row in
                let typed = try decoder.decode([String: BridgitAppletRuntime.JSONValue].self, from: JSONSerialization.data(withJSONObject: row))
                return filter.includes(typed)
            }
        }
        let ordered = AppletPresentation.groups(selected, by: declaration?["group_by"] as? String).flatMap { $0.1 }
        let data = AppletExport.csv(rows: ordered, columns: columns.map { ($0.key, $0.label) })
        guard data.count <= 20 * 1024 * 1024 else { throw DeviceAPIError(413, "export_too_large", "This export is too large. Read paginated rows through the data API.") }
        return data
    }
    func export(applet: String, table: String, declaration: [String: Any]? = nil) async throws -> URL {
        let data = try await exportData(applet: applet, table: table, declaration: declaration)
        let exports = directory.appendingPathComponent("Exports", isDirectory: true)
        try FileManager.default.createDirectory(at: exports, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let url = exports.appendingPathComponent(applet + "-" + UUID().uuidString.lowercased() + ".csv")
        try data.write(to: url, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        return url
    }
}

struct LocalCameraTarget: Identifiable {
    var id: String, applet: String, name: String, label: String
    var action: [String: Any]
}

extension LocalAppletModel {
    var cameraTargets: [LocalCameraTarget] {
        packages.flatMap { package -> [LocalCameraTarget] in
            guard package["installation_status"] as? String != "paused", let applet = package["id"] as? String else { return [] }
            let pieces = ((package["ui"] as? [String: Any])?["card"] as? [String: Any])?["pieces"] as? [[String: Any]] ?? []
            return pieces.filter { $0["type"] as? String == "buttons" }.flatMap { $0["items"] as? [[String: Any]] ?? [] }.compactMap { button in
                guard let action = (button["do"] as? [String: Any])?["camera"] as? [String: Any] else { return nil }
                return .init(id: applet + ":" + (button["id"] as? String ?? "camera"), applet: applet, name: package["name"] as? String ?? applet, label: button["label"] as? String ?? "Take photos", action: action)
            }
        }
    }
}
