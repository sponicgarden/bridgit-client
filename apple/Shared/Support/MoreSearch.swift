import SwiftUI

/// Each result uses the same destination or action as its regular menu row.
enum MoreEntry: String, Identifiable {
    case updates = "Check for updates", linked = "Linked devices"
    case bug = "Report a bug", feature = "Request a feature", settings = "Settings"
    case theme = "Settings · Theme"
    case sendReports = "Settings · Send bug reports to the Bridgit team", install = "Install Android App"
    case network = "Network device monitor", activity = "Activity"
    case hardware = "Hardware access", software = "Software access"
    case features = "Feature enablement", phone = "About this device"
    case abilities = "What can Bridgit do?"
    case localDevice = "This device", about = "About"
    case signIn = "Sign in with Google", signOut = "Sign out", leaveSample = "Leave sample"
    case webLink = "Link this device to the web"
    case connect = "Connect a device with a code"
    var id: Self { self }

    func matches(_ query: String) -> Bool {
        let aliases: String
        switch self {
        case .abilities: aliases = "features abilities capabilities API guide"
        case .webLink: aliases = "web portal setup account code"
        case .linked: aliases = "satellites linked phones devices pairing"
        case .phone: aliases = "phone tablet iPad Mac device details"
        case .connect: aliases = "phone tablet iPad Mac pairing code"
        case .hardware: aliases = "permissions hardware camera microphone photos screenshots"
        case .software: aliases = "permissions software notifications battery background updates install"
        case .network: aliases = "camera cameras monitor"
        case .activity: aliases = "history log requests"
        case .theme: aliases = "appearance system light dark"
        case .sendReports: aliases = "settings feedback upload"
        default: aliases = ""
        }
        let locale = Locale(identifier: "en_US_POSIX")
        let text = (rawValue + " " + aliases).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
        return query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
            .split(whereSeparator: { $0.isWhitespace }).allSatisfy { text.contains($0) }
    }
}

struct LinkedDeviceMetrics: View {
    let deviceCount: Int
    let viewsPaused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Label(String(viewsPaused ? 0 : deviceCount), systemImage: "checkmark.circle.fill")
                .foregroundStyle(B.success)
            Label(String(viewsPaused ? deviceCount : 0), systemImage: "pause.fill")
                .foregroundStyle(B.onSurfaceVariant)
        }
        .font(.subheadline)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(viewsPaused ? 0 : deviceCount) enabled · \(viewsPaused ? deviceCount : 0) disabled")
    }
}

extension EnvironmentValues {
    @Entry var isMoreSearchResult = false
}

struct MoreRowDivider: View {
    @Environment(\.isMoreSearchResult) private var isSearchResult
    var body: some View {
        if !isSearchResult { Divider().overlay(B.outlineVariant) }
    }
}

struct MoreSearchSection<Rows: View, Menu: View>: View {
    @Binding var query: String
    let entries: [MoreEntry]
    @ViewBuilder var row: (MoreEntry) -> Rows
    @ViewBuilder var menu: () -> Menu
    @FocusState private var focused: Bool
    @ScaledMetric(relativeTo: .body) private var fieldWidth: CGFloat = 220

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { heading; Spacer(minLength: 0); field.frame(width: fieldWidth) }
                VStack(alignment: .leading) { heading; field }
            }
            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                menu()
            } else {
                let results = entries.filter { $0.matches(query) }
                if results.isEmpty { Text("No results").foregroundStyle(B.onSurfaceVariant).padding(.vertical, 12) }
                ForEach(results) { entry in
                    row(entry)
                        .environment(\.isMoreSearchResult, true)
                        .accessibilityIdentifier("more-search-result-" + entry.rawValue)
                        .simultaneousGesture(TapGesture().onEnded { focused = false })
                }
            }
        }
    }

    private var heading: some View {
        Text("Device and setup").font(.subheadline.weight(.semibold)).foregroundStyle(B.primary).fixedSize().accessibilityAddTraits(.isHeader)
    }

    private var field: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundStyle(B.onSurfaceVariant).accessibilityHidden(true)
            TextField("Search", text: $query, prompt: Text("Search").foregroundStyle(B.onSurfaceVariant))
                .textFieldStyle(.plain).foregroundStyle(B.onSurface)
                .accessibilityLabel("Search More and settings").focused($focused)
                .submitLabel(.search).onSubmit { focused = false }
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(B.onSurfaceVariant).frame(minWidth: 44, minHeight: 44)
                }
                    .buttonStyle(.plain).accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 12).frame(minHeight: 44)
        .overlay { Capsule().strokeBorder(B.outline).allowsHitTesting(false) }
        #if DEBUG
        .background {
            if CommandLine.arguments.contains("-fixtureExposeSearchBounds") {
                Color.clear.accessibilityElement().accessibilityLabel("Search field bounds")
                    .accessibilityIdentifier("fixture-more-search-bounds").allowsHitTesting(false)
            }
        }
        #endif
    }
}
