import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import BridgitCore

private actor PhotoRequests {
    var requests: [ConnectionHTTPRequest] = []
    func record(_ request: ConnectionHTTPRequest) { requests.append(request) }
}

final class ConnectionPhotoTests: XCTestCase {
    private func jpeg(width: Int = 64, height: Int = 48) throws -> Data {
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        var seed: UInt32 = 1
        for index in stride(from: 0, to: pixels.count, by: 4) {
            seed = 1664525 &* seed &+ 1013904223
            pixels[index] = UInt8(truncatingIfNeeded: seed >> 24)
            pixels[index + 1] = UInt8(truncatingIfNeeded: seed >> 16)
            pixels[index + 2] = UInt8(truncatingIfNeeded: seed >> 8)
            pixels[index + 3] = 255
        }
        let provider = try XCTUnwrap(CGDataProvider(data: Data(pixels) as CFData))
        let image = try XCTUnwrap(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent))
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }
    private func fixture() async throws -> (URL, AppletStore, HostAuthority, HostAgent, String, Data) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
        let store = try AppletStore(directory: folder)
        try await store.install(["id": "photo-one", "name": "Synthetic photos", "format": "bridgit-template/1", "runtime": ["adapter": "bridgit.package.v1"]])
        try await store.install(["id": "photo-two", "name": "Other synthetic photos", "format": "bridgit-template/1", "runtime": ["adapter": "bridgit.package.v1"]])
        let authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Scoped sender", operations: [.connections, .appletsMediaRead], applets: ["photo-one"])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let image = try jpeg()
        let ref = try await store.savePhoto(applet: "photo-one", jpeg: image)
        return (folder, store, authority, agent, ref, image)
    }
    private func connection(_ id: String) throws -> OwnerConnection {
        try OwnerConnection(name: "Synthetic " + id, address: id == "telegram" ? "https://api.telegram.org/bot123:synthetic" : "https://example.com/webhook",
            presetID: id, extra: id == "telegram" ? ["chat_id": "123"] : id == "pushover" ? ["user": "synthetic", "token": "synthetic"] : [:])
    }
    private func job(_ agent: HostAgent, sequence: Int64, connection: OwnerConnection, ref: String, wait: Bool = true) -> HostJob {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "fixture",
            deadline: now + 120000, createdAt: now,
            request: .init(method: "POST", path: "/v1/connections/send", body: ["to": connection.id, "text": "Synthetic", "photo": ref, "wait_reply": wait], idempotencyKey: UUID().uuidString))
    }
    func testGeneratedDiscoveryDescribesCancelOnDestinationRevocation() throws {
        let operation = try XCTUnwrap(HostAPIContract.operations.first { $0.name == "configuration_connections_remove" })
        XCTAssertTrue(operation.summary.contains("cancels pending deliveries"))
        XCTAssertFalse(operation.summary.contains("No queue"))
        let agent = HostAgent(id: UUID().uuidString, name: "Synthetic settings", generation: UUID().uuidString,
                              tokenHash: String(repeating: "a", count: 64), operations: [.configurationWrite], applets: [], paused: false)
        let openapi = HostAPIContract.openAPI(agent)
        let text = String(decoding: try JSONSerialization.data(withJSONObject: openapi), as: UTF8.self)
        XCTAssertTrue(text.contains("cancels pending deliveries")); XCTAssertFalse(text.contains("No queue exists"))
        let discovery = String(decoding: try JSONSerialization.data(withJSONObject: HostAPIContract.discovery(agent)), as: UTF8.self)
        XCTAssertTrue(discovery.contains("cancels pending deliveries")); XCTAssertFalse(discovery.contains("No queue exists"))
    }
    func testImageConversionAndExactByteLimit() throws {
        let image = try jpeg(width: 2048, height: 1536)
        let converted = try OutboundConnectionService.sendJPEG(image)
        XCTAssertLessThanOrEqual(converted.count, 1_000_000)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(converted as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertLessThanOrEqual(properties[kCGImagePropertyPixelWidth] as! Int, 1280)
        XCTAssertLessThanOrEqual(properties[kCGImagePropertyPixelHeight] as! Int, 1280)
        XCTAssertThrowsError(try OutboundConnectionService.sendJPEG(Data("not an image".utf8)))
        let row = try connection("home_assistant")
        XCTAssertNoThrow(try OutboundConnectionService.request(row, text: "Synthetic", data: [:], id: "fixture", now: Date(), photo: Data(repeating: 0, count: 1_000_000)))
        XCTAssertThrowsError(try OutboundConnectionService.request(row, text: "Synthetic", data: [:], id: "fixture", now: Date(), photo: Data(repeating: 0, count: 1_000_001)))
    }
    func testAllSevenInlineProviderBodiesAndSignedPhotoBytes() throws {
        let photo = try OutboundConnectionService.sendJPEG(jpeg())
        let supported = ["home_assistant", "n8n", "discord", "telegram", "ntfy", "pushover", "json"]
        XCTAssertEqual(ConnectionPreset.all.filter(\.takesPhoto).map(\.id), supported)
        for id in supported {
            let row = try connection(id), now = Date(timeIntervalSince1970: 123456)
            let request = try OutboundConnectionService.request(row, text: "Synthetic", data: ["applet": "Fixture"], id: "stable-id", now: now, photo: photo, waitReply: false)
            XCTAssertEqual(request.method, id == "ntfy" ? "PUT" : "POST")
            if ["home_assistant", "n8n", "json"].contains(id) {
                let body = try JSONSerialization.jsonObject(with: request.body) as! [String: Any]
                let object = try XCTUnwrap(body["photo"] as? [String: Any])
                XCTAssertEqual(object["type"] as? String, "image/jpeg")
                XCTAssertEqual(Data(base64Encoded: object["data_base64"] as! String), photo)
            } else if id == "ntfy" {
                XCTAssertEqual(request.body, photo)
                XCTAssertEqual(request.headers["Filename"], "photo.jpg")
                XCTAssertEqual(request.headers["Content-Type"], "image/jpeg")
            } else {
                let header = try XCTUnwrap(request.headers["Content-Type"])
                XCTAssertTrue(header.hasPrefix("multipart/form-data; boundary=bridgit"))
                let field = id == "discord" ? "files[0]" : id == "telegram" ? "photo" : "attachment"
                XCTAssertNotNil(request.body.range(of: Data(("name=\"" + field + "\"; filename=\"photo.jpg\"").utf8)))
                XCTAssertNotNil(request.body.range(of: photo))
                if id == "discord" { XCTAssertTrue(request.address.hasSuffix("wait=false")) }
                if id == "telegram" { XCTAssertTrue(request.address.hasSuffix("/sendPhoto")) }
            }
            let plain = try OutboundConnectionService.request(row, text: "Synthetic", data: [:], id: "plain-id", now: now)
            XCTAssertEqual(plain.method, "POST")
            if id == "ntfy" { XCTAssertEqual(plain.headers["Content-Type"], "text/plain; charset=utf-8") }
            if id == "telegram" { XCTAssertTrue(plain.address.hasSuffix("/sendMessage")) }
            if ["home_assistant", "n8n", "json"].contains(id) {
                let plainBody = try JSONSerialization.jsonObject(with: plain.body) as! [String: Any]
                XCTAssertNil(plainBody["photo"])
            }
            if row.preset.signs {
                let key = Data(base64Encoded: String(row.signingSecret.dropFirst(6)))!
                let signature = HMAC<SHA256>.authenticationCode(for: Data("stable-id.123456.".utf8) + request.body, using: SymmetricKey(data: key))
                XCTAssertEqual(request.headers["webhook-signature"], "v1," + Data(signature).base64EncodedString())
            }
            let description = try JSONSerialization.data(withJSONObject: row.agentDescription)
            let publicText = String(decoding: description, as: UTF8.self)
            XCTAssertFalse(publicText.contains("example.com")); XCTAssertFalse(publicText.contains(row.signingSecret))
        }
        for id in ["slack", "zapier", "make", "ifttt"] {
            XCTAssertThrowsError(try OutboundConnectionService.request(connection(id), text: "Synthetic", data: [:], id: "fixture", now: Date(), photo: photo)) { error in
                XCTAssertEqual((error as? DeviceAPIError)?.code, "photo_not_supported")
            }
        }
    }
    func testDispatcherScopesPhotosAndMapsPublicErrorsBeforeAnyReceiverWrite() async throws {
        let (_, store, authority, agent, ref, _) = try await fixture()
        let recorder = PhotoRequests()
        let service = OutboundConnectionService(store: store, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 204
        })
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] },
            profile: { _ in throw CancellationError() }, connections: service)
        let row = try connection("home_assistant"); try await store.addOwnerConnection(row)
        let valid = await dispatcher.dispatch(job(agent, sequence: 1, connection: row, ref: ref))
        XCTAssertEqual(valid.status, 200)
        let (other, _) = try await authority.create(name: "Other scope", operations: [.connections, .appletsMediaRead], applets: ["photo-two"])
        let (noMedia, _) = try await authority.create(name: "No media", operations: [.connections], applets: [])
        var sequence: Int64 = 2
        for (caller, value, status, code) in [(other, ref, 404, "not_found"), (noMedia, ref, 403, "agent_not_allowed"),
                                           (agent, "kept:missing", 404, "not_found"), (agent, "../private", 422, "invalid_parameter")] {
            let response = await dispatcher.dispatch(job(caller, sequence: sequence, connection: row, ref: value))
            sequence += 1
            XCTAssertEqual(response.status, status)
            let body = try JSONSerialization.jsonObject(with: response.data) as! [String: Any]
            XCTAssertEqual(body["error"] as? String, code)
        }
        let bad = try await store.savePhoto(applet: "photo-one", jpeg: Data("invalid synthetic image".utf8))
        let invalid = await dispatcher.dispatch(job(agent, sequence: 6, connection: row, ref: bad))
        XCTAssertEqual(invalid.status, 422)
        let body = try JSONSerialization.jsonObject(with: invalid.data) as! [String: Any]
        XCTAssertEqual(body["error"] as? String, "invalid_parameter")
        let unsupported = try connection("slack"); try await store.addOwnerConnection(unsupported)
        let rejected = await dispatcher.dispatch(job(agent, sequence: 7, connection: unsupported, ref: ref))
        XCTAssertEqual(rejected.status, 422)
        let rejectedBody = try JSONSerialization.jsonObject(with: rejected.data) as! [String: Any]
        XCTAssertEqual(rejectedBody["error"] as? String, "invalid_parameter")
        let requests = await recorder.requests
        XCTAssertEqual(requests.count, 1)
    }

    func testDirectQueueRevalidatesCancellationAndTransportCommitInStore() async throws {
        actor Attempts {
            var checks = 0
            var writes = 0
            func checked() -> Int { checks += 1; return checks }
            func written() { writes += 1 }
        }
        let (_, store, authority, _, _, _) = try await fixture()
        await authority.setConnected(false)
        await authority.setOwnerDirectEnabled(true)
        let (agent, key) = try await authority.createOwnerDirect(name: "Direct sender", operations: [.connections], applets: [])
        let original = try XCTUnwrap(TailnetAddress("100.80.2.3"))
        let replacement = try XCTUnwrap(TailnetAddress("100.80.2.4"))
        let destination = try connection("json")
        try await store.addOwnerConnection(destination)
        let attempts = Attempts()
        let outbound = OutboundConnectionService(store: store, transport: { _, beforeWrite in
            try await beforeWrite()
            await attempts.written()
            return 204
        })
        func caller(_ peer: TailnetAddress) async throws -> HostAgent {
            _ = try await authority.authenticateOwnerDirect(key: key, source: peer.value)
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return try await authority.admit(.init(id: UUID().uuidString, sequence: 0, agentID: agent.id, generation: agent.generation,
                deviceID: "fixture", mode: OwnerDirectHostService.mode, deadline: now + 30_000, createdAt: now,
                request: .init(method: "POST", path: "/v1/connections/send"), tailnetSource: peer.value))
        }
        func enqueue(_ sender: HostAgent, key: String) async throws -> String {
            let reply = try await outbound.enqueue(to: destination.id, text: "Fixture", data: [:], agent: sender,
                phone: "Fixture", idempotencyKey: key) {
                    try await authority.authorizeOngoing(sender, operation: .connections)
                }
            return try XCTUnwrap(reply["delivery_id"] as? String)
        }
        let fromOriginal = try await caller(original)
        let pending = try await enqueue(fromOriginal, key: "pending")
        do {
            _ = try await outbound.cancelDelivery(pending, for: fromOriginal) {
                try await authority.authorizeOngoing(fromOriginal, operation: .connections)
                try await authority.setOwnerDirectPeers(agent.id, unlock: true)
            }
            XCTFail("A source revoked after initial cancellation authorization changed the receipt.")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
        let afterRejectedCancel = try await store.queueDelivery(pending)
        XCTAssertEqual(afterRejectedCancel.state, .queued)

        let fromReplacement = try await caller(replacement)
        _ = try await outbound.cancelDelivery(pending, for: fromReplacement) {
            try await authority.authorizeOngoing(fromReplacement, operation: .connections)
        }
        let cancelledByCurrentPeer = try await store.queueDelivery(pending)
        XCTAssertEqual(cancelledByCurrentPeer.state, .cancelled)
        let candidate = try await enqueue(fromReplacement, key: "before-write")
        let ran = try await outbound.processQueue(authorize: { id, generation, mode, peer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: peer)
            if await attempts.checked() == 2 {
                // Revoke after the transport's authority call, just before the store mutation.
                try await authority.setOwnerDirectPeers(agent.id, unlock: true)
            }
        }, eligibleModes: [OwnerDirectHostService.mode])
        XCTAssertTrue(ran)
        let after = try await store.queueDelivery(candidate)
        XCTAssertEqual(after.state, .cancelled)
        XCTAssertEqual(after.error, "agent_revoked")
        XCTAssertFalse(after.writing)
        XCTAssertFalse(after.uncertain)
        let writes = await attempts.writes
        XCTAssertEqual(writes, 0)
    }

    func testDirectQueuedPhotoKeepsOriginalPeerAndSkipsInactiveTransport() async throws {
        let (folder, store, authority, relayAgent, ref, _) = try await fixture()
        await authority.setOwnerDirectEnabled(true)
        let (directAgent, key) = try await authority.createOwnerDirect(name: "Direct sender",
            operations: [.connections, .appletsMediaRead], applets: ["photo-one"])
        let original = try XCTUnwrap(TailnetAddress("100.80.2.3"))
        let replacement = try XCTUnwrap(TailnetAddress("100.80.2.4"))
        let destination = try connection("json")
        try await store.addOwnerConnection(destination)
        let recorder = PhotoRequests()
        let outbound = OutboundConnectionService(store: store, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 204
        })
        let dispatcher = HostDispatcher(authority: authority, store: store,
            engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() }, connections: outbound)
        let direct = OwnerDirectHostService(authority: authority, dispatcher: dispatcher)
        let request = TailnetHTTPRequest(method: "POST", target: "/v1/connections/send",
            headers: ["authorization": "Bearer " + key, "idempotency-key": "direct-kept-photo"],
            body: try JSONSerialization.data(withJSONObject: ["to": destination.id, "text": "Direct photo", "photo": ref]))
        let reply = await direct.handle(request, source: original.value)
        XCTAssertEqual(reply.status, 200)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: reply.data) as? [String: Any])
        let directID = try XCTUnwrap(object["delivery_id"] as? String)
        let reopened = try AppletStore(directory: folder)
        let directReceipt = try await reopened.queueDelivery(directID)
        XCTAssertEqual(directReceipt.agentID, directAgent.id)
        XCTAssertEqual(directReceipt.sourcePeer, original)
        XCTAssertNotNil(directReceipt.photo)
        let relayReceipt = try await outbound.enqueue(to: destination.id, text: "Relay message", data: [:],
            agent: relayAgent, phone: "Fixture", idempotencyKey: "relay-note", authorize: {})
        let relayID = try XCTUnwrap(relayReceipt["delivery_id"] as? String)
        let authorize: OutboundConnectionService.QueueAuthorizer = { id, generation, mode, sourcePeer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer)
        }
        let photo: OutboundConnectionService.QueuePhotoAuthorizer = { id, generation, mode, sourcePeer, applet in
            try await authority.authorizeConnectionPhoto(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer, applet: applet)
        }
        await authority.setOwnerDirectEnabled(false)
        let relayModes = await authority.connectionDeliveryModes()
        XCTAssertFalse(relayModes.contains(OwnerDirectHostService.mode))
        let processedRelay = try await outbound.processQueue(authorize: authorize, authorizePhoto: photo, eligibleModes: relayModes)
        XCTAssertTrue(processedRelay)
        let relayAfter = try await store.queueDelivery(relayID)
        XCTAssertEqual(relayAfter.state, .accepted)
        XCTAssertNil(relayAfter.sourcePeer)
        let directPending = try await store.queueDelivery(directID)
        XCTAssertEqual(directPending.state, .queued)
        XCTAssertEqual(directPending.attempts, 0)

        await authority.setConnected(false)
        await authority.setOwnerDirectEnabled(true)
        try await authority.setOwnerDirectPeers(directAgent.id, unlock: true)
        _ = try await authority.authenticateOwnerDirect(key: key, source: replacement.value)
        let directModes = await authority.connectionDeliveryModes()
        XCTAssertEqual(directModes, [OwnerDirectHostService.mode])
        let processedRevocation = try await outbound.processQueue(authorize: authorize, authorizePhoto: photo, eligibleModes: directModes)
        XCTAssertTrue(processedRevocation)
        let cancelled = try await store.queueDelivery(directID)
        XCTAssertEqual(cancelled.state, .cancelled)
        XCTAssertEqual(cancelled.error, "agent_revoked")
        XCTAssertEqual(cancelled.attempts, 0)
        XCTAssertNil(cancelled.photo)
        let afterRevocation = await recorder.requests
        XCTAssertEqual(afterRevocation.count, 1)
        let approved = await direct.handle(.init(method: request.method, target: request.target,
            headers: ["authorization": "Bearer " + key, "idempotency-key": "new-direct-kept-photo"], body: request.body),
            source: replacement.value)
        XCTAssertEqual(approved.status, 200)
        let approvedBody = try XCTUnwrap(JSONSerialization.jsonObject(with: approved.data) as? [String: Any])
        let approvedID = try XCTUnwrap(approvedBody["delivery_id"] as? String)
        let processedDirect = try await outbound.processQueue(authorize: authorize, authorizePhoto: photo, eligibleModes: directModes)
        XCTAssertTrue(processedDirect)
        let directAfter = try await store.queueDelivery(approvedID)
        XCTAssertEqual(directAfter.state, .accepted)
        XCTAssertEqual(directAfter.sourcePeer, replacement)
        let requests = await recorder.requests
        XCTAssertEqual(requests.count, 2)
        let deliveredBody = try XCTUnwrap(JSONSerialization.jsonObject(with: requests[1].body) as? [String: Any])
        let photoBody = try XCTUnwrap(deliveredBody["photo"] as? [String: Any])
        XCTAssertEqual(photoBody["type"] as? String, "image/jpeg")
    }

    func testQueuedPhotoSurvivesReopenAndRetriesSameBytesThenClearsOnTerminalStates() async throws {
        let (folder, store, authority, agent, ref, _) = try await fixture()
        let row = try connection("json"); try await store.addOwnerConnection(row)
        let photo = try await store.connectionPhoto(ref: ref, agent: agent)
        let recorder = PhotoRequests()
        let service = OutboundConnectionService(store: store, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 503
        })
        let accepted = try await service.enqueue(to: row.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: "photo-once", photo: photo, authorize: {})
        let id = accepted["delivery_id"] as! String
        let queued = try await store.queueDelivery(id)
        XCTAssertEqual(queued.photo, photo.jpeg); XCTAssertEqual(queued.photoApplet, "photo-one")
        _ = try await service.processQueue(authorize: { id, generation, mode, sourcePeer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer)
        }, authorizePhoto: { id, generation, mode, sourcePeer, applet in
            try await authority.authorizeConnectionPhoto(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer, applet: applet)
        })
        let retry = try await store.queueDelivery(id)
        XCTAssertEqual(retry.state, .queued); XCTAssertEqual(retry.photo, photo.jpeg)
        let reopened = try AppletStore(directory: folder)
        let restored = OutboundConnectionService(store: reopened, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 204
        })
        _ = try await restored.processQueue(now: Date(timeIntervalSince1970: retry.next + 1), authorize: { id, generation, mode, sourcePeer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer)
        }, authorizePhoto: { id, generation, mode, sourcePeer, applet in
            try await authority.authorizeConnectionPhoto(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer, applet: applet)
        })
        let terminal = try await reopened.queueDelivery(id)
        XCTAssertEqual(terminal.state, .accepted); XCTAssertNil(terminal.photo); XCTAssertNil(terminal.photoApplet)
        let requests = await recorder.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].body, requests[1].body)
        XCTAssertEqual(requests.map { $0.headers["Idempotency-Key"] }, [id, id])
        let cancelled = try await restored.enqueue(to: row.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: "photo-cancel", photo: photo, authorize: {})
        let cancelledID = cancelled["delivery_id"] as! String
        _ = try await restored.cancelDelivery(cancelledID, for: agent, authorize: {})
        let cancelledRow = try await reopened.queueDelivery(cancelledID)
        XCTAssertEqual(cancelledRow.state, .cancelled); XCTAssertNil(cancelledRow.photo)
        let revoked = try await restored.enqueue(to: row.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: "photo-revoke", photo: photo, authorize: {})
        try await reopened.setOwnerConnection(row.id, enabled: false)
        let revokedRow = try await reopened.queueDelivery(revoked["delivery_id"] as! String)
        XCTAssertEqual(revokedRow.state, .cancelled); XCTAssertNil(revokedRow.photo)
    }
    func testMediaGrantRevocationPreventsQueuedReceiverWriteAndDropsCopiedPhoto() async throws {
        let (_, store, authority, agent, ref, _) = try await fixture()
        let row = try connection("json"); try await store.addOwnerConnection(row)
        let photo = try await store.connectionPhoto(ref: ref, agent: agent)
        let recorder = PhotoRequests()
        let service = OutboundConnectionService(store: store, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 204
        })
        let accepted = try await service.enqueue(to: row.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: "revoke-media", photo: photo, authorize: {})
        let (owner, _) = try await authority.create(name: "Synthetic owner", operations: [.administrator], applets: [])
        _ = try await authority.administer(.init(method: "PATCH", path: "/v1/agents/" + agent.id,
            body: ["operations": [HostOperation.connections.rawValue], "applets": [] as [String]]), caller: owner)
        _ = try await service.processQueue(authorize: { id, generation, mode, sourcePeer in
            try await authority.authorizeConnectionDelivery(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer)
        }, authorizePhoto: { id, generation, mode, sourcePeer, applet in
            try await authority.authorizeConnectionPhoto(agentID: id, generation: generation, mode: mode, sourcePeer: sourcePeer, applet: applet)
        })
        let receipt = try await store.queueDelivery(accepted["delivery_id"] as! String)
        XCTAssertEqual(receipt.state, .cancelled); XCTAssertNil(receipt.photo)
        let requests = await recorder.requests; XCTAssertTrue(requests.isEmpty)
    }
    func testDurablePhotoQuotaIsCheckedAtomicallyAndCancellationReleasesBytes() async throws {
        let (_, store, _, agent, _, _) = try await fixture()
        let destination = try connection("json"); try await store.addOwnerConnection(destination)
        let photo = try OutboundConnectionService.sendJPEG(jpeg())
        let now = Date()
        func row(_ key: String) -> ConnectionDelivery {
            .init(id: UUID().uuidString, destination: destination.id, agentID: agent.id, generation: agent.generation,
                mode: agent.mode, idempotency: ConnectionDelivery.digest(Data(key.utf8)), fingerprint: ConnectionDelivery.digest(Data(key.utf8)),
                text: "Synthetic", payload: Data("{}".utf8), photo: photo, photoApplet: "photo-one", created: now.timeIntervalSince1970, next: now.timeIntervalSince1970)
        }
        let first = try await store.enqueueDelivery(row("first"), connection: destination, now: now, photoStorageLimit: photo.count * 2 - 1)
        do {
            _ = try await store.enqueueDelivery(row("second"), connection: destination, now: now, photoStorageLimit: photo.count * 2 - 1)
            XCTFail("Photo quota admitted overflow")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "queue_full") }
        _ = try await store.cancelDelivery(first.id)
        _ = try await store.enqueueDelivery(row("after-cancel"), connection: destination, now: now, photoStorageLimit: photo.count * 2 - 1)
    }

    func testPrePhotoTextQueueReceiptStillReplaysWithUnchangedFingerprint() async throws {
        let (_, store, _, agent, _, _) = try await fixture()
        let destination = try connection("json"); try await store.addOwnerConnection(destination)
        let now = Date(), key = "legacy-text"
        let intent = try JSONSerialization.data(withJSONObject: ["to": destination.id, "text": "Synthetic", "data": [:]], options: [.sortedKeys])
        let old = ConnectionDelivery(id: UUID().uuidString, destination: destination.id, agentID: agent.id, generation: agent.generation,
            mode: agent.mode, idempotency: ConnectionDelivery.digest(Data(key.utf8)), fingerprint: ConnectionDelivery.digest(intent),
            text: "Synthetic", payload: Data("{}".utf8), created: now.timeIntervalSince1970, next: now.timeIntervalSince1970)
        _ = try await store.enqueueDelivery(old, connection: destination, now: now)
        let service = OutboundConnectionService(store: store)
        let replay = try await service.enqueue(to: destination.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: key, authorize: {})
        XCTAssertEqual(replay["delivery_id"] as? String, old.id)
    }

    func testDeletingDestinationCancelsPendingCopiedPhoto() async throws {
        let (_, store, authority, agent, ref, _) = try await fixture()
        let destination = try connection("json"); try await store.addOwnerConnection(destination)
        let image = try await store.connectionPhoto(ref: ref, agent: agent)
        let recorder = PhotoRequests()
        let service = OutboundConnectionService(store: store, transport: { request, authorize in
            try await authorize(); await recorder.record(request); return 204
        })
        let queued = try await service.enqueue(to: destination.id, text: "Synthetic", data: [:], agent: agent, phone: "Fixture", idempotencyKey: "delete-destination", photo: image, authorize: {})
        let (settings, _) = try await authority.create(name: "Synthetic settings", operations: [.configurationWrite], applets: [])
        let response = try await service.configuration(.init(method: "DELETE", path: "/v1/owner/configuration/connections/" + destination.id), agent: settings, authorize: {})
        XCTAssertEqual(response.status, 200)
        let receipt = try await store.queueDelivery(queued["delivery_id"] as! String)
        XCTAssertEqual(receipt.state, .cancelled); XCTAssertNil(receipt.photo)
        let more = try await service.processQueue(authorize: { _, _, _, _ in })
        XCTAssertFalse(more)
        let writes = await recorder.requests; XCTAssertTrue(writes.isEmpty)
    }
}
