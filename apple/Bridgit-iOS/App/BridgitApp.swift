import BridgitCore
import Sentry
import SwiftUI

@main
struct BridgitApp: App {
    @UIApplicationDelegateAdaptor(AppleApplicationDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase
    @AppStorage("appearance", store: AppleFixtureWorkspace.defaults) private var appearance = BridgitAppearance.system
    @StateObject private var firstRun: FirstRunController
    @StateObject private var local = AppleHostBootstrap.local
    @StateObject private var model = AppModel()

    init() {
        let introduction = FirstRunController.launch()
        _firstRun = StateObject(wrappedValue: introduction)
        CrashReports.start()
    }

    var body: some Scene {
        WindowGroup {
            VStack(spacing: 0) {
                CaptureNoticeBanner()
                RootView(firstRun: firstRun)
            }
                .environmentObject(local)
                .environmentObject(model)
                .environmentObject(model.relays)
                .tint(B.primary)
                .background(WindowAppearance(appearance: appearance))
                .buildStampFallback() // version on every screen, sheet and alert that lacks its own (A0159)
                .task { local.configuration.viewer = model }
                .onOpenURL { firstRun.receive($0, platform: "ios"); model.handle(url: $0) }
                .onChange(of: phase) { _, phase in
                    Task { await local.host?.lifecycle(active: phase == .active, backgrounded: phase == .background) }
                }
        }
    }
}

/// Set the window trait so an already-presented sheet also inherits changes back to System.
private struct WindowAppearance: UIViewRepresentable {
    let appearance: BridgitAppearance

    final class WindowProbe: UIView {
        var style = UIUserInterfaceStyle.unspecified {
            didSet { apply() }
        }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }
        private func apply() {
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            window.overrideUserInterfaceStyle = style
        }
    }

    func makeUIView(context: Context) -> WindowProbe { WindowProbe() }
    func updateUIView(_ view: WindowProbe, context: Context) {
        switch appearance {
        case .system: view.style = .unspecified
        case .light: view.style = .light
        case .dark: view.style = .dark
        }
    }
}

/// The APK's bottom navigation, in the same order (res/menu/bottom_nav.xml). Owner, 5 Nov 2026: Camera takes
/// the middle slot, Agents moves right, and Devices moved into More as "Network device monitor".
enum Tab: String, CaseIterable, Identifiable {
    case home, applets, camera, agents, more
    static let allCases: [Tab] = [.applets, .home, .camera, .agents, .more]
    var id: String { rawValue }
    var title: String {
        switch self { case .home: "Devices"; case .applets: "Home"; case .camera: "Camera"; case .agents: "Agents"; case .more: "More" }
    }
    var icon: String {
        switch self {
        case .home: "iphone"; case .applets: "house.fill"; case .camera: "camera.fill"
        case .agents: "person.2.fill"; case .more: "line.3.horizontal"
        }
    }
}

