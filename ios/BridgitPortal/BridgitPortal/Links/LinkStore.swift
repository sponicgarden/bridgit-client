import CryptoKit
import Foundation

/// This device's links, their grants log and waiting introductions (docs/spec-linked-bridgits.md, P3).
/// Relay tokens stay in RelayStore (Keychain); this store keeps only names, directions and the log.
@MainActor
final class LinkStore: ObservableObject {
    struct Pending: Codable, Equatable {
        var direction: String
        var until: Date
    }

    private struct State: Codable {
        var links: [LinkedBridgit] = []
        var log: [LinkEvent] = []
        var pending: [String: Pending] = [:]
        var retiring: [String: String] = [:]
        var introductions: [Introduction] = []
    }

    @Published private(set) var links: [LinkedBridgit] = []
    @Published private(set) var log: [LinkEvent] = []
    /// Disconnects inside their Undo window (LNK-26), by link ID. Access is already cut.
    @Published private(set) var pending: [String: Pending] = [:]
    /// Disconnected here, not yet told to the far device (LNK-30): link ID → direction.
    @Published private(set) var retiring: [String: String] = [:]
    @Published private(set) var introductions: [Introduction] = []

    static let undoWindow: TimeInterval = 10
    static let logLimit = 500
    private static let stateKey = "linked-bridgits-v1"

    let relays: RelayStore
    private var wire: LinkWire
    private var realWire: LinkWire
    /// Sample data: nothing is saved and the wire answers from memory.
    private(set) var isSample = false
    private let defaults: UserDefaults?
    private let window: TimeInterval
    /// This device's name for the log: its Device Domain Name, else the system name.
    var deviceName: () -> String = { "this device" }
    /// How a new introduction is announced (a local notification; nothing in tests).
    var notify: (Introduction) -> Void = { _ in }

    init(relays: RelayStore, wire: LinkWire, defaults: UserDefaults? = .standard, undoWindow: TimeInterval = LinkStore.undoWindow) {
        self.relays = relays
        self.wire = wire
        self.realWire = wire
        self.defaults = defaults
        self.window = undoWindow
        load()
    }

    private func load() {
        let s = defaults?.data(forKey: Self.stateKey).flatMap { try? JSONDecoder().decode(State.self, from: $0) } ?? State()
        links = s.links; log = s.log; pending = s.pending; retiring = s.retiring; introductions = s.introductions
    }

    private func save() {
        guard let defaults, !isSample else { return }
        let s = State(links: links, log: Array(log.suffix(Self.logLimit)), pending: pending, retiring: retiring, introductions: introductions)
        if let d = try? JSONEncoder().encode(s) { defaults.set(d, forKey: Self.stateKey) }
    }

    func link(_ id: String) -> LinkedBridgit? { links.first { $0.id == id } }
    func pairing(for link: LinkedBridgit) -> Pairing? {
        isSample ? SampleData.pairings.first { $0.deviceId == link.relayDeviceId } : relays.pairings.first { $0.deviceId == link.relayDeviceId }
    }
    func events(for link: LinkedBridgit) -> [LinkEvent] { log.filter { $0.linkId == link.id }.reversed() }

    /// The pairings the viewer may use now: not paused, not being disconnected.
    func usable(_ pairings: [Pairing]) -> [Pairing] {
        pairings.filter { p in
            guard let l = links.first(where: { $0.relayDeviceId == p.deviceId }) else { return true }
            return l.out.state == .on && pending[l.id] == nil && retiring[l.id] == nil
        }
    }

    // MARK: Migration (LNK-55)

    /// Local ID for a Portal pairing made before links: stable, never sent as if it were the phone's own.
    nonisolated static func localId(relayDevice: String) -> String {
        "lnk_local_" + SHA256.hash(data: Data(relayDevice.utf8)).prefix(10).map { String(format: "%02x", $0) }.joined()
    }

