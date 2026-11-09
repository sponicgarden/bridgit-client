import BridgitCore
import Foundation
import SwiftUI
import UIKit

/// App-wide state: who is signed in, their devices and applets. Views read it from the environment.
@MainActor
final class AppModel: ObservableObject {
    @Published var showThisDevice = CommandLine.arguments.contains("-showThisDevice")

    enum Mode: Equatable { case signedOut, signedIn, sample }

    @Published var mode: Mode = .signedOut
    @Published var email: String?
    @Published var phones: [Phone] = []
    @Published private(set) var reportedDeviceMetadata: [String: ReportedDeviceMetadata] = [:]
    @Published var applets: [AppletSummary] = []
    @Published var loading = false
    @Published var signingIn = false
    @Published var error: String?
    @Published var lastRefresh: Date?
    @Published var notice: Notice?
    /// An observation from the currently saved pairing, never an access decision.
    func recordDeviceMetadata(_ metadata: ReportedDeviceMetadata, for device: PortalDevice) {
        guard !metadata.isEmpty, let pairing = device.pairing,
              relays.pairings.contains(where: { $0.id == pairing.id && $0.token == pairing.token }),
              reportedDeviceMetadata[device.id] != metadata else { return }
        reportedDeviceMetadata[device.id] = metadata
    }

    /// A pairing that arrived as a link: shown for confirmation before it is saved.
    @Published var pendingPairing: Pairing?

    struct Notice: Identifiable { var id = UUID(); var title: String; var message: String }

    private var lives: [String: PhoneLive] = [:]
    private var accountGeneration = 0
    private var configuredSignInTask: Task<Void, Never>?
    private var configuredSignInID: UUID?
    @Published var selectedDeviceID: String? = UserDefaults.standard.string(forKey: "device")

    /// The phone this iPhone/iPad is attached to: the portal opens on it every launch and keeps its live
    /// (relay) connection. Stored by install id and relay device id, because a phone seen only in the
    /// account snapshot (`cloud:<install>`) becomes `relay:<device>` once live access is granted.
    struct Attachment: Codable, Equatable {
        var install: String?
        var deviceId: String?
        init(_ d: PortalDevice) { install = d.cloud?.phone ?? d.pairing?.install; deviceId = d.pairing?.deviceId }
        func matches(_ d: PortalDevice) -> Bool {
            if let i = install, d.cloud?.phone == i || d.pairing?.install == i { return true }
            if let r = deviceId, d.pairing?.deviceId == r { return true }
            return false
        }
    }
    @Published private(set) var attachment: Attachment?
    private static let attachmentKey = "attached-phone"

    func isAttached(_ d: PortalDevice) -> Bool { attachment?.matches(d) == true }

    /// Attach: remember this phone and, when it is seen only through the account, ask it for live access.
    func attach(_ d: PortalDevice) {
        let a = Attachment(d)
        attachment = a
        AppleFixtureWorkspace.defaults.set(try? JSONEncoder().encode(a), forKey: Self.attachmentKey)
        if !d.isLive, let phone = d.cloud { startLiveAccess(for: phone) }
    }

    func detach() {
        attachment = nil
        AppleFixtureWorkspace.defaults.removeObject(forKey: Self.attachmentKey)
    }

    /// Live access requests in flight, by install id, and their latest status line. Kept here (not in a
    /// view) so a request started from the phone details keeps going after that screen closes.
    @Published private(set) var requestingAccess: Set<String> = []
    @Published var accessStatus: [String: String] = [:]
    private var accessTasks: [String: Task<Void, Never>] = [:]
    private var accessRequestIDs: [String: UUID] = [:]
    private let accessPollNanoseconds: UInt64

