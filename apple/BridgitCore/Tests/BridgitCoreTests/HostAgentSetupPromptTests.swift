import XCTest
@testable import BridgitCore

/// A0239: the Apple agent handover is the guided setup prompt, as on Android, not a bare JSON record.
final class HostAgentSetupPromptTests: XCTestCase {
    private func record(_ fields: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: fields, options: [.prettyPrinted, .sortedKeys]), as: UTF8.self)
    }

    func testRelayConnectorGetsGuidedPromptWithRecordAtEnd() throws {
        let json = try record(["agent_id": "a1", "agent_name": "Almusca", "generation": "g1", "mode": "connector",
                               "token": "brg_fixture", "operations": ["files"], "applets": [], "phone_name": "Studio Mac",
                               "base": "https://bridgit-relay.bridgit-app.workers.dev/d/dev1", "device_id": "dev1",
                               "device_hpke_public": "AAAA"])
        let prompt = HostAgentSetupPrompt.build(setupJSON: json, agentName: "Almusca")
        XCTAssertFalse(prompt.hasPrefix("{"))
        XCTAssertTrue(prompt.hasPrefix("You can use my Apple device (Studio Mac) through Bridgit's relay"))
        for section in ["IDENTITY", "CONNECTION", "RULES", "FIRST CALLS, IN ORDER", "AFTER YOU'RE CONNECTED", "POINTERS", "SETUP DETAILS"] {
            XCTAssertTrue(prompt.contains("\n" + section + "\n"), section)
        }
        XCTAssertTrue(prompt.contains("POST https://bridgit-relay.bridgit-app.workers.dev/d/dev1/jobs?wait=30"))
        XCTAssertTrue(prompt.contains("- Agent name: Almusca"))
        XCTAssertTrue(prompt.contains("~/.config/bridgit/agents/Almusca/header"))
        XCTAssertTrue(prompt.contains("GET /v1/health"))
        XCTAssertTrue(prompt.hasSuffix(json), "the machine-readable record still ends the prompt")
        XCTAssertTrue(prompt.contains("- Your key: brg_fixture"))
    }

    func testOwnerDirectNamesBaseURLAndConnectedTest() throws {
        let json = try record(["agent_id": "a2", "mode": "owner_direct", "authorization": "Bearer", "token": "brg_d",
                               "base": "http://100.80.1.4:8765/v1", "mcp": "http://100.80.1.4:8765/v1/mcp", "phone_name": "Air"])
        let prompt = HostAgentSetupPrompt.build(setupJSON: json, agentName: "Codex")
        XCTAssertTrue(prompt.contains("over my Tailscale network"))
        XCTAssertTrue(prompt.contains("- Base URL: http://100.80.1.4:8765/v1"))
        XCTAssertTrue(prompt.contains("curl -sS -m 60 -H @\"$HOME/.config/bridgit/agents/Codex/header\" http://100.80.1.4:8765/v1/health"))
        XCTAssertFalse(prompt.contains("/jobs?wait="))
    }

    func testOwnerDirectWithoutListenerSaysSoAndCallbackSecretIsIncluded() throws {
        let json = try record(["agent_id": "a3", "mode": "owner_direct", "authorization": "Bearer", "token": "brg_d",
                               "callback_secret": "cbs_fixture"])
        let prompt = HostAgentSetupPrompt.build(setupJSON: json, agentName: nil)
        XCTAssertTrue(prompt.contains("Base URL: not available yet"))
        XCTAssertTrue(prompt.contains("\nCALLBACK SECRET\n- cbs_fixture"))
        XCTAssertFalse(prompt.contains("curl"))
    }

    func testSealedAndUnreadableRecordsAreHandedOverUnchanged() throws {
        let sealed = try record(["agent_id": "a4", "mode": "sealed", "token": "brg_s", "base": "https://r/d/x"])
        XCTAssertEqual(HostAgentSetupPrompt.build(setupJSON: sealed, agentName: "Any"), sealed)
        XCTAssertEqual(HostAgentSetupPrompt.build(setupJSON: "not json", agentName: "Any"), "not json")
    }

    /// A0240: the Apple prompt as whole texts (contracts/client-core/setup-prompt/apple-host-*.txt). Its sentences come from
    /// the prompt database (PGE01); after an intended text change, take the snapshot and refresh these files in the same PR:
    /// BRIDGIT_SETUP_PROMPT_REFRESH=<checkout>/contracts/client-core/setup-prompt swift test --filter HostAgentSetupPromptTests,
    /// then node tools/client-core/gen.mjs.
    func testPromptsMatchTheContractTexts() throws {
        let refresh = ProcessInfo.processInfo.environment["BRIDGIT_SETUP_PROMPT_REFRESH"]
        for (name, fields, agent) in HostSetupPromptCases.all {
            let json = try record(fields)
            if let refresh {
                try (HostAgentSetupPrompt.build(setupJSON: json, agentName: agent) + "\n").write(toFile: refresh + "/" + name, atomically: true, encoding: .utf8)
                continue
            }
            let url = try XCTUnwrap(Bundle.module.url(forResource: (name as NSString).deletingPathExtension, withExtension: "txt",
                                                      subdirectory: "Fixtures/client-core/setup-prompt"), name)
            XCTAssertEqual(HostAgentSetupPrompt.build(setupJSON: json, agentName: agent) + "\n", try String(contentsOf: url, encoding: .utf8), name)
        }
        XCTAssertEqual(SetupPromptText.promptKey, "bridgit.agent_key_setup")
    }
}
