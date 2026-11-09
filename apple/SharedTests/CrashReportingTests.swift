import BridgitCore
import Foundation
import Sentry
import XCTest
import zlib

#if os(macOS)
@testable import Bridgit_macOS
#else
@testable import Bridgit_iOS
#endif

private enum CrashUploadFixtureState {
    static let lock = NSLock()
    static var paused = true
    static var accepted: [String] = []
    static var hold = false
    static var startedHeld = 0
    static var cancelledHeld = 0

    static func reset() {
        lock.withLock {
            paused = true; accepted = []
            hold = false; startedHeld = 0; cancelledHeld = 0
        }
    }
    static func pause(_ value: Bool) { lock.withLock { paused = value } }
    static func hold(_ value: Bool) { lock.withLock { hold = value } }
    static let access = OutboundAccess(paused: { lock.withLock { paused } })
    static var observations: (accepted: [String], started: Int, cancelled: Int) {
        lock.withLock { (accepted, startedHeld, cancelledHeld) }
    }
}

/// Intercepts every forwarded request. An unexpected route or a missing
/// compressed envelope fails here, before any network connection can start.
private final class CrashReceiverFixtureProtocol: URLProtocol {
    private var held = false

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, url.scheme == "https", url.host == "offline-sentry.invalid",
              url.path.split(separator: "/") == ["api", "1", "envelope"], request.httpMethod == "POST",
              request.value(forHTTPHeaderField: CrashForwardingProtocol.epochHeader) == nil,
              request.value(forHTTPHeaderField: "Content-Encoding")?.lowercased() == "gzip",
              request.value(forHTTPHeaderField: "Content-Type") == "application/x-sentry-envelope",
              let message = envelopeMessage() else {
            XCTFail("Unexpected request in isolated crash transport.")
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        let held = CrashUploadFixtureState.lock.withLock {
            if CrashUploadFixtureState.hold {
                self.held = true
                CrashUploadFixtureState.startedHeld += 1
                return true
            }
            CrashUploadFixtureState.accepted.append(message)
            return false
        }
        guard !held else { return }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private func envelopeMessage() -> String? {
        guard let compressed = compressedBody(), let payload = inflateFixture(compressed) else { return nil }
        let lines = payload.split(separator: 0x0a, maxSplits: 2, omittingEmptySubsequences: false)
        guard lines.count == 3,
              let header = try? JSONSerialization.jsonObject(with: Data(lines[0])) as? [String: Any],
              header["event_id"] is String,
              let item = try? JSONSerialization.jsonObject(with: Data(lines[1])) as? [String: Any],
              item["type"] as? String == "event",
              let length = item["length"] as? Int, length > 0, length <= lines[2].count,
              lines[2].dropFirst(length).allSatisfy({ $0 == 0x0a }),
              let event = try? JSONSerialization.jsonObject(with: Data(lines[2].prefix(length))) as? [String: Any],
              let message = event["message"] as? [String: Any] else { return nil }
        return message["formatted"] as? String
    }

    private func compressedBody() -> Data? {
        let maximum = 64 * 1024
        if let data = request.httpBody { return data.count <= maximum ? data : nil }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var bytes = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = stream.read(&bytes, maxLength: bytes.count)
            if count == 0 { return data }
            guard count > 0, data.count + count <= maximum else { return nil }
            data.append(contentsOf: bytes.prefix(count))
        }
    }

    private func inflateFixture(_ compressed: Data) -> Data? {
        var stream = z_stream()
        guard inflateInit2_(&stream, 16 + MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { inflateEnd(&stream) }
        var output = Data(count: 256 * 1024)
        let result = compressed.withUnsafeBytes { source in
            output.withUnsafeMutableBytes { destination in
                stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: UInt8.self).baseAddress)
                stream.avail_in = UInt32(source.count)
                stream.next_out = destination.bindMemory(to: UInt8.self).baseAddress
                stream.avail_out = UInt32(destination.count)
                return inflate(&stream, Z_FINISH)
            }
        }
        guard result == Z_STREAM_END, stream.avail_in == 0 else { return nil }
        return Data(output.prefix(Int(stream.total_out)))
    }

    override func stopLoading() {
        CrashUploadFixtureState.lock.withLock {
            if held { CrashUploadFixtureState.cancelledHeld += 1 }
        }
    }
}

