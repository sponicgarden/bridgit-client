import Foundation
import Sentry

/// Crash and hang reports to Sentry (project bridgit-portal-ios), only when the build carries a DSN
/// (see project.yml). No personal data, screenshots or view hierarchies, and nothing that looks like
/// a sign-in session or relay token ever leaves in a breadcrumb or event.
enum CrashReports {
    static func start() {
        guard !testRun, let dsn = Bundle.main.object(forInfoDictionaryKey: "SentryDSN") as? String, dsn.hasPrefix("https://") else { return }
        SentrySDK.start { o in
            o.dsn = dsn
            o.environment = "production"
            o.sendDefaultPii = false
            o.enableCaptureFailedRequests = false // a busy relay (503) is an answer, not a crash
            o.attachScreenshot = false
            o.attachViewHierarchy = false
            o.enableAppHangTracking = true
            o.tracesSampleRate = 0
            o.beforeBreadcrumb = { crumb in
                if let message = crumb.message, secret(message) { return nil }
                if let data = crumb.data, secret("\(data)") { return nil }
                return crumb
            }
            o.beforeSend = { event in
                if let message = event.message?.formatted, secret(message) { event.message = SentryMessage(formatted: "[redacted]") }
                event.breadcrumbs = event.breadcrumbs?.filter { !secret("\($0.message ?? "") \($0.data ?? [:])") }
                return event
            }
        }
    }

    /// Debug builds, simulators and unit tests: their crashes and hangs are not bugs users hit, and they
    /// filled the project with unreadable noise (Nov 2026). Like the APK's Robolectric guard.
    static var testRun: Bool {
        #if DEBUG || targetEnvironment(simulator)
        return true
        #else
        return ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        #endif
    }

    /// Bridgit's bearer tokens: website sessions (bws_), relay pairings (rly_).
    static func secret(_ s: String) -> Bool { s.contains("bws_") || s.contains("rly_") }
}