    /// Each Portal pairing without a link becomes a link whose `out` direction is `view`, exactly its current
    /// permissions: same token, routes and quota; nothing re-pairs. Links whose pairing is gone are dropped.
    func reconcile() {
        guard !isSample else { return }
        var changed = false
        for p in relays.pairings where !links.contains(where: { $0.relayDeviceId == p.deviceId }) {
            let id = p.linkId ?? Self.localId(relayDevice: p.deviceId)
            let out = LinkDirection(state: .on, services: ["view"], grantedBy: Granter(kind: .migrated), grantedAt: p.addedAt)
            let l = LinkedBridgit(id: id, name: p.name, platform: "android", deviceType: "phone", relayDeviceId: p.deviceId,
                                  out: out, migrated: p.linkId == nil, linkRoutes: p.linkId != nil, linkedAt: p.addedAt)
            links.append(l)
            record(l, "out", "migrated", after: "view", granter: Granter(kind: .migrated), at: p.addedAt, sent: true)
            changed = true
        }
        let held = Set(relays.pairings.map(\.deviceId))
        let before = links.count
        links.removeAll { !held.contains($0.relayDeviceId) && retiring[$0.id] == nil }
        if links.count != before || changed { save() }
        // A disconnect whose Undo window passed while the app was closed.
        commitDue()
    }

    /// The phone renamed itself: keep the link's name in step for migrated pairings.
    func rename(relayDevice: String, to name: String) {
        guard let i = links.firstIndex(where: { $0.relayDeviceId == relayDevice && $0.migrated }), links[i].name != name else { return }
        links[i].name = name
        save()
    }

    // MARK: Claim (LNK-18, LNK-19)

    /// Claims a name and code: this device's user opt-in. Throws `name_required` without a Device Domain Name.
    @discardableResult
    func claim(_ offer: LinkOffer, me: LinkSelf?) async throws -> LinkedBridgit {
        guard let me else { throw LinkError.from(code: "name_required", message: nil) }
        let reach = try await wire.resolve(name: offer.name, code: offer.code)
        let claimed = try await wire.claim(reach, name: offer.name, code: offer.code, me: me)
        var l = claimed.link
        // LNK-10: pairing again with the same device reuses the link.
        if let old = links.first(where: { $0.id == l.id || $0.relayDeviceId == l.relayDeviceId }) {
            l.linkedAt = old.linkedAt
            if old.relayDeviceId != l.relayDeviceId, !isSample, let p = pairing(for: old) { relays.remove(p) }
        }
        if !isSample { relays.add(claimed.pairing) }
        links.removeAll { $0.id == l.id || $0.relayDeviceId == l.relayDeviceId }
        links.append(l)
        pending[l.id] = nil
        retiring[l.id] = nil
        record(l, "out", "code_claimed", after: LinkService.list(l.out.services), granter: .person(on: me.name))
        let known = Set(log.map(\.id))
        log += claimed.farEvents.filter { !known.contains($0.id) }
        save()
        await flush()
        return l
    }

    // MARK: Pause (LNK-27)

    func setPaused(_ id: String, _ paused: Bool) {
        guard let i = links.firstIndex(where: { $0.id == id }) else { return }
        let from = links[i].out.state
        guard paused ? from == .on : from == .paused else { return }
        links[i].out.state = paused ? .paused : .on
        links[i].pausedHere = paused
        record(links[i], "out", paused ? "paused" : "resumed", before: from.rawValue, after: links[i].out.state.rawValue, granter: .person(on: deviceName()))
        save()
        Task { await flush() }
    }

    // MARK: Disconnect with Undo (LNK-26, LNK-30)

    /// Cuts access at once; the far device is told only after the Undo window. Disconnects made while
    /// another is still undoable join one batch, and the batch's 10 seconds start again (design pass).
    func disconnect(_ id: String, direction: String = "both", now: Date = Date()) {
        guard let l = link(id), pending[id] == nil, retiring[id] == nil else { return }
        let until = now.addingTimeInterval(window)
        for k in pending.keys { pending[k]?.until = until }
        pending[id] = Pending(direction: direction, until: until)
        record(l, direction == "both" ? "out" : direction, "disconnected", before: l.out.state.rawValue, after: "off",
               granter: .person(on: deviceName()), sent: true)
        save()
        scheduleBatch(after: window)
    }

