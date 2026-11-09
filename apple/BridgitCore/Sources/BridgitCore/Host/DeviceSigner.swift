import CryptoKit
import Foundation
import Security

public struct DeviceAPIError: LocalizedError {
    public var status: Int
    public var code: String
    public var message: String
    public let reason: String?
    // Used only by the public connections-send adapter; private errors keep their codes.
    var connectionSendRetryAfterSeconds: Int?
    public var errorDescription: String? { message }
    public init(_ status: Int, _ code: String, _ message: String, reason: String? = nil) {
        self.status = status; self.code = code; self.message = message; self.reason = reason
    }
}

/// Persistent signing identity. Private key bytes never leave Security.framework.
public final class DeviceSigner {
    private let key: SecKey
    public let publicKey: Data
    public var installID: String { "bgi_" + Self.base32(Data(SHA256.hash(data: publicKey).prefix(15))) }
    private init(key: SecKey) throws {
        self.key = key
        guard let pub = SecKeyCopyPublicKey(key), let bytes = SecKeyCopyExternalRepresentation(pub, nil) as Data?, bytes.count == 65 else {
            throw DeviceAPIError(500, "identity_unavailable", "The device signing key is unavailable.")
        }
        // DER SubjectPublicKeyInfo for id-ecPublicKey, prime256v1, uncompressed point.
        publicKey = Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]) + bytes
    }
    public static func loadOrCreate(tag: String) throws -> DeviceSigner {
        let tagData = Data(tag.utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tagData,
                                    kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true]
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecSuccess, let found { return try DeviceSigner(key: (found as! SecKey)) }
        guard status == errSecItemNotFound else { throw DeviceAPIError(503, "identity_locked", "Unlock this device to use its signing identity.") }
        let privateAttrs: [String: Any] = [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: tagData,
                                           kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let base: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits as String: 256, kSecPrivateKeyAttrs as String: privateAttrs]
        // Prefer Secure Enclave. Simulator and supported Intel Macs use a device-only Keychain key.
        var enclave = base; enclave[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
        if let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, nil) {
            var attrs = privateAttrs; attrs.removeValue(forKey: kSecAttrAccessible as String); attrs[kSecAttrAccessControl as String] = access
            enclave[kSecPrivateKeyAttrs as String] = attrs
            if let key = SecKeyCreateRandomKey(enclave as CFDictionary, nil) { return try DeviceSigner(key: key) }
        }
        guard let key = SecKeyCreateRandomKey(base as CFDictionary, nil) else { throw DeviceAPIError(503, "identity_unavailable", "The device could not create its signing identity.") }
        return try DeviceSigner(key: key)
    }
    /// An in-memory identity for deterministic protocol tests. It is never registered automatically.
    public static func ephemeral() throws -> DeviceSigner {
        guard let key = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits: 256] as CFDictionary, nil) else {
            throw DeviceAPIError(500, "identity_unavailable", "Could not create the test identity.")
        }
        return try DeviceSigner(key: key)
    }
    public func sign(_ data: Data) throws -> Data {
        guard let der = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, data as CFData, nil) as Data? else {
            throw DeviceAPIError(503, "identity_locked", "The device could not sign this request.")
        }
        return try P256.Signing.ECDSASignature(derRepresentation: der).rawRepresentation
    }
    public static func signingText(installID: String, method: String, path: String, at: Int64, nonce: String, body: Data) -> String {
        ["bridgit-install-v1", installID, method.uppercased(), path, String(at), nonce, SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()].joined(separator: "\n")
    }
    public func signedRequest(url: URL, method: String, body: Data = Data(), at: Int64 = Int64(Date().timeIntervalSince1970 * 1000), nonce: String = UUID().uuidString.lowercased()) throws -> URLRequest {
        var request = URLRequest(url: url); request.httpMethod = method.uppercased(); request.httpBody = body
        request.timeoutInterval = 90; request.cachePolicy = .reloadIgnoringLocalCacheData
        let parts = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let encodedPath = parts?.percentEncodedPath ?? "/"
        let path = (encodedPath.isEmpty ? "/" : encodedPath) + (parts?.percentEncodedQuery.map { "?" + $0 } ?? "")
        let signature = try sign(Data(Self.signingText(installID: installID, method: method, path: path, at: at, nonce: nonce, body: body).utf8))
        request.setValue(installID, forHTTPHeaderField: "X-Bridgit-Install")
        request.setValue("at=\(at),nonce=\(nonce),sig=\(signature.base64EncodedString())", forHTTPHeaderField: "X-Bridgit-Signature")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }
    public static func base32(_ data: Data) -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567"), bytes = [UInt8](data)
        var buffer: UInt32 = 0, bits = 0, out = ""
        for byte in bytes {
            buffer = (buffer << 8) | UInt32(byte); bits += 8
            while bits >= 5 { bits -= 5; out.append(alphabet[Int((buffer >> bits) & 31)]) }
        }
        if bits > 0 { out.append(alphabet[Int((buffer << (5 - bits)) & 31)]) }
        return out
    }
}
