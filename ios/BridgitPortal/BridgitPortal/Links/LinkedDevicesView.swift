import SwiftUI

// Linked devices on Apple (user role). Built to the design pass in docs/design/linked-devices-apple.md:
// flat rows on `surface`, minimal text, one prominent action per view, Undo instead of confirmations.

/// Text at an exact size that still follows Dynamic Type.
private struct Sized: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    let mono: Bool
    init(_ size: CGFloat, _ weight: Font.Weight, mono: Bool) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: size >= 16 ? .body : .subheadline)
        self.weight = weight
        self.mono = mono
    }
    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight, design: mono ? .monospaced : .default)) }
}

extension View {
    fileprivate func text(_ size: CGFloat, _ weight: Font.Weight = .regular, mono: Bool = false) -> some View { modifier(Sized(size, weight, mono: mono)) }
}

/// The shared flat row: 40 pt icon, title, optional secondary lines, one trailing chevron.
private struct FlatRow<Extra: View>: View {
    var icon: String
    var title: String
    var titleColor: Color = B.onSurface
    var secondary: [String] = []
    var chevron = true
    @ViewBuilder var extra: () -> Extra

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 20, weight: .medium)).foregroundStyle(B.onPrimaryContainer)
                .frame(width: 40, height: 40).background(B.primaryContainer, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).text(16, .medium).foregroundStyle(titleColor)
                ForEach(secondary, id: \.self) { Text($0).text(14).foregroundStyle(B.onSurfaceVariant) }
                extra()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if chevron {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(B.outline).frame(width: 44)
            }
        }
        .padding(.vertical, 12).frame(minHeight: 64)
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 1).padding(.leading, 52) }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

extension FlatRow where Extra == EmptyView {
    init(icon: String, title: String, titleColor: Color = B.onSurface, secondary: [String] = [], chevron: Bool = true) {
        self.init(icon: icon, title: title, titleColor: titleColor, secondary: secondary, chevron: chevron) { EmptyView() }
    }
}

private struct SectionHead: View {
    var text: String
    var body: some View {
        Text(text).text(14, .medium).foregroundStyle(B.primary).frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 24).padding(.bottom, 8).accessibilityAddTraits(.isHeader)
    }
}

