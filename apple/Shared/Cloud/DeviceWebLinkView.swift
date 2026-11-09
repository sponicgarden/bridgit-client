import BridgitCore
import SwiftUI

struct EmptyAppletPages: View {
    let webLinkTitle: String
    let offerWebLink: Bool
    let showApplets: () -> Void
    let showWebLink: () -> Void

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                Text("No portals yet").font(.headline).foregroundStyle(B.primary)
                    .accessibilityAddTraits(.isHeader)
                Text("Portals for this device’s applets show here when enabled. Open Applets to install or configure an applet.")
                    .foregroundStyle(B.onSurfaceVariant)
                Button(action: showApplets) {
                    Text("Go to Applets").foregroundStyle(B.onSecondaryContainer)
                }
                .buttonStyle(.borderedProminent).tint(B.secondaryContainer)
                #if os(iOS)
                .controlSize(.large)
                #endif
                if offerWebLink {
                    Button(action: showWebLink) {
                        Text(webLinkTitle)
                        #if os(iOS)
                            .frame(minHeight: 44)
                        #endif
                    }
                }
            }
        }
    }
}

/// Link the installation that issued the code to its owner's web account.
struct DeviceWebLinkView: View {
    @DialogDismiss private var dismiss
    @State private var currentClient: InstallClient?
    @State private var confirmTurnOff = false
    @State private var turningOff = false
    @State private var disabledMessage: String?
    @State private var attempt = 0
    @State private var loading = false
    static let linkedKey = "webAccount.currentInstallationLinked"
    @AppStorage(Self.linkedKey, store: AppleFixtureWorkspace.defaults) private var linked = false
    @State private var code: InstallClient.WebLinkCode?
    @State private var manual = false
    @State private var problem: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Link this device to the web").font(.headline).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Done") { dismiss() }
                }
                Text(deviceName).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                if let disabledMessage {
                    Text(disabledMessage)
                } else if linked {
                    Label("This device is linked to your web account.", systemImage: "checkmark.circle")
                    Link("Open My applets", destination: URL(string: "https://bridgit.cc/myapps")!)
                } else {
                    Text("Open the setup link and sign in to the Bridgit account you want to use for this device.")
                    TimelineView(.periodic(from: .now, by: 1)) { tick in
                        let live = code.flatMap { tick.date < $0.expires ? $0 : nil }
                        VStack(alignment: .leading, spacing: 12) {
                            if let live { Link("Open setup link", destination: live.url) }
                            DisclosureGroup("Set up on another device", isExpanded: $manual) {
                                VStack(alignment: .leading, spacing: 12) {
                                    Text(live.map { String($0.code.prefix(4)) + "-" + $0.code.suffix(4) } ?? "––––-––––")
                                        .font(.title.monospaced()).textSelection(.enabled)
                                    if let live {
                                        Text("Works once · Expires at \(live.expires.formatted(date: .omitted, time: .shortened))")
                                        Button("Copy code") { copyCode(live.code) }
                                        ShareLink("Share setup link", item: live.url)
                                    } else if code != nil {
                                        Text("Code expired. Get a new code to link this device.")
                                    } else if !loading {
                                        Text("Get a link code to continue on another device.")
                                    }
                                }.padding(.vertical, 8)
                            }
                        }
                    }
                    if loading { ProgressView("Getting link code…") }
                    else { Button(code == nil ? "Get link code" : "Get new code") { attempt += 1 }.disabled(turningOff) }
                }
                if let problem { Text(problem).foregroundStyle(B.error) }
                DisclosureGroup("What is shared?") {
                    Text("Bridgit sends this device’s name and type to create the link, then checks whether you have used it.")
                }
                if linked || code != nil {
                    Button("Turn off web view", role: .destructive) { confirmTurnOff = true }
                        .disabled(loading || turningOff)
                }
            }
            .padding()
            .frame(maxWidth: 480, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        #if os(macOS)
        .frame(minWidth: 340, idealWidth: 440, minHeight: 260, idealHeight: 420)
        #endif
        .confirmationDialog("Turn off web view?", isPresented: $confirmTurnOff) {
            Button("Turn off web view", role: .destructive) { turningOff = true }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes this device’s web history, web applet data, photos and shared links from the server.")
        }
        .task(id: turningOff) {
            guard turningOff, let currentClient else { return }
            do {
                let pending = try await currentClient.turnOffWebView()
                linked = false
                code = nil
                problem = nil
                disabledMessage = pending ? "Web view is off. Server data removal is pending." : "Web view is off."
            } catch { if !Task.isCancelled { problem = error.localizedDescription } }
            turningOff = false
        }
        .task(id: attempt) { await requestCode() }
    }

    private var deviceName: String { LocalHostModel.deviceName }

    private func copyCode(_ code: String) {
        #if os(iOS)
        UIPasteboard.general.string = code
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(code, forType: .string)
        #endif
    }

    private func makeClient() throws -> InstallClient {
        if AppleFixtureWorkspace.enabled {
            #if DEBUG
            if AppleFixtureWorkspace.standalone,
               let raw = ProcessInfo.processInfo.environment["BRIDGIT_FIXTURE_WEB_LINK_PORT"],
               let port = UInt16(raw), port > 0 {
                return InstallClient(signer: try .ephemeral(), base: URL(string: "http://127.0.0.1:\(port)")!)
            }
            #endif
            throw DeviceAPIError(503, "offline", "Web linking is offline in this test workspace.")
        }
        let signer = try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "Bridgit") + ".install-signing.v1")
        return InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access)
    }

    @MainActor private func requestCode() async {
        problem = nil
        loading = true
        do {
            let client = try currentClient ?? makeClient()
            currentClient = client
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
            linked = try await client.webAccountLinked(appVersion: version)
            if linked {
                loading = false
                return
            }
            let metadata = DeviceTypeDescription.local
            code = nil
            let fresh = try await client.issueWebLinkCode(name: deviceName, deviceType: metadata.deviceType ?? "device", platform: metadata.platform, appVersion: version)
            try Task.checkCancellation()
            code = fresh
            loading = false
            while Date() < fresh.expires && disabledMessage == nil {
                try await Task.sleep(for: .seconds(5))
                guard disabledMessage == nil else { return }
                do {
                    let claimed = try await client.webAccountLinked(appVersion: version)
                    try Task.checkCancellation()
                    guard disabledMessage == nil, !turningOff else { continue }
                    problem = nil
                    if claimed {
                        linked = true
                        code = nil
                        return
                    }
                } catch {
                    try Task.checkCancellation()
                    if !turningOff && disabledMessage == nil {
                        problem = "Waiting to check whether this device is linked. " + error.localizedDescription
                    }
                }
            }
        } catch {
            guard !Task.isCancelled else { return }
            loading = false
            problem = error.localizedDescription
        }
    }
}

