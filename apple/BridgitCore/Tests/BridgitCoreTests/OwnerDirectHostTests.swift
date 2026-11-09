import Foundation
import Network
import XCTest
@testable import BridgitCore

final class OwnerDirectHostTests: XCTestCase {
    private actor SecondCheckGate {
        var count = 0
        private var released = false
        private var waiting: CheckedContinuation<Void, Never>?
        func suspendAfterBind() async {
            count += 1
            if count == 2, !released { await withCheckedContinuation { waiting = $0 } }
        }
        func release() {
            released = true
            waiting?.resume()
            waiting = nil
        }
    }

    private let source = "100.80.1.2"

    private func setup() async throws -> (AppletStore, HostAuthority, OwnerDirectHostService) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        return (store, authority, service)
    }

    private func wire(key: String, target: String = "/v1/log") -> TailnetHTTPRequest {
        .init(method: "GET", target: target, headers: ["authorization": "Bearer " + key], body: Data())
    }

    private func admitted(_ agent: HostAgent, key: String, authority: HostAuthority, request: HostRequest) async throws -> HostAgent {
        _ = try await authority.authenticateOwnerDirect(key: key, source: source)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try await authority.admit(.init(id: UUID().uuidString, sequence: 0, agentID: agent.id,
            generation: agent.generation, deviceID: "fixture", mode: OwnerDirectHostService.mode,
            deadline: now + 30_000, createdAt: now, request: request, tailnetSource: source))
    }

    private func rawExport(port: UInt16, key: String) async throws -> Data {
        let request = Data(("GET /v1/log/export?kind=note HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + key + "\r\nConnection: close\r\n\r\n").utf8)
        return try await rawHTTP(port: port, request: request)
    }

    private func rawHTTP(port: UInt16, request: Data) async throws -> Data {
        let connection = NWConnection(host: "127.0.0.1", port: try XCTUnwrap(.init(rawValue: port)), using: .tcp)
        connection.start(queue: DispatchQueue(label: "cc.bridgit.direct-fixture"))
        let deadline = Task { try? await Task.sleep(for: .seconds(10)); if !Task.isCancelled { connection.cancel() } }
        defer { deadline.cancel(); connection.cancel() }
        try await withCheckedThrowingContinuation { (sent: CheckedContinuation<Void, Error>) in
            connection.send(content: request, completion: .contentProcessed {
                if let error = $0 { sent.resume(throwing: error) } else { sent.resume() }
            })
        }
        var received = Data(), ended = false
        while !ended {
            let (chunk, complete): (Data, Bool) = try await withCheckedThrowingContinuation { read in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, error in
                    if let error { read.resume(throwing: error) } else { read.resume(returning: (data ?? Data(), complete)) }
                }
            }
            received.append(chunk); ended = complete
        }
        return received
    }

    private func chunks(_ response: Data) throws -> (Data, Bool, Int) {
        let headerEnd = try XCTUnwrap(response.range(of: Data("\r\n\r\n".utf8)))
        let header = String(decoding: response[..<headerEnd.lowerBound], as: UTF8.self)
        XCTAssertTrue(header.hasPrefix("HTTP/1.1 200"))
        XCTAssertTrue(header.contains("Transfer-Encoding: chunked"))
        XCTAssertFalse(header.contains("Content-Length"))
        var start = headerEnd.upperBound
        var decoded = Data(), count = 0
        while start < response.endIndex {
            let sizeEnd = try XCTUnwrap(response[start...].range(of: Data("\r\n".utf8)))
            let size = try XCTUnwrap(Int(String(decoding: response[start..<sizeEnd.lowerBound], as: UTF8.self), radix: 16))
            let bodyEnd = sizeEnd.upperBound + size
            guard bodyEnd + 2 <= response.endIndex else { throw URLError(.cannotDecodeRawData) }
            XCTAssertEqual(response[bodyEnd..<(bodyEnd + 2)], Data("\r\n".utf8))
            start = bodyEnd + 2
            if size == 0 {
                XCTAssertEqual(start, response.endIndex)
                return (decoded, true, count)
            }
            decoded.append(response[sizeEnd.upperBound..<bodyEnd]); count += 1
        }
        return (decoded, false, count)
    }

    private func waitForRequest(in store: AppletStore) async throws -> HostActivityEntry {
        for _ in 0..<100 {
            if let latest = try await store.hostActivity(kinds: [.request]).first { return latest }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The fixture did not receive a completed request audit within one second.")
        throw URLError(.timedOut)
    }

    func testOwnerDirectFirstUseLocksAndStaysSeparateFromRelayAndSatellite() async throws {
        let (store, authority, service) = try await setup()
        let (reader, key) = try await authority.createOwnerDirect(name: "Direct reader", operations: [.log, .deviceRead], applets: [])
        let (relay, relayKey) = try await authority.create(name: "Relay reader", operations: [.log], applets: [])
        let pending = try await authority.agent(reader.id)
        XCTAssertEqual(pending?.ownerDirectPeers, [])
        let invalidRelay = await service.handle(wire(key: relayKey), source: source)
        XCTAssertEqual(invalidRelay.status, 401)
        let invalidSource = await service.handle(wire(key: key), source: "192.168.1.2")
        XCTAssertEqual(invalidSource.status, 403)
        let first = await service.handle(wire(key: key), source: source)
        XCTAssertEqual(first.status, 200)
        let wrong = await service.handle(wire(key: key), source: "100.80.1.3")
        let wrongBody = try XCTUnwrap(JSONSerialization.jsonObject(with: wrong.data) as? [String: Any])
        XCTAssertEqual(wrong.status, 403)
        XCTAssertEqual(wrongBody["error"] as? String, "wrong_device")
        let saved = try await store.transferAgents()
        XCTAssertEqual(saved.first { $0.id == reader.id }?.ownerDirectPeers, [TailnetAddress(source)!])
        let published = try await authority.relayPublication()
        XCTAssertEqual(published.agents.compactMap { $0["id"] as? String }, [relay.id])
        do { _ = try await authority.authenticateDirect(key: key, source: source); XCTFail("Satellite accepted an owner credential") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 409) }
        await authority.setDirectEnabled(true)
        do { _ = try await authority.authenticateDirect(key: key, source: source); XCTFail("Satellite accepted an owner credential") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 401) }
        try await authority.pause(reader.id, paused: true)
        let paused = await service.handle(wire(key: key), source: source)
        XCTAssertEqual(paused.status, 403)
    }

    func testOwnerDirectChangesAcknowledgedOnLoopbackDelivery() async throws {
        let (store, authority, service) = try await setup()
        let (agent, key) = try await authority.createOwnerDirect(name: "Changes wire", operations: [.deviceRead], applets: [])
        let peer = source
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, _ in await service.handle(request, source: peer) },
                                           decorateFailure: { $0.withHostAPIRevision() })
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let session = URLSession(configuration: .ephemeral); defer { session.invalidateAndCancel() }
        func get(_ path: String) async throws -> HTTPURLResponse {
            var request = URLRequest(url: try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)" + path)), timeoutInterval: 10)
            request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
            let (_, response) = try await session.data(for: request)
            return try XCTUnwrap(response as? HTTPURLResponse)
        }
        let first = try await get("/v1/health")
        XCTAssertEqual(first.value(forHTTPHeaderField: "X-Bridgit-Whats-New"), "/v1/changes?since=0")
        let changes = try await get("/v1/changes?since=0")
        XCTAssertEqual(changes.statusCode, 200)
        for _ in 0..<100 {
            if try await store.state(HostAPIChanges.key(agent)) == String(HostAPIChanges.current) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let known = try await store.state(HostAPIChanges.key(agent)); XCTAssertEqual(known, String(HostAPIChanges.current))
        let next = try await get("/v1/health")
        XCTAssertNil(next.value(forHTTPHeaderField: "X-Bridgit-Whats-New"))
    }

    func testOwnerDirectChangesAcknowledgementAfterDelivery() async throws {
        let (store, authority, service) = try await setup()
        let (agent, key) = try await authority.createOwnerDirect(name: "Changes", operations: [.deviceRead], applets: [])
        let first = await service.handle(wire(key: key, target: "/v1/health"), source: source)
        XCTAssertEqual(first.headers["X-Bridgit-Whats-New"], "/v1/changes?since=0")
        let reply = await service.handle(wire(key: key, target: "/v1/changes?since=0"), source: source)
        XCTAssertEqual(reply.status, 200)
        let before = try await store.state(HostAPIChanges.key(agent)); XCTAssertNil(before)
        await reply.afterOriginalDelivery?()
        let after = try await store.state(HostAPIChanges.key(agent)); XCTAssertEqual(after, String(HostAPIChanges.current))
        let health = await service.handle(wire(key: key, target: "/v1/health"), source: source)
        XCTAssertNil(health.headers["X-Bridgit-Whats-New"])
    }

    func testOwnerDirectDiscoveryReportsActualTransportAndTrustedBase() async throws {
        let (store, authority, service) = try await setup()
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct discovery", operations: [.deviceRead, .log], applets: [])
        var untrusted = wire(key: key, target: "/v1/health")
        untrusted.headers["host"] = "outside.invalid"
        untrusted.headers["x-forwarded-host"] = "outside.invalid"
        let health = await service.handle(untrusted, source: source)
        let healthBody = try XCTUnwrap(JSONSerialization.jsonObject(with: health.data) as? [String: Any])
        XCTAssertEqual(healthBody["transport"] as? String, "tailscale")
        XCTAssertNil(healthBody["base_url"])
        let schema = await service.handle(wire(key: key, target: "/v1/openapi.json"), source: source)
        let contract = try XCTUnwrap(JSONSerialization.jsonObject(with: schema.data) as? [String: Any])
        let schemes = try XCTUnwrap((contract["components"] as? [String: Any])?["securitySchemes"] as? [String: Any])
        XCTAssertEqual(Set(schemes.keys), ["directAgent"])
        func exportOperation(_ schema: [String: Any]) throws -> [String: Any] {
            let paths = try XCTUnwrap(schema["paths"] as? [String: [String: Any]])
            return try XCTUnwrap(paths["/v1/log/export"]?["get"] as? [String: Any])
        }
        let directExport = try exportOperation(contract)
        XCTAssertEqual(directExport["summary"] as? String, "Stream all matching Activity as JSONL or CSV.")
        let directResponses = try XCTUnwrap(directExport["responses"] as? [String: Any])
        XCTAssertNotNil(directResponses["200"])
        XCTAssertNil(directResponses["413"])
        let address = try XCTUnwrap(TailnetAddress("fd7a:115c:a1e0::1"))
        let base = try XCTUnwrap(OwnerDirectHostService.baseURL(address: address, port: 8734))
        XCTAssertEqual(base.absoluteString, "http://[fd7a:115c:a1e0::1]:8734/v1")
        var trusted = HostRequest(method: "GET", path: "/v1/health")
        trusted.ownerDirectBaseURL = base
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let response = await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: 0, agentID: agent.id,
            generation: agent.generation, deviceID: "fixture", mode: OwnerDirectHostService.mode,
            deadline: now + 30_000, createdAt: now, request: trusted, tailnetSource: source))
        let actual = try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        XCTAssertEqual(actual["base_url"] as? String, base.absoluteString)
        func callMCP(_ body: [String: Any]) async -> HostResponse {
            var request = HostRequest(method: "POST", path: "/v1/mcp", body: body)
            request.ownerDirectBaseURL = base
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: 0, agentID: agent.id,
                generation: agent.generation, deviceID: "fixture", mode: OwnerDirectHostService.mode,
                deadline: now + 30_000, createdAt: now, request: request, tailnetSource: source))
        }
        let initialize = await callMCP([
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": ["protocolVersion": "2025-11-25", "capabilities": [String: Any](),
                       "clientInfo": ["name": "Fixture", "version": "1"]]
        ])
        let handshake = try XCTUnwrap(JSONSerialization.jsonObject(with: initialize.data) as? [String: Any])
        XCTAssertNotNil(handshake["result"])
        let nested = await callMCP([
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": ["name": "device_health", "arguments": [String: Any]()]
        ])
        let rpc = try XCTUnwrap(JSONSerialization.jsonObject(with: nested.data) as? [String: Any])
        let result = try XCTUnwrap(rpc["result"] as? [String: Any], String(describing: rpc["error"]))
        XCTAssertEqual(result["isError"] as? Bool, false)
        let content = try XCTUnwrap(result["content"] as? [[String: Any]])
        let nestedText = try XCTUnwrap(content.first?["text"] as? String)
        let nestedHealth = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(nestedText.utf8)) as? [String: Any])
        XCTAssertEqual(nestedHealth["transport"] as? String, "tailscale")
        XCTAssertEqual(nestedHealth["base_url"] as? String, base.absoluteString)

        await authority.setState(enabled: true, awake: true, foreground: true)
        let (relay, _) = try await authority.create(name: "Relay discovery", operations: [.deviceRead, .log], applets: [])
        func relayRead(_ path: String, sequence: Int64) async -> HostResponse {
            await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: relay.id,
                generation: relay.generation, deviceID: "fixture", deadline: now + 30_000, createdAt: now,
                request: .init(method: "GET", path: path)))
        }
        let relayHealth = await relayRead("/v1/health", sequence: 1)
        let relayBody = try XCTUnwrap(JSONSerialization.jsonObject(with: relayHealth.data) as? [String: Any])
        XCTAssertEqual(relayBody["transport"] as? String, "relay")
        XCTAssertNil(relayBody["base_url"])
        let relaySchema = await relayRead("/v1/openapi.json", sequence: 2)
        let relayContract = try XCTUnwrap(JSONSerialization.jsonObject(with: relaySchema.data) as? [String: Any])
        let relaySchemes = try XCTUnwrap((relayContract["components"] as? [String: Any])?["securitySchemes"] as? [String: Any])
        XCTAssertEqual(Set(relaySchemes.keys), ["relayAgent"])
        let relayExport = try exportOperation(relayContract)
        XCTAssertTrue((relayExport["summary"] as? String)?.contains("relay result limit") == true)
        let relayResponses = try XCTUnwrap(relayExport["responses"] as? [String: Any])
        XCTAssertNotNil(relayResponses["413"])
    }

    func testOwnerDirectFailedAuthenticationIsPeerScopedAndDoesNotBlockValidKey() async throws {
        let (_, authority, service) = try await setup()
        let (_, key) = try await authority.createOwnerDirect(name: "Direct reader", operations: [.log], applets: [])
        let invalidKey = "brg_" + String(repeating: "A", count: 43)
        for _ in 0..<5 {
            let denied = await service.handle(wire(key: invalidKey), source: source)
            XCTAssertEqual(denied.status, 401)
        }
        let capped = await service.handle(wire(key: invalidKey), source: source)
        let independent = await service.handle(wire(key: invalidKey), source: "100.80.1.3")
        let valid = await service.handle(wire(key: key), source: source)
        XCTAssertEqual(capped.status, 429)
        XCTAssertEqual(independent.status, 401)
        XCTAssertEqual(valid.status, 200)
    }

    func testDeviceLockRecordsDispositionAndOngoingAuthUsesExactPeer() async throws {
        let (store, authority, service) = try await setup()
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct writer", operations: [.log, .configurationWrite], applets: [])
        let peer = try XCTUnwrap(TailnetAddress("100.80.1.3"))
        let other = try XCTUnwrap(TailnetAddress("100.80.1.4"))
        let first = await service.handle(wire(key: key), source: source)
        let refused = await service.handle(wire(key: key), source: peer.value)
        XCTAssertEqual(first.status, 200)
        XCTAssertEqual(refused.status, 403)
        let blocked = try await authority.agent(agent.id)?.ownerDirectPeerAlerts?.first
        XCTAssertEqual(blocked?.address, peer)
        XCTAssertEqual(blocked?.refused, true)
        XCTAssertNotNil(blocked?.at)

        try await store.setOwnerDirectDeviceLock(false)
        let admitted = await service.handle(wire(key: key), source: peer.value)
        XCTAssertEqual(admitted.status, 200)
        try await store.setOwnerDirectDeviceLock(true)
        let afterLock = await service.handle(wire(key: key), source: peer.value)
        XCTAssertEqual(afterLock.status, 200)
        let allowed = try await authority.agent(agent.id)?.ownerDirectPeerAlerts?.first
        XCTAssertEqual(allowed?.address, peer)
        XCTAssertEqual(allowed?.refused, false)
        try await authority.setOwnerDirectPeers(agent.id, dismiss: peer)
        let dismissed = try await authority.agent(agent.id)?.ownerDirectPeerAlerts
        XCTAssertEqual(dismissed, [])

        let wrong = await service.handle(wire(key: key), source: other.value)
        XCTAssertEqual(wrong.status, 403)
        try await authority.setOwnerDirectPeers(agent.id, allow: other)
        let approved = await service.handle(wire(key: key), source: other.value)
        XCTAssertEqual(approved.status, 200)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let job = HostJob(id: UUID().uuidString, sequence: 0, agentID: agent.id, generation: agent.generation,
            deviceID: "fixture", mode: OwnerDirectHostService.mode, deadline: now + 30_000, createdAt: now,
            request: .init(method: "POST", path: "/v1/settings"), tailnetSource: peer.value)
        let requester = try await authority.admit(job)
        try await authority.authorizeOngoing(requester, operation: .configurationWrite)
        try await authority.setOwnerDirectPeers(agent.id, unlock: true)
        let repinned = await service.handle(wire(key: key), source: other.value)
        XCTAssertEqual(repinned.status, 200)
        do { try await authority.authorizeOngoing(requester, operation: .configurationWrite); XCTFail("An unrelated allowed source replaced the request’s peer.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "wrong_device") }
        do { try await store.setOwnerDirectDeviceLock(false, requestedBy: requester); XCTFail("Stale source disabled device lock.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let final = try await authority.agent(agent.id)
        XCTAssertEqual(final?.ownerDirectPeers, [other])
        let stillLocked = try await store.ownerDirectDeviceLock()
        XCTAssertTrue(stillLocked)
    }

    func testDirectRequesterCanRebindAndRevokedStagedBindKeepsCurrentListener() async throws {
        let (_, authority, service) = try await setup()
        addTeardownBlock { await service.stop() }
        let initial = try XCTUnwrap(TailnetAddress("100.81.0.1"))
        let replacement = try XCTUnwrap(TailnetAddress("100.81.0.2"))
        let revokedDestination = try XCTUnwrap(TailnetAddress("100.81.0.3"))
        _ = try await service.bindLoopbackForTesting(address: initial)
        let (writer, key) = try await authority.createOwnerDirect(name: "Direct writer", operations: [.configurationWrite], applets: [])
        _ = try await authority.authenticateOwnerDirect(key: key, source: source)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let job = HostJob(id: UUID().uuidString, sequence: 0, agentID: writer.id, generation: writer.generation,
            deviceID: "fixture", mode: OwnerDirectHostService.mode, deadline: now + 30_000, createdAt: now,
            request: .init(method: "POST", path: "/v1/settings"), tailnetSource: source)
        let requester = try await authority.admit(job)
        let firstRebind = try await service.bindLoopbackForTesting(address: replacement, requester: requester)
        let current = await service.boundEndpoint
        XCTAssertEqual(current?.0, replacement)
        XCTAssertEqual(current?.1, firstRebind)

        let gate = SecondCheckGate()
        let staged = Task {
            try await service.bindLoopbackForTesting(address: revokedDestination, requester: requester) {
                await gate.suspendAfterBind()
            }
        }
        for _ in 0..<100 {
            if await gate.count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let checks = await gate.count
        XCTAssertEqual(checks, 2)
        let during = await service.boundEndpoint
        XCTAssertEqual(during?.0, replacement)
        do { try await authority.pause(writer.id, paused: true) }
        catch { await gate.release(); throw error }
        await gate.release()
        do { _ = try await staged.value; XCTFail("A revoked writer finished activating the staged listener.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let after = await service.boundEndpoint
        XCTAssertEqual(after?.0, replacement)
        XCTAssertEqual(after?.1, firstRebind)
    }

    func testStoppingDuringBindPreventsStagedAndStaleActivation() async throws {
        let (_, authority, service) = try await setup()
        addTeardownBlock { await service.stop() }
        let old = try XCTUnwrap(TailnetAddress("100.81.0.1"))
        let proposed = try XCTUnwrap(TailnetAddress("100.81.0.2"))
        _ = try await service.bindLoopbackForTesting(address: old)
        let gate = SecondCheckGate()
        let staged = Task {
            try await service.bindLoopbackForTesting(address: proposed) { await gate.suspendAfterBind() }
        }
        for _ in 0..<100 {
            if await gate.count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let checks = await gate.count
        XCTAssertEqual(checks, 2)
        await service.stop()
        await gate.release()
        do { _ = try await staged.value; XCTFail("Stopped listener was reactivated by a staged bind.") }
        catch is CancellationError {}
        let stopped = await service.boundEndpoint
        XCTAssertNil(stopped)
        do {
            // Revision 1 activated the initial listener; stop already superseded it.
            try await authority.commitOwnerDirectBinding(revision: 1, enabled: true)
            XCTFail("A stale binding revision re-enabled direct authority.")
        } catch is CancellationError {}
        do {
            _ = try await authority.createOwnerDirect(name: "After stop", operations: [.log], applets: [])
            XCTFail("Direct authority remained enabled after stop.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
    }

    func testDirectSensorsUseCurrentPeerAndAnAuthorizedLongMicrophoneDeadline() async throws {
        actor Capture {
            var cameraStarted = false
            var cameraCancelled = false
            func camera() async throws {
                cameraStarted = true
                do { try await Task.sleep(for: .seconds(10)) }
                catch { cameraCancelled = true; throw error }
            }
        }
        let (store, authority, _) = try await setup()
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct sensor", operations: [.deviceRead, .cameraSnapshot, .microphoneRecord], applets: [])
        let (_, noGrantKey) = try await authority.createOwnerDirect(name: "No sensor grant", operations: [.deviceRead], applets: [])
        let capture = Capture()
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() },
            sensor: { request, caller in
                if request.path == "/v1/mic/record" {
                    try await authority.authorizeOngoing(caller, operation: .microphoneRecord)
                    let options = try MicrophoneClipRequest(request)
                    return try .json(["duration_seconds": options.seconds, "transcribed": options.transcribe, "fixture": true])
                }
                try await authority.authorizeOngoing(caller, operation: .cameraSnapshot)
                try await capture.camera()
                return .init(contentType: "image/jpeg", data: Data([0]))
            })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        func post(_ path: String, key: String, body: [String: Any], action: String) throws -> TailnetHTTPRequest {
            .init(method: "POST", target: path, headers: ["authorization": "Bearer " + key, "idempotency-key": action],
                body: try JSONSerialization.data(withJSONObject: body))
        }
        let options: [String: Any] = ["duration_seconds": 120, "transcribe": true, "return_audio": false]
        let mic = await service.handle(try post("/v1/mic/record", key: key, body: options, action: "direct-mic"), source: source)
        let micResult = try XCTUnwrap(JSONSerialization.jsonObject(with: mic.data) as? [String: Any])
        XCTAssertEqual(mic.status, 200)
        XCTAssertEqual(micResult["duration_seconds"] as? Int, 120)
        let denied = await service.handle(try post("/v1/mic/record", key: noGrantKey, body: options, action: "denied-mic"), source: source)
        XCTAssertEqual(denied.status, 403)
        let camera = try post("/v1/camera/snapshot", key: key, body: [:], action: "direct-camera")
        let source = self.source
        let work = Task { await service.handle(camera, source: source) }
        defer { work.cancel() }
        for _ in 0..<100 {
            if await capture.cameraStarted { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let started = await capture.cameraStarted
        XCTAssertTrue(started)
        try await authority.setOwnerDirectPeers(agent.id, unlock: true)
        let result = await work.value
        let cancelled = await capture.cameraCancelled
        XCTAssertTrue(cancelled)
        XCTAssertEqual(result.status, 409)
        XCTAssertNotEqual(result.contentType, "image/jpeg")
    }

    func testDirectAppletInstallRevalidatesAfterAsyncPrerequisitesAndGuardsRemovalAndMedia() async throws {
        let (store, authority, _) = try await setup()
        let gate = SecondCheckGate()
        let engine = AppletEngine(store: store, checkPrerequisites: { _ in await gate.suspendAfterBind() }) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let applet = "insurance-inventory"
        let packageURL = try XCTUnwrap(Bundle.module.url(forResource: applet, withExtension: "json", subdirectory: "Fixtures"))
        let package = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: packageURL)) as? [String: Any])
        let (manager, key) = try await authority.createOwnerDirect(name: "Direct manager", operations: [.appletsManage], applets: [])
        let source = self.source
        let request = TailnetHTTPRequest(method: "POST", target: "/v1/applets/install",
            headers: ["authorization": "Bearer " + key, "idempotency-key": "install-direct"],
            body: try JSONSerialization.data(withJSONObject: ["package": package]))
        let running = Task { await service.handle(request, source: source) }
        for _ in 0..<100 {
            if await gate.count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let reachedCommitValidation = await gate.count
        XCTAssertEqual(reachedCommitValidation, 2)
        do { try await authority.setOwnerDirectPeers(manager.id, unlock: true) }
        catch { await gate.release(); throw error }
        await gate.release()
        let denied = await running.value
        XCTAssertTrue([403, 409].contains(denied.status))
        let packagesAfterDenial = try await store.packages()
        XCTAssertTrue(packagesAfterDenial.isEmpty)

        let installed = await service.handle(.init(method: request.method, target: request.target,
            headers: ["authorization": "Bearer " + key, "idempotency-key": "authorized-install"], body: request.body), source: source)
        XCTAssertEqual(installed.status, 201)
        let stale = try await admitted(manager, key: key, authority: authority, request: .init(method: "DELETE", path: "/v1/applets/installed/" + applet))
        try await authority.setOwnerDirectPeers(manager.id, unlock: true)
        do { _ = try await engine.remove(applet: applet, requestedBy: stale); XCTFail("A stale peer removed an applet.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        do {
            _ = try await store.addPhotoRow(applet: applet, jpeg: Data([1]), table: "photos", column: "photo", initial: [:], requestedBy: stale)
            XCTFail("A stale peer imported applet media.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let stillInstalled = try await store.package(applet)["id"] as? String
        XCTAssertEqual(stillInstalled, applet)
    }

    func testDirectSharedOperationsUseRealStoresAndCurrentSource() async throws {
        let (store, authority, service) = try await setup()
        let applet = "insurance-inventory"
        let packageURL = try XCTUnwrap(Bundle.module.url(forResource: applet, withExtension: "json", subdirectory: "Fixtures"))
        let package = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: packageURL)) as? [String: Any])
        try await store.install(package)
        for kind in TransferKind.allCases { try await store.setTransferEnabled(kind, enabled: true) }
        let grants: Set<HostOperation> = [.deviceRead, .appletsRead, .appletsWrite, .interactionsRead, .interactionsWrite, .files, .shares, .downloads]
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct operator", operations: grants, applets: [applet])
        let (_, strangerKey) = try await authority.createOwnerDirect(name: "Other operator", operations: grants, applets: [applet])
        let source = self.source
        func call(_ path: String, method: String = "GET", body: [String: Any] = [:], action: String = "", token: String? = nil) async throws -> HostResponse {
            var headers = ["authorization": "Bearer " + (token ?? key)]
            if !action.isEmpty { headers["idempotency-key"] = action }
            return await service.handle(.init(method: method, target: path, headers: headers,
                body: body.isEmpty ? Data() : try JSONSerialization.data(withJSONObject: body)), source: source)
        }
        func object(_ response: HostResponse) throws -> [String: Any] {
            try XCTUnwrap(JSONSerialization.jsonObject(with: response.data) as? [String: Any])
        }
        let registered = HostAPIContract.allowed(agent).map(\.name)
        XCTAssertTrue(Set(registered).isSuperset(of: ["interactions_get", "events", "files_get", "applets_rows_add", "discover"]))
        XCTAssertTrue(registered.contains("satellites_list"))

        let rows = "/v1/applets/installed/" + applet + "/tables/items/rows"
        let rowBody = ["row": ["label": "Direct lamp"]]
        let added = try await call(rows, method: "POST", body: rowBody, action: "direct-add")
        let retry = try await call(rows, method: "POST", body: rowBody, action: "direct-add")
        XCTAssertEqual(added.status, 201)
        XCTAssertEqual(retry.data, added.data)
        let actualRows = try await store.rows(applet: applet, table: "items")
        XCTAssertEqual(actualRows.map { $0["label"] as? String }, ["Direct lamp"])
        let hiddenApplet = try await call("/v1/applets/installed/other-applet")
        XCTAssertEqual(hiddenApplet.status, 403)

        let notice = try await call("/v1/interactions", method: "POST",
            body: ["kind": "notice", "title": "Direct inbox fixture", "no_takeover": true], action: "direct-card")
        XCTAssertEqual(notice.status, 201)
        let cardID = try XCTUnwrap(object(notice)["interaction_id"] as? String)
        let card = try await call("/v1/interactions/" + cardID)
        let hiddenCard = try await call("/v1/interactions/" + cardID, token: strangerKey)
        XCTAssertEqual(card.status, 200)
        XCTAssertEqual(hiddenCard.status, 404)

        let bytes = Data([2, 3, 5])
        let upload = try await call("/v1/files", method: "POST",
            body: ["name": "direct.bin", "data_base64": bytes.base64EncodedString()], action: "direct-file")
        XCTAssertEqual(upload.status, 201)
        let events = try await call("/v1/events?since=0&types=file.received")
        XCTAssertTrue((try object(events)["events"] as? [[String: Any]])?.contains(where: { $0["type"] as? String == "file.received" }) == true)
        let fileID = try XCTUnwrap(object(upload)["id"] as? String)
        let file = try await call("/v1/files/" + fileID)
        let hiddenFile = try await call("/v1/files/" + fileID, token: strangerKey)
        XCTAssertEqual(file.data, bytes)
        XCTAssertEqual(hiddenFile.status, 404)
        for kind in [TransferKind.shares, .downloads] {
            let metadata = try await store.saveTransfer(for: agent, kind: kind, name: "owner.bin", mime: "application/octet-stream", bytes: bytes)
            let id = try XCTUnwrap(metadata["id"] as? String)
            let shared = try await call("/v1/" + kind.rawValue + "/" + id)
            XCTAssertEqual(shared.data, bytes)
        }

        let stale = try await admitted(agent, key: key, authority: authority, request: .init(method: "POST", path: rows))
        try await authority.setOwnerDirectPeers(agent.id, unlock: true)
        let newPeer = try XCTUnwrap(TailnetAddress("100.80.1.3"))
        _ = try await authority.authenticateOwnerDirect(key: key, source: newPeer.value)
        do { try await card.beforeDelivery?(); XCTFail("A removed direct peer received an Inbox result.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "wrong_device") }
        do { try await file.beforeDelivery?(); XCTFail("A removed direct peer received private bytes.") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "wrong_device") }
        do {
            _ = try await store.addRows(applet: applet, table: "items", rows: [["label": "Unauthorized"]], by: "agent", requestedBy: stale)
            XCTFail("A removed direct peer committed an applet row.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        do {
            _ = try await store.cancelInteraction(owner: agent.eventOwner, id: cardID, requestedBy: stale)
            XCTFail("A removed direct peer mutated an Inbox item.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        do {
            try await store.deleteTransfer(id: fileID, for: agent, kind: .files, requestedBy: stale)
            XCTFail("A removed direct peer deleted a file.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }

    func testMaintainedMutationPermitRejectsRestartButIgnoresSameLifecyclePoll() async throws {
        let (_, authority, _) = try await setup()
        let applet = "library-" + String(repeating: "a", count: 56)
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct writer", operations: [.appletsWrite], applets: [applet])
        let request = HostRequest(method: "POST", path: "/v1/applets/installed/" + applet + "/tables/notes/rows")
        let authenticated = try await admitted(agent, key: key, authority: authority, request: request)
        do {
            _ = try await authority.withDirectAppletMutation(authenticated, operation: .appletsWrite, applet: applet) { permit in
                await authority.setOwnerDirectEnabled(false)
                await authority.setOwnerDirectEnabled(true)
                return try permit.commit { 1 }
            }
            XCTFail("A stale permit survived a direct listener restart.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        let value = try await authority.withDirectAppletMutation(authenticated, operation: .appletsWrite, applet: applet) { permit in
            await authority.setState(enabled: false, awake: true, foreground: true)
            return try permit.commit { 2 }
        }
        XCTAssertEqual(value, 2)
    }

    func testMaintainedMutationPermitRejectsRebindClosedAndCancelledEffects() async throws {
        let (_, authority, _) = try await setup()
        let applet = "library-" + String(repeating: "a", count: 56)
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct writer", operations: [.appletsWrite], applets: [applet])
        let request = HostRequest(method: "POST", path: "/v1/applets/installed/" + applet + "/tables/notes/rows")
        let caller = try await admitted(agent, key: key, authority: authority, request: request)
        do {
            _ = try await authority.withDirectAppletMutation(caller, operation: .appletsWrite, applet: applet) { permit in
                try await authority.commitOwnerDirectBinding(revision: 1, enabled: true)
                return try permit.commit { 1 }
            }
            XCTFail("A direct rebind preserved the old permit.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "foreground_required") }
        let expired = try await authority.withDirectAppletMutation(caller, operation: .appletsWrite, applet: applet) { $0 }
        XCTAssertThrowsError(try expired.commit { 1 })
        let gate = SecondCheckGate()
        let work = Task {
            try await authority.withDirectAppletMutation(caller, operation: .appletsWrite, applet: applet) { permit in
                await gate.suspendAfterBind()
                await gate.suspendAfterBind()
                return try permit.commit { 1 }
            }
        }
        for _ in 0..<100 {
            if await gate.count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let reachedCommit = await gate.count
        XCTAssertEqual(reachedCommit, 2)
        work.cancel()
        await gate.release()
        do { _ = try await work.value; XCTFail("A cancelled task committed an effect.") }
        catch is CancellationError {}
    }

    func testOwnerDirectStreamsMoreThanRelayLimitAndAuditsActualDelivery() async throws {
        let (store, authority, service) = try await setup()
        let (reader, key) = try await authority.createOwnerDirect(name: "Export reader", operations: [.log], applets: [])
        let fixtureText = String(repeating: "\\", count: 100)
        let data = Dictionary(uniqueKeysWithValues: (0..<17).map { ("value" + String($0), fixtureText) })
        let note = try HostLogNote(.init(method: "POST", path: "/v1/log", body: ["message": "Large export fixture", "data": data]))
        let sourceWriter = try await admitted(reader, key: key, authority: authority, request: .init(method: "POST", path: "/v1/log"))
        let start = Date().addingTimeInterval(-8_000)
        for index in 0..<2_250 {
            _ = try await store.recordHostNote(note, agent: sourceWriter, at: start.addingTimeInterval(Double(index) * 2))
        }
        let source = self.source
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, socket in
            XCTAssertEqual(socket, "127.0.0.1")
            return await service.handle(request, source: source)
        })
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let raw = try await rawExport(port: port, key: key)
        let (bytes, complete, _) = try chunks(raw)
        XCTAssertTrue(complete)
        XCTAssertGreaterThan(bytes.count, 8 * 1024 * 1024)
        XCTAssertEqual(bytes.filter { $0 == 0x0a }.count, 2_250)
        let last = try await waitForRequest(in: store)
        XCTAssertEqual(last.requestFacts?.bytesIn, 0)
        XCTAssertEqual(last.requestFacts?.bytesOut, bytes.count)
        XCTAssertEqual(last.requestFacts?.sourceIP, source)
        XCTAssertEqual(last.requestFacts?.endpoint, "/v1/log/export")
    }

    func testOwnerDirectVisibilityNarrowingInterruptsChunkedResponse() async throws {
        let (store, authority, service) = try await setup()
        let (reader, key) = try await authority.createOwnerDirect(name: "Export reader", operations: [.log], applets: [])
        let (other, otherKey) = try await authority.createOwnerDirect(name: "Other agent", operations: [.log], applets: [])
        let note = try HostLogNote(.init(method: "POST", path: "/v1/log",
            body: ["message": String(repeating: "private fixture content ", count: 40)]))
        let sourceWriter = try await admitted(other, key: otherKey, authority: authority, request: .init(method: "POST", path: "/v1/log"))
        for _ in 0..<50 { _ = try await store.recordHostNote(note, agent: sourceWriter) }
        let source = self.source
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, _ in
            var response = await service.handle(request, source: source)
            if let original = response.stream {
                response.stream = { write in
                    try await original { chunk in
                        try await write(chunk)
                        try await store.setHostLogVisibility(.own)
                    }
                }
            }
            return response
        })
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let raw = try await rawExport(port: port, key: key)
        let (_, complete, sentChunks) = try chunks(raw)
        XCTAssertFalse(complete)
        XCTAssertEqual(sentChunks, 1)
        let latest = try await waitForRequest(in: store)
        XCTAssertEqual(latest.agentID, reader.id)
        XCTAssertEqual(latest.requestFacts?.result, "log_visibility_changed")
        XCTAssertGreaterThan(latest.requestFacts?.bytesOut ?? 0, 0)
    }
}

extension OwnerDirectHostTests {
    func testOwnerDirectAuthenticationAndUnknownRoutesAuditWithoutUnverifiedIdentity() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "anonymous-audit")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (agent, key) = try await authority.createOwnerDirect(name: "Owner-issued fixture", operations: [.deviceRead], applets: [])
        let (_, reader) = try await authority.createOwnerDirect(name: "Activity reader", operations: [.log, .deviceRead], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                                        profile: { _ in throw CancellationError() })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let unverified = "brg_" + String(repeating: "x", count: 43)
        let privateText = "synthetic credential bait"
        let forgedID = UUID().uuidString
        func call(_ key: String, source: String, target: String = "/v1/health", body: Data = Data()) async -> HostResponse {
            await service.handle(.init(method: "GET", target: target, headers: [
                "authorization": "Bearer " + key, "x-bridgit-agent": forgedID, "x-bridgit-name": privateText
            ], body: body), source: source)
        }
        let wrong = await call(unverified, source: source, target: "/v1/feedback/5?token=" + unverified, body: Data(privateText.utf8))
        XCTAssertEqual(wrong.status, 401)
        let admitted = await call(key, source: source)
        XCTAssertEqual(admitted.status, 200)
        let otherSource = "100.80.1.3"
        let wrongPeer = await call(key, source: otherSource)
        XCTAssertEqual(wrongPeer.status, 403)
        try await authority.pause(agent.id, paused: true)
        let paused = await call(key, source: source)
        XCTAssertEqual(paused.status, 403)
        try await authority.remove(agent.id)
        let revoked = await call(key, source: source)
        XCTAssertEqual(revoked.status, 401)
        let unknownPath = "/v1/" + unverified + "?name=" + forgedID
        let unknownCaller = await call(unverified, source: source, target: unknownPath)
        XCTAssertEqual(unknownCaller.status, 404)
        let unknownEndpoint = await call(reader, source: "100.80.1.5", target: unknownPath)
        XCTAssertEqual(unknownEndpoint.status, 404)
        let badSource = await call(unverified, source: privateText)
        XCTAssertEqual(badSource.status, 403)
        for attempt in 0..<6 {
            let limited = await call(unverified, source: "100.80.1.4")
            XCTAssertEqual(limited.status, attempt < 5 ? 401 : 429)
        }
        let saved = try await store.hostActivity(limit: 100, kinds: [.request])
        let anonymous = saved.filter(\.isAnonymousRequest)
        XCTAssertEqual(anonymous.count, 13)
        XCTAssertEqual(anonymous.filter { $0.status == 429 }.count, 1)
        XCTAssertEqual(anonymous.filter { $0.requestFacts?.endpoint == "(unknown route)" }.count, 2)
        XCTAssertTrue(anonymous.contains { $0.requestFacts?.sourceIP == otherSource && $0.status == 403 })
        XCTAssertTrue(anonymous.contains { $0.requestFacts?.sourceIP == nil && $0.status == 403 })
        let canonical = try XCTUnwrap(anonymous.first { $0.requestFacts?.endpoint == "/v1/feedback/{id}" })
        XCTAssertEqual(canonical.requestFacts?.bytesIn, privateText.utf8.count)
        XCTAssertNil(canonical.requestFacts?.bytesOut)
        for entry in anonymous {
            let fields = HostActivityDetail(entry).fields
            XCTAssertTrue(fields["agent"] is NSNull)
            XCTAssertTrue(fields["agent_id"] is NSNull)
            XCTAssertEqual(entry.displayAgentName, "Unidentified client")
            let serialized = try HostActivityDetail(entry).json()
            for secret in [key, unverified, forgedID, privateText] { XCTAssertFalse(serialized.contains(secret)) }
        }
        let identities = try await store.hostActivityAgentIdentities()
        XCTAssertFalse(identities.contains { $0.name.isEmpty || $0.name == "Unidentified client" })
        let allStats = await call(reader, source: "100.80.1.5", target: "/v1/log/stats")
        let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: allStats.data) as? [String: Any])
        let groups = try XCTUnwrap(stats["by_agent"] as? [[String: Any]])
        XCTAssertEqual(groups.first { $0["key"] as? String == "(no key)" }?["count"] as? Int, 13)
        try await store.setHostLogVisibility(.own)
        let own = await call(reader, source: "100.80.1.5", target: "/v1/log")
        XCTAssertEqual(own.status, 200)
        XCTAssertFalse(String(decoding: own.data, as: UTF8.self).contains("Direct sign-in refused"))
        XCTAssertFalse(String(decoding: own.data, as: UTF8.self).contains("Unknown direct endpoint"))
    }
}

extension OwnerDirectHostTests {
    func testLoopbackDeniedMalformedBodyRecordsOnlyAnonymousWireFacts() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "anonymous-wire")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                                        profile: { _ in throw CancellationError() })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let fixtureSource = source
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, socket in
            XCTAssertEqual(socket, "127.0.0.1")
            return await service.handle(request, source: fixtureSource)
        })
        addTeardownBlock {
            await listener.stop()
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let privateText = "malformed-private-fixture"
        let badKey = "brg_" + String(repeating: "z", count: 43)
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/v1/feedback?credential=" + badKey))
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.httpBody = Data(privateText.utf8)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + badKey, forHTTPHeaderField: "Authorization")
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 401)
        let rows = try await store.hostActivity(kinds: [.request])
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertTrue(row.isAnonymousRequest)
        XCTAssertEqual(row.requestFacts?.method, "POST")
        XCTAssertEqual(row.requestFacts?.endpoint, "/v1/feedback")
        XCTAssertEqual(row.requestFacts?.result, "unauthorized")
        XCTAssertEqual(row.requestFacts?.sourceIP, fixtureSource)
        XCTAssertEqual(row.requestFacts?.bytesIn, privateText.utf8.count)
        let json = try HostActivityDetail(row).json()
        XCTAssertFalse(json.contains(privateText))
        XCTAssertFalse(json.contains(badKey))
        XCTAssertFalse(json.contains("credential="))
    }
}

extension OwnerDirectHostTests {
    /// A0129: the app binds a fixed port (18471 by default). Port 0 hid the
    /// Network.framework EINVAL raised when the port was also passed to `on:`.
    func testListenerBindsAFixedPortOnTheChosenAddress() async throws {
        let probe = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in .failure(DeviceAPIError(404, "not_found", "Missing.")) })
        let free = try await probe.start(address: "127.0.0.1", port: 0)
        await probe.stop()
        try await Task.sleep(for: .milliseconds(100))
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in .failure(DeviceAPIError(404, "not_found", "Missing.")) })
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: free)
        XCTAssertEqual(port, free)
        let raw = try await rawHTTP(port: port, request: Data("GET /v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".utf8))
        XCTAssertTrue(String(decoding: raw, as: UTF8.self).lowercased().hasPrefix("http/1.1 404 "))
    }

    func testSatelliteStyleListenerDoesNotAdvertiseHostRevisionOnParseError() async throws {
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { _, _ in .failure(DeviceAPIError(404, "not_found", "Missing.")) })
        addTeardownBlock { await listener.stop() }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let raw = try await rawHTTP(port: port, request: Data("HEAD /v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".utf8))
        let text = String(decoding: raw, as: UTF8.self).lowercased()
        XCTAssertTrue(text.hasPrefix("http/1.1 400 "))
        XCTAssertFalse(text.contains("x-bridgit-api-revision:"))
    }

    func testSocketAuditsOnlyParsedRejectionsAndVerifiedMalformedJSONIdentity() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        let authority = HostAuthority(store: store, deviceID: "parsed-rejections")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (agent, key) = try await authority.createOwnerDirect(name: "Verified controller", operations: [.deviceRead, .configurationRead], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
                                        profile: { _ in throw CancellationError() })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let source = self.source
        let listener = TailnetHTTPListener(fixtureLoopbackHandler: { request, socket in
            XCTAssertEqual(socket, "127.0.0.1")
            return await service.handle(request, source: source)
        }, rejection: { observation, error, socket, at, started in
            XCTAssertEqual(socket, "127.0.0.1")
            await service.recordParsedRejection(observation, error: error, source: source, at: at, started: started)
        }, decorateFailure: { $0.withHostAPIRevision() })
        addTeardownBlock {
            await listener.stop()
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let port = try await listener.start(address: "127.0.0.1", port: 0)
        let bait = "brg_" + String(repeating: "q", count: 43)
        func expect(_ wire: String, _ status: Int) async throws {
            let response = try await rawHTTP(port: port, request: Data(wire.utf8))
            let text = String(decoding: response, as: UTF8.self)
            XCTAssertTrue(text.hasPrefix("HTTP/1.1 \(status) "))
            XCTAssertTrue(text.lowercased().contains("x-bridgit-api-revision: \(HostAPIChanges.current)"))
            XCTAssertFalse(text.lowercased().contains("x-bridgit-whats-new:"))
        }
        let authenticated = "Authorization: Bearer " + key + "\r\n"
        let start = "POST /v1/feedback?private=" + bait + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        try await expect(start + authenticated + "Content-Length: 1\r\nContent-Length: 2\r\n\r\n", 400)
        try await expect(start + "Content-Length: 1\r\nContent-Type: text/plain\r\n\r\nx", 415)
        try await expect(start + "Content-Length: 9999999\r\nContent-Type: application/json\r\n\r\n", 413)
        try await expect(bait + " /v1/feedback HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", 400)
        try await expect("HEAD /v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", 400)
        try await expect(bait + "\r\n\r\n", 400)
        try await expect(start + authenticated + "Content-Length: \(bait.utf8.count)\r\nContent-Type: application/json\r\n\r\n" + bait, 400)
        try await expect("GET /v1/" + bait + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", 404)
        try await expect("GET /v1/account HTTP/1.1\r\nHost: 127.0.0.1\r\n" + authenticated + "\r\n", 501)

        let rows = try await store.hostActivity(limit: 20, kinds: [.request])
        XCTAssertEqual(rows.count, 8, "Raw input without a valid request line must not invent an audit row.")
        let anonymous = rows.filter(\.isAnonymousRequest)
        XCTAssertEqual(anonymous.count, 6)
        let codec = anonymous.filter { $0.status != 404 }
        XCTAssertEqual(Set(codec.map(\.status)), [400, 413, 415])
        XCTAssertTrue(codec.allSatisfy { $0.requestFacts?.bytesIn == nil })
        XCTAssertTrue(codec.contains { $0.requestFacts?.method == "HEAD" })
        XCTAssertTrue(codec.contains { $0.requestFacts?.method == "(unsupported method)" })
        let invalidJSON = try XCTUnwrap(rows.first { $0.requestFacts?.result == "invalid_json" })
        XCTAssertEqual(invalidJSON.agentID, agent.id)
        XCTAssertEqual(invalidJSON.requestFacts?.bytesIn, bait.utf8.count)
        XCTAssertEqual(invalidJSON.requestFacts?.endpoint, "/v1/feedback")
        let unavailable = try XCTUnwrap(rows.first { $0.status == 501 })
        XCTAssertEqual(unavailable.agentID, agent.id)
        XCTAssertEqual(unavailable.requestFacts?.endpoint, "/v1/account")
        XCTAssertTrue(anonymous.contains { $0.status == 404 && $0.requestFacts?.endpoint == "(unknown route)" })
        for row in rows {
            let value = try HostActivityDetail(row).json()
            XCTAssertFalse(value.contains(bait))
            XCTAssertFalse(value.contains(key))
            XCTAssertFalse(value.contains("private="))
        }
    }
}

