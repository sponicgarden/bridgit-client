import CryptoKit
import Foundation
import Security

public enum PairingClaimError: LocalizedError, Equatable {
    case alreadyClaimed
    case expired

    public var errorDescription: String? {
        switch self {
        case .alreadyClaimed: "This pairing link was already used. Ask the device owner for a new link."
        case .expired: "This pairing link expired. Ask the device owner for a new link."
        }
    }
}

/// Single-use pairing links (`claim=1`): the link carries a one-time claim token. This device makes its own
/// token, sends only its SHA-256 to the phone (POST /v1/portal/claim, authorised by the link token), and
/// keeps the new token. The link token stops working once claimed, so a forwarded link is useless.
public enum PairingClaim {
    /// `rly_` + base64url without padding of 32 random bytes (43 characters).
    public static func newToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let ok = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(ok == errSecSuccess, "SecRandomCopyBytes failed")
        return "rly_" + base64URL(Data(bytes))
    }

    public static func base64URL(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Lowercase hex SHA-256 of the token's UTF-8 bytes.
    public static func tokenHash(_ token: String) -> String {
        SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Claims a `claim=1` link and returns the pairing to save, holding the new token. Throws on any failure;
    /// nothing is saved by this function.
    public static func claim(_ link: Pairing, http: URLSession = .shared, outboundAccess: OutboundAccess = .unrestricted) async throws -> Pairing {
        guard link.needsClaim == true else { return link }
        let token = newToken()
        let client = RelayClient(pairing: link, http: http, outboundAccess: outboundAccess)
        let r: RelayResponse
        do {
            r = try await client.call(method: "POST", path: "/v1/portal/claim", query: [:],
                                      body: ["token_hash": tokenHash(token)], wait: 20,
                                      deadline: Int(Date().timeIntervalSince1970 * 1000) + 30_000)
        } catch RelayError.failed(409, _) {
            throw PairingClaimError.alreadyClaimed
        } catch RelayError.failed(410, _) {
            throw PairingClaimError.expired
        }
        try checkClaimed(r)
        var saved = link
        saved.token = token
        saved.needsClaim = nil
        saved.addedAt = Date()
        return saved
    }

    public static func checkClaimed(_ r: RelayResponse) throws {
        let obj = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any]
        guard r.status == 200, obj?["claimed"] as? Bool == true else {
            throw RelayError.failed(r.status, (obj?["error"] as? String) ?? "the phone didn't confirm the pairing")
        }
    }
}
