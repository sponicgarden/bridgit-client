import SwiftUI

/// Home, as on the phone: a header with the phone's name and freshness, then the APK's own
/// diagnostics scene (features, agents, live activity).
struct HomeView: View {
    @EnvironmentObject var model: AppModel
    var devices: [PortalDevice]
    @Binding var selected: String?
    var live: PhoneLive?
    var openTab: (Tab) -> Void
    @State private var showPair = false

    var body: some View {
        Group {
            if let live {
                HomeContent(live: live, devices: devices, selected: $selected, openTab: openTab)
            } else {
                NoPhoneView(showPair: $showPair)
            }
        }
        .sheet(isPresented: $showPair) { PairView() }
    }
}

private struct HomeContent: View {
    @ObservedObject var live: PhoneLive
    var devices: [PortalDevice]
    @Binding var selected: String?
    var openTab: (Tab) -> Void
    @State private var nodeSheet: String?
    @State private var showPhones = false
    @State private var showDevices = false

    var body: some View {
        VStack(spacing: 0) {
            if let e = live.liveError {
                Label(e, systemImage: "exclamationmark.triangle").frostText(.supporting)
                    .foregroundStyle(B.onSurfaceVariant)
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(B.secondaryContainer.opacity(0.6))
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            if !live.device.isLive, let phone = live.device.cloud {
                LiveAccessBar(phone: phone)
            }
            // Ends at the top of the tab bar: the log is the scene's last row and the floating bar would cover it.
            HomeSceneView(live: live, otherPhone: !isOwnPhone) { id in
                switch id {
                case "applets", "ask": openTab(.applets)
                case "agent", "addAgent": openTab(.agents)
                case "camera": showDevices = true
                case "phones": showPhones = true
                default: nodeSheet = id
                }
            }
            // A new scene per phone: the page keeps its run, log history and state, so a switch must start afresh.
            .id(live.device.id)
        }
        .background(B.sceneBackground.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.25), value: live.liveError)
        .task(id: ObjectIdentifier(live)) {
            // Refresh while Home is open, spaced to what the relay allows for this pairing.
            while !Task.isCancelled {
                await live.refresh()
                try? await Task.sleep(nanoseconds: UInt64(max(live.minInterval, 10) * 1_000_000_000))
            }
        }
        .refreshable { await live.refresh(force: true) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { Task { await live.refresh(force: true) } } label: {
                    if live.busy { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                }
                .accessibilityLabel("Refresh from the phone")
            }
        }
        .navigationDestination(isPresented: $showDevices) { NetworkDevicesView(live: live) }
        .sheet(isPresented: $showPhones) {
            PhonesSheet(devices: devices, current: selected ?? live.device.id) { id in selected = id }
                .presentationDetents([.medium, .large])
        }
        .sheet(item: Binding(get: { nodeSheet.map { NodeID(id: $0) } }, set: { nodeSheet = $0?.id })) { n in
            FeatureSheet(live: live, nodeId: n.id).presentationDetents([.medium])
        }
    }

    struct NodeID: Identifiable { var id: String }

    /// The phone shown is this iPhone's own attachment (the plain hexagon) or another one (the HUB star).
    var isOwnPhone: Bool { model.isAttached(live.device) || devices.count < 2 }
    @EnvironmentObject private var model: AppModel
}

struct HomeHeader: View {
    @ObservedObject var live: PhoneLive
    var devices: [PortalDevice]
    @Binding var selected: String?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                // The phone's name is in the scene's centre hexagon; with several phones this switches between them.
                if devices.count > 1 {
                    Menu {
                        ForEach(devices) { d in
                            Button { selected = d.id } label: { Label(d.name, systemImage: d.isLive ? "dot.radiowaves.left.and.right" : "cloud") }
                        }
                    } label: {
                        Label("Switch phone", systemImage: "chevron.down").frostText(.supporting, weight: .semibold)
                    }
                }
                Text(subtitle).frostText(.supporting).foregroundStyle(B.onSurfaceVariant).lineLimit(3)
                AccentRule().padding(.top, 2)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                TimelineView(.periodic(from: .now, by: 30)) { _ in freshness }
                if let b = live.facts.battery {
                    Label("\(b)%", systemImage: live.facts.charging == true ? "battery.100.bolt" : batteryIcon(b))
                        .frostText(.supporting, weight: .medium).foregroundStyle(B.onSurfaceVariant)
                }
            }
        }
    }

    var subtitle: String {
        [live.facts.model, live.facts.android.map { "Android " + $0 }, live.facts.appVersion.map { "Bridgit " + $0 }]
            .compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " · ")
    }

    @ViewBuilder var freshness: some View {
        switch live.source {
        case .live(let at): StatePill(text: "Live · " + Age.text(at), tone: .good)
        case .stale(let at): StatePill(text: "Not reachable · last live " + Age.text(at), tone: .warn)
        case .snapshot(let at): StatePill(text: "Snapshot · " + (at.map(Age.text) ?? "none yet"), tone: at.map { Date().timeIntervalSince($0) < 900 } == true ? .warn : .idle)
        case .sample: StatePill(text: "Sample data", tone: .idle)
        case .none: StatePill(text: "No data yet", tone: .idle)
        }
    }

    func batteryIcon(_ b: Int) -> String {
        b > 85 ? "battery.100" : b > 60 ? "battery.75" : b > 35 ? "battery.50" : b > 10 ? "battery.25" : "battery.0"
    }
}