/// More › Linked devices.
struct LinkedDevicesView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var links: LinkStore
    @EnvironmentObject var names: DeviceNameStore
    @State private var showClaim = UserDefaults.standard.string(forKey: "open") == "claim"
    @State private var showName = UserDefaults.standard.string(forKey: "open") == "name"
    @State private var showLog = UserDefaults.standard.string(forKey: "open") == "log"
    @State private var via: LinkMapLayout.Node?
    @State private var openCard: String? = UserDefaults.standard.string(forKey: "open").flatMap { $0.hasPrefix("link:") ? String($0.dropFirst(5)) : nil }

    private var me: String { names.name ?? "This device" }
    private var gone: Set<String> { Set(links.pending.keys).union(links.retiring.keys) }
    private var shown: [LinkedBridgit] {
        links.links.filter { links.retiring[$0.id] == nil }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Linked devices").font(.system(size: 34)).foregroundStyle(B.onSurface).padding(.top, 8).padding(.bottom, 16)
                Button { showName = true } label: {
                    FlatRow(icon: DeviceKind.thisDeviceSymbol, title: names.name ?? "Name this device", titleColor: names.name == nil ? B.primary : B.onSurface,
                            secondary: names.name == nil ? [] : ["This device"])
                }.buttonStyle(.plain)
                if !links.introductions.isEmpty {
                    VStack(spacing: 12) { ForEach(links.introductions.sorted { $0.at < $1.at }) { IntroductionCard(intro: $0) } }.padding(.top, 16)
                }
                if shown.isEmpty {
                    Text("No linked devices").text(16).foregroundStyle(B.onSurfaceVariant).padding(.vertical, 24)
                } else {
                    LinkMapView(me: me, links: shown, gone: gone) { node in
                        if node.ring == 0 { showName = true } else if node.via != nil { via = node } else { openCard = node.linkId }
                    }
                    .padding(.top, 16)
                    VStack(spacing: 0) {
                        ForEach(shown) { l in
                            Button { openCard = l.id } label: { LinkRow(link: l, me: me, cut: gone.contains(l.id)) }.buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 16)
                }
                Button { showClaim = true } label: {
                    Text("Link a Bridgit").text(16, .medium).frame(maxWidth: .infinity).frame(minHeight: 48)
                }
                .buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 12)).tint(B.primary).foregroundStyle(B.onPrimary)
                .padding(.top, 20)
                NavigationLink { LinkLogView(events: links.log.reversed()) } label: {
                    FlatRow(icon: "list.bullet.rectangle", title: "Log")
                }
                .buttonStyle(.plain).padding(.top, 16)
                if shown.contains(where: { links.pending[$0.id] == nil }) {
                    Button(role: .destructive) { links.disconnectAll() } label: {
                        Text("Disconnect all").text(16, .medium).frame(maxWidth: .infinity).frame(minHeight: 48)
                    }
                    .buttonStyle(.plain).foregroundStyle(B.error).padding(.top, 24)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { UndoBar() }
        .navigationDestination(item: $openCard) { LinkCardView(linkId: $0) }
        .navigationDestination(isPresented: $showLog) { LinkLogView(events: links.log.reversed()) }
        .sheet(isPresented: $showClaim) { ClaimLinkView() }
        .sheet(isPresented: $showName) { NameDeviceView() }
        .sheet(item: $via) { node in ViaSheet(node: node, me: me) { id in via = nil; openCard = id } }
        .task {
            // Peers and introductions while the screen is open; the relay allows a call every few seconds.
            while !Task.isCancelled {
                await links.refreshRemote()
                await links.flush()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }
}

/// One linked Bridgit: name, what this device may use (and, two-way, what it may use here), and the state.
struct LinkRow: View {
    var link: LinkedBridgit
    var me: String
    var cut: Bool

    private var lines: [String] {
        let out = LinkService.list(link.out.services), inn = LinkService.list(link.inbound.services)
        if link.out.state != .off && link.inbound.state != .off { return ["→ " + out, "← " + inn] }
        return [link.inbound.state != .off ? inn : out].filter { !$0.isEmpty }
    }

    var body: some View {
        FlatRow(icon: DeviceKind.symbol(link.deviceType), title: link.name, secondary: lines) {
            LinkStatePill(state: cut ? .off : (link.out.state != .off ? link.out.state : link.inbound.state)).padding(.top, 4)
        }
    }
}

/// Dot + word, outlined; the word carries the state, never colour alone.
struct LinkStatePill: View {
    var state: DirectionState

    private var spec: (String, Color) {
        switch state {
        case .on: ("On", B.success)
        case .paused: ("Paused", B.onSecondaryContainer)
        case .waiting_for_target, .waiting_for_user: ("Waiting", B.onSurfaceVariant)
        case .off: ("Off", B.onSurfaceVariant)
        }
    }

    var body: some View {
        let (word, color) = spec
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 5, height: 5)
            Text(word).text(12, .medium).foregroundStyle(color)
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .overlay(Capsule().stroke(color, lineWidth: 1))
        .fixedSize()
    }
}

/// LNK-26: one bar for the batch of disconnects, Undo for 10 seconds; no countdown.
private struct UndoBar: View {
    @EnvironmentObject var links: LinkStore
    @Environment(\.dynamicTypeSize) private var type

    private var message: String {
        let ids = Array(links.pending.keys)
        if ids.count == 1, let l = links.link(ids[0]) { return "Disconnected " + l.name }
        if ids.count > 1 && ids.count == links.links.filter({ links.retiring[$0.id] == nil }).count { return "Disconnected all" }
        return "Disconnected \(ids.count) devices"
    }

    var body: some View {
        if !links.pending.isEmpty {
            let layout = type.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                Text(message).text(14).foregroundStyle(B.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                Button("Undo") { links.undoAll() }.text(16, .semibold).foregroundStyle(B.primary).frame(minWidth: 44, minHeight: 44)
            }
            .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 56)
            .background(B.surfaceHigh, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(B.outline, lineWidth: 1))
            .frame(maxWidth: 728).padding(.horizontal, 16).padding(.bottom, 8)
            .accessibilityElement(children: .contain)
            .onAppear { UIAccessibility.post(notification: .announcement, argument: message + ". Undo available.") }
        }
    }
}

