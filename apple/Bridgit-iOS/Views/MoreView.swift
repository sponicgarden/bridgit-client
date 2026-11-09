import BridgitCore
import CoreImage
import SwiftUI

/// More, laid out like the APK's More list: rows with an icon, a title and a chevron, under section labels.
struct MoreView: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var relays: RelayStore
    @EnvironmentObject var local: LocalAppletModel
    var live: PhoneLive?
    var showsHeading = true
    var openLinkedDevices: (() -> Void)?
    @State private var moreQuery = ""
    @State private var showLink = false
    @State private var showDeviceWebLink = false
    @State private var showInstall = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if showsHeading { Text("More").font(.title).foregroundStyle(B.onSurface).padding(.bottom, 4) }
                row(.updates)
                linkedDevicesLink {
                    if textSize.isAccessibilitySize {
                        HStack(alignment: .top, spacing: 12) {
                            Image(decorative: "more_linked").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 8) {
                                Text(MoreEntry.linked.rawValue).font(.body).fixedSize(horizontal: false, vertical: true)
                                LinkedDeviceMetrics(deviceCount: devices.count, viewsPaused: model.configuredViewsPaused == true)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                        }.padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
                    } else {
                        HStack(spacing: 16) {
                            Image(decorative: "more_linked").frame(width: 24).foregroundStyle(B.onSurfaceVariant)
                            Text(MoreEntry.linked.rawValue).font(.body)
                            Spacer()
                            LinkedDeviceMetrics(deviceCount: devices.count, viewsPaused: model.configuredViewsPaused == true)
                            Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                        }.padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
                    }
                }.buttonStyle(.plain)
                FeedbackEntries()
                MoreSearchSection(query: $moreQuery, entries: searchEntries, row: row) {
                    row(.settings)
                    if let live { SectionLabel(text: live.device.name) }
                    row(.network)
                    row(.activity)
                    row(.features)
                    if live == nil && !model.isSample { row(.hardware); row(.software) }
                    row(.phone)
                    SectionLabel(text: "Account")
                    switch model.mode {
                    case .signedIn:
                        InfoRow(icon: "person.crop.circle", title: model.email ?? "Signed in", detail: "Bridgit account (Google)")
                    case .sample:
                        InfoRow(icon: "eye", title: "Showing sample data", detail: "Nothing here is from a real phone")
                    case .signedOut: EmptyView()
                    }
                    ForEach(accountEntries) { row($0) }
                    SectionLabel(text: "This device")
                    if !model.isSample { row(.webLink) }
                    SectionLabel(text: "App")
                    row(.install)
                    row(.about)
                    row(.abilities)
                }
                if let e = model.error { Text(e).font(.footnote).foregroundStyle(B.error).padding(.top, 12) }
            }
            .padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showLink) {
            LinkCodeView { code in
                let name = try await model.link(code: code)
                model.notice = .init(title: "Connected", message: "\(name) is now on your account.")
            }
        }
        .sheet(isPresented: $showDeviceWebLink) { DeviceWebLinkView() }
        .sheet(isPresented: $showInstall) { InstallAndroidView().presentationDetents([.large]) }
    }

    private var accountEntries: [MoreEntry] {
        switch model.mode {
        case .signedIn: return [.connect, .signOut]
        case .sample: return AppleFixtureWorkspace.deviceIdentity ? [.connect, .leaveSample] : [.leaveSample]
        case .signedOut: return [.signIn]
        }
    }

    private var searchEntries: [MoreEntry] {
        var entries: [MoreEntry] = [.updates, .linked, .bug, .feature, .abilities, .settings, .theme, .sendReports, .install]
        entries.append(.activity)
        if live == nil && !model.isSample { entries += [.hardware, .software] }
        entries.append(.phone)
        entries.append(.network)
        if !model.isSample { entries.append(.webLink) }
        return entries + [.features] + accountEntries + [.about]
    }

    @ViewBuilder private func row(_ entry: MoreEntry) -> some View {
        switch entry {
        case .abilities: NavRow(icon: Image(systemName: "lightbulb"), title: entry.rawValue) {
            if let live,
               let current = PortalDevice.current(in: devices, selectedID: model.selectedDeviceID, attached: model.isAttached),
               current.id == live.device.id {
                AbilityCatalogView(scope: .init(identity: model.selectedDeviceID ?? live.device.id,
                                                name: live.device.name, platform: live.device.reportedMetadata.platform,
                                                local: false, switches: Dictionary(live.features.map { ($0.id, $0.state) }, uniquingKeysWith: { _, newer in newer })),
                                   readProfile: { try await live.readAbilityProfile() })
            } else if let selected = model.selectedDeviceID, selected != PortalDevice.localID {
                AbilityCatalogView(scope: .init(identity: selected, name: "Linked device", platform: nil, local: false))
            } else { LocalAbilityCatalogView() }
        }
        case .updates:
            TestFlightUpdateButton {
                if textSize.isAccessibilitySize {
                    HStack(alignment: .top, spacing: 12) {
                        Image(decorative: "more_update").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(entry.rawValue).fixedSize(horizontal: false, vertical: true)
                            if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
                                Text("v" + version).foregroundStyle(B.onSurfaceVariant)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right").accessibilityHidden(true)
                    }.frame(minHeight: 56).contentShape(Rectangle())
                } else {
                    HStack(spacing: 8) {
                        Image(decorative: "more_update").foregroundStyle(B.onSurfaceVariant).frame(width: 24)
                        Text(entry.rawValue)
                        if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
                            Text("v" + version).foregroundStyle(B.onSurfaceVariant)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").accessibilityHidden(true)
                    }.frame(minHeight: 56).contentShape(Rectangle())
                }
            }.buttonStyle(.plain)
            MoreRowDivider()
        case .linked:
            linkedDevicesLink {
                RowBody(icon: Image(decorative: "more_linked"), title: entry.rawValue)
            }
            MoreRowDivider()
        case .bug: FeedbackEntryRow(kind: .bug)
        case .feature: FeedbackEntryRow(kind: .feature)
        case .settings:
            OwnerSettingsRow(title: entry.rawValue)
            MoreRowDivider()
        case .theme, .sendReports: OwnerSettingsRow(title: entry.rawValue)
        case .install: ActionRow(icon: "qrcode", title: entry.rawValue) { showInstall = true }
        case .network:
            NavRow(icon: Image(systemName: DevicesIcon.symbol), title: entry.rawValue) { NetworkDevicesView(live: live) }
        case .activity:
            if let live { NavRow(icon: Image(decorative: "more_activity"), title: entry.rawValue) { ActivityView(live: live) } }
            else { NavRow(icon: Image(decorative: "more_activity"), title: entry.rawValue) {
                if let host = local.host { LocalActivityView(host: host) }
                else { Text("Host activity is unavailable on this device.").navigationTitle("Activity") }
            } }
        case .hardware: NavRow(icon: Image(systemName: "camera"), title: entry.rawValue) { HardwareAccessView() }
        case .software: NavRow(icon: Image(systemName: "gearshape"), title: entry.rawValue) { SoftwareAccessView() }
        case .features:
            NavRow(icon: Image(systemName: "switch.2"), title: entry.rawValue) {
                if let live { FeaturesView(live: live) }
                else { List { TransferFeatureSettings() }.navigationTitle(entry.rawValue) }
            }
        case .phone:
            if let live { NavRow(icon: Image(systemName: "info.circle"), title: entry.rawValue) { PhoneInfoView(live: live) } }
            else { NavRow(icon: Image(systemName: "info.circle"), title: LocalDeviceInfoView.title) { LocalDeviceInfoView() } }
        case .webLink: ActionRow(icon: "globe", title: entry.rawValue) { showDeviceWebLink = true }
        case .connect: ActionRow(icon: "link.badge.plus", title: entry.rawValue) { showLink = true }
        case .signOut, .leaveSample:
            ActionRow(icon: "rectangle.portrait.and.arrow.right", title: entry.rawValue) { Task { await model.signOut() } }
        case .signIn:
            ActionRow(icon: "person.crop.circle.badge.checkmark", title: entry.rawValue) { Task { await model.signInWithBridgit() } }
        case .localDevice: ActionRow(icon: "iphone", title: entry.rawValue) { model.showThisDevice = true }
        case .about: AboutBridgitButton()
        }
    }

    private var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: model.isSample ? SampleData.pairings : relays.pairings, metadata: model.reportedDeviceMetadata)
    }
    @ViewBuilder private func linkedDevicesLink<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        if let openLinkedDevices {
            Button(action: openLinkedDevices, label: label).buttonStyle(.plain)
        } else {
            LinkedDeviceLink(route: .list, label: label).buttonStyle(.plain)
        }
    }
}