/// Tapping a node in the scene: what the phone says about that feature.
struct FeatureSheet: View {
    @ObservedObject var live: PhoneLive
    var nodeId: String
    var body: some View {
        NavigationStack {
            List {
                let exact = live.features.filter { $0.id == nodeId || $0.id.hasPrefix(nodeId + "_") }
                let rows = exact.isEmpty ? live.features.filter { PhoneLive.group(for: $0.id) == PhoneLive.group(for: nodeId) } : exact
                if rows.isEmpty {
                    Text("The phone hasn't reported this feature to the portal.").foregroundStyle(B.onSurfaceVariant)
                }
                ForEach(rows) { f in FeatureLine(feature: f) }
                Section { Text("Changes to features are made on the phone. The portal only shows them.").frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
            }
            .navigationTitle(PhoneLive.title(for: nodeId))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct FeatureLine: View {
    var feature: FeatureRow
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(feature.name).foregroundStyle(B.onSurface)
                if let s = feature.summary { Text(s).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
            }
            Spacer()
            switch feature.state {
            case .on: StatePill(text: "On", tone: .good)
            case .blocked: StatePill(text: "Needs access", tone: .warn)
            case .off: StatePill(text: "Off", tone: .idle)
            }
        }
    }
}

struct NoPhoneView: View {
    @EnvironmentObject var model: AppModel
    @Binding var showPair: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ScreenHeader(title: "No phone yet", lead: "Bridgit shows a Bridgit phone's screens here. The phone's owner grants access on the phone.")
                InfoPanel {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Two ways to get access").frostText(.label, weight: .semibold)
                        Label("On the phone, open More › Linked devices › Pair a device and scan its QR code. Live view through the relay.", systemImage: "qrcode.viewfinder")
                        Label("Or sign in with the Google account the phone is connected to (More › Link web view on the phone).", systemImage: "person.crop.circle.badge.checkmark")
                    }.frostText(.supporting)
                }
                Button { showPair = true } label: { Label("Pair with a phone", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                if model.mode == .signedIn {
                    Button("Check my account again") { Task { await model.refresh() } }.frame(maxWidth: .infinity)
                }
            }
            .padding(16)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
    }
}

/// On a phone seen only through the account snapshot: ask it for live access (the owner taps Allow on the phone).
/// A phone that looks gone (old install, silent for days) gets one short line and Remove instead.
struct LiveAccessBar: View {
    @EnvironmentObject var model: AppModel
    var phone: Phone
    @State private var confirmRemove = false