    func startLiveAccess(for phone: Phone) {
        let id = phone.phone
        guard accessTasks[id] == nil else { return }
        if isSample { accessStatus[id] = "Sample data: no live access request is sent."; return }
        requestingAccess.insert(id)
        let operation = UUID(); accessRequestIDs[id] = operation
        accessTasks[id] = Task { [weak self] in
            await self?.requestLiveAccess(for: phone) { message in
                if self?.accessRequestIDs[id] == operation { self?.accessStatus[id] = message }
            }
            if self?.accessRequestIDs[id] == operation {
                self?.accessTasks[id] = nil; self?.accessRequestIDs[id] = nil
                self?.requestingAccess.remove(id)
            }
        }
    }

    func stopLiveAccess(for phone: Phone) {
        accessTasks[phone.phone]?.cancel()
        accessTasks[phone.phone] = nil; accessRequestIDs[phone.phone] = nil
        requestingAccess.remove(phone.phone)
        accessStatus[phone.phone] = nil
    }

    /// One PhoneLive per phone, kept across redraws; rebuilt only when its pairing changes.
    func live(for d: PortalDevice) -> PhoneLive {
        if let l = lives[d.id], l.device.pairing == d.pairing {
            if l.device != d { l.update(d) }
            return l
        }
        let l = PhoneLive(device: d, model: self)
        lives[d.id] = l
        return l
    }

    let cloud: WatchClient
    let relays: RelayStore
    private let signIn = SignIn()

    private static let sessionKey = "watch-session"
    private static let emailKey = "watch-email"

    init(cloud: WatchClient? = nil, relays: RelayStore? = nil, loadStored: Bool = true, accessPollNanoseconds: UInt64 = 4_000_000_000) {
        self.cloud = cloud ?? WatchClient(); self.relays = relays ?? RelayStore()
        self.accessPollNanoseconds = accessPollNanoseconds
        if !loadStored { return }
        if AppleFixtureWorkspace.enabled {
            if !AppleFixtureWorkspace.standalone { enterSample() }
            return
        }
        if let data = AppleFixtureWorkspace.defaults.data(forKey: Self.attachmentKey) {
            attachment = try? JSONDecoder().decode(Attachment.self, from: data)
        }
        if CommandLine.arguments.contains("-sampleData") {
            enterSample(); return
        }
        if !AppleFixtureWorkspace.defaults.bool(forKey: "account.signed_out"), let s = Keychain.get(Self.sessionKey) {
            self.cloud.session = s
            email = Keychain.get(Self.emailKey)
            mode = .signedIn
        }
    }

    var isSample: Bool { mode == .sample }

    func signInWithBridgit() async {
        if AppleFixtureWorkspace.enabled { error = "Sign-in stays offline in the fixture workspace."; return }
        guard !signingIn else { return }
        accountGeneration += 1
        let current = accountGeneration
        signingIn = true
        defer { if current == accountGeneration { signingIn = false } }
        error = nil
        do {
            let r = try await signIn.run()
            guard current == accountGeneration, !Task.isCancelled else { return }
            await adopt(session: r.session, email: r.email)
        } catch is CancellationError {
        } catch {
            if current == accountGeneration { self.error = error.localizedDescription }
        }
    }

    /// Called only after the pending native sign-in has validated its callback.
    func adopt(session: String, email: String?) async {
        guard Keychain.set(session, for: Self.sessionKey) else { error = "Sign-in could not be saved."; return }
        Keychain.set(email, for: Self.emailKey)
        AppleFixtureWorkspace.defaults.set(false, forKey: "account.signed_out")
        accountGeneration += 1; signingIn = false
        cancelAccessRequests()
        phones = []; applets = []; lives = [:]; lastRefresh = nil
        cloud.session = session
        self.email = email
        mode = .signedIn
        await refresh()
    }

    func handle(url: URL) {
        if url.scheme == "bridgit", url.host == "device", url.path.isEmpty,
           url.user == nil, url.password == nil, url.port == nil {
            showThisDevice = true; return
        }
        if url.scheme == "https", url.host == SignInAttempt.callbackHost, url.path == SignInAttempt.callbackPath(platform: "ios") {
            if !signIn.receive(url) { error = "Start sign-in from Bridgit and use its current sign-in window." }
            return
        }
        if let p = Pairing.parse(link: url) { pendingPairing = p; return }
        notice = Notice(title: "Link not recognised", message: "Open a fresh Bridgit pairing link or scan a new code from the device owner.")
    }

