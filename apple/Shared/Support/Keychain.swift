import Foundation
import Security

/// Small wrapper over the iOS Keychain for the sign-in session and relay tokens.
/// Items stay on this device only (ThisDeviceOnly) and are readable after first unlock.
enum Keychain {
    private static let service = Bundle.main.bundleIdentifier ?? "cc.bridgit.app"
    private static let fixtureLock = NSLock()
    private static var fixtureValues: [String: String] = [:]

    @discardableResult static func set(_ value: String?, for key: String) -> Bool {
        if AppleFixtureWorkspace.enabled {
            fixtureLock.lock(); defer { fixtureLock.unlock() }; fixtureValues[key] = value; return true
        }
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: key]
        guard let value else {
            let status = SecItemDelete(base as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        guard let data = value.data(using: .utf8) else { return false }
        // Updating atomically keeps the old credential if the Keychain refuses a write.
        let status = SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ key: String) -> String? {
        if AppleFixtureWorkspace.enabled {
            fixtureLock.lock(); defer { fixtureLock.unlock() }; return fixtureValues[key]
        }
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
