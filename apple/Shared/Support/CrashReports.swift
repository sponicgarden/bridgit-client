import BridgitCore
import Foundation
import Sentry

/// The SDK retains its outer session and can queue tasks after SDK close.
/// Retiring the epoch blocks that session permanently without invalidating it.
final class CrashUploadConsent: @unchecked Sendable {
    private let lock = NSLock()
    private let read: @Sendable () -> Bool
    private let write: @Sendable (Bool) -> Void
    private let outboundAccess: OutboundAccess
    private var epoch: String?
    private var forwarding: URLSession?
    private var tasks: [UUID: URLSessionDataTask] = [:]

    init(read: @escaping @Sendable () -> Bool, write: @escaping @Sendable (Bool) -> Void,
         outboundAccess: OutboundAccess = .unrestricted) {
        self.read = read
        self.write = write
        self.outboundAccess = outboundAccess
    }

    var enabled: Bool { lock.withLock { read() } }
    var hasSession: Bool { lock.withLock { forwarding != nil } }

    func setEnabled(_ enabled: Bool) {
        let retired = lock.withLock {
            write(enabled)
            return enabled ? nil : detach()
        }
        retired?.invalidateAndCancel()
    }

    private func detach() -> URLSession? {
        if let epoch { CrashForwardingProtocol.unregister(epoch) }
        epoch = nil
        tasks.removeAll()
        defer { forwarding = nil }
        return forwarding
    }

    func makeSDKSession(forwardingConfiguration: URLSessionConfiguration) -> URLSession? {
        lock.withLock {
            guard read(), epoch == nil else { return nil }
            let epoch = UUID().uuidString
            let inner = forwardingConfiguration
            inner.protocolClasses = (inner.protocolClasses ?? []).filter { $0 != CrashForwardingProtocol.self }
            forwarding = URLSession(configuration: inner, delegate: CrashUploadRedirectPolicy(), delegateQueue: nil)
            self.epoch = epoch
            CrashForwardingProtocol.register(self, for: epoch)

            let outer = URLSessionConfiguration.ephemeral
            outer.protocolClasses = [CrashForwardingProtocol.self]
            outer.httpAdditionalHeaders = [CrashForwardingProtocol.epochHeader: epoch]
            return URLSession(configuration: outer)
        }
    }

    fileprivate func forward(_ request: URLRequest, epoch: String,
                             completion: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void) -> URLSessionDataTask? {
        lock.withLock {
            guard epoch == self.epoch, read(), !outboundAccess.isPaused, let forwarding else { return nil }
            let id = UUID()
            let task = forwarding.dataTask(with: request) { [weak self] data, response, error in
                self?.lock.withLock { self?.tasks[id] = nil }
                completion(data, response, error)
            }
            tasks[id] = task
            task.resume()
            return task
        }
    }

    func setOutboundPaused(_ paused: Bool, apply: () -> Void) {
        let pending = lock.withLock {
            apply()
            return paused ? Array(tasks.values) : []
        }
        for task in pending { task.cancel() }
    }
}

private final class CrashUploadRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// The epoch is confined to the SDK-facing session. Every request is mediated,
/// and only the forwarding session can reach the destination.
final class CrashForwardingProtocol: URLProtocol, @unchecked Sendable {
    static let epochHeader = "X-Bridgit-Crash-Epoch"
    private static let registryLock = NSLock()
    private static var registry: [String: CrashUploadConsent] = [:]

    fileprivate static func register(_ consent: CrashUploadConsent, for epoch: String) {
        registryLock.withLock { registry[epoch] = consent }
    }
    fileprivate static func unregister(_ epoch: String) {
        registryLock.withLock { registry[epoch] = nil }
    }

    private let taskLock = NSLock()
    private var forwardedTask: URLSessionDataTask?
    private var stopped = false

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let epoch = request.value(forHTTPHeaderField: Self.epochHeader),
              let consent = Self.registryLock.withLock({ Self.registry[epoch] }) else {
            complete(data: nil, response: nil, error: URLError(.notConnectedToInternet))
            return
        }
        var forwarded = request
        forwarded.setValue(nil, forHTTPHeaderField: Self.epochHeader)
        guard let started = consent.forward(forwarded, epoch: epoch, completion: { [weak self] data, response, error in
            self?.complete(data: data, response: response, error: error)
        }) else {
            complete(data: nil, response: nil, error: URLError(.notConnectedToInternet))
            return
        }
        let cancelled = taskLock.withLock {
            guard !stopped else { return true }
            forwardedTask = started
            return false
        }
        if cancelled { started.cancel() }
    }

    private func complete(data: Data?, response: URLResponse?, error: Error?) {
        guard taskLock.withLock({ !stopped }) else { return }
        if let response { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
        if let data, !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
        if let error { client?.urlProtocol(self, didFailWithError: error) }
        else { client?.urlProtocolDidFinishLoading(self) }
    }

    override func stopLoading() {
        let pending = taskLock.withLock {
            stopped = true
            defer { forwardedTask = nil }
            return forwardedTask
        }
        pending?.cancel()
    }
}

