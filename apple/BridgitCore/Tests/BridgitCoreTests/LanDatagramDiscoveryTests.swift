import Darwin
import XCTest
@testable import BridgitCore

final class LanDatagramDiscoveryTests: XCTestCase {
    func testPinnedDiscoveryMessagesAndResponseHints() {
        let ssdp = LanDatagramDiscovery.Method.ssdp
        XCTAssertEqual(ssdp.port, 1900)
        XCTAssertEqual(String(decoding: ssdp.query(), as: UTF8.self), "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n")
        XCTAssertEqual(ssdp.service(Data("HTTP/1.1 200 OK\r\nsT: urn:device:player\r\nLOCATION: http://evil.invalid/private\r\n".utf8)), "urn:device:player")
        XCTAssertEqual(ssdp.service(Data("reply".utf8)), "ssdp_responder")
        let onvif = LanDatagramDiscovery.Method.onvif
        XCTAssertEqual(onvif.port, 3702)
        let id = UUID(uuidString: "12345678-1234-1234-1234-123456789abc")!
        let query = String(decoding: onvif.query(id: id), as: UTF8.self)
        XCTAssertTrue(query.contains("uuid:12345678-1234-1234-1234-123456789abc"))
        XCTAssertTrue(query.contains("<d:Types>n:NetworkVideoTransmitter</d:Types>"))
        XCTAssertEqual(onvif.service(Data("<d:ProbeMatches>ignored URL</d:ProbeMatches>".utf8)), "onvif_candidate")
        XCTAssertNil(onvif.service(Data("other reply".utf8)))
        XCTAssertNil(ssdp.service(Data(repeating: 65, count: 8193)))
    }

    func testRealDatagramsPreserveSourceAndPayloadWithoutInterfaceFallback() async throws {
        let index = if_nametoindex("lo0")
        let receiver = try LanDatagramSocket(local: 0x7f000001, interface: index, multicast: false)
        let sender = try LanDatagramSocket(local: 0x7f000001, interface: index, multicast: false)
        XCTAssertNil(try receiver.receive())
        let payload = Data("read-only fixture".utf8)
        try sender.send(payload, address: 0x7f000001, port: receiver.localPort())
        var packet: LanDatagramSocket.Packet?
        for _ in 0..<100 {
            packet = try receiver.receive()
            if packet != nil { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertEqual(packet?.host, "127.0.0.1")
        XCTAssertEqual(packet?.port, try sender.localPort())
        XCTAssertEqual(packet?.data, payload)
        XCTAssertThrowsError(try LanDatagramSocket(local: 0x7f000001, interface: UInt32.max, multicast: false))
    }

    func testAuthorityAndPhysicalLANRequiredBeforeAnyProbe() async throws {
        let invalid = LanInterfaceSelection(name: "loopback", index: if_nametoindex("lo0"), address: "127.0.0.1", prefix: 8)
        do {
            try await LanDatagramDiscovery.scan(.ssdp, network: invalid, observations: .init(network: invalid), authorize: {}, currentNetwork: { invalid })
            XCTFail("Public scan must reject loopback")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "lan_unavailable") }
        do {
            try await LanDatagramDiscovery.scan(.onvif, network: invalid, observations: .init(network: invalid), authorize: {
                throw DeviceAPIError(403, "agent_revoked", "Revoked")
            }, currentNetwork: { invalid })
            XCTFail("Authority must be checked before opening a socket")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "agent_revoked") }
    }
}
