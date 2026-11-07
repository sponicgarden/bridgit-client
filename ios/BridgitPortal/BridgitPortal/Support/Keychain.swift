import Foundation
import Security

/// Small wrapper over the iOS Keychain for the sign-in session and relay tokens.
/// Items stay on this device only (ThisDeviceOnly) and are readable after first unlock.
enum Keychain {
    private static let service = "cc.bridgit.portal"

    static func set(_ value: String?, for key: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: key]
        SecItemDelete(base as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    /// Create once, including when another window or process won the race.
    static func getOrCreate(_ key: String, generate: () -> String) throws -> String {
        if let existing = get(key) { return existing }
        let value = generate()
        let add: [String: Any] = [kSecClass as String:kSecClassGenericPassword,
                                  kSecAttrService as String:service, kSecAttrAccount as String:key,
                                  kSecValueData as String:Data(value.utf8),
                                  kSecAttrAccessible as String:kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecSuccess { return value }
        if status == errSecDuplicateItem, let existing = get(key) { return existing }
        throw NSError(domain:NSOSStatusErrorDomain,code:Int(status))
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: key,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