enum LinkedDeviceRoute: Hashable {
    case list
    case details(String)
    case applets(String)
}

/// iPad updates its shared route path directly. Other roots retain native
/// destination links and their existing Back behavior.
private struct LinkedDeviceLink<Label: View>: View {
    let route: LinkedDeviceRoute
    let navigate: ((LinkedDeviceRoute) -> Void)?
    let label: Label

    init(route: LinkedDeviceRoute, navigate: ((LinkedDeviceRoute) -> Void)? = nil, @ViewBuilder label: () -> Label) {
        self.route = route
        self.navigate = navigate
        self.label = label()
    }

    @ViewBuilder var body: some View {
        if let navigate {
            Button { navigate(route) } label: { label }
        } else {
            NavigationLink { LinkedDeviceRouteDestination(route: route) } label: { label }
        }
    }
}

/// The same iOS device-management controller in More and the regular-width shell.
/// Its pairing and removal actions do not pass through the Mac controller.
struct IOSLinkedDevicesView: View {
    var showsNavigationTitle = true
    var navigate: ((LinkedDeviceRoute) -> Void)?
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var relays: RelayStore
    @State private var showPair = false
    private var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: model.isSample ? SampleData.pairings : relays.pairings, metadata: model.reportedDeviceMetadata)
    }

    var body: some View {
        let isTablet = UIDevice.current.userInterfaceIdiom == .pad
        let noun = isTablet ? "tablet" : "phone"
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                LinkedDeviceMap(devices: devices) {
                    Button { model.showThisDevice = true } label: {
                        Label {
                            VStack(alignment: .leading) {
                                Text(DeviceModel.name).font(.headline)
                                Text("This " + noun).font(.caption).foregroundStyle(B.onSurfaceVariant)
                            }
                        } icon: {
                            Image(systemName: isTablet ? "ipad" : "iphone").foregroundStyle(B.primary)
                        }
                    }
                    .buttonStyle(.plain).padding(12)
                } peer: { device in
                    VStack(alignment: .leading, spacing: 8) {
                        LinkedDeviceLink(route: .details(device.id), navigate: navigate) {
                            HStack {
                                DeviceNameLabel(name: device.name, metadata: device.reportedMetadata)
                                Spacer()
                                Image(systemName: "chevron.right").accessibilityHidden(true)
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("linked-device-details-" + device.id)
                        if isTablet {
                            LinkedDeviceLink(route: .applets(device.id), navigate: navigate) {
                                Label("View applets", systemImage: "square.grid.2x2")
                                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                    .contentShape(Rectangle())
                            }
                            .accessibilityLabel("View applets for " + device.name)
                            .accessibilityIdentifier("linked-device-applets-" + device.id)
                        }
                    }.padding(.horizontal, 12)

                }

                if devices.isEmpty {
                    Text("Nothing is linked to this " + noun + " yet.").foregroundStyle(B.onSurfaceVariant)
                }
                ActionRow(icon: "qrcode.viewfinder", title: "Pair a device") { showPair = true }
            }
            .padding(16)
        }
        .background(B.surface)
        .navigationTitle(showsNavigationTitle ? "Linked devices" : "")
        .coveringBuildStampSurface(showPair)
        .sheet(isPresented: $showPair) { PairView().buildStampSurface() }
    }

}

