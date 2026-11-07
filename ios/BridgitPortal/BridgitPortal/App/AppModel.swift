import Foundation
import SwiftUI
import UIKit
import UserNotifications
import CryptoKit
import BridgitAppletRuntime

/// App-wide state: who is signed in, their devices and applets. Views read it from the environment.
@MainActor
final class AppModel: ObservableObject {
    enum Mode: Equatable { case signedOut, signedIn, sample }

    @Published private(set) var librarySessionGeneration = UUID()
    @Published var mode: Mode = .signedOut
    @Published var email: String?
    @Published var phones: [Phone] = []
    @Published var applets: [AppletSummary] = []
    @Published var loading = false
    @Published var signingIn = false
    @Published var error: String?
    @Published var lastRefresh: Date?
    @Published var notice: Notice?
    /// A pairing that arrived as a link: shown for confirmation before it is saved.
    @Published var pendingSettings: URL?
    @Published var pendingPairing: Pairing?
    /// A name and code that arrived as a link or QR code: opens Link a Bridgit, filled in.
    @Published var pendingLinkOffer: LinkOffer?

    struct Notice: Identifiable { var id = UUID(); var title: String; var message: String }

    private var lives: [String: PhoneLive] = [:]

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
        UserDefaults.standard.set(try? JSONEncoder().encode(a), forKey: Self.attachmentKey)
        if !d.isLive, let phone = d.cloud { startLiveAccess(for: phone) }
    }

    func detach() {
        attachment = nil
        UserDefaults.standard.removeObject(forKey: Self.attachmentKey)
    }

    /// Live access requests in flight, by install id, and their latest status line. Kept here (not in a
    /// view) so a request started from the phone details keeps going after that screen closes.
    @Published private(set) var requestingAccess: Set<String> = []
    @Published var accessStatus: [String: String] = [:]
    private var accessTasks: [String: Task<Void, Never>] = [:]

    func startLiveAccess(for phone: Phone) {
        let id = phone.phone
        guard accessTasks[id] == nil else { return }
        if isSample { accessStatus[id] = "Sample data: no live access request is sent."; return }
        requestingAccess.insert(id)
        accessTasks[id] = Task { [weak self] in
            await self?.requestLiveAccess(for: phone) { self?.accessStatus[id] = $0 }
            self?.accessTasks[id] = nil
            self?.requestingAccess.remove(id)
        }
    }

    func stopLiveAccess(for phone: Phone) {
        accessTasks[phone.phone]?.cancel()
        accessTasks[phone.phone] = nil
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

    // Bind the verified server subject to this session so an offline relaunch can
    // open its local records. Display email and callback parameters confer no access.
    private func libraryBinding(_ session: String) -> String {
        "library-" + SHA256.hash(data: Data(session.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func localApplets() async throws -> LocalAppletLibrary {
        guard mode == .signedIn, let session = cloud.session else { throw AppletRuntimeError.revoked }
        let binding = libraryBinding(session)
        let account: String
        if let saved = Keychain.get(binding) {
            account = saved
        } else {
            account = try await cloud.libraryAccount()
            guard mode == .signedIn, session == cloud.session else { throw AppletRuntimeError.revoked }
            Keychain.set(account, for: binding)
        }
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try LocalAppletLibrary(database: root.appendingPathComponent("AppletLibrary/installed.sqlite"), account: account)
    }

    let cloud = WatchClient()
    let relays: RelayStore
    /// This device's Device Domain Name (bridgit.cc/names).
    let names = DeviceNameStore()
    /// Linked Bridgits (docs/spec-linked-bridgits.md, Apple user role).
    let links: LinkStore
    private let introductionDelegate = IntroductionNotificationDelegate()
    private let signIn = SignIn()

    private static let sessionKey = "watch-session"
    private static let emailKey = "watch-email"

    init() {
        let relays = RelayStore()
        self.relays = relays
        links = LinkStore(relays: relays, wire: RelayLinkWire(backend: { TeamBackend(identity: try DeviceIdentity.load()) }))
        links.deviceName = { [names] in names.name ?? UIDevice.current.name }
        links.notify = { IntroductionNotifications.post($0) }
        links.reconcile()
        IntroductionNotifications.register()
        UNUserNotificationCenter.current().delegate = introductionDelegate
        introductionDelegate.onAnswer = { [weak self] id, allow in Task { await self?.answerIntroduction(id, allow: allow) } }
        if let data = UserDefaults.standard.data(forKey: Self.attachmentKey) {
            attachment = try? JSONDecoder().decode(Attachment.self, from: data)
        }
        if CommandLine.arguments.contains("-sampleData") {
            enterSample(); return
        }
        if let s = Keychain.get(Self.sessionKey) {
            cloud.session = s
            email = Keychain.get(Self.emailKey)
            mode = .signedIn
        }
    }

    var isSample: Bool { mode == .sample }

    func signInWithBridgit() async {
        guard !signingIn else { return }
        signingIn = true
        defer { signingIn = false }
        error = nil
        do {
            let r = try await signIn.run()
            await adopt(session: r.session, email: r.email)
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Also used when the app is opened from a bridgitportal://signin link.
    func adopt(session: String, email: String?) async {
        librarySessionGeneration = UUID()
        cloud.session = session
        Keychain.set(session, for: Self.sessionKey)
        Keychain.set(email, for: Self.emailKey)
        self.email = email
        mode = .signedIn
        await refresh()
    }

    func handle(url: URL) {
        if let target = AppletLists.settingsTarget(url) { pendingSettings = target; return }
        if let r = SignIn.parse(url) { Task { await adopt(session: r.session, email: r.email) }; return }
        if let p = Pairing.parse(link: url) { pendingPairing = p; return }
        if let o = LinkOffer.parse(url.absoluteString) { pendingLinkOffer = o; return }
        notice = Notice(title: "Link not recognised", message: "Open a fresh pairing link or QR code from Bridgit on the phone.")
    }

    func addPairing(_ p: Pairing) {
        guard p.needsClaim != true else {
            // Single-use link: swap the link's one-time token for one made here before saving anything.
            Task {
                do {
                    addPairing(try await PairingClaim.claim(p))
                } catch {
                    notice = Notice(title: "Couldn't add \(p.name)", message: error.localizedDescription)
                }
            }
            return
        }
        relays.add(p)
        links.reconcile()
        lives = lives.filter { $0.key != "relay:" + p.deviceId }
        notice = Notice(title: "Added \(p.name)", message: "Checking the phone now. The phone's owner can remove this access on the phone at any time.")
    }

    func removePairing(_ p: Pairing) {
        relays.remove(p)
        links.reconcile()
        lives["relay:" + p.deviceId] = nil
    }

    /// What this device says about itself when it claims a link; nil until it has a Device Domain Name.
    var linkSelf: LinkSelf? {
        guard let name = names.name else { return nil }
        if isSample { return LinkSelf(name: name, displayName: UIDevice.current.name, install: "bgi_sample_this_device", platform: DeviceKind.platform.platform, deviceType: DeviceKind.platform.deviceType) }
        guard let id = try? DeviceIdentity.load().id else { return nil }
        return LinkSelf(name: name, displayName: UIDevice.current.name, install: id, platform: DeviceKind.platform.platform, deviceType: DeviceKind.platform.deviceType)
    }

    func claimLink(_ offer: LinkOffer) async throws -> LinkedBridgit {
        let l = try await links.claim(offer, me: linkSelf)
        lives = lives.filter { $0.key != "relay:" + l.relayDeviceId }
        return l
    }

    func answerIntroduction(_ id: String, allow: Bool) async {
        do { try await links.answer(id, allow: allow, me: linkSelf) }
        catch { notice = Notice(title: "Couldn't link", message: error.localizedDescription) }
    }

    /// Asks a phone on this account for live access and waits (up to 10 minutes) for its owner to tap Allow.
    func requestLiveAccess(for phone: Phone, progress: @escaping (String) -> Void) async {
        do {
            let name = UIDevice.current.name
            let r = try await cloud.requestPortalAccess(phone: phone.phone, deviceName: name)
            progress("Waiting for someone to tap Allow on \(phone.displayName)…")
            let until = Date().addingTimeInterval(600)
            while Date() < until, !Task.isCancelled {
                try await Task.sleep(nanoseconds: 4_000_000_000)
                guard let reply = try await cloud.portalPairing(phone: phone.phone, request: r.request) else { continue }
                if reply.status == "denied" { progress("The phone's owner declined."); return }
                if reply.status == "expired" { progress("The request expired before anyone answered on the phone. Ask again."); return }
                if reply.status == "pending" { continue }
                if let p = reply.pairing(install: phone.phone) { addPairing(p); progress(""); return }
            }
            progress("No answer from the phone. Try again when someone is near it.")
        } catch let e as APIError where e.code == "phone_outdated" {
            // The server's line is already short ("Not seen since 2 Nov, on an old Bridgit. Probably gone: remove it.").
            progress(e.message)
        } catch let e as APIError where e.status == 404 && e.code == "not_found" {
            progress("This phone's Bridgit doesn't support live access requests yet. Update Bridgit on the phone, or pair with its QR code.")
        } catch {
            progress(error.localizedDescription)
        }
    }

    /// Removes a phone that looks gone from the account, then reloads the list.
    func removePhone(_ phone: Phone) async {
        if mode == .sample { phones.removeAll { $0.phone == phone.phone }; return }
        do {
            try await cloud.unlink(phone: phone.phone)
            notice = Notice(title: "Removed \(phone.displayName)", message: "If it comes back, link it again from Bridgit on the phone.")
            await refresh()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func signOut() async {
        if let session = cloud.session { Keychain.set(nil, for: libraryBinding(session)) }
        if mode == .signedIn { await cloud.signOut() }
        Keychain.set(nil, for: Self.sessionKey)
        Keychain.set(nil, for: Self.emailKey)
        librarySessionGeneration = UUID()
        cloud.session = nil
        email = nil
        if mode == .sample { links.leaveSample(); names.reloadCached() }
        phones = []
        applets = []
        lives = [:]
        mode = .signedOut
    }

    func enterSample() {
        librarySessionGeneration = UUID()
        mode = .sample
        email = "sample@bridgit.cc"
        phones = SampleData.phones
        applets = SampleData.applets
        names.setSampleName("rahuls-ipad")
        let fixture = FixtureLinkWire()
        Task { await fixture.addOffer(name: "porch-cam", code: "K7QX3MPA"); await fixture.addOffer(name: "living-tv", code: "4M9R2KQZ") }
        links.loadSample(SampleData.linkSample, wire: fixture)
        lastRefresh = Date()
    }

    func refresh() async {
        guard mode == .signedIn else { lastRefresh = Date(); return }
        loading = true
        defer { loading = false }
        do {
            async let p = cloud.phones()
            async let a = cloud.applets()
            let (pr, ar) = try await (p, a)
            phones = pr.phones
            if let e = pr.email { email = e }
            applets = ar.applets
            error = nil
            lastRefresh = Date()
        } catch let e as APIError where e.isSignedOut {
            await signOut()
            error = "Your sign-in expired. Please sign in again."
        } catch {
            self.error = error.localizedDescription
        }
    }

    func events(for phone: Phone, since: Int?) async throws -> [WatchEvent] {
        if isSample { return since == nil ? SampleData.events : [] }
        return try await cloud.events(phone: phone.phone, since: since).events
    }

    func link(code: String) async throws -> String {
        let clean = code.uppercased().filter { $0.isLetter || $0.isNumber }
        let preview = try await cloud.linkPreview(code: clean)
        try await cloud.link(code: clean)
        await refresh()
        return preview.name ?? "Device"
    }
}