/// LNK-36 Ask me: Allow or Deny an introduction.
private struct IntroductionCard: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var links: LinkStore
    var intro: Introduction
    @State private var busy: Bool?
    @State private var failed: Bool?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Use \(intro.fromName)?").text(16, .semibold).foregroundStyle(B.onSecondaryContainer)
            if !intro.services.isEmpty { Text(LinkService.list(intro.services)).text(14).foregroundStyle(B.onSecondaryContainer).padding(.top, 6) }
            Text([intro.agentName, "via " + intro.via].compactMap { $0 }.joined(separator: " "))
                .text(14).foregroundStyle(B.onSecondaryContainer).padding(.top, 4)
            HStack(spacing: 8) {
                answerButton(false, "Deny", B.onSecondaryContainer)
                answerButton(true, "Allow", B.primary)
            }
            .padding(.top, 12)
            if let f = failed { Text(f ? "Couldn’t allow. Try again." : "Couldn’t deny. Try again.").text(14).foregroundStyle(B.error).padding(.top, 8) }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(B.secondaryContainer, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .disabled(busy != nil)
    }

    private func answerButton(_ allow: Bool, _ title: String, _ tint: Color) -> some View {
        Button { answer(allow) } label: {
            HStack(spacing: 8) {
                Text(title).text(16, .medium)
                if busy == allow { ProgressView().controlSize(.small) }
            }
            .frame(minHeight: 48).padding(.horizontal, 16)
        }
        .buttonStyle(.bordered).buttonBorderShape(.roundedRectangle(radius: 12)).tint(tint)
    }

    private func answer(_ allow: Bool) {
        busy = allow
        failed = nil
        Task {
            await model.answerIntroduction(intro.id, allow: allow)
            if links.introductions.contains(where: { $0.id == intro.id }) { failed = allow }
            busy = nil
        }
    }
}

extension LinkMapLayout.Node: Hashable {
    func hash(into h: inout Hasher) { h.combine(id) }
}

/// LNK-40: the path to a device seen through a linked Bridgit.
private struct ViaSheet: View {
    var node: LinkMapLayout.Node
    var me: String
    var openCard: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let b = node.via ?? ""
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    hop(DeviceKind.thisDeviceSymbol, me)
                    arrow
                    hop("app.connected.to.app.below.fill", b)
                    arrow
                    hop("app.connected.to.app.below.fill", node.name)
                    SectionHead(text: b + " → " + node.name)
                    HStack(alignment: .firstTextBaseline, spacing: 16) {
                        Text("Can use").text(14).foregroundStyle(B.onSurfaceVariant).frame(width: 112, alignment: .leading)
                        Text("Not reported").text(16).foregroundStyle(B.onSurface)
                    }
                    .frame(minHeight: 56)
                    if let id = node.linkId {
                        Button { openCard(id) } label: { FlatRow(icon: "app.connected.to.app.below.fill", title: b) }.buttonStyle(.plain).padding(.top, 24)
                    }
                }
                .padding(16)
            }
            .background(B.surface)
            .navigationTitle(node.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.buttonStyle(.plain) } }
        }
        .presentationDetents([.medium, .large])
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(node.name), via \(b)")
    }

    private func hop(_ icon: String, _ name: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 20, weight: .medium)).foregroundStyle(B.onPrimaryContainer)
                .frame(width: 40, height: 40).background(B.primaryContainer, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text(name).text(16, .medium).foregroundStyle(B.onSurface)
        }
        .frame(minHeight: 64)
    }

    private var arrow: some View {
        Image(systemName: "arrow.down").font(.system(size: 14, weight: .semibold)).foregroundStyle(B.primary)
            .frame(width: 40, height: 24).accessibilityHidden(true)
    }
}

/// LNK-42: one linked Bridgit's card: both directions, who granted each and when, Pause and Disconnect.
struct LinkCardView: View {
    @EnvironmentObject var links: LinkStore
    @EnvironmentObject var names: DeviceNameStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var type
    var linkId: String

