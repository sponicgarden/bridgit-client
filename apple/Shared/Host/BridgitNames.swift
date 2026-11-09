import BridgitCore
import Foundation

/// This device's signed connection to the names service, shared by every names call so the install
/// registers once per launch. Uses the same install identity as feedback and web linking.
actor BridgitNamesTransport {
    private var client: InstallClient?

    func call(_ method: String, _ path: String, _ body: [String: Any]?) async throws -> (Int, [String: Any]) {
        if client == nil {
            let signer = try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "Bridgit") + ".install-signing.v1")
            client = InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access)
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        do { return try await client!.teamCall(method: method, path: path, body: body, appVersion: version) }
        catch let error as DeviceAPIError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw DeviceAPIError(503, "team_unavailable", "The names service can't be reached: " + error.localizedDescription) }
    }
}

extension DeviceDomainNameService {
    static let cacheKey = "bridgit.domain-name.v1"

    /// The names service for this app; nil in the offline test workspace.
    static var platform: DeviceDomainNameService? {
        guard !AppleFixtureWorkspace.enabled else { return nil }
        let transport = BridgitNamesTransport()
        let defaults = AppleFixtureWorkspace.defaults
        return DeviceDomainNameService(
            call: { method, path, body in try await transport.call(method, path, body) },
            load: { defaults.data(forKey: cacheKey) },
            store: { data in
                if let data { defaults.set(data, forKey: cacheKey) } else { defaults.removeObject(forKey: cacheKey) }
            })
    }
}
