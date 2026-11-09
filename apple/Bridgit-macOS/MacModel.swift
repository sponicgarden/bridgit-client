import AppKit
import AuthenticationServices
import AVFoundation
import BridgitCore
import Combine
import Foundation

@MainActor
final class MacModel: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    @Published private(set) var phones: [Phone] = []
    @Published private(set) var reportedDeviceMetadata: [String: ReportedDeviceMetadata] = [:]
    @Published private(set) var applets: [AppletSummary] = []
    @Published private(set) var pairings: [Pairing] = []
    @Published private(set) var email: String?
    @Published private var relayBusy = false
    @Published private var accountBusy = false
    @Published private var pairingBusy = false
    var busy: Bool { relayBusy || accountBusy || pairingBusy }
    @Published private(set) var paused = false
    @Published private(set) var profile: DeviceProfile
    @Published var problem: String?
    @Published var pendingPairing: Pairing?
    @Published private(set) var snapshot: [String: Any] = [:]
    @Published private(set) var snapshotAt: Date?
    @Published private(set) var selectedAccountID: String?
    @Published private(set) var selectedPairingID: String?
    private var signIn: ASWebAuthenticationSession?
    private var signInAttempt = SignInAttempt()
    private var signInID: UUID?
    private let cloud: WatchClient
    private let storageEnabled: Bool
    private let relayHTTP: URLSession
    private var observations = Set<AnyCancellable>()
    private var refreshTask: Task<Void, Never>?
    private var client: RelayClient?
    private var generation = 0
    private var accountGeneration = 0

    var signedIn: Bool { cloud.session != nil }
    var signingIn: Bool { signIn != nil }
    var selectedPairing: Pairing? { pairings.first { $0.id == selectedPairingID } }

    var viewingLinkedDevice: Bool { selectedPairingID != nil || selectedAccountID != nil }
    var selectedInstallID: String? { selectedPairing?.install ?? selectedAccountID }
    var selectedDeviceName: String? { selectedPairing?.displayName ?? phones.first { $0.phone == selectedAccountID }?.displayName }
    var portalSession: String? { cloud.session }
    var portalAccountRevision: Int { accountGeneration }
    var selectedAccountApplets: [AppletSummary] {
        guard let install = selectedInstallID else { return [] }
        return applets.filter { $0.phone == install }
    }
    var remoteAppletRows: [[String: Any]] {
        snapshot["applets"] as? [[String: Any]] ?? (snapshot["applets"] as? [String: Any])?["applets"] as? [[String: Any]] ?? []
    }
    var appletPortals: [AppletLists.Portal] {
        guard let install = selectedInstallID else { return [] }
        return AppletLists.portals(install: install, account: selectedAccountApplets)
    }
    func selectAccount(_ phone: Phone) {
        refreshTask?.cancel(); generation += 1; relayBusy = false
        selectedPairingID = nil; client = nil; snapshot = [:]; snapshotAt = nil
        selectedAccountID = phone.phone
        persistSelection()
    }

    override convenience init() { self.init(cloud: WatchClient(), loadStored: true) }
    init(cloud: WatchClient, loadStored: Bool, relayHTTP: URLSession = .shared) {
        self.cloud = cloud; storageEnabled = loadStored; self.relayHTTP = relayHTTP
        profile = Self.localProfile()
        super.init()
        if loadStored {
            paused = AppleFixtureWorkspace.defaults.bool(forKey: "viewer.paused")
            cloud.session = AppleFixtureWorkspace.defaults.bool(forKey: "account.signed_out") ? nil : Keychain.get("watch-session")
            email = Keychain.get("watch-email")
            if let text = Keychain.get("relay-pairings"), let saved = try? JSONDecoder().decode([Pairing].self, from: Data(text.utf8)) { pairings = saved }
        }
        NotificationCenter.default.publisher(for: AVCaptureDevice.wasConnectedNotification)
            .merge(with: NotificationCenter.default.publisher(for: AVCaptureDevice.wasDisconnectedNotification))
            .receive(on: RunLoop.main).sink { [weak self] _ in self?.refreshLocalProfile() }.store(in: &observations)
        NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.didWakeNotification)
            .receive(on: RunLoop.main).sink { [weak self] _ in self?.refreshLocalProfile() }.store(in: &observations)
        if CommandLine.arguments.contains("-sampleData") { return }
        let selection = loadStored ? AppleFixtureWorkspace.defaults.string(forKey: "viewer.selection") : nil
        if let selection, selection == "local" { return }
        if let selection, let pairing = pairings.first(where: { "relay:" + $0.id == selection }) { select(pairing) }
        else if let selection, selection.hasPrefix("cloud:"), signedIn { selectedAccountID = String(selection.dropFirst(6)) }
    }

    func refreshLocalProfile() { profile = Self.localProfile() }
    private static func localProfile() -> DeviceProfile {
        let now = Date()
        let cameras = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera, .external], mediaType: .video, position: .unspecified).devices
        let mics = AVCaptureDevice.DiscoverySession(deviceTypes: [.microphone, .external], mediaType: .audio, position: .unspecified).devices
        return .init(profileRevision: Int(now.timeIntervalSince1970), observedAt: now, expiresAt: now.addingTimeInterval(60),
                     device: .init(installID: nil, name: LocalHostModel.deviceName, deviceType: "computer",
                                   platform: "macos", osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
                                   appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local",
                                   apiRevision: 0, services: ["view"],
                                   hardware: ["camera": cameras.isEmpty ? "absent" : "present", "microphone": mics.isEmpty ? "absent" : "present"]),
                     availability: .init(state: "online", inForeground: NSApp.isActive), capabilities: [:])
    }

    func signInWithBridgit() {
        if AppleFixtureWorkspace.enabled { problem = "Sign-in stays offline in the fixture workspace."; return }
        guard signIn == nil else { return }
        do { try DeviceOutboundPolicy.access.require() }
        catch { problem = error.localizedDescription; return }
        problem = nil
        let expectedSignIn = UUID(); signInID = expectedSignIn
        let start = signInAttempt.begin(platform: "macos")
        let session = ASWebAuthenticationSession(url: start, callback: .https(host: SignInAttempt.callbackHost, path: SignInAttempt.callbackPath(platform: "macos"))) { [weak self] url, error in
            Task { @MainActor in
                guard let self, self.signInID == expectedSignIn else { return }; self.signIn = nil; self.signInID = nil
                guard let url, Self.isSignIn(url) else {
                    self.signInAttempt.cancel()
                    if let error, (error as? ASWebAuthenticationSessionError)?.code != .canceledLogin { self.problem = "Sign-in did not finish. Please try again." }
                    return
                }
                await self.handle(url)
            }
        }
        session.presentationContextProvider = self
        signIn = session
        if !session.start() { signInID = nil; signInAttempt.cancel(); signIn = nil; problem = "The sign-in window could not open." }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor()
    }

    static func isSignIn(_ url: URL) -> Bool {
        url.scheme == "https" && url.host == SignInAttempt.callbackHost && url.path == SignInAttempt.callbackPath(platform: "macos") && url.user == nil && url.password == nil && url.port == nil
    }
    func handle(_ url: URL) async {
        if Self.isSignIn(url) {
            guard let result = signInAttempt.consume(url) else { problem = "Start sign-in from Bridgit and use its current sign-in window."; return }
            let session = signIn; signInID = nil; signIn = nil; session?.cancel()
            await adoptAccount(session: result.session, email: result.email)
            return
        }
        if let pairing = Pairing.parse(link: url) { pendingPairing = pairing }
        else { problem = "This link is not a Bridgit sign-in or device pairing link." }
    }

    func adoptAccount(session token: String, email address: String?) async {
        if storageEnabled {
            guard Keychain.set(token, for: "watch-session") else { problem = "Sign-in could not be saved."; return }
            Keychain.set(address, for: "watch-email")
            AppleFixtureWorkspace.defaults.set(false, forKey: "account.signed_out")
        }
        accountGeneration += 1; accountBusy = false
        phones = []; applets = []; selectedAccountID = nil; problem = nil
        cloud.session = token; email = address
        await refreshAccount()
    }

    func preparePairing(_ text: String) {
        guard let p = Pairing.parse(text) else { problem = "Paste a valid Bridgit pairing link or relay setup."; return }
        pendingPairing = p
    }
    func confirmPairing(_ pairing: Pairing) async {
        pairingBusy = true; defer { pairingBusy = false }
        do {
            let claimed = try await PairingClaim.claim(pairing, http: relayHTTP, outboundAccess: DeviceOutboundPolicy.access)
            try saveConfiguredPairing(claimed)
        } catch { problem = error.localizedDescription }
    }
    @discardableResult private func savePairings() -> Bool {
        guard storageEnabled else { return true }
        guard let data = try? JSONEncoder().encode(pairings) else { return false }
        return Keychain.set(String(decoding: data, as: UTF8.self), for: "relay-pairings")
    }
    func remove(_ pairing: Pairing) {
        let previous = pairings
        pairings.removeAll { $0.id == pairing.id }
        guard savePairings() else { pairings = previous; problem = "The linked-device change could not be saved."; return }
        if selectedPairingID == pairing.id { refreshTask?.cancel(); generation += 1; relayBusy = false; client = nil; selectedPairingID = nil; snapshot = [:]; snapshotAt = nil; persistSelection() }
    }
    func select(_ pairing: Pairing) {
        refreshTask?.cancel(); generation += 1; selectedPairingID = pairing.id; selectedAccountID = nil
        snapshot = [:]; snapshotAt = nil; client = RelayClient(pairing: pairing, http: relayHTTP, outboundAccess: DeviceOutboundPolicy.access)
        persistSelection()
        _ = beginRelayRefresh()
    }
    private func persistSelection() {
        if storageEnabled { AppleFixtureWorkspace.defaults.set(configuredAttachment ?? "local", forKey: "viewer.selection") }
    }
    func pause() {
        paused.toggle()
        if storageEnabled { AppleFixtureWorkspace.defaults.set(paused, forKey: "viewer.paused") }
        if paused {
            refreshTask?.cancel(); generation += 1; relayBusy = false
            accountGeneration += 1; accountBusy = false
        } else { _ = beginRelayRefresh() }
    }
    private func beginRelayRefresh() -> Task<Void, Never>? {
        guard !paused, let client else { return nil }
        refreshTask?.cancel(); generation += 1
        let current = generation
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.readSelected(client: client, generation: current)
        }
        refreshTask = task
        return task
    }
    /// A caller-filtered profile is valid only for the pairing that issued the request.
    func readSelectedAbilityProfile() async throws -> DeviceProfile {
        guard !paused, let client, let pairing = selectedPairingID else { throw RelayError.offline }
        let current = generation
        let response = try await client.get("/v1/device/profile")
        try Task.checkCancellation()
        guard generation == current, selectedPairingID == pairing else { throw CancellationError() }
        return try DeviceProfile.decode(response.body)
    }

    /// Bind the snapshot of UI intent to its pairing even if selection changes while the camera is open.
    private func cameraRelay(for pairingID: String) throws -> RelayClient {
        guard !paused, selectedPairingID == pairingID, let client else { throw CancellationError() }
        return client
    }

    func photoTargets(for pairingID: String) async throws -> (targets: [PhotoTarget], chosen: [String]) {
        try await OwnerPhotos.targets(from: cameraRelay(for: pairingID), client: "Mac", type: "computer")
    }

    func sendPhoto(_ data: Data, front: Bool?, targets: [String], to pairingID: String) async throws -> [PhotoResult] {
        let relay = try cameraRelay(for: pairingID)
        let jpeg = try LocalAppletModel.jpeg(data, maxEdge: Int(OwnerPhotos.maxEdge), quality: 0.75)
        try Task.checkCancellation()
        return try await OwnerPhotos.send(jpeg, front: front, targets: targets, to: relay, client: "Mac", type: "computer")
    }

    func refreshSelected() async {
        guard let task = beginRelayRefresh() else { return }
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }
    private func readSelected(client: RelayClient, generation current: Int) async {
        guard !Task.isCancelled, current == generation, !paused else { return }
        relayBusy = true; defer { if current == generation { relayBusy = false } }
        problem = nil
        do {
            let obj = try await client.getJSON("/v1/portal/snapshot", query: ["logs": "20", "client": "Mac", "client_type": "computer", "client_platform": "macos", "client_device": "Mac · Bridgit local"])
            guard !Task.isCancelled, current == generation else { return }
            var value = obj as? [String: Any] ?? [:]
            if let pairing = selectedPairing,
               let device = (value["home"] as? [String: Any])?["device"] as? [String: Any] {
                let metadata = ReportedDeviceMetadata(device: device)
                if !metadata.isEmpty { reportedDeviceMetadata["relay:" + pairing.deviceId] = metadata }
            }
            if var page = value["logs"] as? [String: Any] {
                let previous = snapshot["logs"] as? [String: Any] ?? [:]
                let oldEntries = previous["entries"] as? [[String: Any]] ?? []
                let newEntries = page["entries"] as? [[String: Any]] ?? []
                page["entries"] = Self.mergeDiagnosticLogs(newEntries, oldEntries)
                if oldEntries.count > newEntries.count { page["has_older"] = previous["has_older"] }
                value["logs"] = page
            }
            snapshot = value; snapshotAt = Date()
        } catch {
            guard !Task.isCancelled, current == generation else { return }
            problem = error.localizedDescription
        }
    }
    func loadOlderDiagnosticsLogs() async {
        guard !paused, let client, var page = snapshot["logs"] as? [String: Any],
              page["has_older"] as? Bool == true,
              let entries = page["entries"] as? [[String: Any]],
              let oldest = entries.compactMap({ $0["id"] as? Int }).min() else { return }
        let expected = generation
        do {
            let raw = try await client.getJSON("/v1/portal/logs", query: ["until": String(oldest), "limit": "100", "client": "Mac", "client_type": "computer", "client_platform": "macos"])
            guard expected == generation, !Task.isCancelled, !paused else { return }
            let response = raw as? [String: Any] ?? [:]
            let older = response["entries"] as? [[String: Any]] ?? []
            // Re-read after awaiting so a simultaneous snapshot refresh cannot be overwritten.
            page = snapshot["logs"] as? [String: Any] ?? page
            page["entries"] = Self.mergeDiagnosticLogs(page["entries"] as? [[String: Any]] ?? [], older)
            page["has_older"] = response["has_older"] as? Bool ?? (older.count == 100)
            snapshot["logs"] = page
        } catch {
            guard expected == generation else { return }
            page = snapshot["logs"] as? [String: Any] ?? page
            page["has_older"] = false; snapshot["logs"] = page; problem = error.localizedDescription
        }
    }
    private static func mergeDiagnosticLogs(_ first: [[String: Any]], _ second: [[String: Any]]) -> [[String: Any]] {
        var seen = Set<Int>()
        return (first + second).filter { entry in
            guard let id = entry["id"] as? Int else { return false }
            return seen.insert(id).inserted
        }.sorted { ($0["id"] as? Int ?? 0) > ($1["id"] as? Int ?? 0) }
    }

    func refreshAccount() async {
        guard signedIn, !paused else { return }
        let current = accountGeneration
        let session = cloud.session
        accountBusy = true
        defer { if current == accountGeneration { accountBusy = false } }
        problem = nil
        do {
            async let p = cloud.phones(); async let a = cloud.applets()
            let (pr, ar) = try await (p, a)
            guard !Task.isCancelled, current == accountGeneration, session == cloud.session else { return }
            phones = pr.phones; applets = ar.applets
            if selectedPairingID == nil, !phones.contains(where: { $0.phone == selectedAccountID }) { selectedAccountID = nil }
        } catch {
            guard !Task.isCancelled, current == accountGeneration, session == cloud.session else { return }
            problem = error.localizedDescription
        }
    }
    func signOut() async {
        // Clear local state before the network call can suspend. Old replies cannot restore it.
        accountGeneration += 1; accountBusy = false
        signInID = nil; signInAttempt.cancel(); signIn?.cancel(); signIn = nil
        if storageEnabled { AppleFixtureWorkspace.defaults.set(true, forKey: "account.signed_out") }
        let oldSession = cloud.session
        cloud.session = nil; email = nil; phones = []; applets = []; selectedAccountID = nil
        if storageEnabled { Keychain.set(nil, for: "watch-session"); Keychain.set(nil, for: "watch-email") }
        if let oldSession { await cloud.signOut(session: oldSession) }
    }
}


