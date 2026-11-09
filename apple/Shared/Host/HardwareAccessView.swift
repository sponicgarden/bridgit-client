import AVFoundation
import CoreLocation
import BridgitCore
import SwiftUI
#if DEBUG
import UserNotifications
#endif
#if os(iOS)
import UIKit
#endif

enum HardwareSensor: String, CaseIterable, Identifiable {
    case camera = "Camera", microphone = "Microphone"
    var id: Self { self }
    var mediaType: AVMediaType { self == .camera ? .video : .audio }
    var detail: String {
        self == .camera ? "Allows Bridgit to use this device’s camera."
                        : "Allows Bridgit to use this device’s microphone."
    }
}
private enum PermissionGroup: String, CaseIterable {
    case needsYou = "Needs you", allSet = "All set", unavailable = "Unavailable"
}
private struct Permission: Identifiable {
    let sensor: HardwareSensor
    let present: Bool
    let status: AVAuthorizationStatus
    var id: HardwareSensor { sensor }
    static func readAll() -> [Permission] {
        HardwareSensor.allCases.map { sensor in
            Permission(sensor: sensor, present: AVCaptureDevice.default(for: sensor.mediaType) != nil,
                       status: AVCaptureDevice.authorizationStatus(for: sensor.mediaType))
        }
    }
    var diagnostic: String {
        guard present else { return "No " + sensor.rawValue.lowercased() + " found" }
        switch status {
        case .authorized: return "Allowed"
        case .notDetermined, .denied: return "Needs access"
        case .restricted: return "Restricted"
        @unknown default: return "Unknown"
        }
    }
    var group: PermissionGroup {
        guard present else { return .unavailable }
        switch status {
        case .authorized: return .allSet
        case .denied, .notDetermined: return .needsYou
        default: return .unavailable
        }
    }
}

private struct RefreshHardware: ViewModifier {
    @Environment(\.scenePhase) private var phase
    let read: () -> Void

    func body(content: Content) -> some View {
        content.onAppear(perform: read)
            .onChange(of: phase) { _, state in if state == .active { read() } }
            .onReceive(NotificationCenter.default.publisher(for: AVCaptureDevice.wasConnectedNotification)) { _ in read() }
            .onReceive(NotificationCenter.default.publisher(for: AVCaptureDevice.wasDisconnectedNotification)) { _ in read() }
    }
}

/// Reports OS permission and hardware presence independently of host switches and agent grants.
struct LocalHardwareDiagnostics: View {
    var onRefresh: () -> Void = {}
    @State private var permissions: [Permission] = []
    @State private var lastCheck: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Hardware diagnostics").font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(permissions) { row in
                LabeledContent(row.sensor.rawValue, value: row.diagnostic)
            }
            Button("Rerun diagnostics", systemImage: "arrow.clockwise") {
                permissions = Permission.readAll()
                onRefresh()
                lastCheck = Date()
                AccessibilityNotification.Announcement("Hardware diagnostics updated").post()
            }
            if let lastCheck {
                Text("Checked \(lastCheck.formatted(date: .omitted, time: .standard))")
                    .font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
        }
        .modifier(RefreshHardware { permissions = Permission.readAll() })
    }
}