@MainActor final class CrashReportRuntime {
    private let consent: CrashUploadConsent
    private let dsn: String?
    private let forwardingConfiguration: () -> URLSessionConfiguration
    private let configure: (Options) -> Void
    private var running = false

    init(consent: CrashUploadConsent, dsn: String?,
         forwardingConfiguration: @escaping () -> URLSessionConfiguration = { .ephemeral },
         configure: @escaping (Options) -> Void = { _ in }) {
        self.consent = consent
        self.dsn = dsn
        self.forwardingConfiguration = forwardingConfiguration
        self.configure = configure
    }

    func reconcile() {
        // A rapid off/on can retire the SDK's epoch before the main actor
        // observes the off state. Close that SDK instance before restarting.
        if running && !consent.hasSession {
            SentrySDK.close()
            running = false
        }
        guard !running, let dsn, consent.enabled else { return }
        guard let session = consent.makeSDKSession(forwardingConfiguration: forwardingConfiguration()) else { return }
        let consent = consent
        let configure = configure
        SentrySDK.start { o in
            o.urlSession = session
            o.dsn = dsn
            #if DEBUG
            o.environment = "debug"
            #else
            o.environment = "production"
            #endif
            o.sendDefaultPii = false
            #if os(iOS)
            o.attachScreenshot = false
            o.attachViewHierarchy = false
            o.enableUserInteractionTracing = false
            #endif
            o.enableAppHangTracking = true
            o.enableAutoSessionTracking = false
            o.enableAutoBreadcrumbTracking = false
            o.enableNetworkBreadcrumbs = false
            o.enableNetworkTracking = false
            o.enableCaptureFailedRequests = false
            o.enableAutoPerformanceTracing = false
            o.tracePropagationTargets = []
            o.tracesSampleRate = 0
            o.beforeBreadcrumb = { crumb in
                if let message = crumb.message, CrashReports.secret(message) { return nil }
                if let data = crumb.data, CrashReports.secret("\(data)") { return nil }
                return crumb
            }
            o.beforeSend = { event in
                guard consent.enabled else { return nil }
                if let message = event.message?.formatted, CrashReports.secret(message) {
                    event.message = SentryMessage(formatted: "[redacted]")
                }
                event.breadcrumbs = event.breadcrumbs?.filter { !CrashReports.secret("\($0.message ?? "") \($0.data ?? [:])") }
                return event
            }
            configure(o)
        }
        running = true
    }
}

/// Crash and hang reporting is available only when this build contains a DSN.
enum CrashReports {
    static let preferenceKey = "privacy.crashReportsOff"
    static let consent = CrashUploadConsent(
        read: { !AppleFixtureWorkspace.defaults.bool(forKey: preferenceKey) },
        write: { AppleFixtureWorkspace.defaults.set(!$0, forKey: preferenceKey) },
        outboundAccess: .init(paused: { DeviceOutboundPolicy.access.isPaused }))

    static var packagedDSN: String? {
        guard let url = Bundle.main.url(forResource: "CrashReporting", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let settings = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: String]
        else { return nil }
        return settings["SentryDSN"]
    }

    private static var dsn: String? {
        guard !AppleFixtureWorkspace.enabled, let dsn = packagedDSN, dsn.hasPrefix("https://"),
              (try? SentryDsn(string: dsn)) != nil else { return nil }
        return dsn
    }

    static var configured: Bool { dsn != nil }

    @MainActor private static let runtime = CrashReportRuntime(
        consent: consent, dsn: dsn)

    @MainActor static func start() { runtime.reconcile() }

    /// Bridgit's bearer tokens: website sessions (bws_), relay pairings (rly_).
    static func secret(_ s: String) -> Bool { s.contains("bws_") || s.contains("rly_") }
}