private final class RotationConfigurationFixture: HostConfigurationService {
    let authority: HostAuthority
    init(_ authority: HostAuthority) { self.authority = authority }
    func configuration(_ request: HostRequest, agent: HostAgent) async throws -> HostResponse {
        try await authority.administer(request, caller: agent)
    }
}

extension OwnerDirectHostTests {
    func testDirectKeyRotationKeepsIdentityAndRejectsOldGenerationAfterReopen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "rotation")
        await authority.setState(enabled: false, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (original, oldKey) = try await authority.createOwnerDirect(name: "Stable agent",
            operations: [.deviceRead, .cameraSnapshot], applets: ["fixture-applet"])
        let gate = FeedbackWaitGate()
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, sensor: { _, _ in
                await gate.hold()
                return try .json(["private_fixture_result": true])
            })
        let service = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let oldSource = source
        let inFlight = Task {
            await service.handle(.init(method: "POST", target: "/v1/camera/snapshot",
                headers: ["authorization": "Bearer " + oldKey, "idempotency-key": "rotation-camera"], body: Data("{}".utf8)),
                source: oldSource)
        }
        for _ in 0..<100 {
            if await gate.count == 1 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        guard await gate.count == 1 else {
            await gate.release()
            _ = await inFlight.value
            XCTFail("Old generation did not reach the held sensor fixture.")
            return
        }
        let (rotated, newKey) = try await authority.rotateControlKey(original.id)
        await gate.release()
        let oldResult = await inFlight.value
        XCTAssertFalse(String(decoding: oldResult.data, as: UTF8.self).contains("private_fixture_result"))
        XCTAssertGreaterThanOrEqual(oldResult.status, 400)
        XCTAssertEqual(rotated.id, original.id)
        XCTAssertEqual(rotated.operations, original.operations)
        XCTAssertEqual(rotated.applets, original.applets)
        XCTAssertEqual(rotated.ownerDirectPeers, [])
        XCTAssertNotEqual(rotated.generation, original.generation)
        XCTAssertNotEqual(newKey, oldKey)
        let beforeRestart = await service.handle(wire(key: oldKey, target: "/v1/health"), source: oldSource)
        XCTAssertEqual(beforeRestart.status, 401)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: beforeRestart.data) as? [String: String])?["reason"], "key_rotated")

        let reopenedStore = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await reopenedStore.closeTemporaryFixture(at: directory)
            #endif
        }
        let reopened = HostAuthority(store: reopenedStore, deviceID: "rotation")
        await reopened.setState(enabled: false, awake: true, foreground: true)
        await reopened.setOwnerDirectEnabled(true)
        let reopenedDispatcher = HostDispatcher(authority: reopened, store: reopenedStore,
            engine: AppletEngine(store: reopenedStore) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let reopenedService = OwnerDirectHostService(authority: reopened, dispatcher: reopenedDispatcher)
        let stillRetired = await reopenedService.handle(wire(key: oldKey, target: "/v1/health"), source: oldSource)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: stillRetired.data) as? [String: String])?["reason"], "key_rotated")
        do {
            _ = try await reopened.authenticateOwnerDirect(key: oldKey, source: oldSource,
                                                           now: Date().addingTimeInterval(8 * 24 * 60 * 60))
            XCTFail("An expired retirement must not restore the old credential.")
        } catch let error as DeviceAPIError {
            XCTAssertEqual(error.status, 401)
            XCTAssertNil(error.reason)
        }
        let newSource = "100.80.1.5"
        let current = await reopenedService.handle(wire(key: newKey, target: "/v1/health"), source: newSource)
        XCTAssertEqual(current.status, 200)
        try await reopened.remove(rotated.id)
        let removed = await reopenedService.handle(wire(key: newKey, target: "/v1/health"), source: newSource)
        XCTAssertEqual(removed.status, 401)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: removed.data) as? [String: String])?["reason"], "agent_removed")
        let activities = try await reopenedStore.hostActivity()
        XCTAssertTrue(activities.contains { $0.agentID == original.id && $0.operation == "Rotated the key for Stable agent" })
        for entry in activities {
            let json = try HostActivityDetail(entry).json()
            for secret in [oldKey, newKey, PairingClaim.tokenHash(oldKey), PairingClaim.tokenHash(newKey)] {
                XCTAssertFalse(json.contains(secret))
            }
        }
    }

    func testAgentRotationAPIAllowsCrossAgentAdminOnlyAndReplaysWithoutChangingGeneration() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "rotation-api")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (admin, _) = try await authority.create(name: "Administrator", operations: [.administrator], applets: [])
        let (ordinary, _) = try await authority.create(name: "Ordinary", operations: [.deviceRead], applets: [])
        let (target, originalKey) = try await authority.create(name: "Target", operations: [.deviceRead, .log], applets: ["fixture"])
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, configuration: RotationConfigurationFixture(authority))
        let key = "rly_" + String(repeating: "x", count: 43), hash = PairingClaim.tokenHash(key)
        func rotate(_ caller: HostAgent, _ id: String, sequence: Int64, actionKey: String, replacement: String? = nil) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let request = HostRequest(method: "POST", path: "/v1/agents/\(id)/rotate", body: ["token_hash": replacement ?? hash], idempotencyKey: actionKey)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: "rotation-api", deadline: now + 30_000, createdAt: now, request: request))
        }
        let denied = await rotate(ordinary, target.id, sequence: 1, actionKey: "ordinary")
        XCTAssertEqual(denied.status, 403)
        let selfDenied = await rotate(admin, admin.id, sequence: 1, actionKey: "self")
        XCTAssertEqual(selfDenied.status, 403)
        let viewerDenied = await rotate(admin, viewer.id, sequence: 2, actionKey: "viewer")
        XCTAssertEqual(viewerDenied.status, 403)
        let changed = await rotate(admin, target.id, sequence: 3, actionKey: "rotate")
        XCTAssertEqual(changed.status, 200)
        let replay = await rotate(admin, target.id, sequence: 4, actionKey: "rotate")
        XCTAssertEqual(replay.data, changed.data)
        let current = try await authority.agent(target.id)
        let saved = try XCTUnwrap(current)
        XCTAssertEqual(saved.id, target.id)
        XCTAssertEqual(saved.operations, target.operations)
        XCTAssertEqual(saved.applets, target.applets)
        XCTAssertEqual(saved.tokenHash, hash)
        XCTAssertNotEqual(saved.generation, target.generation)
        let response = String(decoding: changed.data, as: UTF8.self)
        for secret in [key, hash, originalKey, PairingClaim.tokenHash(originalKey)] { XCTAssertFalse(response.contains(secret)) }
        let rejected = await rotate(admin, target.id, sequence: 5, actionKey: "retired", replacement: PairingClaim.tokenHash(originalKey))
        XCTAssertEqual(rejected.status, 422)
        let audit = try await store.hostActivity(kinds: [.request])
        for entry in audit {
            let fields = try HostActivityDetail(entry).json()
            XCTAssertFalse(fields.contains(hash))
            XCTAssertFalse(fields.contains(PairingClaim.tokenHash(originalKey)))
        }
    }
}