struct LinkCodeView: View {
    let connect: (String) async throws -> Void
    @DialogDismiss private var dismiss
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        #if os(macOS)
        VStack(alignment: .leading, spacing: 16) {
            Text("Connect a device").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            codeFields
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                connectButton.keyboardShortcut(.defaultAction)
            }
        }
        .padding(24).frame(width: 460).background(B.surface)
        #else
        NavigationStack {
            Form {
                Section { codeFields }
                connectButton
            }
            .navigationTitle("Connect a device")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        #endif
    }

    @ViewBuilder private var codeFields: some View {
        Text("Enter the web link code shown in Bridgit on the other device.").font(.subheadline)
        TextField("XXXX-XXXX", text: $code)
            #if os(iOS)
            .textInputAutocapitalization(.characters)
            #endif
            .autocorrectionDisabled()
            .accessibilityLabel("Device link code")
            .font(.title3.monospaced())
        if let error { Text(error).foregroundStyle(B.error) }
    }

    private var connectButton: some View {
        Button(busy ? "Connecting…" : "Connect device") {
            guard let code = ViewerConfigurationAction.phoneLinkCode(code) else { return }
            busy = true
            Task {
                defer { busy = false }
                do { try await connect(code); dismiss() }
                catch { self.error = error.localizedDescription }
            }
        }.disabled(busy || ViewerConfigurationAction.phoneLinkCode(code) == nil)
    }
}
