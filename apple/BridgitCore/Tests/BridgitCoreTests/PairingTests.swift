import XCTest
@testable import BridgitCore

final class PairingTests: XCTestCase {
    private let tail = "#v=1&relay=https%3A%2F%2Fr.example&device=dev_ABCDEFGH12&token=rly_0123456789abcdef0123&name=Kitchen&portal=1"
    func testCleanSchemePairing() throws {
        for scheme in ["bridgit"] {
            let p = try XCTUnwrap(Pairing.parse(scheme + "://pair" + tail))
            XCTAssertEqual(p.name, "Kitchen"); XCTAssertTrue(p.portal)
            XCTAssertEqual(p.base.absoluteString, "https://r.example/d/dev_ABCDEFGH12")
        }
    }
    func testUnnamedLinkAndSetupTextUseDeviceLabel() throws {
        let link = "bridgit://pair" + tail.replacingOccurrences(of: "&name=Kitchen", with: "")
        let setup = #"{"base":"https://r.example","device_id":"dev_ABCDEFGH12","token":"rly_0123456789abcdef0123"}"#
        for input in [link, setup] {
            XCTAssertEqual(try XCTUnwrap(Pairing.parse(input)).displayName, "Bridgit device")
        }
    }
    func testOtherBridgitRoutesCannotBecomePairings() {
        for route in ["satellite", "signin", "pair/extra", "evil@pair", "pair:22"] {
            XCTAssertNil(Pairing.parse("bridgit://" + route + tail))
        }
        XCTAssertNil(Pairing.parse("https://pair" + tail))
        XCTAssertNil(Pairing.parse("bridgit://pair" + tail.replacingOccurrences(of: "https%3A", with: "http%3A")))
    }
    func testLegacySavedPairingStillDecodes() throws {
        let old = Data("""
        {"base":"https://r.example/d/dev_ABCDEFGH12","deviceId":"dev_ABCDEFGH12","token":"rly_0123456789abcdef0123","name":"P","portal":false,"addedAt":0}
        """.utf8)
        let p = try JSONDecoder().decode(Pairing.self, from: old)
        XCTAssertNil(p.needsClaim); XCTAssertFalse(p.portal)
        XCTAssertEqual(try JSONDecoder().decode(Pairing.self, from: JSONEncoder().encode(p)), p)
    }
    func testClaimNeedsAffirmativeServerResponse() {
        XCTAssertThrowsError(try PairingClaim.checkClaimed(.init(status: 200, body: Data("{}".utf8))))
        XCTAssertNoThrow(try PairingClaim.checkClaimed(.init(status: 200, body: Data("{\"claimed\":true}".utf8))))
        XCTAssertEqual(PairingClaim.tokenHash("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
    func testRealAPK3347ViewerFormatIsImportOnlyAndKeepsClaimBoundary() throws {
        let input = "bridgitportal://pair" + tail + "&claim=1"
        let pairing = try XCTUnwrap(Pairing.parse(input))
        XCTAssertTrue(pairing.portal); XCTAssertEqual(pairing.needsClaim, true)
        XCTAssertEqual(pairing.base.absoluteString, "https://r.example/d/dev_ABCDEFGH12")
        XCTAssertNil(Pairing.parse(link: try XCTUnwrap(URL(string: input))), "No obsolete app/deep-link alias")
        for route in ["signin", "satellite", "pair/extra", "evil@pair", "pair:22"] {
            XCTAssertNil(Pairing.parse("bridgitportal://" + route + tail))
        }
        for fragment in [tail.replacingOccurrences(of: "v=1", with: "v=2"),
                         tail.replacingOccurrences(of: "&portal=1", with: ""),
                         tail.replacingOccurrences(of: "https%3A", with: "http%3A"),
                         tail + "&relay=https%3A%2F%2Fother.invalid", tail + "&claim=yes"] {
            XCTAssertNil(Pairing.parse("bridgitportal://pair" + fragment))
        }
    }
    func testPairingInputCannotImportASignInAliasByExtractingEmbeddedJSON() {
        let setup = #"{"base":"https://r.example","device_id":"dev_ABCDEFGH12","token":"rly_0123456789abcdef0123"}"#
        XCTAssertNil(Pairing.parse("bridgitportal://signin#" + setup))
        XCTAssertNil(Pairing.parse("bridgit://signin#" + setup))
    }
    func testAmbiguousOrMalformedAuthorizationFieldsAreRejected() {
        for suffix in ["&token=rly_aaaaaaaaaaaaaaaaaaaa", "&device=dev_DIFFERENT12", "&v=1", "&portal=0", "&claim=unexpected"] {
            XCTAssertNil(Pairing.parse("bridgit://pair" + tail + suffix))
        }
    }

    func testAPKEncodedNamesRemainDataRatherThanAdditionalFields() throws {
        let name = "Kitchen & Studio #1 100% ☃"
        var fields = URLComponents()
        fields.queryItems = [URLQueryItem(name: "v", value: "1"), URLQueryItem(name: "relay", value: "https://r.example"),
                            URLQueryItem(name: "device", value: "dev_ABCDEFGH12"), URLQueryItem(name: "token", value: "rly_0123456789abcdef0123"),
                            URLQueryItem(name: "name", value: name), URLQueryItem(name: "portal", value: "1"), URLQueryItem(name: "claim", value: "1")]
        for scheme in ["bridgit", "bridgitportal"] {
            let pairing = try XCTUnwrap(Pairing.parse(scheme + "://pair#" + fields.percentEncodedQuery!))
            XCTAssertEqual(pairing.name, name); XCTAssertEqual(pairing.needsClaim, true)
        }
    }

}