    func addPairing(_ p: Pairing) {
        guard p.needsClaim != true else {
            // Single-use link: swap the link's one-time token for one made here before saving anything.
            Task {
                do {
                    addPairing(try await PairingClaim.claim(p, outboundAccess: DeviceOutboundPolicy.access))
                } catch {
                    notice = Notice(title: "Couldn't add \(p.name)", message: error.localizedDescription)
                }
            }
            return
        }
        do { try saveConfiguredPairing(p) } catch { notice = Notice(title: "Couldn’t save the link", message: error.localizedDescription); return }
        lives = lives.filter { $0.key != "relay:" + p.deviceId }
        notice = Notice(title: "Added \(p.name)", message: "Checking the phone now. The phone's owner can remove this access on the phone at any time.")
    }

    func removePairing(_ p: Pairing) {
        do { try removeConfiguredPairing(p) } catch { notice = Notice(title: "Couldn’t remove the link", message: error.localizedDescription) }
    }

    /// Automatic revocation cleanup must not remove a replacement credential.
    func removePairingIfCurrent(_ pairing: Pairing) {
        guard relays.pairings.contains(where: { $0.id == pairing.id && $0.token == pairing.token && $0.base == pairing.base }) else { return }
        removePairing(pairing)
    }

    /// Asks a phone on this account for live access and waits (up to 10 minutes) for its owner to tap Allow.
    func requestLiveAccess(for phone: Phone, progress: @escaping (String) -> Void) async {
        let current = accountGeneration, session = cloud.session
        func isCurrent() -> Bool { current == accountGeneration && session == cloud.session && mode == .signedIn && !Task.isCancelled }
        do {
            guard isCurrent() else { return }
            let name = LocalHostModel.deviceName
            let r = try await cloud.requestPortalAccess(phone: phone.phone, deviceName: name)
            guard isCurrent() else { return }
            progress("Waiting for someone to tap Allow on \(phone.displayName)…")
            let until = Date().addingTimeInterval(600)
            while Date() < until, !Task.isCancelled {
                try await Task.sleep(nanoseconds: accessPollNanoseconds)
                guard isCurrent() else { return }
                let response = try await cloud.portalPairing(phone: phone.phone, request: r.request)
                guard isCurrent() else { return }
                guard let reply = response else { continue }
                if reply.status == "denied" { progress("The phone's owner declined."); return }
                if reply.status == "expired" { progress("The request expired before anyone answered on the phone. Ask again."); return }
                if reply.status == "pending" { continue }
                if let pairing = reply.pairing(install: phone.phone) {
                    let claimed = try await PairingClaim.claim(pairing, outboundAccess: DeviceOutboundPolicy.access)
                    guard isCurrent() else { return }
                    addPairing(claimed); progress(""); return
                }
            }
            if isCurrent() { progress("No answer from the phone. Try again when someone is near it.") }
        } catch let e as APIError where e.code == "phone_outdated" {
            guard isCurrent() else { return }
            // The server's line is already short ("Not seen since 2 Nov, on an old Bridgit. Probably gone: remove it.").
            progress(e.message)
        } catch let e as APIError where e.status == 404 && e.code == "not_found" {
            guard isCurrent() else { return }
            progress("This phone's Bridgit doesn't support live access requests yet. Update Bridgit on the phone, or pair with its QR code.")
        } catch {
            if isCurrent() { progress(error.localizedDescription) }
        }
    }