    var body: some View {
        Group {
            if let l = links.link(linkId) {
                let me = names.name ?? "This device"
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        direction(l, out: true, head: me + " → " + l.name, d: l.out)
                        direction(l, out: false, head: l.name + " → " + me, d: l.inbound)
                        let others = l.peers.filter { $0.name != me }
                        if !others.isEmpty {
                            SectionHead(text: "Linked to")
                            ForEach(others, id: \.name) { p in
                                FlatRow(icon: "app.connected.to.app.below.fill", title: p.name,
                                        secondary: [p.uses ? l.name + " → " + p.name : p.name + " → " + l.name], chevron: false)
                            }
                        }
                        NavigationLink { LinkLogView(events: links.events(for: l), peer: l.name) } label: {
                            FlatRow(icon: "list.bullet.rectangle", title: "Log")
                        }
                        .buttonStyle(.plain).padding(.top, 24)
                        if links.pending[l.id] == nil {
                            Button(role: .destructive) { links.disconnect(l.id); dismiss() } label: {
                                Text("Disconnect").text(16, .medium).frame(maxWidth: .infinity).frame(minHeight: 48)
                            }
                            .buttonStyle(.plain).foregroundStyle(B.error).padding(.top, 8)
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 24).frame(maxWidth: 760).frame(maxWidth: .infinity)
                }
                .navigationTitle(l.name)
            } else {
                Text("Disconnected").text(16).foregroundStyle(B.onSurfaceVariant).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(B.surface)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { UndoBar() }
    }

    @ViewBuilder private func direction(_ l: LinkedBridgit, out: Bool, head: String, d: LinkDirection) -> some View {
        SectionHead(text: head)
        meta("Can use", d.services.isEmpty || d.state == .off ? "None" : LinkService.list(d.services))
        if d.state != .off || d.grantedBy != nil {
            meta("Granted by", d.grantedBy?.label ?? "Not recorded")
            if let at = d.grantedAt { meta("Since", at.formatted(date: .long, time: .shortened)) }
        }
        HStack(spacing: 16) {
            Text("State").text(14).foregroundStyle(B.onSurfaceVariant).frame(width: 112, alignment: .leading)
            LinkStatePill(state: out && links.pending[l.id] != nil ? .off : d.state)
            Spacer()
        }
        .frame(minHeight: 56)
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 1) }
        if out && links.pending[l.id] == nil {
            if d.state == .on { action("Pause") { links.setPaused(l.id, true) } }
            if d.state == .paused { action("Resume") { links.setPaused(l.id, false) } }
            if l.inbound.state != .off && d.state != .off {
                Button(role: .destructive) { links.disconnect(l.id, direction: "out") } label: {
                    Text("Disconnect direction").text(16, .medium).frame(minHeight: 48)
                }.buttonStyle(.plain).foregroundStyle(B.error)
            }
        }
    }

    private func meta(_ label: String, _ value: String) -> some View {
        let stacked = type.isAccessibilitySize
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4)) : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 16))
        return layout {
            Text(label).text(14).foregroundStyle(B.onSurfaceVariant).frame(width: stacked ? nil : 112, alignment: .leading)
            Text(value).text(16).foregroundStyle(B.onSurface).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 12).frame(minHeight: 56)
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 1) }
        .accessibilityElement(children: .combine)
    }

    private func action(_ title: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) { Text(title).text(16, .medium).frame(minHeight: 48) }.buttonStyle(.plain).foregroundStyle(B.primary)
    }
}

/// LNK-24: the grants log, newest first.
struct LinkLogView: View {
    @EnvironmentObject var names: DeviceNameStore
    var events: [LinkEvent]
    /// Set for one device's log: rows then show the event alone.
    var peer: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if events.isEmpty { Text("No activity").text(16).foregroundStyle(B.onSurfaceVariant).padding(.top, 24) }
                ForEach(events) { LogRow(event: $0, me: names.name ?? "This device", showPeer: peer == nil) }
            }
            .padding(.horizontal, 16).padding(.top, 8).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .navigationTitle("Log")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LogRow: View {
    var event: LinkEvent
    var me: String
    var showPeer: Bool

    private var path: String {
        switch event.direction {
        case "in": event.peer + " → " + me
        case "both": me + " ↔ " + event.peer
        default: me + " → " + event.peer
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(showPeer ? event.title + " · " + event.peer : event.title).text(16, .medium).foregroundStyle(B.onSurface)
            Text(path).text(14).foregroundStyle(B.onSurfaceVariant).padding(.top, 4)
            Text([event.granter.label, event.after].compactMap { $0 }.joined(separator: " · ")).text(14).foregroundStyle(B.onSurfaceVariant).padding(.top, 2)
            Text(event.at.formatted(date: .long, time: .shortened) + (event.late && event.reportedBy != nil ? " · received later" : ""))
                .text(12).foregroundStyle(B.onSurfaceVariant).padding(.top, 4)
        }
        .padding(.vertical, 14).frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 1) }
        .accessibilityElement(children: .combine)
    }
}

