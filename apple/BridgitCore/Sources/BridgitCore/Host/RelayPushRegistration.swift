import Foundation

/// Push tokens are sent only after the authenticated relay challenge advertises that provider.
/// This is transport support, never permission to capture in the background.
public struct RelayPushRegistration {
    public let provider: String
    public let token: String
    public let environment: String?
    public init(apns token: Data, environment: String) throws {
        guard !token.isEmpty, token.count <= 1024, ["sandbox", "production"].contains(environment) else { throw DeviceAPIError(422, "invalid_push_token", "The APNs token or environment is invalid.") }
        self.provider = "apns"; self.environment = environment; self.token = token.map { String(format: "%02x", $0) }.joined()
    }
    public var frame: [String: Any] { ["type": "push", "provider": provider, "token": token, "environment": environment as Any? ?? NSNull()] }
}