@MainActor private final class CrashSDKFixture {
    private let suite = "cc.bridgit.crash-test." + UUID().uuidString
    private let cache = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    private(set) var sessions: [URLSession] = []
    lazy var consent = makeConsent()
    lazy var runtime = CrashReportRuntime(consent: consent, dsn: "https://fixture@offline-sentry.invalid/1",
        forwardingConfiguration: {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [CrashReceiverFixtureProtocol.self]
            return configuration
        }, configure: { [weak self] options in
            guard let self else { return }
            if let session = options.urlSession { sessions.append(session) }
            XCTAssertFalse(options.sendDefaultPii)
            XCTAssertFalse(options.enableAutoSessionTracking)
            XCTAssertFalse(options.enableAutoBreadcrumbTracking)
            XCTAssertFalse(options.enableNetworkBreadcrumbs)
            XCTAssertFalse(options.enableNetworkTracking)
            XCTAssertFalse(options.enableCaptureFailedRequests)
            XCTAssertFalse(options.enableAutoPerformanceTracing)
            XCTAssertTrue(options.tracePropagationTargets.isEmpty)
            #if os(iOS)
            XCTAssertFalse(options.attachScreenshot)
            XCTAssertFalse(options.attachViewHierarchy)
            XCTAssertFalse(options.enableUserInteractionTracing)
            #endif
            options.cacheDirectoryPath = cache.path
            options.enableCrashHandler = false
            options.enableWatchdogTerminationTracking = false
            options.enableAppHangTracking = false
            options.sendClientReports = false
            options.shutdownTimeInterval = 0
        })

    init() throws {
        let packagedDSN = CrashReports.packagedDSN ?? ""
        try XCTSkipUnless(packagedDSN.isEmpty || packagedDSN == "disabled-for-fixture",
                          "Requires an isolated build with the production crash destination disabled.")
        CrashUploadFixtureState.reset()
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
    }

    func makeConsent() -> CrashUploadConsent {
        let suite = suite
        return CrashUploadConsent(
            read: { UserDefaults(suiteName: suite)?.bool(forKey: "disabled") != true },
            write: { UserDefaults(suiteName: suite)?.set(!$0, forKey: "disabled") },
            outboundAccess: CrashUploadFixtureState.access)
    }

    func cleanup() {
        consent.setEnabled(false)
        runtime.reconcile()
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: cache)
    }

    func cached(_ marker: String) -> Bool {
        let files = FileManager.default.enumerator(at: cache, includingPropertiesForKeys: [.isRegularFileKey])
        while let file = files?.nextObject() as? URL {
            guard (try? file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true,
                  let bytes = try? Data(contentsOf: file) else { continue }
            if bytes.range(of: Data(marker.utf8)) != nil { return true }
        }
        return false
    }

    func waitUntil(_ phase: String, _ predicate: () -> Bool) async throws {
        for _ in 0..<100 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTAssertTrue(predicate(), phase)
    }

    func assertTransportBlocked(_ session: URLSession) async throws {
        let url = URL(string: "https://offline-sentry.invalid/api/1/envelope/")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data("fixture must never be sent".utf8)
        do {
            _ = try await session.data(for: request)
            XCTFail("A retired or unknown epoch reached the receiver.")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .notConnectedToInternet)
        }
    }
}