/// A labelled input box (design pass: persistent label, surfaceContainer, 1 pt outline, 2 pt primary when focused).
private struct Field: View {
    var label: String
    var placeholder: String
    @Binding var text: String
    var mono = false
    var size: CGFloat = 16
    var caps = false
    var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).text(14, .medium).foregroundStyle(B.onSurfaceVariant)
            TextField("", text: $text, prompt: Text(placeholder).foregroundStyle(B.onSurfaceVariant))
                .text(size, mono: mono).foregroundStyle(B.onSurface)
                .textInputAutocapitalization(caps ? .characters : .never).autocorrectionDisabled()
                .focused($focused)
                .padding(.horizontal, 14).padding(.vertical, 12).frame(minHeight: 52)
                .background(B.surfaceContainer, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(focused ? B.primary : B.outline, lineWidth: focused ? 2 : 1))
                .accessibilityLabel(label)
            if let error { Text(error).text(14).foregroundStyle(B.error) }
        }
    }
}

private struct BottomAction: View {
    var title: String
    var enabled: Bool
    var run: () -> Void
    var body: some View {
        Button(action: run) { Text(title).text(16, .medium).frame(maxWidth: .infinity).frame(minHeight: 48) }
            .buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 12)).tint(B.primary).foregroundStyle(B.onPrimary)
            .disabled(!enabled).keyboardShortcut(.defaultAction)
            .padding(16).background(B.surface)
    }
}

/// LNK-18: claim a name and code, typed or scanned.
struct ClaimLinkView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var names: DeviceNameStore
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false
    @State private var name = ""
    @State private var code = ""
    @State private var scanning = false
    @State private var busy = false
    @State private var tried = false
    @State private var error: String?

    private var offer: LinkOffer? {
        guard let n = DeviceNameStore.parse(name), let c = LinkCode.normalize(code) else { return nil }
        return LinkOffer(name: n, code: c)
    }

    private var nameError: String? {
        guard tried || !name.isEmpty else { return nil }
        if name.trimmingCharacters(in: .whitespaces).isEmpty { return tried ? "Enter a Device Domain Name." : nil }
        return DeviceNameStore.parse(name) == nil ? "Use 3–30 lowercase letters or digits, with single hyphens between groups." : nil
    }

    private var codeError: String? {
        guard tried || code.filter({ $0.isLetter || $0.isNumber }).count >= 8 else { return nil }
        return LinkCode.normalize(code) == nil ? "Enter an 8-character code." : nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if names.name == nil {
                        Button { naming = true } label: { FlatRow(icon: DeviceKind.thisDeviceSymbol, title: "Name this device", titleColor: B.primary) }
                            .buttonStyle(.plain)
                    }
                    Field(label: "Device Domain Name", placeholder: "kitchen-tab", text: $name, error: nameError)
                    Field(label: "Link code", placeholder: "K7QX-3MPA", text: $code, mono: true, size: 20, caps: true, error: codeError)
                        .onChange(of: code) { _, v in
                            let raw = v.uppercased().filter { $0.isLetter || $0.isNumber }
                            let shown = raw.count > 4 ? String(raw.prefix(4)) + "-" + String(raw.dropFirst(4).prefix(4)) : raw
                            if shown != v { code = shown }
                        }
                    if DeviceKind.hasCamera {
                        Button { scanning = true } label: {
                            Label("Scan QR code", systemImage: "qrcode.viewfinder").text(16, .medium).frame(maxWidth: .infinity).frame(minHeight: 48)
                        }
                        .buttonStyle(.bordered).buttonBorderShape(.roundedRectangle(radius: 12)).tint(B.primary)
                    }
                    if let error { Text(error).text(14).foregroundStyle(B.error) }
                }
                .padding(.horizontal, 16).padding(.top, 24)
            }
            .background(B.surface)
            .safeAreaInset(edge: .bottom) {
                BottomAction(title: busy ? "Linking…" : "Link", enabled: !busy && offer != nil && names.name != nil) { link() }
            }
            .navigationTitle("Link a Bridgit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.buttonStyle(.plain) } }
            .sheet(isPresented: $naming) { NameDeviceView() }
            .sheet(isPresented: $scanning) {
                NavigationStack {
                    QRScanner { text in
                        scanning = false
                        if let o = LinkOffer.parse(text) { name = o.name; code = LinkCode.display(o.code); error = nil } else { error = "Not a Bridgit link code." }
                    }
                    .ignoresSafeArea()
                    .overlay { RoundedRectangle(cornerRadius: 20).stroke(.white, lineWidth: 2).frame(width: 240, height: 240).accessibilityHidden(true) }
                    .navigationTitle("Scan QR code")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { scanning = false } } }
                }
            }
            .onAppear {
                if let o = model.pendingLinkOffer { name = o.name; code = LinkCode.display(o.code); model.pendingLinkOffer = nil }
            }
        }
        .presentationDetents([.large])
    }

    private func link() {
        tried = true
        guard let offer else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                _ = try await model.claimLink(offer)
                dismiss()
            } catch let e as LinkError {
                switch e.code {
                case "code_invalid", "code_expired", "code_used_up": error = "Code invalid or expired."
                case "name_required": naming = true
                case "rate_limited": error = e.message
                default: error = "Couldn’t link. Try again."
                }
            } catch {
                self.error = "Couldn’t link. Try again."
            }
        }
    }
}

