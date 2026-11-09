import Darwin
import Foundation
import dnssd

/// One bounded DNS-SD operation. DNS references and callback context ownership
/// are confined to the same queue, as required by DNSServiceSetDispatchQueue.
final class LanBonjourOperation: @unchecked Sendable {
    struct Service: Equatable, Sendable {
        let name: String
        let type: String
        let domain: String
        let interface: UInt32
    }
    struct Resolution: Sendable {
        let host: String
        let port: UInt16
        let attributes: [String: Data]
        let interface: UInt32
    }
    enum Event: Sendable {
        case service(Service), resolved(Resolution), address(String)
    }
    let events: AsyncThrowingStream<Event, Error>
    private let continuation: AsyncThrowingStream<Event, Error>.Continuation
    private let queue = DispatchQueue(label: "cc.bridgit.discovery.bonjour")
    private var reference: DNSServiceRef?
    private var finished = false
    private var ownsContext = false

    private init(duration: TimeInterval,
                 create: @escaping @Sendable (UnsafeMutablePointer<DNSServiceRef?>, UnsafeMutableRawPointer) -> DNSServiceErrorType) {
        let stream = AsyncThrowingStream<Event, Error>.makeStream(bufferingPolicy: .bufferingOldest(128))
        events = stream.stream
        continuation = stream.continuation
        continuation.onTermination = { [weak self] _ in self?.stop() }
        queue.async { [self] in
            guard !self.finished else { return }
            self.ownsContext = true
            let context = Unmanaged.passRetained(self).toOpaque()
            let error = create(&self.reference, context)
            guard error == kDNSServiceErr_NoError, let reference = self.reference else {
                self.finish(error: Self.failure(error)); return
            }
            let scheduled = DNSServiceSetDispatchQueue(reference, self.queue)
            guard scheduled == kDNSServiceErr_NoError else { self.finish(error: Self.failure(scheduled)); return }
            self.queue.asyncAfter(deadline: .now() + duration) { [weak self] in self?.finish() }
        }
    }

    func stop() { queue.async { self.finish() } }

    private func finish(error: Error? = nil) {
        guard !finished else { return }
        finished = true
        if let reference { DNSServiceRefDeallocate(reference); self.reference = nil }
        continuation.finish(throwing: error)
        if ownsContext {
            ownsContext = false
            Unmanaged.passUnretained(self).release()
        }
    }

    private static func failure(_ code: DNSServiceErrorType) -> DeviceAPIError {
        DeviceAPIError(503, "mdns_unavailable", code == kDNSServiceErr_PolicyDenied
                       ? "Local network access is required for Bonjour discovery."
                       : "Bonjour discovery is unavailable.")
    }

    static func browse(type: String, interface: UInt32, duration: TimeInterval = 2) -> LanBonjourOperation {
        LanBonjourOperation(duration: duration) { reference, context in
            DNSServiceBrowse(reference, 0, interface, type, "local.", { _, flags, index, error, name, type, domain, context in
                guard let context else { return }
                let operation = Unmanaged<LanBonjourOperation>.fromOpaque(context).takeUnretainedValue()
                guard error == kDNSServiceErr_NoError else { operation.finish(error: LanBonjourOperation.failure(error)); return }
                guard flags & kDNSServiceFlagsAdd != 0, let name, let type, let domain else { return }
                operation.continuation.yield(.service(Service(name: String(cString: name), type: String(cString: type),
                                                             domain: String(cString: domain), interface: index)))
            }, context)
        }
    }

    static func resolve(_ service: Service, duration: TimeInterval = 0.75) -> LanBonjourOperation {
        LanBonjourOperation(duration: duration) { reference, context in
            DNSServiceResolve(reference, 0, service.interface, service.name, service.type, service.domain,
                              { _, _, index, error, _, host, port, length, text, context in
                guard let context else { return }
                let operation = Unmanaged<LanBonjourOperation>.fromOpaque(context).takeUnretainedValue()
                guard error == kDNSServiceErr_NoError else { operation.finish(error: LanBonjourOperation.failure(error)); return }
                guard let host else { return }
                var attributes: [String: Data] = [:]
                if let text {
                    for key in ["fn", "md", "model", "am", "ty", "product"] {
                        var count: UInt8 = 0
                        if let value = TXTRecordGetValuePtr(length, text, key, &count) {
                            attributes[key] = Data(bytes: value, count: Int(count))
                        }
                    }
                }
                operation.continuation.yield(.resolved(Resolution(host: String(cString: host), port: UInt16(bigEndian: port),
                                                                  attributes: attributes, interface: index)))
                operation.finish()
            }, context)
        }
    }

    static func addresses(host: String, interface: UInt32, duration: TimeInterval = 0.75) -> LanBonjourOperation {
        LanBonjourOperation(duration: duration) { reference, context in
            DNSServiceGetAddrInfo(reference, 0, interface, DNSServiceProtocol(kDNSServiceProtocol_IPv4), host,
                                  { _, flags, _, error, _, address, _, context in
                guard let context else { return }
                let operation = Unmanaged<LanBonjourOperation>.fromOpaque(context).takeUnretainedValue()
                guard error == kDNSServiceErr_NoError else { operation.finish(error: LanBonjourOperation.failure(error)); return }
                guard flags & kDNSServiceFlagsAdd != 0, let address, address.pointee.sa_family == AF_INET else { return }
                let number = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
                operation.continuation.yield(.address(LanDiscoveryRules.host(number)))
                if flags & kDNSServiceFlagsMoreComing == 0 { operation.finish() }
            }, context)
        }
    }
}
