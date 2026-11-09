import Foundation

/// The bundled sample library is Android's (site-content/content/samples.json), where an
/// interaction_id is a number. `GET /v1/samples` on an Apple host must describe this host's own
/// contract instead (A0249): interaction_id is a lowercase UUID string, cards need
/// `no_takeover: true`, and only kinds and fields this host accepts are offered. Each listed
/// request has passed this host's own interaction validation, so a caller can send it as is.
public enum HostSamples {
    public static let interactionIDFormat = "uuid"
    static let createConvention = "POST /v1/interactions with the sample's request as the JSON body. Reply 201: {\"interaction_id\": \"<UUID string>\", \"status\": \"pending\", \"expires_at\": \"<ISO-8601>\"}. On this Apple host interaction_id is always a lowercase UUID string; send it back exactly as returned."
    static let hostConvention = "This Apple host accepts kind notice, question and html with no_takeover: true (normal priority, inbox delivery, no speech). Samples it cannot deliver are left out; the full Android catalog is https://bridgit.cc/samples/samples.json."

    /// Rewrites the bundled catalog for this host. `id` selects one sample, as the route does.
    public static func appleHost(_ root: [String: Any], id: String? = nil, now: Date = Date()) throws -> [String: Any] {
        let accepted = (root["samples"] as? [[String: Any]] ?? []).compactMap { adapt($0, now: now) }
        if let id {
            guard let row = accepted.first(where: { $0["id"] as? String == id }) else {
                throw DeviceAPIError(404, "sample_not_found", "This sample is unavailable on this host.")
            }
            return row
        }
        var conventions = root["conventions"] as? [String: Any] ?? [:]
        conventions["create"] = createConvention
        conventions["apple_host"] = hostConvention
        var value = root
        value["conventions"] = conventions
        value["api_revision"] = HostAPIChanges.current
        value["interaction_id_format"] = interactionIDFormat
        value["samples"] = accepted
        return value
    }

    static func adapt(_ sample: [String: Any], now: Date) -> [String: Any]? {
        guard sample["calls"] == nil, var request = sample["request"] as? [String: Any] else { return nil }
        request["no_takeover"] = true
        guard (try? InteractionValidation.create(request, now: now)) != nil else { return nil }
        var row = sample
        row["request"] = request
        return row
    }
}
