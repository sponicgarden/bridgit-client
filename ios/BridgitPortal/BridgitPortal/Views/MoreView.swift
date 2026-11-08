import CoreImage
import SwiftUI

/// More, laid out like the APK's More list: rows with an icon, a title and a chevron, under section labels.
struct MoreView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var relays: RelayStore
    @EnvironmentObject var updates: UpdatePrompts
    var live: PhoneLive?
    @State private var showPair = false
    @State private var showLink = false
    @State private var showInstall = false
    /// `-open linked` (or `-open link:<id>`, `claim`, `name`, `log`) opens Linked devices, for screenshots.
    @State private var openLinked = UserDefaults.standard.string(forKey: "open").map { ["linked", "claim", "name", "log"].contains($0) || $0.hasPrefix("link:") } ?? false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("More").font(.system(size: 34)).foregroundStyle(B.onSurface).padding(.bottom, 4)
                // Same first rows as the APK's More: Check for updates, then (portal only) the Android app.
                ActionRow(icon: "arrow.down.circle", title: "Check for updates") { checkForUpdates() }
                // A0050: ask to update when a new TestFlight build is ready; on by default.
                ToggleRow(icon: "bell.badge", title: "Update prompts", isOn: Binding(get: { updates.enabled }, set: { updates.setEnabled($0) }))
                ActionRow(icon: "qrcode", title: "Install Android App") { showInstall = true }
                if let live {
                    SectionLabel(text: live.device.name)
                    // Owner, 5 Nov 2026: the Devices tab moved here, first in the phone's section.
                    NavRow(icon: DevicesIcon.symbol, title: "Network device monitor") { NetworkDevicesView(live: live) }
                    NavRow(icon: "chart.xyaxis.line", title: "Activity") { ActivityView(live: live) }
                    NavRow(icon: "switch.2", title: "Feature enablement") { FeaturesView(live: live) }
                    NavRow(icon: "info.circle", title: "About this phone") { PhoneInfoView(live: live) }
                }
                SectionLabel(text: "Access to phones")
                NavRow(icon: "app.connected.to.app.below.fill", title: "Linked devices") { LinkedDevicesView() }
                ActionRow(icon: "qrcode.viewfinder", title: "Pair with a phone") { showPair = true }
                SectionLabel(text: "Account")
                switch model.mode {
                case .signedIn:
                    InfoRow(icon: "person.crop.circle", title: model.email ?? "Signed in", detail: "Bridgit account (Google)")
                    ActionRow(icon: "link.badge.plus", title: "Connect a phone with a code") { showLink = true }
                    ActionRow(icon: "rectangle.portrait.and.arrow.right", title: "Sign out") { Task { await model.signOut() } }
                case .sample:
                    InfoRow(icon: "eye", title: "Showing sample data", detail: "Nothing here is from a real phone")
                    ActionRow(icon: "rectangle.portrait.and.arrow.right", title: "Leave sample") { Task { await model.signOut() } }
                case .signedOut:
                    ActionRow(icon: "person.crop.circle.badge.checkmark", title: "Sign in with Google") { Task { await model.signInWithBridgit() } }
                }
                SectionLabel(text: "About")
                InfoRow(icon: "app.badge", title: "Bridgit Portal", detail: AppVersion.label)
                Link(destination: URL(string: "https://bridgit.cc/privacy/")!) {
                    HStack(spacing: 18) {
                        Image(systemName: "hand.raised").frame(width: 28)
                        Text("Privacy").foregroundStyle(B.onSurface)
                        Spacer()
                        Image(systemName: "arrow.up.right").foregroundStyle(B.outline)
                    }.padding(.vertical, 14)
                }
                if let e = model.error { Text(e).frostText(.supporting).foregroundStyle(B.error).padding(.top, 12) }
            }
            .padding(16).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $openLinked) { LinkedDevicesView() }
        .sheet(isPresented: $showPair) { PairView() }
        .sheet(isPresented: $showLink) { LinkCodeView() }
        .sheet(isPresented: $showInstall) { InstallAndroidView().presentationDetents([.large]) }
    }
}

extension MoreView {
    /// iOS updates come through TestFlight (later the App Store); open it, or say how updates arrive.
    func checkForUpdates() {
        guard let url = URL(string: "itms-beta://") else { return }
        UIApplication.shared.open(url) { opened in
            if !opened {
                model.notice = .init(title: "Updates", message: "Bridgit Portal updates arrive through TestFlight. Install TestFlight from the App Store to get them.")
            }
        }
    }
}

