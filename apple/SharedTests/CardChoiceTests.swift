import CryptoKit
import BridgitAppletRuntime
import WebKit
import XCTest
#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

/// A shared confirmation heading can conceal a wrong button ID. Read the message
/// posted by the bundled card before allowing its normal acknowledgement.
@MainActor
final class CardChoiceTests: XCTestCase {
    private final class Probe: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let loaded = XCTestExpectation(description: "Card loaded")
        let posted = XCTestExpectation(description: "Card responded")
        var answer: [String: Any]?

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded.fulfill() }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            if let raw = message.body as? String,
               let answer = CardPreviewRules.parseRespond(raw) as? [String: Any] {
                self.answer = answer
            }
            posted.fulfill()
        }
    }

    func testInstalledCardBridgeBlocksDirectNetworkAndLocation() async throws {
        let handled = expectation(description: "Installed card reached its scoped handler")
        let coordinator = InstalledCardCoordinator { request in
            XCTAssertEqual(request["path"]?.text, "/v1/applet/tables/stories/rows")
            handled.fulfill()
            return .object(["count": .number(2)])
        }
        let web = coordinator.makeWeb("<script>window.result=Bridgit.act({path:'/v1/applet/tables/stories/rows'}).then(r=>[r.ok,r.body.count,typeof fetch,typeof navigator.geolocation].join(','))</script>")
        defer { coordinator.close(web) }
        await fulfillment(of: [handled], timeout: 15)
        let value = try await web.callAsyncJavaScript("return await window.result", arguments: [:], in: nil, contentWorld: .page) as? String
        XCTAssertEqual(value, "true,2,undefined,undefined")
    }

    func testLibraryBundleMatchesTheReviewed3347Resource() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "samples", withExtension: "json"))
        let digest = SHA256.hash(data: try Data(contentsOf: url))
        XCTAssertEqual(digest.map { String(format: "%02x", $0) }.joined(),
                       "1397b2f5014598de337e4a43ad201017c73deec00737d98b3c200d0cc1ba796e")
    }

    func testEveryChoicePostsItsOwnIDBeforeShowingTheSharedAcknowledgement() async throws {
        for (cardID, choices, heading) in [
            ("html-agenda", ["start", "later"], "Here we go"),
            ("html-connection-test", ["received", "skip"], "Reply sent"),
            ("html-step", ["go", "skip", "end"], "Watch the light"),
            ("html-wrapup", ["more", "setup", "done"], "Thanks!")
        ] {
            for (index, choice) in choices.enumerated() {
                let (web, answer) = try await submit(cardID, script: "document.querySelectorAll('#actions button')[\(index)].click()")
                XCTAssertEqual(answer["button"] as? String, choice, cardID)
                _ = try await web.evaluateJavaScript(CardPreviewRules.confirmation(answer: ["button": choice], title: "Answer received", message: ""))
                let title = try await web.evaluateJavaScript("document.querySelector('#title').textContent") as? String
                let locked = try await web.evaluateJavaScript("Array.from(document.querySelectorAll('#actions button')).every(b => b.disabled)") as? Bool
                XCTAssertEqual(title, heading)
                XCTAssertEqual(locked, true)
            }
        }
    }

    private func submit(_ cardID: String, script: String) async throws -> (WKWebView, [String: Any]) {
        let card = try XCTUnwrap(AppletLists.bundledCards.first { $0.id == cardID })
        let probe = Probe()
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.add(probe, name: "bridgit")
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = probe
        web.loadHTMLString(CardPreviewRules.prepare(card.html), baseURL: nil)
        await fulfillment(of: [probe.loaded], timeout: 10)
        _ = try await web.evaluateJavaScript(script + "; true")
        await fulfillment(of: [probe.posted], timeout: 5)
        return (web, try XCTUnwrap(probe.answer))
    }

    func testPhotoCorrectionIsOptionalAndTrimmed() async throws {
        for (index, text, expected) in [(0, "", [:]), (1, " blue cup ", ["actually": "blue cup"])] {
            let script = """
                document.getElementById('field').value = '\(text)';
                document.querySelectorAll('#actions button')[\(index)].click()
                """
            let (_, answer) = try await submit("html-photo-check", script: script)
            XCTAssertEqual(answer["button"] as? String, index == 0 ? "yes" : "no")
            XCTAssertEqual(answer["values"] as? [String: String], expected)
            XCTAssertEqual(answer["step"] as? Int, 2)
        }
    }

    func testGameReadyAndStopIdentifyTheRound() async throws {
        for (selector, choice) in [("go", "ready"), ("stop", "stop")] {
            let (_, answer) = try await submit("html-rps", script: "document.getElementById('\(selector)').click()")
            XCTAssertEqual(answer["button"] as? String, choice)
            XCTAssertEqual(answer["round"] as? Int, 2)
            XCTAssertEqual(answer["step"] as? Int, 6)
        }
    }

    func testChecklistPreservesNamesAndNumericSliderBounds() async throws {
        let items = ["Passport", "Phone charger", "Sunglasses", "Rain jacket", "Train tickets"]
        for (count, level) in [(0, 1), (2, 5), (5, 10)] {
            let script = """
                [...document.querySelectorAll('input[type=checkbox]')].slice(0, \(count)).forEach(box => box.click());
                document.getElementById('slider').value = \(level);
                document.getElementById('send').click()
                """
            let (_, answer) = try await submit("html-checklist", script: script)
            XCTAssertEqual(answer["button"] as? String, "send")
            XCTAssertEqual(answer["checked"] as? [String], Array(items.prefix(count)))
            XCTAssertEqual(answer["unchecked"] as? [String], Array(items.dropFirst(count)))
            XCTAssertEqual(answer["ready"] as? Int, level)
        }
    }
}

