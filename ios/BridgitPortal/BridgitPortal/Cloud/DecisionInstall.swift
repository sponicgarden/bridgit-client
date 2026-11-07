import Foundation
import CryptoKit
import BridgitAppletRuntime

/// Uses the same SPKI-derived install ID and P1363 signatures as the Android client.
struct DecisionInstall {
    let key: P256.Signing.PrivateKey

    init(owner: String) throws {
        let stored = try Keychain.getOrCreate("applet-signing-v1-" + owner) {
            P256.Signing.PrivateKey().rawRepresentation.base64EncodedString()
        }
        guard let data = Data(base64Encoded: stored) else { throw AppletRuntimeError.invalid("Unreadable service key") }
        key = try P256.Signing.PrivateKey(rawRepresentation:data)
    }
    init(key: P256.Signing.PrivateKey) { self.key = key }

    var id: String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz234567")
        var pending = 0, bits = 0, result = "bgi_"
        for byte in SHA256.hash(data:key.publicKey.derRepresentation).prefix(15) {
            pending = (pending << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                result.append(alphabet[(pending >> bits) & 31])
            }
            pending &= (1 << bits) - 1
        }
        return result
    }

    func sign(_ request: inout URLRequest, at: Int64, nonce: String) throws {
        guard let url = request.url else { throw AppletRuntimeError.invalid("Missing service URL") }
        let digest = SHA256.hash(data:request.httpBody ?? Data()).map { String(format:"%02x",$0) }.joined()
        let path = url.path + (url.query.map { "?" + $0 } ?? "")
        let text = ["bridgit-install-v1",id,request.httpMethod ?? "POST",path,String(at),nonce,digest].joined(separator:"\n")
        let signature = try key.signature(for:Data(text.utf8)).rawRepresentation.base64EncodedString()
        request.setValue(nil, forHTTPHeaderField:"Authorization")
        request.setValue(id, forHTTPHeaderField:"X-Bridgit-Install")
        request.setValue("at=\(at),nonce=\(nonce),sig=\(signature)", forHTTPHeaderField:"X-Bridgit-Signature")
    }

    static func limits(_ budget: JSONValue?) throws -> [JSONValue] {
        let raw: [JSONValue]
        if case .array(let items)? = budget?["limits"] ?? budget {
            if let fields = budget?.fields, !Set(fields.keys).isSubset(of:["limits","on_forecast"]) {
                throw AppletRuntimeError.invalid("Unknown applet budget field")
            }
            raw = items
        } else {
            guard budget == nil || budget == .null || budget?.fields?.keys.allSatisfy({ ["day_usd","month_usd"].contains($0) }) == true,
                  case .number(let day) = budget?["day_usd"] ?? .number(1),
                  case .number(let month) = budget?["month_usd"] ?? .number(max(10,day)), month >= day else {
                throw AppletRuntimeError.invalid("Invalid applet budget")
            }
            raw = [.object(["usd":.number(day),"per":.string("day")]), .object(["usd":.number(month),"per":.string("month")])]
        }
        guard (1...8).contains(raw.count) else { throw AppletRuntimeError.invalid("Applet budget needs 1–8 limits") }
        var ids = Set<String>()
        return try raw.enumerated().map { index, item in
            let id = item["id"] ?? .string("l" + String(index+1))
            guard var fields = item.fields, Set(fields.keys).isSubset(of:["id","unit","usd","per","check_at"]), let name = id.text,
                  name.range(of:#"\A[a-z0-9][a-z0-9_-]{0,23}\z"#,options:.regularExpression) != nil, ids.insert(name).inserted,
                  case .number(let usd)? = item["usd"], (0.01...100).contains(usd),
                  ["hour","day","week","month"].contains(item["per"]?.text),
                  (item["unit"] ?? .string("usd")) == .string("usd") else { throw AppletRuntimeError.invalid("Invalid applet budget limit") }
            fields["id"] = id
            if let check = item["check_at"] {
                let values: [JSONValue]
                switch check {
                case .number: values = [check]
                case .array(let list): values = list
                case .null: values = []
                default: throw AppletRuntimeError.invalid("Invalid applet budget thresholds")
                }
                fields["check_at"] = .array(values)
                guard values.count <= 4,
                      values.allSatisfy({ if case .number(let x) = $0 { return x > 0 && x < 1 }; return false }) else {
                    throw AppletRuntimeError.invalid("Invalid applet budget thresholds")
                }
            }
            return .object(fields)
        }
    }
}