    /// Removes a phone that looks gone from the account, then reloads the list.
    func removePhone(_ phone: Phone) async {
        if mode == .sample { phones.removeAll { $0.phone == phone.phone }; return }
        guard mode == .signedIn, let session = cloud.session else { return }
        let generation = accountGeneration
        func isCurrent() -> Bool { mode == .signedIn && cloud.session == session && generation == accountGeneration }
        do {
            let pending = try await cloud.unlink(phone: phone.phone)
            guard isCurrent() else { return }
            notice = Notice(title: "Removed " + phone.displayName,
                            message: pending == true ? "Bridgit is still deleting its stored account activity." : "To reconnect it, use a new code from the phone.")
            await refresh()
        } catch {
            if isCurrent() { self.error = error.localizedDescription }
        }
    }

    func signOut() async {
        accountGeneration += 1; signingIn = false
        cancelConfiguredSignIn()
        let old = mode == .signedIn ? cloud.session : nil
        AppleFixtureWorkspace.defaults.set(true, forKey: "account.signed_out")
        Keychain.set(nil, for: Self.sessionKey); Keychain.set(nil, for: Self.emailKey)
        cloud.session = nil; email = nil; phones = []; applets = []; lives = [:]
        cancelAccessRequests()
        mode = .signedOut; loading = false
        if let old { await cloud.signOut(session: old) }
    }

    private func cancelAccessRequests() {
        for task in accessTasks.values { task.cancel() }
        accessTasks = [:]; accessRequestIDs = [:]; requestingAccess = []; accessStatus = [:]
    }

    func enterSample() {
        mode = .sample
        email = "sample@bridgit.cc"
        phones = SampleData.phones
        applets = SampleData.applets
        #if DEBUG
        if AppleFixtureWorkspace.deviceOSFacts { phones += SampleData.operatingSystemPeers }
        if AppleFixtureWorkspace.deviceIdentity {
            phones.append(Phone(phone: "bgi_fixture_unknown", name: "Workshop unit", devices: nil,
                agent_history: nil, linked_at: SampleData.now, last_push_at: nil, status: nil,
                status_at: nil, dog_at: nil, dog_photo_at: nil))
        }
        if AppleFixtureWorkspace.enabled && CommandLine.arguments.contains("-fixturePortalPicker") {
            applets.append(AppletSummary(phone: "bgi_sample_kitchen", applet: "pkg.alpha-test", friendly_name: "Alpha Test"))
        }
        #endif
        lastRefresh = Date()
    }

    func refresh() async {
        guard mode == .signedIn else { lastRefresh = Date(); return }
        let current = accountGeneration, session = cloud.session
        loading = true
        defer { if current == accountGeneration { loading = false } }
        do {
            async let p = cloud.phones()
            async let a = cloud.applets()
            let (pr, ar) = try await (p, a)
            guard current == accountGeneration, session == cloud.session, mode == .signedIn, !Task.isCancelled else { return }
            phones = pr.phones
            if let e = pr.email { email = e }
            applets = ar.applets
            error = nil
            lastRefresh = Date()
        } catch let e as APIError where e.isSignedOut {
            guard current == accountGeneration, session == cloud.session else { return }
            error = "Your sign-in expired. Please sign in again."
            await signOut()
        } catch {
            if current == accountGeneration, session == cloud.session { self.error = error.localizedDescription }
        }
    }

    func events(for phone: Phone, since: Int?) async throws -> [WatchEvent] {
        if isSample { return since == nil ? SampleData.events : [] }
        return try await cloud.events(phone: phone.phone, since: since).events
    }

    func link(code: String) async throws -> String {
        guard mode == .signedIn, let session = cloud.session else {
            throw DeviceAPIError(409, "account_sign_in_required", "Sign in to connect a phone.")
        }
        guard let clean = ViewerConfigurationAction.phoneLinkCode(code) else {
            throw DeviceAPIError(422, "invalid_code", "Enter the eight-character code shown on your phone.")
        }
        let generation = accountGeneration
        func checkAccount() throws {
            try Task.checkCancellation()
            guard mode == .signedIn, generation == accountGeneration, session == cloud.session else {
                throw DeviceAPIError(409, "account_changed", "The signed-in account changed.")
            }
        }
        let preview = try await cloud.linkPreview(code: clean)
        try checkAccount()
        try await cloud.link(code: clean)
        try checkAccount()
        await refresh()
        try checkAccount()
        return preview.name ?? "Device"
    }
}


