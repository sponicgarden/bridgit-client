import XCTest
@testable import Bridgit_iOS
import BridgitCore

/// Mirrors the APK's AppletLists unit tests: portal list, which pages stay in the portal, the card library.
@MainActor
final class AppletListsTests: XCTestCase {
    func testPortalsMatch3347DeviceScopeNamesOrderingAndURLs() {
        func row(_ id: String, _ title: String? = nil, phone: String = "bgi_x") -> AppletSummary {
            AppletSummary(phone: phone, applet: id, friendly_name: title)
        }
        let pages = AppletLists.portals(install: "bgi_x",
            account: [row("pkg.kitchen-cleanliness"), row("pkg.insurance", "Inventory"), row("dog"),
                      row("pkg.invalid/path"), row("pkg.insurance", "Inventory"), row("pkg.remote", phone: "bgi_y"),
                      row("activity"), row("overview")])
        XCTAssertEqual(pages.map(\.name), ["Pet monitor", "Inventory", "Kitchen cleanliness"])
        XCTAssertEqual(pages.map(\.url.absoluteString), ["https://bridgit.cc/myapps/?device=bgi_x#dog",
            "https://bridgit.cc/myapps/?device=bgi_x#pkg.insurance",
            "https://bridgit.cc/myapps/?device=bgi_x#pkg.kitchen-cleanliness"])
    }

    func testPortalsForDevice() {
        let pairing = Pairing(base: URL(string: "https://r.example/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12",
                              token: "rly_0123456789abcdef0123", name: "Kitchen", install: "bgi_k", portal: true)
        let relayOnly = PortalDevice(id: "relay:dev", name: "Kitchen", cloud: nil, pairing: pairing)
        XCTAssertEqual(AppletLists.portals(device: relayOnly, accountApplets: []), [])

        let linked = PortalDevice(id: "cloud:bgi_sample_kitchen", name: "Kitchen phone", cloud: SampleData.phones[0], pairing: nil)
        XCTAssertEqual(AppletLists.portals(device: linked, accountApplets: []).map(\.id), [])
        XCTAssertEqual(AppletLists.portals(device: linked, accountApplets: SampleData.applets).map(\.id),
                       ["dog"])
        // Another phone's Pet monitor doesn't count.
        let other = PortalDevice(id: "cloud:bgi_sample_garage", name: "Garage", cloud: SampleData.phones[1], pairing: nil)
        XCTAssertEqual(AppletLists.portals(device: other, accountApplets: SampleData.applets).map(\.id), [])
    }

    func testStaysInPortal() {
        XCTAssertTrue(AppletLists.staysInPortal(URL(string: "https://bridgit.cc/myapps/#dog")!))
        XCTAssertTrue(AppletLists.staysInPortal(URL(string: "https://watch.bridgit.cc/x")!))
        XCTAssertFalse(AppletLists.staysInPortal(URL(string: "http://bridgit.cc/")!))
        XCTAssertFalse(AppletLists.staysInPortal(URL(string: "https://evilbridgit.cc/")!))
        XCTAssertFalse(AppletLists.staysInPortal(URL(string: "https://bridgit.cc.evil.example/")!))
        XCTAssertFalse(AppletLists.staysInPortal(URL(string: "mailto:a@b.c")!))
    }

    func testCardsFromSamples() {
        let json = """
        {"samples":[{"id":"n","kind":"notice","title":"Notice","summary":"s","request":{}},
          {"id":"a","kind":"html","title":"HTML card: demo agenda","summary":"The agenda.","request":{"html":"<p>hi</p>"}},
          {"id":"b","kind":"html","title":"HTML card: empty","summary":"","request":{"html":"  "}}]}
        """
        let cards = AppletLists.cards(json: Data(json.utf8))
        XCTAssertEqual(cards.map(\.id), ["a"])
        XCTAssertEqual(cards.first?.title, "Demo agenda")
        XCTAssertEqual(cards.first?.html, "<p>hi</p>")
    }

    func testBundledLibraryLoads() {
        XCTAssertGreaterThanOrEqual(AppletLists.bundledCards.count, 5, "samples.json should be copied into the app bundle")
    }

    func testSignInScriptQuotesSession() {
        let s = AppletLists.signInScript(session: "bws_a'b\"c</script>", email: "a@b.c")
        XCTAssertTrue(s.contains("\"bws_a'b\\\"c<\\/script>\"") || s.contains("\"bws_a'b\\\"c</script>\""))
        XCTAssertTrue(s.contains("localStorage.setItem('bridgit-watch-session',s)"))
        XCTAssertTrue(AppletLists.signInScript(session: nil, email: nil).contains("var s=null"))
    }

    func testRespondParsing() {
        XCTAssertNotNil(CardWebView.parseRespond("{\"ok\":true}"))
        XCTAssertNotNil(CardWebView.parseRespond("\"yes\""))
        XCTAssertNil(CardWebView.parseRespond("{nope"))
        XCTAssertNil(CardWebView.parseRespond(String(repeating: "1", count: 16 * 1024 + 1)))
        XCTAssertTrue(CardWebView.prepare("<meta HTTP-EQUIV=refresh>").contains("data-blocked-equiv=refresh"))
    }
}