struct RootView: View {
    @ObservedObject private var firstRun: FirstRunController
    @State private var firstRunAgentSetup = false
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var relays: RelayStore
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.scenePhase) private var phase
    @Environment(\.horizontalSizeClass) private var size
    /// `-tab devices` (launch argument) opens on that tab, for screenshots.
    /// A0138: otherwise it reopens on the tab the owner last used.
    @State private var tab: Tab = (UserDefaults.standard.string(forKey: "tab") ?? UserDefaults.standard.string(forKey: Self.lastTabKey))
        .flatMap(Tab.init(rawValue:)) ?? .applets
    static let lastTabKey = "navigation.lastTab"
    @State private var localDestination: String?
    @State private var showDevices = false
    @State private var showLinkedDevices = false
    @State private var wideSection: WideSection = .home
    @State private var widePath = NavigationPath()
    @State private var linkedPath: [LinkedDeviceRoute] = []
    @State private var compactWindowTopClearance: CGFloat = 0
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    /// `-device cloud:<install id>` (launch argument) opens on that phone, for screenshots.
    private var selectedDevice: String? {
        get { model.selectedDeviceID }
        nonmutating set { model.selectedDeviceID = newValue }
    }

    init(firstRun: FirstRunController? = nil) { self.firstRun = firstRun ?? .inactive() }

    var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: model.isSample ? SampleData.pairings : relays.pairings, metadata: model.reportedDeviceMetadata)
    }

    var current: PortalDevice? {
        PortalDevice.current(in: devices, selectedID: selectedDevice, attached: model.isAttached)
    }

    func live(for d: PortalDevice) -> PhoneLive { model.live(for: d) }

    var body: some View {
        Group {
            if firstRun.isPresented {
                FirstRunView(controller: firstRun, choose: completeFirstRun)
            } else if size == .regular {
                iPadLayout
            } else {
                iPhoneLayout
            }
        }
        .background(B.surface.ignoresSafeArea())
        .environment(\.manageAppletDevices, openLinkedDevices)
        .accessibilityHidden(firstRunAgentSetup)
        .allowsHitTesting(!firstRunAgentSetup)
        .coveringBuildStampSurface(firstRunAgentSetup)
        .sheet(isPresented: $firstRunAgentSetup) {
            if let host = local.host { ControlAgentSetup(host: host, packages: local.packages) }
        }
        .buildStampSurface()
        .task { await model.refresh() }
        .task {
            await local.host?.loadSavedAgents()
            firstRun.recognizeSavedAgents(local.host?.agents ?? [])
        }
        .task(id: phase) { if phase == .active { await local.observeInboxBadge() } }
        .onChange(of: tab) { _, value in UserDefaults.standard.set(value.rawValue, forKey: Self.lastTabKey) }
        .onChange(of: model.showThisDevice, initial: true) { _, show in
            if show { showLinkedDevices = false; selectedDevice = PortalDevice.localID; navigate(.home); model.showThisDevice = false }
        }
        .alert(item: $model.notice) { n in Alert(title: Text(n.title), message: Text(n.message)) }
        .confirmationDialog("Add this device?", isPresented: Binding(get: { model.pendingPairing != nil }, set: { if !$0 { model.pendingPairing = nil } }),
                            titleVisibility: .visible, presenting: model.pendingPairing) { p in
            Button("Add \(p.name)") { model.addPairing(p); model.pendingPairing = nil }
            Button("Cancel", role: .cancel) { model.pendingPairing = nil }
        } message: { p in
            Text("A link wants to add \"\(p.name)\" through \(p.base.host ?? "a relay"). Only add it if its owner gave you this link.")
        }
    }

    private func completeFirstRun(_ action: FirstRunProgress.Action) {
        if action == .addControlAgent && local.host == nil {
            model.notice = .init(title: "Agent setup unavailable", message: local.problem ?? "The local host could not be opened.")
            return
        }
        guard firstRun.finish(action) else { return }
        selectedDevice = PortalDevice.localID
        widePath = NavigationPath()
        navigate(action == .diagnostic ? .home : .agents)
        firstRunAgentSetup = action == .addControlAgent
    }

    @ViewBuilder func screen(_ t: Tab) -> some View {
        switch t {
        case .home:
            if current == nil {
                LocalDiagnosticsView { id in
                    switch id {
                    case "phones": showDevices = true
                    case "agent", "addAgent": navigate(.agents)
                    case "applets", "ask": navigate(.applets)
                    case "camera": navigate(.camera)
                    case "mic", "voicerec", "speaker": localDestination = "hardware"
                    case "settings": localDestination = "features"
                    case "connected": openLinkedDevices()
                    default: navigate(.more)
                    }
                }
                .navigationDestination(isPresented: Binding(get: { localDestination != nil }, set: { if !$0 { localDestination = nil } })) {
                    Group {
                        switch localDestination {
                        case "hardware": HardwareAccessView()
                        case "features": List { TransferFeatureSettings() }.navigationTitle("Feature enablement")
                        case "linked": IOSLinkedDevicesView()
                        default: EmptyView()
                        }
                    }.toolbar(.visible, for: .navigationBar)
                }
                .sheet(isPresented: $showDevices) {
                    DiagnosticsDeviceChooser(devices: devices, current: selectedDevice, select: { selectedDevice = $0 }, onLinked: openLinkedDevices)
                        .presentationDetents([.medium, .large])
                }
            }
            else { HomeView(devices: devices, selected: $model.selectedDeviceID, live: current.map(live(for:)), onLinked: openLinkedDevices, openTab: navigate) }
        case .applets: AppletsView(live: current.map(live(for:)))
        case .camera:
            if current == nil {
                if tab == .camera { LocalCameraTab() } else { Color.black }
            }
            else { CameraView(live: current.map(live(for:)), active: tab == .camera) }
        case .agents:
            if current == nil { ScrollView { LocalHostSection().padding(20).frame(maxWidth: 760).frame(maxWidth: .infinity) }.background(B.surface) }
            else { AgentsView(live: current.map(live(for:))) }
        case .more:
            MoreView(live: current.map(live(for:)),
                     showsHeading: !(size != .regular && current == nil),
                     openLinkedDevices: UIDevice.current.userInterfaceIdiom == .pad ? openLinkedDevices : nil)
        }
    }

    private func openLinkedDevices() {
        showDevices = false
        if size == .regular || UIDevice.current.userInterfaceIdiom == .pad {
            linkedPath = [.list]
            if size == .regular { wideSection = .linkedDevices }
            else { tab = .more }
        } else {
            showLinkedDevices = true
        }
    }

    private func inboxBadge(_ tab: Tab) -> Text? {
        guard tab == .applets else { return nil }
        return local.inboxBadgeLabel.map { Text(verbatim: $0) }
    }

    private func tabAccessibilityLabel(_ tab: Tab) -> String {
        guard tab == .applets, local.inboxBadgeCount > 0 else { return tab.title }
        let count = local.inboxBadgeCount
        return "\(tab.title), \(count) \(count == 1 ? "interaction" : "interactions") waiting for you"
    }

    @ViewBuilder private func navigationRoot(_ destination: Tab) -> some View {
        if destination == .applets || destination == .more || destination == .camera {
            screen(destination)
                .safeAreaInset(edge: .top, spacing: 0) {
                    if size == .regular || (destination == .applets && current == nil) {
                        HStack {
                            BrandBar(compact: true)
                            Spacer()
                            if size != .regular { BuildStamp() }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(B.surface)
                    } else if destination == .more && current == nil {
                        HStack(alignment: .top, spacing: 12) {
                            Text("More").font(.title).foregroundStyle(B.onSurface)
                            Spacer(minLength: 12)
                            BuildStamp()
                        }
                            .padding(.horizontal, 16).padding(.vertical, 6)
                            .background(B.surface)
                    }
                }
                .toolbar(.hidden, for: .navigationBar)
        } else if destination == .home {
            screen(destination)
                .safeAreaInset(edge: .top, spacing: 0) {
                    HStack {
                        BrandBar(compact: true)
                        Spacer()
                        BuildStamp()
                    }.padding(.horizontal, 16).padding(.vertical, 6).background(B.surface)
                }
                .toolbar(.hidden, for: .navigationBar)
        } else {
            screen(destination).brandBar()
        }
    }

    var iPhoneLayout: some View {
        TabView(selection: $tab) {
            ForEach(Tab.allCases) { t in
                compactStack(t)
                    .deviceBar(current.map(live(for:)), devices: devices, cameraBackground: t == .camera, hidden: t == .home, rootStampInContent: (t == .applets || t == .more) && current == nil) { selectedDevice = $0 }
                    .tabItem { Label(t.title, systemImage: t.icon).accessibilityLabel(tabAccessibilityLabel(t)) }
                    .badge(inboxBadge(t))
                    .tag(t)
            }
        }
        .padding(.top, compactWindowTopClearance)
        .background {
            if #available(iOS 26, *), UIDevice.current.userInterfaceIdiom == .pad {
                CompactWindowControlsProbe { compactWindowTopClearance = $0 }
            }
        }
    }

    @ViewBuilder private func compactStack(_ destination: Tab) -> some View {
        if UIDevice.current.userInterfaceIdiom == .pad && destination == .more {
            NavigationStack(path: $linkedPath) {
                compactRoot(destination)
                    .navigationDestination(for: LinkedDeviceRoute.self) {
                        LinkedDeviceRouteDestination(route: $0, navigate: pushLinkedDeviceRoute, openLinkedDevices: openLinkedDevices)
                            .toolbar(.visible, for: .navigationBar)
                    }
            }
        } else {
            NavigationStack { compactRoot(destination) }
        }
    }

    private func compactRoot(_ destination: Tab) -> some View {
        navigationRoot(destination)
            .navigationDestination(isPresented: Binding(get: { showLinkedDevices && tab == destination }, set: {
                if !$0 && tab == destination { showLinkedDevices = false }
            })) {
                IOSLinkedDevicesView().toolbar(.visible, for: .navigationBar)
            }
    }

    @ViewBuilder private var iPadScreen: some View {
        switch wideSection {
        case .home: screen(.applets)
        case .devices: screen(.home)
        case .linkedDevices: IOSLinkedDevicesView(showsNavigationTitle: false, navigate: pushLinkedDeviceRoute)
        case .camera: screen(.camera)
        case .agents: screen(.agents)
        case .activity:
            if let live = current.map(live(for:)) { ActivityView(live: live, showsNavigationTitle: false) }
            else if let host = local.host { ScrollView { LocalActivityView(host: host, showsNavigationTitle: false).padding(20) } }
            else { Text("Host activity is unavailable on this device.") }
        case .more: MoreView(live: current.map(live(for:)), showsHeading: false, openLinkedDevices: openLinkedDevices)
        }
    }

    private func navigate(_ destination: Tab) {
        tab = destination
        switch destination {
        case .applets: wideSection = .home
        case .home: wideSection = .devices
        case .camera: wideSection = .camera
        case .agents: wideSection = .agents
        case .more: wideSection = .more
        }
    }

    var iPadLayout: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            WideSidebar(selection: $wideSection, inboxBadge: local.inboxBadgeLabel) { selected in
                widePath = NavigationPath()
                linkedPath = selected == .linkedDevices ? [.list] : []
                if selected == .devices { selectedDevice = PortalDevice.localID }
            }
                .navigationTitle("")
                .navigationBarTitleDisplayMode(.inline)
        } detail: {
            if wideSection == .linkedDevices {
                NavigationStack(path: wideLinkedPath) {
                    wideRoot
                        .navigationDestination(for: LinkedDeviceRoute.self) {
                            LinkedDeviceRouteDestination(route: $0, showsBrandHeader: columnVisibility == .detailOnly,
                                                         navigate: pushLinkedDeviceRoute,
                                                         openLinkedDevices: openLinkedDevices)
                        }
                }
            } else {
                NavigationStack(path: $widePath) { wideRoot }
            }
        }
        .onAppear {
            switch tab {
            case .applets: wideSection = .home
            case .home: showWideDeviceDiagnostics()
            case .camera: wideSection = .camera
            case .agents: wideSection = .agents
            case .more: wideSection = linkedPath.isEmpty ? .more : .linkedDevices
            }
        }
        .onChange(of: current?.id) { _, id in
            if wideSection == .devices, id != nil { showWideDeviceDiagnostics() }
        }
        .onChange(of: wideSection) { _, section in
            widePath = NavigationPath()
            switch section {
            case .home: tab = .applets
            case .devices: tab = .home
            case .camera: tab = .camera
            case .agents: tab = .agents
            case .more, .linkedDevices, .activity: tab = .more
            }
        }
    }

    private func pushLinkedDeviceRoute(_ route: LinkedDeviceRoute) {
        if linkedPath.isEmpty { linkedPath = [.list] }
        linkedPath.append(route)
    }

    private func showWideDeviceDiagnostics() {
        if let device = current {
            linkedPath = [.list, .details(device.id)]
            wideSection = .linkedDevices
        } else {
            wideSection = .devices
        }
    }

    // Compact More pushes the list as its first route. The wide sidebar makes
    // that same list the root, so only the detail routes go into its stack.
    private var wideLinkedPath: Binding<[LinkedDeviceRoute]> {
        Binding(get: { Array(linkedPath.dropFirst()) }, set: { linkedPath = [.list] + $0 })
    }

    private var wideRoot: some View {
        iPadScreen
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) {
                WidePaneHeading(title: wideSection.title, sidebarHidden: columnVisibility == .detailOnly)
                    .padding(.horizontal, 24).padding(.vertical, 16).background(B.surface)
            }
            .deviceBar(current.map(live(for:)), devices: devices, cameraBackground: wideSection == .camera, hidden: wideSection == .devices, showsBuildStamp: false) { selectedDevice = $0 }
    }

}