@MainActor final class PortableCardCaptureTests: XCTestCase {
#if os(iOS)
    func testCanceledCaptureIgnoresLateCallbacks() async {
        let gate = PhotoCaptureGate()
        let first = Task { await withCheckedContinuation { gate.begin(id: 1, continuation: $0) } }
        for _ in 0..<100 where gate.pendingID == nil { await Task.yield() }
        XCTAssertEqual(gate.pendingID, 1)
        gate.cancel()
        let firstResult = await first.value
        XCTAssertNil(firstResult)

        let second = Task { await withCheckedContinuation { gate.begin(id: 2, continuation: $0) } }
        for _ in 0..<100 where gate.pendingID == nil { await Task.yield() }
        XCTAssertEqual(gate.pendingID, 2)
        gate.finish(id: 1, image: nil)
        XCTAssertEqual(gate.pendingID, 2)
        gate.finish(id: 2, image: nil)
        let secondResult = await second.value
        XCTAssertNil(secondResult)
        XCTAssertNil(gate.pendingID)
    }
#endif

    func testPhotoCountdownTicksAndCancellation() async throws {
        var ticks = [Int](), sleeps = 0
        try await PortablePhotoCountdown.run(5, update: { ticks.append($0) }, tick: { sleeps += 1 })
        XCTAssertEqual(ticks, [5,4,3,2,1,0]); XCTAssertEqual(sleeps,5)
        var cancelled = [Int]()
        do {
            try await PortablePhotoCountdown.run(3, update: { cancelled.append($0) }, tick: { throw CancellationError() })
            XCTFail("Canceled countdown completed")
        } catch is CancellationError {}
        XCTAssertEqual(cancelled,[3])
        let started = Date()
        try await PortablePhotoCountdown.run(1, update: { _ in })
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(started),0.9)
    }

    func testPhotoRequestPassesFiveSecondCountdownAndRejectsInvalidValues() async throws {
        let definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "a-sorta-fairytale-about-me" }).definition()
        let package = try BridgitAppletRuntime.AppletPackage(data: JSONSerialization.data(withJSONObject: definition))
        let session = PortableCardCaptureSession(); session.activate()
        let png = try XCTUnwrap(Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQqt0DAAI4AWQxmI8QAAAAAElFTkSuQmCC"))
        func request(_ delay: Double) -> BridgitAppletRuntime.JSONValue {
            .object(["path":.string("/v1/camera/snapshot"),"method":.string("POST"),"body":.object(["camera":.string("front"),"countdown_seconds":.number(delay),"store":.array([.string("memory")]),"return_image":.bool(false)])])
        }
        let result = try await session.action(request(5),authorize:{package},table:{_ in .null},reviewPhoto:{ lens, delay, _ in
            XCTAssertEqual(lens,"front"); XCTAssertEqual(delay,5); return .init(jpeg:png,camera:"front")
        })
        XCTAssertNotNil(result["capture"]?["capture_id"]?.text)
        for invalid in [-1.0, 10.5, 11.0] {
            do { _ = try await session.action(request(invalid),authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in XCTFail("Invalid timer opened camera"); throw CancellationError()}); XCTFail("Invalid timer accepted") } catch is BridgitAppletRuntime.AppletRuntimeError {}
        }
        session.close()
    }

    func testReviewedVideoIsScopedAndCleansUp() async throws {
        let definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "a-sorta-fairytale-about-me" }).definition()
        let package = try BridgitAppletRuntime.AppletPackage(data: JSONSerialization.data(withJSONObject: definition))
        let png = try XCTUnwrap(Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQqt0DAAI4AWQxmI8QAAAAAElFTkSuQmCC"))
        let jpeg = try LocalAppletModel.jpeg(png)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mov")
        try Data("fixture only".utf8).write(to:path)
        defer { try? FileManager.default.removeItem(at: path) }
        let session = PortableCardCaptureSession(); session.activate()
        let request: BridgitAppletRuntime.JSONValue = .object(["path":.string("/v1/camera/video"),"method":.string("POST"),"body":.object(["camera":.string("rear"),"duration_seconds":.number(20),"audio":.bool(false),"resolution":.string("720p"),"extract_frames":.number(2)])])
        let unsafe: BridgitAppletRuntime.JSONValue = .object(["path":.string("/v1/camera/video"),"method":.string("POST"),"body":.object(["camera":.string("rear"),"duration_seconds":.number(20),"audio":.bool(true),"resolution":.string("720p"),"extract_frames":.number(2)])])
        do { _ = try await session.action(unsafe,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewVideo:{_,_,_,_,_ in XCTFail("Audio request opened camera"); throw CancellationError()}); XCTFail("Audio request accepted") } catch is BridgitAppletRuntime.AppletRuntimeError {}
        do { _ = try await session.action(request,authorize:{ throw BridgitAppletRuntime.AppletRuntimeError.revoked },table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewVideo:{_,_,_,_,_ in XCTFail("Revoked camera opened"); throw CancellationError()}); XCTFail("Revocation ignored") } catch {}
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewVideo:{_,_,_,_,_ in throw CancellationError()}); XCTFail("Canceled video succeeded") } catch is CancellationError {}
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewVideo:{_,_,_,_,_ in throw BridgitAppletRuntime.AppletRuntimeError.unavailable("video recording")}); XCTFail("Unavailable camera succeeded") } catch is BridgitAppletRuntime.AppletRuntimeError {}
        let result = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in XCTFail("Unexpected photo"); throw CancellationError()},reviewVideo:{ lens, seconds, countdown, count, _ in
            XCTAssertEqual(lens,"rear"); XCTAssertEqual(seconds,20); XCTAssertEqual(countdown,0); XCTAssertEqual(count,2)
            return .init(movie:PortableCardMovie(url:path),frames:[jpeg,jpeg],camera:"rear")
        })
        let video = try XCTUnwrap(result["video_capture_id"]?.text)
        guard case .array(let frames)? = result["frames"] else { return XCTFail("Missing frames") }
        XCTAssertEqual(frames.count,2); XCTAssertTrue(FileManager.default.fileExists(atPath:path.path))
        let frame = try XCTUnwrap(frames[0]["capture_id"]?.text)
        let preview = try await session.action(.object(["path":.string("/v1/captures/"+frame)]),authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()})
        XCTAssertNotNil(preview["data_base64"]?.text)
        let reviewed = try await session.action(.object(["path":.string("/v1/camera/video/review"),"method":.string("POST"),"body":.object(["capture_id":.string(video)])]),authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewExistingVideo:{ _ in true })
        XCTAssertEqual(reviewed["deleted"],.bool(true)); XCTAssertFalse(FileManager.default.fileExists(atPath:path.path))
        session.close()
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()},reviewVideo:{_,_,_,_,_ in XCTFail("Closed camera opened"); throw CancellationError()}); XCTFail("Closed session accepted video") } catch is CancellationError {}
    }

    func testReviewedPhotoIsScopedRevokedAndDeniedSafely() async throws {
        let definition = try XCTUnwrap(HomeBundledDefinition.all.first { $0.id == "a-sorta-fairytale-about-me" }).definition()
        let package = try BridgitAppletRuntime.AppletPackage(data: JSONSerialization.data(withJSONObject: definition))
        let session = PortableCardCaptureSession(); session.activate()
        let request: BridgitAppletRuntime.JSONValue = .object([
            "path":.string("/v1/camera/snapshot"),"method":.string("POST"),
            "body":.object(["camera":.string("front"),"viewfinder":.bool(true),"store":.array([.string("memory")]),"return_image":.bool(false),"max_edge_px":.number(960),"quality":.number(80)])
        ])
        let png = try XCTUnwrap(Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQqt0DAAI4AWQxmI8QAAAAAElFTkSuQmCC"))
        var reviewed = 0
        let response = try await session.action(request, authorize: { package }, table: { _ in XCTFail("Unexpected table"); return .null }, reviewPhoto: { lens, countdown, _ in
            XCTAssertEqual(countdown, 0)
            reviewed += 1; XCTAssertEqual(lens,"front"); return .init(jpeg:png,camera:"front")
        })
        XCTAssertEqual(reviewed,1)
        let id = try XCTUnwrap(response["capture"]?["capture_id"]?.text)
        let read: BridgitAppletRuntime.JSONValue = .object(["path":.string("/v1/captures/"+id)])
        let image = try await session.action(read,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in XCTFail("Unexpected camera"); throw CancellationError()})
        XCTAssertNotNil(Data(base64Encoded:try XCTUnwrap(image["data_base64"]?.text)))
        let other = PortableCardCaptureSession(); other.activate()
        do { _ = try await other.action(read,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()}); XCTFail("Capture escaped its card") } catch {}
        let deletion: BridgitAppletRuntime.JSONValue = .object(["path":.string("/v1/captures/"+id),"method":.string("DELETE")])
        let deleted = try await session.action(deletion,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()})
        XCTAssertEqual(deleted["deleted"],.bool(true))
        do { _ = try await session.action(read,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()}); XCTFail("Deleted image remained readable") } catch {}
        do { _ = try await session.action(request,authorize:{ throw BridgitAppletRuntime.AppletRuntimeError.revoked },table:{_ in .null},reviewPhoto:{_,_,_ in XCTFail("Camera opened after revocation"); throw CancellationError()}); XCTFail("Revocation ignored") } catch {}
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()}); XCTFail("Canceled photo succeeded") } catch is CancellationError {}
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw BridgitAppletRuntime.AppletRuntimeError.unavailable("camera")}); XCTFail("Unavailable camera succeeded") } catch is BridgitAppletRuntime.AppletRuntimeError {}
        var withoutCamera = definition
        var permissions = try XCTUnwrap(withoutCamera["permissions"] as? [String:Any])
        let grants = try XCTUnwrap(permissions["required"] as? [String])
        permissions["required"] = grants.filter { $0 != "camera" }; withoutCamera["permissions"] = permissions
        let deniedPackage = try BridgitAppletRuntime.AppletPackage(data: JSONSerialization.data(withJSONObject: withoutCamera))
        do { _ = try await session.action(request,authorize:{deniedPackage},table:{_ in .null},reviewPhoto:{_,_,_ in XCTFail("Camera opened without grant"); throw CancellationError()}); XCTFail("Missing grant accepted") } catch {}
        var authorizations = 0
        do {
            _ = try await session.action(request, authorize: { authorizations += 1; return authorizations == 1 ? package : deniedPackage }, table: { _ in .null }, reviewPhoto: { _,_,_ in .init(jpeg:png,camera:"front") })
            XCTFail("Capture was retained after its grant was removed")
        } catch is BridgitAppletRuntime.AppletRuntimeError {}
        XCTAssertEqual(authorizations,2)
        let pinned: BridgitAppletRuntime.JSONValue = .object(["path":.string("/v1/camera/snapshot"),"method":.string("POST"),"body":.object(["camera":.string("front"),"store":.array([.string("memory")]),"return_image":.bool(false),"max_edge_px":.number(960),"quality":.number(80)])])
        let pinnedResult = try await session.action(pinned,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in .init(jpeg:png,camera:"front")})
        XCTAssertNotNil(pinnedResult["capture"]?["capture_id"]?.text)
        do { _ = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in session.close(); return .init(jpeg:png,camera:"front")}); XCTFail("Closed review succeeded") } catch is CancellationError {}
        session.activate()
        let again = try await session.action(request,authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in .init(jpeg:png,camera:"front")})
        let second = try XCTUnwrap(again["capture"]?["capture_id"]?.text)
        session.close(); session.activate()
        do { _ = try await session.action(.object(["path":.string("/v1/captures/"+second)]),authorize:{package},table:{_ in .null},reviewPhoto:{_,_,_ in throw CancellationError()}); XCTFail("Capture survived closing") } catch {}
    }
}
