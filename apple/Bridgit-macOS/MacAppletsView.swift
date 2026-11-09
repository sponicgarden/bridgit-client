import SwiftUI

struct MacAppletsView: View {
    @EnvironmentObject private var model: MacModel
    var showLinkedDevices: () -> Void = {}
    @State private var inboxFilter = LocalInteractionInbox.Filter.all
    @State private var picked: AppletsSection?
    @State private var cardMode = "Inbox"
    @State private var files: OwnerFilesDestination?
    @AppStorage(DeviceWebLinkView.linkedKey, store: AppleFixtureWorkspace.defaults) private var webAccountLinked = false
    @State private var webLink = false
    @State private var showInstructions = false
    @State private var preview: AppletLists.Card?
    @State private var selectedPortalID: String?
    @StateObject private var portalBrowser = AppletPageBrowser()
    private var section: AppletsSection { picked ?? (model.appletPortals.isEmpty ? .config : .portals) }
    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            if model.viewingLinkedDevice {
                HStack {
                    Label(model.selectedDeviceName ?? "Linked device", systemImage: "eye")
                    Spacer()
                    Button("Local Mac", action: showLocalDevice)
                }
            }
            HStack {
                Picker("Applet section", selection: Binding(get: { section }, set: { picked = $0 })) {
                    ForEach(AppletsSection.allCases) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented).labelsHidden().frame(maxWidth: 360, alignment: .leading)
                if section == .cards {
                    Spacer()
                    OwnerFilesMenu(selection: $files)
                }
            }
            switch section {
            case .portals: portals
            case .config: config
            case .cards: cards
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .bridgitDialog(item: $files) { destination in
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Files and device access").font(.headline)
                    Spacer()
                    Button("Done") { files = nil }
                }.padding()
                OwnerFilesView(kind: destination.kind)
            }.frame(minWidth: 460, idealWidth: 600, minHeight: 380, idealHeight: 560)
        }
        .bridgitDialog(isPresented: $showInstructions) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Build instructions").font(.headline)
                    Spacer()
                    Button("Done") { showInstructions = false }
                }.padding()
                BuildInstructionsView()
            }.frame(minWidth: 460, idealWidth: 600, minHeight: 380, idealHeight: 560)
        }
        .bridgitDialog(isPresented: $webLink) { DeviceWebLinkView() }
        .bridgitDialog(item: $preview) { card in
            MacLibraryCardSheet(card: card) { preview = nil }
        }
        .onChange(of: model.selectedInstallID) { _, _ in selectedPortalID = nil }
        .onChange(of: model.portalAccountRevision) { _, _ in selectedPortalID = nil }
    }
    private func showLocalDevice() {
        do { try model.selectConfiguredDevice(nil) }
        catch { model.problem = error.localizedDescription }
    }
    private var config: some View {
        VStack(alignment: .leading, spacing: 24) {
            if !model.viewingLinkedDevice {
                PortableHomeApplets(cloud: model.configuredCloud, session: model.portalSession,
                                    signIn: model.signInWithBridgit, showLinkedDevices: showLinkedDevices)
            }
            if model.viewingLinkedDevice && (!model.remoteAppletRows.isEmpty || !model.selectedAccountApplets.isEmpty) {
                VStack(alignment: .leading, spacing: 14) {
                    Text(model.selectedDeviceName ?? "Linked device").font(.headline)
                    ForEach(Array(model.remoteAppletRows.enumerated()), id: \.offset) { _, row in
                        statusRow(title: row["name"] as? String ?? "Applet", detail: "Runs on \(model.selectedDeviceName ?? "the linked device")", state: row["running"] as? Bool == true ? "Running" : "Not running")
                    }
                    ForEach(model.selectedAccountApplets) { a in
                        statusRow(title: a.title, detail: a.tagline ?? "On \(model.selectedDeviceName ?? a.phone)", state: a.paused == true ? "Paused" : a.running == true ? "Running" : "Account snapshot")
                    }
                }
            }
            Divider()
            SectionLabel(text: "Tools").accessibilityAddTraits(.isHeader)
            ConnectionsEntry()
            Button { showInstructions = true } label: {
                BuildInstructionsRow()
            }.buttonStyle(.plain)
            DisclosureGroup("More applets and help") {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Pet monitor is set up on a capable linked device.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                    Button("Choose a device", action: showLinkedDevices).buttonStyle(.link)
                }.padding(.top, 12).frame(maxWidth: .infinity, alignment: .leading)
            }.font(.callout).foregroundStyle(B.onSurfaceVariant)
        }
    }
    @ViewBuilder private var portals: some View {
        let pages = model.appletPortals
        if let current = pages.first(where: { $0.id == selectedPortalID }) ?? pages.first {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    if pages.count > 1 {
                        Picker("Portal", selection: Binding(get: { current.id }, set: { selectedPortalID = $0 })) {
                            ForEach(pages) { page in Text(page.name).tag(page.id) }
                        }.labelsHidden()
                    } else {
                        Text(current.name).font(.headline)
                    }
                    Spacer()
                    Button("Reload page", systemImage: "arrow.clockwise") { portalBrowser.reload() }
                    Button("Open in browser", systemImage: "arrow.up.right.square") {
                        portalBrowser.openInBrowser(fallback: current.url)
                    }
                }.labelStyle(.iconOnly)
                MacPortalPage(url: current.url, session: model.portalSession, email: model.email, browser: portalBrowser)
                    .overlay {
                        if let problem = portalBrowser.problem {
                            ContentUnavailableView("Webpage not available", systemImage: "globe", description: Text(problem))
                                .frame(maxWidth: .infinity, maxHeight: .infinity).background(B.surface)
                        }
                    }
                    .id(model.portalAccountRevision)
                    .frame(minHeight: 420)
            }
        } else {
            EmptyAppletPages(webLinkTitle: "Link this Mac to the web", offerWebLink: !webAccountLinked,
                             showApplets: { picked = .config }, showWebLink: { webLink = true })
        }
    }
    private var cards: some View {
        VStack(alignment: .leading, spacing: 18) {
            Picker("Card source", selection: $cardMode) { Text("Inbox").tag("Inbox"); Text("Library").tag("Library") }
                .pickerStyle(.segmented).labelsHidden().frame(maxWidth: 220).frame(maxWidth: .infinity)
            if cardMode == "Inbox" {
                LocalInteractionInbox(browseLibrary: { cardMode = "Library" }, filter: $inboxFilter)
            } else {
                Text("Rich cards control agents can show on this Mac. Choose one to try it; your answer stays here.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                if AppletLists.bundledCards.isEmpty { Text("The card library did not load.").foregroundStyle(B.error) }
                VStack(spacing: 0) {
                    let cards = AppletLists.bundledCards
                    ForEach(cards) { card in
                        Button { preview = card } label: {
                            CardLibraryRow(card: card)
                        }.buttonStyle(.plain)
                        if card.id != cards.last?.id { Divider() }
                    }
                }
            }
        }
    }
    private func statusRow(title: String, detail: String, state: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 5) { Text(title).font(.headline); Text(detail).font(.callout).foregroundStyle(B.onSurfaceVariant) }
            Spacer(); Text(state).font(.caption).foregroundStyle(B.onSurfaceVariant)
        }.padding(.vertical, 12)
    }
}

struct MacLibraryCardSheet: View {
    let card: AppletLists.Card
    let close: () -> Void
    @State private var contentHeight: CGFloat = 340

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(card.title).font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Done", action: close)
            }.padding()
            Text("Try it out: your answer stays on this Mac.")
                .font(.callout).foregroundStyle(B.onSurfaceVariant).padding(.horizontal).padding(.bottom, 10)
            MacCardPreview(html: card.previewHTML, onContentHeight: { contentHeight = $0 })
                .frame(minWidth: 440, idealWidth: 700)
                .frame(height: min(max(340, contentHeight), availableHeight))
        }
    }

    private var availableHeight: CGFloat {
        let screen = NSApp.keyWindow?.screen ?? NSScreen.main
        // Leave room for the sheet header, window title bar, and sheet margins.
        return max(200, (screen?.visibleFrame.height ?? 760) - 160)
    }
}
