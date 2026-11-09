import AVFoundation
import BridgitCore
import Combine
import Foundation
import ImageIO
import Speech
#if os(macOS)
import AppKit
#else
import UIKit
#endif

private struct NativeSatelliteSecrets: SatelliteSecrets {
    func load(_ id: String) async throws -> String? { Keychain.get("satellite:" + id) }
    func save(_ value: String, for id: String) async throws {
        guard Keychain.set(value, for: "satellite:" + id) else { throw DeviceAPIError(503, "keychain_failed", "Could not save the satellite key on this device.") }
    }
    func remove(_ id: String) async throws {
        guard Keychain.set(nil, for: "satellite:" + id) else { throw DeviceAPIError(503, "keychain_failed", "Could not erase the satellite key on this device.") }
    }
}
private struct NativeCallbackSecrets: SatelliteSecrets {
    func load(_ id: String) async throws -> String? { Keychain.get("host-callback:" + id) }
    func save(_ value: String, for id: String) async throws {
        guard Keychain.set(value, for: "host-callback:" + id) else { throw DeviceAPIError(503, "keychain_failed", "Could not save the callback key on this device.") }
    }
    func remove(_ id: String) async throws {
        guard Keychain.set(nil, for: "host-callback:" + id) else { throw DeviceAPIError(503, "keychain_failed", "Could not erase the callback key on this device.") }
    }
}
private actor FixtureCallbackSecrets: SatelliteSecrets {
    private var values: [String: String] = [:]
    func load(_ id: String) async throws -> String? { values[id] }
    func save(_ value: String, for id: String) async throws { values[id] = value }
    func remove(_ id: String) async throws { values.removeValue(forKey: id) }
}
struct NativeHubLink: Identifiable {
    let agent: HostAgent
    let info: SatelliteHubInfo?
    var id: String { agent.id }
}