private struct RowBody: View {
    var icon: String
    var title: String
    var chevron = true
    var body: some View {
        HStack(spacing: 18) {
            Image(systemName: icon).frostText(.section, weight: .semibold).frame(width: 28).foregroundStyle(B.onSurfaceVariant)
            Text(title).frostText(.section, weight: .semibold).foregroundStyle(B.onSurface)
            Spacer()
            if chevron { Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant) }
        }
        // Design pass (A0050): one row geometry for action, link and switch rows: 32 pt content + 12 pt each side.
        .frame(minHeight: 32)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

struct NavRow<D: View>: View {
    var icon: String
    var title: String
    @ViewBuilder var destination: () -> D
    var body: some View {
        NavigationLink { destination() } label: { RowBody(icon: icon, title: title) }.buttonStyle(.plain)
        Divider().overlay(B.outlineVariant)
    }
}

struct ActionRow: View {
    var icon: String
    var title: String
    var action: () -> Void
    var body: some View {
        Button(action: action) { RowBody(icon: icon, title: title, chevron: false) }.buttonStyle(.plain)
        Divider().overlay(B.outlineVariant)
    }
}

struct ToggleRow: View {
    var icon: String
    var title: String
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: 18) {
                Image(systemName: icon).frostText(.section, weight: .semibold).frame(width: 28).foregroundStyle(B.onSurfaceVariant)
                    .accessibilityHidden(true)
                Text(title).frostText(.section, weight: .semibold).foregroundStyle(B.onSurface)
            }
        }
        .tint(B.primary)
        .frame(minHeight: 32)
        .padding(.vertical, 12)
        Divider().overlay(B.outlineVariant)
    }
}

struct InfoRow: View {
    var icon: String
    var title: String
    var detail: String
    var body: some View {
        HStack(spacing: 18) {
            Image(systemName: icon).frostText(.section, weight: .semibold).frame(width: 28).foregroundStyle(B.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(B.onSurface)
                Text(detail).frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
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
            Section { Text("What the phone has switched on. Changes are made on the phone.").frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
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
            Section("Phone") {
                LabeledContent("Name", value: live.device.name)
                if let m = live.facts.model, !m.isEmpty { LabeledContent("Model", value: m) }
                if let a = live.facts.android { LabeledContent("Android", value: a) }
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
                Text(live.device.isLive ? "Live through the Bridgit relay, with the permission the phone's owner granted this device."
                     : "From the status the phone sends to its owner's account every few minutes.").frostText(.supporting)
            }
        }
        .scrollContentBackground(.hidden).background(B.surface)
        .navigationTitle("About this phone")
    }
}

/// Connects a phone to this account with the 8-character code from the phone (More › Link web view),
/// the same as bridgit.cc/myapps.
struct LinkCodeView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("On the phone, open More › Link web view and use the code it shows. Connect only your own phone.").frostText(.supporting)
                    TextField("XXXX-XXXX", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .font(.title3.monospaced())
                }
                if let error { Text(error).foregroundStyle(B.error) }
                Button(busy ? "Connecting…" : "Connect phone") {
                    Task {
                        busy = true; defer { busy = false }
                        do { let name = try await model.link(code: code); model.notice = .init(title: "Connected", message: "\(name) is now on your account."); dismiss() }
                        catch { self.error = error.localizedDescription }
                    }
                }.disabled(busy || code.filter { $0.isLetter || $0.isNumber }.count != 8)
            }
            .navigationTitle("Connect a phone")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// A QR code for installing Bridgit on an Android phone. It always encodes bridgit.cc/get/android, which the
/// website redirects (the download page for now, Google Play later), so the destination can change without
/// a new iOS build.
struct InstallAndroidView: View {
    @Environment(\.dismiss) private var dismiss
    static let link = URL(string: "https://bridgit.cc/get/android?src=ios-portal")!

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
                            .frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
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
    /// "ios-0.1.0(42)", for the backend's app_version field.
    static var short: String {
        let info = Bundle.main.infoDictionary ?? [:]
        return "ios-" + (info["CFBundleShortVersionString"] as? String ?? "?") + "(" + (info["CFBundleVersion"] as? String ?? "") + ")"
    }
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
