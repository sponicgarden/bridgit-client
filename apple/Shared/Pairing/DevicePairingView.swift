import BridgitCore
import Foundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import UniformTypeIdentifiers
import Vision
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Imported credentials are never authority for this device. Saving follows the real host claim.
@MainActor
final class PairingImportFlow: ObservableObject {
    @Published private(set) var candidate: Pairing?
    @Published private(set) var busy = false
    @Published private(set) var saved = false
    @Published var problem: String?
    private var claimed: Pairing?
    private var task: Task<Void, Never>?
    private var run: UUID?

    @discardableResult func review(_ text: String) -> Bool {
        guard !busy else { return false }
        guard let value = Pairing.parse(text) else {
            problem = text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("bridgit://satellite")
                ? "Satellite pairing is not available in this Apple app. Use a viewer pairing link."
                : "Use a pairing link or relay setup shared by the host owner. Account codes and sign-in links are separate."
            return false
        }
        candidate = value; claimed = nil; saved = false; problem = nil
        return true
    }
    func confirm(http: URLSession = .shared, save: @escaping (Pairing) throws -> Void) {
        guard let candidate, !busy, !saved else { return }
        let id = UUID(); run = id; busy = true; problem = nil
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let value: Pairing
                if let claimed = self.claimed { value = claimed }
                else { value = try await PairingClaim.claim(candidate, http: http, outboundAccess: DeviceOutboundPolicy.access) }
                try Task.checkCancellation()
                guard self.run == id else { return }
                // Keep an acknowledged replacement in memory if Keychain persistence fails.
                // A retry then saves that actual credential, without consuming the link again.
                self.claimed = value
                try save(value)
                self.saved = true
            } catch is CancellationError {
                if self.run == id { self.problem = "Pairing was not saved. If the code was used, ask the host owner for a new one." }
            } catch {
                if self.run == id { self.problem = error.localizedDescription }
            }
            if self.run == id { self.busy = false; self.task = nil }
        }
    }
    func reset() {
        task?.cancel(); task = nil; run = nil
        busy = false; candidate = nil; claimed = nil; saved = false; problem = nil
    }
    deinit { task?.cancel() }
}

enum PairingQRImage {
    static func read(_ data: Data, satellite: Bool = false) throws -> String {
        guard data.count <= 20 * 1024 * 1024 else { throw Failure.tooLarge }
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        try VNImageRequestHandler(data: data, options: [:]).perform([request])
        let links = Set((request.results ?? []).compactMap(\.payloadStringValue).filter { satellite ? (try? SatellitePairingLink.parse($0)) != nil : Pairing.parse($0) != nil })
        guard links.count == 1, let link = links.first else { throw satellite ? Failure.oneSatelliteCode : Failure.oneCode }
        return link
    }
    enum Failure: LocalizedError {
        case tooLarge, oneCode, oneSatelliteCode
        var errorDescription: String? {
            switch self {
            case .tooLarge: "Choose a QR image smaller than 20 MB."
            case .oneCode: "Choose an image containing one viewer pairing QR code."
            case .oneSatelliteCode: "Choose an image containing one satellite pairing QR code."
            }
        }
    }
}

