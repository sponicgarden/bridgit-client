import Foundation
import Security

/// Android 3347's read-only TDP v2 discovery. The ephemeral private key is never
/// persisted or used to decrypt device/account information.
enum LanTPLinkDiscovery {
    struct Hint: Equatable { let name: String; let service: String }
    static func query() throws -> Data {
        guard let key = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeRSA,
              kSecAttrKeySizeInBits: 2048] as CFDictionary, nil),
              let publicKey = SecKeyCopyPublicKey(key),
              let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else { throw unavailable() }
        // Security exports RSA public keys as PKCS#1. TDP expects an X.509
        // SubjectPublicKeyInfo PEM, as Java PublicKey.encoded supplies on Android.
        let algorithm = Data([0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,0x01,0x05,0x00])
        let spki = der(0x30, algorithm + der(0x03, Data([0]) + pkcs1))
        let base64 = spki.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        let pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n"
        let payload = try JSONSerialization.data(withJSONObject: ["params": ["rsa_key": pem]], options: [.sortedKeys])
        guard payload.count <= 65535 else { throw unavailable() }
        var nonce = [UInt8](repeating: 0, count: 4)
        guard SecRandomCopyBytes(kSecRandomDefault, nonce.count, &nonce) == errSecSuccess else { throw unavailable() }
        var bytes = Data([2,0,0,1,UInt8(payload.count >> 8),UInt8(payload.count & 255),17,0])
        bytes.append(contentsOf: nonce)
        bytes.append(contentsOf: [0x5a,0x6b,0x7c,0x8d]); bytes.append(payload)
        let checksum = crc32(bytes)
        bytes.replaceSubrange(12..<16, with: [UInt8(checksum >> 24), UInt8((checksum >> 16) & 255), UInt8((checksum >> 8) & 255), UInt8(checksum & 255)])
        return bytes
    }
    static func parse(_ bytes: Data) -> Hint? {
        guard (17...8192).contains(bytes.count), bytes.first == 2,
              let json = (try? JSONSerialization.jsonObject(with: bytes.dropFirst(16))) as? [String: Any],
              let error = json["error_code"] as? NSNumber, error.intValue == 0,
              let result = json["result"] as? [String: Any] else { return nil }
        let model = LanDiscoveryObservation.bounded(LanDiscoveryObservation.cleanName(result["device_model"] as? String ?? ""), utf16Limit: 60)
        let prefix: String, service: String
        switch result["device_type"] as? String {
        case "SMART.IPCAMERA", "IOT.IPCAMERA": prefix = "Tapo"; service = "tapo_camera"
        case "SMART.TAPOPLUG", "SMART.TAPOBULB", "SMART.TAPOHUB": prefix = "Tapo"; service = "tplink_smart_device"
        case "HOMEWIFISYSTEM": prefix = "TP-Link Deco"; service = "tplink_router"
        default: prefix = "TP-Link"; service = "tplink_device"
        }
        return Hint(name: (prefix + " " + model).trimmingCharacters(in: .whitespacesAndNewlines), service: service)
    }
    static func scan(network: LanInterfaceSelection, observations: LanDiscoveryAccumulator,
                     authorize: @escaping @Sendable () async throws -> Void,
                     currentNetwork: @escaping @Sendable () async throws -> LanInterfaceSelection) async throws {
        let check: @Sendable () async throws -> Void = {
            try Task.checkCancellation()
            try await authorize()
            guard try await currentNetwork() == network else {
                throw DeviceAPIError(409, "lan_changed", "The local network changed during discovery.")
            }
        }
        try await check()
        guard let local = LanDiscoveryRules.privateIPv4(network.address), network.index != 0 else { throw unavailable() }
        let payload = try query()
        let socket = try LanDatagramSocket(local: local, interface: network.index, multicast: false, broadcast: true)
        let send = {
            for port: UInt16 in [20002,20004] {
                try await check()
                try socket.send(payload, address: UInt32.max, port: port)
            }
        }
        try await send()
        let start = ContinuousClock.now
        var repeated = false, packets = 0
        while start.duration(to: .now) < .milliseconds(2500) && packets < 64 {
            try await check()
            if !repeated && start.duration(to: .now) > .milliseconds(750) { try await send(); repeated = true }
            guard let packet = try socket.receive() else { try await Task.sleep(nanoseconds: 20_000_000); continue }
            packets += 1
            guard [20002,20004].contains(packet.port),
                  LanDiscoveryRules.onLink(packet.host, local: network.address, prefix: network.prefix),
                  let hint = parse(packet.data) else { continue }
            try await check()
            await observations.add(host: packet.host, source: .tplink, service: hint.service, name: hint.name)
        }
        try await check()
    }
    static func crc32(_ bytes: Data) -> UInt32 {
        var crc = UInt32.max
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ (crc & 1 == 1 ? 0xedb88320 : 0) }
        }
        return ~crc
    }
    private static func der(_ tag: UInt8, _ content: Data) -> Data {
        let length = content.count
        let header: [UInt8] = length < 128 ? [tag,UInt8(length)] : [tag,0x82,UInt8(length >> 8),UInt8(length & 255)]
        return Data(header) + content
    }
    private static func unavailable() -> DeviceAPIError {
        DeviceAPIError(503, "lan_discovery_unavailable", "Local network discovery is unavailable.")
    }
}
