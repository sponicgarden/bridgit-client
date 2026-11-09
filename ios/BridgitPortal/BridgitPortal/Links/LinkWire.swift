import Foundation

/// Where a target can be reached after the names backend checked the code (LNK-19).
struct LinkReach: Equatable {
    /// The target's name as registered (the salt of the code keys).
    var name: String
    var relay: String
    var device: String
    /// The first relay credential, derived from the code (§4.1): it only lets this device submit the
    /// `/v1/link/claim` job, once, before the code expires.
    var claimToken: String
}

/// What this device says about itself when it claims (LNK-18).
struct LinkSelf: Equatable {
    /// This device's Device Domain Name.
    var name: String
    /// Its system name ("Rahul's iPad"), shown on the target.
    var displayName: String = "Bridgit"
    var install: String
    var platform: String
    var deviceType: String
}

/// The answer to a claim: the saved relay pairing (holding a token made on this device) and the link.
struct LinkClaimed {
    var pairing: Pairing
    var link: LinkedBridgit
    /// The target's own log entries about this link (LNK-23), directions already from this side.
    var farEvents: [LinkEvent] = []
}

/// What the far device said in a sync: its half of "this device uses it", and its log entries.
struct LinkSync: Equatable {
    struct Half: Equatable { var optedIn: Bool; var paused: Bool; var services: [String] }
    var farIn: Half?
    var farEvents: [LinkEvent] = []
}

struct LinkError: LocalizedError, Equatable {
    var code: String
    var message: String
    var errorDescription: String? { message }

    /// LNK-49 codes in the app's words.
    static func from(code: String, message: String?) -> LinkError {
        let text: String
        switch code {
        case "code_invalid": text = "That name and code don't match."
        case "code_expired": text = "That code expired. Ask for a new one."
        case "code_used_up": text = "That code was used up. Ask for a new one."
        case "name_required": text = "Name this device first."
        case "link_revoked": text = "The other device disconnected."
        case "link_paused": text = "Paused on the other device."
        case "link_waiting": text = "Waiting for the other device."
        case "target_offline": text = "The other device is offline."
        case "introduction_refused": text = "Refused."
        case "rate_limited": text = "Too many tries. Try again later."
        default: text = message ?? code
        }
        return LinkError(code: code, message: text)
    }
}

/// Every network shape of linking, in one place. The real one talks to the names backend and the target
/// over the relay; the fixture one answers from memory (sample data and tests).
protocol LinkWire: Sendable {
    func resolve(name: String, code: String) async throws -> LinkReach
    func claim(_ reach: LinkReach, name: String, code: String, me: LinkSelf) async throws -> LinkClaimed
    /// Sends this side's unsent events and its halves (`POST /v1/link/events`); returns the far side's.
    func sync(events: [LinkEvent], ack: [String], link: LinkedBridgit, me: String, pausedHere: Bool, over pairing: Pairing) async throws -> LinkSync
    func peers(over pairing: Pairing) async throws -> [LinkPeer]
    func introductions(over pairing: Pairing, link: LinkedBridgit) async throws -> [Introduction]
    func answer(introduction: Introduction, allow: Bool, over pairing: Pairing) async throws
    func disconnect(linkId: String, direction: String, over pairing: Pairing) async throws
}

/// The real wire: the names backend as in docs/spec-linked-bridgits.md §4.1, and the target's
/// device-to-device routes (§10 LNK-47) over the relay. Introductions (P2) are a proposal until Android has them.
struct RelayLinkWire: LinkWire {
    var backend: @Sendable () throws -> TeamBackend
    var http: URLSession = .shared