extension OwnerDirectHostTests {
    func testDelegatedRotationRollsBackWhenCancelledStoppedOrCommitFails() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "rotation-rollback")
        await authority.setState(enabled: true, awake: true, foreground: true)
        await authority.setOwnerDirectEnabled(true)
        let (admin, _) = try await authority.create(name: "Administrator", operations: [.administrator], applets: [])
        let (target, oldKey) = try await authority.createOwnerDirect(name: "Target", operations: [.deviceRead], applets: [])
        let peer = source
        _ = try await authority.authenticateOwnerDirect(key: oldKey, source: peer)
        let newKey = "brg_" + String(repeating: "z", count: 43)
        let newHash = PairingClaim.tokenHash(newKey)
        let request = HostRequest(method: "POST", path: "/v1/agents/\(target.id)/rotate",
                                  body: ["token_hash": newHash], idempotencyKey: "rollback")
        for interruption in ["cancel", "stop", "write failure"] {
            let gate = FeedbackWaitGate()
            let task = Task {
                try await authority.administer(request, caller: admin) {
                    await gate.hold()
                    if interruption == "write failure" {
                        throw DeviceAPIError(503, "fixture_write_failure", "Fixture prevented credential save.")
                    }
                }
            }
            for _ in 0..<100 {
                if await gate.count == 1 { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            guard await gate.count == 1 else {
                task.cancel()
                await gate.release()
                _ = await task.result
                XCTFail("Rotation did not reach the held commit boundary.")
                return
            }
            if interruption == "cancel" { task.cancel() }
            if interruption == "stop" {
                await authority.setState(enabled: false, awake: false, foreground: true)
                await authority.setState(enabled: true, awake: true, foreground: true)
            }
            await gate.release()
            switch await task.result {
            case .success: XCTFail("Rotation committed after \(interruption).")
            case .failure(let error):
                if interruption == "cancel" { XCTAssertTrue(error is CancellationError) }
                if interruption == "write failure" { XCTAssertEqual((error as? DeviceAPIError)?.code, "fixture_write_failure") }
            }
            let reopened = HostAuthority(store: store, deviceID: "rotation-rollback")
            await reopened.setState(enabled: true, awake: true, foreground: true)
            await reopened.setOwnerDirectEnabled(true)
            for reader in [authority, reopened] {
                let active = try await reader.authenticateOwnerDirect(key: oldKey, source: peer)
                XCTAssertEqual(active.generation, target.generation)
                do {
                    _ = try await reader.authenticateOwnerDirect(key: newKey, source: peer)
                    XCTFail("Uncommitted replacement credential authenticated.")
                } catch let error as DeviceAPIError { XCTAssertEqual(error.status, 401) }
            }
            let retired = try await store.retiredHostCredential(PairingClaim.tokenHash(oldKey))
            XCTAssertNil(retired)
        }
    }
}

