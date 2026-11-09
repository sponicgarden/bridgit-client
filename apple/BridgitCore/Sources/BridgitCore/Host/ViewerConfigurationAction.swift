import CoreFoundation
import Foundation

/// Validated owner-control commands. Pairing credentials stay in this in-memory
/// value and the platform Keychain; they never enter configuration operation history.
public enum ViewerConfigurationAction {
    case account, signIn, cancelSignIn, signOut, linkedDevices
    case add(Pairing), remove(String), rename(String, String?), attach(String?), pauseViews(Bool)
    case accountPhones, linkPhone(String), unlinkPhone(String)
    static func validPhoneID(_ id: String) -> Bool {
        (1...128).contains(id.utf8.count) && id.utf8.allSatisfy { byte in
            (48...57).contains(byte) || (65...90).contains(byte) || (97...122).contains(byte) || byte == 45 || byte == 95
        }
    }
    public static func phoneLinkCode(_ input: String) -> String? {
        let code = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.range(of: #"\A[A-HJ-NP-Za-hj-np-z2-9]{4}-?[A-HJ-NP-Za-hj-np-z2-9]{4}\z"#, options: .regularExpression) != nil else { return nil }
        return code.replacingOccurrences(of: "-", with: "").uppercased()
    }
    public init(_ request: HostRequest) throws {
        guard request.query.isEmpty else { throw Self.invalid() }
        let p = request.path.split(separator: "/").map(String.init)
        if request.path == "/v1/account/phones" {
            if request.method == "GET", request.body.isEmpty { self = .accountPhones; return }
            if request.method == "POST", Set(request.body.keys) == ["code"], let input = request.body["code"] as? String,
               let code = Self.phoneLinkCode(input) {
                self = .linkPhone(code); return
            }
        }
        if p.count == 4, p[1] == "account", p[2] == "phones", Self.validPhoneID(p[3]), request.method == "DELETE", request.body.isEmpty {
            self = .unlinkPhone(p[3]); return
        }
        if request.path == "/v1/account", request.body.isEmpty {
            if request.method == "GET" { self = .account; return }
            if request.method == "DELETE" { self = .signOut; return }
        }
        if request.path == "/v1/account/sign-in", request.body.isEmpty {
            if request.method == "POST" { self = .signIn; return }
            if request.method == "DELETE" { self = .cancelSignIn; return }
        }
        if request.path == "/v1/linked-devices" {
            if request.method == "GET", request.body.isEmpty { self = .linkedDevices; return }
            if request.method == "POST", Set(request.body.keys) == ["setup"], let setup = request.body["setup"] as? String, setup.utf8.count <= 65536, let pairing = Pairing.parse(setup) { self = .add(pairing); return }
        }
        if request.path == "/v1/linked-devices/attachment", request.method == "POST", Set(request.body.keys) == ["id"] {
            if request.body["id"] is NSNull { self = .attach(nil); return }
            if let id = request.body["id"] as? String, (1...160).contains(id.count) { self = .attach(id); return }
        }
        if request.path == "/v1/linked-devices/pause", request.method == "POST", Set(request.body.keys) == ["paused"], let value = request.body["paused"] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() { self = .pauseViews(value.boolValue); return }
        if p.count == 4, p[1] == "linked-devices", p[2] == "pairings", Pairing.validDevice(p[3]) {
            if request.method == "DELETE", request.body.isEmpty { self = .remove(p[3]); return }
            if request.method == "PATCH", Set(request.body.keys) == ["name"], request.body["name"] is NSNull { self = .rename(p[3], nil); return }
            if request.method == "PATCH", Set(request.body.keys) == ["name"], let name = request.body["name"] as? String {
                let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !value.isEmpty, value.count <= 60 else { throw Self.invalid() }
                self = .rename(p[3], value); return
            }
        }
        throw Self.invalid()
    }
    private static func invalid() -> DeviceAPIError { .init(422, "invalid_configuration", "Supply only the declared account or linked-device fields.") }
}
