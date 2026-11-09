import SwiftUI

/// Keeps the owner’s card selection when switching between local and linked devices.
struct AppletsView: View {
    typealias Part = AppletsSection

    var live: PhoneLive?
    @State private var cardMode = AppletCardsView.Mode.inbox
    @State private var inboxFilter = LocalInteractionInbox.Filter.all
    @State private var picked: AppletsView.Part? = UserDefaults.standard.string(forKey: "appletsTab").flatMap(AppletsView.Part.init(rawValue:))

    var body: some View {
        if let live { LiveAppletsTabs(live: live, picked: $picked, cardMode: $cardMode, inboxFilter: $inboxFilter) } else { AppletsTabs(device: nil, phoneApplets: [], picked: $picked, cardMode: $cardMode, inboxFilter: $inboxFilter) }
    }
}

/// A linked destination never substitutes this iPad's install catalog or owner tools.
struct LinkedDeviceAppletsView: View {
    @ObservedObject var live: PhoneLive
    var body: some View {
        VStack(spacing: 0) {
            HomeHeader(live: live, devices: [], selected: .constant(live.device.id)).padding(16)
            if !live.device.isLive, let phone = live.device.cloud { LiveAccessBar(phone: phone) }
            if let error = live.liveError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(B.onSurfaceVariant).padding(12)
            }
            ScrollView {
                LinkedAppletReadList(rows: live.applets.map { .init(id: $0.id, name: $0.name, kind: $0.kind, summary: $0.summary, running: $0.runningReported ? $0.running : nil, lastAt: $0.lastAt, sensors: $0.sensors) }, reported: live.appletsReported)
                    .padding(16)
            }
        }
        .background(B.surface)
        .navigationTitle(live.device.name + " · Applets")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: ObjectIdentifier(live)) {
            while !Task.isCancelled {
                await live.refresh()
                try? await Task.sleep(for: .seconds(max(live.minInterval, 10)))
            }
        }
        .refreshable { await live.refresh(force: true) }
    }
}

/// Watches the phone so its applet list redraws the tabs when it changes.
private struct LiveAppletsTabs: View {
    @ObservedObject var live: PhoneLive
    @Binding var picked: AppletsView.Part?
    @Binding var cardMode: AppletCardsView.Mode
    @Binding var inboxFilter: LocalInteractionInbox.Filter
    var body: some View {
        AppletsTabs(device: live.device, phoneApplets: live.applets, picked: $picked, cardMode: $cardMode, inboxFilter: $inboxFilter)
            .task(id: ObjectIdentifier(live)) { await live.refresh() } // spaced by the relay allowance; cheap when recently read
    }
}

private struct AppletsTabs: View {
    @EnvironmentObject var model: AppModel
    var device: PortalDevice?
    var phoneApplets: [AppletRow]
    @Binding var picked: AppletsView.Part?
    @Binding var cardMode: AppletCardsView.Mode
    @Binding var inboxFilter: LocalInteractionInbox.Filter
    @State private var files: OwnerFilesDestination?
    @State private var hasShownPortals = false
    @State private var portal: String?
    @StateObject private var web = AppletPageBrowser()

    var portals: [AppletLists.Portal] {
        AppletLists.portals(device: device, accountApplets: model.applets)
    }

    /// Android 3347 opens Portals when available and otherwise opens Applets. Linked pages arrive asynchronously.
    var section: AppletsView.Part { picked ?? (portals.isEmpty ? .config : .portals) }