/// Owner-facing OS permissions for the selected local device.
struct HardwareAccessView: View {
    @Environment(\.openURL) private var openURL
    @State private var permissions: [Permission] = []
    @State private var requesting: HardwareSensor?
    @State private var settingsError: String?
    @State private var showingHelp = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button("Show me how", systemImage: "questionmark.circle") { showingHelp = true }
                    .accessibilityIdentifier("hardware-access-help")
                ForEach(PermissionGroup.allCases, id: \.self) { group in
                    let rows = permissions.filter { $0.group == group }
                    if !rows.isEmpty {
                        SectionLabel(text: group.rawValue).accessibilityAddTraits(.isHeader)
                        ForEach(rows) { row in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(row.sensor.rawValue).font(.headline)
                                    Spacer()
                                    if !row.present { Text("No device found") }
                                    else if row.status == .restricted { Text("Restricted") }
                                    else { Text(row.status == .authorized ? "On" : "Off") }
                                }
                                Text(row.sensor.detail).foregroundStyle(B.onSurfaceVariant)
                                if row.group == .needsYou {
                                    if HardwareAccessPolicy.action(status: row.status, present: row.present) == .request {
                                        Button("Grant") {
                                            requesting = row.sensor
                                            Task {
                                                _ = await AVCaptureDevice.requestAccess(for: row.sensor.mediaType)
                                                requesting = nil
                                                refresh()
                                            }
                                        }.disabled(requesting != nil)
                                         .accessibilityLabel("Grant " + row.sensor.rawValue.lowercased() + " access")
                                         .accessibilityIdentifier("hardware-grant-" + row.sensor.rawValue.lowercased())
                                    } else {
                                        Button("Open Settings") { openSettings(for: row.sensor) }
                                            .accessibilityLabel("Open " + row.sensor.rawValue.lowercased() + " settings")
                                            .accessibilityIdentifier("hardware-settings-" + row.sensor.rawValue.lowercased())
                                    }
                                }
                            }
                            Divider()
                        }
                    }
                }
                WiFiHistoryAccessView()
                ForEach(ApplePermissionTopic.hardware.filter { $0 != .camera && $0 != .microphone }) { topic in
                    LabeledContent(topic.title, value: topic.status(.current))
                }
                #if os(iOS)
                Button("Open app settings") { openSettings(for: nil) }
                #else
                Button("Open Privacy & Security") { openSettings(for: nil) }
                #endif
                if let settingsError { Text(settingsError).foregroundStyle(B.error) }
            }.padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(B.surface)
        .navigationTitle("Hardware access")
        .modifier(RefreshHardware(read: refresh))
        .bridgitDialog(isPresented: $showingHelp) { ApplePermissionHelpSheet(hardware: true) }
    }

    private func refresh() {
        permissions = Permission.readAll()
    }

    private func openSettings(for sensor: HardwareSensor?) {
        #if os(iOS)
        let target = UIApplication.openSettingsURLString
        #else
        let privacy = "x-apple.systempreferences:com.apple.preference.security"
        let target = sensor.map { privacy + ($0 == .camera ? "?Privacy_Camera" : "?Privacy_Microphone") } ?? privacy
        #endif
        guard let url = URL(string: target) else { return }
        openURL(url) { accepted in
            settingsError = accepted ? nil : "Could not open privacy settings."
        }
    }
}

/// OS consent is independent of feature switches and per-agent/app-specific authority.
enum HardwareAccessPolicy {
    enum Action { case request, settings, none }
    static func action(status: AVAuthorizationStatus, present: Bool) -> Action {
        guard present else { return .none }
        switch status {
        case .notDetermined: return .request
        case .denied: return .settings
        default: return .none
        }
    }
}

enum ApplePermissionPlatform {
    case iOS, macOS
    static var current: Self {
        #if os(iOS)
        .iOS
        #else
        .macOS
        #endif
    }
}

enum ApplePermissionTopic: String, Identifiable, CaseIterable {
    case camera, microphone, photos, screenshots, notifications, battery, updates
    var id: String { rawValue }
    static let hardware: [Self] = [.camera, .microphone, .photos, .screenshots]
    static let software: [Self] = [.notifications, .battery, .updates]
    var title: String {
        switch self {
        case .camera: "Camera"
        case .microphone: "Microphone"
        case .photos: "Photos"
        case .screenshots: "Screenshots"
        case .notifications: "Notifications"
        case .battery: "Background availability"
        case .updates: "App updates"
        }
    }
    var requestsOSPermission: Bool { self == .camera || self == .microphone || self == .notifications }
    func status(_ platform: ApplePermissionPlatform) -> String {
        switch self {
        case .camera, .microphone: "OS permission"
        case .photos: "Selected attachments only"
        case .screenshots: "Not implemented"
        case .notifications: "Capture notifications"
        case .battery: platform == .macOS ? "While this Mac is awake" : "Foreground required for capture"
        case .updates: platform == .macOS ? "Install a newer Mac build" : "TestFlight"
        }
    }
    func steps(_ platform: ApplePermissionPlatform) -> [String] {
        switch self {
        case .camera, .microphone:
            let sensor = title.lowercased()
            if platform == .iOS {
                return ["Tap Grant for \(sensor) in Hardware access, then allow Bridgit access in the system prompt.",
                        "If access was denied, tap Open Settings and enable \(title) for Bridgit, then return here.",
                        "If access is restricted or no device is found, Grant is unavailable. OS permission does not enable a feature or grant an agent access."]
            }
            return ["Click Grant for \(sensor) in Hardware access, then allow Bridgit access in the macOS prompt.",
                    "If access was denied, open Privacy & Security → \(title) and enable Bridgit, then return here. macOS may ask you to reopen the app.",
                    "If access is restricted or no device is found, Grant is unavailable. OS permission does not enable a feature or grant an agent access."]
        case .photos:
            return [platform == .macOS ? "In an applet’s photo action, choose Photo library. In a report, choose Add screenshot to select an image file." : "In an applet’s photo action, tap Photo library. In a report, tap Add screenshot. Select only the images you want to share.",
                    "This picker does not grant agents access to your photo library. Agent gallery search and library fetch are not implemented.",
                    "Saved applet photos remain subject to that applet’s media permissions and the agent’s applet scope."]
        case .screenshots:
            return ["Agent capture of this device’s screen is not implemented in this Apple build.",
                    "Attaching an existing screenshot to a report does not enable device screenshot capture."]
        case .notifications:
            return ["Allow notifications for Bridgit to show capture notices.",
                    "If notifications are off, open system settings and allow alerts for Bridgit. Focus and system settings can affect delivery."]
        case .battery:
            if platform == .macOS {
                return ["Keep this Mac awake while hosting. The host can run in the background while the Mac is awake; sleep makes it unavailable.",
                        "There is no Android-style unrestricted-battery permission to grant here."]
            }
            return ["Keep Bridgit open for camera and microphone work. iOS does not offer an unrestricted-battery permission for Bridgit.",
                    "Allow relay wake-ups supports limited background reads only. It does not permit background capture, and wake delivery is not guaranteed."]
        case .updates:
            return platform == .macOS
                ? ["Use Check for updates to open the newest Bridgit build installed on this Mac.", "Android’s Install unknown apps permission does not apply to macOS."]
                : ["Use Check for updates to open TestFlight, then install an available Bridgit update.", "Android’s Install unknown apps permission does not apply to iPhone or iPad."]
        }
    }
}

