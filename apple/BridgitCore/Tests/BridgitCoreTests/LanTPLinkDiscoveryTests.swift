import XCTest
@testable import BridgitCore

final class LanTPLinkDiscoveryTests: XCTestCase {
    func testProbeMatchesHeaderChecksumAndContainsOnlyPublicKey() throws {
        let packet = try LanTPLinkDiscovery.query()
        XCTAssertEqual(Array(packet.prefix(4)), [2,0,0,1])
        XCTAssertEqual(Int(packet[4]) * 256 + Int(packet[5]), packet.count - 16)
        XCTAssertEqual(Array(packet[6..<8]), [17,0])
        let checksum = packet[12..<16].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        var restored = packet
        restored.replaceSubrange(12..<16, with: [0x5a,0x6b,0x7c,0x8d])
        XCTAssertEqual(checksum, LanTPLinkDiscovery.crc32(restored))
        XCTAssertEqual(LanTPLinkDiscovery.crc32(Data("123456789".utf8)), 0xcbf43926)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: packet.dropFirst(16)) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["params"])
        let params = try XCTUnwrap(json["params"] as? [String: String])
        XCTAssertEqual(Set(params.keys), ["rsa_key"])
        let pem = try XCTUnwrap(params["rsa_key"])
        XCTAssertTrue(pem.hasPrefix("-----BEGIN PUBLIC KEY-----\n"))
        XCTAssertFalse(pem.contains("PRIVATE"))
        // Independent parser verifies the SPKI wrapper is usable, not merely
        // that our own encoder accepts its output. No private key leaves memory.
        let process = Process(), input = Pipe(), output = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        process.arguments = ["rsa", "-pubin", "-text", "-noout"]
        process.standardInput = input; process.standardOutput = output; process.standardError = output
        try process.run()
        try input.fileHandleForWriting.write(contentsOf: Data(pem.utf8))
        try input.fileHandleForWriting.close()
        let decoded = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
        XCTAssertTrue(String(decoding: decoded, as: UTF8.self).contains("2048 bit"))
    }
    func testOnlyPublicModelAndTypeAreUsed() throws {
        func packet(_ type: String, _ model: String) throws -> Data {
            var header = Data(repeating: 0, count: 16); header[0] = 2
            return header + (try JSONSerialization.data(withJSONObject: ["error_code": 0, "result": ["device_type": type, "device_model": model, "encrypt_info": ["data": "PRIVATE-ACCOUNT-DATA"]]]))
        }
        XCTAssertEqual(LanTPLinkDiscovery.parse(try packet("SMART.IPCAMERA", "C210")), .init(name: "Tapo C210", service: "tapo_camera"))
        XCTAssertEqual(LanTPLinkDiscovery.parse(try packet("HOMEWIFISYSTEM", "M4R"))?.service, "tplink_router")
        XCTAssertEqual(LanTPLinkDiscovery.parse(try packet("SMART.TAPOPLUG", "P110M"))?.service, "tplink_smart_device")
        XCTAssertEqual(LanTPLinkDiscovery.parse(try packet("unknown", "\u{0001}X"))?.name, "TP-Link X")
        XCTAssertFalse(String(describing: LanTPLinkDiscovery.parse(try packet("SMART.IPCAMERA", "C210"))).contains("PRIVATE-ACCOUNT"))
    }
    func testMalformedPacketsAreNotDevices() {
        XCTAssertNil(LanTPLinkDiscovery.parse(Data(repeating: 0, count: 16)))
        XCTAssertNil(LanTPLinkDiscovery.parse(Data(repeating: 2, count: 8193)))
        XCTAssertNil(LanTPLinkDiscovery.parse(Data([2] + Array(repeating: 0, count: 15)) + Data("not json".utf8)))
    }
}
