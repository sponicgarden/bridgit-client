import SwiftUI

/// Applets, as on the APK (InteractionsFragment): no title row, three tabs at the top. Portals: each applet's web
/// page on bridgit.cc/myapps. Library: what's installed on the phone and what can be added. Cards: the card library.
struct AppletsView: View {
    enum Part: String, CaseIterable, Identifiable {
        case portals = "Portals", config = "Library", cards = "Cards"
        var id: String { rawValue }
    }

    var live: PhoneLive?

    var body: some View {
        if let live { LiveAppletsTabs(live: live) } else { AppletsTabs(device: nil, phoneApplets: []) }
    }
}

/// Watches the phone so its applet list redraws the tabs when it changes.
private struct LiveAppletsTabs: View {
    @ObservedObject var live: PhoneLive
    var body: some View {
        AppletsTabs(device: live.device, phoneApplets: live.applets)
            .task(id: ObjectIdentifier(live)) { await live.refresh() } // spaced by the relay allowance; cheap when recently read
    }
}

private struct AppletsTabs: View {
    @EnvironmentObject var model: AppModel
    var device: PortalDevice?
    var phoneApplets: [AppletRow]
    /// `-appletsTab Library` (or Portals, Cards) opens that tab first; used for screenshots.
    @State private var picked: AppletsView.Part? = UserDefaults.standard.string(forKey: "appletsTab").flatMap(AppletsView.Part.init(rawValue:))
    @State private var portal: String?
    @StateObject private var web = PortalWeb()

    var portals: [AppletLists.Portal] {
        AppletLists.portals(device: device, accountApplets: model.applets, phoneApplets: phoneApplets)
    }

    /// The APK opens Cards when there are unread cards; this app can't see those, so Portals if there is one,
    /// else Library. Portals arrive with the data, so the default is worked out each time until a tab is picked.
    var section: AppletsView.Part { picked ?? (portals.isEmpty ? .config : .portals) }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Applets", selection: Binding(get: { section }, set: { picked = $0 })) {
                ForEach(AppletsView.Part.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 520)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            ZStack {
                // The portal page stays loaded while another tab shows, like the APK's hidden fragment.
                if section == .portals || !portals.isEmpty {
                    AppletPortalsView(portals: portals, current: $portal, web: web)
                        .opacity(section == .portals ? 1 : 0)
                        .allowsHitTesting(section == .portals)
                        .accessibilityHidden(section != .portals)
                }
                switch section {
                case .portals: EmptyView()
                case .config: AppletConfigView(device: device, phoneApplets: phoneApplets, portals: portals).background(B.surface)
                case .cards:
                    // `-card <id>` opens one library card directly; used for screenshots.
                    if let id = UserDefaults.standard.string(forKey: "card"), let c = AppletLists.bundledCards.first(where: { $0.id == id }) {
                        CardPreviewView(card: c)
                    } else {
                        AppletCardsView(phoneName: device?.name).background(B.surface)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(B.surface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if section == .portals, let open = portals.first(where: { $0.id == portal }) ?? portals.first {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { web.reload() } label: { Label("Reload portal", systemImage: "arrow.clockwise") }
                        Button { web.openInBrowser(fallback: open.url) } label: { Label("Open in browser", systemImage: "safari") }
                    } label: {
                        Image(systemName: "ellipsis.circle").accessibilityLabel("Portal options")
                    }
                }
            }
        }
        .navigationDestination(for: AppletSummary.self) { AppletDetailView(applet: $0) }
        .navigationDestination(for: AppletLists.Card.self) { CardPreviewView(card: $0) }
    }
}

/// Applets › Library (APK AppletConfigFragment): installed applets first (Pet monitor once it runs or its portal is
/// on, the apps agents built, and the applet portals on the account), then the ones that can be added. Read-only:
/// setting one up happens on the phone.
struct AppletConfigView: View {
    @EnvironmentObject var model: AppModel
    var device: PortalDevice?
    var phoneApplets: [AppletRow]
    var portals: [AppletLists.Portal]

