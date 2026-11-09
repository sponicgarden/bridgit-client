import Foundation

/// The phone's permission for this iPhone or iPad: a relay connector agent the owner created on the
/// phone. It comes from the phone as a QR code / bridgit://pair link, from the phone's existing
/// "Copy setup" text (More → Relay → Add relay agent), or after the owner taps Allow for a Google sign-in.
public struct Pairing: Codable, Identifiable, Hashable {
    /// `https://<relay>/d/<device>`; every call goes to `<base>/jobs`.
    public var base: URL
    public var deviceId: String
    public var token: String
    public static let unnamedDevice = "Bridgit device"
    public var name: String
    public var localAlias: String?
    public var displayName: String { localAlias ?? name }
    /// The phone's remote-view install id (bgi_…), when the phone shares it, to join live and cloud data.
    public var install: String?
    /// True when the relay grants this agent the larger portal allowance; otherwise refresh is sparing.
    public var portal: Bool = false
    public var addedAt: Date = Date()
    /// True for a single-use link (`claim=1`): `token` is then a one-time claim token that must be swapped
    /// for a token made on this device (PairingClaim) before anything is saved. Never true once saved.
    public var needsClaim: Bool?

    public var id: String { deviceId }

    public init(base: URL, deviceId: String, token: String, name: String,
                install: String? = nil, portal: Bool = false, addedAt: Date = Date(), needsClaim: Bool? = nil, localAlias: String? = nil) {
        self.base = base; self.deviceId = deviceId; self.token = token; self.name = name
        self.install = install; self.portal = portal; self.addedAt = addedAt; self.needsClaim = needsClaim; self.localAlias = localAlias
    }

    private static let devicePattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{8,128}$")
    private static let tokenPattern = try! NSRegularExpression(pattern: "^rly_[A-Za-z0-9_-]{16,200}$")

    public static func validDevice(_ s: String) -> Bool { devicePattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }
    public static func validToken(_ s: String) -> Bool { tokenPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }

    /// Only https bases ending in /d/<device> are accepted, so a pasted link can't point the token elsewhere.
    public static func makeBase(relay: String, device: String) -> URL? {
        guard validDevice(device), var c = URLComponents(string: relay), c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { return nil }
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        if !path.hasSuffix("/d/" + device) { path += "/d/" + device }
        c.path = path
        return c.url
    }

    /// Accepts a bridgit://pair link, or the phone's relay setup JSON (alone or inside the copied prompt).
    public static func parse(_ text: String) -> Pairing? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: t), ["bridgit", "bridgitportal"].contains(url.scheme ?? "") {
            // APK 3347's actual QR formatter emits bridgitportal://pair. Accept that
            // specific viewer format only as explicitly imported data, never as an
            // application deep-link/sign-in alias or a source of authority.
            return parsePairURL(url, importedAPK: url.scheme == "bridgitportal")
        }
        guard let start = t.firstIndex(of: "{"), let end = t.lastIndex(of: "}"), start < end,
              let obj = try? JSONSerialization.jsonObject(with: Data(t[start...end].utf8)) as? [String: Any] else { return nil }
        guard (obj["mode"] as? String ?? "connector") == "connector",
              let base = obj["base"] as? String, let device = obj["device_id"] as? String,
              let token = obj["token"] as? String, validToken(token),
              let url = makeBase(relay: base, device: device) else { return nil }
        let name = (obj["phone_name"] as? String) ?? (obj["name"] as? String) ?? unnamedDevice
        return Pairing(base: url, deviceId: device, token: token, name: String(name.prefix(60)), install: obj["install"] as? String)
    }

    public static func parse(link: URL) -> Pairing? { parsePairURL(link, importedAPK: false) }

    private static func parsePairURL(_ link: URL, importedAPK: Bool) -> Pairing? {
        guard link.scheme == "bridgit" || importedAPK && link.scheme == "bridgitportal",
              link.host == "pair", link.path.isEmpty, link.user == nil, link.password == nil, link.port == nil else { return nil }
        let c = URLComponents(url: link, resolvingAgainstBaseURL: false)
        var items = c?.queryItems ?? []
        if let f = c?.percentEncodedFragment, let fc = URLComponents(string: "x:?" + f) { items += fc.queryItems ?? [] }
        let fields = Set(["v", "relay", "device", "token", "install", "portal", "claim", "name"])
        guard fields.allSatisfy({ key in items.filter { $0.name == key }.count <= 1 }) else { return nil }
        func v(_ k: String) -> String? { items.first { $0.name == k }?.value }
        guard !importedAPK || v("v") == "1" && v("portal") == "1",
              [nil, "0", "1"].contains(v("portal")), [nil, "0", "1"].contains(v("claim")),
              v("v") == nil || v("v") == "1",
              let relay = v("relay"), let device = v("device"), let token = v("token"), validToken(token),
              let base = makeBase(relay: relay, device: device) else { return nil }
        let install = v("install").flatMap { $0.hasPrefix("bgi_") ? $0 : nil }
        return Pairing(base: base, deviceId: device, token: token, name: String((v("name") ?? unnamedDevice).prefix(60)),
                       install: install, portal: v("portal") == "1", needsClaim: v("claim") == "1" ? true : nil)
    }
}