struct ApplePermissionHelpSheet: View {
    let hardware: Bool
    @DialogDismiss private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(hardware ? ApplePermissionTopic.hardware : ApplePermissionTopic.software) { topic in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(topic.title).font(.headline).accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("permission-help-" + topic.id)
                            ForEach(Array(topic.steps(.current).enumerated()), id: \.offset) { index, step in
                                Text("\(index + 1). \(step)")
                                    .accessibilityLabel("\(index + 1). \(step)")
                            }
                        }
                    }
                }.padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(hardware ? "Hardware access" : "Software access")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 600, minHeight: 420, idealHeight: 560)
        #endif
    }
}

struct SoftwareAccessView: View {
    @State private var showingHelp = false
    @State private var notificationError: String?
    #if DEBUG
    @State private var fixtureNoticeResult = ""
    #endif
    @Environment(\.scenePhase) private var phase
    @Environment(\.openURL) private var openURL
    @ObservedObject private var notices = CaptureNotices.shared
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button("Show me how", systemImage: "questionmark.circle") { showingHelp = true }
                    .accessibilityIdentifier("software-access-help")
                LabeledContent("Notifications", value: notices.availability == .unknown ? "Checking" : notices.availability == .allowed ? "On" : "Off")
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("software-notifications-status")
                if notices.availability == .notDetermined {
                    Button("Grant notifications") {
                        Task {
                            do { try await notices.request(); notificationError = nil }
                            catch { notificationError = error.localizedDescription }
                        }
                    }
                        .accessibilityIdentifier("software-grant-notifications")
                } else if notices.availability == .unavailable {
                    Button("Open notification settings") {
                        #if os(iOS)
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        #else
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { openURL(url) }
                        #endif
                    }.accessibilityIdentifier("software-settings-notifications")
                }
                if let notificationError { Text(notificationError).foregroundStyle(B.error) }
                #if DEBUG
                if CommandLine.arguments.contains("-fixtureCaptureNotice") {
                    Button("Send fixture capture notice") {
                        Task {
                            do {
                                let id = try await notices.begin("Fixture agent is taking a photo")
                                defer { notices.end(id) }
                                fixtureNoticeResult = "Notice accepted"
                                for _ in 0..<50 {
                                    let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
                                    if delivered.contains(where: { $0.request.identifier == "capture." + id.uuidString }) {
                                        fixtureNoticeResult = "Notice delivered to notification center"
                                        break
                                    }
                                    try await Task.sleep(nanoseconds: 100_000_000)
                                }
                                let seconds: UInt64 = CommandLine.arguments.contains("-fixtureCaptureNoticeLong") ? 25 : 4
                                try await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                            } catch { fixtureNoticeResult = "Fixture notice failed" }
                        }
                    }.accessibilityIdentifier("fixture-capture-notice")
                    Text(fixtureNoticeResult).accessibilityIdentifier("fixture-capture-result")
                }
                #endif
                ForEach(ApplePermissionTopic.software.filter { $0 != .notifications }) { topic in
                    LabeledContent(topic.title, value: topic.status(.current))
                }
                #if os(iOS)
                TestFlightUpdateButton { Label("Check for updates", systemImage: "arrow.down.circle") }
                #else
                MacUpdateButton { Label("Check for updates", systemImage: "arrow.down.circle") }
                #endif
            }.padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(B.surface)
        .navigationTitle("Software access")
        #if os(iOS)
        .toolbar { ToolbarItem(placement: .primaryAction) { BuildStamp() } }
        #endif
        .task { await notices.refresh() }
        .onChange(of: phase) { _, phase in if phase == .active { Task { await notices.refresh() } } }
        .bridgitDialog(isPresented: $showingHelp) { ApplePermissionHelpSheet(hardware: false) }
    }
}