    @Environment(\.dynamicTypeSize) private var textSize
    private var tabs: some View {
        ForEach(AppletsView.Part.allCases) { tab in
            let selected = section == tab
            Button { picked = tab } label: {
                Text(tab.rawValue)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(selected ? B.primary : B.onSurfaceVariant)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.vertical, 8)
                    .frame(minHeight: 48)
                    .overlay(alignment: .bottom) {
                        if selected { Capsule().fill(B.primary).frame(height: 3) }
                    }
                    .padding(.horizontal, 6)
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .id(tab)
            .accessibilityIdentifier("home-section-tab-" + tab.id)
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                if textSize.isAccessibilitySize {
                    ScrollViewReader { reader in
                        ScrollView(.horizontal) {
                            HStack(spacing: 6) { tabs }
                        }
                        .scrollIndicators(.hidden)
                        .onChange(of: section, initial: true) { _, tab in reader.scrollTo(tab, anchor: .center) }
                    }
                } else { tabs }
                if section == .cards { OwnerFilesMenu(selection: $files) }
            }
            .frame(maxWidth: 600)
            ZStack {
                // The portal page stays loaded while another tab shows, like the APK's hidden fragment.
                if section == .portals || hasShownPortals {
                    AppletPortalsView(portals: portals, current: $portal, web: web, visible: section == .portals, showApplets: { picked = .config })
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
                        AppletCardsView(inboxFilter: $inboxFilter, mode: $cardMode).background(B.surface)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(B.surface)
        .onChange(of: section, initial: true) { _, section in
            if section == .portals { hasShownPortals = true }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $files) { destination in
            OwnerFilesView(kind: destination.kind)
                .navigationTitle("Files and device access")
                .toolbar(.visible, for: .navigationBar)
                .toolbar(.hidden, for: .tabBar)
        }
        .navigationDestination(for: AppletSummary.self) { AppletDetailView(applet: $0) }
        .navigationDestination(for: AppletLists.Card.self) { CardPreviewView(card: $0) }
    }
}

/// Local package reviews stay available alongside applets reported by a linked device.
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
    var accountApplets: [AppletSummary] { model.applets.filter { $0.phone == install && !(dogInstalled && $0.id == dogAccount?.id) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if device == nil {
                    PortableHomeApplets(cloud: model.cloud, session: model.cloud.session,
                                        signIn: { Task { await model.signInWithBridgit() } })
                        .padding(.vertical, 12)
                }
                if let device {
                    SectionLabel(text: device.name)
                    if dogInstalled { dogCard }
                    ForEach(otherApps) { PhoneAppletRowCard(applet: $0) }
                    if !agentApps.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Made by your agents").font(.headline).foregroundStyle(B.onSurface)
                            Text("These run on the phone and keep counting while your agent is offline.")
                                .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                        }
                        .padding(.top, 6)
                        ForEach(agentApps) { PhoneAppletRowCard(applet: $0) }
                    }
                    if !accountApplets.isEmpty {
                        Text("Applet pages on your account").font(.headline).foregroundStyle(B.onSurface).padding(.top, 6)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 10)], spacing: 10) {
                            ForEach(accountApplets) { a in
                                NavigationLink(value: a) { AppletCard(applet: a) }.buttonStyle(.plain)
                            }
                        }
                    }

                    if !dogInstalled { dogCard }
                }
                SectionLabel(text: "Tools").accessibilityAddTraits(.isHeader)
                ConnectionsEntry()
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
                    Text("Pet monitor").font(.headline).foregroundStyle(B.onSurface)
                    Text("Photo checks on your pet.")
                        .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                    if dogRow?.running == true {
                        Text("Watching your dog").font(.caption.weight(.semibold)).foregroundStyle(B.onPrimaryContainer)
                            .padding(.horizontal, 10).padding(.vertical, 4).background(B.primaryContainer, in: Capsule()).padding(.top, 4)
                    }
                    if let t = dogRow?.lastAt { Text("Last active " + Age.text(ms: t)).font(.caption).foregroundStyle(B.onSurfaceVariant) }
                    if !dogInstalled {
                        Text("Set it up on the phone: Home › Applets › Pet monitor.").font(.caption).foregroundStyle(B.onSurfaceVariant).padding(.top, 2)
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

    var buildCard: some View {
        NavigationLink {
            BuildInstructionsView()
                .navigationTitle("Build instructions")
                .toolbar(.hidden, for: .tabBar)
        } label: {
            BuildInstructionsRow()
        }.buttonStyle(.plain)
    }
}

/// One applet the phone reports (/v1/portal/applets): name, its state word, sensors and last activity.
struct PhoneAppletRowCard: View {
    var applet: AppletRow
    var body: some View {
        LinkedAppletReadRow(applet: .init(id: applet.id, name: applet.name, kind: applet.kind,
            summary: applet.summary, running: applet.runningReported ? applet.running : nil, lastAt: applet.lastAt, sensors: applet.sensors))
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
                    Text(applet.title).font(.headline).foregroundStyle(B.onSurface)
                    Text(applet.tagline ?? applet.name ?? "").font(.subheadline).foregroundStyle(B.onSurfaceVariant).lineLimit(2)
                    HStack(spacing: 8) {
                        StatePill(text: applet.paused == true ? "Paused" : applet.running == true ? "Running" : "Not running",
                                  tone: applet.paused == true ? .idle : applet.running == true ? .good : .warn)
                        Text(applet.isOwner ? "Owner" : "Shared with you").font(.caption).foregroundStyle(B.onSurfaceVariant)
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
                        Text(detail?.friendly_name ?? applet.title).font(.title2.weight(.semibold))
                        Text(detail?.tagline ?? applet.tagline ?? "").foregroundStyle(B.onSurfaceVariant)
                    }
                }
                AccentRule()
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("CURRENT STATUS").font(.caption.weight(.semibold)).foregroundStyle(B.onSurfaceVariant)
                        if let img = current {
                            Image(uiImage: img).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12)).transition(.opacity)
                        }
                        Text(detail?.current_image?.words ?? (model.isSample ? "Resting · calm" : "Location indeterminate")).font(.headline)
                        HStack {
                            StatePill(text: detail?.connected == true ? "Device connected" : "Device not connected", tone: detail?.connected == true ? .good : .idle)
                            if let at = detail?.live_at { Text("Updated " + Age.text(ms: at)).font(.caption).foregroundStyle(B.onSurfaceVariant) }
                        }
                    }
                }
                if let counts = detail?.saved_photo_counts, !counts.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
                        ForEach(counts.sorted { $0.value > $1.value }, id: \.key) { k, v in
                            Card { VStack(alignment: .leading) { Text("\(v)").font(.title2.weight(.semibold)); Text(k.capitalized).font(.caption).foregroundStyle(B.onSurfaceVariant) } }
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
                            if let summary { Text(summary).font(.body).transition(.opacity) }
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
                if let error { Text(error).foregroundStyle(B.error).font(.footnote) }
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
                HStack { Spacer(); Button("Done") { viewer = nil } }
                if let t = thumbs[im.id] { Image(uiImage: t).resizable().scaledToFit() }
                Text(im.words).font(.headline)
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