extension OwnerDirectHostTests {
    func testOwnerEditsControlAgentWithoutChangingCredentialAndRejectsStaleDraft() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "owner-agent-detail")
        let (agent, key) = try await authority.create(name: "Before", operations: [.deviceRead], applets: [])
        let (viewer, _) = try await authority.createViewer(name: "Viewer")
        let (legacy, _) = try await authority.create(name: "Legacy owner name with more than twenty-four characters", operations: [.deviceRead], applets: [])
        let (taken, _) = try await authority.create(name: "Taken", operations: [.deviceRead], applets: [])
        let editedLegacy = try await authority.editControlAgent(legacy, name: legacy.name, notes: "Owner note",
                                                                operations: legacy.operations, applets: legacy.applets)
        XCTAssertEqual(editedLegacy.name, legacy.name)
        for invalid in ["-Leading", "Otter 🦦", String(repeating: "a", count: 25), " tAKen "] {
            do {
                _ = try await authority.editControlAgent(agent, name: invalid, notes: "ignored", operations: agent.operations, applets: [])
                XCTFail("An invalid or duplicate name was saved.")
            } catch let error as DeviceAPIError {
                XCTAssertEqual(error.code, invalid.contains("tAKen") ? "agent_name_in_use" : "invalid_agent_name")
            }
        }
        let unchanged = try await authority.agent(agent.id)
        XCTAssertEqual(unchanged, agent)
        let unchangedTaken = try await authority.agent(taken.id)
        XCTAssertEqual(unchangedTaken, taken)
        try await authority.pause(agent.id, paused: true)
        let notes = String(repeating: "🦦", count: 100)
        let saved = try await authority.editControlAgent(agent, name: " Renamed ", notes: notes,
                                                         operations: [.deviceRead, .log], applets: ["fixture"])
        XCTAssertEqual(saved.name, "Renamed")
        XCTAssertEqual(saved.notes, notes)
        XCTAssertEqual(saved.operations, [.deviceRead, .log])
        XCTAssertEqual(saved.applets, ["fixture"])
        XCTAssertEqual(saved.id, agent.id)
        XCTAssertEqual(saved.generation, agent.generation)
        XCTAssertTrue(saved.tokenHash == PairingClaim.tokenHash(key))
        XCTAssertTrue(saved.paused)
        do {
            _ = try await authority.editControlAgent(agent, name: "Stale", notes: "", operations: [.deviceRead], applets: [])
            XCTFail("A stale owner draft overwrote current agent fields.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_changed") }
        do {
            _ = try await authority.editControlAgent(viewer, name: "Viewer", notes: "", operations: [.administrator], applets: [])
            XCTFail("Viewer link was editable as a control agent.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "viewer_read_only") }
        do {
            _ = try await authority.editControlAgent(saved, name: saved.name, notes: notes + "x", operations: saved.operations, applets: saved.applets)
            XCTFail("Notes exceeded 200 UTF-16 units.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_agent_notes") }
        let reopened = HostAuthority(store: store, deviceID: "owner-agent-detail")
        let current = try await reopened.agent(saved.id)
        XCTAssertEqual(current, saved)
        let (rotated, newKey) = try await reopened.rotateControlKey(saved.id)
        do {
            _ = try await reopened.editControlAgent(saved, name: "Stale key", notes: "", operations: [.deviceRead], applets: [])
            XCTFail("An old edit draft overwrote the rotated agent.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_changed") }
        let afterRotation = try await reopened.agent(saved.id)
        XCTAssertEqual(afterRotation, rotated)
        XCTAssertTrue(rotated.tokenHash == PairingClaim.tokenHash(newKey))
        XCTAssertTrue(rotated.paused)
        let events = try await store.hostActivity(kinds: [.agent])
        XCTAssertTrue(events.contains { $0.agentID == saved.id && $0.operation == "Updated Renamed" })
        XCTAssertFalse(events.contains { $0.operation.contains(notes) })
    }

    func testAdministratorNotesAPIKeepsNotesOutOfSelfProfileAndActivity() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = try AppletStore(directory: directory)
        addTeardownBlock {
            #if DEBUG
            try await store.closeTemporaryFixture(at: directory)
            try FileManager.default.removeItem(at: directory)
            #endif
        }
        let authority = HostAuthority(store: store, deviceID: "agent-notes")
        await authority.setState(enabled: true, awake: true, foreground: true)
        let (admin, _) = try await authority.create(name: "Admin", operations: [.administrator], applets: [])
        let (agent, _) = try await authority.create(name: "Control", operations: [.configurationRead, .configurationWrite], applets: [])
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, configuration: RotationConfigurationFixture(authority))
        let privateNotes = "A fixture owner note"
        func call(_ caller: HostAgent, _ sequence: Int64, _ method: String, _ path: String, body: [String: Any] = [:]) async -> HostResponse {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return await dispatcher.dispatch(.init(id: UUID().uuidString, sequence: sequence, agentID: caller.id,
                generation: caller.generation, deviceID: "agent-notes", deadline: now + 30_000, createdAt: now,
                request: .init(method: method, path: path, body: body, idempotencyKey: method == "PATCH" ? "notes-\(sequence)" : nil)))
        }
        let path = "/v1/agents/" + agent.id
        let forbidden = await call(agent, 1, "PATCH", path, body: ["notes": privateNotes])
        XCTAssertEqual(forbidden.status, 403)
        let own = await call(agent, 2, "PATCH", "/v1/agents/me", body: ["notes": privateNotes])
        XCTAssertEqual(own.status, 422)
        let updated = await call(admin, 1, "PATCH", path, body: ["name": " Control Renamed ", "notes": privateNotes])
        XCTAssertEqual(updated.status, 200)
        XCTAssertFalse(String(decoding: updated.data, as: UTF8.self).contains(privateNotes))
        let roster = await call(admin, 2, "GET", "/v1/agents")
        XCTAssertEqual(roster.status, 200)
        XCTAssertTrue(String(decoding: roster.data, as: UTF8.self).contains(privateNotes))
        let renamed = try await authority.agent(agent.id)
        XCTAssertEqual(renamed?.name, "Control Renamed")
        let selfProfile = await call(agent, 3, "GET", "/v1/agents/me")
        XCTAssertEqual(selfProfile.status, 200)
        XCTAssertFalse(String(decoding: selfProfile.data, as: UTF8.self).contains(privateNotes))
        let events = try await store.hostActivity()
        for row in events { XCTAssertFalse(try HostActivityDetail(row).json().contains(privateNotes)) }
        let cleared = await call(admin, 3, "PATCH", path, body: ["notes": ""])
        XCTAssertEqual(cleared.status, 200)
        let saved = try await authority.agent(agent.id)
        XCTAssertNil(saved?.notes)
    }
}
