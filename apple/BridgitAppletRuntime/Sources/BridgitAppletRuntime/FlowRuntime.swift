import Foundation

/// JSON values cross the host boundary without turning untrusted package text into Swift code.
public enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let x = try? c.decode(Bool.self) { self = .bool(x) }
        else if let x = try? c.decode(Double.self) { self = .number(x) }
        else if let x = try? c.decode(String.self) { self = .string(x) }
        else if let x = try? c.decode([JSONValue].self) { self = .array(x) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let x): try c.encode(x)
        case .number(let x): try c.encode(x)
        case .string(let x): try c.encode(x)
        case .array(let x): try c.encode(x)
        case .object(let x): try c.encode(x)
        }
    }
    public var text: String? { if case .string(let x) = self { return x }; return nil }
    public var fields: [String: JSONValue]? { if case .object(let x) = self { return x }; return nil }
    public subscript(_ key: String) -> JSONValue? { fields?[key] }
}

public enum AppletRuntimeError: LocalizedError, Equatable {
    public var errorDescription: String? {
        switch self {
        case .invalid(let message): return message
        case .unavailable(let step): return "This device cannot perform " + step
        case .missingReference(let path): return "Missing applet reference: " + path
        case .revoked: return "Applet permission was revoked"
        }
    }

    case invalid(String), unavailable(String), missingReference(String), revoked
}

/// Platform hosts enforce permissions, cloud consent, provider selection and budgets at every action.
/// A supported capability is an implementation, never a permission grant.
public struct AppletActionContext: Sendable {
    public let instance: String
    public let execution: String
    public let step: Int
    /// Stable across retries; hosts must use this key for externally visible effects.
    public var idempotencyKey: String { "\(execution):\(step)" }
}

public protocol AppletHost: Sendable {
    var supportedSteps: Set<String> { get }
    func perform(type: String, arguments: [String: JSONValue], context: AppletActionContext) async throws -> JSONValue
    func authorize(instance: String) async throws
}

public struct FlowStep: Sendable {
    public let id: String?
    public let type: String
    public let arguments: [String: JSONValue]
    public let onError: String
    public let retries: Int
    static func hostFields(_ value: JSONValue) throws -> [String: JSONValue] {
        guard var fields = value.fields else { throw AppletRuntimeError.invalid("Step must be an object") }
        guard fields["type"] == .string("service") else { return fields }
        guard fields["mode"] == nil else { throw AppletRuntimeError.invalid("Use operation to select a service operation") }
        let service = fields.removeValue(forKey:"service")?.text
        guard let operation = fields.removeValue(forKey:"operation")?.text else { throw AppletRuntimeError.invalid("Missing service operation") }
        switch (service,operation) {
        case ("chef.signed-edition-budget.v2","scan"), ("chef.signed-edition-budget.v2","observe"):
            fields["type"] = .string("kitchen")
            fields["mode"] = .string(operation)
        case ("appraise.signed-edition-budget.v2","appraise"):
            fields["type"] = .string("appraise")
        default: throw AppletRuntimeError.unavailable("Unknown vision service or operation")
        }
        return fields
    }
    public init(_ value: JSONValue) throws {
        var f = try Self.hostFields(value)
        guard let type = f.removeValue(forKey: "type")?.text else { throw AppletRuntimeError.invalid("Step needs a type") }
        self.type = type
        if let id = f.removeValue(forKey: "id") {
            guard let name = id.text, name.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil else { throw AppletRuntimeError.invalid("Invalid step id") }
            self.id = name
        } else { self.id = nil }
        onError = f.removeValue(forKey: "on_error")?.text ?? "stop"
        guard ["stop", "skip", "retry"].contains(onError) else { throw AppletRuntimeError.invalid("Unknown error policy") }
        if let retry = f.removeValue(forKey: "retries") {
            guard onError == "retry", case .number(let n) = retry, n.rounded() == n, (1...3).contains(n) else { throw AppletRuntimeError.invalid("Retries require retry policy and 1–3 attempts") }
            retries = Int(n)
        } else { retries = onError == "retry" ? 1 : 0 }
        arguments = f
    }
}

public struct FlowOutcome: Equatable, Sendable {
    public let results: [String: JSONValue]
    public let skipped: [Int]
}