    var body: some View {
        let status = model.accessStatus[phone.phone] ?? ""
        HStack(spacing: 10) {
            Image(systemName: phone.goneLine != nil ? "exclamationmark.triangle" : "dot.radiowaves.left.and.right")
                .foregroundStyle(phone.goneLine != nil ? B.onSurfaceVariant : B.primary)
            Text(status.isEmpty ? (phone.goneLine ?? "Ask the phone for live view.") : status)
                .frostText(.supporting).foregroundStyle(B.onSurfaceVariant).lineLimit(2)
            Spacer()
            if phone.isLikelyGone {
                Button("Remove", role: .destructive) { confirmRemove = true }
                    .buttonStyle(.bordered).controlSize(.small)
            } else if model.requestingAccess.contains(phone.phone) {
                Button("Stop") { model.stopLiveAccess(for: phone) }.controlSize(.small)
            } else {
                Button("Ask") { model.startLiveAccess(for: phone) }.buttonStyle(.borderedProminent).controlSize(.small)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(B.primaryContainer.opacity(0.5))
        .confirmationDialog("Remove \(phone.displayName) from your account?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { await model.removePhone(phone) } }
        } message: {
            Text("Its activity log here is deleted. If the phone is still in use, link it again from Bridgit on it.")
        }
    }
}

/// Opened from the scene's centre hexagon: every phone this iPhone/iPad can view. Tapping one opens its
/// details, where Connect reads more from it and Attach keeps a live connection to it.
struct PhonesSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var devices: [PortalDevice]
    var current: String
    var select: (String) -> Void
    @State private var showPair = false
    @State private var path: [String] = []
    /// A phone attached from its details: Home switches to it when this sheet closes, even if another
    /// phone was picked by hand earlier (a hand-picked phone otherwise wins over the attachment).
    @State private var attachedHere: String?

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(devices) { d in
                        PhoneCard(live: model.live(for: d), selected: d.id == current, attached: model.isAttached(d),
                                  onSelect: { select(d.id) }, onDetails: { path.append(d.id) })
                    }
                }
                .padding(16)
            }
            // Outside the scroll view so it stays fully visible at the half-height detent.
            .safeAreaInset(edge: .bottom) {
                Button { showPair = true } label: { Label("Add a phone", systemImage: "plus").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).controlSize(.large)
                    .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 12)
                    .background(B.surface)
            }
            .background(B.surface)
            .navigationTitle("Phones")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { id in
                if let d = devices.first(where: { $0.id == id }) {
                    PhoneDetailView(device: d, live: model.live(for: d)) { attachedHere = $0; select($0) }
                }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.fontWeight(.semibold) }
            }
            .sheet(isPresented: $showPair) { PairView() }
        }
        .onDisappear { if let id = attachedHere { select(id) } }
    }
}

/// One phone: its name, the details line (model · Android · Bridgit), freshness and battery.
struct PhoneCard: View {
    @ObservedObject var live: PhoneLive
    var selected: Bool
    var attached = false
    /// Tapping the card shows this phone on Home right away; the chevron opens its details.
    var onSelect: () -> Void = {}
    var onDetails: () -> Void = {}
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: selected ? "checkmark.circle.fill" : "iphone.gen3")
                .frostText(.section, weight: .semibold).foregroundStyle(selected ? B.primary : B.onSurfaceVariant).frame(width: 30)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(live.device.name).frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                    if attached {
                        Label("Attached", systemImage: "link").frostText(.supporting, weight: .semibold).foregroundStyle(B.success)
                    }
                }
                HomeHeader(live: live, devices: [], selected: .constant(nil))
                if !live.device.isLive, let gone = live.device.cloud?.goneLine {
                    Label(gone, systemImage: "exclamationmark.triangle").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                }
            }
            Button(action: onDetails) {
                Image(systemName: "chevron.right").frostText(.supporting, weight: .semibold).foregroundStyle(B.outline)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain).accessibilityLabel("Details")
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(selected ? B.primaryContainer.opacity(0.45) : B.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(selected ? B.primary : B.outlineVariant.opacity(0.6), lineWidth: selected ? 1.5 : 1))
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .task { await live.refresh() } // spaced by the relay allowance; cheap when recently read
    }
}