    func resolve(name: String, code: String) async throws -> LinkReach {
        let team = try backend()
        // The code keys are salted with the name as registered, never as typed.
        let (ls, lookup) = try await team.call("GET", "/v1/names/" + name)
        guard ls == 200, let registered = lookup["name"] as? String, lookup["status"] as? String != "unregistered" else {
            throw LinkError.from(code: ls == 429 ? "rate_limited" : "code_invalid", message: nil)
        }
        guard let keys = await Task.detached(priority: .userInitiated, operation: { LinkCode.keys(code: code, name: registered) }).value else {
            throw LinkError.from(code: "code_invalid", message: nil)
        }
        let proof = keys.proof(name: registered, claimingInstall: team.identity.id)
        let (status, json) = try await team.call("POST", "/v1/names/" + registered + "/resolve", body: ["code_proof": proof])
        guard status == 200 else { throw Self.error(status, json) }
        let relay = (json["reach"] as? [String: Any])?["relay"] as? [String: Any] ?? [:]
        guard let origin = relay["origin"] as? String, let device = relay["device"] as? String else {
            throw LinkError(code: "upstream_failed", message: "The names backend gave no address for \(registered).")
        }
        return LinkReach(name: json["name"] as? String ?? registered, relay: origin, device: device, claimToken: keys.claimToken)
    }

