import BridgitCore
import SwiftUI

struct MacDiagnosticsView: View {
    @ObservedObject var model: MacModel
    var onOpen: (String) -> Void
    var body: some View {
        Group {
            if model.viewingLinkedDevice {
                DiagnosticsSceneView(snapshot: snapshot, onOpen: onOpen,
                    onRerun: { await model.refreshSelected() },
                    onOlder: { await model.loadOlderDiagnosticsLogs() })
                    .id(model.configuredAttachment)
                    .task(id: model.configuredAttachment) {
                        while !Task.isCancelled {
                            await model.refreshSelected()
                            let allowance = model.snapshot["relay_daily_jobs"] as? Int ?? 200
                            try? await Task.sleep(for: .seconds(allowance >= 5000 ? 15 : 600))
                        }
                    }
            } else {
                LocalDiagnosticsView(onOpen: onOpen)
                    .id(PortalDevice.localID)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if model.paused { Text("Device views are paused").font(.footnote).padding(8) }
            if let problem = model.problem, model.viewingLinkedDevice {
                Text(problem).font(.footnote).foregroundStyle(B.error).padding(8)
            }
        }
    }
    private var metadata: ReportedDeviceMetadata {
        let home = model.snapshot["home"] as? [String: Any] ?? model.snapshot
        let reported = ReportedDeviceMetadata(device: home["device"] as? [String: Any] ?? [:])
        let account = model.phones.first { $0.phone == model.selectedInstallID }?.reportedMetadata ?? .init()
        return reported.fillingMissing(from: account)
    }
    private var snapshot: DiagnosticsSnapshot {
        let home = model.snapshot["home"] as? [String: Any] ?? model.snapshot
        let scene = home["scene"] as? [String: Any] ?? home
        let log = model.snapshot["logs"] as? [String: Any]
        let entries = log?["entries"] as? [[String: Any]] ?? model.snapshot["logs"] as? [[String: Any]] ?? []
        var options = scene["options"] as? [String: Any] ?? [:]
        if options.isEmpty, let phone = model.phones.first(where: { $0.phone == model.selectedInstallID }) {
            options = ["capStates": phone.status?.caps ?? [:]]
            if let names = phone.status?.agents {
                options["agents"] = names.map { name -> [String: Any] in
                    var row: [String: Any] = ["name": name]
                    if let status = phone.status?.agent_status?.first(where: { $0.name == name }), let used = status.last_used_at { row["called"] = used > 0 }
                    return row
                }
            }
            var direct: [String: String] = [:]
            if let network = phone.status?.connections?["tailscale"] {
                direct["tailscale"] = network == "connected" ? "on" : network == "disconnected" ? "off" : "unreported"
            }
            for id in ["jev", "clef", "weather"] {
                if let state = phone.status?.services?[id] { direct[id] = state == "ready" ? "on" : ReportedFeatureState(reported: state).rawValue }
            }
            options["nodeStates"] = direct
        }
        var status = scene["home_status"] as? [String: Any] ?? [:]
        if status["applets"] == nil, let applets = model.phones.first(where: { $0.phone == model.selectedInstallID })?.status?.applets {
            status["applets"] = applets.filter { $0.running == true }.map { ["name": $0.name ?? $0.kind, "state": "Running", "description": "Running · " + ($0.name ?? $0.kind)] }
            status["emptyApplets"] = applets.contains { $0.running == nil } ? "Applet status not reported" : "No applets running"
        }
        let freshRelay = model.selectedPairing != nil && model.snapshotAt != nil && model.problem == nil && !model.paused
        let sourceLabel = model.selectedPairing == nil ? String(localized: "Account snapshot") : freshRelay ? String(localized: "Relay snapshot") : String(localized: "Last known")
        return DiagnosticsSnapshot(name: model.selectedDeviceName ?? "Device", metadata: metadata, stateAvailable: !model.snapshot.isEmpty || model.phones.first(where: { $0.phone == model.selectedInstallID })?.status != nil, deviceID: model.configuredAttachment, animated: freshRelay, sourceLabel: sourceLabel, options: options,
            status: status, logs: entries,
            hasOlder: log?["has_older"] as? Bool ?? false,
            activityReported: log?["entries"] is [[String: Any]] || model.snapshot["logs"] is [[String: Any]],
            paused: scene["paused"] as? Bool ?? false,
            listening: scene["listening"] as? [String] ?? [], otherDevice: true,
            viewers: [["kind": "Mac", "platform": "macos", "device_type": "computer", "description": "Mac", "sub": "View satellite", "self": true,
                       "live": model.snapshotAt != nil && model.problem == nil && !model.paused]])
    }
}
