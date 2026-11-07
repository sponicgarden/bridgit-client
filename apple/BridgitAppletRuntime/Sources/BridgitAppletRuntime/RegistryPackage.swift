import Foundation

public struct CatalogEntry: Decodable, Hashable, Sendable {
    public let key: String
    public let id: String
    public let revision: Int
    public let name: String
    public let summary: String

    /// Only choose a prerequisite within the originally selected update range.
    public func nextRevision(after installed: Int) throws -> Self {
        guard installed >= 1, installed < revision, revision <= Int(Int32.max) else {
            throw AppletRuntimeError.invalid("No later revision in this edition")
        }
        return Self(key:key,id:id,revision:installed+1,name:name,summary:summary)
    }

    /// The destination resolves the pin using its own registry credentials.
    public var installReviewURL: URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "bridgit.cc"
        components.path = "/install/"
        components.queryItems = [URLQueryItem(name: "template", value: key),
                                 URLQueryItem(name: "revision", value: String(revision))]
        return components.url
    }
}

public struct CatalogPage: Decodable, Sendable {
    public let scope: String?
    public let applets: [CatalogEntry]
    public let next: String?
    private enum CodingKeys: String, CodingKey { case format, applets, next, scope }

    public init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        guard try fields.decode(String.self, forKey: .format) == "bridgit-applet-catalog/1" else {
            throw AppletRuntimeError.invalid("Unsupported catalog format")
        }
        scope = try fields.decodeIfPresent(String.self, forKey: .scope)
        applets = try fields.decode([CatalogEntry].self, forKey: .applets)
        next = try fields.decodeIfPresent(String.self, forKey: .next)
        let keys = applets.map(\.key)
        guard keys.count <= 100, keys == keys.sorted(), Set(keys).count == keys.count,
              applets.allSatisfy({ entry in
                  entry.key.range(of: #"\A[a-zA-Z0-9_-]{1,100}\z"#, options: .regularExpression) != nil && (1...Int(Int32.max)).contains(entry.revision)
              }), next == nil || next == keys.last else { throw AppletRuntimeError.invalid("Invalid catalog page") }
    }
}

/// An exact release selected from a catalog page. Only the verified definition goes into an export.
public struct RegistryPackage: Sendable {
    public let selection: CatalogEntry
    public let package: AppletPackage
    public let data: Data

    public init(response: Data, selection: CatalogEntry) throws {
        let pin = try CatalogRelease(response: response, selection: selection)
        guard case .local(let local) = pin else {
            throw AppletRuntimeError.unavailable("This edition requires an Android monitoring device.")
        }
        self = local
    }

    fileprivate init(selection: CatalogEntry, package: AppletPackage, data: Data) {
        self.selection = selection
        self.package = package
        self.data = data
    }
}

/// A monitoring edition can be inspected or exported on Apple. Only local editions can be installed in its runtime.
public enum CatalogRelease: Sendable {
    case local(RegistryPackage)
    case androidMonitor(package: AppletPackage, data: Data)

    public init(response: Data, selection: CatalogEntry) throws {
        struct Pin: Decodable { let key: String; let revision: Int; let state: String }
        guard response.count <= 262144 else { throw AppletRuntimeError.invalid("Registry response exceeds 256 KiB") }
        let pin = try JSONDecoder().decode(Pin.self, from: response)
        guard pin.key == selection.key, pin.revision == selection.revision,
              ["published", "deprecated"].contains(pin.state),
              let envelope = try JSONSerialization.jsonObject(with: response) as? [String: Any],
              let definition = envelope["definition"] as? [String: Any] else {
            throw AppletRuntimeError.invalid("The registry returned a different release")
        }
        let data = try JSONSerialization.data(withJSONObject: definition, options: [.sortedKeys, .withoutEscapingSlashes])
        let package = try AppletPackage(data: data, reviewingAndroidMonitor: true)
        guard package.id == selection.id else { throw AppletRuntimeError.invalid("The package identity differs from the catalog entry") }
        if package.definition["runtime"]?["adapter"] == .string("android.dog.v1") {
            self = .androidMonitor(package: package, data: data)
        } else {
            self = .local(RegistryPackage(selection: selection, package: package, data: data))
        }
    }
}
