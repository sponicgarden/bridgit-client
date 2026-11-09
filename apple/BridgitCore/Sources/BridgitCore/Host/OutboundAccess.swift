import Foundation

/// A live owner policy consulted at the transport boundary for each new send.
public struct OutboundAccess: Sendable {
    private let paused: @Sendable () -> Bool

    public init(paused: @escaping @Sendable () -> Bool) { self.paused = paused }
    public static let unrestricted = Self(paused: { false })

    public var isPaused: Bool { paused() }

    public func require() throws {
        guard !isPaused else {
            throw DeviceAPIError(503, "external_paused", "External calls are paused. Resume them in Agents.")
        }
    }
}
