import CryptoKit
import Foundation

/// The CID-20 request signature (docs/spec-client-identity.md), shared by every install key in the app.
/// Same text, SPKI-derived ID and P1363 signature as Android's InstallAuth.
enum InstallSigning {
    static func installId(spki: Data) -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567")
        var pending = 0, bits = 0, result = "bgi_"
        for byte in SHA256.hash(data: spki).prefix(15) {
            pending = (pending << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                result.append(alphabet[(pending >> bits) & 31])
            }
            pending &= (1 << bits) - 1
        }
        if bits > 0 { result.append(alphabet[(pending << (5 - bits)) & 31]) }
        return result
    }

    static func signingText(id: String, method: String, pathAndQuery: String, at: Int64, nonce: String, body: Data) -> String {
        let digest = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        return ["bridgit-install-v1", id, method.uppercased(), pathAndQuery, String(at), nonce, digest].joined(separator: "\n")
    }

    static func sign(_ request: inout URLRequest, key: P256.Signing.PrivateKey, id: String, at: Int64, nonce: String) throws {
        guard let url = request.url else { throw URLError(.badURL) }
        let path = url.path + (url.query.map { "?" + $0 } ?? "")
        let text = signingText(id: id, method: request.httpMethod ?? "GET", pathAndQuery: path, at: at, nonce: nonce, body: request.httpBody ?? Data())
        let signature = try key.signature(for: Data(text.utf8)).rawRepresentation.base64EncodedString()
        request.setValue(nil, forHTTPHeaderField: "Authorization")
        request.setValue(id, forHTTPHeaderField: "X-Bridgit-Install")
        request.setValue("at=\(at),nonce=\(nonce),sig=\(signature)", forHTTPHeaderField: "X-Bridgit-Signature")
    }
}

/// This Apple install's own identity with the Bridgit team backend: one P-256 key per device (Keychain,
/// this device only, never synced), independent of sign-in. It is what a Device Domain Name belongs to.
///
/// Trust: the backend checks Android Key Attestation only, so an Apple install registers with an empty
/// attestation chain and is recorded as `unverified` (reason `no_chain`). Names and link offers accept it.
final class DeviceIdentity: @unchecked Sendable {
    static let keychainKey = "device-install-v1"
    private static let registeredKey = "device-install-registered"
    private let lock = NSLock()
    let key: P256.Signing.PrivateKey
    let defaults: UserDefaults

    init(key: P256.Signing.PrivateKey, defaults: UserDefaults = .standard) {
        self.key = key
        self.defaults = defaults
    }

    /// The device's key, made once.
    static func load(defaults: UserDefaults = .standard) throws -> DeviceIdentity {
        let stored = try Keychain.getOrCreate(keychainKey) { P256.Signing.PrivateKey().rawRepresentation.base64EncodedString() }
        guard let raw = Data(base64Encoded: stored) else { throw URLError(.cannotDecodeContentData) }
        return DeviceIdentity(key: try P256.Signing.PrivateKey(rawRepresentation: raw), defaults: defaults)
    }

    var publicKeyB64: String { key.publicKey.derRepresentation.base64EncodedString() }
    var id: String { InstallSigning.installId(spki: key.publicKey.derRepresentation) }

    /// What the backend said at registration: `unverified` for Apple today.
    var trust: String? { defaults.string(forKey: Self.registeredKey) }
    var isRegistered: Bool { trust != nil }

    func markRegistered(trust: String) { defaults.set(trust, forKey: Self.registeredKey) }

    /// The backend lost this install: register again next time with the same key.
    func forgetRegistration() { defaults.removeObject(forKey: Self.registeredKey) }

    func sign(_ request: inout URLRequest, now: Date = Date()) throws {
        lock.lock(); defer { lock.unlock() }
        try InstallSigning.sign(&request, key: key, id: id, at: Int64(now.timeIntervalSince1970 * 1000), nonce: UUID().uuidString)
    }
}
