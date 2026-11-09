import Foundation

/// Discovery is an untrusted hint, never enrollment or evidence of permission.
struct LanDiscoveryObservation: Codable, Equatable {
    enum Source: String, Codable { case ssdp, onvif, mdns, tplink, tcp }
    let host: String
    let source: Source
    let service: String
    let port: Int?
    let name: String?
    let advertisedModel: String?
    let observedAt: Int64
    var verified = false
    var enrolled = false
    var availability = "observed"

    enum CodingKeys: String, CodingKey {
        case host, source, service, port, name, verified, enrolled, availability
        case advertisedModel = "advertised_model", observedAt = "observed_at"
    }

    static func cleanName(_ raw: String) -> String {
        var value = String(String.UnicodeScalarView(raw.unicodeScalars.filter {
            $0.properties.generalCategory != .control && $0.properties.generalCategory != .format
        })).trimmingCharacters(in: .whitespacesAndNewlines)
        if let prefix = value.range(of: "^[0-9a-fA-F]{12}@", options: .regularExpression) {
            value.removeSubrange(prefix)
        }
        return bounded(value, utf16Limit: 100)
    }

    /// Swift strings retain whole Unicode scalars when enforcing Android's UTF-16 limit.
    static func bounded(_ value: String, utf16Limit: Int) -> String {
        var result = "", used = 0
        for scalar in value.unicodeScalars {
            let size = scalar.value > 0xffff ? 2 : 1
            guard used + size <= utf16Limit else { break }
            result.unicodeScalars.append(scalar)
            used += size
        }
        return result
    }
}

actor LanDiscoveryAccumulator {
    let network: LanInterfaceSelection
    private var values: [LanDiscoveryObservation] = []
    init(network: LanInterfaceSelection) { self.network = network }

    @discardableResult
    func add(host: String, source: LanDiscoveryObservation.Source, service: String,
             port: Int? = nil, name: String = "", model: String = "", now: Date = Date()) -> Bool {
        guard LanDiscoveryRules.onLink(host, local: network.address, prefix: network.prefix),
              port == nil || (1...65535).contains(port!), values.count < 256 else { return false }
        let label = LanDiscoveryObservation.bounded(String(String.UnicodeScalarView(service.unicodeScalars.filter { $0.value >= 32 })), utf16Limit: 160)
        guard !values.contains(where: { $0.host == host && $0.source == source && $0.service == label && $0.port == port }) else { return false }
        let cleanName = LanDiscoveryObservation.cleanName(name)
        let cleanModel = LanDiscoveryObservation.cleanName(model)
        values.append(LanDiscoveryObservation(host: host, source: source, service: label, port: port,
            name: cleanName.isEmpty ? nil : cleanName, advertisedModel: cleanModel.isEmpty ? nil : cleanModel,
            observedAt: Int64(now.timeIntervalSince1970 * 1000)))
        return true
    }

    func snapshot() -> [LanDiscoveryObservation] { values }
}