    func claim(_ reach: LinkReach, name: String, code: String, me: LinkSelf) async throws -> LinkClaimed {
        guard let base = Pairing.makeBase(relay: reach.relay, device: reach.device) else {
            throw LinkError(code: "upstream_failed", message: "The names backend gave an address this app can't use.")
        }
        let token = PairingClaim.newToken()
        let claimer = Pairing(base: base, deviceId: reach.device, token: reach.claimToken, name: reach.name)
        let r = try await RelayClient(pairing: claimer, http: http).callRaw(method: "POST", path: "/v1/link/claim", body: [
            "name": me.displayName, "bridgit_name": me.name, "install": me.install, "granter": ["kind": "person"],
            "token_hash": PairingClaim.tokenHash(token), "platform": me.platform, "device_type": me.deviceType,
        ], wait: 20)
        let json = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] ?? [:]
        guard r.status == 200, let id = json["link_id"] as? String else { throw Self.error(r.status, json) }
        let label = (json["bridgit_name"] as? String).flatMap(DeviceNameStore.parse) ?? reach.name
        var pairing = Pairing(base: base, deviceId: reach.device, token: token, name: label,
                              install: (json["install"] as? String).flatMap { $0.hasPrefix("bgi_") ? $0 : nil }, portal: true)
        pairing.linkId = id
        // The target's "in" direction is this device's "out": on once the target listed services for it.
        let services = json["services"] as? [String] ?? []
        let targetIn = (json["directions"] as? [String] ?? ["in"]).contains("in")
        let out = LinkDirection(state: targetIn && !services.isEmpty ? .on : .waiting_for_target, services: targetIn ? services : [],
                                grantedBy: .person(on: me.name), grantedAt: Date())
        let link = LinkedBridgit(id: id, name: label, platform: json["platform"] as? String ?? "android",
                                 deviceType: json["device_type"] as? String ?? "phone", relayDeviceId: reach.device,
                                 out: out, inbound: .off, linkedAt: Date())
        return LinkClaimed(pairing: pairing, link: link, farEvents: Self.farEvents(json["events"], link: link))
    }

    func sync(events: [LinkEvent], ack: [String], link: LinkedBridgit, me: String, pausedHere: Bool, over pairing: Pairing) async throws -> LinkSync {
        let state: [String: Any] = ["in": ["opted_in": false, "paused": false, "services": [String]()],
                                    "out": ["opted_in": link.out.state != .off, "paused": pausedHere]]
        let r = try await RelayClient(pairing: pairing, http: http).callRaw(method: "POST", path: "/v1/link/events", body: [
            "link_id": link.id, "events": events.map(\.json), "ack": ack, "state": state, "bridgit_name": me,
        ])
        let json = try Self.check(r)
        // The far side's "in" half (it is used by this device) is this device's "out".
        let farIn = ((json["state"] as? [String: Any])?["in"] as? [String: Any]).map {
            LinkSync.Half(optedIn: $0["opted_in"] as? Bool ?? true, paused: $0["paused"] as? Bool ?? false, services: $0["services"] as? [String] ?? [])
        }
        return LinkSync(farIn: farIn, farEvents: Self.farEvents(json["events"], link: link))
    }

    /// The far device's log entries, stored as reported by it, with directions flipped to this side.
    static func farEvents(_ any: Any?, link: LinkedBridgit) -> [LinkEvent] {
        (any as? [[String: Any]] ?? []).compactMap { e in
            guard let id = e["id"] as? String, let event = e["event"] as? String else { return nil }
            func text(_ v: Any?) -> String? {
                switch v {
                case let s as String: return s
                case let a as [String]: return LinkService.list(a)
                case let o as [String: Any]: return o.keys.sorted().map { k in "\(k): \(text(o[k]) ?? "–")" }.joined(separator: ", ")
                case let n as NSNumber: return n.stringValue
                default: return nil
                }
            }
            let dir: String
            switch e["direction"] as? String { case "in": dir = "out"; case "out": dir = "in"; default: dir = "both" }
            return LinkEvent(id: id, at: (e["at"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date(), linkId: link.id, peer: link.name,
                             direction: dir, event: event, before: text(e["before"]), after: text(e["after"]),
                             granter: Granter(json: e["granter"]) ?? Granter(kind: .person, device: link.name),
                             device: e["device"] as? String ?? link.name, reportedBy: link.name, late: e["late"] as? Bool ?? false, sent: true)
        }
    }

    func peers(over pairing: Pairing) async throws -> [LinkPeer] {
        let r = try await RelayClient(pairing: pairing, http: http).callRaw(method: "GET", path: "/v1/link/peers", body: nil)
        let json = try Self.check(r)
        return (json["peers"] as? [[String: Any]] ?? []).compactMap { p in
            guard let n = p["name"] as? String else { return nil }
            // {name, in, out}: from the linked Bridgit's side, "out" = it uses that peer.
            let d = p["directions"] as? [String: Any] ?? p
            func on(_ k: String) -> Bool { (d[k] as? String).map { $0 != "off" } ?? (d[k] as? Bool ?? false) }
            return LinkPeer(name: n, uses: on("out"), usedBy: on("in"))
        }
    }

    func introductions(over pairing: Pairing, link: LinkedBridgit) async throws -> [Introduction] {
        // Proposed for P2: introductions for this device wait on the linked Bridgit that relays them. A Bridgit
        // without the route (Android P1) answers 403/404 or the relay refuses the route: that means "none yet",
        // never "an old pairing without link routes".
        let r: RelayResponse
        do { r = try await RelayClient(pairing: pairing, http: http).callRaw(method: "GET", path: "/v1/link/introductions", body: nil) }
        catch RelayError.notAllowed { return [] }
        let json = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] ?? [:]
        if (r.status == 403 || r.status == 404) && json["error"] as? String != "link_revoked" { return [] }
        _ = try Self.check(r)
        return (json["introductions"] as? [Any] ?? []).compactMap { Introduction(json: $0, via: link) }
    }

    func answer(introduction: Introduction, allow: Bool, over pairing: Pairing) async throws {
        let r = try await RelayClient(pairing: pairing, http: http).callRaw(method: "POST", path: "/v1/link/introductions/" + introduction.id,
                                                                          body: ["answer": allow ? "allow" : "deny"])
        try Self.check(r)
    }

    func disconnect(linkId: String, direction: String, over pairing: Pairing) async throws {
        let r = try await RelayClient(pairing: pairing, http: http).callRaw(method: "POST", path: "/v1/link/disconnect",
                                                                          body: ["link_id": linkId, "direction": direction, "granter": ["kind": "person"]])
        // Already gone on the far side counts as done.
        if r.status == 401 || r.status == 404 { return }
        let json = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] ?? [:]
        if json["error"] as? String == "link_revoked" { return }
        try Self.check(r)
    }

    @discardableResult
    static func check(_ r: RelayResponse) throws -> [String: Any] {
        let json = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] ?? [:]
        guard (200..<300).contains(r.status) else {
            // A disconnected link answers 403 link_revoked (LNK-30); any other 403/404 is an old pairing without link routes.
            if let code = json["error"] as? String, ["link_revoked", "link_paused", "link_waiting"].contains(code) { throw error(r.status, json) }
            if r.status == 403 || r.status == 404 { throw RelayError.notAllowed("link routes") }
            throw error(r.status, json)
        }
        return json
    }

    static func error(_ status: Int, _ json: [String: Any]) -> LinkError {
        LinkError.from(code: json["error"] as? String ?? "upstream_failed", message: json["message"] as? String ?? "Answered \(status).")
    }
}