final class CrashOutboundPauseTests: XCTestCase {
    @MainActor func testOptOutStopsSDKAndCachedDeliveryIndependentlyOfOutboundPause() async throws {
        let fixture = try CrashSDKFixture()
        defer { fixture.cleanup() }
        let consent = fixture.consent
        let marker = "Synthetic consent envelope fixture"

        XCTAssertTrue(consent.enabled)
        fixture.runtime.reconcile()
        let first = try XCTUnwrap(fixture.sessions.first)
        SentrySDK.capture(message: marker)
        try await fixture.waitUntil("Outbound pause cached an SDK envelope") { fixture.cached(marker) }

        consent.setEnabled(false)
        XCTAssertFalse(consent.hasSession)
        XCTAssertFalse(fixture.makeConsent().enabled, "Opt-out is persistent")
        CrashUploadFixtureState.pause(false)
        try await fixture.assertTransportBlocked(first)
        fixture.runtime.reconcile()
        XCTAssertFalse(SentrySDK.isEnabled)
        fixture.runtime.reconcile()
        XCTAssertFalse(SentrySDK.isEnabled, "Startup while opted out leaves Sentry stopped")
        XCTAssertTrue(fixture.cached(marker))
        XCTAssertTrue(CrashUploadFixtureState.observations.accepted.isEmpty)

        consent.setEnabled(true)
        fixture.runtime.reconcile()
        SentrySDK.flush(timeout: 1)
        try await fixture.waitUntil("Explicit opt-in resumed the retained envelope once") {
            CrashUploadFixtureState.observations.accepted == [marker] && !fixture.cached(marker)
        }
        try await fixture.assertTransportBlocked(first)

        CrashUploadFixtureState.hold(true)
        let rapidMarker = "Synthetic rapid consent change"
        SentrySDK.capture(message: rapidMarker)
        try await fixture.waitUntil("The current SDK began a held request") {
            CrashUploadFixtureState.observations.started > 0
        }
        let old = try XCTUnwrap(fixture.sessions.last)
        consent.setEnabled(false)
        consent.setEnabled(true)
        try await fixture.waitUntil("Opt-out cancelled the actual held request") {
            CrashUploadFixtureState.observations.cancelled > 0
        }
        XCTAssertFalse(consent.hasSession)
        XCTAssertEqual(CrashUploadFixtureState.observations.accepted, [marker])
        try await fixture.assertTransportBlocked(old)
        CrashUploadFixtureState.hold(false)
        fixture.runtime.reconcile()
        let new = try XCTUnwrap(fixture.sessions.last)
        XCTAssertEqual(fixture.sessions.count, 3)
        XCTAssertFalse(old === new)
        try await fixture.assertTransportBlocked(old)
        SentrySDK.flush(timeout: 1)
        try await fixture.waitUntil("The fresh session delivered the retained held request") {
            CrashUploadFixtureState.observations.accepted == [marker, rapidMarker] && !fixture.cached(rapidMarker)
        }
    }

    @MainActor func testSentryCachedEnvelopeStaysOfflineWhilePausedAndResendsAfterResume() async throws {
        let fixture = try CrashSDKFixture()
        defer { fixture.cleanup() }
        CrashUploadFixtureState.pause(false)
        CrashUploadFixtureState.hold(true)
        fixture.runtime.reconcile()
        let session = try XCTUnwrap(fixture.sessions.first)
        let marker = "Synthetic outbound pause fixture"
        SentrySDK.capture(message: marker)
        try await fixture.waitUntil("Sentry began a held request") {
            CrashUploadFixtureState.observations.started > 0
        }
        fixture.consent.setOutboundPaused(true) { CrashUploadFixtureState.pause(true) }
        try await fixture.waitUntil("Outbound pause cancelled the already started upload") {
            CrashUploadFixtureState.observations.cancelled > 0
        }
        XCTAssertTrue(fixture.consent.enabled)
        XCTAssertTrue(fixture.consent.hasSession)
        XCTAssertTrue(fixture.cached(marker))
        try await fixture.assertTransportBlocked(session)
        CrashUploadFixtureState.hold(false)
        CrashUploadFixtureState.pause(false)
        SentrySDK.flush(timeout: 1)
        try await fixture.waitUntil("Outbound resume sent the same cached envelope once") {
            CrashUploadFixtureState.observations.accepted == [marker] && !fixture.cached(marker)
        }
        XCTAssertEqual(fixture.sessions.count, 1)
    }

    @MainActor func testMissingAndUnknownEpochNeverReachNetwork() async throws {
        let fixture = try CrashSDKFixture()
        defer { fixture.cleanup() }
        for marker in [nil, UUID().uuidString] {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [CrashForwardingProtocol.self, CrashReceiverFixtureProtocol.self]
            if let marker { configuration.httpAdditionalHeaders = [CrashForwardingProtocol.epochHeader.lowercased(): marker] }
            try await fixture.assertTransportBlocked(URLSession(configuration: configuration))
        }
        XCTAssertTrue(CrashUploadFixtureState.observations.accepted.isEmpty)
    }
}
