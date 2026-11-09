import CryptoKit
import Foundation
import Security

/// Relay identity is separate from the paid-install identity and saved viewer credentials.
public final class RelayHostIdentity {
    public let deviceID: String
    public let signer: DeviceSigner
    private let agreement: P256.KeyAgreement.PrivateKey
    public var hpkePublic: Data { agreement.publicKey.x963Representation }
    private init(deviceID: String, signer: DeviceSigner, agreement: P256.KeyAgreement.PrivateKey) { self.deviceID = deviceID; self.signer = signer; self.agreement = agreement }
    public static func loadOrCreate(service: String) throws -> RelayHostIdentity {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "relay-host-identity-v1"]
        var read = query; read[kSecReturnData as String] = true
        var found: CFTypeRef?; let status = SecItemCopyMatching(read as CFDictionary, &found)
        let id: String, key: P256.KeyAgreement.PrivateKey
        if status == errSecSuccess, let data = found as? Data {
            guard let saved = try JSONSerialization.jsonObject(with: data) as? [String: String], let device = saved["device_id"], UUID(uuidString: device) != nil,
                  let secret = saved["agreement"].flatMap({ Data(base64Encoded: $0) }) else { throw DeviceAPIError(503, "identity_invalid", "The saved relay identity needs repair; it will not be replaced automatically.") }
            id = device; key = try P256.KeyAgreement.PrivateKey(rawRepresentation: secret)
        } else if status == errSecItemNotFound {
            id = UUID().uuidString.lowercased(); key = P256.KeyAgreement.PrivateKey()
            let data = try JSONSerialization.data(withJSONObject: ["device_id": id, "agreement": key.rawRepresentation.base64EncodedString()])
            var create = query; create[kSecValueData as String] = data; create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(create as CFDictionary, nil) == errSecSuccess else { throw DeviceAPIError(503, "identity_unavailable", "The relay identity could not be saved.") }
        } else { throw DeviceAPIError(503, "identity_locked", "Unlock this device before starting its relay host.") }
        return try .init(deviceID: id, signer: .loadOrCreate(tag: service + ".relay-signing.v1"), agreement: key)
    }
    public static func ephemeral(deviceID: String = UUID().uuidString.lowercased()) throws -> RelayHostIdentity { try .init(deviceID: deviceID, signer: .ephemeral(), agreement: .init()) }
    public func proof(domain: String, nonce: String, at: Int64) throws -> String { try signer.sign(Data([domain, deviceID, nonce, String(at)].joined(separator: "\n").utf8)).base64EncodedString() }
    public func open(peer: Data, envelope: [String: Any], aad: Data) throws -> Data { try RelaySealed.open(privateKey: agreement, peer: peer, envelope: envelope, aad: aad) }
    public func seal(peer: Data, plaintext: Data, aad: Data) throws -> [String: Any] { try RelaySealed.seal(privateKey: agreement, peer: peer, plaintext: plaintext, aad: aad) }
}
public enum RelaySealed {
    private static var suite: HPKE.Ciphersuite { .init(kem: .P256_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_128) }
    public static func aad(device: String, agent: String, generation: String, requestID: String, deadline: Int64, sequence: Int64, direction: String) -> Data { Data(["bridgit-relay-v1", device, agent, generation, requestID, String(deadline), String(sequence), direction].joined(separator: "\n").utf8) }
    public static func open(privateKey: P256.KeyAgreement.PrivateKey, peer: Data, envelope: [String: Any], aad: Data) throws -> Data {
        guard let enc = (envelope["enc"] as? String).flatMap({ Data(base64Encoded: $0) }), enc.count == 65,
              let ct = (envelope["ct"] as? String).flatMap({ Data(base64Encoded: $0) }), ct.count <= 1_048_576 else { throw DeviceAPIError(400, "invalid_envelope", "The sealed request is invalid or too large.") }
        var context = try HPKE.Recipient(privateKey: privateKey, ciphersuite: suite, info: Data("bridgit-relay-v1".utf8), encapsulatedKey: enc, authenticatedBy: P256.KeyAgreement.PublicKey(x963Representation: peer))
        return try context.open(ct, authenticating: aad)
    }
    public static func seal(privateKey: P256.KeyAgreement.PrivateKey, peer: Data, plaintext: Data, aad: Data) throws -> [String: Any] {
        var context = try HPKE.Sender(recipientKey: P256.KeyAgreement.PublicKey(x963Representation: peer), ciphersuite: suite, info: Data("bridgit-relay-v1".utf8), authenticatedBy: privateKey)
        return try ["enc": context.encapsulatedKey.base64EncodedString(), "ct": context.seal(plaintext, authenticating: aad).base64EncodedString()]
    }
}