/// One phone's details. Connect asks the phone for fresh data (Home snapshot and network devices) and
/// shows where it is and what it runs; Attach makes it the phone the portal stays connected to.
struct PhoneDetailView: View {
    @EnvironmentObject var model: AppModel
    var device: PortalDevice
    @ObservedObject var live: PhoneLive
    var attached: (String) -> Void
    @State private var stage: Stage = .idle
    enum Stage { case idle, connecting, connected }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(live.device.name).frostText(.section, weight: .bold).foregroundStyle(B.onSurface)
                    HomeHeader(live: live, devices: [], selected: .constant(nil))
                }
                InfoPanel { VStack(alignment: .leading, spacing: 4) {
                    Label(device.isLive ? "Live through the relay" : "Account snapshot only",
                          systemImage: device.isLive ? "dot.radiowaves.left.and.right" : "cloud")
                        .frostText(.supporting, weight: .semibold)
                    Text(device.isLive ? "This iPhone or iPad can read the phone directly." : "Up to a few minutes old. Attach to ask the phone for a live connection.")
                        .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                } }
                switch stage {
                case .idle:
                    Button { Task { await connect() } } label: {
                        Label("Connect", systemImage: "antenna.radiowaves.left.and.right").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                case .connecting:
                    HStack(spacing: 10) { ProgressView(); Text("Reading \(live.device.name)…").foregroundStyle(B.onSurfaceVariant) }
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                case .connected:
                    details
                }
                if stage == .connected || model.isAttached(device) { attachSection }
            }
            .padding(16)
        }
        .background(B.surface)
        .navigationTitle(live.device.name)
        .navigationBarTitleDisplayMode(.inline)
    }

    func connect() async {
        withAnimation { stage = .connecting }
        // One after the other: each skips while the other is busy.
        await live.refresh(force: true)
        await live.refreshDevices()
        withAnimation { stage = .connected }
    }

    var networkDevices: Int { live.categories.reduce(0) { $0 + $1.total } }

    @ViewBuilder var details: some View {
        if let e = live.liveError {
            Label(e, systemImage: "exclamationmark.triangle").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
        }
        VStack(alignment: .leading, spacing: 10) {
            Text("Where").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
            DetailRow(title: "Network address", value: live.networkAddress ?? "Not shared")
            ForEach(live.facts.connections.sorted(by: { $0.key < $1.key }), id: \.key) { k, v in
                HStack {
                    Text(Self.connectionName(k)).foregroundStyle(B.onSurface)
                    Spacer()
                    StatePill(text: v == "connected" ? "Connected" : v.capitalized, tone: v == "connected" ? .good : .idle)
                }
                .frostText(.supporting)
            }
        }
        .detailCard()
        VStack(alignment: .leading, spacing: 10) {
            Text("Services").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                StatTile(value: "\(live.features.filter { $0.state == .on }.count) of \(live.features.count)", label: "Features on")
                StatTile(value: "\(live.agents.filter { !$0.portal }.count)", label: "Control agents")
                StatTile(value: "\(live.applets.filter(\.running).count) of \(live.applets.count)", label: "Applets running")
                StatTile(value: "\(networkDevices)", label: "Network devices")
            }
            ForEach(live.facts.services.sorted(by: { $0.key < $1.key }), id: \.key) { k, v in
                DetailRow(title: k.capitalized, value: v.capitalized)
            }
        }
        .detailCard()
    }

    @ViewBuilder var attachSection: some View {
        if model.isAttached(device) {
            VStack(alignment: .leading, spacing: 10) {
                Label("Attached", systemImage: "link").frostText(.label, weight: .semibold).foregroundStyle(B.success)
                Text(device.isLive ? "The portal opens on this phone and stays connected to it." : "The portal opens on this phone. It switches to live as soon as someone taps Allow on the phone.")
                    .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                if let phone = device.cloud, let s = model.accessStatus[phone.phone], !s.isEmpty {
                    HStack(spacing: 8) {
                        if model.requestingAccess.contains(phone.phone) { ProgressView().controlSize(.small) }
                        Text(s).frostText(.supporting).foregroundStyle(B.onSurface)
                    }
                }
                Button("Detach", role: .destructive) {
                    if let phone = device.cloud { model.stopLiveAccess(for: phone) }
                    model.detach()
                }
                .buttonStyle(.bordered)
            }
            .detailCard()
        } else {
            VStack(spacing: 6) {
                Button {
                    model.attach(device)
                    attached(device.id) // selects it now, so Home and the checkmark follow at once
                } label: {
                    Label("Attach to Device", systemImage: "link").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                Text(device.isLive ? "Keeps a live connection to this phone and opens on it every time."
                     : "Asks the phone for live access. Someone taps Allow on the phone, then the portal stays connected.")
                    .frostText(.supporting).foregroundStyle(B.onSurfaceVariant).multilineTextAlignment(.center)
            }
        }
    }

    static func connectionName(_ k: String) -> String {
        ["wifi": "Wi-Fi", "tailscale": "Tailscale", "relay": "Relay", "adb": "ADB"][k] ?? k.capitalized
    }
}

private struct DetailRow: View {
    var title: String
    var value: String
    var body: some View {
        HStack {
            Text(title).foregroundStyle(B.onSurface)
            Spacer()
            Text(value).foregroundStyle(B.onSurfaceVariant).multilineTextAlignment(.trailing)
        }
        .frostText(.supporting)
    }
}

private struct StatTile: View {
    var value: String
    var label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).frostText(.section, weight: .bold).foregroundStyle(B.onSurface)
            Text(label).frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(B.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private extension View {
    func detailCard() -> some View {
        padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceLow)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(B.outlineVariant.opacity(0.6), lineWidth: 1))
    }
}
