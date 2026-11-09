import Foundation

/// A transport seam for local protocol tests; production always uses HTTPS/WSS with normal
/// platform certificate validation and refuses HTTP redirects carrying identity proofs.
public protocol RelayHostTransport: AnyObject {
    func post(_ request: URLRequest) async throws -> (Data, Int)
    func open(_ request: URLRequest) async throws
    func send(_ text: String) async throws
    func receive() async throws -> Data
    func close() async
}
public actor URLSessionHostTransport: RelayHostTransport {
    private let http: URLSession
    private let outboundAccess: OutboundAccess
    private var socket: URLSessionWebSocketTask?
    public init(http: URLSession? = nil, outboundAccess: OutboundAccess = .unrestricted) {
        self.http = http ?? URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)
        self.outboundAccess = outboundAccess
    }
    public func post(_ request: URLRequest) async throws -> (Data, Int) {
        try outboundAccess.require()
        let (data, response) = try await http.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw DeviceAPIError(502, "invalid_relay_response", "The relay response is invalid.") }
        return (data, response.statusCode)
    }
    public func open(_ request: URLRequest) async throws {
        try outboundAccess.require()
        let socket = http.webSocketTask(with: request); socket.maximumMessageSize = 2 * 1024 * 1024; self.socket = socket; socket.resume()
    }
    public func send(_ text: String) async throws {
        try outboundAccess.require()
        guard let socket else { throw CancellationError() }
        try await socket.send(.string(text))
    }
    public func receive() async throws -> Data {
        try outboundAccess.require()
        guard let socket else { throw CancellationError() }
        let message = try await socket.receive()
        try outboundAccess.require()
        switch message { case .data(let bytes): return bytes; case .string(let text): return Data(text.utf8); @unknown default: throw CancellationError() }
    }
    public func close() { socket?.cancel(with: .goingAway, reason: nil); socket = nil }
}
