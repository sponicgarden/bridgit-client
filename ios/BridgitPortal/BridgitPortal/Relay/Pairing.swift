import Foundation

/// The phone's permission for this iPhone or iPad: a relay connector agent the owner created on the
/// phone. It comes from the phone as a QR code / bridgitportal://pair link, from the phone's existing
/// "Copy setup" text (More → Relay → Add relay agent), or after the owner taps Allow for a Google sign-in.
struct Pairing: Codable, Identifiable, Hashable {
    /// `https://<relay>/d/<device>`; every call goes to `<base>/jobs`.
    var base: URL
    var deviceId: String
    var token: String
    var name: String
    /// The phone's remote-view install id (bgi_…), when the phone shares it, to join live and cloud data.
    var install: String?
    /// True when the relay grants this agent the larger portal allowance; otherwise refresh is sparing.
    var portal: Bool = false
    var addedAt: Date = Date()
    /// True for a single-use link (`claim=1`): `token` is then a one-time claim token that must be swapped
    /// for a token made on this device (PairingClaim) before anything is saved. Never true once saved.
    var needsClaim: Bool?

    var id: String { deviceId }

    private static let devicePattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{8,128}$")
    private static let tokenPattern = try! NSRegularExpression(pattern: "^rly_[A-Za-z0-9_-]{16,200}$")

    static func validDevice(_ s: String) -> Bool { devicePattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }
    static func validToken(_ s: String) -> Bool { tokenPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }

    /// Only https bases ending in /d/<device> are accepted, so a pasted link can't point the token elsewhere.
    static func makeBase(relay: String, device: String) -> URL? {
        guard validDevice(device), var c = URLComponents(string: relay), c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { return nil }
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        if !path.hasSuffix("/d/" + device) { path += "/d/" + device }
        c.path = path
        return c.url
    }

    /// Accepts a bridgitportal://pair link, or the phone's relay setup JSON (alone or inside the copied prompt).
    static func parse(_ text: String) -> Pairing? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("bridgitportal://pair"), let url = URL(string: t) { return parse(link: url) }
        guard let start = t.firstIndex(of: "{"), let end = t.lastIndex(of: "}"), start < end,
              let obj = try? JSONSerialization.jsonObject(with: Data(t[start...end].utf8)) as? [String: Any] else { return nil }
        guard (obj["mode"] as? String ?? "connector") == "connector",
              let base = obj["base"] as? String, let device = obj["device_id"] as? String,
              let token = obj["token"] as? String, validToken(token),
              let url = makeBase(relay: base, device: device) else { return nil }
        let name = (obj["phone_name"] as? String) ?? (obj["name"] as? String) ?? "Bridgit phone"
        return Pairing(base: url, deviceId: device, token: token, name: String(name.prefix(60)), install: obj["install"] as? String)
    }

    static func parse(link: URL) -> Pairing? {
        guard link.scheme == "bridgitportal", link.host == "pair" else { return nil }
        let c = URLComponents(url: link, resolvingAgainstBaseURL: false)
        var items = c?.queryItems ?? []
        if let f = c?.fragment, let fc = URLComponents(string: "x:?" + f) { items += fc.queryItems ?? [] }
        func v(_ k: String) -> String? { items.first { $0.name == k }?.value }
        guard v("v") == nil || v("v") == "1",
              let relay = v("relay"), let device = v("device"), let token = v("token"), validToken(token),
              let base = makeBase(relay: relay, device: device) else { return nil }
        let install = v("install").flatMap { $0.hasPrefix("bgi_") ? $0 : nil }
        return Pairing(base: base, deviceId: device, token: token, name: String((v("name") ?? "Bridgit phone").prefix(60)),
                       install: install, portal: v("portal") == "1", needsClaim: v("claim") == "1" ? true : nil)
    }
}

/// Pairings live in the Keychain (they hold the relay token), indexed by relay device id.
@MainActor
final class RelayStore: ObservableObject {
    @Published private(set) var pairings: [Pairing] = []
    private static let key = "relay-pairings"

    init() {
        if let s = Keychain.get(Self.key), let list = try? JSONDecoder().decode([Pairing].self, from: Data(s.utf8)) {
            pairings = list
        }
    }

    func add(_ p: Pairing) {
        pairings.removeAll { $0.deviceId == p.deviceId }
        pairings.append(p)
        save()
    }

    func remove(_ p: Pairing) {
        pairings.removeAll { $0.deviceId == p.deviceId }
        save()
    }

    /// The phone's friendly name changed on the phone: keep the stored pairing in step.
    func rename(_ p: Pairing, to name: String) {
        guard let i = pairings.firstIndex(where: { $0.id == p.id }), pairings[i].name != name else { return }
        pairings[i].name = name
        save()
    }

    func pairing(forInstall install: String) -> Pairing? { pairings.first { $0.install == install } }

    func replaceAllForSample(_ list: [Pairing]) { pairings = list }

    private func save() {
        let data = try? JSONEncoder().encode(pairings)
        Keychain.set(data.flatMap { String(data: $0, encoding: .utf8) }, for: Self.key)
    }
}