extension MacModel: ViewerConfiguration {
    var configuredCloud: WatchClient { cloud }
    func refreshConfiguredAccount() async { await refreshAccount() }
    var configuredPairings: [Pairing] { pairings }
    var configuredAccountEmail: String? { signedIn ? email : nil }
    var configuredSignedIn: Bool { signedIn }
    var configuredSigningIn: Bool { signingIn }
    var configuredViewsPaused: Bool? { paused }
    var configuredAttachment: String? {
        if let id = selectedPairingID { return "relay:" + id }
        return selectedAccountID.map { "cloud:" + $0 }
    }
    var configuredDevices: [ViewerDeviceDescription] {
        PortalDevice.list(phones: phones, pairings: pairings, metadata: reportedDeviceMetadata).map {
            .init(id: $0.id, name: $0.name, installID: $0.cloud?.phone ?? $0.pairing?.install,
                  relayDeviceID: $0.pairing?.deviceId, reportedMetadata: $0.reportedMetadata)
        }
    }
    func beginConfiguredSignIn() { signInWithBridgit() }
    func cancelConfiguredSignIn() { signInID = nil; signInAttempt.cancel(); signIn?.cancel(); signIn = nil; accountGeneration += 1 }
    func configuredSignOut() async { await signOut() }
    func saveConfiguredPairing(_ pairing: Pairing) throws {
        guard pairing.needsClaim != true else { throw DeviceAPIError(422, "pairing_claim_required", "Claim this pairing before saving it.") }
        let previous = pairings
        var value = pairing; value.localAlias = pairings.first(where: { $0.id == pairing.id })?.localAlias
        pairings.removeAll { $0.id == pairing.id }; pairings.append(value)
        guard savePairings() else { pairings = previous; throw DeviceAPIError(503, "keychain_unavailable", "The linked-device change could not be saved.") }
        select(pairing)
    }
    func removeConfiguredPairing(_ pairing: Pairing) throws {
        remove(pairing)
        guard !pairings.contains(where: { $0.id == pairing.id }) else { throw DeviceAPIError(503, "keychain_unavailable", "The linked-device change could not be saved.") }
    }
    func renameConfiguredPairing(_ pairing: Pairing, name: String?) throws {
        guard let index = pairings.firstIndex(where: { $0.id == pairing.id }) else { throw DeviceAPIError(404, "linked_device_not_found", "This link is no longer saved.") }
        let previous = pairings; pairings[index].localAlias = name
        guard savePairings() else { pairings = previous; throw DeviceAPIError(503, "keychain_unavailable", "The linked-device change could not be saved.") }
    }
    func selectConfiguredDevice(_ id: String?) throws {
        if let id, let pairing = pairings.first(where: { "relay:" + $0.id == id }) { select(pairing); return }
        if let id, let phone = phones.first(where: { "cloud:" + $0.phone == id }) { selectAccount(phone); return }
        guard id == nil || id == "local" else { throw DeviceAPIError(404, "linked_device_not_found", "Choose a known linked device.") }
        refreshTask?.cancel(); generation += 1; relayBusy = false; client = nil
        selectedPairingID = nil; selectedAccountID = nil; snapshot = [:]; snapshotAt = nil; persistSelection()
    }
    func setConfiguredViewsPaused(_ value: Bool) throws { if paused != value { pause() } }
}
