import Foundation

/// Shared owner/API scan coordinator. Hold one instance per host so concurrent
/// callers cannot start overlapping scans. This is not yet a native/API adapter.
actor LanDiscoverySession {
    enum Method: String, CaseIterable { case ssdp, onvif, mdns, tplink, tcpInventory = "tcp_inventory" }
    struct Outcome: Codable, Equatable { let method: String; let status: String }
    struct Result {
        let network: LanInterfaceSelection
        let observations: [LanDiscoveryObservation]
        let methods: [Outcome]
        let complete = false // Discovery silence never proves absence.
        var reliable: Bool { methods.allSatisfy { $0.status == "completed" } }
    }
    struct Context {
        let network: LanInterfaceSelection
        let targets: [String]
        let observations: LanDiscoveryAccumulator
        let authorize: @Sendable () async throws -> Void
        let currentNetwork: @Sendable () async throws -> LanInterfaceSelection
    }
    typealias Runner = @Sendable (Method, Context) async throws -> Void
    private var running = false
    private let runMethod: Runner
    private let deadline: Duration
    init(deadline: Duration = .seconds(25), runMethod: @escaping Runner = { method, context in
        switch method {
        case .ssdp, .onvif:
            try await LanDatagramDiscovery.scan(method == .ssdp ? .ssdp : .onvif,
                network: context.network, observations: context.observations,
                authorize: context.authorize, currentNetwork: context.currentNetwork)
        case .mdns:
            try await LanBonjourDiscovery.scan(network: context.network, observations: context.observations,
                authorize: context.authorize, currentNetwork: context.currentNetwork)
        case .tplink:
            try await LanTPLinkDiscovery.scan(network: context.network, observations: context.observations,
                authorize: context.authorize, currentNetwork: context.currentNetwork)
        case .tcpInventory:
            try await LanTCPInventory.scan(network: context.network, hosts: context.targets, observations: context.observations,
                authorize: context.authorize, currentNetwork: context.currentNetwork)
        }
    }) {
        self.deadline = max(.milliseconds(1), min(deadline, .seconds(25)))
        self.runMethod = runMethod
    }
    func scan(hosts: [String] = [], subnet: Bool = false, timeout: Duration? = nil,
              authorize: @escaping @Sendable () async throws -> Void,
              currentNetwork: @escaping @Sendable () async throws -> LanInterfaceSelection) async throws -> Result {
        try Task.checkCancellation()
        try await authorize()
        guard !running else { throw DeviceAPIError(409, "lan_busy", "A local network operation is already running.") }
        running = true
        defer { running = false }
        let network = try await currentNetwork()
        let targets = try LanDiscoveryRules.targets(hosts: hosts, subnet: subnet, local: network.address, prefix: network.prefix)
        let observations = LanDiscoveryAccumulator(network: network)
        let context = Context(network: network, targets: targets, observations: observations,
                              authorize: authorize, currentNetwork: currentNetwork)
        let runner = runMethod, deadline = max(.milliseconds(1), min(deadline, timeout ?? deadline))
        let outcomes = try await withThrowingTaskGroup(of: [Outcome].self) { race in
            race.addTask {
                try await withThrowingTaskGroup(of: Outcome.self) { methods in
                    for method in Method.allCases {
                        methods.addTask {
                            try Task.checkCancellation()
                            try await authorize()
                            do {
                                try await runner(method, context)
                                try Task.checkCancellation()
                                try await authorize()
                                return Outcome(method: method.rawValue, status: "completed")
                            } catch is CancellationError { throw CancellationError() }
                            catch let error as DeviceAPIError where error.status == 401 || error.status == 403 || error.code == "lan_changed" {
                                throw error
                            } catch {
                                try Task.checkCancellation()
                                try await authorize()
                                return Outcome(method: method.rawValue, status: "unavailable")
                            }
                        }
                    }
                    var outcomes: [Outcome] = []
                    for try await outcome in methods { outcomes.append(outcome) }
                    return outcomes.sorted { $0.method < $1.method }
                }
            }
            race.addTask {
                try await Task.sleep(for: deadline)
                throw DeviceAPIError(504, "lan_timeout", "Local network discovery timed out.")
            }
            defer { race.cancelAll() }
            return try await race.next() ?? []
        }
        try Task.checkCancellation()
        try await authorize()
        guard try await currentNetwork() == network else {
            throw DeviceAPIError(409, "lan_changed", "The local network changed during discovery.")
        }
        return Result(network: network, observations: await observations.snapshot(), methods: outcomes)
    }
}
