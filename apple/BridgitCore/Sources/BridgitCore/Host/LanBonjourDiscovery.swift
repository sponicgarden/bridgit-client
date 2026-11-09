import Foundation

/// Fixed catalog discovery with one bounded resolver, matching Android 3347.
/// The caller must have selected a physical LAN and obtained OS network consent.
enum LanBonjourDiscovery {
    struct Result: Sendable {
        let resolution: LanBonjourOperation.Resolution
        let addresses: [String]
    }
    typealias Browse = @Sendable (String, UInt32) -> AsyncThrowingStream<LanBonjourOperation.Event, Error>
    typealias Resolve = @Sendable (LanBonjourOperation.Service) async throws -> Result?

    static func scan(network: LanInterfaceSelection, observations: LanDiscoveryAccumulator,
                     authorize: @escaping @Sendable () async throws -> Void,
                     currentNetwork: @escaping @Sendable () async throws -> LanInterfaceSelection,
                     browse: @escaping Browse = { type, index in
                         LanBonjourOperation.browse(type: type, interface: index).events
                     }, resolve: @escaping Resolve = { try await resolveService($0) }) async throws {
        let pending = AsyncStream<LanBonjourOperation.Service>.makeStream(bufferingPolicy: .bufferingOldest(128))
        let budget = Budget()
        let check: @Sendable () async throws -> Void = {
            try Task.checkCancellation()
            try await authorize()
            guard try await currentNetwork() == network else {
                throw DeviceAPIError(409, "lan_changed", "The local network changed during discovery.")
            }
        }
        try await check()
        defer { pending.continuation.finish() }
        try await withThrowingTaskGroup(of: Bool.self) { tasks in
            tasks.addTask {
                for await service in pending.stream {
                    try await check()
                    let resolved: Result?
                    do { resolved = try await resolve(service) }
                    catch let error as DeviceAPIError where error.code == "mdns_unavailable" {
                        // Android ignores an individual NSD resolution failure.
                        // Authority/network checks remain outside this recovery path.
                        try await check()
                        continue
                    }
                    guard let result = resolved else { continue }
                    try await check()
                    guard result.resolution.interface == network.index else { continue }
                    let attributes = result.resolution.attributes
                    func text(_ key: String) -> String { attributes[key].flatMap { String(data: $0, encoding: .utf8) } ?? "" }
                    let friendly = service.type == "_googlecast._tcp." ? text("fn") : ""
                    let keys: [String]
                    switch service.type {
                    case "_googlecast._tcp.": keys = ["md"]
                    case "_airplay._tcp.": keys = ["model"]
                    case "_raop._tcp.": keys = ["am"]
                    case "_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.": keys = ["ty", "product"]
                    default: keys = []
                    }
                    let model = keys.map(text).first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? ""
                    for address in result.addresses {
                        try await check()
                        await observations.add(host: address, source: .mdns, service: service.type,
                            port: Int(result.resolution.port),
                            name: friendly.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? service.name : friendly,
                            model: model)
                    }
                }
                return false
            }
            tasks.addTask {
                let types = LanDiscoveryCatalog.services.map(\.type)
                for start in stride(from: 0, to: types.count, by: LanDiscoveryCatalog.batchSize) {
                    try await check()
                    try await withThrowingTaskGroup(of: Void.self) { batch in
                        for type in types[start..<min(start + LanDiscoveryCatalog.batchSize, types.count)] {
                            batch.addTask {
                                try await check()
                                for try await event in browse(type, network.index) {
                                    try await check()
                                    guard case let .service(service) = event,
                                          service.interface == network.index,
                                          service.type == type, service.domain == "local.",
                                          await budget.admit(service) else { continue }
                                    pending.continuation.yield(service)
                                }
                            }
                        }
                        try await batch.waitForAll()
                    }
                }
                return true
            }
            while let browsingFinished = try await tasks.next() {
                if browsingFinished {
                    pending.continuation.finish()
                    tasks.cancelAll() // Android ends queued resolution when browsing ends.
                    break
                }
            }
        }
        try await check()
    }

    private static func resolveService(_ service: LanBonjourOperation.Service) async throws -> Result? {
        let deadline = Date().addingTimeInterval(0.75)
        let operation = LanBonjourOperation.resolve(service)
        defer { operation.stop() }
        for try await event in operation.events {
            guard case let .resolved(resolution) = event else { continue }
            try Task.checkCancellation()
            guard resolution.interface == service.interface else { return nil }
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0 else { return nil }
            let lookup = LanBonjourOperation.addresses(host: resolution.host, interface: service.interface, duration: remaining)
            defer { lookup.stop() }
            var addresses: [String] = []
            for try await event in lookup.events {
                if case let .address(address) = event, addresses.count < 256 { addresses.append(address) }
            }
            return Result(resolution: resolution, addresses: addresses)
        }
        return nil
    }

    actor Budget {
        private var seen = Set<String>()
        private var counts: [String: Int] = [:]
        private var total = 0
        func admit(_ service: LanBonjourOperation.Service) -> Bool {
            // Bound the deduplication set as well as admitted work under a hostile responder.
            guard total < 192, counts[service.type, default: 0] < 24,
                  seen.insert(service.type + ":" + service.name).inserted else { return false }
            counts[service.type, default: 0] += 1
            total += 1
            return true
        }
    }
}
