import Foundation
import SwiftUI
import UIKit

/// Update prompts (A0050, 7 Nov 2026). Apple doesn't let an app install itself, so Bridgit Portal checks the
/// newest TestFlight build (published by publish-testflight.sh next to the APK manifest) and asks the owner to
/// update in TestFlight. On by default. Agents change the switch, or ask for the prompt now, through a paired
/// phone: its snapshot carries their request (`updates`), and this reports back with POST /v1/portal/updates.
@MainActor
final class UpdatePrompts: ObservableObject {
    static let manifestURL = URL(string: "https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/portal-ios-latest.json")!
    static let checkEvery: TimeInterval = 3 * 3600
    static let laterFor: TimeInterval = 24 * 3600
    private static let enabledKey = "update-prompts"
    private static let laterKey = "update-later"

    @Published private(set) var enabled: Bool
    @Published private(set) var latestBuild: String?
    @Published private(set) var lastResult: String?
    /// The "Update ready" alert.
    @Published var showPrompt = false

    let installedBuild: String
    /// A TestFlight install (or the `-updatePromptPreview` launch argument, for screenshots and tests).
    let supported: Bool
    private let preview: Bool
    private var lastCheck: Date?
    private var checking = false
    /// What was last reported to each phone, so a report is only sent when something changed.
    private var reported: [String: [String: AnyHashable]] = [:]

    init(defaults: UserDefaults = .standard, bundle: Bundle = .main, arguments: [String] = CommandLine.arguments) {
        enabled = defaults.object(forKey: Self.enabledKey) as? Bool ?? true
        installedBuild = bundle.infoDictionary?["CFBundleVersion"] as? String ?? ""
        preview = arguments.contains("-updatePromptPreview")
        #if DEBUG
        supported = preview
        #else
        supported = preview || bundle.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
        #endif
    }

    func setEnabled(_ on: Bool) {
        enabled = on
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        if !on { showPrompt = false }
    }

    /// When the app opens or comes forward: at most every few hours unless [force]d (an agent's request).
    func check(force: Bool = false, now: Date = Date()) async {
        guard supported else { lastResult = "unsupported"; return }
        if !force, let last = lastCheck, now.timeIntervalSince(last) < Self.checkEvery { return }
        guard !checking else { return }
        checking = true
        defer { checking = false }
        lastCheck = now
        let latest: String?
        if preview {
            latest = (Int64(installedBuild)).map { String($0 + 1) } ?? "2611072030"
        } else {
            latest = await Self.fetchLatest()
        }
        guard let latest else { lastResult = "check_failed"; return }
        latestBuild = latest
        lastResult = Self.decide(installed: installedBuild, latest: latest, enabled: enabled, force: force, laterUntil: laterUntil(latest), now: now)
        if lastResult == "prompted" { showPrompt = true }
    }

    func update() {
        showPrompt = false
        lastResult = "opened_testflight"
        if let url = URL(string: "itms-beta://") { UIApplication.shared.open(url) }
    }

    func later(now: Date = Date()) {
        showPrompt = false
        lastResult = "later"
        if let b = latestBuild { UserDefaults.standard.set(["build": b, "until": now.addingTimeInterval(Self.laterFor).timeIntervalSince1970], forKey: Self.laterKey) }
    }

    /// An agent's request from a phone's snapshot (`updates`), applied before reporting to that phone.
    func apply(_ request: [String: Any]?) async -> String? {
        guard let r = request, let id = r["id"] as? String else { return nil }
        if let on = r["prompts"] as? Bool { setEnabled(on) }
        if r["prompt_now"] as? Bool == true { await check(force: true) }
        return id
    }

    /// The POST /v1/portal/updates body, or nil when [phone] already has this state and nothing is acknowledged.
    func report(for phone: String, ack: String?) -> [String: Any]? {
        var body: [String: AnyHashable] = ["prompts_enabled": enabled, "supported": supported]
        if Self.isBuild(installedBuild) { body["installed_build"] = installedBuild }
        if let b = latestBuild { body["latest_build"] = b }
        if let r = lastResult { body["last_result"] = r }
        if ack == nil, reported[phone] == body { return nil }
        reported[phone] = body
        var out: [String: Any] = body
        if let ack { out["ack"] = ack }
        return out
    }

    /// A report the phone refused or that failed: send it again next time.
    func forgetReport(for phone: String) { reported[phone] = nil }

    private func laterUntil(_ build: String) -> Date? {
        guard let d = UserDefaults.standard.dictionary(forKey: Self.laterKey), d["build"] as? String == build,
              let t = d["until"] as? Double else { return nil }
        return Date(timeIntervalSince1970: t)
    }

    // MARK: Pure parts (unit-tested)

    static func isBuild(_ s: String) -> Bool { (1...12).contains(s.count) && s.allSatisfy(\.isASCII) && s.allSatisfy(\.isNumber) }

    static func newer(_ latest: String, than installed: String) -> Bool {
        guard isBuild(latest), isBuild(installed), let l = Int64(latest), let i = Int64(installed) else { return false }
        return l > i
    }

    /// What one check does: prompt, or say why not. A forced check (an agent asked) prompts even when the
    /// switch is off or the owner chose Later.
    static func decide(installed: String, latest: String, enabled: Bool, force: Bool, laterUntil: Date?, now: Date) -> String {
        guard newer(latest, than: installed) else { return "up_to_date" }
        if force { return "prompted" }
        if !enabled { return "off" }
        if let until = laterUntil, until > now { return "update_ready" }
        return "prompted"
    }

    /// The manifest's build, if it is for this app.
    static func parseManifest(_ data: Data, bundleId: String) -> String? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              o["bundle_id"] as? String == bundleId, let b = o["build"] as? String, isBuild(b) else { return nil }
        return b
    }

    private static func fetchLatest() async -> String? {
        var req = URLRequest(url: manifestURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.httpMethod = "GET"
        guard let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200,
              data.count < 64 * 1024 else { return nil }
        return parseManifest(data, bundleId: Bundle.main.bundleIdentifier ?? "cc.bridgit.portal")
    }
}

/// The "Update ready" alert, shown over every screen.
struct UpdatePromptAlert: ViewModifier {
    @ObservedObject var updates: UpdatePrompts
    func body(content: Content) -> some View {
        content.alert("Update ready", isPresented: $updates.showPrompt) {
            Button("Later", role: .cancel) { updates.later() }
            Button("Update") { updates.update() }
        } message: {
            Text("Install it in TestFlight.")
        }
    }
}
