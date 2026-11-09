import SwiftUI

/// Linked-device state feeds the same renderer used by the local device and Mac.
struct HomeSceneView: View {
    @ObservedObject var live: PhoneLive
    var otherPhone = true
    var onOpen: (String) -> Void
    var body: some View {
        DiagnosticsSceneView(snapshot: snapshot, onOpen: onOpen,
                             onRerun: { await live.refresh(force: true) },
                             onOlder: { await live.loadOlderLogs() })
    }
    private var snapshot: DiagnosticsSnapshot {
        var options = live.sceneOptions ?? [:]
        if options["nodes"] == nil {
            var direct = options["nodeStates"] as? [String: String] ?? [:]
            if direct["tailscale"] == nil, let state = live.facts.connections["tailscale"] {
                direct["tailscale"] = state == "connected" ? "on" : state == "disconnected" ? "off" : "unreported"
            }
            if direct["clef"] == nil {
                if let watches = (options["capStates"] as? [String: String])?["watches"] { direct["clef"] = watches }
                else if let state = live.facts.services["clef"] {
                    direct["clef"] = state == "ready" || state == "on" ? "on" : state == "off" ? "off" : state == "blocked" ? "blocked" : "unreported"
                }
            }
            options["nodeStates"] = direct
        }
        var reachable = false
        let sourceLabel: String
        switch live.source {
        case .live: reachable = true; sourceLabel = String(localized: "Relay snapshot")
        case .stale: sourceLabel = String(localized: "Last known")
        case .snapshot: sourceLabel = String(localized: "Account snapshot")
        case .sample: sourceLabel = String(localized: "Sample data")
        case .none: sourceLabel = String(localized: "Not reported")
        }
        return DiagnosticsSnapshot(name: live.device.name, metadata: live.device.reportedMetadata, stateAvailable: live.sceneOptions != nil || live.homeStatus != nil, deviceID: live.device.id, animated: reachable, sourceLabel: sourceLabel, options: options, status: live.homeStatus ?? [:],
            logs: live.logs.map(\.sceneJSON), hasOlder: live.hasOlder, activityReported: live.activityReported, paused: live.paused,
            listening: live.listening, otherDevice: otherPhone,
            viewers: [["kind": UIDevice.current.model, "device_type": DeviceTypeDescription.local.deviceType ?? "", "platform": DeviceTypeDescription.local.platform ?? "", "description": DeviceTypeDescription.label(DeviceTypeDescription.local), "sub": "View satellite", "self": true, "live": reachable]])
    }
}