/// Custom compact headers sit outside UINavigationBar, so reserve the extra
/// system clearance for window controls that the default safe area omits.
@available(iOS 26, *)
private struct CompactWindowControlsProbe: UIViewRepresentable {
    var update: (CGFloat) -> Void

    func makeUIView(context: Context) -> ProbeView { ProbeView() }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.update = update
        view.setNeedsLayout()
    }

    final class ProbeView: UIView {
        var update: ((CGFloat) -> Void)?
        private var lastClearance: CGFloat?

        override func layoutSubviews() {
            super.layoutSubviews()
            guard let window else { return }
            let controls = window.edgeInsets(for: .safeArea(cornerAdaptation: .vertical)).top
            let clearance = max(0, controls - window.safeAreaInsets.top)
            guard clearance != lastClearance else { return }
            lastClearance = clearance
            DispatchQueue.main.async { [weak self] in self?.update?(clearance) }
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            setNeedsLayout()
        }
    }
}

struct DeviceLabel: View {
    var device: PortalDevice
    var selected = false
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: DeviceTypeDescription.symbol(device.reportedMetadata))
                .foregroundStyle(selected ? B.primary : B.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                DeviceNameLabel(name: device.name, metadata: device.reportedMetadata).foregroundStyle(B.onSurface).fontWeight(selected ? .semibold : .regular)
                Text(device.isLive ? "Live through relay" : "Account snapshot").font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
        }
    }
}
