import BridgitCore
import SwiftUI

struct MacRootView: View {
    @ObservedObject private var firstRun: FirstRunController
    @State private var firstRunAgentSetup = false
    @EnvironmentObject var model: MacModel
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.scenePhase) private var phase
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var section: WideSection = .home
    @State private var moreQuery = ""
    @State private var pairingText = ""
    @State private var pairingError: String?
    @State private var showDeviceWebLink = false
    @State private var showAbilities = false
    @State private var showNetworkDevices = false
    @State private var showLocalInfo = false
    @State private var showDeviceChooser = false
    @State private var showLocalFeatures = false
    @State private var showHardwareAccess = false
    @State private var showSoftwareAccess = false
    @State private var showLocalActivity = false
    @State private var showConnectPhone = false
    @State private var showPair = false
    @State private var linkedAppletTarget: PortalDevice?
    @State private var removingPairing: Pairing?
    @State private var removingAccountPhone: Phone?
    @State private var removalAccountRevision: Int?

    private let feedbackReports: FeedbackReports

    /// A0138: the sidebar section the owner last used, reopened on the next launch.
    static let lastSectionKey = "navigation.lastSection"

    init(initialSection: String? = nil, feedbackReports: FeedbackReports? = nil, firstRun: FirstRunController? = nil) {
        self.firstRun = firstRun ?? .inactive()
        _section = State(initialValue: WideSection.restored(initialSection ?? AppleFixtureWorkspace.defaults.string(forKey: Self.lastSectionKey) ?? "Home"))
        self.feedbackReports = feedbackReports ?? .shared
    }

    var body: some View {
        Group {
            if firstRun.isPresented { FirstRunView(controller: firstRun, choose: completeFirstRun) }
            else { mainContent }
        }
        .task {
            await local.host?.loadSavedAgents()
            firstRun.recognizeSavedAgents(local.host?.agents ?? [])
        }
        .accessibilityHidden(firstRunAgentSetup)
        .allowsHitTesting(!firstRunAgentSetup)
        .bridgitDialog(isPresented: $firstRunAgentSetup) {
            if let host = local.host { ControlAgentSetup(host: host, packages: local.packages) }
        }
    }

    private func completeFirstRun(_ action: FirstRunProgress.Action) {
        if action == .addControlAgent && local.host == nil { firstRun.problem = local.problem ?? "The local host could not be opened."; return }
        do { try model.selectConfiguredDevice(PortalDevice.localID) }
        catch { firstRun.problem = error.localizedDescription; return }
        guard firstRun.finish(action) else { return }
        linkedAppletTarget = nil
        section = action == .diagnostic ? .devices : .agents
        firstRunAgentSetup = action == .addControlAgent
    }

    private var mainContent: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            WideSidebar(selection: $section, inboxBadge: local.inboxBadgeLabel) { selected in
                linkedAppletTarget = nil
                if selected == .devices {
                    do { try model.selectConfiguredDevice(PortalDevice.localID) }
                    catch { model.problem = error.localizedDescription }
                }
            }
        } detail: {
            if section == .devices {
                MacDiagnosticsView(model: model) { id in
                    if model.viewingLinkedDevice && !["phones", "connected"].contains(id) { return }
                    switch id {
                    case "phones": showDeviceChooser = true
                    case "connected": section = .linkedDevices
                    case "agent", "addAgent": section = .agents
                    case "ask", "applets": section = .home
                    case "camera", "mic": showHardwareAccess = true
                    case "settings": showLocalFeatures = true
                    default: section = .more
                    }
                }
                .safeAreaInset(edge: .top, spacing: 0) {
                    WidePaneHeading(title: model.viewingLinkedDevice ? model.selectedDeviceName ?? section.title : section.title,
                                    sidebarHidden: columnVisibility == .detailOnly)
                        .padding(.horizontal, 24).padding(.vertical, 16).background(B.surface)
                }
                .bridgitDialog(isPresented: $showDeviceChooser) {
                    DiagnosticsDeviceChooser(devices: devices, current: model.configuredAttachment, snapshot: model.snapshot, snapshotAt: model.snapshotAt) { id in
                        do {
                            if let d = devices.first(where: { $0.id == id }) {
                                if let p = d.pairing { model.select(p) }
                                else if let phone = d.cloud { model.selectAccount(phone) }
                            } else { try model.selectConfiguredDevice(PortalDevice.localID) }
                        } catch { model.problem = error.localizedDescription }
                    } onLinked: {
                        section = .linkedDevices
                    }
                }
            } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    HStack(alignment: .top) {
                        WidePaneHeading(title: section == .home ? linkedAppletTarget.map { $0.name + " · Applets" } ?? section.title : section.title,
                                        sidebarHidden: columnVisibility == .detailOnly)
                        if model.busy { ProgressView().controlSize(.small) }
                        if section != .devices && (model.signedIn || model.selectedPairing != nil) { Button("Refresh", systemImage: "arrow.clockwise") { Task { await model.refreshAccount(); await model.refreshSelected() } }.buttonStyle(.borderless).font(.callout).disabled(model.paused || model.busy) }
                    }
                    if model.paused { Label("Device views are paused", systemImage: "pause.circle").foregroundStyle(B.secondary) }
                    if let problem = model.problem { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(B.error).textSelection(.enabled) }
                    switch section {
                                        case .linkedDevices: linkedDevices
                    case .home:
                        if let target = linkedAppletTarget { MacLinkedDeviceAppletsView(model: model, device: target) }
                        else { MacAppletsView(showLinkedDevices: { section = .linkedDevices }) }
                    case .camera:
                        if model.viewingLinkedDevice && model.paused {
                            EmptyView()
                        } else if let id = model.selectedPairingID {
                            MacOwnerCameraView(pairingID: id).id(id)
                        } else if model.viewingLinkedDevice {
                            Text("Pair this Mac with the device to send photos.")
                        } else {
                            MacOwnerCameraView()
                        }
                    case .more: more
                    case .agents:
                        // A0137: this Mac's own agents always show, also while a linked device is selected.
                        LocalHostSection()
                        if model.viewingLinkedDevice { Divider(); MacLinkedAgentsView(model: model) }
                    case .activity:
                        if model.viewingLinkedDevice {
                            remoteRows(key: "logs", empty: "No activity loaded", symbol: "clock")
                        } else if let host = local.host {
                            LocalActivityView(host: host, feedbackReports: feedbackReports)
                        } else {
                            Text(local.problem ?? "Local activity is unavailable.").foregroundStyle(B.error)
                        }
                    default: EmptyView()
                    }
                    Spacer(minLength: 20)
                }.padding(28).frame(maxWidth: 960, alignment: .leading).frame(maxWidth: .infinity, alignment: .topLeading).foregroundStyle(B.onSurface)
            }.background(B.surface)
            }
        }
        .tint(B.primary)
        .onChange(of: section) { _, value in AppleFixtureWorkspace.defaults.set(value.rawValue, forKey: Self.lastSectionKey) }
        .onChange(of: model.configuredAttachment) { _, selected in
            if let target = linkedAppletTarget, target.id != selected { linkedAppletTarget = nil }
        }
        .coveringBuildStampSurface(showPair || showAbilities || firstRunAgentSetup)
        .buildStampSurface()
        .task(id: phase) { if phase == .active { await local.observeInboxBadge() } }
        .task { local.configuration.viewer = model; await model.refreshAccount() }
        .bridgitDialog(isPresented: $showNetworkDevices) { MacNetworkDevicesView() }
        .bridgitDialog(isPresented: $showLocalInfo) {
            VStack(alignment: .leading) {
                HStack { Text(LocalDeviceInfoView.title).font(.headline); Spacer(); Button("Done") { showLocalInfo = false } }.padding()
                LocalDeviceInfoView()
            }.frame(minWidth: 420, minHeight: 200)
        }
        .bridgitDialog(isPresented: $showDeviceWebLink) { DeviceWebLinkView() }
        .bridgitDialog(isPresented: $showLocalActivity) {
            if let host = local.host {
                VStack(alignment: .leading) {
                    HStack {
                        Text("Activity").font(.headline)
                        Spacer()
                        Button("Done") { showLocalActivity = false }.keyboardShortcut(.cancelAction)
                    }
                    ScrollView { LocalActivityView(host: host, feedbackReports: feedbackReports) }
                }.padding().frame(minWidth: 480, minHeight: 360)
            }
        }
        .bridgitDialog(isPresented: $showHardwareAccess) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(MoreEntry.hardware.rawValue).font(.headline)
                    Spacer()
                    Button("Done") { showHardwareAccess = false }.keyboardShortcut(.cancelAction)
                }.padding()
                HardwareAccessView()
            }.frame(minWidth: 460, idealWidth: 560, minHeight: 340, idealHeight: 440)
        }
        .bridgitDialog(isPresented: $showSoftwareAccess) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(MoreEntry.software.rawValue).font(.headline)
                    Spacer()
                    Button("Done") { showSoftwareAccess = false }.keyboardShortcut(.cancelAction)
                }.padding()
                SoftwareAccessView()
            }.frame(minWidth: 460, idealWidth: 560, minHeight: 340, idealHeight: 440)
        }
        .bridgitDialog(isPresented: $showLocalFeatures) {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(MoreEntry.features.rawValue).font(.headline).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Done") { showLocalFeatures = false }.keyboardShortcut(.cancelAction)
                }
                TransferFeatureSettings()
            }.padding(24).frame(minWidth: 460, idealWidth: 540)
        }
        .bridgitDialog(isPresented: $showAbilities) {
            MacAbilityCatalogSheet(model: model) { showAbilities = false }
        }
        .bridgitDialog(isPresented: $showConnectPhone) {
            LinkCodeView { code in
                _ = try await ViewerConfigurationRouter.execute(.linkPhone(code), viewer: model, authorize: {})
            }
        }
        .bridgitDialog(isPresented: $showPair) {
            DevicePairingView(save: { try model.saveConfiguredPairing($0) }).buildStampSurface()
        }
        .confirmationDialog("Remove " + (removingAccountPhone?.displayName ?? "device") + " from your account?",
                            isPresented: Binding(get: { removingAccountPhone != nil }, set: { if !$0 { removingAccountPhone = nil } }),
                            titleVisibility: .visible, presenting: removingAccountPhone) { phone in
            Button("Remove from account", role: .destructive) {
                let expectedRevision = removalAccountRevision
                Task {
                    do {
                        _ = try await ViewerConfigurationRouter.execute(.unlinkPhone(phone.phone), viewer: model) {
                            guard model.portalAccountRevision == expectedRevision else {
                                throw DeviceAPIError(409, "account_changed", "The signed-in account changed.")
                            }
                        }
                    } catch { model.problem = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Removing this device also deletes its stored account activity. You can link it again using a new code from that device.")
        }
        .confirmationDialog("Remove this saved device link?", isPresented: Binding(get: { removingPairing != nil }, set: { if !$0 { removingPairing = nil } }), titleVisibility: .visible) {
            if let pairing = removingPairing { Button("Remove link", role: .destructive) { model.remove(pairing); removingPairing = nil } }
            Button("Cancel", role: .cancel) { removingPairing = nil }
        } message: { Text("The linked device and its applet data are kept. You will need a new pairing link to connect again.") }
        .confirmationDialog("Add this device?", isPresented: Binding(get: { model.pendingPairing != nil }, set: { if !$0 { model.pendingPairing = nil } }), titleVisibility: .visible) {
            if let pairing = model.pendingPairing {
                Button("Add \(pairing.name)") { Task { await model.confirmPairing(pairing) } }
            }
            Button("Cancel", role: .cancel) { model.pendingPairing = nil }
        } message: {
            if let p = model.pendingPairing {
                Text("A link wants to add \"\(p.name)\" through \(p.base.host ?? "a relay"). Only add it if its owner gave you this link.")
            }
        }
    }

    private var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: model.pairings, metadata: model.reportedDeviceMetadata)
    }

    private func linkedDevicesButton(showsCounts: Bool = false) -> some View {
        Button { section = .linkedDevices } label: {
            HStack {
                Label(MoreEntry.linked.rawValue, systemImage: "point.3.connected.trianglepath.dotted")
                Spacer()
                if showsCounts { LinkedDeviceMetrics(deviceCount: devices.count, viewsPaused: model.paused) }
                Image(systemName: "chevron.right").accessibilityHidden(true)
            }.contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private var more: some View {
        VStack(alignment: .leading, spacing: 18) {
            updatesButton
            linkedDevicesButton(showsCounts: true)
            FeedbackEntries(store: feedbackReports)
            MoreSearchSection(query: $moreQuery, entries: moreEntries) { entry in
                switch entry {
                case .network: networkDevicesButton
                case .activity: activityButton
                case .hardware: hardwareAccessButton
                case .software: softwareAccessButton
                case .phone: Button(LocalDeviceInfoView.title) { showLocalInfo = true }
                case .features: localFeaturesButton
                case .abilities: abilitiesButton
                case .bug: FeedbackEntryRow(kind: .bug, store: feedbackReports)
                case .feature: FeedbackEntryRow(kind: .feature, store: feedbackReports)
                case .settings, .theme, .sendReports: OwnerSettingsRow(title: entry.rawValue)
                case .connect: connectPhoneButton
                case .webLink: webLinkButton
                case .linked: linkedDevicesButton()
                case .localDevice: Button("Devices", action: showLocalDevice)
                case .about: AboutBridgitButton()
                case .updates: updatesButton
                default: EmptyView()
                }
            } menu: {
                OwnerSettingsRow()
                activityButton
                if !model.viewingLinkedDevice {
                    networkDevicesButton
                    localFeaturesButton
                    hardwareAccessButton
                    softwareAccessButton
                }
                Button(LocalDeviceInfoView.title) { showLocalInfo = true }
                webLinkButton
                if model.signedIn { connectPhoneButton }
                Text("App").font(.headline).accessibilityAddTraits(.isHeader)
                AboutBridgitButton()
                abilitiesButton
            }
        }
    }

    /// First row, like Android's and iPhone's More: Check for updates with the installed version after it.
    private var updatesButton: some View {
        MacUpdateButton {
            HStack {
                Label(MoreEntry.updates.rawValue, systemImage: "arrow.down.circle")
                Text(MacUpdates.current.label).foregroundStyle(B.onSurfaceVariant)
                Spacer()
                Image(systemName: "chevron.right").accessibilityHidden(true)
            }.contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("check-for-updates")
    }

    private var networkDevicesButton: some View {
        Button(MoreEntry.network.rawValue, systemImage: "point.3.connected.trianglepath.dotted") { showNetworkDevices = true }
            .accessibilityIdentifier("network-devices")
    }

    private var moreEntries: [MoreEntry] {
        var entries: [MoreEntry] = [.updates, .bug, .feature, .abilities, .settings, .theme, .sendReports, .linked, .activity]
        if !model.viewingLinkedDevice { entries += [.network, .features, .hardware, .software] }
        if model.signedIn { entries.append(.connect) }
        return entries + [.webLink, .phone, .about]
    }

    private var hardwareAccessButton: some View {
        Button(MoreEntry.hardware.rawValue, systemImage: "camera") { showHardwareAccess = true }
    }

    private var softwareAccessButton: some View {
        Button(MoreEntry.software.rawValue, systemImage: "gearshape") { showSoftwareAccess = true }
    }

    private var localFeaturesButton: some View {
        Button(MoreEntry.features.rawValue, systemImage: "switch.2") { showLocalFeatures = true }
    }

    private var activityButton: some View {
        Button(MoreEntry.activity.rawValue, systemImage: "clock.arrow.circlepath") { section = .activity }
    }

    private var connectPhoneButton: some View {
        Button(MoreEntry.connect.rawValue, systemImage: "link.badge.plus") { showConnectPhone = true }
    }

    private var webLinkButton: some View {
        Button(MoreEntry.webLink.rawValue, systemImage: "globe") { showDeviceWebLink = true }
    }

    private var abilitiesButton: some View {
        Button(MoreEntry.abilities.rawValue, systemImage: "lightbulb") { showAbilities = true }
    }

    private func statusRow(_ title: String, detail: String, state: String, icon: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).frame(width: 24).foregroundStyle(B.primary)
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline); Text(detail).font(.callout).foregroundStyle(B.onSurfaceVariant) }
            Spacer(); Text(state).font(.callout).foregroundStyle(B.onSurfaceVariant)
        }.padding(15).background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
    }
    private func showLocalDevice() {
        do {
            try model.selectConfiguredDevice(PortalDevice.localID)
            section = .devices
        } catch { model.problem = error.localizedDescription }
    }

    private var linkedDevices: some View {
        VStack(alignment: .leading, spacing: 18) {
            LinkedDeviceMap(devices: devices) {
                Button(action: showLocalDevice) {
                    Label {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(model.profile.device.name).font(.headline)
                            Text("This Mac").foregroundStyle(B.onSurfaceVariant)
                        }
                    } icon: {
                        Image(systemName: "desktopcomputer").foregroundStyle(B.primary)
                    }
                }.buttonStyle(.plain)
            } peer: { device in
                GroupBox {
                    VStack(alignment: .leading, spacing: 8) {
                        DeviceNameLabel(name: device.name, metadata: device.reportedMetadata)
                        if let pairing = device.pairing {
                            Text("This Mac is a view satellite · relay pairing").font(.callout).foregroundStyle(B.onSurfaceVariant)
                            HStack {
                                Button("View") { model.select(pairing); section = .devices }
                                Button("Remove", role: .destructive) { removingPairing = pairing }
                            }
                        }
                        if let phone = device.cloud {
                            Text("Account snapshot · " + (phone.status?.app_version ?? "version unknown")).font(.callout).foregroundStyle(B.onSurfaceVariant)
                            if phone.isLikelyGone {
                                Text("Not recently seen").font(.caption)
                                Button("Remove from account", role: .destructive) {
                                    removalAccountRevision = model.portalAccountRevision
                                    removingAccountPhone = phone
                                }
                            }
                        }
                        Button("View applets") {
                            if let pairing = device.pairing { model.select(pairing) }
                            else if let phone = device.cloud { model.selectAccount(phone) }
                            else { return }
                            linkedAppletTarget = device
                            section = .home
                        }.accessibilityIdentifier("linked-device-applets-" + device.id)
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if devices.isEmpty {
                Text("Nothing is linked to this Mac yet.").foregroundStyle(B.onSurfaceVariant)
            }
            Button(model.paused ? "Resume device views" : "Pause device views",
                   systemImage: model.paused ? "play.circle" : "pause.circle") { model.pause() }
            if model.signedIn { connectPhoneButton }
            HStack { Button("Pair a device", systemImage: "plus") { pairingError = nil; model.problem = nil; showPair = true }.buttonStyle(.borderedProminent)
                if model.signedIn { Text(model.email ?? "Signed in").foregroundStyle(B.onSurfaceVariant); Button("Sign out") { Task { await model.signOut() } } }
                else { Button("Sign in with Google") { model.signInWithBridgit() } }
            }

        }
    }
    private func remoteRows(key: String, empty: String, symbol: String) -> some View {
        let raw = model.snapshot[key]
        let rows = raw as? [[String: Any]] ?? (raw as? [String: Any])?[key == "logs" ? "entries" : key] as? [[String: Any]] ?? []
        return VStack(alignment: .leading, spacing: 14) {
            if let name = model.selectedDeviceName {
                Text("On \(name)").font(.title3.bold())
                if let at = model.snapshotAt { Text("Snapshot read \(at.formatted(date: .omitted, time: .standard)) · Refresh to read again").font(.caption).foregroundStyle(B.onSurfaceVariant) }
            }
            if rows.isEmpty {
                if model.selectedPairing == nil {
                    ContentUnavailableView {
                        Label("Activity unavailable", systemImage: symbol)
                    } description: {
                        Text("Pair with this device to view its activity.")
                    } actions: {
                        Button("Pair a device") { pairingError = nil; model.problem = nil; showPair = true }
                    }
                } else if model.snapshotAt == nil, model.paused {
                    ContentUnavailableView {
                        Label("Device views are paused", systemImage: symbol)
                    } description: {
                        Text("Resume device views to load activity.")
                    } actions: {
                        Button("Resume device views") { model.pause() }
                    }
                } else if model.snapshotAt == nil, model.problem != nil {
                    ContentUnavailableView("Activity unavailable", systemImage: symbol, description: Text("Check the connection or access message above."))
                } else if model.snapshotAt == nil {
                    ContentUnavailableView(empty, systemImage: symbol, description: Text("Refresh to load activity from this device."))
                } else {
                    ContentUnavailableView("No activity yet", systemImage: symbol)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                statusRow(row["name"] as? String ?? row["message"] as? String ?? row["endpoint"] as? String ?? "Entry",
                          detail: row["agent"] as? String ?? row["kind"] as? String ?? "", state: row["paused"] as? Bool == true ? "Paused" : "", icon: symbol)
            }
        }
    }
}


/// Same read-only applet projection as iPad; ordinary Home retains its mixed local catalog.
private struct MacLinkedDeviceAppletsView: View {
    @ObservedObject var model: MacModel
    var device: PortalDevice
    private var account: Phone? { model.phones.first { $0.phone == (device.cloud?.phone ?? device.pairing?.install) } }
    private var metadata: ReportedDeviceMetadata {
        guard model.configuredAttachment == device.id else { return device.reportedMetadata }
        let home = model.snapshot["home"] as? [String: Any] ?? model.snapshot
        return ReportedDeviceMetadata(device: home["device"] as? [String: Any] ?? [:])
            .fillingMissing(from: account?.reportedMetadata ?? device.reportedMetadata)
    }
    private var reported: (rows: [ReportedLinkedApplet], known: Bool, relay: Bool) {
        guard model.configuredAttachment == device.id else { return ([], false, false) }
        let raw = model.snapshot["applets"]
        if let rows = raw as? [[String: Any]] ?? (raw as? [String: Any])?["applets"] as? [[String: Any]] {
            let values = rows.compactMap(ReportedLinkedApplet.init(raw:))
            return (values, rows.isEmpty || !values.isEmpty, true)
        }
        if let rows = account?.status?.applets {
            return (rows.map { .init(id: $0.id, name: $0.name ?? $0.kind, kind: $0.kind, running: $0.running, lastAt: $0.last_at, sensors: $0.sensors ?? []) }, true, false)
        }
        return ([], false, false)
    }
    /// Why the list is missing, so the empty state names a reason and a next step (A0244).
    private var gapReason: LinkedAppletGap.Reason {
        if model.paused { return .paused }
        if model.problem != nil { return .unreachable }
        if model.configuredAttachment != device.id { return .checking }
        if device.pairing != nil { return !model.snapshot.isEmpty ? .notInReport : model.busy ? .checking : .unreachable }
        if let account { return .noAccountReport(account.status_at.map { Date(timeIntervalSince1970: $0 / 1000) }) }
        return model.busy ? .checking : .noAccountReport(nil)
    }
    private func retry() async {
        if device.pairing != nil { await model.refreshSelected() } else { await model.refreshAccount() }
    }
    var body: some View {
        let projection = reported
        VStack(alignment: .leading, spacing: 12) {
            Text(DeviceTypeDescription.label(metadata)).font(.callout).foregroundStyle(B.onSurfaceVariant)
            if projection.relay, let at = model.snapshotAt {
                Text((model.paused || model.problem != nil ? "Last known · " : "Relay snapshot · ") + Age.text(at)).font(.caption).foregroundStyle(B.onSurfaceVariant)
            } else if let at = account?.status_at {
                Text("Account snapshot · " + Age.text(ms: at)).font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
            LinkedAppletReadList(rows: projection.rows, reported: projection.known,
                                 gap: .init(device: device.name, reason: gapReason), retry: retry)
        }
        .task(id: device.id) {
            guard model.configuredAttachment == device.id else { return }
            while !Task.isCancelled {
                if device.pairing != nil { await model.refreshSelected() } else { await model.refreshAccount() }
                let allowance = model.snapshot["relay_daily_jobs"] as? Int ?? 200
                try? await Task.sleep(for: .seconds(device.pairing == nil ? 30 : allowance >= 5000 ? 15 : 600))
            }
        }
    }
}

/// A presented catalog is its own foreground surface and identifies the running build.
struct MacAbilityCatalogSheet: View {
    @ObservedObject var model: MacModel
    var close: () -> Void
    var body: some View {
        VStack {
            HStack {
                Text(MoreEntry.abilities.rawValue).font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                BuildStamp()
                Button("Done", action: close).keyboardShortcut(.cancelAction)
            }.padding()
            if model.viewingLinkedDevice {
                AbilityCatalogView(scope: .init(identity: model.selectedPairingID.map { "relay:" + $0 } ?? model.selectedAccountID.map { "cloud:" + $0 } ?? "unknown",
                                                name: model.selectedDeviceName ?? "Linked device",
                                                platform: model.selectedPairing.flatMap { model.reportedDeviceMetadata["relay:" + $0.deviceId]?.platform }
                                                    ?? model.phones.first { $0.phone == model.selectedAccountID }?.reportedMetadata.platform,
                                                local: false),
                                   readProfile: { try await model.readSelectedAbilityProfile() })
            } else { LocalAbilityCatalogView() }
        }.frame(minWidth: 460, idealWidth: 700, minHeight: 380, idealHeight: 620)
            .buildStampSurface()
    }
}
