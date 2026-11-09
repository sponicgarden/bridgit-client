import XCTest
@testable import BridgitCore

final class LanDiscoveryHistoryStoreTests: XCTestCase {
    func testActualStoreReopensAndKeepsNetworksSeparate() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-lan-history-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let device = LanDiscoveredDevice(host: "192.168.2.2", name: "Kitchen", services: ["_http._tcp."], cameraCandidate: false, modelHints: [])
        let store = try LanDiscoveryHistoryStore(directory: directory)
        _ = try await store.update(scope: "session-a", observed: [device], reliable: true, now: 1000)
        let reopened = try LanDiscoveryHistoryStore(directory: directory)
        let saved = try await reopened.read(scope: "session-a", now: 1001)
        XCTAssertEqual(saved.first?.name, "Kitchen")
        XCTAssertEqual(saved.first?.lastSeen, 1000)
        let other = try await reopened.read(scope: "session-b", now: 1001)
        XCTAssertTrue(other.isEmpty)
        let mode = try FileManager.default.attributesOfItem(atPath: directory.appendingPathComponent("lan-history.json").path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(mode?.intValue, 0o600)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["lan-history.json"])
    }
    func testCorruptFileIsNotSilentlyReplaced() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-lan-history-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("lan-history.json"), broken = Data("not json".utf8)
        try broken.write(to: file)
        XCTAssertThrowsError(try LanDiscoveryHistoryStore(directory: directory))
        XCTAssertEqual(try Data(contentsOf: file), broken)
    }
    func testFailedWriteDoesNotPublishNewMemoryState() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-lan-history-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LanDiscoveryHistoryStore(directory: directory)
        let file = directory.appendingPathComponent("lan-history.json")
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        let device = LanDiscoveredDevice(host: "192.168.2.2", name: "Kitchen", services: [], cameraCandidate: false, modelHints: [])
        do {
            _ = try await store.update(scope: "session", observed: [device], reliable: true)
            XCTFail("A directory at the file path must prevent saving")
        } catch { }
        let saved = try await store.read(scope: "session")
        XCTAssertTrue(saved.isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["lan-history.json"])
    }
}
