import Sentry
import SwiftUI

@main
struct BridgitPortalApp: App {
    @StateObject private var model = AppModel()

    init() {
        // A force-quit during recording can leave an unowned temporary clip.
        try? FileManager.default.removeItem(at:CardMovieFile.directory)
        CrashReports.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .id(model.librarySessionGeneration)
                .environmentObject(model)
                .environmentObject(model.relays)
                .environmentObject(model.links)
                .environmentObject(model.names)
                .tint(B.primary)
                .onOpenURL { model.handle(url: $0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { model.handle(url: url) }
                }
        }
    }
}

/// The APK's bottom navigation, in the same order (res/menu/bottom_nav.xml). Owner, 5 Nov 2026: Camera takes
/// the middle slot, Agents moves right, and Devices moved into More as "Network device monitor".
enum Tab: String, CaseIterable, Identifiable {
    case home, applets, camera, agents, more
    var id: String { rawValue }
    var title: String {
        switch self { case .home: "Home"; case .applets: "Applets"; case .camera: "Camera"; case .agents: "Control agents"; case .more: "More" }
    }
    var icon: String {
        switch self {
        case .home: "house.fill"; case .applets: "questionmark.bubble"; case .camera: "camera.fill"
        case .agents: "person.2.fill"; case .more: "line.3.horizontal"
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var relays: RelayStore
    @EnvironmentObject var links: LinkStore
    @EnvironmentObject var names: DeviceNameStore
    @Environment(\.scenePhase) private var phase
    @Environment(\.horizontalSizeClass) private var size
    /// `-tab devices` (launch argument) opens on that tab, for screenshots.
    @State private var tab: Tab = UserDefaults.standard.string(forKey: "tab").flatMap(Tab.init(rawValue:)) ?? .home
    /// `-device cloud:<install id>` (launch argument) opens on that phone, for screenshots.
    @StateObject private var settingsWeb = PortalWeb()
    @State private var selectedDevice: String? = UserDefaults.standard.string(forKey: "device")
    @State private var claimFromLink = false

    var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: links.usable(model.isSample ? SampleData.pairings : relays.pairings))
    }

    var current: PortalDevice? {
        devices.first { $0.id == selectedDevice } ?? devices.first { model.isAttached($0) } ?? devices.first
    }

    func live(for d: PortalDevice) -> PhoneLive { model.live(for: d) }

    var body: some View {
        Group {
            if model.mode == .signedOut && relays.pairings.isEmpty {
                SignInView()
            } else if size == .regular {
                iPadLayout
            } else {
                iPhoneLayout
            }
        }
        .background(B.surface.ignoresSafeArea())
        .sheet(isPresented: Binding(get: { model.pendingSettings != nil }, set: { if !$0 { model.pendingSettings = nil } })) {
            if let url = model.pendingSettings {
                NavigationStack {
                    PortalWebView(url: url, session: model.mode == .signedIn ? model.cloud.session : nil, email: model.email, web: settingsWeb)
                        .id(model.cloud.session ?? "signed-out")
                        .navigationTitle("Applet settings")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.pendingSettings = nil } } }
                }
            }
        }
        .task { await model.refresh() }
        .onChange(of: phase, initial: true) { _, p in
            if p == .active {
                if !model.isSample { names.startHeartbeat() }
                links.commitDue()
                Task { await links.flush() }
            } else if p == .background {
                names.stopHeartbeat()
            }
        }
        .onChange(of: model.pendingLinkOffer) { _, o in if o != nil { claimFromLink = true } }
        .sheet(isPresented: $claimFromLink) { ClaimLinkView() }
        .alert(item: $model.notice) { n in Alert(title: Text(n.title), message: Text(n.message)) }
        .confirmationDialog("Add this phone?", isPresented: Binding(get: { model.pendingPairing != nil }, set: { if !$0 { model.pendingPairing = nil } }),
                            titleVisibility: .visible, presenting: model.pendingPairing) { p in
            Button("Add \(p.name)") { model.addPairing(p); model.pendingPairing = nil }
            Button("Cancel", role: .cancel) { model.pendingPairing = nil }
        } message: { p in
            Text("A link wants to add \"\(p.name)\" through \(p.base.host ?? "a relay"). Only add it if you just opened Bridgit Portal from that phone.")
        }
    }

    @ViewBuilder func screen(_ t: Tab) -> some View {
        switch t {
        case .home: HomeView(devices: devices, selected: $selectedDevice, live: current.map(live(for:)), openTab: { tab = $0 })
        case .applets: AppletsView(live: current.map(live(for:)))
        case .camera: CameraView(live: current.map(live(for:)), active: tab == .camera)
        case .agents: AgentsView(live: current.map(live(for:)))
        case .more: MoreView(live: current.map(live(for:)))
        }
    }

    var iPhoneLayout: some View {
        TabView(selection: $tab) {
            ForEach(Tab.allCases) { t in
                NavigationStack { screen(t).brandBar() }
                    .deviceBar(current, devices: devices) { selectedDevice = $0 }
                    .tabItem { Label(t.title, systemImage: t.icon) }
                    .tag(t)
            }
        }
    }

    var iPadLayout: some View {
        NavigationSplitView {
            List {
                Section {
                    ForEach(Tab.allCases) { t in
                        Button { tab = t } label: {
                            Label(t.title, systemImage: t.icon)
                                .foregroundStyle(tab == t ? B.onPrimaryContainer : B.onSurface)
                        }
                        .listRowBackground(tab == t ? B.primaryContainer : Color.clear)
                    }
                }
                if !devices.isEmpty {
                    Section("Phones") {
                        ForEach(devices) { d in
                            Button { selectedDevice = d.id } label: { DeviceLabel(device: d, selected: d.id == current?.id) }
                        }
                    }
                }
            }
            .toolbar { ToolbarItem(placement: .principal) { BrandBar(compact: true) } }
            .navigationTitle("Bridgit Portal")
            .navigationBarTitleDisplayMode(.inline)
            .scrollContentBackground(.hidden)
            .background(B.surfaceLow)
        } detail: {
            NavigationStack { screen(tab).brandBar() }
                .deviceBar(current, devices: devices) { selectedDevice = $0 }
        }
    }
}

struct DeviceLabel: View {
    var device: PortalDevice
    var selected = false
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "iphone.gen3").foregroundStyle(selected ? B.primary : B.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).foregroundStyle(B.onSurface).fontWeight(selected ? .semibold : .regular)
                Text(device.isLive ? "Live through relay" : "Account snapshot").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
            }
        }
    }
}