/// Executes validated linear flows. Scheduling, package validation and host drivers are separate.
/// This core supports both Apple platforms; it does not claim that a portal is a local host.
public struct FlowRuntime: Sendable {
    public let host: any AppletHost
    public init(host: any AppletHost) { self.host = host }
    public func run(instance: String, steps: [FlowStep], execution: String = UUID().uuidString, trigger: JSONValue = .object([:])) async throws -> FlowOutcome {
        guard (1...32).contains(steps.count) else { throw AppletRuntimeError.invalid("Use 1–32 steps") }
        var ids = Set<String>()
        // Refuse unsupported steps before the first side effect.
        for step in steps {
            guard host.supportedSteps.contains(step.type) || ["wait", "stop"].contains(step.type) else { throw AppletRuntimeError.unavailable(step.type) }
            if let id = step.id, !ids.insert(id).inserted { throw AppletRuntimeError.invalid("Duplicate step id") }
        }
        var results: [String: JSONValue] = [:], skipped: [Int] = []
        for (index, step) in steps.enumerated() {
            try Task.checkCancellation(); try await host.authorize(instance: instance)
            if let condition = step.arguments["only_if"], try !conditionMatches(condition, results: results, trigger: trigger) {
                skipped.append(index); continue
            }
            var args = step.arguments; args.removeValue(forKey: "only_if")
            if step.type == "record", let source = args["for_each"] {
                guard source.fields?.count == 1, source["$step"]?.text != nil,
                      let template = args["row"]?.fields, !template.isEmpty else {
                    throw AppletRuntimeError.invalid("for_each needs a step result and a row template")
                }
                let list = try resolve(source, results:results, trigger:trigger)
                let items: [JSONValue]
                switch list {
                case .array(let array) where array.count <= 50: items = array
                case .null: items = []
                default: throw AppletRuntimeError.invalid("for_each needs a list of at most 50 items")
                }
                args["row"] = .array(try items.map { item in
                    .object(try template.mapValues { cell in
                        if let fields = cell.fields, fields.count == 1, let field = fields["$item"]?.text {
                            return path(item, field) ?? .null
                        }
                        return try resolve(cell, results:results, trigger:trigger)
                    })
                })
                args.removeValue(forKey:"for_each")
            } else {
                args = try args.mapValues { try resolve($0, results: results, trigger: trigger) }
            }
            if step.type == "stop" { break }
            if step.type == "wait" {
                guard case .number(let seconds)? = args["seconds"], seconds.rounded() == seconds, (1...300).contains(seconds) else { throw AppletRuntimeError.invalid("Wait seconds must be 1–300") }
                try await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000); continue
            }
            var attempt = 0
            while true {
                do {
                    try Task.checkCancellation(); try await host.authorize(instance: instance)
                    let value = try await host.perform(type: step.type, arguments: args, context: AppletActionContext(instance: instance, execution: execution, step: index))
                    // Pause/revocation during an awaited service prevents downstream actions/results.
                    try Task.checkCancellation(); try await host.authorize(instance: instance)
                    if let id = step.id { results[id] = value }
                    break
                } catch is CancellationError { throw CancellationError() }
                catch AppletRuntimeError.revoked { throw AppletRuntimeError.revoked }
                catch {
                    if step.onError == "skip" { skipped.append(index); break }
                    guard step.onError == "retry", attempt < step.retries else { throw error }
                    let delay: [UInt64] = [2, 5, 10]
                    try await Task.sleep(nanoseconds: delay[attempt] * 1_000_000_000); attempt += 1
                }
            }
        }
        return FlowOutcome(results: results, skipped: skipped)
    }
    private func path(_ value: JSONValue, _ path: String) -> JSONValue? {
        path.split(separator: ".").reduce(Optional(value)) { $0?[String($1)] }
    }
    private func reference(_ fields: [String: JSONValue], results: [String: JSONValue], trigger: JSONValue) throws -> JSONValue? {
        if let p = fields["$step"]?.text {
            let parts = p.split(separator: ".", maxSplits: 1).map(String.init)
            guard parts.count == 2, let result = results[parts[0]], let value = path(result, parts[1]) else { throw AppletRuntimeError.missingReference(p) }
            return value
        }
        if let p = fields["$trigger"]?.text {
            guard let value = path(trigger, p) else { throw AppletRuntimeError.missingReference(p) }; return value
        }
        return nil
    }
    private func resolve(_ value: JSONValue, results: [String: JSONValue], trigger: JSONValue) throws -> JSONValue {
        switch value {
        case .object(let fields):
            if fields.keys.contains(where: { $0.hasPrefix("$") }) {
                guard fields.count == 1, let ref = try reference(fields, results: results, trigger: trigger) else { throw AppletRuntimeError.invalid("Unsupported or mixed reference") }; return ref
            }
            return .object(try fields.mapValues { try resolve($0, results: results, trigger: trigger) })
        case .array(let values): return .array(try values.map { try resolve($0, results: results, trigger: trigger) })
        default: return value
        }
    }
    private func conditionMatches(_ value: JSONValue, results: [String: JSONValue], trigger: JSONValue) throws -> Bool {
        guard let f = value.fields else { throw AppletRuntimeError.invalid("Condition must be an object") }
        guard ["step", "trigger"].filter({ f[$0] != nil }).count == 1 else { throw AppletRuntimeError.invalid("Condition needs one source") }
        let source: JSONValue?
        if let p = f["step"]?.text {
            let parts = p.split(separator: ".", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { throw AppletRuntimeError.invalid("Condition step path") }
            source = results[parts[0]].flatMap { path($0, parts[1]) }
        } else if let p = f["trigger"]?.text { source = path(trigger, p) }
        else { throw AppletRuntimeError.invalid("Unsupported condition source") }
        let tests = Set(f.keys).subtracting(["step", "trigger"])
        guard tests.count == 1, let test = tests.first else { throw AppletRuntimeError.invalid("Condition needs exactly one test") }
        switch test {
        case "exists": guard case .bool(let expected)? = f[test] else { throw AppletRuntimeError.invalid("exists requires boolean") }; return (source != nil) == expected
        case "is": return source != nil && source == f[test]
        case "is_not": return source != nil && source != f[test]
        case "in": guard case .array(let values)? = f[test] else { throw AppletRuntimeError.invalid("in requires array") }; return source.map { values.contains($0) } ?? false
        case "at_least", "below": guard case .number(let lhs)? = source, case .number(let rhs)? = f[test] else { return false }; return test == "below" ? lhs < rhs : lhs >= rhs
        default: throw AppletRuntimeError.invalid("Unsupported condition test")
        }
    }
}