extension AppModel: ViewerConfiguration {
    var configuredCloud: WatchClient { cloud }
    func refreshConfiguredAccount() async { await refresh() }
    var configuredPairings: [Pairing] { relays.pairings }
    var configuredAccountEmail: String? { mode == .signedIn ? email : nil }
    var configuredSignedIn: Bool { mode == .signedIn }
    var configuredSigningIn: Bool { signingIn || configuredSignInTask != nil }
    var configuredViewsPaused: Bool? { nil }
    var configuredAttachment: String? {
        let all = PortalDevice.list(phones: phones, pairings: relays.pairings)
        return all.first(where: isAttached)?.id
    }
    var configuredDevices: [ViewerDeviceDescription] {
        PortalDevice.list(phones: phones, pairings: relays.pairings, metadata: reportedDeviceMetadata).map { .init(id: $0.id, name: $0.name, installID: $0.cloud?.phone ?? $0.pairing?.install, relayDeviceID: $0.pairing?.deviceId, reportedMetadata: $0.reportedMetadata) }
    }
    func beginConfiguredSignIn() {
        guard configuredSignInTask == nil, !signingIn else { return }
        let id = UUID(); configuredSignInID = id
        configuredSignInTask = Task { [weak self] in
            await self?.signInWithBridgit()
            if self?.configuredSignInID == id { self?.configuredSignInTask = nil; self?.configuredSignInID = nil }
        }
    }
    func cancelConfiguredSignIn() {
        configuredSignInTask?.cancel(); configuredSignInTask = nil; configuredSignInID = nil
        signIn.cancel(); accountGeneration += 1; signingIn = false
    }
    func configuredSignOut() async { await signOut() }
    func saveConfiguredPairing(_ pairing: Pairing) throws {
        guard pairing.needsClaim != true else { throw DeviceAPIError(422, "pairing_claim_required", "Claim this pairing before saving it.") }
        var next = relays.pairings, value = pairing
        value.localAlias = next.first(where: { $0.id == pairing.id })?.localAlias
        next.removeAll { $0.id == pairing.id }; next.append(value)
        try relays.setChecked(next); lives["relay:" + pairing.deviceId] = nil; selectedDeviceID = "relay:" + pairing.deviceId
    }
    func removeConfiguredPairing(_ pairing: Pairing) throws {
        try relays.setChecked(relays.pairings.filter { $0.id != pairing.id }); lives["relay:" + pairing.deviceId] = nil
        if attachment?.deviceId == pairing.deviceId { detach() }
        if selectedDeviceID == "relay:" + pairing.deviceId { selectedDeviceID = PortalDevice.localID }
    }
    func renameConfiguredPairing(_ pairing: Pairing, name: String?) throws {
        var next = relays.pairings
        guard let index = next.firstIndex(where: { $0.id == pairing.id }) else { throw DeviceAPIError(404, "linked_device_not_found", "This link is no longer saved.") }
        next[index].localAlias = name; try relays.setChecked(next)
    }
    func selectConfiguredDevice(_ id: String?) throws {
        guard let id, id != PortalDevice.localID else { detach(); selectedDeviceID = PortalDevice.localID; return }
        guard let device = PortalDevice.list(phones: phones, pairings: relays.pairings).first(where: { $0.id == id }) else { throw DeviceAPIError(404, "linked_device_not_found", "Choose a known linked device.") }
        attach(device); selectedDeviceID = id
    }
    func setConfiguredViewsPaused(_ paused: Bool) throws { throw DeviceAPIError(422, "unsupported_setting", "This build has no global linked-view pause control.") }
}
