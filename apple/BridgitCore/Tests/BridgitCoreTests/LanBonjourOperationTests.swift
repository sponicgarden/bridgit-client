import XCTest
import dnssd
@testable import BridgitCore

final class LanBonjourOperationTests: XCTestCase {
    func testLocalOnlyBrowseAndResolvePreservePortAndSelectedTXT() async throws {
        let name = "Bridgit-" + UUID().uuidString
        let type = "_bridgit-parity._tcp."
        var registration: DNSServiceRef?
        let text = Data([5]) + Data("fn=Hi".utf8)
        let error = text.withUnsafeBytes { bytes in
            DNSServiceRegister(&registration, 0, kDNSServiceInterfaceIndexLocalOnly, name, type, "local.", nil,
                               UInt16(48000).bigEndian, UInt16(text.count), bytes.baseAddress, nil, nil)
        }
        XCTAssertEqual(error, DNSServiceErrorType(kDNSServiceErr_NoError))
        guard let registration else { XCTFail("Local-only service was not registered"); return }
        defer { DNSServiceRefDeallocate(registration) }
        let browse = LanBonjourOperation.browse(type: type, interface: kDNSServiceInterfaceIndexLocalOnly, duration: 2)
        defer { browse.stop() }
        var found: LanBonjourOperation.Service?
        for try await event in browse.events {
            if case .service(let service) = event, service.name == name { found = service; break }
        }
        let service = try XCTUnwrap(found)
        let resolve = LanBonjourOperation.resolve(service)
        defer { resolve.stop() }
        var resolved: LanBonjourOperation.Resolution?
        for try await event in resolve.events {
            if case .resolved(let value) = event { resolved = value; break }
        }
        let result = try XCTUnwrap(resolved)
        XCTAssertEqual(result.port, 48000)
        XCTAssertEqual(result.attributes["fn"], Data("Hi".utf8))
        XCTAssertFalse(result.host.isEmpty)
    }

    func testStopFinishesStreamAndReleasesCallbackContext() async throws {
        var operation: LanBonjourOperation? = .browse(type: "_bridgit-parity._tcp.", interface: kDNSServiceInterfaceIndexLocalOnly, duration: 30)
        weak var released = operation
        let events = try XCTUnwrap(operation).events
        operation?.stop()
        for try await _ in events {}
        operation = nil
        for _ in 0..<50 where released != nil { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertNil(released, "Stopped DNS-SD operation must release its retained callback context")
    }
}
