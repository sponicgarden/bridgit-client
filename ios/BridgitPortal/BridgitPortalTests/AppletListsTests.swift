import XCTest
import WebKit
import AVFoundation
import CoreLocation
import BridgitAppletRuntime
@testable import Bridgit_Portal

/// Mirrors the APK's AppletLists unit tests: portal list, which pages stay in the portal, the card library.
@MainActor
final class AppletListsTests: XCTestCase {
    func testPortalsMostSpecificFirst() {
        XCTAssertEqual(AppletLists.portals(dogPortal: false, activityPortal: false, device: "bgi_x"), [])
        let both = AppletLists.portals(dogPortal: true, activityPortal: true, device: "bgi_x")
        XCTAssertEqual(both.map(\.name), ["Pet monitor", "Agent activity", "All my applets"])
        XCTAssertEqual(both.map(\.url.absoluteString), ["https://bridgit.cc/myapps/?device=bgi_x#dog",
                                                         "https://bridgit.cc/myapps/?device=bgi_x#activity",
                                                         "https://bridgit.cc/myapps/?device=bgi_x"])
        XCTAssertEqual(AppletLists.portals(dogPortal: false, activityPortal: true, device: nil).map(\.url.absoluteString),
                       ["https://bridgit.cc/myapps/#activity", "https://bridgit.cc/myapps/"])
    }

    func testPortalsForDevice() {
        let pairing = Pairing(base: URL(string: "https://r.example/d/dev_ABCDEFGH12")!, deviceId: "dev_ABCDEFGH12",
                              token: "rly_0123456789abcdef0123", name: "Kitchen", install: "bgi_k", portal: true)
        let relayOnly = PortalDevice(id: "relay:dev", name: "Kitchen", cloud: nil, pairing: pairing)
        XCTAssertEqual(AppletLists.portals(device: relayOnly, accountApplets: [], phoneApplets: []), [])
        let dogRow = AppletRow(id: "dog", name: "Pet monitor", kind: "dog", running: true, lastAt: nil)
        XCTAssertEqual(AppletLists.portals(device: relayOnly, accountApplets: [], phoneApplets: [dogRow]).map(\.id), ["dog", "overview"])

        let linked = PortalDevice(id: "cloud:bgi_sample_kitchen", name: "Kitchen phone", cloud: SampleData.phones[0], pairing: nil)
        XCTAssertEqual(AppletLists.portals(device: linked, accountApplets: [], phoneApplets: []).map(\.id), ["activity", "overview"])
        XCTAssertEqual(AppletLists.portals(device: linked, accountApplets: SampleData.applets, phoneApplets: []).map(\.id),
                       ["dog", "activity", "overview"])
        // Another phone's Pet monitor doesn't count.
        let other = PortalDevice(id: "cloud:bgi_sample_garage", name: "Garage", cloud: SampleData.phones[1], pairing: nil)
        XCTAssertEqual(AppletLists.portals(device: other, accountApplets: SampleData.applets, phoneApplets: []).map(\.id), ["activity", "overview"])
    }

