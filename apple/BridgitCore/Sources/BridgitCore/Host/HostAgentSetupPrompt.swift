import Foundation

/// A0239: the text the owner hands a new control agent. Android gives a guided, plain-text setup prompt
/// (what the device is, how to call it, where to keep the key, the first calls in order, pointers) with the
/// machine-readable record at the end; Apple handed over only that bare JSON record. This renders the same
/// shape from the Apple setup record, using only paths the Apple host serves. Every sentence comes from the
/// prompt database (PGE01, A0240) through `SetupPromptText`; this code picks the blocks and fills in the values.
public enum HostAgentSetupPrompt {
    public static let agentGuideURL = "https://bridgit.cc/agentguide/"
    public static let startURL = "https://bridgit.cc/learn/start.md"
    public static let connectionURL = "https://bridgit.cc/learn/connection.md"
    public static let bugsURL = "https://bridgit.cc/learn/bugs.md"
    public static let catalogURL = "https://bridgit.cc/learn/catalog-index.json"

    /// [setupJSON] is the one-time setup record (agent_id, token, mode, base, ...). A sealed relay agent
    /// needs its own encryption client and reads that record directly, as on Android, so it gets the record only.
    public static func build(setupJSON: String, agentName: String?) -> String {
        guard let setup = try? JSONSerialization.jsonObject(with: Data(setupJSON.utf8)) as? [String: Any],
              let token = setup["token"] as? String else { return setupJSON }
        let mode = setup["mode"] as? String ?? ""
        if mode == "sealed" { return setupJSON }
        let direct = mode == "owner_direct" || setup["authorization"] as? String == "Bearer"
        let name = agentName?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "agent"
        let device = (setup["phone_name"] as? String)?.nonEmpty ?? "this device"
        let base = (setup["base"] as? String)?.nonEmpty
        let dir = "~/.config/bridgit/agents/" + name
        let home = "$HOME/.config/bridgit/agents/" + name
        let t = SetupPromptText.render
        var out: [String] = []

        out.append(t(direct ? "apple.opening.direct" : "apple.opening.relay", ["device": device]))
        out.append("")
        out.append(t("apple.identity", ["device": device, "agent_name": name]))
        if let id = setup["device_id"] as? String { out.append(t("apple.identity.device_id", ["device_id": id])) }
        out.append("")
        out.append(t("apple.intro", [:]))
        out.append("")
        out.append(t("apple.connection", [:]))
        let key = t("apple.key", ["key": token])
        let store = t("apple.store", ["agent_name": name])
        if direct {
            if let base {
                out.append(t("apple.direct.base", ["base_url": base, "connection_url": connectionURL]))
            } else {
                out.append(t("apple.direct.no_base", [:]))
            }
            out.append(key)
            out.append(store)
            if let base { out.append(t("apple.direct.test", ["agent_name": name, "base_url": base])) }
            if let base { out.append(t("apple.mcp", ["base_url": base])) }
        } else if let base {
            out.append(t("apple.relay.base", ["base_url": base]))
            out.append(key)
            out.append(t("apple.relay.jobs", ["base_url": base]))
            out.append(store)
            out.append(t("apple.mcp", ["base_url": base]))
            out.append(t("apple.relay.unreachable", [:]))
        } else {
            out.append(key)
        }
        out.append(t("apple.key_rules", ["agent_name": name]))
        out.append("")
        out.append(t("apple.rules", [:]))
        out.append("")
        out.append(t("apple.first_calls", ["start_url": startURL]))
        if let secret = setup["callback_secret"] as? String {
            out.append("")
            out.append(t("apple.callback", ["callback_secret": secret]))
        }
        out.append("")
        out.append(t("apple.after", ["bugs_url": bugsURL]))
        out.append("")
        out.append(t("apple.pointers", ["agent_guide_url": agentGuideURL, "catalog_url": catalogURL]))
        out.append("")
        out.append(t("apple.details", [:]))
        out.append(setupJSON)
        return out.joined(separator: "\n")
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