    var install: String? { device?.cloud?.phone ?? device?.pairing?.install }
    var dogRow: AppletRow? { phoneApplets.first { $0.kind == "dog" } }
    var dogAccount: AppletSummary? { install.flatMap { i in model.applets.first { $0.applet == "dog" && $0.phone == i } } }
    var dogInstalled: Bool { dogRow?.running == true || portals.contains { $0.id == "dog" } }
    var agentApps: [AppletRow] { phoneApplets.filter { $0.kind == "watch" } }
    var otherApps: [AppletRow] { phoneApplets.filter { $0.kind != "dog" && $0.kind != "watch" } }
    /// The account's applet portals; the phone's own Pet monitor is reached from its card instead.
    var accountApplets: [AppletSummary] { model.applets.filter { !(dogInstalled && $0.id == dogAccount?.id) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if model.mode == .signedIn {
                    NavigationLink { LocalAppletsView() } label: {
                        Label("On this device", systemImage: "iphone")
                    }
                }
                SectionLabel(text: "Installed")
                if !dogInstalled && agentApps.isEmpty && otherApps.isEmpty && accountApplets.isEmpty {
                    Text("Nothing installed yet. Add one below, or ask your control agent to build one.")
                        .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                }
                if dogInstalled { dogCard }
                ForEach(otherApps) { PhoneAppletRowCard(applet: $0) }
                if !agentApps.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Made by your control agents").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                        Text("These run on the phone and keep counting while your control agent is offline.")
                            .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                    }
                    .padding(.top, 6)
                    ForEach(agentApps) { PhoneAppletRowCard(applet: $0) }
                }
                if !accountApplets.isEmpty {
                    Text("Applet portals on your account").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface).padding(.top, 6)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 10)], spacing: 10) {
                        ForEach(accountApplets) { a in
                            NavigationLink(value: a) { AppletCard(applet: a) }.buttonStyle(.plain)
                        }
                    }
                }

                SectionLabel(text: "Available")
                if model.mode == .signedIn {
                    NavigationLink { AppletCatalogView() } label: {
                        Label("Browse applet library", systemImage: "books.vertical")
                    }
                } else if !dogInstalled { dogCard }
                buildCard
            }
            .padding(.horizontal, 16).padding(.bottom, 16)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await model.refresh() }
    }

    var dogCardBody: some View {
        Card {
            HStack(spacing: 14) {
                Image(systemName: "pawprint.fill").font(.system(size: 26)).foregroundStyle(B.primary).frame(width: 40)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Pet monitor").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                    Text("Leave the phone where your animal hangs out. Pet monitor will take photos and analyze their activities.")
                        .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                    if dogRow?.running == true {
                        Text("Watching your dog").frostText(.supporting, weight: .semibold).foregroundStyle(B.onPrimaryContainer)
                            .padding(.horizontal, 10).padding(.vertical, 4).background(B.primaryContainer, in: Capsule()).padding(.top, 4)
                    }
                    if let t = dogRow?.lastAt { Text("Last active " + Age.text(ms: t)).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
                    if !dogInstalled {
                        Text("Set it up on the phone: Applets › Library › Pet monitor.").frostText(.supporting).foregroundStyle(B.onSurfaceVariant).padding(.top, 2)
                    }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                if dogInstalled, dogAccount != nil { Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant) }
            }
        }
    }

    @ViewBuilder var dogCard: some View {
        if dogInstalled, let a = dogAccount {
            NavigationLink(value: a) { dogCardBody }.buttonStyle(.plain)
        } else {
            dogCardBody
        }
    }

    /// applets_build_share_text on Android.
    static let shareText = "Help me build a useful Bridgit applet. Ask what I want it to do, then read https://bridgit.cc/learn/start.md and the compact https://bridgit.cc/learn/catalog-index.json. Follow only the selected capability details and closest recipe at https://bridgit.cc/recipes/. Full instructions: \(AppletLists.buildGuide.absoluteString)\n\nPrefer existing APIs, phone-resident watches or owner-approved schedules, and an attractive HTML card adapted from the recipe template. Ordinary applets need no native build. Separate phone work, remote model calls and controller integrations. Before running, confirm my authorization and the installed API, grants, switches, permissions and device prerequisites. Show source freshness, errors and unknown coverage honestly. Verify the answer and actual pause/stop state; explain retained data. Report what works, what was tested and any remaining integration. Do not treat a saved answer as proof that a controller action completed."

    var buildCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 4) {
                Text("How to build a Bridgit applet").frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                Text("Start with a useful idea, choose a recipe, and let your control agent build it with the phone’s existing features.")
                    .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                HStack(spacing: 8) {
                    Spacer()
                    ShareLink(item: Self.shareText, subject: Text("Bridgit: How to build a Bridgit applet")) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderless).frame(minHeight: 44)
                    Link(destination: AppletLists.buildGuide) { Text("Open instructions") }
                        .buttonStyle(.bordered).frame(minHeight: 44)
                }
                .padding(.top, 6)
            }
        }
    }
}