/// LNK-21 / LNK-52.1: register this device's Device Domain Name, then show it with Visible.
struct NameDeviceView: View {
    @EnvironmentObject var names: DeviceNameStore
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var error: String?
    @State private var visible = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let n = names.name {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Device Domain Name").text(14).foregroundStyle(B.onSurfaceVariant)
                            Text(n).text(16, mono: true).foregroundStyle(B.onSurface).textSelection(.enabled)
                        }
                        .padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 1) }
                        Toggle(isOn: Binding(get: { visible }, set: { v in setVisible(v) })) { Text("Visible").text(16).foregroundStyle(B.onSurface) }
                            .tint(B.primary).frame(minHeight: 56)
                            .accessibilityLabel("Device name visible")
                    } else {
                        Field(label: "Device Domain Name", placeholder: "kitchen-tab", text: $text, mono: true,
                              error: !text.isEmpty && DeviceNameStore.parse(text) == nil ? "Use 3–30 lowercase letters or digits, with single hyphens between groups." : nil)
                    }
                    if let error { Text(error).text(14).foregroundStyle(B.error).padding(.top, 8) }
                }
                .padding(.horizontal, 16).padding(.top, 24)
            }
            .background(B.surface)
            .safeAreaInset(edge: .bottom) {
                if names.name == nil { BottomAction(title: names.busy ? "Saving…" : "Save", enabled: !names.busy && DeviceNameStore.parse(text) != nil) { save() } }
            }
            .navigationTitle(names.name == nil ? "Name this device" : "This device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if names.name == nil { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.buttonStyle(.plain) } }
                else { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.buttonStyle(.plain) } }
            }
            .onAppear {
                if text.isEmpty { text = DeviceNameStore.suggestion(from: UIDevice.current.name) }
                visible = names.own?.visible ?? true
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func save() {
        error = nil
        Task {
            do { try await names.register(text); dismiss() }
            catch let e as BackendError where e.code == "name_taken" { error = "Name taken." }
            catch let e as BackendError where e.code == "invalid_parameter" { error = "Use 3–30 lowercase letters or digits, with single hyphens between groups." }
            catch { self.error = "Couldn’t save. Try again." }
        }
    }

    private func setVisible(_ v: Bool) {
        let before = visible
        visible = v
        error = nil
        Task {
            do { try await names.update(visible: v) } catch { visible = before; self.error = "Couldn’t update visibility. Try again." }
        }
    }
}