/// APK 3347 has three cards and four actions. Viewer import and issuance use dedicated native authority; satellite roles remain separate.
struct DevicePairingView: View {
    let save: (Pairing) throws -> Void
    var scanner: ((@escaping (String) -> Void) -> AnyView)? = nil
    @EnvironmentObject private var local: LocalAppletModel
    @DialogDismiss private var dismiss
    @StateObject private var flow = PairingImportFlow()
    @State private var entry: Entry = .roles
    @State private var draft = ""
    @State private var chooseImage = false
    @State private var readingImage = false
    @State private var account = false
    @State private var issued: (agent: HostAgent, link: URL)?
    @State private var issuanceTask: Task<Void, Never>?
    @State private var issuing = false
    @State private var wireFormat: HostViewerPairing.WireFormat = .current
    enum Entry { case roles, paste, scan, issuer, joinSatellite, addSatellite, manageSatellites }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                WideBrandHeader()
                Spacer(minLength: 12)
                Button(flow.saved ? "Done" : "Cancel") { flow.reset(); dismiss() }
                    .frame(minHeight: 44).accessibilityIdentifier("pair.close")
            }.padding().opacity(account ? 0 : 1).accessibilityHidden(account)
                .background(B.surface.ignoresSafeArea(edges: .top))
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if flow.saved {
                        Text("Link saved").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        Text("The host controls the access granted by this link.")
                    } else if let pairing = flow.candidate {
                        review(pairing)
                    } else {
                        switch entry {
                        case .roles: roles
                        case .paste: paste
                        case .issuer: issuer
                        case .joinSatellite, .addSatellite, .manageSatellites:
                            if let host = local.host {
                                SatellitePairingPanel(host: host, mode: entry == .joinSatellite ? .join : entry == .addSatellite ? .add : .manage, scanner: scanner) { entry = .roles }
                            } else { ProgressView("Opening local satellite storage") }
                        case .scan:
                            Text("Scan the host’s pairing code").font(.title2.bold())
                            if let scanner { scanner { accept($0) }.frame(minHeight: 260, maxHeight: 420).clipShape(RoundedRectangle(cornerRadius: 16)) }
                            Button("Paste a link") { entry = .paste }
                            Button("Choose a QR image") { chooseImage = true }
                            Button("Back") { entry = .roles; flow.problem = nil }
                        }
                    }
                    if let problem = flow.problem { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(B.error).accessibilityIdentifier("pair.problem") }
                    if readingImage { ProgressView("Reading QR image") }
                }.frame(maxWidth: 700, alignment: .leading).frame(maxWidth: .infinity).padding(20)
            }
            #if os(iOS)
            .scrollDismissesKeyboard(.interactively)
            #endif
        }.tint(B.primary).background(B.surface.ignoresSafeArea())
        .onDisappear { issuanceTask?.cancel(); issuanceTask = nil; flow.reset(); draft = ""; issued = nil }
        .fileImporter(isPresented: $chooseImage, allowedContentTypes: [.image]) { result in
            switch result {
            case .success(let url):
                readingImage = true
                Task {
                    do {
                        let permitted = url.startAccessingSecurityScopedResource()
                        defer { if permitted { url.stopAccessingSecurityScopedResource() } }
                        let bytes = try Data(contentsOf: url)
                        let link = try await Task.detached { try PairingQRImage.read(bytes) }.value
                        accept(link)
                    } catch { flow.problem = error.localizedDescription }
                    readingImage = false
                }
            case .failure(let error): flow.problem = error.localizedDescription
            }
        }
        .bridgitDialog(isPresented: $account) {
            VStack(spacing: 0) {
                HStack { WideBrandHeader(); Spacer() }.padding().background(B.surface)
                DeviceWebLinkView()
            }.buildStampSurface()
        }
        .buildStampSurface()
        #if os(macOS)
        .frame(minWidth: 500, idealWidth: 660, minHeight: 560, idealHeight: 760)
        #endif
    }
    private func accept(_ text: String) { if flow.candidate == nil { _ = flow.review(text) } }
    private var roles: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Pair a device").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            SectionLabel(text: "This device joins another")
            Card {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Make this device a satellite of a hub", systemImage: "iphone.radiowaves.left.and.right").font(.headline)
                    Text("Share selected services with a hub over Tailscale.").foregroundStyle(B.onSurfaceVariant)
                    Button("Show QR code and link") { entry = .joinSatellite }.accessibilityIdentifier("pair.joinSatellite")
                }
            }
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    Label("View another device", systemImage: "display").font(.headline)
                    Text("Import the host owner’s viewer link. Access remains limited by that host’s grants.").foregroundStyle(B.onSurfaceVariant)
                    if scanner != nil { Button("Scan its code") { entry = .scan; flow.problem = nil }.accessibilityIdentifier("pair.scanViewer") }
                    Button("Paste a link or relay setup") { entry = .paste; flow.problem = nil }.accessibilityIdentifier("pair.pasteViewer")
                    Button("Choose a QR image") { chooseImage = true }.accessibilityIdentifier("pair.imageViewer")
                }
            }
            SectionLabel(text: "Another device joins this device")
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Add a device to this device", systemImage: "qrcode").font(.headline)
                    Text("Create a viewer code, or connect a satellite over Tailscale.").foregroundStyle(B.onSurfaceVariant)
                    Button("Show pairing code") { entry = .issuer; flow.problem = nil }.accessibilityIdentifier("pair.issueViewer")
                    Button("Scan or paste a satellite’s code") { entry = .addSatellite }.accessibilityIdentifier("pair.addSatellite")
                }
            }
            Button("Manage satellite links") { entry = .manageSatellites }.accessibilityIdentifier("pair.manageSatellites")
            SectionLabel(text: "Account linking")
            Text("Account codes link an installation to a web account. They are not viewer or satellite pairing keys.").foregroundStyle(B.onSurfaceVariant)
            Button("Link this device to the web") { account = true }.accessibilityIdentifier("pair.account")
        }
    }
    private var issuer: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Share this device’s Home").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let issued {
                Text("One-time viewer code").font(.headline)
                if let image = pairingImage(issued.link.absoluteString) {
                    Image(decorative: image, scale: 1).interpolation(.none).resizable().scaledToFit()
                        .frame(maxWidth: 280, maxHeight: 280).accessibilityLabel("One-time viewer pairing QR code")
                }
                Text("This code expires after 24 hours unless claimed. Once claimed, only the new viewer credential works. A code being shown does not mean a viewer has connected.")
                Text(wireFormat == .current ? "Created for current Bridgit apps." : "Created using Android APK 3347’s exact pairing protocol spelling. This does not register an older app URL scheme.").font(.callout).foregroundStyle(B.onSurfaceVariant)
                Button("Copy pairing link") {
                    #if os(macOS)
                    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(issued.link.absoluteString, forType: .string)
                    #else
                    UIPasteboard.general.string = issued.link.absoluteString
                    #endif
                }.accessibilityIdentifier("pair.copyIssued")
                Button("Revoke this viewer link", role: .destructive) {
                    Task {
                        await local.host?.remove(issued.agent)
                        if local.host?.agents.contains(where: { $0.id == issued.agent.id }) == false { self.issued = nil }
                        else { flow.problem = local.host?.problem ?? "The viewer link could not be revoked." }
                    }
                }.accessibilityIdentifier("pair.revokeIssued")
            } else {
                Text("The viewer can read this host’s native Home, control-agent status, applet names and saved Activity. It cannot change settings, run tools, read applet records or capture sensors.")
                Text("Creating a code starts this device’s outbound relay host if needed. Keep this app open and the device awake. The relay must be reachable; no system permission is requested by code creation.")
                Picker("Receiving app", selection: $wireFormat) {
                    ForEach(HostViewerPairing.WireFormat.allCases, id: \.self) { format in Text(format.label).tag(format) }
                }.disabled(issuing).accessibilityIdentifier("pair.wireFormat")
                Button("Start relay and create one-time viewer code") {
                    guard !issuing else { return }; issuing = true; flow.problem = nil
                    issuanceTask = Task {
                        defer { issuing = false; issuanceTask = nil }
                        let startedHere = local.host?.enabledIntent == false
                        do {
                            guard let host = local.host else { throw DeviceAPIError(503, "storage_unavailable", "This device’s host is not ready.") }
                            if host.state != .connected { await host.start() }
                            try Task.checkCancellation()
                            issued = try await host.issueViewerLink(format: wireFormat)
                        } catch {
                            if Task.isCancelled, startedHere { await local.host?.stop() }
                            else { flow.problem = error.localizedDescription }
                        }
                    }
                }.disabled(issuing).buttonStyle(.borderedProminent).accessibilityIdentifier("pair.createViewer")
                if issuing { ProgressView("Preparing one-time viewer code") }
            }
            Button("Back") { entry = .roles }.disabled(issuing)
        }
    }
    private func pairingImage(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8)
        guard let output = filter.outputImage else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
    private var paste: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Viewer pairing link").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            TextEditor(text: $draft).font(.body.monospaced()).frame(minHeight: 150)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
                .accessibilityLabel("Pairing link or relay setup").accessibilityIdentifier("pair.input")
            Button("Paste from clipboard") {
                #if os(macOS)
                draft = NSPasteboard.general.string(forType: .string) ?? ""
                #else
                draft = UIPasteboard.general.string ?? ""
                #endif
            }
            Button("Review link") { _ = flow.review(draft) }.buttonStyle(.borderedProminent)
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("pair.review")
            Button("Back") { entry = .roles; flow.problem = nil }
        }
    }
    private func review(_ pairing: Pairing) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Review host link").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            LabeledContent("Device", value: pairing.displayName)
            VStack(alignment: .leading, spacing: 4) {
                Text("Relay").font(.headline)
                Text(pairing.base.absoluteString).font(.callout.monospaced()).textSelection(.enabled)
            }
            Text("Only add this link if the host owner gave it to you. This does not grant access to this device.")
            if pairing.needsClaim == true { Text("The one-time code is exchanged with the host before the link is saved.").foregroundStyle(B.onSurfaceVariant) }
            if flow.busy { ProgressView("Pairing with host").accessibilityIdentifier("pair.progress") }
            Button("Add this host") { flow.confirm(save: save) }.buttonStyle(.borderedProminent)
                .disabled(flow.busy).accessibilityIdentifier("pair.confirm")
            Button("Back") { flow.reset() }.disabled(flow.busy).accessibilityIdentifier("pair.backReview")
        }
    }
}