    func disconnectAll(now: Date = Date()) {
        for l in links where pending[l.id] == nil && retiring[l.id] == nil { disconnect(l.id, now: now) }
    }

    func undo(_ id: String) {
        guard pending.removeValue(forKey: id) != nil, let l = link(id) else { return }
        record(l, "out", "disconnect_undone", before: "off", after: l.out.state.rawValue, granter: .person(on: deviceName()), sent: true)
        if pending.isEmpty { batch?.cancel(); batch = nil }
        save()
    }

    /// Undo for the whole batch.
    func undoAll() { for id in Array(pending.keys) { undo(id) } }

    private var batch: Task<Void, Never>?

    private func scheduleBatch(after delay: TimeInterval) {
        batch?.cancel()
        batch = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.commitDue()
        }
    }

    /// Commits every disconnect whose Undo window has passed.
    func commitDue(now: Date = Date()) {
        for (id, p) in pending where p.until <= now.addingTimeInterval(0.05) { Task { await commit(id) } }
    }

    func commit(_ id: String) async {
        guard let p = pending.removeValue(forKey: id) else { return }
        retiring[id] = p.direction
        save()
        await tellRetired(id)
    }

    /// Tells the far device, then forgets the token. Offline: kept (unused) and retried later.
    private func tellRetired(_ id: String) async {
        guard let direction = retiring[id], let l = link(id) else { retiring[id] = nil; save(); return }
        if let pairing = pairing(for: l) {
            do {
                if l.linkRoutes { try await wire.disconnect(linkId: l.id, direction: direction, over: pairing) }
            } catch RelayError.offline, RelayError.timeout, RelayError.quota {
                return
            } catch let e as URLError where e.code != .badURL {
                return
            } catch {
                // Revoked, unknown, or an old pairing without link routes: nothing more to tell.
            }
            if !isSample { relays.remove(pairing) }
        }
        retiring[id] = nil
        links.removeAll { $0.id == id }
        save()
    }

    // MARK: Telling the far device (LNK-23) and reading from it (LNK-43)

    /// Sends unsent events and this side's halves, takes the far side's (LNK-23), and finishes waiting disconnects.
    func flush() async {
        for id in Array(retiring.keys) { await tellRetired(id) }
        for l in links where l.linkRoutes && retiring[l.id] == nil && pending[l.id] == nil && l.out.state != .off {
            guard let p = pairing(for: l) else { continue }
            // At most 50 events a call (the rest go next time); the far device's entries are acked by id.
            let unsent = Array(log.filter { $0.linkId == l.id && !$0.sent }.prefix(50))
            let ack = toAck[l.id] ?? []
            do {
                let late = unsent.map { e -> LinkEvent in var e = e; e.late = Date().timeIntervalSince(e.at) > 60; return e }
                let far = try await wire.sync(events: late, ack: ack, link: l, me: deviceName(), pausedHere: l.pausedHere == true, over: p)
                toAck[l.id] = far.farEvents.map(\.id)
                let ids = Set(unsent.map(\.id))
                for i in log.indices where ids.contains(log[i].id) { log[i].sent = true; log[i].late = late.first { $0.id == log[i].id }?.late ?? false }
                apply(far, to: l.id)
                save()
            } catch RelayError.notAllowed {
                markNoRoutes(l.id)
            } catch let e as LinkError where e.code == "link_revoked" {
                markOff(l.id)
            } catch {}
        }
    }

    /// Ids of the far device's entries stored here, to acknowledge on the next sync (it resends until then).
    private var toAck: [String: [String]] = [:]

    /// The far device's halves and log entries.
    func apply(_ far: LinkSync, to id: String) {
        guard let i = links.firstIndex(where: { $0.id == id }) else { return }
        let known = Set(log.map(\.id))
        log += far.farEvents.filter { !known.contains($0.id) }
        log.sort { $0.at < $1.at }
        if let h = far.farIn, links[i].out.state != .off {
            if !h.services.isEmpty { links[i].out.services = h.services }
            links[i].out.state = !h.optedIn || links[i].out.services.isEmpty ? .waiting_for_target : (h.paused || links[i].pausedHere == true) ? .paused : .on
        }
    }

    /// LNK-30: the far device disconnected while this one wasn't told; mark this side off too.
    func markOff(_ id: String) {
        guard let i = links.firstIndex(where: { $0.id == id }), links[i].out.state != .off else { return }
        let before = links[i].out.state
        links[i].out.state = .off
        record(links[i], "out", "marked_off", before: before.rawValue, after: "off", granter: Granter(kind: .person, device: links[i].name), sent: true)
        save()
    }

    /// Peers (for "via B") and introductions from every linked Bridgit that answers link routes.
    func refreshRemote() async {
        for l in links where l.linkRoutes && l.out.state == .on && pending[l.id] == nil && retiring[l.id] == nil {
            guard let p = pairing(for: l) else { continue }
            do {
                let peers = try await wire.peers(over: p)
                if let i = links.firstIndex(where: { $0.id == l.id }), links[i].peers != peers { links[i].peers = peers; save() }
                // Introductions are P2: a Bridgit without them must not demote the link.
                let fresh = (try? await wire.introductions(over: p, link: l)) ?? []
                let known = Set(introductions.map(\.id))
                let added = fresh.filter { !known.contains($0.id) }
                if !added.isEmpty {
                    introductions += added
                    save()
                    for intro in added { notify(intro) }
                }
            } catch RelayError.notAllowed {
                markNoRoutes(l.id)
            } catch let e as LinkError where e.code == "link_revoked" {
                markOff(l.id)
            } catch {}
        }
    }

    private func markNoRoutes(_ id: String) {
        guard let i = links.firstIndex(where: { $0.id == id }), links[i].linkRoutes else { return }
        links[i].linkRoutes = false
        save()
    }

    // MARK: Introductions (LNK-36, Ask me)

    func answer(_ introId: String, allow: Bool, me: LinkSelf?) async throws {
        guard let intro = introductions.first(where: { $0.id == introId }) else { return }
        let via = link(intro.viaLink)
        if allow { try await claim(LinkOffer(name: intro.fromName, code: intro.code), me: me) }
        introductions.removeAll { $0.id == introId }
        if let via {
            record(via, "out", allow ? "introduction_allowed" : "introduction_denied", after: intro.fromName,
                   granter: .person(on: deviceName()))
            if let p = pairing(for: via) { try? await wire.answer(introduction: intro, allow: allow, over: p) }
        }
        save()
        await flush()
    }

    // MARK: Log (LNK-22)

    private func record(_ l: LinkedBridgit, _ direction: String, _ event: String, before: String? = nil, after: String? = nil,
                        granter: Granter, at: Date = Date(), sent: Bool = false) {
        log.append(LinkEvent(id: "lev_" + UUID().uuidString.lowercased(), at: at, linkId: l.id, peer: l.name, direction: direction,
                             event: event, before: before, after: after, granter: granter, device: deviceName(), sent: sent || !l.linkRoutes))
        if log.count > Self.logLimit { log.removeFirst(log.count - Self.logLimit) }
    }

    // MARK: Sample data

    func loadSample(_ sample: (links: [LinkedBridgit], log: [LinkEvent], introductions: [Introduction]), wire fixture: LinkWire) {
        isSample = true
        wire = fixture
        links = sample.links; log = sample.log; introductions = sample.introductions; pending = [:]; retiring = [:]
    }

    func leaveSample() {
        guard isSample else { return }
        batch?.cancel()
        batch = nil
        isSample = false
        wire = realWire
        load()
    }
}