/// One applet the phone reports (/v1/portal/applets): name, its state word, sensors and last activity.
struct PhoneAppletRowCard: View {
    var applet: AppletRow
    var body: some View {
        Card {
            HStack(spacing: 12) {
                Text(applet.icon).frostText(.section, weight: .semibold).frame(width: 40)
                VStack(alignment: .leading, spacing: 3) {
                    Text(applet.name).frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                    let detail = [applet.lastAt.map { "Last active " + Age.text(ms: $0) },
                                  applet.sensors.isEmpty ? nil : "Uses " + applet.sensors.joined(separator: ", ")].compactMap { $0 }
                    if !detail.isEmpty { Text(detail.joined(separator: " · ")).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
                }
                Spacer(minLength: 0)
                StatePill(text: applet.summary ?? (applet.running ? "Running" : "Paused"), tone: applet.running ? .good : .idle)
            }
        }
    }
}

struct AppletAvatar: View {
    var applet: AppletSummary
    var size: CGFloat = 52
    var body: some View {
        Group {
            if let s = applet.avatar_data_url, let comma = s.firstIndex(of: ","), let d = Data(base64Encoded: String(s[s.index(after: comma)...])),
               let img = UIImage(data: d) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                Text(applet.applet == "dog" ? "🐾" : "◈").font(.system(size: size * 0.5))
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(B.primaryContainer)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
    }
}

struct AppletCard: View {
    var applet: AppletSummary
    var body: some View {
        Card {
            HStack(spacing: 14) {
                AppletAvatar(applet: applet)
                VStack(alignment: .leading, spacing: 4) {
                    Text(applet.title).frostText(.label, weight: .semibold).foregroundStyle(B.onSurface)
                    Text(applet.tagline ?? applet.name ?? "").frostText(.supporting).foregroundStyle(B.onSurfaceVariant).lineLimit(2)
                    HStack(spacing: 8) {
                        StatePill(text: applet.paused == true ? "Paused" : applet.running == true ? "Running" : "Not running",
                                  tone: applet.paused == true ? .idle : applet.running == true ? .good : .warn)
                        Text(applet.isOwner ? "Owner" : "Shared with you").frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(B.outline)
            }
        }
    }
}

struct AppletDetailView: View {
    @EnvironmentObject var model: AppModel
    var applet: AppletSummary
    @State private var detail: AppletDetail?
    @State private var images: [AppletImage] = []
    @State private var thumbs: [Int: UIImage] = [:]
    @State private var current: UIImage?
    @State private var summary: String?
    @State private var summarizing = false
    @State private var error: String?
    @State private var viewer: AppletImage?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 14) {
                    AppletAvatar(applet: applet, size: 64)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(detail?.friendly_name ?? applet.title).frostText(.section, weight: .semibold)
                        Text(detail?.tagline ?? applet.tagline ?? "").foregroundStyle(B.onSurfaceVariant)
                    }
                }
                AccentRule()
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("CURRENT STATUS").frostText(.supporting, weight: .semibold).foregroundStyle(B.onSurfaceVariant)
                        if let img = current {
                            Image(uiImage: img).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12)).transition(.opacity)
                        }
                        Text(detail?.current_image?.words ?? (model.isSample ? "Resting · calm" : "Location indeterminate")).frostText(.label, weight: .semibold)
                        HStack {
                            StatePill(text: detail?.connected == true ? "Device connected" : "Device not connected", tone: detail?.connected == true ? .good : .idle)
                            if let at = detail?.live_at { Text("Updated " + Age.text(ms: at)).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
                        }
                    }
                }
                if let counts = detail?.saved_photo_counts, !counts.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
                        ForEach(counts.sorted { $0.value > $1.value }, id: \.key) { k, v in
                            Card { VStack(alignment: .leading) { Text("\(v)").frostText(.section, weight: .semibold); Text(k.capitalized).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) } }
                        }
                    }
                }
                if applet.isOwner {
                    Card {
                        VStack(alignment: .leading, spacing: 10) {
                            Button { Task { await summarize() } } label: {
                                Label(summarizing ? "Looking back over the last 24 hours…" : "Summarize the last 24 hours", systemImage: "sparkles")
                            }
                            .buttonStyle(.borderedProminent).disabled(summarizing)
                            if let summary { Text(summary).frostText(.body).transition(.opacity) }
                        }
                    }
                }
                if !images.isEmpty {
                    SectionLabel(text: "Photo history · last 24 hours")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 6)], spacing: 6) {
                        ForEach(images) { im in
                            Button { viewer = im } label: {
                                ZStack {
                                    B.surfaceContainer
                                    if let t = thumbs[im.id] { Image(uiImage: t).resizable().scaledToFill().transition(.opacity) }
                                }
                                .frame(height: 104).clipShape(RoundedRectangle(cornerRadius: 10))
                            }
                            .task { await loadThumb(im) }
                        }
                    }
                }
                if let error { Text(error).foregroundStyle(B.error).frostText(.supporting) }
            }
            .padding(16)
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .navigationTitle(applet.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: $viewer) { im in
            VStack(spacing: 12) {
                if let t = thumbs[im.id] { Image(uiImage: t).resizable().scaledToFit() }
                Text(im.words).frostText(.label, weight: .semibold)
                if let at = im.captured_at { Text(Date(timeIntervalSince1970: at / 1000).formatted(date: .abbreviated, time: .shortened)).foregroundStyle(B.onSurfaceVariant) }
            }
            .padding().presentationDetents([.large])
        }
        .animation(.easeInOut(duration: 0.3), value: current)
        .animation(.easeInOut(duration: 0.3), value: summary)
    }

    func load() async {
        if model.isSample { detail = SampleData.appletDetail; return }
        do {
            detail = try await model.cloud.applet(applet)
            if let id = detail?.current_image?.id, let d = try? await model.cloud.image(applet, id: id) { current = UIImage(data: d) }
            images = (try? await model.cloud.gallery(applet).images) ?? []
            error = nil
        } catch { self.error = error.localizedDescription }
    }

    func loadThumb(_ im: AppletImage) async {
        guard thumbs[im.id] == nil, !model.isSample, let d = try? await model.cloud.image(applet, id: im.id), let img = UIImage(data: d) else { return }
        withAnimation(.easeIn(duration: 0.2)) { thumbs[im.id] = img }
    }

    func summarize() async {
        summarizing = true
        defer { summarizing = false }
        if model.isSample { summary = "A calm day: mostly resting by the window, two short play sessions after lunch."; return }
        do { summary = try await model.cloud.summarize(applet).summary }
        catch { self.error = error.localizedDescription }
    }
}
