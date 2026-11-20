import Foundation

/// The Apple host setup prompt's fixture cases (contracts/client-core/setup-prompt/apple-host-*.txt): invented keys and addresses.
enum HostSetupPromptCases {
    static let all: [(String, [String: Any], String?)] = [
        ("apple-host-relay.txt", ["agent_id": "a1", "agent_name": "Almusca", "generation": "g1", "mode": "connector",
            "token": "brg_fixture", "operations": ["files"], "applets": [String](), "phone_name": "Studio Mac",
            "base": "https://relay.example.invalid/d/dev1", "device_id": "dev1", "device_hpke_public": "AAAA",
            "callback_secret": "cbs_fixture"], "Almusca"),
        ("apple-host-direct.txt", ["agent_id": "a2", "mode": "owner_direct", "authorization": "Bearer", "token": "brg_d",
            "base": "http://100.80.1.4:8765/v1", "mcp": "http://100.80.1.4:8765/v1/mcp", "phone_name": "Air"], "Codex"),
        ("apple-host-direct-no-listener.txt", ["agent_id": "a3", "mode": "owner_direct", "authorization": "Bearer", "token": "brg_d",
            "callback_secret": "cbs_fixture"], nil),
        ("apple-host-no-base.txt", ["agent_id": "a5", "mode": "connector", "token": "brg_n"], "Grok"),
    ]
}