private struct SatellitePairingPanel: View {
    enum Mode { case join, add, manage }
    @ObservedObject var host: LocalHostModel
    let mode: Mode
    let scanner: ((@escaping (String) -> Void) -> AnyView)?
    let back: () -> Void
    @State private var addresses: [TailnetAddress] = []
    @State private var address: TailnetAddress?
    @State private var port = "18471"
    @State private var name = LocalHostModel.deviceName
    @State private var camera = false
    @State private var inbox = false
    @State private var text = ""
    @FocusState private var editingCode: Bool
    @State private var reviewed: SatellitePairingLink?
    @State private var issued: SatellitePairingLink?
    @State private var completedHub: NativeHubLink?
    @State private var saved: LinkedSatellite?
    @State private var priorHubs: Set<String> = []
    @State private var agents: Set<String> = []
    @State private var manageAgents = false
    @State private var scanning = false
    @State private var chooseImage = false
    @State private var work: Task<Void, Never>?
    @State private var loaded = false
    @State private var problem: String?
    @State private var notice: String?
    @State private var hubHome: (String, SatelliteHubHome)?

    private var controlAgents: [HostAgent] { host.agents.filter { $0.viewer != true && $0.tailnetPeer == nil } }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            switch mode {
            case .join: join
            case .add: add
            case .manage: manage
            }
            if work != nil { ProgressView("Working on satellite link") }
            if let notice { Text(notice).foregroundStyle(B.onSurfaceVariant) }
            if let problem = problem ?? host.satelliteProblem { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(B.error).accessibilityIdentifier("satellite.problem") }
            Button("Back", action: back).disabled(work != nil).accessibilityIdentifier("satellite.back")
        }
        .task {
            updateAddresses()
            do { try await host.loadSatelliteLinks(); await host.loadSavedAgents(); loaded = true }
            catch { problem = error.localizedDescription }
        }
        .task(id: issued != nil) {
            while issued != nil, !Task.isCancelled {
                do {
                    try await host.loadSatelliteLinks()
                    if let hub = host.hubLinks.first(where: { !priorHubs.contains($0.id) && $0.info != nil }) {
                        completedHub = hub; issued = nil; break
                    }
                    try await Task.sleep(for: .seconds(1))
                } catch is CancellationError { break }
                catch { problem = error.localizedDescription; break }
            }
        }
        .onDisappear { work?.cancel(); work = nil; text = ""; reviewed = nil; issued = nil }
        .fileImporter(isPresented: $chooseImage, allowedContentTypes: [.image]) { result in
            switch result {
            case .success(let url): run {
                let value = try await Task.detached {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let count = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard count <= 20 * 1024 * 1024 else { throw PairingQRImage.Failure.tooLarge }
                    return try PairingQRImage.read(Data(contentsOf: url, options: .mappedIfSafe), satellite: true)
                }.value
                try Task.checkCancellation(); accept(value)
            }
            case .failure(let error): problem = error.localizedDescription
            }
        }
    }
    private func run(_ operation: @escaping () async throws -> Void) {
        guard work == nil else { return }; problem = nil; notice = nil
        work = Task {
            defer { work = nil }
            do { try await operation() }
            catch is CancellationError { }
            catch { problem = error.localizedDescription }
        }
    }
    private func updateAddresses() {
        addresses = host.availableTailnetAddresses.filter { value in reviewed.map { value.isIPv6 == $0.address.isIPv6 } ?? true }
        if address == nil || !addresses.contains(address!) {
            address = host.satelliteAddress.flatMap { addresses.contains($0) ? $0 : nil } ?? addresses.first
        }
        if let actualPort = host.satellitePort { port = String(actualPort) }
    }
    private func accept(_ value: String) {
        do { reviewed = try SatellitePairingLink.parse(value); text = ""; scanning = false; problem = nil; updateAddresses() }
        catch { problem = error.localizedDescription }
    }
    private var listener: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Tailscale listener").font(.headline)
            if let actual = host.satelliteAddress, let actualPort = host.satellitePort {
                Text("Listening on \(actual.value), port \(actualPort).")
            }
            if host.satelliteIntent {
                Button("Stop listener", role: .destructive) { run { await host.stopSatelliteListener(); updateAddresses() } }.disabled(work != nil)
            }
            if addresses.isEmpty {
                Text("No matching Tailscale address is available. Turn on Tailscale on this device, then check again.").accessibilityIdentifier("satellite.addressUnavailable")
                Button("Check again", action: updateAddresses)
            } else {
                Picker("Tailscale address", selection: $address) {
                    Text("Choose an address").tag(Optional<TailnetAddress>.none)
                    ForEach(addresses, id: \.self) { Text($0.value).tag(Optional($0)) }
                }.accessibilityIdentifier("satellite.address")
                TextField("Port", text: $port).textFieldStyle(.roundedBorder).accessibilityIdentifier("satellite.port")
            }
            Text("The listener accepts only Tailscale peers. Keep this device awake; on iPhone and iPad, keep Bridgit open.").font(.callout).foregroundStyle(B.onSurfaceVariant)
        }
    }
    private func startListener() async throws {
        guard let address, let port = UInt16(port), port > 0 else { throw DeviceAPIError(422, "tailnet_required", "Choose a local Tailscale address and valid port.") }
        try await host.startSatelliteListener(address, port: port)
    }
    private var join: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Make this device a satellite").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let completedHub {
                Text("Hub linked").font(.headline)
                Text(completedHub.info?.name ?? completedHub.agent.name)
                Text("The hub’s return link was saved on this device.")
            } else if let issued {
                if let image = qr(issued.url.absoluteString) {
                    Image(decorative: image, scale: 1).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 280, maxHeight: 280)
                        .accessibilityLabel("One-time satellite pairing QR code")
                }
                Text("This one-time code expires after 15 minutes. Keep the listener running until the hub claims it.")
                if host.satelliteAddress == nil {
                    Text("The listener is stopped. Restart it on the original address and port to use this code before it expires.")
                    listener
                    Button("Restart listener") { run { try await host.startSatelliteListener(issued.address, port: issued.port) } }.disabled(work != nil)
                }
                Button("Copy satellite link") { copy(issued.url.absoluteString) }.accessibilityIdentifier("satellite.copy")
                Button("Cancel code", role: .destructive) { run { try await host.cancelSatelliteCode(issued); self.issued = nil } }.disabled(work != nil)
                if host.hubLinks.contains(where: { !priorHubs.contains($0.id) }) { Text("Code claimed. Waiting for the hub’s return details.") }
            } else {
                Text("A hub can read this device’s status. Choose any additional services to share. A pairing code does not request camera permission or change your account.")
                TextField("Name on the hub", text: $name).textFieldStyle(.roundedBorder)
                Toggle("Allow camera capture when camera access is enabled", isOn: $camera)
                Toggle("Allow inbox cards and their replies", isOn: $inbox)
                listener
                Button("Start listener and show code") { run {
                    try await startListener()
                    try Task.checkCancellation()
                    try await host.loadSatelliteLinks(); priorHubs = Set(host.hubLinks.map(\.id))
                    var grants: Set<HostOperation> = [.deviceRead]
                    if camera { grants.insert(.cameraSnapshot) }; if inbox { grants.formUnion([.interactionsRead, .interactionsWrite]) }
                    let link = try await host.makeSatelliteCode(name: name, operations: grants)
                    if Task.isCancelled { try? await host.cancelSatelliteCode(link); throw CancellationError() }
                    issued = link
                } }.buttonStyle(.borderedProminent).disabled(address == nil || work != nil).accessibilityIdentifier("satellite.issue")
            }
        }
    }
    private var add: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Add another satellite").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let saved {
                Text("Satellite linked").font(.headline)
                Text(saved.name)
                Text("The satellite confirmed the return link. Selected control agents can use its shared endpoints.")
            } else if let reviewed {
                LabeledContent("Satellite", value: reviewed.name)
                LabeledContent("Tailscale peer", value: reviewed.address.value + ", port " + String(reviewed.port))
                Text("Only continue if the satellite’s owner shared this code. This grants the satellite a return link to this device’s Home and Activity.")
                Toggle("Allow the satellite to pause and resume this hub’s control agents", isOn: $manageAgents)
                Text("Control agents allowed to use this satellite").font(.headline)
                if controlAgents.isEmpty { Text("No control agents are configured. You can assign them later.") }
                ForEach(controlAgents) { agent in
                    Toggle(agent.name, isOn: Binding(get: { agents.contains(agent.id) }, set: { value in if value { agents.insert(agent.id) } else { agents.remove(agent.id) } }))
                }
                listener
                Button("Claim and add satellite") { run {
                    try await startListener()
                    try Task.checkCancellation()
                    saved = try await host.addSatellite(reviewed, agents: agents, allowAgentPause: manageAgents)
                    self.reviewed = nil
                } }.buttonStyle(.borderedProminent).disabled(address == nil || work != nil).accessibilityIdentifier("satellite.confirm")
                Button("Edit code") { self.reviewed = nil; updateAddresses() }.disabled(work != nil)
            } else {
                Text("On the other device, make a satellite code while Tailscale is on. Review it here before claiming anything.")
                Button("Review satellite code") { editingCode = false; accept(text) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("satellite.review")
                TextEditor(text: $text).font(.body.monospaced()).frame(minHeight: 115).autocorrectionDisabled().focused($editingCode)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .accessibilityLabel("Satellite link").accessibilityIdentifier("satellite.input")
                Button("Paste from clipboard") {
                    #if os(macOS)
                    text = NSPasteboard.general.string(forType: .string) ?? ""
                    #else
                    text = UIPasteboard.general.string ?? ""
                    #endif
                }
                if scanning, let scanner { scanner { accept($0) }.frame(minHeight: 260, maxHeight: 420).clipShape(RoundedRectangle(cornerRadius: 16)) }
                if scanner != nil { Button("Scan satellite code") { editingCode = false; scanning.toggle() }.accessibilityIdentifier("satellite.scan") }
                Button("Choose a QR image") { editingCode = false; chooseImage = true }.accessibilityIdentifier("satellite.image")
            }
        }
    }
    private var manage: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Manage satellite links").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if loaded, host.linkedSatellites.isEmpty, host.hubLinks.isEmpty { Text("No saved satellite or hub links.") }
            ForEach(host.linkedSatellites) { record in
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(record.name).font(.headline)
                        TimelineView(.periodic(from: .now, by: 15)) { context in Text(status(record.state(at: context.date))) }
                        Text("Last checked \(record.checkedAt.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(B.onSurfaceVariant)
                        Button("Refresh status") { run { try await host.refreshSatellite(record.id) } }.disabled(work != nil || record.paused)
                        Button(record.paused ? "Resume satellite" : "Pause satellite") { run { try await host.pauseSatellite(record.id, paused: !record.paused) } }.disabled(work != nil)
                        DisclosureGroup("Allowed control agents") {
                            ForEach(controlAgents) { agent in
                                Toggle(agent.name, isOn: Binding(get: { record.allowedAgents.contains(agent.id) }, set: { enabled in
                                    var selected = record.allowedAgents
                                    if enabled { selected.insert(agent.id) } else { selected.remove(agent.id) }
                                    run { try await host.setSatelliteAgents(record.id, agents: selected) }
                                })).disabled(work != nil)
                            }
                        }
                        Button("Remove satellite", role: .destructive) { run {
                            let confirmed = try await host.removeSatellite(record.id)
                            notice = confirmed ? "Satellite removed." : "Removed here. Remote unlinking was not confirmed. Remove the hub link on the satellite."
                        } }.disabled(work != nil)
                    }
                }
            }
            ForEach(host.hubLinks) { hub in
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(hub.info?.name ?? hub.agent.name).font(.headline)
                        Text(hub.info == nil ? "Waiting for the hub’s return details" : "Hub return link saved")
                        Button("Read hub Home") { run { hubHome = (hub.id, try await host.readLinkedHub(hub.id)) } }.disabled(hub.info?.localAddress == nil || work != nil)
                        if let (id, report) = hubHome, id == hub.id {
                            Text(report.name).font(.headline)
                            Text("Hub report · \(report.observedAt.formatted(date: .abbreviated, time: .shortened))").font(.caption)
                            if let agents = report.agents { Text(agents.isEmpty ? "No agents reported" : "Agents: " + agents.joined(separator: ", ")) }
                            if let applets = report.applets { Text(applets.isEmpty ? "No applets reported" : "Applets: " + applets.joined(separator: ", ")) }
                        }
                        Button("Remove hub access", role: .destructive) { run { try await host.removeHubLink(hub.id); notice = "Hub access removed from this device." } }.disabled(work != nil)
                    }
                }
            }
            listener
        }
    }
    private func status(_ value: String) -> String {
        switch value {
        case "online": "Recently reached"
        case "last_known": "Last known report"
        case "offline": "Did not answer the last check"
        case "paused": "Paused"
        case "unlinked": "Peer no longer accepts this key"
        case "blocked": "Last check could not be completed"
        default: "Not checked"
        }
    }
    private func copy(_ value: String) {
        #if os(macOS)
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(value, forType: .string)
        #else
        UIPasteboard.general.string = value
        #endif
    }
    private func qr(_ value: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8)
        guard let image = filter.outputImage else { return nil }
        return CIContext().createCGImage(image, from: image.extent)
    }
}
