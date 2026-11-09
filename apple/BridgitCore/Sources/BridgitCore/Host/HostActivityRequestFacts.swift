import Foundation

/// Measurements from the executing host. Old rows and unavailable wire facts stay absent.
public struct HostActivityRequestFacts: Codable, Sendable {
    public let method: String
    public let endpoint: String
    public private(set) var result: String?
    public let bytesIn: Int?
    public private(set) var bytesOut: Int?
    public let durationMS: Int64?
    public let requestID: String?
    public let sourceIP: String?
    public let agentGeneration: String?
    let denialReason: String?
    let params: String?

    init(job: HostJob, response: HostResponse, started: ContinuousClock.Instant? = nil, request: HostRequest? = nil,
         resultCode: String? = nil, streamedBytes: Int? = nil) {
        let action = request ?? job.request
        agentGeneration = job.generation
        method = action.method
        let operation = HostAPIContract.operation(matching: action)
        endpoint = operation?.path ?? HostLogNote.scrub(action.path, max: 512)
        result = Self.result(response, explicitCode: resultCode)
        denialReason = nil
        params = operation.flatMap { Self.parameters(action, operation: $0) }
        bytesIn = action.bodyBytesMeasured
        bytesOut = streamedBytes ?? (started == nil || response.stream != nil ? nil : response.data.count)
        if let started {
            let duration = started.duration(to: .now).components
            durationMS = duration.seconds * 1_000 + duration.attoseconds / 1_000_000_000_000_000
        } else { durationMS = nil }
        requestID = job.id.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) == nil
            ? nil : HostLogNote.scrub(job.id, max: 128)
        sourceIP = (job.mode == "direct" || job.mode == OwnerDirectHostService.mode) ? job.tailnetSource.flatMap(TailnetAddress.init)?.value : nil
    }
    init(parsedMethod: String, canonicalEndpoint: String, resultCode: String, source: TailnetAddress?,
         bytesIn: Int?, requestID: String, started: ContinuousClock.Instant, denialReason: String? = nil) {
        agentGeneration = nil
        method = parsedMethod
        endpoint = canonicalEndpoint
        result = resultCode
        self.bytesIn = bytesIn
        bytesOut = nil
        let duration = started.duration(to: .now).components
        durationMS = duration.seconds * 1_000 + duration.attoseconds / 1_000_000_000_000
        self.requestID = requestID
        sourceIP = source?.value
        self.denialReason = denialReason.flatMap { RetiredHostCredential(rawValue: $0)?.rawValue }
        params = nil
    }

    func replacingResponse(_ response: HostResponse) -> Self {
        var facts = self
        facts.result = Self.result(response)
        facts.bytesOut = response.data.count
        return facts
    }

    private static func result(_ response: HostResponse, explicitCode: String? = nil) -> String? {
        if (200...299).contains(response.status) { return "ok" }
        let returnedCode = explicitCode ?? (response.status >= 400
            ? (try? JSONSerialization.jsonObject(with: response.data) as? [String: Any])?["error"] as? String
            : nil)
        return returnedCode.flatMap {
            $0.range(of: "^[a-z][a-z0-9_]{0,63}$", options: .regularExpression) == nil ? nil : HostLogNote.scrub($0, max: 64)
        }
    }

    private static func parameters(_ request: HostRequest, operation: HostAPIOperation) -> String? {
        func properties(_ schema: [String: Any]) -> [String: Any] {
            var fields = schema["properties"] as? [String: Any] ?? [:]
            for choice in schema["oneOf"] as? [[String: Any]] ?? [] {
                fields.merge(properties(choice)) { old, new in
                    (old as? [String: Any])?["writeOnly"] as? Bool == true ? old : new
                }
            }
            return fields
        }
        var fields: [String: Any] = [:]
        for (provided, schema) in [(request.query, operation.querySchema), (request.body, operation.bodySchema)] {
            let declared = properties(schema)
            for (key, value) in provided {
                guard let property = declared[key] as? [String: Any] else { continue }
                if property["writeOnly"] as? Bool == true { fields[key] = "[redacted]" }
                else if let text = value as? String, key == "message" { fields[key] = "[\(text.utf16.count) chars]" }
                else if let items = value as? [Any] { fields[key] = "[\(items.count) items]" }
                else if value is [String: Any] { fields[key] = "[object]" }
                else if value is String || value is NSNumber || value is NSNull { fields[key] = value }
            }
        }
        return fields.isEmpty ? nil : try? HostLogNote.encodedRedacted(fields)
    }

}
