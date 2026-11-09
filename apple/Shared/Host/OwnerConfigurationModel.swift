import BridgitCore
import Combine
import Foundation

struct DeviceOutboundPolicy: Sendable {
    static let preferenceKey = "privacy.pause_external_calls"
    static let access = OutboundAccess(paused: { AppleFixtureWorkspace.defaults.bool(forKey: preferenceKey) })
    static let shared = DeviceOutboundPolicy(
        access: access,
        set: { paused in
            CrashReports.consent.setOutboundPaused(paused) {
                AppleFixtureWorkspace.defaults.set(paused, forKey: preferenceKey)
            }
        })

    let access: OutboundAccess
    let set: @Sendable (Bool) -> Void
}

struct DeviceNamePreference: Sendable {
    static let key = "device.displayName"
    static let shared = Self(
        read: { AppleFixtureWorkspace.defaults.string(forKey: key) ?? "" },
        write: { AppleFixtureWorkspace.defaults.set($0, forKey: key) })

    let read: @Sendable () -> String
    let write: @Sendable (String) -> Void
}

struct CrashReportingSettings {
    static var platform: Self? {
        guard CrashReports.configured else { return nil }
        return .init(read: { CrashReports.consent.enabled }, write: { CrashReports.consent.setEnabled($0) },
                     reconcile: { CrashReports.start() })
    }

    let read: @Sendable () -> Bool
    let write: @Sendable (Bool) -> Void
    let reconcile: @MainActor () -> Void
}

@MainActor final class OwnerConfigurationModel: ObservableObject, HostConfigurationService {
    private weak var local: LocalAppletModel?
    var canConfigureExternalCalls: Bool { local?.store != nil }
    private let crashReporting: CrashReportingSettings?
    @Published private(set) var crashReportingEnabled: Bool?
    private let deviceNamePreference: DeviceNamePreference
    @Published private(set) var deviceNameOverride: String
    private let outboundPolicy: DeviceOutboundPolicy
    private var outboundLifecycle: Task<Void, Never>?
    @Published private(set) var externalCallsPaused: Bool
    var captureNotices = CaptureNotices.shared
    var wifiHistoryAccess = WiFiHistoryAccess.shared
    weak var viewer: (any ViewerConfiguration)?
    @Published private(set) var directDeviceLock: Bool?
    @Published private(set) var activityRetention: [HostActivityRetentionField: Int]?
    @Published private(set) var logVisibility: HostLogVisibility?
    @Published private(set) var requests: [[String: Any]] = []
    @Published private(set) var turningOffCapabilities = false
    var speech: LocalSpeechModel? { local?.speech }
    @Published var problem: String?
    /// Device Domain Name (bridgit.cc/names); nil where the names service is unavailable.
    let bridgitNames: DeviceDomainNameService?
    @Published private(set) var bridgitName: DeviceDomainNameRecord?
    private var bridgitNameHeartbeat: Task<Void, Never>?
    init(local: LocalAppletModel, outboundPolicy: DeviceOutboundPolicy = .shared, deviceNamePreference: DeviceNamePreference = .shared,
         crashReporting: CrashReportingSettings? = .platform, bridgitNames: DeviceDomainNameService? = .platform) {
        self.local = local
        self.bridgitNames = bridgitNames
        self.crashReporting = crashReporting
        crashReportingEnabled = crashReporting?.read()
        self.deviceNamePreference = deviceNamePreference
        deviceNameOverride = deviceNamePreference.read()
        self.outboundPolicy = outboundPolicy
        externalCallsPaused = outboundPolicy.access.isPaused
        startBridgitNameHeartbeat()
    }
    deinit { bridgitNameHeartbeat?.cancel() }
    func supportsCrashReporting() async -> Bool { crashReporting != nil }

    func bridgitNameHint(canConfigure: Bool) async -> [String: Any]? {
        await bridgitNames?.hint(canConfigure: canConfigure)
    }

