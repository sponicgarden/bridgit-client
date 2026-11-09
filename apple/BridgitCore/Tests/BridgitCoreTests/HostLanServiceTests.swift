import XCTest
@testable import BridgitCore

final class HostLanServiceTests: XCTestCase {
    private func fixture(outboundAccess: OutboundAccess = .unrestricted, _ runner: @escaping LanDiscoverySession.Runner = { _, _ in }) throws -> (HostLanService, LanNetworkProvider, URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let network = LanNetworkProvider(testing: ())
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        let service = try HostLanService(network: network, history: .init(directory: root), session: .init(runMethod: runner), outboundAccess: outboundAccess, enabled: { true })
        return (service, network, root)
    }
    func testScanPersistsAndRememberedReadDoesNotScan() async throws {
        actor Count { var count = 0; func add() { count += 1 } }
        let count = Count()
        let (service, _, root) = try fixture { method, context in
            await count.add()
            if method == .mdns { await context.observations.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.") }
        }
        defer { try? FileManager.default.removeItem(at: root) }
        let scan = try await service.execute(.init(method: "POST", path: "/v1/lan/discover"), authorize: {})
        let scanJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: scan.data) as? [String: Any])
        XCTAssertEqual(scanJSON["scan_performed"] as? Bool, true)
        XCTAssertEqual(scanJSON["total_devices"] as? Int, 1)
        let read = try await service.execute(.init(method: "GET", path: "/v1/lan/discovered"), authorize: {})
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        XCTAssertEqual(json["total_devices"] as? Int, 1)
        XCTAssertEqual(json["scan_performed"] as? Bool, false)
        let metadata = try XCTUnwrap(json["network"] as? [String: Any])
        XCTAssertEqual(metadata["history_persistence"] as? String, "connection")
        XCTAssertNil(metadata["history_scope"])
        let calls = await count.count
        XCTAssertEqual(calls, 5)
    }
    func testExternalPauseRefusesOwnerAndAgentScansBeforeProbeButKeepsInventory() async throws {
        let (service, _, root) = try fixture(outboundAccess: .init(paused: { true })) { _, _ in
            XCTFail("Paused scanner called a probe")
        }
        defer { try? FileManager.default.removeItem(at: root) }
        let inventory = try await service.ownerInventory()
        XCTAssertEqual(inventory.status, 200)
        do { _ = try await service.ownerScan(authorize: {}); XCTFail("Paused owner scan ran") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
        do {
            _ = try await service.execute(.init(method: "POST", path: "/v1/lan/discover"), authorize: {})
            XCTFail("Paused agent scan ran")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "external_paused") }
    }

    func testDeliveryRejectsNetworkChangeAndRevocation() async throws {
        let (service, network, root) = try fixture()
        defer { try? FileManager.default.removeItem(at: root) }
        let response = try await service.execute(.init(method: "GET", path: "/v1/lan/discovered"), authorize: {})
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        do { try await response.beforeDelivery?(); XCTFail("Old scope must not be delivered") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_changed") }
        do {
            _ = try await service.execute(.init(method: "GET", path: "/v1/lan/catalog"), authorize: { throw DeviceAPIError(403, "agent_revoked", "Revoked") })
            XCTFail("Revoked caller must not receive catalog")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }
    func testOwnerReadDoesNotNeedAgentDiscoverySwitchOrStartScan() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let network = LanNetworkProvider(testing: ())
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        let service = try HostLanService(network: network, history: .init(directory: root),
            session: .init { _, _ in XCTFail("Owner browsing must not scan") }, enabled: { false })
        let coverage = try await service.ownerCoverage()
        let catalog = try XCTUnwrap(JSONSerialization.jsonObject(with: coverage.data) as? [String: Any])
        XCTAssertFalse((catalog["services"] as? [[String: Any]] ?? []).isEmpty)
        XCTAssertEqual(coverage.status, 200)
        let inventory = try await service.ownerInventory()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: inventory.data) as? [String: Any])
        XCTAssertEqual(object["scan_performed"] as? Bool, false)
        let logo = try await service.ownerLogo(id: "tapo")
        XCTAssertEqual(logo.status, 200)
        do {
            _ = try await service.execute(.init(method: "GET", path: "/v1/lan/discovered"), authorize: {})
            XCTFail("Owner entry point must not relax authenticated API policy")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_disabled") }
        do { _ = try await service.ownerLogo(id: "../discovered"); XCTFail("Logo path must remain bounded") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "invalid_parameter") }
    }

    func testOwnerScanIsSeparateFromAgentSwitchAndHonorsCancellation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let network = LanNetworkProvider(testing: ())
        network.accept(.success(.init(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)))
        let service = try HostLanService(network: network, history: .init(directory: root), session: .init { _, context in
            XCTAssertTrue(context.targets.isEmpty)
        }, enabled: { false })
        let result = try await service.ownerScan(authorize: {})
        XCTAssertEqual(result.status, 200)
        do { _ = try await service.ownerScan(authorize: { throw CancellationError() }); XCTFail("Cancelled owner scan must fail") }
        catch is CancellationError { }
    }

    func testDisabledPolicyAndPassiveLogo() async throws {
        let (service, network, root) = try fixture { _, _ in XCTFail("Passive reads must not scan") }
        defer { try? FileManager.default.removeItem(at: root) }
        let disabled = try HostLanService(network: network, history: .init(directory: root), session: .init(), enabled: { false })
        do {
            _ = try await disabled.execute(.init(method: "GET", path: "/v1/lan/catalog"), authorize: {})
            XCTFail("Disabled discovery must reject API")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_disabled") }
        let response = try await service.execute(.init(method: "GET", path: "/v1/lan/brands/tapo/logo", query: ["raw": true]), authorize: {})
        XCTAssertEqual(response.contentType, "image/svg+xml")
        XCTAssertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        XCTAssertFalse(response.data.isEmpty)
    }
    func testRecreatedServiceReadsOnlyItsAuthorizedNetworkHistory() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let selected = LanInterfaceSelection(name: "fixture", index: 4, address: "192.168.2.1", prefix: 24)
        let identity = LanHistoryIdentity(bssid: "10:20:30:40:50:60", gateways: ["192.168.2.254"])
        let firstNetwork = LanNetworkProvider(testing: ())
        firstNetwork.accept(.success(selected), identity: identity)
        let first = try HostLanService(network: firstNetwork, history: .init(directory: root),
            session: .init { method, context in
                if method == .mdns { await context.observations.add(host: "192.168.2.2", source: .mdns, service: "_http._tcp.") }
            }, enabled: { true })
        let scan = try await first.ownerScan(authorize: {})
        let nextNetwork = LanNetworkProvider(testing: ())
        nextNetwork.accept(.success(selected), identity: identity)
        let reopened = try HostLanService(network: nextNetwork, history: .init(directory: root),
            session: .init { _, _ in XCTFail("Remembered reads must not probe") }, enabled: { true })
        let read = try await reopened.ownerInventory()
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: read.data) as? [String: Any])
        XCTAssertEqual(json["total_devices"] as? Int, 1)
        XCTAssertEqual(json["scan_performed"] as? Bool, false)
        let metadata = try XCTUnwrap(json["network"] as? [String: Any])
        XCTAssertEqual(metadata["history_persistence"] as? String, "authorized_network")
        let wire = String(decoding: read.data, as: UTF8.self)
        XCTAssertFalse(wire.contains(identity.bssid))
        XCTAssertFalse(wire.contains(try nextNetwork.snapshot().historyScope))
        XCTAssertFalse(wire.contains(try nextNetwork.snapshot().scope))
        firstNetwork.accept(.success(selected), identity: identity)
        do { try await scan.beforeDelivery?(); XCTFail("Same AP reconnect must invalidate pending delivery") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_changed") }
        nextNetwork.accept(.success(selected), identity: .init(bssid: "10:20:30:40:50:61", gateways: ["192.168.2.254"]))
        let other = try await reopened.ownerInventory()
        let otherJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: other.data) as? [String: Any])
        XCTAssertEqual(otherJSON["total_devices"] as? Int, 0)
    }

}