/// Explicit authorization for identifying the current Wi-Fi network. Never requests coordinates.
@MainActor final class WiFiHistoryAccess: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = WiFiHistoryAccess()
    @Published private(set) var status = "unknown"
    private let manager = CLLocationManager()
    private let statusOverride: (() -> String)?
    private let activeOverride: (() -> Bool)?
    private let promptOverride: (() -> Void)?

    init(status: (() -> String)? = nil, active: (() -> Bool)? = nil, prompt: (() -> Void)? = nil) {
        statusOverride = status; activeOverride = active; promptOverride = prompt
        super.init()
        manager.delegate = self
        refresh()
    }

    static func status(authorization: CLAuthorizationStatus, accuracy: CLAccuracyAuthorization) -> String {
        switch authorization {
        case .notDetermined: return "not_determined"
        case .restricted: return "restricted"
        case .denied: return "denied"
        case .authorizedAlways, .authorizedWhenInUse:
            return accuracy == .fullAccuracy ? "allowed" : "reduced_accuracy"
        @unknown default: return "unknown"
        }
    }
    func refresh() { status = statusOverride?() ?? Self.status(authorization: manager.authorizationStatus, accuracy: manager.accuracyAuthorization) }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { refresh() }
    var remainingAction: String? {
        switch status {
        case "allowed": return nil
        case "not_determined": return "request_permission"
        case "reduced_accuracy": return "enable_precise_location_in_system_settings"
        case "denied": return "enable_location_in_system_settings"
        case "restricted": return "resolve_system_restriction"
        default: return "check_location_settings"
        }
    }
    static func promptNeeded(status: String) throws -> Bool {
        switch status {
        case "not_determined": return true
        case "allowed": return false
        case "reduced_accuracy":
            throw DeviceAPIError(403, "os_permission_required", "Enable precise location for Bridgit in system settings.")
        case "denied":
            throw DeviceAPIError(403, "os_permission_required", "Allow location for Bridgit in system settings.")
        case "restricted":
            throw DeviceAPIError(403, "os_permission_required", "Resolve the system location restriction before continuing.")
        default:
            throw DeviceAPIError(403, "os_permission_required", "Check Bridgit location access in system settings.")
        }
    }
    /// Initiation only: the OS response arrives through the delegate and must be read separately.
    func request() throws {
        #if os(iOS)
        let active = UIApplication.shared.applicationState == .active
        #else
        let active = NSApp.isActive
        #endif
        guard activeOverride?() ?? active else { throw DeviceAPIError(409, "foreground_required", "Open Bridgit to respond to the location permission prompt.") }
        refresh()
        if try Self.promptNeeded(status: status) {
            if let promptOverride { promptOverride() } else { manager.requestWhenInUseAuthorization() }
        }
    }
}

private struct WiFiHistoryAccessView: View {
    @ObservedObject private var access = WiFiHistoryAccess.shared
    @Environment(\.scenePhase) private var phase
    @Environment(\.openURL) private var openURL
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LabeledContent("Wi-Fi history", value: access.status == "allowed" ? "Allowed" : access.status == "restricted" ? "Restricted" : access.status == "denied" ? "Location denied" : access.status == "reduced_accuracy" ? "Precise location off" : "Needs location access")
                .accessibilityIdentifier("wifi-history-status")
            if access.status == "not_determined" {
                Button("Grant location access") {
                    do { try access.request(); problem = nil } catch { problem = error.localizedDescription }
                }.accessibilityIdentifier("wifi-history-grant")
            } else if access.status == "denied" || access.status == "reduced_accuracy" {
                Button("Open location settings") {
                    #if os(iOS)
                    let value = UIApplication.openSettingsURLString
                    #else
                    let value = "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"
                    #endif
                    if let url = URL(string: value) { openURL(url) }
                }.accessibilityIdentifier("wifi-history-settings")
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }.onAppear { access.refresh() }
            .onChange(of: phase) { _, value in if value == .active { access.refresh() } }
    }
}