    /// A visible name says "I'm on" every heartbeat interval while the app runs (the service skips early ticks).
    private func startBridgitNameHeartbeat() {
        guard let service = bridgitNames else { return }
        bridgitNameHeartbeat = Task { [weak self] in
            self?.bridgitName = await service.cached
            while !Task.isCancelled {
                if await service.heartbeatIfDue() { self?.bridgitName = await service.cached }
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { break }
            }
        }
    }

    /// /v1/owner/configuration/name: GET reads, POST registers or changes label/visibility, DELETE hides.
    private func bridgitNameConfiguration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard let service = bridgitNames, let authority = local?.host?.authority else {
            throw DeviceAPIError(503, "names_unavailable", "Bridgit names are unavailable on this device.")
        }
        guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Names configuration takes no query.") }
        let operation: HostOperation = request.method == "GET" ? .configurationRead : .configurationWrite
        func authorize() async throws { try Task.checkCancellation(); try await authority.authorizeOngoing(agent, operation: operation) }
        try await authorize()
        let value: [String: Any]
        switch request.method {
        case "GET":
            guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Names reads take no body.") }
            value = try await service.refresh()
        case "POST":
            let change = try DeviceDomainNameChange(body: request.body)
            try await authorize()
            value = try await service.set(name: change.name, label: change.label, visible: change.visible)
        case "DELETE":
            guard request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Hiding the name takes no body.") }
            value = try await service.hide()
        default: throw DeviceAPIError(405, "method_not_allowed", "Use GET, POST or DELETE.")
        }
        bridgitName = await service.cached
        return try .json(value)
    }

    /// Owner UI: register a name, or change the label or visibility of the one this Bridgit has.
    func setBridgitName(name: String? = nil, label: String?? = nil, visible: Bool? = nil) async throws {
        guard let service = bridgitNames else { throw DeviceAPIError(503, "names_unavailable", "Bridgit names are unavailable on this device.") }
        var body: [String: Any] = [:]
        if let name { body["name"] = name }
        if let label { body["label"] = label ?? NSNull() }
        if let visible { body["visible"] = visible }
        let change = try DeviceDomainNameChange(body: body)
        _ = try await service.set(name: change.name, label: change.label, visible: change.visible)
        bridgitName = await service.cached
    }

    func refreshBridgitName() async {
        guard let service = bridgitNames else { return }
        do { _ = try await service.refresh() } catch { problem = error.localizedDescription }
        bridgitName = await service.cached
    }

    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        guard let local, let store = local.store else { throw DeviceAPIError(503, "storage_unavailable", "Storage is unavailable.") }
        let path = request.path.split(separator: "/").map(String.init), owner = agent.id + ":" + agent.generation
        if Array(path.prefix(4)) == ["v1", "owner", "configuration", "inbound-shares"] {
            guard let service = local.inboundSharing, let authority = local.host?.authority else { throw DeviceAPIError(503, "sharing_unavailable", "Inbound sharing is unavailable.") }
            guard request.query.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Inbound sharing takes no query.") }
            func authorize() async throws { try Task.checkCancellation(); try await authority.authorizeOngoing(agent, operation: request.method == "GET" ? .configurationRead : .configurationWrite) }
            try await authorize()
            if path.count == 4, request.method == "GET", request.body.isEmpty { return try .json(await service.status()) }
            if path.count == 4, request.method == "POST" {
                let value = try await service.enqueue(request.body) { mode in
                    try await authorize(); try await authority.authorizeOngoing(agent, operation: mode.kind.grant)
                }
                return try .json(value.summary, status: 201)
            }
            if path.count >= 5, UUID(uuidString: path[4]) != nil, let inbox = service.inbox, request.body.isEmpty {
                if path.count == 5, request.method == "GET" { return try .json(inbox.request(path[4]).summary) }
                if path.count == 5, request.method == "DELETE" { try await service.cancel(path[4]); return try .json(inbox.request(path[4]).summary) }
                if path.count == 6, path[5] == "retry", request.method == "POST" {
                    let item = try inbox.request(path[4]); try await authority.authorizeOngoing(agent, operation: item.mode.kind.grant)
                    try await authorize(); try await service.retry(item.id); return try .json(inbox.request(item.id).summary)
                }
            }
            throw DeviceAPIError(422, "invalid_parameter", "Use a declared inbound share operation without extra fields.")
        }
        if Array(path.prefix(4)) == ["v1", "owner", "configuration", "connections"] {
            guard let service = local.connections, let authority = local.host?.authority else { throw DeviceAPIError(503, "connections_unavailable", "Connection configuration is unavailable.") }
            let operation: HostOperation = request.method == "GET" ? .configurationRead : .configurationWrite
            return try await service.configuration(request, agent: agent) {
                try Task.checkCancellation()
                try await authority.authorizeOngoing(agent, operation: operation)
            }
        }
        if path == ["v1", "owner", "configuration", "name"] { return try await bridgitNameConfiguration(request, agent: agent) }
        if path.dropFirst().first == "agents" {
            guard let host = local.host, let authority = host.authority else { throw DeviceAPIError(503, "settings_unavailable", "Host authority is unavailable.") }
            let response = try await authority.administer(request, caller: agent)
            if request.method != "GET" {
                let target = path.count >= 3 && UUID(uuidString: path[2]) != nil ? path[2].lowercased() : nil
                try await host.refreshAgentAccess(target: target == agent.id ? nil : target)
            }
            return response
        }
        if path.count >= 2, ["account", "linked-devices"].contains(path[1]) {
            guard let viewer, let authority = local.host?.authority else { throw DeviceAPIError(503, "configuration_unavailable", "The owner app is not ready for account configuration.") }
            let action = try ViewerConfigurationAction(request)
            return try await ViewerConfigurationRouter.execute(action, viewer: viewer, authorize: {
                try Task.checkCancellation()
                try await authority.authorizeOngoing(agent, operation: request.method == "GET" ? .configurationRead : .configurationWrite)
            })
        }
        if path.count == 4 {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Request status and cancellation take no parameters.") }
            let value: [String: Any]
            if request.method == "GET" { value = try await store.ownerRequest(owner: owner, id: path[3]) }
            else { value = try await store.cancelOwnerRequest(owner: owner, id: path[3]) }
            return try .json(value)
        }
        if request.method == "GET" {
            guard request.query.isEmpty, request.body.isEmpty else { throw DeviceAPIError(422, "invalid_parameter", "Settings reads take no parameters.") }
            var values: [String: Any] = ["feedback_upload": FeedbackReports.shared.sendingEnabled, "host_enabled": local.host?.enabledIntent ?? false, "host_status": local.host?.state.rawValue ?? "stopped", "camera_os_permission": AppleCamera.permission, "host_camera": local.host?.cameraEnabled ?? false, "host_microphone": local.host?.microphoneEnabled ?? false, "host_wake": local.host?.wakeEnabled ?? false, "wake_status": local.host?.wakeStatus ?? "off", "cloud_appraisals": local.modelEnabled,
                "applets": local.packages.compactMap { package -> [String: Any]? in
                    guard let id = package["id"] as? String, agent.allowsApplet(id, operation: .configurationRead) else { return nil }
                    return ["id": id, "speech_enabled": local.speech.enabled.contains(id), "speech_cloud": local.speech.cloudAllowed.contains(id)]
                }]
            let name = deviceNamePreference.read()
            values[HostSettingChange.deviceNameField] = name
            values["device_name_effective"] = LocalHostModel.effectiveDeviceName(override: name)
            values[HostSettingChange.externalCallsPausedField] = outboundPolicy.access.isPaused
            values[HostSettingChange.speakerVolumeField] = local.speech.speakerVolumeCeiling
            values["lan_discovery_enabled"] = local.lanDiscoveryEnabled
            for (field, limit) in try await store.hostActivityRetention() { values[field.rawValue] = limit }
            values["log_visibility"] = try await store.hostLogVisibility().rawValue
            values["host_direct_enabled"] = local.host?.ownerDirectIntent ?? false
            values["host_direct_device_lock"] = try await store.ownerDirectDeviceLock()
            values["host_direct_status"] = local.host?.ownerDirectAddress != nil ? "listening" : "stopped"
            values["host_direct_address"] = local.host?.ownerDirectPreferredAddress?.value as Any? ?? NSNull()
            values["host_direct_bound_address"] = local.host?.ownerDirectAddress?.value as Any? ?? NSNull()
            values["host_direct_address_candidates"] = local.host?.availableTailnetAddresses.map(\.value) ?? []
            values["host_direct_port"] = local.host?.ownerDirectPort.map { Int($0) } as Any? ?? NSNull()
            let speech = local.speech.readiness()
            wifiHistoryAccess.refresh()
            values["wifi_history_authorization"] = wifiHistoryAccess.status
            values["wifi_history_remaining_action"] = wifiHistoryAccess.remainingAction as Any? ?? NSNull()
            await captureNotices.refresh()
            values["os_permissions"] = ["capture_notifications": captureNotices.availability == .allowed,
                                        "camera": AppleCamera.permission,
                                        "microphone": speech["microphone_allowed"] as? Bool == true,
                                        "speech_recognition": speech["recognition_allowed"] as? Bool == true]
            values["os_authorization"] = ["capture_notifications": captureNotices.availability.rawValue,
                                           "microphone": speech["microphone_authorization"] as? String ?? "unknown",
                                           "speech_recognition": speech["recognition_authorization"] as? String ?? "unknown"]
            switch captureNotices.availability {
            case .allowed: values["capture_notifications_remaining_action"] = NSNull()
            case .notDetermined: values["capture_notifications_remaining_action"] = "request_permission"
            case .unavailable: values["capture_notifications_remaining_action"] = "enable_alerts_in_system_settings"
            case .unknown: values["capture_notifications_remaining_action"] = "check_notification_settings"
            }
            values["callback_allow_internet"] = try await store.callbackInternetAllowed()
            let enabledTransfers = try await store.enabledTransfers()
            for kind in TransferKind.allCases { values[kind.rawValue + "_enabled"] = enabledTransfers.contains(kind) }
            values["feedback_auto_fix"] = UserDefaults.standard.object(forKey: "feedback.bug.autoFix") as? Bool ?? true
            var supported = HostSettingChange.availableFields(crashReporting: crashReporting != nil)
            if let crashReporting { values[HostSettingChange.crashReportsField] = crashReporting.read() }
            values["appearance"] = AppleFixtureWorkspace.defaults.string(forKey: "appearance") ?? "System"
            values["devices_tab"] = AppleFixtureWorkspace.defaults.string(forKey: "devices.tab") ?? "map"
            #if os(macOS)
            supported.removeAll { $0 == "host_wake" }
            #endif
            return try .json(["values": values, "supported_fields": supported, "device_capabilities_off_fields": HostSettingChange.deviceCapabilityFields
                + (crashReporting != nil ? [HostSettingChange.crashReportsField] : []), "write_mode": "authorized_after_delivery", "operation_expires_seconds": 600, "request_limit_per_caller": 3, "request_url": "/v1/settings/requests/{id}", "secrets": "write_only"])
        }
        let change = try HostSettingChange(request, agent: agent, crashReporting: crashReporting != nil)
        #if os(macOS)
        guard change.field != "host_wake" else { throw DeviceAPIError(422, "unsupported_setting", "This setting is available on iPhone and iPad.") }
        #endif
        if let applet = change.applet { _ = try await store.package(applet) }
        let summary = Self.summary(change)
        let value = try await store.createOwnerRequest(owner: owner, sender: agent.name, summary: summary, payload: change.payload)
        await reload()
        return await attachDelivery(to: try .json(value, status: 202), agent: agent)
    }
    func turnOffDeviceCapabilities(request: (id: String, agent: HostAgent)? = nil) async throws {
        guard let local, let host = local.host, let store = local.store else {
            throw DeviceAPIError(503, "settings_unavailable", "Host settings are unavailable.")
        }
        guard !turningOffCapabilities else {
            throw DeviceAPIError(409, "settings_busy", "Device capabilities are already being turned off.")
        }
        turningOffCapabilities = true
        defer { turningOffCapabilities = false }
        let disableCrashReporting: (@Sendable () -> Void)?
        if let write = crashReporting?.write { disableCrashReporting = { write(false) } }
        else { disableCrashReporting = nil }
        try await store.disableDurableDeviceCapabilities(request: request, disableCrashReporting: disableCrashReporting)
        refreshCrashReporting()
        host.disableHostSensors()
        local.disableModels()
        local.setLanDiscoveryEnabled(false)
        await host.refreshCallbackInternetPolicy()
    }

    func reload() async {
        do {
            deviceNameOverride = deviceNamePreference.read()
            requests = try await local?.store?.ownerRequests() ?? []
            activityRetention = try await local?.store?.hostActivityRetention()
            logVisibility = try await local?.store?.hostLogVisibility()
            directDeviceLock = try await local?.store?.ownerDirectDeviceLock()
        }
        catch { problem = error.localizedDescription }
    }
    func setDirectDeviceLock(_ enabled: Bool) async {
        guard let store = local?.store else { return }
        do {
            try await store.setOwnerDirectDeviceLock(enabled)
            directDeviceLock = try await store.ownerDirectDeviceLock()
        } catch { problem = error.localizedDescription }
    }
    func setExternalCallsPaused(_ paused: Bool) async {
        guard let store = local?.store, paused != outboundPolicy.access.isPaused else { return }
        let policy = outboundPolicy
        do {
            try await store.setExternalCallsPolicy(paused: paused) { policy.set(paused) }
            externalPolicyChanged()
        } catch { problem = error.localizedDescription }
    }

    private func externalPolicyChanged() {
        externalCallsPaused = outboundPolicy.access.isPaused
        let preceding = outboundLifecycle
        outboundLifecycle = Task { [weak local] in
            await preceding?.value
            await local?.host?.reconcileExternalCalls()
        }
    }

    func setCrashReporting(_ enabled: Bool, request: (id: String, agent: HostAgent)? = nil) async throws {
        guard let store = local?.store, let crashReporting else {
            throw DeviceAPIError(422, "unsupported_setting", "Crash reporting is unavailable in this build.")
        }
        try await store.setHostCrashReporting(enabled, request: request, apply: crashReporting.write)
        refreshCrashReporting()
    }

    private func refreshCrashReporting() {
        crashReportingEnabled = crashReporting?.read()
        crashReporting?.reconcile()
    }

    func setDeviceName(_ raw: String) async throws {
        guard let store = local?.store else { throw DeviceAPIError(503, "storage_unavailable", "Storage is unavailable.") }
        try await store.setHostDeviceName(raw, apply: deviceNamePreference.write)
        deviceNameOverride = deviceNamePreference.read()
    }

    func setActivityRetention(_ values: [HostActivityRetentionField: Int]) async throws {
        guard let store = local?.store else { throw DeviceAPIError(503, "storage_unavailable", "Storage is unavailable.") }
        try await store.setHostActivityRetention(values)
        activityRetention = try await store.hostActivityRetention()
    }
    func setLogVisibility(_ visibility: HostLogVisibility) async {
        guard let store = local?.store else { return }
        do {
            try await store.setHostLogVisibility(visibility)
            logVisibility = try await store.hostLogVisibility()
        } catch { problem = error.localizedDescription }
    }
    func cancel(_ id: String) async {
        guard let local, let store = local.store else { return }
        do { try await store.cancelConfigurationOperation(id: id) }
        catch { problem = error.localizedDescription }
        await reload()
    }
    func attachDelivery(to response: HostResponse, agent: HostAgent) async -> HostResponse {
        guard response.status == 202,
              let value = try? JSONSerialization.jsonObject(with: response.data) as? [String: Any],
              value["status"] as? String == "queued", let id = value["request_id"] as? String else { return response }
        var result = response
        result.afterDelivery = { [weak self] in await self?.apply(id, requester: agent) }
        return result
    }
    private func apply(_ id: String, requester: HostAgent) async {
        let expectedOwner = requester.eventOwner
        guard let local, let store = local.store, let authority = local.host?.authority else { return }
        var claimed = false
        do {
            let previous = try await store.ownerRequest(owner: expectedOwner, id: id)
            guard previous["status"] as? String == "queued" else { return }
            let proposal = try await store.beginOwnerRequest(id: id)
            claimed = true
            guard proposal.owner == expectedOwner else { throw CancellationError() }
            let parts = proposal.owner.split(separator: ":").map(String.init)
            guard parts.count == 2, let agent = try await authority.agent(parts[0]), agent.generation == parts[1] else { throw CancellationError() }
            let change = try HostSettingChange(.init(method: "POST", path: "/v1/settings", body: proposal.payload), agent: agent, crashReporting: crashReporting != nil)
            let authorize: () async throws -> Void = {
                try Task.checkCancellation()
                try await store.ensureOwnerRequestApplying(id: id)
                try await authority.authorizeOngoing(requester, operation: .configurationWrite, applet: change.applet)
                guard let current = try await authority.agent(agent.id), current.generation == agent.generation, !current.paused, current.allows(.configurationWrite), change.applet == nil || current.allowsApplet(change.applet!, operation: .configurationWrite) else { throw DeviceAPIError(403, "agent_revoked", "The requesting agent no longer has this access.") }
            }
            let commit: () async throws -> Void = {
                try await authorize()
                try await store.commitOwnerRequest(id: id)
            }
            try await authorize()
            switch change.field {
            case HostSettingChange.disableDeviceCapabilities:
                try await turnOffDeviceCapabilities(request: (id, requester))
            case "files_enabled", "shares_enabled", "downloads_enabled":
                guard let kind = TransferKind(rawValue: String(change.field.dropLast("_enabled".count))) else { throw CancellationError() }
                try await commit()
                try await store.setTransferEnabled(kind, enabled: change.value as? Bool == true)
            case "callback_allow_internet":
                guard let host = local.host else { throw DeviceAPIError(503, "host_unavailable", "Host settings are unavailable.") }
                try await commit()
                try await host.setCallbackInternetAllowed(change.value as? Bool == true, requestedBy: requester)
            case "feedback_auto_fix":
                try await commit()
                UserDefaults.standard.set(change.value as? Bool, forKey: "feedback.bug.autoFix")
            case "appearance":
                try await commit()
                AppleFixtureWorkspace.defaults.set(change.value as? String, forKey: "appearance")
            case "devices_tab":
                try await commit()
                AppleFixtureWorkspace.defaults.set(change.value as? String, forKey: "devices.tab")
            case "lan_discovery_enabled":
                try await commit()
                local.setLanDiscoveryEnabled(change.value as? Bool == true)
            case "host_enabled":
                if change.value as? Bool == true {
                    await local.host?.start()
                    do { try await commit() } catch { await local.host?.stop(); throw error }
                    guard local.host?.state == .connected else { throw DeviceAPIError(409, "host_not_connected", "The host did not connect.") }
                } else { try await commit(); await local.host?.stop() }
            case "host_wake":
                if change.value as? Bool != true { try await commit() }
                await local.host?.setWakeEnabled(change.value as? Bool == true, authorize: authorize, commit: commit)
                guard local.host?.wakeEnabled == change.value as? Bool else { throw DeviceAPIError(409, "push_not_supported", "Apple wake-ups were not enabled.") }
            case "feedback_upload":
                try await commit()
                try FeedbackReports.shared.setSendingEnabled(change.value as? Bool == true)
            case HostSettingChange.wifiHistoryPermissionRequest:
                try await commit()
                try wifiHistoryAccess.request()
            case HostSettingChange.captureNotificationsRequest:
                try await captureNotices.request()
                try await authorize()
                guard captureNotices.availability == .allowed else {
                    throw DeviceAPIError(403, "os_permission_required", "Allow capture notifications in system settings.")
                }
                try await commit()
            case "host_camera":
                if change.value as? Bool != true { try await commit() }
                await local.host?.setCameraEnabled(change.value as? Bool == true, authorize: authorize, commit: commit)
                guard local.host?.cameraEnabled == change.value as? Bool else { throw DeviceAPIError(403, "os_permission_required", "Camera access was not enabled.") }
            case "host_microphone":
                guard let host = local.host else { throw DeviceAPIError(503, "settings_unavailable", "Host settings are unavailable.") }
                let enabled = change.value as? Bool == true
                if !enabled { try await commit() }
                try await host.setMicrophoneEnabled(enabled, authorize: authorize, commit: commit)
            case "host_direct_device_lock":
                try await commit()
                try await store.setOwnerDirectDeviceLock(change.value as? Bool == true, requestedBy: requester)
            case "host_direct_address":
                guard let host = local.host else { throw DeviceAPIError(503, "settings_unavailable", "Host settings are unavailable.") }
                try await commit()
                try await host.updateOwnerDirectPreferredAddress((change.value as? String).flatMap(TailnetAddress.init))
            case "host_direct_enabled":
                guard let host = local.host else { throw DeviceAPIError(503, "settings_unavailable", "Host settings are unavailable.") }
                try await commit()
                try await authority.authorizeOngoing(requester, operation: .configurationWrite)
                if change.value as? Bool == true {
                    try await host.enableConfiguredOwnerDirect(requester: requester)
                }
                else { await host.stopOwnerDirect() }
            case HostSettingChange.externalCallsPausedField:
                guard let paused = change.value as? Bool else { throw CancellationError() }
                let policy = outboundPolicy
                try await store.setExternalCallsPolicy(paused: paused, request: (id, requester)) { policy.set(paused) }
            case HostSettingChange.crashReportsField:
                guard let value = change.value as? Bool else { throw CancellationError() }
                try await setCrashReporting(value, request: (id, requester))
            case HostSettingChange.deviceNameField:
                guard let value = change.value as? String else { throw CancellationError() }
                try await store.setHostDeviceName(value, request: (id, requester), apply: deviceNamePreference.write)
            case HostSettingChange.speakerVolumeField:
                guard let value = change.value as? Int else { throw CancellationError() }
                try await commit()
                local.speech.setSpeakerVolumeCeiling(value)
            case HostActivityRetentionField.entries.rawValue, HostActivityRetentionField.days.rawValue:
                guard let field = HostActivityRetentionField(rawValue: change.field), let limit = change.value as? Int else {
                    throw DeviceAPIError(422, "invalid_setting", "Use an allowed Activity retention limit.")
                }
                try await store.setHostActivityRetention([field: limit], request: (id, requester))
            case "log_visibility":
                guard let value = change.value as? String, let visibility = HostLogVisibility(rawValue: value) else {
                    throw DeviceAPIError(422, "invalid_setting", "Choose all Activity or the agent’s own Activity.")
                }
                try await commit()
                try await store.setHostLogVisibility(visibility, requestedBy: requester)
            case "cloud_appraisals":
                if change.value as? Bool == true {
                    await local.enableModels(authorize: authorize, commit: commit)
                    guard local.modelEnabled else { throw DeviceAPIError(409, "registration_failed", "Cloud appraisals were not enabled.") }
                } else { try await commit(); local.disableModels() }
            case "speech_enabled":
                let applet = change.applet!
                if change.value as? Bool == true {
                    let package = try await store.package(applet)
                    let hearing = (((package["processing"] as? [String: Any])?["flows"] as? [[String: Any]]) ?? []).contains { ($0["steps"] as? [[String: Any]] ?? []).contains { $0["type"] as? String == "hear" } }
                    await local.speech.enable(applet: applet, hearing: hearing, authorize: authorize, commit: commit)
                    guard local.speech.enabled.contains(applet) else { throw DeviceAPIError(403, "os_permission_required", "Speech was not enabled.") }
                } else { try await commit(); local.speech.disable(applet: applet) }
            case "speech_cloud":
                try await commit()
                local.speech.setCloud(applet: change.applet!, allowed: change.value as? Bool == true)
                guard local.speech.cloudAllowed.contains(change.applet!) == (change.value as? Bool == true) else { throw DeviceAPIError(409, "speech_disabled", "Enable this applet's speech first.") }
            default: throw DeviceAPIError(422, "unsupported_setting", "This setting is unavailable.")
            }
            try await store.finishOwnerRequest(id: id, status: "completed")
            if change.field == HostSettingChange.externalCallsPausedField { externalPolicyChanged() }
        } catch {
            // An acknowledged retry may race the first worker. A failed claim must
            // never finalize or cancel another worker's applying operation.
            guard claimed else { return }
            let status = (error as? DeviceAPIError)?.code == "os_permission_required" ? "needs_os_permission" : "failed"
            try? await store.finishOwnerRequest(id: id, status: status, message: error.localizedDescription)
            problem = error.localizedDescription
        }
        await reload()
    }
    private static func summary(_ change: HostSettingChange) -> String {
        if let field = HostActivityRetentionField(rawValue: change.field), let limit = change.value as? Int {
            let unit = field == .entries ? "entries" : "days"
            return "Activity retention · " + (limit == 0 ? "Unlimited " + unit : "\(limit) " + unit)
        }
        if change.field == HostSettingChange.crashReportsField {
            return change.value as? Bool == true ? "Enable crash reports" : "Disable crash reports"
        }
        if change.field == HostSettingChange.deviceNameField {
            return (change.value as? String)?.isEmpty == false ? "Change Bridgit device name" : "Reset Bridgit device name"
        }
        if change.field == HostSettingChange.externalCallsPausedField { return change.value as? Bool == true ? "Pause external calls" : "Resume external calls" }
        if change.field == HostSettingChange.speakerVolumeField, let value = change.value as? Int { return "Agent speech volume limit: \(value)%" }
        if change.field == HostSettingChange.disableDeviceCapabilities { return "Turn off device capabilities" }
        if change.field == HostSettingChange.wifiHistoryPermissionRequest { return "Request Wi-Fi history location permission" }
        if change.field == HostSettingChange.captureNotificationsRequest { return "Request capture notification permission" }
        let names = ["callback_allow_internet": "Internet callback destinations", "feedback_auto_fix": "Automatic bug fixes", "files_enabled": "Files", "shares_enabled": "Shares", "downloads_enabled": "Downloads", "devices_tab": "Network devices view", "host_enabled": "Host", "host_camera": "Host camera", "host_microphone": "Host microphone clips", "host_direct_enabled": "Direct Tailscale hosting", "host_direct_address": "Preferred Tailscale address", "host_direct_device_lock": "Tailscale device lock", "log_visibility": "Agent Activity visibility", "host_wake": "Apple relay wake-ups", "feedback_upload": "Send bug reports", "cloud_appraisals": "Photo uploads and paid cloud appraisals", "speech_enabled": "Speech", "speech_cloud": "Cloud audio and text", "appearance": "Appearance"]
        let value = change.value as? String ?? (change.field == "host_direct_address" ? "Automatic" : (change.value as? Bool == true) ? "On" : "Off")
        return (names[change.field] ?? change.field) + (change.applet.map { " · " + $0 } ?? "") + ": " + value
    }
}
