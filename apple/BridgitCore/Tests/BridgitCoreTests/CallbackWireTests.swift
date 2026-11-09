import Foundation
import XCTest
@testable import BridgitCore

private actor WireFixtureSecrets: SatelliteSecrets {
    private var values: [String: String] = [:]
    func load(_ id: String) async throws -> String? { values[id] }
    func save(_ value: String, for id: String) async throws { values[id] = value }
    func remove(_ id: String) async throws { values.removeValue(forKey: id) }
}

final class CallbackWireTests: XCTestCase {
    func testPinnedCallbackSendsExactSignedBytesToOwnedLocalReceiver() async throws {
        guard let address = TailnetAddress.localCandidates().first(where: { !$0.isIPv6 }) else {
            throw XCTSkip("This test needs an assigned local IPv4 Tailscale address.")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let portFile = directory.appendingPathComponent("port"), receipt = directory.appendingPathComponent("wire.json")
        let server = #"""
import base64, hashlib, hmac, json, socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(15)
s.bind((sys.argv[1], 0)); s.listen(1)
with open(sys.argv[2], 'w') as f: f.write(str(s.getsockname()[1]))
c, peer = s.accept(); c.settimeout(10)
buffer = b''
while b'\r\n\r\n' not in buffer:
    part = c.recv(65536)
    if not part: raise RuntimeError('missing headers')
    buffer += part
head, body = buffer.split(b'\r\n\r\n', 1)
lines = head.decode('iso-8859-1').split('\r\n')
headers = dict(line.split(':', 1) for line in lines[1:])
headers = {k.lower(): v.strip() for k, v in headers.items()}
size = int(headers['content-length'])
while len(body) < size:
    part = c.recv(65536)
    if not part: raise RuntimeError('short body')
    body += part
key = b'cbs_' + b'a' * 43
signed = headers['x-bridgit-timestamp'].encode('ascii') + b'.' + body
expected = 'sha256=' + hmac.new(key, signed, hashlib.sha256).hexdigest()
valid = hmac.compare_digest(headers.get('x-bridgit-signature', ''), expected) and hmac.compare_digest(headers.get('x-bridget-signature', ''), expected)
with open(sys.argv[3], 'w') as f:
    json.dump({'request': lines[0], 'headers': headers, 'body': base64.b64encode(body).decode(), 'independent_hmac_valid': valid}, f)
c.sendall(b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
c.close(); s.close()
"""#
        let receiver = Process()
        receiver.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        receiver.arguments = ["-c", server, address.value, portFile.path, receipt.path]
        receiver.standardOutput = Pipe(); receiver.standardError = Pipe()
        try receiver.run()
        defer { if receiver.isRunning { receiver.terminate() }; receiver.waitUntilExit() }
        for _ in 0..<500 {
            if FileManager.default.fileExists(atPath: portFile.path) { break }
            guard receiver.isRunning else { XCTFail("Synthetic local receiver exited before binding"); return }
            try await Task.sleep(for: .milliseconds(10))
        }
        let port = try XCTUnwrap(Int(try String(contentsOf: portFile, encoding: .utf8)))
        XCTAssertTrue(TailnetAddress.localCandidates().contains(address), "Receiver must remain bound to this host")
        let store = try AppletStore(directory: directory), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Wire fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: false, awake: true, foreground: true); await authority.setCallbackExecutionEnabled(true)
        let config = IndexedCallbackConfiguration(store: store, secrets: WireFixtureSecrets())
        let policy = CallbackNetworkPolicy(tailnetDomain: nil, allowInternet: false)
        let secret = "cbs_" + String(repeating: "a", count: 43)
        let url = "http://" + address.value + ":" + String(port) + "/hook?fixture=1"
        _ = try await config.set(agentID: agent.id, url: url, secret: secret, caller: nil, policy: { policy }, authorize: {})
        let card = try await store.createInteraction(owner: agent.eventOwner, input: ["kind": "notice", "title": "Wire fixture", "no_takeover": true])
        try await store.markInteractionSeen(id: try XCTUnwrap(card["interaction_id"] as? String))
        let page = try await store.interactionEvents(owner: agent.eventOwner)
        let event = try XCTUnwrap((page["events"] as? [[String: Any]])?.last)
        let eventID = try XCTUnwrap(Int64(try XCTUnwrap(event["id"] as? String)))
        let delivery = CallbackDelivery(authority: authority, store: store, configuration: config, appVersion: "fixture", policy: { policy })
        let status = try await delivery.attempt(agentID: agent.id, grantRevision: 0, configEpoch: 0, eventOwner: agent.eventOwner, eventID: eventID, now: Date(timeIntervalSince1970: 123))
        XCTAssertEqual(status, 204)
        receiver.waitUntilExit(); XCTAssertEqual(receiver.terminationStatus, 0)
        let wire = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: receipt)) as? [String: Any])
        XCTAssertEqual(wire["request"] as? String, "POST /hook?fixture=1 HTTP/1.1")
        XCTAssertEqual(wire["independent_hmac_valid"] as? Bool, true)
        let headers = try XCTUnwrap(wire["headers"] as? [String: String])
        let body = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(wire["body"] as? String)))
        XCTAssertEqual(headers["x-bridgit-timestamp"], "123"); XCTAssertEqual(headers["x-bridgit-event-id"], String(eventID))
        XCTAssertEqual(headers["x-bridgit-signature"], CallbackRules.signature(secret: secret, timestamp: 123, body: body))
        XCTAssertEqual(headers["x-bridget-signature"], headers["x-bridgit-signature"])
        let payload = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(payload["agent"] as? String, "Wire fixture")
        XCTAssertEqual((payload["event"] as? [String: Any])?["id"] as? String, String(eventID))
        XCTAssertEqual((payload["event"] as? [String: Any])?["type"] as? String, "interaction.seen")
    }

    func testPinnedSenderRejectsDisallowedDestinationBeforeConnecting() async throws {
        let own = TailnetAddress.localCandidates().first(where: { !$0.isIPv6 })?.value ?? "127.0.0.1"
        for address in ["http://127.0.0.1:1/hook", "http://" + own + ":1/hook"] {
            let request = ConnectionHTTPRequest(callbackAddress: address, destination: .internet, headers: [:], body: Data())
            do { _ = try await PinnedConnectionHTTP.send(request, authorize: {}); XCTFail("Disallowed URL must fail before connecting") }
            catch let error as DeviceAPIError { XCTAssertTrue(["invalid_connection", "invalid_parameter"].contains(error.code)) }
        }
    }
}