/// Answers from memory: sample data and tests. Each code in `offers` is valid once.
actor FixtureLinkWire: LinkWire {
    struct Offer { var platform = "android"; var deviceType = "tablet"; var services = ["camera", "mic", "speaker", "interactions", "view"] }
    private var offers: [String: Offer]
    private var tries: [String: Int] = [:]
    private(set) var sent: [LinkEvent] = []
    private(set) var disconnects: [(String, String)] = []
    var pendingIntroductions: [String: [Introduction]] = [:]
    var peerLists: [String: [LinkPeer]] = [:]

    init(offers: [String: Offer] = [:]) { self.offers = offers }

    func addOffer(name: String, code: String, offer: Offer = Offer()) { offers[name + "/" + (LinkCode.normalize(code) ?? code)] = offer }
    func setIntroductions(_ list: [Introduction], via relayDevice: String) { pendingIntroductions[relayDevice] = list }
    func setPeers(_ list: [LinkPeer], via relayDevice: String) { peerLists[relayDevice] = list }
    func sentEvents() -> [LinkEvent] { sent }
    func disconnectCalls() -> [(String, String)] { disconnects }

    func resolve(name: String, code: String) async throws -> LinkReach {
        let key = name + "/" + (LinkCode.normalize(code) ?? code)
        guard offers[key] != nil else {
            tries[name, default: 0] += 1
            throw LinkError.from(code: tries[name, default: 0] >= 5 ? "code_used_up" : "code_invalid", message: nil)
        }
        return LinkReach(name: name, relay: "https://bridgit-relay.bridgit-app.workers.dev", device: "fixture-" + name, claimToken: "rly_fixture_" + name)
    }

    func claim(_ reach: LinkReach, name: String, code: String, me: LinkSelf) async throws -> LinkClaimed {
        let key = name + "/" + (LinkCode.normalize(code) ?? code)
        guard let offer = offers.removeValue(forKey: key), let base = Pairing.makeBase(relay: reach.relay, device: reach.device) else {
            throw LinkError.from(code: "code_used_up", message: nil)
        }
        let id = "lnk_fixture_" + name
        var p = Pairing(base: base, deviceId: reach.device, token: PairingClaim.newToken(), name: name, portal: true)
        p.linkId = id
        let out = LinkDirection(state: .on, services: offer.services, grantedBy: Granter(kind: .person, device: name), grantedAt: Date())
        return LinkClaimed(pairing: p, link: LinkedBridgit(id: id, name: name, platform: offer.platform, deviceType: offer.deviceType,
                                                            relayDeviceId: reach.device, out: out, linkedAt: Date()))
    }

    var farHalves: [String: LinkSync.Half] = [:]
    func setFarHalf(_ h: LinkSync.Half?, via relayDevice: String) { farHalves[relayDevice] = h }
    func sync(events: [LinkEvent], ack: [String], link: LinkedBridgit, me: String, pausedHere: Bool, over pairing: Pairing) async throws -> LinkSync {
        if revoked.contains(pairing.deviceId) { throw LinkError.from(code: "link_revoked", message: nil) }
        sent += events
        return LinkSync(farIn: farHalves[pairing.deviceId])
    }
    var revoked: Set<String> = []
    func revoke(_ relayDevice: String) { revoked.insert(relayDevice) }
    func peers(over pairing: Pairing) async throws -> [LinkPeer] { peerLists[pairing.deviceId] ?? [] }
    /// Like a Bridgit without the (P2) introductions route.
    var noIntroductions = false
    func setNoIntroductions() { noIntroductions = true }
    func introductions(over pairing: Pairing, link: LinkedBridgit) async throws -> [Introduction] {
        if noIntroductions { throw RelayError.notAllowed("link routes") }
        return pendingIntroductions[pairing.deviceId] ?? []
    }
    func answer(introduction: Introduction, allow: Bool, over pairing: Pairing) async throws {
        pendingIntroductions[pairing.deviceId]?.removeAll { $0.id == introduction.id }
    }
    func disconnect(linkId: String, direction: String, over pairing: Pairing) async throws { disconnects.append((linkId, direction)) }
}