/// Owner-controlled host lifecycle. This object never reads or upgrades a viewer pairing.
@MainActor final class LocalHostModel: ObservableObject {
    @Published private(set) var state: RelayHostConnection.State = .stopped
    @Published private(set) var agents: [HostAgent] = []
    @Published private(set) var deviceID: String?
    @Published private(set) var activity: [String] = []
    @Published private(set) var ownerDirectAddress: TailnetAddress?
    @Published private(set) var ownerDirectPort: UInt16?
    @Published private(set) var ownerDirectStarting = false
    @Published private(set) var ownerDirectIntent = false
    @Published private(set) var ownerDirectProblem: String?
    private var satelliteGeneration = UUID()
    @Published private(set) var satelliteIntent = false
    @Published private(set) var satelliteAddress: TailnetAddress?
    @Published private(set) var satellitePort: UInt16?
    @Published private(set) var satelliteProblem: String?
    @Published private(set) var linkedSatellites: [LinkedSatellite] = []
    @Published private(set) var hubLinks: [NativeHubLink] = []
    @Published var problem: String?
    @Published var oneTimeSetup: String?
    @Published private(set) var busy = false
    @Published private(set) var creatingAgent = false
    @Published private(set) var callbackInternetAllowed = false
    @Published private(set) var cameraEnabled = false
    @Published private(set) var microphoneEnabled = false
    @Published private(set) var microphoneTranscriptionAllowed = false
    @Published private(set) var wakeEnabled = false
    @Published private(set) var wakeStatus = "off"
    @Published private(set) var backgroundReading = false
    private var apnsToken: Data?
    private var wakeRevision: UInt64 = 0
    private let wakePreparationOverride: (() async throws -> Void)?
    private let wakeRegistrationOverride: (() -> Void)?
    private var backgroundWakeTask: Task<Void, Never>?
    private var backgroundReadDeadline: Date?
    private let camera = AppleCamera()
    var captureNotices = CaptureNotices.shared
    private let cameraPermission: () async -> Bool
    private let microphonePermission: () async -> Bool
    private var cameraConsentGeneration = UUID()
    private var microphoneConsentGeneration = UUID()
    private weak var local: LocalAppletModel?
    private var identity: RelayHostIdentity?
    private(set) var authority: HostAuthority?
    private var callbacks: CallbackHostService?
    private var connection: RelayHostConnection?
    private var ownerDirectHosting: OwnerDirectHostService?
    @Published private(set) var ownerDirectPreferredAddress: TailnetAddress?
    private var ownerDirectGeneration = UUID()
    private var satelliteHosting: SatelliteHostService?
    private var satelliteHub: SatelliteHubService?
    #if DEBUG
    private var fixtureDispatcher: HostDispatcher?
    #endif
    private var foreground = true
    private var awake = true
    private var startRequested = false
    var enabledIntent: Bool { startRequested }
    private var pendingStart = false
    private var drainingStarts = false
    private let startOverride: (() async -> Void)?
    private let stopOverride: (() async -> Void)?
    private let satelliteStartOverride: ((TailnetAddress, UInt16) async throws -> UInt16)?
    private let satelliteStopOverride: (() async -> Void)?
    #if os(macOS)
    static let allowsBackgroundAwake = true
    #else
    static let allowsBackgroundAwake = false
    #endif
    private var executionAllowed: Bool { awake && (foreground || Self.allowsBackgroundAwake) }
    private var lifecycleGeneration: UInt64 = 0
    private var relayStartGeneration: UInt64 = 0
    private let outboundAccess: OutboundAccess
    init(local: LocalAppletModel, startConnection: (() async -> Void)? = nil, stopConnection: (() async -> Void)? = nil, cameraPermission: (() async -> Bool)? = nil, microphonePermission: (() async -> Bool)? = nil, wakePreparation: (() async throws -> Void)? = nil, wakeRegistration: (() -> Void)? = nil, satelliteStart: ((TailnetAddress, UInt16) async throws -> UInt16)? = nil, satelliteStop: (() async -> Void)? = nil, outboundAccess: OutboundAccess = DeviceOutboundPolicy.access) {
        self.outboundAccess = outboundAccess
        self.wakePreparationOverride = wakePreparation; self.wakeRegistrationOverride = wakeRegistration
        self.cameraPermission = cameraPermission ?? { await AppleCamera.requestPermission() }
        self.microphonePermission = microphonePermission ?? { await AVCaptureDevice.requestAccess(for: .audio) }
        self.local = local; self.startOverride = startConnection; self.stopOverride = stopConnection
        self.satelliteStartOverride = satelliteStart; self.satelliteStopOverride = satelliteStop
        cameraEnabled = local.settings?.bool(forKey: "host.camera") ?? false
        microphoneEnabled = local.settings?.bool(forKey: "host.microphone") ?? false
        microphoneTranscriptionAllowed = SFSpeechRecognizer.authorizationStatus() == .authorized
        startRequested = local.settings?.bool(forKey: "host.enabled") ?? false
        ownerDirectIntent = local.settings?.bool(forKey: "host.ownerDirect.enabled") ?? false
        ownerDirectPreferredAddress = local.settings?.string(forKey: "host.ownerDirect.address").flatMap(TailnetAddress.init)
        #if os(iOS)
        wakeEnabled = startRequested && (local.settings?.bool(forKey: "host.wake") ?? false)
        foreground = UIApplication.shared.applicationState == .active
        wakeStatus = wakeEnabled ? "awaiting_reconnect" : "off"
        #endif
    }
    private func persistIntent() {
        local?.settings?.set(startRequested, forKey: "host.enabled")
        local?.settings?.set(wakeEnabled, forKey: "host.wake")
    }
    func resumeConfiguredHost() async {
        await resumeOwnerDirect()
        if startRequested, state == .stopped || state == .failed { await start() }
    }
    func prepare(identityOverride: RelayHostIdentity? = nil) async {
        guard !busy, identity == nil else { return }; busy = true
        defer { busy = false; if pendingStart { Task { await self.drainStarts() } } }
        do {
            guard let local, let store = local.store, let engine = local.engine else { throw DeviceAPIError(503, "storage_unavailable", "Applet storage is unavailable.") }
            let identity = try identityOverride ?? (local.fixtureMode ? RelayHostIdentity.ephemeral() : RelayHostIdentity.loadOrCreate(service: Bundle.main.bundleIdentifier ?? "cc.bridgit.local"))
            let authority = HostAuthority(store: store, deviceID: identity.deviceID, allowsBackgroundAwake: Self.allowsBackgroundAwake)
            await authority.setState(enabled: false, awake: awake, foreground: foreground)
            await authority.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
            callbackInternetAllowed = try await store.callbackInternetAllowed()
            let callbackSecrets: any SatelliteSecrets = local.fixtureMode ? FixtureCallbackSecrets() : NativeCallbackSecrets()
            let candidateCallbacks = CallbackHostService(authority: authority, store: store, secrets: callbackSecrets,
                appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local",
                outboundAccess: outboundAccess, policy: { .init(tailnetDomain: nil, allowInternet: (try? await store.callbackInternetAllowed()) == true) })
            let callbacks: CallbackHostService?
            do { try await candidateCallbacks.start(); callbacks = candidateCallbacks }
            catch { callbacks = nil; problem = "Callbacks unavailable: " + error.localizedDescription }
            self.callbacks = callbacks
            let profile: HostDispatcher.Profile = { [weak self] agent in
                guard let self else { throw CancellationError() }
                await self.captureNotices.refresh()
                return try await self.profile(for: agent, enabledTransfers: store.enabledTransfers())
            }
            let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, appletChanged: { [weak local] id, event in await local?.appletChanged(id, event: event) }, profile: profile, sensor: { [weak self] request, agent in
                guard let self else { throw CancellationError() }; return try await self.sensor(request, agent: agent)
            }, samples: { try AppletLists.sample($0) }, configuration: local.configuration, connections: local.connections,
            additionalApplets: { [weak local] agent in
                guard let viewer = await local?.configuration.viewer, await viewer.configuredSignedIn else { return [] }
                let controller = await PortableLibraryController(cloud: viewer.configuredCloud)
                try await authority.authorizeOngoing(agent, operation: .appletsRead)
                return try await MaintainedAppletHost.installed(controller, agent: agent)
            }, additionalAppletAction: { [weak local] request, agent in
                guard let viewer = await local?.configuration.viewer, await viewer.configuredSignedIn else { return nil }
                let controller = await PortableLibraryController(cloud: viewer.configuredCloud)
                return try await MaintainedAppletHost.handle(request, agent: agent, controller: controller, coreStore: store, directAuthority: authority) {
                    guard let route = request.route else { throw DeviceAPIError(501, "unsupported_operation", "Unknown operation.") }
                    try await authority.authorizeOngoing(agent, operation: route.0, applet: route.1)
                }
            }, additionalViewerApplets: { [weak local] _ in
                guard let viewer = await local?.configuration.viewer, await viewer.configuredSignedIn else { return [] }
                let controller = await PortableLibraryController(cloud: viewer.configuredCloud)
                // Dispatcher checks the owner-issued viewer grant before and after delivery.
                return try await MaintainedAppletHost.viewerNames(controller)
            }, satelliteForward: { [weak self] request, agent, authorize in
                guard let hub = await self?.satelliteHub else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite storage is unavailable.") }
                return try await hub.forward(request, agent: agent, authorize: authorize)
            }, satelliteList: { [weak self] agent in
                guard let hub = await self?.satelliteHub else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite storage is unavailable.") }
                return try await hub.publicSatellites(for: agent)
            }, feedback: { request, agent in
                try await FeedbackReports.shared.agentRequest(request, agent: agent, authority: authority, activity: store)
            }, lan: local.lan, callbacks: callbacks, captureNotifications: { [weak self] in
                guard let self else { return false }
                await self.captureNotices.refresh()
                return await self.captureNotices.availability == .allowed
            })
            let secrets = NativeSatelliteSecrets()
            let satellite = SatelliteHostService(authority: authority, dispatcher: dispatcher, store: store, secrets: secrets, unavailable: { [weak self] in await self?.satelliteListenerUnavailable() }, profile: profile)
            let hub = SatelliteHubService(store: store, authority: authority, secrets: secrets, endpoint: { [weak satellite] in
                guard let bound = await satellite?.boundEndpoint else { throw DeviceAPIError(409, "listener_required", "Start this device’s Tailscale listener before adding a satellite.") }
                return bound
            }, localName: { await MainActor.run { Self.deviceName } }, localType: { await MainActor.run { DeviceTypeDescription.local.deviceType } },
            sharedView: { request, agent in try await dispatcher.satelliteReturnView(request, agent: agent) })
            await satellite.setReturnHandler { request, agent in try await hub.returnCall(request, agent: agent) }
            self.satelliteHosting = satellite; self.satelliteHub = hub
            self.ownerDirectHosting = OwnerDirectHostService(authority: authority, dispatcher: dispatcher, unavailable: { [weak self] in
                await self?.ownerDirectUnavailable()
            })
            #if DEBUG
            if local.fixtureMode { fixtureDispatcher = dispatcher }
            #endif
            let base = URL(string: "https://bridgit-relay.bridgit-app.workers.dev/d/" + identity.deviceID)!
            let connection = try RelayHostConnection(base: base, identity: identity, authority: authority, dispatcher: dispatcher, outboundAccess: outboundAccess) { [weak self] state, message in
                await self?.changed(state, message: message)
            }
            self.identity = identity; self.deviceID = identity.deviceID; self.authority = authority; self.connection = connection
            agents = try await authority.list()
            FeedbackReports.shared.reconcileTeamKeys(agents)
            note("Host identity prepared locally. No relay connection started.")
        } catch { problem = error.localizedDescription }
    }
    #if DEBUG
    func prepareRotationFixture() async {
        guard local?.fixtureMode == true else { return }
        await prepare()
        guard let authority else { return }
        do {
            _ = try await authority.createOwnerDirectFixture(name: "Rotation fixture", operations: [.deviceRead], applets: [])
            _ = try await authority.createViewer(name: "Viewer fixture")
            agents = try await authority.list()
        } catch { problem = error.localizedDescription }
    }

    /// Seed activity through the real request admission path using an ephemeral offline host.
    func prepareCreatorFixture() async {
        let names = AppleFixtureWorkspace.creatorNames
        guard local?.fixtureMode == true, !names.isEmpty else { return }
        await prepare()
        guard let authority, let deviceID, let fixtureDispatcher else { return }
        do {
            state = .connected
            await authority.setState(enabled: true, awake: true, foreground: true)
            for (index, name) in names.enumerated() {
                let notesFixture = CommandLine.arguments.contains("-fixtureHostNotes")
                var operations: Set<HostOperation> = notesFixture ? [.interactionsRead, .log] : [.interactionsRead]
                if CommandLine.arguments.contains("-fixtureRenameNotes") { operations.insert(.configurationWrite) }
                let (agent, _) = try await authority.create(name: name, operations: operations, applets: [])
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                let poll = HostJob(id: UUID().uuidString, sequence: 1, agentID: agent.id, generation: agent.generation,
                                   deviceID: deviceID, deadline: now + 30_000, createdAt: now,
                                   request: .init(method: "GET", path: "/v1/events"))
                _ = await fixtureDispatcher.dispatch(poll)
                if notesFixture {
                    let body: [String: Any] = ["message": index == 0 ? "Fixture error note" : "Fixture info note",
                                               "level": index == 0 ? "error" : "info",
                                               "data": ["fixture": index == 0 ? "first" : "second", "count": 7]]
                    let job = HostJob(id: UUID().uuidString, sequence: 2, agentID: agent.id, generation: agent.generation,
                                      deviceID: deviceID, deadline: now + 30_000, createdAt: now,
                                      request: .init(method: "POST", path: "/v1/log", body: body))
                    let response = await fixtureDispatcher.dispatch(job)
                    guard response.status == 201 else { throw DeviceAPIError(500, "fixture_note_failed", "The test note was not admitted.") }
                    if CommandLine.arguments.contains("-fixtureRenameNotes") {
                        let rename = HostJob(id: UUID().uuidString, sequence: 3, agentID: agent.id, generation: agent.generation,
                                             deviceID: deviceID, deadline: now + 30_000, createdAt: now,
                                             request: .init(method: "PATCH", path: "/v1/agents/me", body: ["name": "Fixture Renamed"], idempotencyKey: "fixture-rename"))
                        let renamed = await fixtureDispatcher.dispatch(rename)
                        guard renamed.status == 200 else { throw DeviceAPIError(500, "fixture_rename_failed", "The test rename was not admitted.") }
                    }
                }
                if CommandLine.arguments.contains("-fixtureHostActivity") {
                    let invalid = HostJob(id: UUID().uuidString, sequence: 2, agentID: agent.id, generation: agent.generation,
                                          deviceID: deviceID, deadline: now + 30_000, createdAt: now,
                                          request: .init(method: "GET", path: "/v1/events", query: ["invalid": "fixture"]))
                    _ = await fixtureDispatcher.dispatch(invalid)
                }
            }
            agents = try await authority.list()
        } catch { problem = error.localizedDescription }
    }
    #endif

    func reconcileExternalCalls() async {
        if outboundAccess.isPaused {
            relayStartGeneration &+= 1
            pendingStart = false
            backgroundWakeTask?.cancel()
            if let stopOverride { await stopOverride() } else { await connection?.stop() }
            await updateAuthorityState()
        } else if startRequested && executionAllowed {
            await start()
        }
    }

    func start() async {
        guard executionAllowed else { return }
        if local?.fixtureMode == true && startOverride == nil { problem = "Fixture workspace stays offline."; return }
        startRequested = true; pendingStart = true; persistIntent()
        await authority?.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
        if outboundAccess.isPaused {
            pendingStart = false
            problem = "External calls are paused. Resume them in Agents."
            return
        }
        await drainStarts()
    }
    private func drainStarts() async {
        guard !busy, !drainingStarts else { return }
        drainingStarts = true; defer { drainingStarts = false }
        while pendingStart && startRequested && executionAllowed && !outboundAccess.isPaused {
            pendingStart = false
            let run = lifecycleGeneration
            let relayRun = relayStartGeneration
            if identity == nil && startOverride == nil { await prepare() }
            guard executionAllowed, startRequested, run == lifecycleGeneration,
                  relayRun == relayStartGeneration, !outboundAccess.isPaused else { continue }
            guard startOverride != nil || connection != nil else { return }
            busy = true; problem = nil
            if let startOverride { await startOverride() } else { await connection?.start() }
            busy = false
            guard executionAllowed, startRequested, run == lifecycleGeneration,
                  relayRun == relayStartGeneration, !outboundAccess.isPaused else { continue }
            await updateAuthorityState()
            if wakeEnabled, relayRun == relayStartGeneration, !outboundAccess.isPaused { await refreshWakeRegistration() }
        }
    }
    private func invalidatePendingSensorConsent() {
        cameraConsentGeneration = UUID()
        microphoneConsentGeneration = UUID()
    }
    func stop() async {
        let preserveDirectSensors = ownerDirectAddress != nil && executionAllowed
        if !preserveDirectSensors { invalidatePendingSensorConsent() }
        if !preserveDirectSensors { await local?.connections?.stopQueue() }
        startRequested = false; pendingStart = false; persistIntent()
        await authority?.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
        backgroundWakeTask?.cancel()
        if !preserveDirectSensors { local?.speech.suspend(); camera.stop() }
        local?.suspendModels()
        await authority?.setConnected(false)
        await connection?.cancelActive()
        await setWakeEnabled(false)
        await suspend(preserveDirectSensors: preserveDirectSensors)
    }
    private func suspend(preserveDirectSensors: Bool = false) async {
        if preserveDirectSensors { await updateConnectionQueue() }
        else { await local?.connections?.stopQueue() }
        if !preserveDirectSensors {
            local?.speech.suspend(); lifecycleGeneration &+= 1; camera.stop()
        }
        if let stopOverride { await stopOverride() } else { await connection?.stop() }; local?.suspendModels(); note("Host stopped.")
    }
    func lifecycle(active: Bool, backgrounded: Bool = false) async {
        if backgrounded { invalidatePendingSensorConsent() }
        foreground = active
        local?.inboundSharing?.setActive(awake && (active || Self.allowsBackgroundAwake))
        await updateAuthorityState()
        local?.speech.foreground = executionAllowed
        if backgrounded && !Self.allowsBackgroundAwake { await stopOwnerDirect(persist: false) }
        if !executionAllowed {
            await stopSatelliteListener(preserveIntent: true)
            await suspend()
        }
        else if !Self.allowsBackgroundAwake {
            await resumeOwnerDirect()
            backgroundWakeTask?.cancel()
            if startRequested { await start() }
        }
    }
    func sleep() async {
        invalidatePendingSensorConsent()
        local?.inboundSharing?.setActive(false)
        awake = false; local?.speech.foreground = false
        await updateAuthorityState()
        backgroundWakeTask?.cancel()
        await stopOwnerDirect(persist: false)
        await stopSatelliteListener(preserveIntent: true)
        await suspend()
    }
    func wake() async {
        local?.inboundSharing?.setActive(foreground || Self.allowsBackgroundAwake)
        awake = true; local?.speech.foreground = executionAllowed
        await updateAuthorityState()
        await resumeOwnerDirect()
        if startRequested && executionAllowed { await start() }
    }
    private func updateAuthorityState() async {
        await authority?.setState(enabled: state == .connected && !outboundAccess.isPaused, awake: awake, foreground: foreground)
        await updateConnectionQueue()
    }
    private func activeConnectionModes() async -> Set<String> {
        guard let authority else { return [] }
        var modes = await authority.connectionDeliveryModes()
        if !startRequested { modes.subtract(["connector", "sealed"]) }
        return modes
    }
    private func updateConnectionQueue() async {
        await authority?.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
        guard let authority, !(await activeConnectionModes()).isEmpty else {
            await local?.connections?.stopQueue()
            return
        }
        await local?.connections?.startQueue(authorize: { id, generation, mode, sourcePeer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer)
        }, authorizePhoto: { id, generation, mode, sourcePeer, applet in
            try await authority.authorizeConnectionPhoto(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer, applet: applet)
        }, eligibleModes: { [weak self] in await self?.activeConnectionModes() ?? [] })
    }
    /// Reading saved agents neither creates a host identity nor starts the relay.
    func loadSavedAgents() async {
        do {
            if let authority { agents = try await authority.list() }
            else if let store = local?.store {
                let saved = try await store.transferAgents()
                if authority == nil && !creatingAgent { agents = saved }
            }
        } catch { problem = error.localizedDescription }
    }
    func observeSavedAgents() async {
        guard let store = local?.store else { return }
        let changes = await store.changes(to: .hostAgents)
        await loadSavedAgents()
        for await _ in changes {
            if Task.isCancelled { return }
            await loadSavedAgents()
        }
    }

    private func ownerDirectUnavailable() async {
        ownerDirectAddress = nil
        ownerDirectPort = nil
        ownerDirectProblem = "The direct listener stopped. Check Tailscale and start it again."
        await updateConnectionQueue()
    }

    private func resumeOwnerDirect() async {
        guard ownerDirectIntent, executionAllowed, ownerDirectAddress == nil, !ownerDirectStarting else { return }
        do { try await enableConfiguredOwnerDirect() }
        catch is CancellationError {}
        catch { ownerDirectProblem = error.localizedDescription }
    }

    func startOwnerDirect(_ address: TailnetAddress, requester: HostAgent? = nil) async throws {
        guard local?.fixtureMode != true, executionAllowed, !ownerDirectStarting else {
            throw DeviceAPIError(409, "foreground_required", "Open Bridgit and connect Tailscale before starting direct hosting.")
        }
        guard availableTailnetAddresses.contains(address) else {
            throw DeviceAPIError(409, "tailnet_required", "Choose an address assigned by Tailscale on this device.")
        }
        if ownerDirectAddress == address, ownerDirectPort != nil { return }
        ownerDirectStarting = true
        ownerDirectProblem = nil
        let generation = UUID()
        ownerDirectGeneration = generation
        defer { if ownerDirectGeneration == generation { ownerDirectStarting = false } }
        _ = try await preparedAuthority()
        guard ownerDirectGeneration == generation, let ownerDirectHosting else { throw CancellationError() }
        let port = try await ownerDirectHosting.start(address: address, requester: requester)
        guard ownerDirectGeneration == generation else { throw CancellationError() }
        guard awake, !Task.isCancelled else {
            await stopOwnerDirect(persist: false)
            throw CancellationError()
        }
        ownerDirectAddress = address
        ownerDirectPort = port
        ownerDirectIntent = true
        setOwnerDirectPreferredAddress(address)
        local?.settings?.set(true, forKey: "host.ownerDirect.enabled")
        await updateConnectionQueue()
    }

    func stopOwnerDirect(persist: Bool = true) async {
        ownerDirectGeneration = UUID()
        ownerDirectAddress = nil
        ownerDirectPort = nil
        ownerDirectStarting = false
        if persist {
            ownerDirectIntent = false
            local?.settings?.set(false, forKey: "host.ownerDirect.enabled")
        }
        await ownerDirectHosting?.stop()
        await updateConnectionQueue()
    }

    func updateOwnerDirectPreferredAddress(_ address: TailnetAddress?) throws {
        guard address.map(availableTailnetAddresses.contains) ?? true else {
            throw DeviceAPIError(409, "tailnet_required", "Choose an address assigned by Tailscale on this device.")
        }
        setOwnerDirectPreferredAddress(address)
    }

    private func setOwnerDirectPreferredAddress(_ address: TailnetAddress?) {
        ownerDirectPreferredAddress = address
        local?.settings?.set(address?.value, forKey: "host.ownerDirect.address")
    }

    func enableConfiguredOwnerDirect(requester: HostAgent? = nil) async throws {
        let candidates = availableTailnetAddresses
        guard let address = ownerDirectPreferredAddress.flatMap({ candidates.contains($0) ? $0 : nil })
                ?? (candidates.count == 1 ? candidates.first : nil) else {
            throw DeviceAPIError(409, "tailnet_required", "Choose a Tailscale address in Control agents first.")
        }
        try await startOwnerDirect(address, requester: requester)
    }

    func observeOwnerDirectFirstCall(agentID: String, generation: String, receive: () -> Void) async {
        guard let store = local?.store else { return }
        let changes = await store.changes(to: .activity)
        var iterator = changes.makeAsyncIterator()
        while !Task.isCancelled {
            if (try? await store.hostActivity(agentFilter: .identified(agentID), kinds: [.request]))?.contains(where: {
                (200...299).contains($0.status) && $0.requestFacts?.agentGeneration == generation
            }) == true {
                receive()
                return
            }
            guard await iterator.next() != nil else { return }
        }
    }

    func changeOwnerDirectPeers(_ agent: HostAgent, allow: TailnetAddress? = nil, unlock: Bool = false, dismiss: TailnetAddress? = nil) async {
        do {
            let authority = try await preparedAuthority()
            try await authority.setOwnerDirectPeers(agent.id, allow: allow, unlock: unlock, dismiss: dismiss)
            await loadSavedAgents()
        } catch { problem = error.localizedDescription }
    }

    private func preparedAuthority() async throws -> HostAuthority {
        if authority == nil { await prepare() }
        guard let authority else { throw DeviceAPIError(503, "host_preparation_failed", problem ?? "Could not prepare this device locally. Try again.") }
        return authority
    }
    func create(name: String, operations: Set<HostOperation>, applets: Set<String>, peer: String?, ownerDirect: Bool = false) async {
        guard !creatingAgent else { return }
        guard oneTimeSetup == nil else { problem = "Finish the current agent setup before creating another agent."; return }
        creatingAgent = true
        defer { creatingAgent = false }
        problem = nil
        do {
            let authority = try await preparedAuthority()
            try Task.checkCancellation()
            guard let deviceID, let identity else { throw DeviceAPIError(503, "host_preparation_failed", "Could not prepare this device locally. Try again.") }
            let existing = try await authority.list()
            guard !existing.contains(where: { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(name.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame }) else { throw DeviceAPIError(409, "agent_name_in_use", "A control agent already uses that name. Choose another name.") }
            let agent: HostAgent, token: String
            if ownerDirect {
                guard let address = ownerDirectAddress, let port = ownerDirectPort,
                      availableTailnetAddresses.contains(address), OwnerDirectHostService.baseURL(address: address, port: port) != nil else {
                    throw DeviceAPIError(409, "listener_required", "Start the direct Tailscale listener before creating an agent.")
                }
                (agent, token) = try await authority.createOwnerDirect(name: name, operations: operations, applets: applets)
            } else {
                (agent, token) = try await authority.create(name: name, operations: operations, applets: applets, peer: peer)
            }
            oneTimeSetup = try controlSetup(agent, token: token, deviceID: deviceID, identity: identity)
            agents = try await authority.list()
            if !ownerDirect { try await connection?.permissionsChanged() }
        } catch { problem = error.localizedDescription }
    }
    func editControlAgent(_ original: HostAgent, name: String, notes: String,
                          operations: Set<HostOperation>, applets: Set<String>) async throws {
        let authority = try await preparedAuthority()
        let updated = try await authority.editControlAgent(original, name: name, notes: notes,
                                                           operations: operations, applets: applets)
        let grantsChanged = original.operations != updated.operations || original.applets != updated.applets
        do { try await refreshAgentAccess(target: grantsChanged ? updated.id : nil) }
        catch { problem = "Agent details were saved locally. Restart the relay connection if access has not updated." }
    }

    func rotate(_ agent: HostAgent) async -> Bool {
        guard !creatingAgent, oneTimeSetup == nil else {
            problem = "Finish the current agent setup before rotating another key."
            return false
        }
        creatingAgent = true
        defer { creatingAgent = false }
        problem = nil
        do {
            let authority = try await preparedAuthority()
            guard let deviceID, let identity else { throw DeviceAPIError(503, "host_preparation_failed", "Could not prepare this device locally. Try again.") }
            let (rotated, token) = try await authority.rotateControlKey(agent.id)
            // The control key must remain available even if callback Keychain access fails.
            var setup = try controlSetup(rotated, token: token, deviceID: deviceID, identity: identity)
            var callbackProblem: String?
            do {
                let service = try await preparedCallbacks()
                if let secret = try await service.ownerSetupSecret(agentID: rotated.id) {
                    var values = try JSONSerialization.jsonObject(with: Data(setup.utf8)) as? [String: Any] ?? [:]
                    values["callback_secret"] = secret
                    setup = try Self.encodeControlSetup(values)
                }
            } catch {
                callbackProblem = "The callback signing key could not be included. Set a new callback key if the agent needs it."
            }
            oneTimeSetup = setup
            do { try await refreshAgentAccess(target: rotated.id) }
            catch { problem = "The key was rotated locally. Restart the relay connection if the new key is unavailable." }
            if let callbackProblem { problem = [problem, callbackProblem].compactMap { $0 }.joined(separator: " ") }
            return true
        } catch {
            problem = error.localizedDescription
            return false
        }
    }

    func presentedControlSetup(direct: Bool) -> String? {
        guard direct, let oneTimeSetup else { return oneTimeSetup }
        return try? Self.presentedDirectControlSetup(oneTimeSetup, address: ownerDirectAddress, port: ownerDirectPort)
    }

    static func presentedDirectControlSetup(_ setupText: String, address: TailnetAddress?, port: UInt16?) throws -> String {
        guard var setup = try JSONSerialization.jsonObject(with: Data(setupText.utf8)) as? [String: Any] else {
            return setupText
        }
        updateDirectSetupAddress(&setup, address: address, port: port)
        return try encodeControlSetup(setup)
    }

    private static func updateDirectSetupAddress(_ setup: inout [String: Any], address: TailnetAddress?, port: UInt16?) {
        let base: String?
        if let address, let port {
            base = OwnerDirectHostService.baseURL(address: address, port: port)?.absoluteString
        } else { base = nil }
        setup["base"] = base
        setup["mcp"] = base.map { $0 + "/mcp" }
    }

    private func controlSetup(_ agent: HostAgent, token: String, deviceID: String, identity: RelayHostIdentity) throws -> String {
        var setup: [String: Any] = [
            "agent_id": agent.id, "generation": agent.generation, "mode": agent.mode, "token": token,
            "operations": agent.operations.map(\.rawValue).sorted(), "applets": agent.applets.sorted(), "phone_name": Self.deviceName
        ]
        if agent.ownerDirectPeers != nil {
            setup["authorization"] = "Bearer"
            Self.updateDirectSetupAddress(&setup, address: ownerDirectAddress, port: ownerDirectPort)
        } else {
            setup["base"] = "https://bridgit-relay.bridgit-app.workers.dev/d/" + deviceID
            setup["device_id"] = deviceID
            setup["device_hpke_public"] = identity.hpkePublic.base64EncodedString()
        }
        return try Self.encodeControlSetup(setup)
    }

    private static func encodeControlSetup(_ setup: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: setup, options: [.prettyPrinted, .sortedKeys]), as: UTF8.self)
    }

    var availableTailnetAddresses: [TailnetAddress] { local?.fixtureMode == true ? [] : TailnetAddress.localCandidates() }
    private func satelliteListenerUnavailable() {
        satelliteAddress = nil; satellitePort = nil
        satelliteProblem = "The Tailscale listener stopped. Check Tailscale and start it again."
    }
    func startSatelliteListener(_ address: TailnetAddress, port: UInt16 = 18471) async throws {
        guard (local?.fixtureMode != true || satelliteStartOverride != nil), executionAllowed else { throw DeviceAPIError(409, "foreground_required", "The Tailscale listener needs an active, non-fixture device.") }
        satelliteProblem = nil
        let run = UUID(); satelliteGeneration = run
        satelliteIntent = true
        _ = try await preparedAuthority()
        guard satelliteGeneration == run, satelliteIntent, executionAllowed else { throw CancellationError() }
        await authority?.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
        if let satelliteStartOverride {
            let bound = try await satelliteStartOverride(address, port)
            guard satelliteGeneration == run, satelliteIntent else {
                if !satelliteIntent { await satelliteStopOverride?() }
                throw CancellationError()
            }
            guard executionAllowed, !Task.isCancelled else { await satelliteStopOverride?(); throw CancellationError() }
            satelliteAddress = address; satellitePort = bound; return
        }
        guard let satelliteHosting else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite hosting is unavailable.") }
        if let bound = await satelliteHosting.boundEndpoint {
            guard satelliteGeneration == run, satelliteIntent else { throw CancellationError() }
            guard bound.0 == address, bound.1 == port else { throw DeviceAPIError(409, "listener_address_changed", "Stop the current listener before changing its address or port.") }
            satelliteAddress = bound.0; satellitePort = bound.1; return
        }
        let bound = try await satelliteHosting.start(address: address, port: port)
        guard satelliteGeneration == run, satelliteIntent else {
            if !satelliteIntent { await satelliteHosting.stop() }
            throw CancellationError()
        }
        guard executionAllowed, !Task.isCancelled else { await satelliteHosting.stop(); throw CancellationError() }
        satelliteAddress = address; satellitePort = bound
    }
    func stopSatelliteListener(preserveIntent: Bool = false) async {
        satelliteGeneration = UUID()
        if !preserveIntent { satelliteIntent = false }
        satelliteAddress = nil; satellitePort = nil
        await authority?.setCallbackExecutionEnabled(startRequested || ownerDirectIntent || satelliteIntent)
        if let satelliteStopOverride { await satelliteStopOverride() } else { await satelliteHosting?.stop() }
    }
    func makeSatelliteCode(name: String, operations: Set<HostOperation>) async throws -> SatellitePairingLink {
        guard let satelliteHosting else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite hosting is unavailable.") }
        return try await satelliteHosting.makeLink(name: name, operations: operations)
    }
    func cancelSatelliteCode(_ link: SatellitePairingLink) async throws { try await authority?.cancelSatellitePairing(claim: link.claim) }
    func loadSatelliteLinks() async throws {
        let authority = try await preparedAuthority()
        guard let satelliteHub, let satelliteHosting else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite storage is unavailable.") }
        let endpoint = await satelliteHosting.boundEndpoint
        satelliteAddress = endpoint?.0; satellitePort = endpoint?.1
        linkedSatellites = try await satelliteHub.list()
        var links: [NativeHubLink] = []
        for agent in try await authority.list() where agent.satelliteRole == "hub" {
            links.append(NativeHubLink(agent: agent, info: try await satelliteHosting.hubInfo(agent.id)))
        }
        hubLinks = links
    }
    func addSatellite(_ link: SatellitePairingLink, agents: Set<String>, allowAgentPause: Bool) async throws -> LinkedSatellite {
        guard local?.fixtureMode != true, executionAllowed, let satelliteHub else { throw DeviceAPIError(409, "satellite_unavailable", "Keep this host open with Tailscale on to add a satellite.") }
        let record = try await satelliteHub.add(link, allowedAgents: agents, allowAgentPause: allowAgentPause)
        try await loadSatelliteLinks(); await loadSavedAgents(); return record
    }
    func refreshSatellite(_ id: String) async throws { _ = try await satelliteHub?.refresh(id); try await loadSatelliteLinks() }
    func setSatelliteAgents(_ id: String, agents: Set<String>) async throws { try await satelliteHub?.setAllowedAgents(id, agents: agents); try await loadSatelliteLinks() }
    func pauseSatellite(_ id: String, paused: Bool) async throws { try await satelliteHub?.setPaused(id, paused: paused); try await loadSatelliteLinks() }
    func removeSatellite(_ id: String) async throws -> Bool {
        guard let satelliteHub else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite storage is unavailable.") }
        let remote = try await satelliteHub.remove(id); try await loadSatelliteLinks(); await loadSavedAgents(); return remote
    }
    func readLinkedHub(_ id: String) async throws -> SatelliteHubHome {
        guard let satelliteHosting else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite storage is unavailable.") }
        return try await satelliteHosting.readHubHome(id)
    }
    func removeHubLink(_ id: String) async throws {
        guard let satelliteHosting else { throw DeviceAPIError(503, "satellite_unavailable", "Satellite hosting is unavailable.") }
        try await satelliteHosting.removeHub(id); try await loadSatelliteLinks(); await loadSavedAgents()
    }

    /// Explicit owner action. A fixed viewer role never inherits control-agent grants.
    func issueViewerLink(format: HostViewerPairing.WireFormat = .current) async throws -> (agent: HostAgent, link: URL) {
        guard !creatingAgent, state == .connected, let connection else {
            throw DeviceAPIError(409, "host_not_connected", "Start this device's relay host before creating a viewer code.")
        }
        creatingAgent = true; defer { creatingAgent = false }
        let authority = try await preparedAuthority()
        guard let deviceID else { throw DeviceAPIError(503, "host_preparation_failed", "Prepare this host's identity first.") }
        let existing = try await authority.list().map(\.name)
        var number = 1
        while existing.contains(number == 1 ? "Bridgit viewer" : "Bridgit viewer \(number)") { number += 1 }
        let name = number == 1 ? "Bridgit viewer" : "Bridgit viewer \(number)"
        try Task.checkCancellation()
        let (agent, token) = try await authority.createViewer(name: name)
        do {
            try Task.checkCancellation()
            let base = URL(string: "https://bridgit-relay.bridgit-app.workers.dev/d/" + deviceID)!
            let link = try HostViewerPairing.link(base: base, device: deviceID, token: token, name: Self.deviceName, format: format)
            try await connection.permissionsChanged()
            try Task.checkCancellation()
            agents = try await authority.list()
            return (agent, link)
        } catch {
            try? await authority.remove(agent.id)
            try? await connection.permissionsChanged()
            agents = (try? await authority.list()) ?? agents
            throw error
        }
    }

    func setAdministrator(_ agent: HostAgent, enabled: Bool) async {
        do {
            let authority = try await preparedAuthority()
            try await authority.setAdministrator(agent.id, enabled: enabled)
            try await refreshAgentAccess(target: agent.id)
        } catch { problem = error.localizedDescription }
    }
    func setFeedbackFixer(_ agent: HostAgent, enabled: Bool) async {
        do {
            let authority = try await preparedAuthority()
            try await authority.setFeedbackFixer(agent.id, enabled: enabled)
            try await refreshAgentAccess(target: agent.id)
        } catch { problem = error.localizedDescription }
    }

    func refreshAgentAccess(target: String?) async throws {
        agents = try await authority?.list() ?? []
        FeedbackReports.shared.reconcileTeamKeys(agents)
        try await connection?.agentAccessChanged(target: target)
    }
    func pause(_ agent: HostAgent) async {
        local?.speech.stop()
        await connection?.cancelActive()
        do {
            let authority = try await preparedAuthority()
            try await authority.pause(agent.id, paused: !agent.paused)
            agents = try await authority.list()
            FeedbackReports.shared.reconcileTeamKeys(agents)
            try await connection?.permissionsChanged()
        }
        catch { problem = error.localizedDescription }
    }
    func remove(_ agent: HostAgent) async {
        local?.speech.stop()
        await connection?.cancelActive()
        do {
            let authority = try await preparedAuthority()
            try await authority.remove(agent.id)
            agents = try await authority.list()
            FeedbackReports.shared.reconcileTeamKeys(agents)
            try await connection?.permissionsChanged()
        }
        catch { problem = error.localizedDescription }
    }
    private func preparedCallbacks() async throws -> CallbackHostService {
        if callbacks == nil { await prepare() }
        for _ in 0..<100 where callbacks == nil && busy {
            try Task.checkCancellation()
            try await Task.sleep(for: .milliseconds(50))
        }
        guard let callbacks else { throw DeviceAPIError(503, "callbacks_unavailable", "Callbacks are unavailable.") }
        return callbacks
    }
    func callbackOwnerStatus(_ id: String) async throws -> [String: Any] {
        return try await preparedCallbacks().ownerStatus(agentID: id)
    }
    func callbackOwnerURL(_ id: String) async throws -> String? {
        return try await preparedCallbacks().ownerURL(agentID: id)
    }
    func validateCallbackURL(_ url: String) async throws {
        try await preparedCallbacks().validateOwnerURL(url)
    }
    func setOwnerCallback(_ id: String, url: String) async throws -> String {
        return try await preparedCallbacks().ownerSet(agentID: id, url: url)
    }
    func removeOwnerCallback(_ id: String) async throws {
        try await preparedCallbacks().ownerRemove(agentID: id)
    }
    #if DEBUG
    func stopCallbacksForTesting() async { await callbacks?.stop(); callbacks = nil }
    #endif
    func setCallbackInternetAllowed(_ enabled: Bool, requestedBy agent: HostAgent? = nil) async throws {
        guard let store = local?.store else { throw DeviceAPIError(503, "storage_unavailable", "Applet storage is unavailable.") }
        try await store.setCallbackInternetAllowed(enabled, requestedBy: agent)
        await refreshCallbackInternetPolicy()
    }

    func refreshCallbackInternetPolicy() async {
        if let current = try? await local?.store?.callbackInternetAllowed() { callbackInternetAllowed = current }
    }
    func setCameraEnabled(_ enabled: Bool, authorize: (() async throws -> Void)? = nil, commit: (() async throws -> Void)? = nil) async {
        cameraConsentGeneration = UUID()
        let consent = cameraConsentGeneration
        if !enabled { disableCamera(); await connection?.cancelActive(); return }
        guard executionAllowed else { return }
        let allowed = await cameraPermission()
        // A native permission alert briefly makes the app inactive. The pending
        // preference survives that alert, while sensor execution stays suspended.
        guard awake, consent == cameraConsentGeneration, !Task.isCancelled else { return }
        do { try await authorize?() } catch { problem = error.localizedDescription; return }
        guard awake, consent == cameraConsentGeneration, !Task.isCancelled else { return }
        if allowed { do { try await commit?() } catch { problem = error.localizedDescription; return } }
        guard awake, consent == cameraConsentGeneration, !Task.isCancelled else { return }
        cameraEnabled = allowed
        local?.settings?.set(allowed, forKey: "host.camera")
        if !allowed { problem = "Camera access is off. Allow Bridgit in system privacy settings to use this host's camera." }
    }
    func setMicrophoneEnabled(_ enabled: Bool, authorize: (() async throws -> Void)? = nil, commit: (() async throws -> Void)? = nil) async throws {
        microphoneConsentGeneration = UUID()
        let consent = microphoneConsentGeneration
        if !enabled {
            disableMicrophone()
            await connection?.cancelActive()
            return
        }
        guard executionAllowed else { throw CancellationError() }
        let allowed = await microphonePermission()
        // The system prompt makes the iOS scene temporarily inactive. Completing
        // owner consent may update the preference during that transition, while
        // recording itself still requires the active execution context.
        try Task.checkCancellation()
        guard awake, consent == microphoneConsentGeneration else { throw CancellationError() }
        try await authorize?()
        try Task.checkCancellation()
        guard awake, consent == microphoneConsentGeneration else { throw CancellationError() }
        guard allowed else { throw DeviceAPIError(403, "os_permission_required", "Allow microphone access in system privacy settings to enable host recording.") }
        try await commit?()
        try Task.checkCancellation()
        guard awake, consent == microphoneConsentGeneration else { throw CancellationError() }
        microphoneEnabled = true
        local?.settings?.set(true, forKey: "host.microphone")
    }
    func disableHostSensors() {
        cameraConsentGeneration = UUID()
        microphoneConsentGeneration = UUID()
        disableCamera()
        disableMicrophone()
    }

    private func disableCamera() {
        cameraEnabled = false
        local?.settings?.set(false, forKey: "host.camera")
        camera.stop()
    }

    private func disableMicrophone() {
        microphoneEnabled = false
        local?.settings?.set(false, forKey: "host.microphone")
        local?.speech.stopMicrophoneClip()
    }

    func requestMicrophoneTranscription() async {
        guard microphoneEnabled, executionAllowed else { return }
        let status = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        microphoneTranscriptionAllowed = status == .authorized
        if !microphoneTranscriptionAllowed { problem = "Allow speech recognition in system privacy settings to transcribe clips locally." }
    }
    private func prepareWakeRegistration() async throws {
        if let wakePreparationOverride { try await wakePreparationOverride(); return }
        #if os(iOS)
        guard local?.fixtureMode != true, let connection, state == .connected, await connection.pushProviders.contains("apns") else { throw DeviceAPIError(409, "push_not_supported", "This relay is not configured for Apple wake-ups.") }
        guard await connection.pushTopic == Bundle.main.bundleIdentifier else { throw DeviceAPIError(409, "push_topic_mismatch", "This relay is configured for a different app build.") }
        guard ["development", "production"].contains(Bundle.main.object(forInfoDictionaryKey: "BridgitAPNsEnvironment") as? String ?? "") else { throw DeviceAPIError(409, "push_signing_required", "Apple wake-ups require a correctly signed device build.") }
        #else
        throw DeviceAPIError(422, "unsupported_setting", "Apple wake-ups are available on iPhone and iPad.")
        #endif
    }
    func setWakeEnabled(_ enabled: Bool, authorize: (() async throws -> Void)? = nil, commit: (() async throws -> Void)? = nil) async {
        wakeRevision &+= 1
        let revision = wakeRevision
        if !enabled {
            wakeEnabled = false; wakeStatus = "off"; apnsToken = nil; persistIntent()
            backgroundWakeTask?.cancel()
            if state == .connected { try? await connection?.publishPush(nil, revision: revision) }
            return
        }
        do {
            try await prepareWakeRegistration()
            guard revision == wakeRevision else { throw CancellationError() }
            try await authorize?()
            guard revision == wakeRevision else { throw CancellationError() }
            try await commit?()
            guard revision == wakeRevision else { throw CancellationError() }
            wakeEnabled = true; wakeStatus = "awaiting_os_registration"; persistIntent()
            registerForWake()
        } catch { if revision == wakeRevision { problem = error.localizedDescription; wakeStatus = "unavailable" } }
    }
    private func registerForWake() {
        if let wakeRegistrationOverride { wakeRegistrationOverride() }
        else {
            #if os(iOS)
            UIApplication.shared.registerForRemoteNotifications()
            #endif
        }
    }
    private func refreshWakeRegistration() async {
        guard !outboundAccess.isPaused else { return }
        let revision = wakeRevision
        do {
            try await prepareWakeRegistration()
            guard wakeEnabled, revision == wakeRevision else { return }
            if apnsToken == nil { registerForWake() }
            else { await publishWakeToken() }
        } catch { if wakeEnabled, revision == wakeRevision { wakeStatus = "unavailable" } }
    }
    func receivedAPNsToken(_ token: Data) async {
        guard wakeEnabled else { return }
        wakeRevision &+= 1; apnsToken = token; await publishWakeToken()
    }
    func failedAPNsRegistration(_ error: Error) {
        guard wakeEnabled else { return }; wakeStatus = "registration_failed"; problem = error.localizedDescription
    }
    private func publishWakeToken() async {
        guard wakeEnabled, let token = apnsToken, let connection, state == .connected else { return }
        let revision = wakeRevision
        do {
            guard await connection.pushProviders.contains("apns"), await connection.pushTopic == Bundle.main.bundleIdentifier else { throw DeviceAPIError(409, "push_not_supported", "This relay has no matching Apple push configuration.") }
            let declared = Bundle.main.object(forInfoDictionaryKey: "BridgitAPNsEnvironment") as? String
            guard ["development", "production"].contains(declared ?? "") else { throw DeviceAPIError(409, "push_signing_required", "This build has no Apple push environment.") }
            let registration = try RelayPushRegistration(apns: token, environment: declared == "development" ? "sandbox" : "production")
            guard wakeEnabled, revision == wakeRevision else { throw CancellationError() }
            try await connection.publishPush(registration, revision: revision)
            if wakeEnabled, revision == wakeRevision { wakeStatus = "submitted" }
        } catch { if wakeEnabled, revision == wakeRevision { wakeStatus = "submission_failed"; problem = error.localizedDescription } }
    }
    /// APNs is a content-free hint. A short lease admits only already-granted reads;
    /// no camera, speech, applet write, configuration change or paid flow can run.
    func backgroundWake() async -> Bool {
        #if os(iOS)
        guard !outboundAccess.isPaused, UIApplication.shared.applicationState != .active, wakeEnabled, startRequested, awake, !busy, local?.fixtureMode != true else { return false }
        foreground = false
        if authority == nil { await prepare() }
        guard wakeEnabled, startRequested, !busy, let authority, let connection else { return false }
        await updateAuthorityState()
        busy = true; backgroundReading = true
        defer {
            busy = false; backgroundReading = false; backgroundReadDeadline = nil; backgroundWakeTask = nil
            if pendingStart { Task { await self.drainStarts() } }
        }
        let deadline = Int64(Date().timeIntervalSince1970 * 1000) + 20_000
        do { try await authority.setBackgroundReadLease(until: deadline) } catch { return false }
        backgroundReadDeadline = Date(timeIntervalSince1970: Double(deadline) / 1000)
        let before = await connection.completedJobs
        let work = Task {
            await connection.start()
            if self.wakeEnabled { await self.refreshWakeRegistration() }
            let remaining = deadline - Int64(Date().timeIntervalSince1970 * 1000)
            if remaining > 0 { try? await Task.sleep(nanoseconds: UInt64(remaining) * 1_000_000) }
        }
        backgroundWakeTask = work
        let timer = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); if !Task.isCancelled { work.cancel() } }
        await withTaskCancellationHandler(operation: { await work.value }, onCancel: { work.cancel() })
        timer.cancel()
        try? await authority.setBackgroundReadLease(until: nil)
        let served = await connection.completedJobs > before
        if !foreground || !startRequested { await connection.stop() }
        else { pendingStart = true }
        return served
        #else
        return false
        #endif
    }

    private func sensor(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        let ownerDirect = agent.ownerDirectPeers != nil
        let transportReady = ownerDirect ? ownerDirectAddress != nil : agent.mode == "direct" ? satelliteAddress != nil : state == .connected
        guard transportReady, executionAllowed || (!ownerDirect && agent.mode != "direct" && backgroundReading && request.method == "GET") else { throw DeviceAPIError(409, "foreground_required", "Keep this host open and awake for this action.") }
        if request.route?.0 == .microphoneRecord {
            guard microphoneEnabled, agent.allows(.microphoneRecord), let local, let authority else {
                throw DeviceAPIError(403, "capability_disabled", "The owner has not enabled microphone recording on this host.")
            }
            let options = try MicrophoneClipRequest(request)
            let consent = microphoneConsentGeneration
            let generation = lifecycleGeneration
            try await authority.authorizeOngoing(agent, operation: .microphoneRecord)
            let notice = try await captureNotices.begin(agent.name + " is recording audio")
            defer { captureNotices.end(notice) }
            guard executionAllowed, microphoneEnabled, microphoneConsentGeneration == consent,
                  lifecycleGeneration == generation else { throw CancellationError() }
            let clip = try await local.speech.recordMicrophoneClip(options) {
                guard self.executionAllowed, self.microphoneEnabled, self.microphoneConsentGeneration == consent,
                      self.lifecycleGeneration == generation, AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
                    throw DeviceAPIError(403, "permission_denied", "Microphone access is unavailable in this state.")
                }
                try await self.captureNotices.requireAvailable()
                try await authority.authorizeOngoing(agent, operation: .microphoneRecord)
            }
            var reply = try HostResponse.json(clip)
            reply.headers["Cache-Control"] = "no-store"
            return reply
        }
        if request.route?.0 == .appletsMediaWrite {
            let options = try PhotoImportOptions(body: request.body, query: request.query)
            guard let applet = request.route?.1, agent.allows(.appletsMediaWrite), agent.allowsApplet(applet, operation: .appletsMediaWrite), let local else { throw DeviceAPIError(403, "agent_not_allowed", "This caller cannot import photos for this applet.") }
            return try .json(await local.importPhoto(applet: applet, data: options.data, action: ["table": options.table, "photo": options.column, "row": options.row], requestedBy: agent), status: 201)
        }
        if request.method == "GET", let applet = request.route?.1 {
            guard agent.allows(.appletsRead), agent.allowsApplet(applet, operation: .appletsRead), let local,
                  let format = request.query["format"] as? String, format == "csv",
                  let table = request.query["table"] as? String, table.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil else { throw DeviceAPIError(422, "invalid_export", "Choose a declared table and csv format.") }
            let data = try await local.exportData(applet: applet, table: table)
            guard data.count <= 5 * 1024 * 1024 else { throw DeviceAPIError(413, "export_too_large", "This relay report is too large. Read the JSON data and paginated rows instead.") }
            return .init(contentType: "text/csv; charset=utf-8", data: data, headers: ["Content-Disposition": "attachment; filename=\"" + applet + "." + format + "\"", "Cache-Control": "no-store"])
        }
        guard cameraEnabled, agent.allows(.cameraSnapshot) else { throw DeviceAPIError(403, "capability_disabled", "The owner has not enabled the host camera.") }
        guard request.path == "/v1/camera/snapshot" else { throw DeviceAPIError(501, "not_implemented", "This sensor operation is unavailable.") }
        let options = try CameraRequestOptions(body: request.body, query: request.query)
        let lens = options.lens ?? AppleCamera.defaultLens
        let consent = cameraConsentGeneration
        let generation = lifecycleGeneration
        let directAuthority: HostAuthority?
        if ownerDirect || agent.mode != "direct" {
            guard let authority else { throw DeviceAPIError(409, "host_unavailable", "The host is unavailable.") }
            directAuthority = authority
        } else { directAuthority = nil }
        try await directAuthority?.authorizeOngoing(agent, operation: .cameraSnapshot)
        guard executionAllowed, cameraEnabled, consent == cameraConsentGeneration, generation == lifecycleGeneration else { throw CancellationError() }
        let notice = try await captureNotices.begin(agent.name + " is taking a photo")
        defer { captureNotices.end(notice) }
        try await captureNotices.requireAvailable()
        try await directAuthority?.authorizeOngoing(agent, operation: .cameraSnapshot)
        guard executionAllowed, cameraEnabled, consent == cameraConsentGeneration, generation == lifecycleGeneration else { throw CancellationError() }
        let noticeMonitor = captureNotices.monitor { [weak self] in self?.camera.stop() }
        defer { noticeMonitor.cancel() }
        let jpeg = try await camera.capture(lens: lens, maxEdge: options.maxEdge, quality: options.quality)
        try await captureNotices.requireAvailable()
        try await directAuthority?.authorizeOngoing(agent, operation: .cameraSnapshot)
        guard executionAllowed, cameraEnabled, consent == cameraConsentGeneration, generation == lifecycleGeneration else { throw CancellationError() }
        let at = ISO8601DateFormatter().string(from: Date())
        let headers = ["X-Bridgit-Ran-On": "local", "X-Bridgit-Captured-At": at, "X-Bridgit-Camera": lens]
        if options.raw { return .init(contentType: "image/jpeg", data: jpeg, headers: headers) }
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil), let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else { throw DeviceAPIError(500, "capture_failed", "The captured image metadata could not be read.") }
        return try .json(["captured_at": at, "width_px": properties[kCGImagePropertyPixelWidth] ?? 0, "height_px": properties[kCGImagePropertyPixelHeight] ?? 0,
            "format": "jpeg", "size_bytes": jpeg.count, "data_base64": jpeg.base64EncodedString()])
    }

    private func changed(_ state: RelayHostConnection.State, message: String?) async {
        if outboundAccess.isPaused, state == .connecting || state == .connected { return }
        let changed = self.state != state
        self.state = state; if let message { problem = message }
        if changed, let store = local?.store {
            do { try await store.recordRelayState(state) }
            catch { problem = "Could not save host activity: " + error.localizedDescription }
        }
        await updateAuthorityState()
    }
    private func note(_ message: String) { activity.insert(Date().formatted(date: .omitted, time: .standard) + " · " + message, at: 0); activity = Array(activity.prefix(100)) }
    static var deviceName: String { effectiveDeviceName(override: DeviceNamePreference.shared.read()) }

    static func effectiveDeviceName(override: String) -> String {
        #if os(macOS)
        HostDeviceName.effective(override: override, systemName: Host.current().localizedName ?? "", fallback: "This Mac")
        #else
        HostDeviceName.effective(override: override, systemName: UIDevice.current.name,
                                 fallback: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
        #endif
    }
    static func microphoneCapability(recordingAllowed: Bool, hearingAllowed: Bool, recordingEnabled: Bool,
                                     hearingEnabled: Bool, microphonePresent: Bool, microphoneAllowed: Bool,
                                     offlineHearing: Bool, hostReady: Bool, contextAllowed: Bool,
                                     notificationsAllowed: Bool = true) -> ProfileCapability {
        let canRecord = recordingAllowed && recordingEnabled && microphonePresent && microphoneAllowed
        let canHear = hearingAllowed && hearingEnabled && microphonePresent && offlineHearing
        let allowed = recordingAllowed || hearingAllowed
        let enabled = recordingAllowed && recordingEnabled || hearingAllowed && hearingEnabled
        let blocked: String?
        if !allowed { blocked = "agent_not_allowed" }
        else if !contextAllowed || !hostReady { blocked = "foreground_required" }
        else if !recordingAllowed { blocked = "operation_unavailable" }
        else if !recordingEnabled { blocked = "capability_disabled" }
        else if !notificationsAllowed { blocked = "notifications_off" }
        else if !canRecord { blocked = "hardware_or_permission_unavailable" }
        else { blocked = nil }
        return .init(runtimeID: "mic", implemented: true, supported: microphonePresent, enabled: enabled,
            ready: hostReady && contextAllowed && notificationsAllowed && (canRecord || canHear), agentAllowed: allowed, callable: blocked == nil,
            blockedReason: blocked, constraints: .init(execution: allowsBackgroundAwake ? ["foreground", "background_awake"] : ["foreground"],
                parameters: ["format": ["m4a"], "store": ["none"], "applet_hearing": [canHear && notificationsAllowed ? "ready" : "unavailable"],
                             "on_device_transcription": [canRecord && offlineHearing && notificationsAllowed ? "available_for_default_language" : "unavailable_for_default_language"]],
                limits: ["duration_seconds_min": 1, "capture_duration_seconds_max": Double(MicrophoneClipRequest.maximumSeconds),
                         "job_timeout_seconds": Double(MicrophoneClipRequest.longJobTimeoutSeconds), "max_audio_bytes": 1_500_000]),
            operations: canRecord && contextAllowed ? [.init(method: "POST", path: "/v1/mic/record")] : [])
    }
    func profile(for agent: HostAgent, enabledTransfers: Set<TransferKind> = []) -> DeviceProfile {
        let now = Date()
        let ready = agent.ownerDirectPeers != nil ? executionAllowed && ownerDirectAddress != nil
            : agent.mode == "direct" ? executionAllowed && satelliteAddress != nil
            : (executionAllowed || backgroundReading) && state == .connected && !outboundAccess.isPaused
        #if os(macOS)
        let platform = "macos", type = "computer", version = ProcessInfo.processInfo.operatingSystemVersionString
        #else
        let metadata = DeviceTypeDescription.local
        let platform = metadata.platform ?? "ios", type = metadata.deviceType ?? "device"
        let version = ProcessInfo.processInfo.isiOSAppOnMac ? ProcessInfo.processInfo.operatingSystemVersionString : UIDevice.current.systemVersion
        #endif
        let cameraPresent = AppleCamera.hardware
        let microphonePresent = AVCaptureDevice.default(for: .audio) != nil
        let speechReadiness = local?.speech.readiness() ?? [:]
        let speechEnabled = (local?.speech.enabled ?? []).contains { agent.allowsApplet($0, operation: .speechSpeak) }
        let hearingEnabled = agent.allows(.speechHear) && (local?.speech.enabled ?? []).contains { agent.allowsApplet($0, operation: .speechHear) }
        let microphoneAllowed = speechReadiness["microphone_allowed"] as? Bool == true
        let offlineHearing = speechReadiness["hearing_on_device"] as? Bool == true
        var capabilities: [String: ProfileCapability] = [:]
        for runtime in ["device", "applets", "workflows", "camera", "mic", "speaker", "interactions", "settings", "files", "downloads", "shares", "connections", "log", "feedback"] {
            let operations = HostAPIRoutes.operations(for: agent, capability: runtime, installedApplets: Set((local?.packages ?? []).compactMap { $0["id"] as? String }), callbacksAvailable: callbacks != nil)
            let allowed = agent.ownerDirectPeers != nil ? !operations.isEmpty
                : HostOperation.allCases.contains { $0.capability == runtime && agent.allows($0) }
            let enabled = TransferKind(rawValue: runtime).map(enabledTransfers.contains) ?? (runtime == "camera" ? cameraEnabled : runtime == "speaker" ? speechEnabled : true)
            let contextAllowed = !backgroundReading || ["device", "settings", "interactions", "applets"].contains(runtime)
            let supported: Bool
            let deviceReady: Bool
            switch runtime {
            case "camera":
                supported = cameraPresent
                deviceReady = cameraPresent && AppleCamera.permission
            case "speaker":
                supported = true
                deviceReady = speechReadiness["speaking_on_device"] as? Bool == true
            default:
                supported = true
                deviceReady = true
            }
            let hardwareReady = contextAllowed && deviceReady
            let callableOperations = backgroundReading ? operations.filter { $0.method == "GET" || $0.path == "/v1/mcp" } : operations
            let parameters: [String: [String]]? = runtime == "camera" ? ["camera": [AppleCamera.defaultLens], "flash": ["false"], "zoom": ["1"], "size": ["agent", "thumb", "large"], "format": ["jpeg"], "store": ["none"]] : nil
            var limits: [String: Double]? = runtime == "interactions" ? ["pending_per_agent": 5, "terminal_cards_device": 200, "events_per_agent": 1000, "idempotency_seconds": HostIdempotencyPolicy.retentionSeconds, "idempotency_per_agent": Double(HostIdempotencyPolicy.perCallerLimit)] : runtime == "camera" ? ["max_width_px_min": 64, "max_width_px_max": 4096, "quality_min": 40, "quality_max": 100] : nil
            if runtime == "mic" {
                capabilities[runtime] = Self.microphoneCapability(
                    recordingAllowed: agent.mode != "direct" && agent.allows(.microphoneRecord), hearingAllowed: agent.allows(.speechHear),
                    recordingEnabled: microphoneEnabled, hearingEnabled: hearingEnabled, microphonePresent: microphonePresent,
                    microphoneAllowed: microphoneAllowed, offlineHearing: offlineHearing, hostReady: ready, contextAllowed: contextAllowed,
                    notificationsAllowed: captureNotices.availability == .allowed)
                continue
            }
            if runtime == "speaker", let speech = local?.speech {
                limits = ["volume_ceiling_percent": Double(speech.speakerVolumeCeiling)]
            }
            if runtime == "connections" {
                limits = ["max_connections": 25, "max_text_characters": 1000, "max_data_keys": 32,
                          "max_data_bytes": 16384, "sends_per_connection_sender_hour": 60, "timeout_seconds": 15]
            }
            if let kind = TransferKind(rawValue: runtime) {
                limits = ["retention_days": 30, "max_file_bytes": 25 * 1024 * 1024,
                          "max_device_bytes": 100 * 1024 * 1024, "max_read_chunk_bytes": 2 * 1024 * 1024]
                if kind == .files {
                    limits?["max_upload_json_bytes"] = 1_048_576
                    limits?["max_agent_uploads"] = 25
                    limits?["max_agent_upload_bytes"] = 25 * 1024 * 1024
                }
            }
            let noticeReady = runtime != "camera" || captureNotices.availability == .allowed
            capabilities[runtime] = .init(runtimeID: runtime, implemented: true, supported: supported, enabled: enabled, ready: ready && hardwareReady && noticeReady, agentAllowed: allowed,
                callable: ready && hardwareReady && noticeReady && enabled && allowed && !callableOperations.isEmpty, blockedReason: !allowed ? "agent_not_allowed" : !contextAllowed ? "foreground_required" : callableOperations.isEmpty ? "operation_unavailable" : !enabled ? "capability_disabled" : !noticeReady ? "notifications_off" : !hardwareReady ? "hardware_or_permission_unavailable" : !ready ? "foreground_required" : nil,
                constraints: .init(execution: Self.allowsBackgroundAwake ? ["foreground", "background_awake"] : ["device", "settings", "interactions", "applets"].contains(runtime) ? ["foreground", "background_read"] : ["foreground"], parameters: parameters, limits: limits), operations: callableOperations)
        }
        capabilities["lan_discovery"] = HostLanService.capability(agent: agent,
            serviceAvailable: local?.lan != nil, enabled: local?.lanDiscoveryEnabled == true,
            networkAvailable: local?.lan?.networkAvailable == true, hostReady: ready,
            contextAllowed: !backgroundReading,
            execution: Self.allowsBackgroundAwake ? ["foreground", "background_awake"] : ["foreground"],
            outboundPaused: outboundAccess.isPaused)
        return .init(profileRevision: Int(now.timeIntervalSince1970 * 1000), observedAt: now, expiresAt: min(now.addingTimeInterval(30), backgroundReadDeadline ?? now.addingTimeInterval(30)),
            device: .init(installID: nil, name: Self.deviceName, deviceType: type, platform: platform, osVersion: version, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local", apiRevision: HostAPIChanges.current, services: ["view", "host"], hardware: ["camera": cameraPresent ? "present" : "absent", "microphone": microphonePresent ? "present" : "absent"], relayDeviceID: deviceID),
            availability: .init(state: ready ? "online" : "paused", inForeground: foreground), capabilities: capabilities)
    }
}
