import Foundation

public enum AppletStorageLocation {
    /// Release keeps its existing data. Development never opens or migrates that database.
    public static func directory(applicationSupport: URL, bundleIdentifier: String, development: Bool) -> URL {
        let root = applicationSupport.appendingPathComponent("Bridgit", isDirectory: true)
        guard development else { return root.appendingPathComponent("Applets", isDirectory: true) }
        let id = bundleIdentifier.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        return root.appendingPathComponent("Development", isDirectory: true).appendingPathComponent(id, isDirectory: true).appendingPathComponent("Applets", isDirectory: true)
    }
}