/// Registered at each navigation root so destinations stay available after sidebar changes.
struct LinkedDeviceRouteDestination: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var relays: RelayStore
    let route: LinkedDeviceRoute
    var showsBrandHeader = false
    var navigate: ((LinkedDeviceRoute) -> Void)?
    var openLinkedDevices: (() -> Void)?
    private var devices: [PortalDevice] {
        PortalDevice.list(phones: model.phones, pairings: model.isSample ? SampleData.pairings : relays.pairings, metadata: model.reportedDeviceMetadata)
    }
    var body: some View {
        destination.safeAreaInset(edge: .top, spacing: 0) {
            if showsBrandHeader {
                WideBrandHeader()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24).padding(.vertical, 10)
                    .background(B.surface)
            }
        }
    }

    @ViewBuilder private var destination: some View {
        switch route {
        case .list:
            IOSLinkedDevicesView(navigate: navigate)
        case .details(let id):
            if let device = devices.first(where: { $0.id == id }) {
                if UIDevice.current.userInterfaceIdiom == .pad {
                    IOSLinkedDeviceDetail(device: device, live: model.live(for: device), navigate: navigate, openLinkedDevices: openLinkedDevices)
                } else {
                    IOSLinkedDeviceManagement(device: device)
                        .padding().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .navigationTitle(device.name)
                }
            }
        case .applets(let id):
            if let device = devices.first(where: { $0.id == id }) {
                LinkedDeviceAppletsView(live: model.live(for: device))
                    .navigationTitle(device.name + " · Applets")
                    .navigationBarTitleDisplayMode(.inline)
                    .onAppear { model.selectedDeviceID = device.id }
            }
        }
    }
}

