import Foundation

/// A caller-filtered observation. Dispatch must still enforce current grants and device state.
public struct DeviceProfile: Codable, Equatable {
    public static let schemaVersion = 1
    public var schemaVersion: Int
    public var profileRevision: Int
    public var observedAt: Date
    public var expiresAt: Date
    public var device: DeviceIdentity
    public var link: DeviceLink?
    public var availability: DeviceAvailability
    public var capabilities: [String: ProfileCapability]

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version", profileRevision = "profile_revision"
        case observedAt = "observed_at", expiresAt = "expires_at"
        case device, link, availability, capabilities
    }

    public init(profileRevision: Int, observedAt: Date, expiresAt: Date, device: DeviceIdentity,
                link: DeviceLink? = nil, availability: DeviceAvailability, capabilities: [String: ProfileCapability]) {
        schemaVersion = Self.schemaVersion; self.profileRevision = profileRevision
        self.observedAt = observedAt; self.expiresAt = expiresAt; self.device = device
        self.link = link; self.availability = availability; self.capabilities = capabilities
    }

    public func isFresh(at now: Date = Date()) -> Bool {
        schemaVersion == Self.schemaVersion && observedAt <= now && expiresAt > now && expiresAt > observedAt
    }

    /// Unknown, stale and paused states fail closed. This is a UI hint, never an authorization token.
    public func canCall(_ id: String, operation: ProfileOperation? = nil, at now: Date = Date()) -> Bool {
        guard isFresh(at: now), availability.state == "online",
              link == nil || link?.state == "active", let cap = capabilities[id] else { return false }
        if let operation, !cap.operations.contains(operation) { return false }
        let execution = cap.constraints.execution
        let readOnlyLease = execution.contains("background_read") && (operation.map { $0.method == "GET" } ?? cap.operations.contains { $0.method == "GET" })
        let contextAllowed = execution.isEmpty || execution.contains("background_awake") ||
            (availability.inForeground && execution.contains("foreground")) || readOnlyLease
        return cap.implemented && cap.supported && cap.enabled && cap.ready && cap.agentAllowed && cap.callable &&
            cap.blockedReason == nil && contextAllowed
    }

    public static func decode(_ data: Data) throws -> DeviceProfile {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            let format = ISO8601DateFormatter()
            format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = format.date(from: text) { return date }
            format.formatOptions = [.withInternetDateTime]
            if let date = format.date(from: text) { return date }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Expected an ISO-8601 timestamp")
        }
        return try decoder.decode(Self.self, from: data)
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        return try encoder.encode(self)
    }
}

public struct DeviceIdentity: Codable, Equatable {
    public var installID: String?
    public var relayDeviceID: String?
    public var name: String
    public var deviceType: String
    public var platform: String
    public var osVersion: String
    public var appVersion: String
    public var apiRevision: Int
    public var services: [String]
    /// present / absent / unknown. An absent value is not permission to probe a sensor.
    public var hardware: [String: String]
    enum CodingKeys: String, CodingKey {
        case relayDeviceID = "relay_device_id"
        case installID = "install_id", deviceType = "device_type", osVersion = "os_version"
        case appVersion = "app_version", apiRevision = "api_revision"
        case name, platform, services, hardware
    }
    public init(installID: String?, name: String, deviceType: String, platform: String, osVersion: String,
                appVersion: String, apiRevision: Int, services: [String], hardware: [String: String], relayDeviceID: String? = nil) {
        self.relayDeviceID = relayDeviceID
        self.installID = installID; self.name = name; self.deviceType = deviceType; self.platform = platform
        self.osVersion = osVersion; self.appVersion = appVersion; self.apiRevision = apiRevision
        self.services = services; self.hardware = hardware
    }
}

public struct DeviceLink: Codable, Equatable {
    public var id: String
    public var role: String
    public var target: String?
    public var allowedServices: [String]
    public var state: String
    enum CodingKeys: String, CodingKey { case id, role, target, state; case allowedServices = "allowed_services" }
    public init(id: String, role: String, target: String?, allowedServices: [String], state: String) {
        self.id = id; self.role = role; self.target = target; self.allowedServices = allowedServices; self.state = state
    }
    /// Descriptive labels only. In particular, a view link never becomes a sensor/control grant.
    public var flavors: [String] { ["view", "sensors", "control"].filter { allowedServices.contains($0) } }
}

public struct DeviceAvailability: Codable, Equatable {
    public var state: String
    public var inForeground: Bool
    enum CodingKeys: String, CodingKey { case state; case inForeground = "in_foreground" }
    public init(state: String, inForeground: Bool) { self.state = state; self.inForeground = inForeground }
}

public struct ProfileOperation: Codable, Equatable {
    public var method: String
    public var path: String
    public init(method: String, path: String) { self.method = method; self.path = path }
}

public struct ProfileConstraints: Codable, Equatable {
    public var execution: [String]
    public var parameters: [String: [String]]?
    public var limits: [String: Double]?
    public init(execution: [String], parameters: [String: [String]]? = nil, limits: [String: Double]? = nil) { self.execution = execution; self.parameters = parameters; self.limits = limits }
}

public struct ProfileCapability: Codable, Equatable {
    public var runtimeID: String
    public var implemented: Bool
    public var supported: Bool
    public var enabled: Bool
    public var ready: Bool
    public var agentAllowed: Bool
    public var callable: Bool
    public var blockedReason: String?
    public var legacyReason: String?
    public var constraints: ProfileConstraints
    public var operations: [ProfileOperation]
    enum CodingKeys: String, CodingKey {
        case runtimeID = "runtime_id", agentAllowed = "agent_allowed"
        case blockedReason = "blocked_reason", legacyReason = "legacy_reason"
        case implemented, supported, enabled, ready, callable, constraints, operations
    }
    public init(runtimeID: String, implemented: Bool, supported: Bool, enabled: Bool, ready: Bool,
                agentAllowed: Bool, callable: Bool, blockedReason: String? = nil, legacyReason: String? = nil,
                constraints: ProfileConstraints = .init(execution: []), operations: [ProfileOperation] = []) {
        self.runtimeID = runtimeID; self.implemented = implemented; self.supported = supported
        self.enabled = enabled; self.ready = ready; self.agentAllowed = agentAllowed; self.callable = callable
        self.blockedReason = blockedReason; self.legacyReason = legacyReason
        self.constraints = constraints; self.operations = operations
    }
}
