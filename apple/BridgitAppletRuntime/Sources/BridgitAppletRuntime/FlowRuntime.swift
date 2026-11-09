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
        case .paused: return "This applet is paused. Resume it before starting work."
        case .executionStopped: return "This applet run was stopped. Start a new run to continue."
        }
    }

    case invalid(String), unavailable(String), missingReference(String), revoked, paused, executionStopped
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
        if let policy = f.removeValue(forKey: "on_error") {
            guard let text = policy.text else { throw AppletRuntimeError.invalid("Error policy must be stop, skip or retry") }
            onError = text
        } else { onError = "stop" }
        guard ["stop", "skip", "retry"].contains(onError) else { throw AppletRuntimeError.invalid("Unknown error policy") }
        if let retry = f.removeValue(forKey: "retries") {
            guard onError == "retry", case .number(let n) = retry, n.rounded() == n, (1...3).contains(n) else { throw AppletRuntimeError.invalid("Retries require retry policy and 1–3 attempts") }
            retries = Int(n)
        } else { retries = onError == "retry" ? 1 : 0 }
        if onError == "retry", ["ask_model", "appraise", "kitchen"].contains(type) || onError == "retry" && ["hear", "speak"].contains(type) && f["allow_cloud"] == .bool(true) {
            throw AppletRuntimeError.invalid("Paid operations cannot retry implicitly")
        }
        if let condition = f["only_if"] { try FlowCondition.validate(condition, allowInputs: true) }
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
    private let retrySleep: @Sendable (UInt64) async throws -> Void
    public init(host: any AppletHost) { self.host = host; retrySleep = { try await Task.sleep(nanoseconds: $0) } }
    init(host: any AppletHost, retrySleep: @escaping @Sendable (UInt64) async throws -> Void) { self.host = host; self.retrySleep = retrySleep }
    public func run(instance: String, steps: [FlowStep], execution: String = UUID().uuidString, trigger: JSONValue = .object([:]), onlyIf: JSONValue? = nil) async throws -> FlowOutcome {
        guard (1...32).contains(steps.count) else { throw AppletRuntimeError.invalid("Use 1–32 steps") }
        if let onlyIf { try FlowCondition.validate(onlyIf, earlier: [], flow: true) }
        var ids = Set<String>()
        // Refuse unsupported steps before the first side effect.
        for step in steps {
            guard host.supportedSteps.contains(step.type) || ["wait", "stop"].contains(step.type) else { throw AppletRuntimeError.unavailable(step.type) }
            if let condition = step.arguments["only_if"] { try FlowCondition.validate(condition, earlier: ids) }
            if let id = step.id, !ids.insert(id).inserted { throw AppletRuntimeError.invalid("Duplicate step id") }
        }
        try Task.checkCancellation(); try await host.authorize(instance: instance)
        if let onlyIf, try !FlowCondition.matches(onlyIf, results: [:], trigger: trigger) {
            return FlowOutcome(results: [:], skipped: Array(steps.indices))
        }
        var results: [String: JSONValue] = [:], skipped: [Int] = []
        for (index, step) in steps.enumerated() {
            try Task.checkCancellation(); try await host.authorize(instance: instance)
            if let condition = step.arguments["only_if"], try !FlowCondition.matches(condition, results: results, trigger: trigger) {
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
                // Authorization failures are never subject to a step's skip/retry policy.
                try Task.checkCancellation(); try await host.authorize(instance: instance)
                let value: JSONValue
                do {
                    value = try await host.perform(type: step.type, arguments: args, context: AppletActionContext(instance: instance, execution: execution, step: index))
                } catch is CancellationError { throw CancellationError() }
                catch AppletRuntimeError.revoked { throw AppletRuntimeError.revoked }
                catch AppletRuntimeError.paused { throw AppletRuntimeError.paused }
                catch AppletRuntimeError.executionStopped { throw AppletRuntimeError.executionStopped }
                catch {
                    if let url = error as? URLError, url.code == .cancelled { throw CancellationError() }
                    let failure = StepFailure(error)
                    guard !failure.blocking else { throw error }
                    let safe = ["lookup", "context", "wait", "stop"].contains(step.type) || step.type == "photo" && (args["keep"] == nil || args["keep"] == .bool(false))
                    // Unknown effect outcomes halt even under skip; never deliver the next effect or retry it.
                    if !safe && failure.ambiguous {
                        throw AppletStepFailure(status: 500, code: "unknown_outcome", message: "This action may already have completed. Inspect its result before explicitly running it again.", effectStarted: true)
                    }
                    guard failure.known else { throw error }
                    if step.onError == "skip" { skipped.append(index); break }
                    guard step.onError == "retry", failure.transient, attempt < step.retries else { throw error }
                    let delay: [UInt64] = [2, 5, 10]
                    try await retrySleep(delay[attempt] * 1_000_000_000); attempt += 1
                    continue
                }
                try Task.checkCancellation(); try await host.authorize(instance: instance)
                if let id = step.id { results[id] = value }
                break
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
 }

/// Native hosts can declare a known rejection before any effect, without guessing from a thrown message.
public struct AppletStepFailure: LocalizedError, Sendable {
    public let status: Int
    public let code: String
    public let message: String
    public let effectStarted: Bool?
    public var errorDescription: String? { message }
    public init(status: Int, code: String, message: String, effectStarted: Bool? = nil) {
        self.status = status; self.code = code; self.message = message; self.effectStarted = effectStarted
    }
}
private struct StepFailure {
    let known: Bool
    let blocking: Bool
    let transient: Bool
    let ambiguous: Bool
    init(_ error: Error) {
        let status: Int?, code: String, effectStarted: Bool?
        if let failure = error as? AppletStepFailure {
            status = failure.status; code = failure.code; effectStarted = failure.effectStarted
        } else if let failure = error as? URLError {
            status = [.userAuthenticationRequired, .userCancelledAuthentication].contains(failure.code) ? 401 : failure.code == .timedOut ? 408 : 503
            code = "transport_failed"; effectStarted = nil
        } else if error is AppletRuntimeError {
            status = 422; code = "invalid_parameter"; effectStarted = false
        } else { status = nil; code = "unknown_outcome"; effectStarted = nil }
        known = status != nil
        blocking = status == 401 || status == 403 || ["agent_not_allowed", "unauthorized", "agent_revoked", "permission_denied"].contains(code)
        transient = status.map { [408, 409, 429].contains($0) || $0 >= 500 } ?? false
        ambiguous = code == "unknown_outcome" || effectStarted != false && (status == nil || status == 408 || (status ?? 0) >= 500)
    }
}
