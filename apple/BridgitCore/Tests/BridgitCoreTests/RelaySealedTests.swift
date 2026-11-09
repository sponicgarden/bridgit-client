import CryptoKit
import Foundation
import XCTest
@testable import BridgitCore
final class RelaySealedTests: XCTestCase {
    func testOpensMaintainedAndroidAndJavaScriptFixtureAndAuthenticatesResponse() throws {
        let data = try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "relay-hpke-fixture", withExtension: "json", subdirectory: "Fixtures")))
        let f = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let phone = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(base64Encoded: f["phone_private"] as! String)!)
        let agent = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(base64Encoded: f["agent_private"] as! String)!)
        let envelope = f["envelope"] as! [String: Any]
        let aad = RelaySealed.aad(device: "phone0001", agent: "agent001", generation: "gen1", requestID: "fixture-request", deadline: 1800000000000, sequence: 1, direction: "request")
        let opened = try RelaySealed.open(privateKey: phone, peer: agent.publicKey.x963Representation, envelope: envelope, aad: aad)
        let object = try JSONSerialization.jsonObject(with: opened) as! [String: Any]
        XCTAssertEqual((object["request"] as? [String: Any])?["path"] as? String, "/v1/health")
        XCTAssertThrowsError(try RelaySealed.open(privateKey: phone, peer: agent.publicKey.x963Representation, envelope: envelope, aad: aad + Data([0])))
        XCTAssertThrowsError(try RelaySealed.open(privateKey: phone, peer: P256.KeyAgreement.PrivateKey().publicKey.x963Representation, envelope: envelope, aad: aad))
        let responseAAD = RelaySealed.aad(device: "phone0001", agent: "agent001", generation: "gen1", requestID: "fixture-request", deadline: 1800000000000, sequence: 1, direction: "response")
        let response = try RelaySealed.seal(privateKey: phone, peer: agent.publicKey.x963Representation, plaintext: Data("verified".utf8), aad: responseAAD)
        XCTAssertEqual(try RelaySealed.open(privateKey: agent, peer: phone.publicKey.x963Representation, envelope: response, aad: responseAAD), Data("verified".utf8))
    }
}
