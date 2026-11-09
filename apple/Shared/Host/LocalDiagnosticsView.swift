import AVFoundation
import BridgitCore
import SwiftUI

/// Reads this device's actual local runtime; viewing it never enrolls or enables a host.
struct LocalDiagnosticsView: View {
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.scenePhase) private var phase
    @AppStorage(DeviceNamePreference.key, store: AppleFixtureWorkspace.defaults) private var nameOverride = ""
    @State private var snapshot = DiagnosticsSnapshot(name: LocalHostModel.deviceName, stateAvailable: false)
    @State private var retainedLogs: [HostActivityEntry] = []
    var onOpen: (String) -> Void

    private var displaySnapshot: DiagnosticsSnapshot {
        var display = snapshot
        display.name = LocalHostModel.effectiveDeviceName(override: nameOverride)
        return display
    }

    var body: some View {
        DiagnosticsSceneView(snapshot: displaySnapshot, onOpen: onOpen,
            onRerun: { await refresh() },
            onOlder: { await loadOlder() })
        .task(id: phase) {
            guard phase == .active else { return }
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func refresh() async {
        let host = local.host
        await host?.loadSavedAgents()
        let speech = local.speech.readiness()
        var states: [String: String] = ["ask": local.engine == nil ? "off" : "on", "settings": "on", "bugs": "on"]
        states["camera"] = !AppleCamera.hardware ? "unavailable" : host?.cameraEnabled != true ? "off" : AppleCamera.permission ? "on" : "blocked"
        let hearing = !local.speech.enabled.isEmpty
        let clipEnabled = host?.microphoneEnabled == true
        let clipReady = clipEnabled && speech["microphone_allowed"] as? Bool == true
        let hearingReady = hearing && speech["hearing_on_device"] as? Bool == true
        states["mic"] = clipReady || hearingReady ? "on" : clipEnabled || hearing ? "blocked" : "off"
        states["speaker"] = !hearing ? "off" : speech["speaking_on_device"] as? Bool == true ? "on" : "blocked"
        // Unsupported local capabilities are unavailable, distinct from disabled switches.
        let agents: [[String: Any]] = (host?.agents ?? []).map { ["name": $0.name, "called": false] }
        var value = DiagnosticsSnapshot(name: LocalHostModel.deviceName, metadata: DeviceTypeDescription.local, missingState: .unavailable, deviceID: PortalDevice.localID,
            options: ["nodeStates": states, "agents": agents],
            status: ["applets": local.packages.map { ["id": $0["id"] ?? "", "name": $0["name"] ?? "", "kind": $0["kind"] ?? "applet", "running": false, "state": "Installed", "description": "Installed"] }])
        if let store = local.store, let entries = try? await store.hostActivity(limit: 101) {
            value.activityReported = true
            let combined = entries.prefix(100) + retainedLogs
            var ids = Set<Int64>()
            retainedLogs = combined.filter { ids.insert($0.id).inserted }.sorted { $0.id > $1.id }
            if let oldest = retainedLogs.last?.id {
                value.hasOlder = (try? await store.hostActivity(limit: 1, before: oldest).isEmpty) == false
            }
            value.options["agents"] = (host?.agents ?? []).map { agent in
                ["name": agent.name, "called": !agent.paused && retainedLogs.contains { $0.agentID == agent.id && $0.kind == .request }]
            }
            value.logs = retainedLogs.map { entry in
                var row: [String: Any] = ["id": Int(entry.id), "ts": entry.at.timeIntervalSince1970 * 1000,
                    "agent": entry.agent, "kind": entry.kind.rawValue,
                    "message": entry.operation, "channel": "audit", "level": entry.status >= 400 ? "warn" : "info"]
                if let id = entry.agentID { row["agent_id"] = id }
                if entry.kind == .request { row["http"] = entry.status }
                return row
            }
        }
        guard !Task.isCancelled else { return }
        snapshot = value
    }
    private func loadOlder() async {
        guard let store = local.store, let oldest = retainedLogs.last?.id,
              let page = try? await store.hostActivity(limit: 100, before: oldest) else { return }
        retainedLogs.append(contentsOf: page)
        await refresh()
    }

}

/// The APK chooser switches the scene immediately; pairing management remains under Linked devices.
struct DiagnosticsDeviceChooser: View {
    @DialogDismiss private var dismiss
    var devices: [PortalDevice]
    var current: String?
    var snapshot: [String: Any] = [:]
    var snapshotAt: Date? = nil
    var select: (String) -> Void
    var onLinked: (() -> Void)? = nil
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Devices").font(.headline)
                Spacer()
                Button("Done") { dismiss() }
            }.padding()
            ScrollView {
                VStack(spacing: 12) {
                    choice("This device", id: PortalDevice.localID, metadata: DeviceTypeDescription.local,
                           role: LocalHostModel.deviceName,
                           detail: [ProcessInfo.processInfo.operatingSystemVersionString,
                                    "Bridgit " + (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")].joined(separator: " · "))
                    ForEach(devices) { device in
                        choice(device.name, id: device.id, metadata: device.reportedMetadata,
                               role: device.pairing == nil ? "Linked device" : "Paired device",
                               detail: details(device), at: device.id == current ? snapshotAt.map { $0.timeIntervalSince1970 * 1000 } ?? device.cloud?.status_at : device.cloud?.status_at ?? device.cloud?.last_seen_at,
                               battery: reportedHome(device)["battery"] as? [String: Any])
                    }
                }.padding(16)
            }
            if let onLinked {
                Button("Linked devices and pairing") { dismiss(); onLinked() }
                    .buttonStyle(.bordered).controlSize(.large)
                    .accessibilityIdentifier("diagnostics-linked-devices")
                    .padding(16)
            }
        }
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 520, minHeight: 380, idealHeight: 540)
        #endif
    }
    private func reportedHome(_ device: PortalDevice) -> [String: Any] {
        guard device.id == current else { return [:] }
        return snapshot["home"] as? [String: Any] ?? snapshot
    }
    private func details(_ device: PortalDevice) -> String {
        let info = device.cloud?.status?.device
        let reported = reportedHome(device)["device"] as? [String: Any] ?? [:]
        let version = reported["app_version"] as? String ?? device.cloud?.status?.app_version
        return [reported["model"] as? String ?? info?.model,
                reported["os_version"] as? String ?? reported["android"] as? String ?? info?.os_version ?? info?.android,
                version.map { "Bridgit " + $0 }]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
    private func choice(_ name: String, id: String, metadata: ReportedDeviceMetadata,
                        role: String, detail: String, at: Double? = nil, battery: [String: Any]? = nil) -> some View {
        let selected = id == (current ?? PortalDevice.localID)
        return Button {
            select(id)
            dismiss()
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title2).foregroundStyle(B.primary)
                VStack(alignment: .leading, spacing: 5) {
                    DeviceNameLabel(name: name, metadata: metadata)
                    Text(role).font(.subheadline).foregroundStyle(B.secondary)
                    if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(B.secondary) }
                }
                Spacer()
                if id != PortalDevice.localID {
                    VStack(alignment: .trailing, spacing: 5) {
                        Text(at.map { Date(timeIntervalSince1970: $0 / 1000).formatted(.relative(presentation: .named)) } ?? "Not yet seen")
                        if let percent = battery?["percent"] as? Int {
                            Label("\(percent)%", systemImage: battery?["charging"] as? Bool == true ? "battery.100.bolt" : "battery.100")
                        }
                    }.font(.caption).foregroundStyle(B.secondary)
                }
                Image(systemName: "chevron.right").foregroundStyle(B.secondary)
            }
            .padding(14).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(selected ? B.primary.opacity(0.12) : B.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? B.primary : B.secondary.opacity(0.25), lineWidth: selected ? 2 : 1))
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("diagnostics-device-" + id)
    }
}