    func testSettingsLinks() {
        let q = "device=bgi_aaaaaaaaaaaaaaaaaaaaaaaa&applet=dog"
        let target = "https://bridgit.cc/myapps/?device=bgi_aaaaaaaaaaaaaaaaaaaaaaaa&screen=settings#dog"
        XCTAssertEqual(AppletLists.settingsTarget(URL(string: "https://bridgit.cc/applet-settings/?" + q)!)?.absoluteString, target)
        XCTAssertEqual(AppletLists.settingsTarget(URL(string: "https://www.bridgit.cc/applet-settings/?" + q)!)?.absoluteString, target)
        XCTAssertEqual(AppletLists.settingsTarget(URL(string: "bridgitportal://settings?" + q)!)?.absoluteString, target)
        for raw in ["https://evil.example/applet-settings/?" + q, "http://bridgit.cc/applet-settings/?" + q, "bridgitportal://settings?" + q + "&applet=dog", "bridgitportal://settings?device=other&applet=dog"] {
            XCTAssertNil(AppletLists.settingsTarget(URL(string: raw)!))
        }
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

    func testCardLocationSharesPrecisionAndAvailableMeasurements() throws {
        let when = Date(timeIntervalSince1970:100)
        let fix = CLLocation(coordinate:.init(latitude:52.2297,longitude:21.0122),altitude:-12,
                             horizontalAccuracy:50,verticalAccuracy:2,timestamp:when)
        let reading = try CardLocation.response(fix,approximate:true,now:when.addingTimeInterval(8))
        XCTAssertEqual(reading["latitude"],.number(52.2297))
        XCTAssertEqual(reading["altitude_m"],.number(-12))
        XCTAssertEqual(reading["age_s"],.number(8))
        XCTAssertEqual(reading["precision"],.string("approximate"))
        XCTAssertEqual(reading["speed_mps"],.null)
        XCTAssertEqual(reading["bearing_deg"],.null)
    }

    #if targetEnvironment(simulator)
    func testIsolatedSimulatorOneShotLocation() async throws {
        let scenario = ProcessInfo.processInfo.environment["BRIDGIT_TEST_LOCATION"]
        try XCTSkipUnless(["allowed","denied"].contains(scenario),"Needs a disposable simulator with a configured location grant and coordinate")
        let reader = CardLocation()
        if scenario == "denied" {
            do { _ = try await reader.read(fresh:true,balanced:false); XCTFail("Denied location was returned") }
            catch { XCTAssertTrue(error.localizedDescription.contains("Location access is off")) }
        } else {
            let reading = try await reader.read(fresh:true,balanced:false)
            XCTAssertEqual(reading["latitude"],.number(52.2297))
            XCTAssertEqual(reading["longitude"],.number(21.0122))
        }
    }
    #endif

    func testCardIllustrationDisplaysJPEGAndReturnsMetadataToCard() throws {
        let photo = UIGraphicsImageRenderer(size:CGSize(width:120,height:80)).image { context in
            UIColor.blue.setFill(); context.fill(CGRect(x:0,y:0,width:120,height:80))
        }
        func response(_ bytes: Data) -> JSONValue {
            .object(["task_key":.string("make_fairytale_image"),"model":.string("image"),"quota":.object(["used":.number(1)]),
                     "data":.array([.object(["b64_json":.string(bytes.base64EncodedString())])])])
        }
        let artwork = try CardIllustration(response:response(XCTUnwrap(photo.jpegData(compressionQuality:0.8))),title:"Blue room")
        XCTAssertEqual(artwork.title,"Blue room")
        XCTAssertGreaterThan(artwork.image.size.width,0)
        XCTAssertEqual(artwork.receipt["interaction_id"],.string(artwork.id.uuidString))
        XCTAssertEqual(artwork.receipt["display"]?["full_screen"],.bool(true))
        XCTAssertNil(artwork.receipt["data"])
        XCTAssertThrowsError(try CardIllustration(response:response(XCTUnwrap(photo.pngData())),title:"Wrong format"))
    }

    func testNarrationPlayerQueuesAndStopsOnlyItsOwnReading() throws {
        // One second of silent mono PCM, long enough to inspect the active queue.
        var wav = Data("RIFF".utf8)
        func word<T:FixedWidthInteger>(_ value:T) { withUnsafeBytes(of:value.littleEndian) { wav.append(contentsOf:$0) } }
        word(UInt32(32036)); wav.append(Data("WAVEfmt ".utf8)); word(UInt32(16)); word(UInt16(1)); word(UInt16(1))
        word(UInt32(16000)); word(UInt32(32000)); word(UInt16(2)); word(UInt16(16)); wav.append(Data("data".utf8))
        word(UInt32(32000)); wav.append(Data(repeating:0,count:32000))
        let speaker = CardSpeaker()
        try speaker.play(wav,replace:true)
        try speaker.play(wav,replace:false)
        XCTAssertEqual(speaker.status["playing"],.bool(true))
        XCTAssertEqual(speaker.status["queued"],.array([.bool(true)]))
        let other = CardSpeaker()
        other.stop()
        XCTAssertEqual(speaker.status["playing"],.bool(true))
        speaker.stop()
        XCTAssertEqual(speaker.status["playing"],.bool(false))
        XCTAssertEqual(speaker.status["queued"],.array([]))
    }

    func testCardAudioRequiresExactPackageReferenceAndTrustedAssetPath() throws {
        let url = "https://bridgit.cc/assets/audio/test-intro.mp3"
        let html = "play({url:'" + url + "'})"
        XCTAssertEqual(try CardAudioAsset.url(url,html:html).absoluteString,url)
        XCTAssertThrowsError(try CardAudioAsset.url(url,html:""))
        for bad in [url + "?x=1",url + "#secret",url.replacingOccurrences(of:"bridgit.cc",with:"example.org"),
                    url.replacingOccurrences(of:"test-intro",with:"../outside")] {
            XCTAssertThrowsError(try CardAudioAsset.url(bad,html:"'" + bad + "'"))
        }
    }

    func testRecordedMovieFramesAndReviewAreScopedToOpenCard() async throws {
        var movie: CardMovieFile? = try CardMovieFile()
        let url = try XCTUnwrap(movie?.url)
        let writer = try AVAssetWriter(outputURL:url,fileType:.mov)
        let input = AVAssetWriterInput(mediaType:.video,outputSettings:[
            AVVideoCodecKey:AVVideoCodecType.h264,AVVideoWidthKey:64,AVVideoHeightKey:64])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[
            kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String:64,kCVPixelBufferHeightKey as String:64])
        writer.add(input)
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime:.zero)
        for index in 0..<12 {
            var pixel: CVPixelBuffer?
            try XCTAssertEqual(CVPixelBufferPoolCreatePixelBuffer(nil,XCTUnwrap(adaptor.pixelBufferPool),&pixel),kCVReturnSuccess)
            let buffer = try XCTUnwrap(pixel)
            CVPixelBufferLockBaseAddress(buffer,[])
            memset(CVPixelBufferGetBaseAddress(buffer),Int32(index*20),CVPixelBufferGetBytesPerRow(buffer)*64)
            CVPixelBufferUnlockBaseAddress(buffer,[])
            for _ in 0..<100 where !input.isReadyForMoreMediaData { try await Task.sleep(for:.milliseconds(10)) }
            XCTAssertTrue(adaptor.append(buffer,withPresentationTime:CMTime(value:Int64(index),timescale:12)))
        }
        writer.endSession(atSourceTime:CMTime(value:1,timescale:1))
        input.markAsFinished()
        await writer.finishWriting()
        XCTAssertEqual(writer.status,.completed)
        let frames = try await XCTUnwrap(movie).frames(count:6)
        XCTAssertEqual(frames.count,6)
        XCTAssertGreaterThan(Set(frames).count,1)
        let store = CardCaptureMemory()
        let result = try store.storeVideo(XCTUnwrap(movie),camera:"rear",frames:frames)
        let id = try XCTUnwrap(result["video_capture_id"]?.text)
        XCTAssertEqual(try store.movie(id).url,url)
        XCTAssertThrowsError(try CardCaptureMemory().movie(id))
        guard case .array(let refs)? = result["frames"] else { return XCTFail("Missing movie frames") }
        let frame = try XCTUnwrap(refs.first?["capture_id"]?.text)
        XCTAssertNotNil(try store.readOrDelete(.object([:]),id:frame)["data_base64"])
        movie = nil
        _ = try store.readOrDelete(.object(["method":.string("DELETE")]),id:id)
        XCTAssertThrowsError(try store.movie(id))
        XCTAssertFalse(FileManager.default.fileExists(atPath:url.path))
        store.clear()
        XCTAssertThrowsError(try store.readOrDelete(.object([:]),id:frame))
    }

    func testCardPortraitPreviewDeletionAndSessionIsolation() throws {
        let image = UIGraphicsImageRenderer(size:CGSize(width:320,height:200)).image { _ in UIColor.blue.setFill(); UIRectFill(CGRect(x:0,y:0,width:320,height:200)) }
        let photo = LocalAppletLibrary.ReviewedPhoto(jpeg:try XCTUnwrap(image.jpegData(compressionQuality:0.8)),camera:"front")
        let store = CardCaptureMemory()
        let result = try store.store(photo)
        let id = try XCTUnwrap(result["capture"]?["capture_id"]?.text)
        XCTAssertEqual(result["camera"],.string("front"))
        let preview = JSONValue.object(["query":.object(["max_edge_px":.number(240)])])
        let encoded = try XCTUnwrap(store.readOrDelete(preview,id:id)["data_base64"]?.text)
        let thumb = try XCTUnwrap(UIImage(data:try XCTUnwrap(Data(base64Encoded:encoded))))
        XCTAssertEqual(thumb.size.width,240)
        XCTAssertThrowsError(try CardCaptureMemory().readOrDelete(preview,id:id))
        _ = try store.readOrDelete(.object(["method":.string("DELETE")]),id:id)
        XCTAssertThrowsError(try store.readOrDelete(preview,id:id))
        let other = try XCTUnwrap(store.store(photo)["capture"]?["capture_id"]?.text)
        store.clear()
        XCTAssertThrowsError(try store.readOrDelete(preview,id:other))
    }

    func testInstalledWebCardReadsThroughNativeBridgeWithNetworkDisabled() async throws {
        let handled = expectation(description:"Main-frame card called its native table route")
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let coordinator = CardWebView.Coordinator(action:{ request in
            XCTAssertEqual(request["path"],.string("/v1/applet/tables/stories/rows"))
            handled.fulfill()
            return .object(["count":.number(2)])
        })
        config.userContentController.addScriptMessageHandler(coordinator,contentWorld:.page,name:"appletAction")
        let view = WKWebView(frame:.zero,configuration:config)
        coordinator.view = view
        view.navigationDelegate = coordinator
        defer { config.userContentController.removeScriptMessageHandler(forName:"appletAction",contentWorld:.page) }
        let html = """
        <script>window.result = Bridgit.act({path:'/v1/applet/tables/stories/rows'}).then(r => [r.ok,r.body.count,typeof fetch,typeof navigator.geolocation].join(','));</script>
        """
        view.loadHTMLString(CardWebView.prepare(html,installed:true),baseURL:nil)
        await fulfillment(of:[handled],timeout:15)
        let result: String? = try await withCheckedThrowingContinuation { continuation in
            view.callAsyncJavaScript("return await window.result",arguments:[:],in:nil,in:.page) { outcome in
                continuation.resume(with:outcome.map { $0 as? String })
            }
        }
        XCTAssertEqual(result,"true,2,undefined,undefined")
    }

    func testRespondParsing() {
        XCTAssertNotNil(CardWebView.parseRespond("{\"ok\":true}"))
        XCTAssertNotNil(CardWebView.parseRespond("\"yes\""))
        XCTAssertNil(CardWebView.parseRespond("{nope"))
        XCTAssertNil(CardWebView.parseRespond(String(repeating: "1", count: 16 * 1024 + 1)))
        XCTAssertTrue(CardWebView.prepare("<meta HTTP-EQUIV=refresh>").contains("data-blocked-equiv=refresh"))
    }
}
