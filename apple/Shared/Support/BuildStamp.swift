import SwiftUI

/// Each presented feedback surface owns its stamp; covered ancestors retain no visible stamp.
struct CoveredBuildStampSurfacePreference: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}
private struct BuildStampVisibilityKey: EnvironmentKey { static let defaultValue = true }
extension EnvironmentValues {
    var buildStampVisible: Bool {
        get { self[BuildStampVisibilityKey.self] }
        set { self[BuildStampVisibilityKey.self] = newValue }
    }
}
private struct BuildStampSurfaceScope: ViewModifier {
    @State private var covered = false
    func body(content: Content) -> some View {
        content.environment(\.buildStampVisible, !covered)
            .onPreferenceChange(CoveredBuildStampSurfacePreference.self) { covered = $0 }
    }
}
extension View {
    func buildStampSurface() -> some View { modifier(BuildStampSurfaceScope()) }
    func coveringBuildStampSurface(_ covered: Bool) -> some View {
        // A presenter can also carry an inactive composer. Writing a new preference would
        // replace its earlier true value; merge so either active presentation covers it.
        transformPreference(CoveredBuildStampSurfacePreference.self) { $0 = $0 || covered }
    }
}

/// Like Android's VersionStamp, keeps screenshots identifiable without adding a focus stop.
/// Two lines by design: the version, then the build number (A0156/A0158).
struct BuildStamp: View {
    /// Line one is "v" + CFBundleShortVersionString; line two is CFBundleVersion, with a
    /// yymmddHHMM stamp shortened to its eight-digit mmddHHMM form (2611071810 → 11071810).
    /// An unstamped build (project.yml default "1") shows "1" here; tools/install-macos.sh refuses those.
    static func lines(version: String, build: String) -> (version: String, build: String) {
        let shortBuild = build.count == 10 && build.hasPrefix("26") && build.allSatisfy(\.isNumber)
            ? String(build.dropFirst(2)) : build
        return ("v" + version, shortBuild)
    }
    var foreground: Color? = nil
    /// Tests pass a fixed version/build; the app reads its own Info.plist.
    var info: (version: String, build: String)? = nil
    @Environment(\.buildStampVisible) private var visible
    private var hiddenFromAccessibility: Bool {
        #if DEBUG
        !CommandLine.arguments.contains("-fixtureExposeStampBounds")
        #else
        true
        #endif
    }
    var body: some View {
        if visible, let version = info?.version ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
           let build = info?.build ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String {
            let lines = Self.lines(version: version, build: build)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: lines.version)
                Text(verbatim: lines.build)
            }
                .font(.system(size: 9).monospacedDigit())
                .foregroundStyle(foreground ?? .secondary)
                .fixedSize()
                .allowsHitTesting(false)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("fixture-build-stamp")
                .accessibilityHidden(hiddenFromAccessibility)
        }
    }
}

/// Common navigation vocabulary for native Mac and regular-width iPad windows.
/// This controls presentation only; destinations keep their platform's controllers.
enum WideSection: String, CaseIterable, Identifiable {
    case home = "Home", devices = "Devices", linkedDevices = "Linked devices"
    case camera = "Camera", agents = "Agents", activity = "Activity", more = "More"
    var id: String { rawValue }
    var title: String { self == .devices ? "This device" : rawValue }
    static func restored(_ value: String) -> Self { value == "This device" ? .devices : Self(rawValue: value) ?? .home }
    var symbol: String {
        switch self {
        case .home: "house"
        case .devices:
#if os(macOS)
            "desktopcomputer"
#else
            "ipad"
#endif
        case .linkedDevices: "point.3.connected.trianglepath.dotted"
        case .camera: "camera"
        case .agents: "person.2"
        case .activity: "clock.arrow.circlepath"
        case .more: "line.3.horizontal"
        }
    }
}

struct WideBrandHeader: View {
    var stampInfo: (version: String, build: String)? = nil
    var body: some View {
        HStack(spacing: 8) {
            BrandBar(compact: true)
            BuildStamp(foreground: B.onSurfaceVariant, info: stampInfo)
        }
    }
}

/// One shared sidebar, with platform-native destinations supplied by the root views.
struct WideSidebar: View {
    @Binding var selection: WideSection
    var inboxBadge: String?
    var localPreview = false
    var onSelect: (WideSection) -> Void = { _ in }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var columnWidth: CGFloat {
        #if os(iOS)
        dynamicTypeSize.isAccessibilitySize ? 280 : 235
        #else
        235
        #endif
    }

    @ViewBuilder private func rowLabel(_ section: WideSection) -> some View {
        let color = selection == section ? B.onPrimaryContainer : B.onSurface
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 4) {
                Image(systemName: section.symbol)
                    .symbolRenderingMode(.monochrome)
                    .accessibilityHidden(true)
                Text(section.title)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(color)
        } else {
            Label {
                Text(section.title).foregroundStyle(color)
            } icon: {
                Image(systemName: section.symbol)
                    .symbolRenderingMode(.monochrome)
                    .foregroundStyle(color)
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WideBrandHeader()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(22)
            #if os(macOS)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(WideSection.allCases) { section in
                        Button { onSelect(section); selection = section } label: {
                            HStack {
                                rowLabel(section)
                                Spacer(minLength: 8)
                                if section == .home, let inboxBadge {
                                    Text(verbatim: inboxBadge).font(.caption.bold())
                                        .foregroundStyle(selection == section ? B.onPrimaryContainer : B.onSurfaceVariant)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                            .padding(.horizontal, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .background(selection == section ? B.primaryContainer : Color.clear)
                        .accessibilityLabel(section == .home && inboxBadge != nil
                            ? "Home, \(inboxBadge!) \(inboxBadge == "1" ? "interaction" : "interactions") waiting for you"
                            : section.title)
                        .accessibilityIdentifier("wide-sidebar-" + section.rawValue)
                        .accessibilityAddTraits(selection == section ? .isSelected : [])
                    }
                }
            }
            #else
            List {
                ForEach(WideSection.allCases) { section in
                    Button { onSelect(section); selection = section } label: {
                        rowLabel(section)
                            .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(section == .home && inboxBadge != nil
                        ? "Home, \(inboxBadge!) \(inboxBadge == "1" ? "interaction" : "interactions") waiting for you"
                        : section.title)
                    .accessibilityIdentifier("wide-sidebar-" + section.rawValue)
                    .badge(section == .home ? inboxBadge.map { Text(verbatim: $0) } : nil)
                    .listRowBackground(selection == section ? B.primaryContainer : Color.clear)
                    .accessibilityAddTraits(selection == section ? .isSelected : [])
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)
            #endif
            if localPreview {
                Divider().padding(.horizontal, 16)
                Label("Local preview", systemImage: "hammer")
                    .font(.caption).foregroundStyle(B.onSurfaceVariant)
                    .help("This build stays on this Mac. Host access starts only when you choose.")
                    .padding(16)
            }
        }
        .background(B.surfaceLow)
        .navigationSplitViewColumnWidth(min: columnWidth, ideal: columnWidth, max: 280)
    }
}

struct WidePaneHeading: View {
    let title: String
    var sidebarHidden = false
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if sidebarHidden { WideBrandHeader() }
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.largeTitle.weight(.semibold))
                    .foregroundStyle(B.onSurface).accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("wide-pane-heading")
                AccentRule()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
