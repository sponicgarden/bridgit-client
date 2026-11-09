import CryptoKit
import Foundation
import XCTest
@testable import BridgitCore

final class DeviceSignerTests: XCTestCase {
    func testSigningMatchesBackendSPKIAndP1363Contract() throws {
        let signer = try DeviceSigner.ephemeral()
        XCTAssertEqual(signer.publicKey.count, 91)
        XCTAssertTrue(signer.installID.hasPrefix("bgi_")); XCTAssertEqual(signer.installID.count, 28)
        let pub = try P256.Signing.PublicKey(derRepresentation: signer.publicKey)
        let bytes = Data("hello".utf8)
        let sig = try signer.sign(bytes)
        XCTAssertEqual(sig.count, 64)
        XCTAssertTrue(pub.isValidSignature(try .init(rawRepresentation: sig), for: bytes))
        XCTAssertFalse(pub.isValidSignature(try .init(rawRepresentation: sig), for: Data("changed".utf8)))
        XCTAssertEqual(DeviceSigner.base32(Data("foo".utf8)), "mzxw6")
    }
    func testRequestSignsExactBytesEncodedPathAndQuery() throws {
        let signer = try DeviceSigner.ephemeral()
        let body = Data("{\"value\":\"a b\"}".utf8)
        let url = URL(string: "https://api.invalid/v1/test%20path?x=a%20b")!
        let nonce = "00000000-0000-0000-0000-000000000001"
        let req = try signer.signedRequest(url: url, method: "post", body: body, at: 123, nonce: nonce)
        XCTAssertEqual(req.httpBody, body)
        let header = try XCTUnwrap(req.value(forHTTPHeaderField: "X-Bridgit-Signature"))
        let signature = try XCTUnwrap(Data(base64Encoded: String(header.split(separator: ",").last!.dropFirst(4))))
        let text = DeviceSigner.signingText(installID: signer.installID, method: "POST", path: "/v1/test%20path?x=a%20b", at: 123, nonce: nonce, body: body)
        let pub = try P256.Signing.PublicKey(derRepresentation: signer.publicKey)
        XCTAssertTrue(pub.isValidSignature(try .init(rawRepresentation: signature), for: Data(text.utf8)))
        XCTAssertEqual(req.value(forHTTPHeaderField: "X-Bridgit-Install"), signer.installID)
    }
    func testPaidCallCannotImplicitlyRegisterOrCharge() async throws {
        let client = InstallClient(signer: try .ephemeral(), base: URL(string: "https://never-contact.invalid")!)
        do { _ = try await client.appraise(jpeg: Data([1]), applet: "insurance-inventory", package: ["id": "insurance-inventory"]); XCTFail("Unregistered paid call allowed") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "install_not_registered") }
    }
}