/// The same iOS diagnostics controller, scoped to this stable linked-device identity.
private struct IOSLinkedDeviceDetail: View {
    @EnvironmentObject private var model: AppModel
    let device: PortalDevice
    @ObservedObject var live: PhoneLive
    var navigate: ((LinkedDeviceRoute) -> Void)?
    var openLinkedDevices: (() -> Void)?
    @State private var destination: Tab?
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                IOSLinkedDeviceManagement(device: device)
                LinkedDeviceLink(route: .applets(device.id), navigate: navigate) {
                    Label("View applets", systemImage: "square.grid.2x2")
                }
                .accessibilityLabel("View applets for " + device.name)
                .accessibilityIdentifier("linked-device-detail-applets-" + device.id)
            }.padding(16)
            Divider()
            HomeView(devices: [device], selected: Binding(get: { device.id }, set: { selected in
                if selected == PortalDevice.localID { model.showThisDevice = true }
            }), live: live, onLinked: openLinkedDevices) { destination = $0 }
        }
        .navigationTitle(device.name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.selectedDeviceID = device.id }
        .navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) {
            Group {
                switch destination {
                case .applets: LinkedDeviceAppletsView(live: live)
                case .camera: CameraView(live: live, active: true)
                case .agents: AgentsView(live: live)
                case .more: FeaturesView(live: live)
                default: EmptyView()
                }
            }.toolbar(.visible, for: .navigationBar)
        }
    }
}

/// Retain actual saved-link management even when diagnostics becomes the primary destination.
private struct IOSLinkedDeviceManagement: View {
    @EnvironmentObject private var model: AppModel
    let device: PortalDevice
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let pairing = device.pairing {
                LabeledContent("Role", value: "Hub")
                LabeledContent("Paired", value: pairing.addedAt.formatted(date: .abbreviated, time: .omitted))
                if !model.isSample {
                    Button("Remove", role: .destructive) { model.removePairing(pairing) }
                }
            } else {
                LabeledContent("Access", value: "Account view")
            }
        }
    }
}

private struct RowBody: View {
    var icon: Image
    var title: String
    var value: String?
    var chevron = true
    @Environment(\.dynamicTypeSize) private var textSize
    var body: some View {
        Group {
            if textSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        icon.font(.title3).foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
                        Text(title).font(.body).foregroundStyle(B.onSurface).fixedSize(horizontal: false, vertical: true)
                        if let value { Text(value).foregroundStyle(B.onSurfaceVariant).fixedSize(horizontal: false, vertical: true) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if chevron { Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true) }
                }
            } else {
                HStack(spacing: 16) {
                    icon.font(.title3).frame(width: 24).foregroundStyle(B.onSurfaceVariant)
                    Text(title).font(.body).foregroundStyle(B.onSurface)
                    Spacer()
                    if let value { Text(value).foregroundStyle(B.onSurfaceVariant) }
                    if chevron { Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant) }
                }
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }
}

struct NavRow<D: View>: View {
    var icon: Image
    var title: String
    var value: String?
    @ViewBuilder var destination: () -> D
    var body: some View {
        NavigationLink { destination() } label: { RowBody(icon: icon, title: title, value: value) }.buttonStyle(.plain)
        MoreRowDivider()
    }
}

struct ActionRow: View {
    var icon: String
    var title: String
    var value: String?
    var action: () -> Void
    var body: some View {
        Button(action: action) { RowBody(icon: Image(systemName: icon), title: title, value: value, chevron: false) }.buttonStyle(.plain)
        MoreRowDivider()
    }
}

