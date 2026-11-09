import Darwin
import Foundation

/// One host-owned store. Network scope comes from the host's network identity
/// provider; callers must not select a scope through request parameters.
actor LanDiscoveryHistoryStore {
    private let file: URL
    private var history: LanDiscoveryHistory
    init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        file = directory.appendingPathComponent("lan-history.json")
        if FileManager.default.fileExists(atPath: file.path) {
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            guard let size = attributes[.size] as? NSNumber, size.intValue <= 4 * 1024 * 1024 else { throw Self.unavailable() }
            do { history = try JSONDecoder().decode(LanDiscoveryHistory.self, from: Data(contentsOf: file)) }
            catch { throw Self.unavailable() } // Never silently overwrite unreadable inventory.
        } else { history = LanDiscoveryHistory() }
    }
    func read(scope: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> [LanDiscoveredDevice] {
        try Self.validate(scope)
        return history.read(scope: scope, now: now)
    }
    func update(scope: String, observed: [LanDiscoveredDevice], reliable: Bool,
                now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> [LanDiscoveredDevice] {
        try Self.validate(scope)
        guard now >= 0 else { throw Self.unavailable() }
        var next = history
        let devices = next.update(scope: scope, observed: observed, reliable: reliable, now: now)
        let data = try JSONEncoder().encode(next)
        guard data.count <= 4 * 1024 * 1024 else { throw Self.unavailable() }
        // Only publish the new in-memory state after the atomic disk write.
        let staged = file.deletingLastPathComponent().appendingPathComponent(".lan-history-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: staged) }
        try data.write(to: staged, options: [.withoutOverwriting, .completeFileProtection])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: staged.path)
        guard rename(staged.path, file.path) == 0 else { throw Self.unavailable() }
        history = next
        return devices
    }
    private static func validate(_ scope: String) throws {
        guard !scope.isEmpty, scope.utf8.count <= 256, !scope.unicodeScalars.contains(where: { $0.value < 32 }) else { throw unavailable() }
    }
    private static func unavailable() -> DeviceAPIError {
        DeviceAPIError(503, "lan_history_unavailable", "The saved network inventory is unavailable.")
    }
}