struct InfoRow: View {
    var icon: String
    var title: String
    var detail: String
    var body: some View {
        HStack(spacing: 18) {
            Image(systemName: icon).font(.title3).frame(width: 28).foregroundStyle(B.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(B.onSurface)
                Text(detail).font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
            Spacer()
        }
        .padding(.vertical, 14)
        Divider().overlay(B.outlineVariant)
    }
}

struct FeaturesView: View {
    @ObservedObject var live: PhoneLive
    let groups = ["Senses", "Awareness", "Controls", "Talks with you", "Decisions", "Other"]
    var body: some View {
        List {
            Section { Text("What the phone has switched on. Changes are made on the phone.").font(.footnote).foregroundStyle(B.onSurfaceVariant) }
            ForEach(groups, id: \.self) { g in
                let rows = live.features.filter { $0.group == g }
                if !rows.isEmpty {
                    Section(g) { ForEach(rows) { FeatureLine(feature: $0) } }
                }
            }
        }
        .scrollContentBackground(.hidden).background(B.surface)
        .navigationTitle("Feature enablement")
        .refreshable { await live.refresh(force: true) }
    }
}

struct PhoneInfoView: View {
    @ObservedObject var live: PhoneLive
    var body: some View {
        List {
            Section("Device") {
                LabeledContent("Name", value: live.device.name)
                LabeledContent("Device type", value: DeviceTypeDescription.label(live.device.reportedMetadata))
                if let m = live.facts.model, !m.isEmpty { LabeledContent("Model", value: m) }
                if let os = live.facts.operatingSystem { LabeledContent(os.label, value: os.version) }
                if let v = live.facts.appVersion { LabeledContent("Bridgit", value: v) }
                if let b = live.facts.battery { LabeledContent("Battery", value: "\(b)%" + (live.facts.charging == true ? " · charging" : "")) }
            }
            if !live.facts.connections.isEmpty {
                Section("Connections") { ForEach(live.facts.connections.sorted(by: { $0.key < $1.key }), id: \.key) { LabeledContent($0.key.capitalized, value: $0.value) } }
            }
            if !live.facts.services.isEmpty {
                Section("Services") { ForEach(live.facts.services.sorted(by: { $0.key < $1.key }), id: \.key) { LabeledContent($0.key.capitalized, value: $0.value) } }
            }
            Section("How this is read") {
                Text(live.device.isLive ? "Live through the Bridgit relay, with the permission its owner granted this device."
                     : "From the status the device sends to its owner’s account.").font(.footnote)
            }
        }
        .scrollContentBackground(.hidden).background(B.surface)
        .navigationTitle("About this device")
    }
}

/// A QR code for installing Bridgit on an Android phone. It always encodes bridgit.cc/get/android, which the
/// website redirects (the download page for now, Google Play later), so the destination can change without
/// a new iOS build.
struct InstallAndroidView: View {
    @Environment(\.dismiss) private var dismiss
    static let link = URL(string: "https://bridgit.cc/get/android?src=ios")!

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    ScreenHeader(title: "Install Android App", lead: "Scan this with the Android phone's camera to install Bridgit on it. Then pair it with this iPhone or iPad.")
                    if let qr = QRCode.image(for: Self.link.absoluteString) {
                        Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                            .frame(maxWidth: 280).padding(18).background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(B.outlineVariant, lineWidth: 1))
                            .accessibilityLabel("QR code for the Bridgit Android app")
                    }
                    Text("bridgit.cc/get/android").font(.body.monospaced()).foregroundStyle(B.onSurfaceVariant).textSelection(.enabled)
                    HStack(spacing: 12) {
                        ShareLink(item: Self.link) { Label("Share link", systemImage: "square.and.arrow.up") }.buttonStyle(.bordered)
                        Button { UIPasteboard.general.url = Self.link } label: { Label("Copy link", systemImage: "doc.on.doc") }.buttonStyle(.bordered)
                    }
                    InfoPanel {
                        Text("Bridgit runs on Android 14 or newer. After it's installed, open More › Linked devices › Pair a device on that phone to let this device view it.")
                            .font(.footnote).foregroundStyle(B.onSurfaceVariant)
                    }
                }
                .padding(16).frame(maxWidth: 560).frame(maxWidth: .infinity)
            }
            .background(B.surface)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

enum QRCode {
    static func image(for text: String, scale: CGFloat = 12) -> UIImage? {
        let f = CIFilter(name: "CIQRCodeGenerator")
        f?.setValue(Data(text.utf8), forKey: "inputMessage")
        f?.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f?.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// "Version 0.1.0 · built 4 Nov 2026, 14:30". The version stays the same between TestFlight builds
/// (a new version means another Apple review), so the build time is what tells updates apart.
/// The build number is the UTC time the build was made, as yymmddHHMM (see publish-testflight.sh).
enum AppVersion {
    static var label: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? ""
        if let date = buildDate(build) {
            return "Version \(version) · built \(date.formatted(date: .abbreviated, time: .shortened))"
        }
        return build.isEmpty ? "Version \(version)" : "Version \(version) (\(build))"
    }

    static func buildDate(_ build: String) -> Date? {
        guard build.count == 10, build.allSatisfy(\.isNumber) else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMddHHmm"
        return f.date(from: build)
    }
}
