import AppKit
import BridgitCore
import BridgitAppletRuntime
import SwiftUI
import WebKit
import XCTest
import Vision
@testable import Bridgit_macOS

private final class ActivityReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let denied = request.url?.path.contains("denied") == true
        let reply: [String: Any] = ["response": ["status": denied ? 403 : 200,
            "body": denied ? ["error": "fixture_unavailable", "message": "Fixture activity is unavailable."] : ["home": [:], "logs": ["entries": []]]]]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: reply))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class HomeRenderTests: XCTestCase {
    /// SwiftUI tasks can retain the SQLite store past the end of the test method.
    /// Unlinking its WAL before the last connection closes invalidates SQLite's handles.
    @MainActor private func removeFixtureAfterClosingStore(_ directory: URL, local: LocalAppletModel) {
        addTeardownBlock { [weak store = local.store] in
            for _ in 0..<50 where store != nil { try await Task.sleep(for: .milliseconds(100)) }
            guard store == nil else {
                XCTFail("The render fixture still owns its database: " + directory.path)
                return
            }
            try FileManager.default.removeItem(at: directory)
        }
    }

    @MainActor func testLinkedActivityEmptyStatesRenderWithoutDesktopFocus() async throws {
        for state in ["account", "paused", "unavailable", "empty"] {
            let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ActivityReplyProtocol.self]
            let session = URLSession(configuration: config)
            let model = MacModel(cloud: WatchClient(), loadStored: false, relayHTTP: session)
            if state == "account" {
                let phone = try JSONDecoder().decode(Phone.self, from: Data(#"{"phone":"fixture-account","name":"Kitchen phone"}"#.utf8))
                model.selectAccount(phone)
                XCTAssertNil(model.selectedPairing)
            } else {
                model.pause()
                let id = state == "unavailable" ? "dev_denied" : "dev_empty"
                try model.saveConfiguredPairing(Pairing(base: URL(string: "https://activity.invalid/d/" + id)!, deviceId: id, token: "rly_fixture_only", name: "Kitchen phone"))
                if state != "paused" { model.pause(); await model.refreshSelected() }
                XCTAssertEqual(model.snapshotAt != nil, state == "empty")
            }
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let local = LocalAppletModel(directory: directory)
            removeFixtureAfterClosingStore(directory, local: local)
            let host = NSHostingView(rootView: AnyView(MacRootView(initialSection: "Activity").environmentObject(model).environmentObject(local)))
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760), styleMask: [.titled], backing: .buffered, defer: false)
            window.contentView = host
            for (label, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
                window.appearance = NSAppearance(named: appearance); window.layoutIfNeeded()
                try await Task.sleep(for: .milliseconds(350))
                try capture(host, name: "linked-activity-" + state + "-" + label)
            }
            host.rootView = AnyView(EmptyView()); host.layoutSubtreeIfNeeded(); window.contentView = nil
            try model.selectConfiguredDevice("local"); session.invalidateAndCancel()
        }
    }

    @MainActor func testPhoneCodeFormRendersInBothAppearances() async throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 480, height: 300),
                              styleMask: [.titled], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: LinkCodeView { _ in XCTFail("Rendering must not link an account") })
        window.contentView = host
        defer { window.contentView = nil }
        for (label, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(300))
            try capture(host, name: "account-link-code-" + label)
        }
    }

    /// Exercise the real split view and applet section without taking focus from
    /// another app or reading the owner's account and applet store.
    @MainActor func testStandaloneHomeRendersInAppKit() async throws {
        let expected = [
            ("Demo agenda", "The action card page with a numbered list: the demo's opening card."),
            ("Connection test", "A polished connection check using the shared action-card template. No device action; the owner confirms the answer path."),
            ("Step card", "Step pill, icon medallion, info panel, gradient primary button, Skip and End demo, status line."),
            ("Photo with a guess", "A photo, your guess, optional choice chips and text box, and buttons. Values come back in data.values."),
            ("Rock paper scissors round", "The demo's game page: Ready starts a 3, 2, 1, Shoot! countdown, the agent's hand appears as an icon at Shoot!, and the score and last round sit on top."),
            ("Wrap-up", "The action card page as the demo's last card: what happened and what next."),
            ("Checklist and slider mini app", "Checkboxes, a 1-10 slider and a send button; the answer is structured data.")
        ]
        XCTAssertEqual(AppletLists.bundledCards.map(\.title), expected.map(\.0))
        XCTAssertEqual(AppletLists.bundledCards.map(\.summary), expected.map(\.1))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: directory)
        removeFixtureAfterClosingStore(directory, local: local)
        let view = MacRootView()
            .environmentObject(MacModel(cloud: WatchClient(), loadStored: false))
            .environmentObject(local)
        let rect = NSRect(x: 0, y: 0, width: 1100, height: 760)
        let window = NSWindow(contentRect: rect, styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: view)
        window.contentView = host
        defer { window.contentView = nil }
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(350))
            let sections = ["Portals", "Applets", "Cards"]
            let segments = try XCTUnwrap(findPicker(in: host, labels: sections))
            XCTAssertEqual(segments.selectedSegment, 1)
            try capture(host, name: "home-" + name)
            try await select("Portals", in: sections, host: host)
            try capture(host, name: "portals-" + name)
            try await select("Cards", in: sections, host: host)
            let sources = ["Inbox", "Library"]
            XCTAssertEqual(try XCTUnwrap(findPicker(in: host, labels: sources)).selectedSegment, 0)
            try capture(host, name: "inbox-" + name)
            try await select("Library", in: sources, host: host)
            try capture(host, name: "library-" + name)
            try await select("Inbox", in: sources, host: host)
            try await select("Applets", in: sections, host: host)
        }
    }

    @MainActor func testEmptyLinkedAppletPagesRenderWithoutDesktopFocus() async throws {
        let model = MacModel(cloud: WatchClient(), loadStored: false)
        model.pause()
        model.preparePairing("bridgit://pair#v=1&relay=https%3A%2F%2F127.0.0.1%3A1&device=kitchen-fixture&token=rly_00000000000000000000000000000000&name=Kitchen%20phone")
        let pairing = try XCTUnwrap(model.pendingPairing)
        await model.confirmPairing(pairing)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: directory)
        removeFixtureAfterClosingStore(directory, local: local)
        let host = NSHostingView(rootView: AnyView(MacRootView().environmentObject(model).environmentObject(local)))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        window.contentView = host
        defer {
            host.rootView = AnyView(EmptyView())
            host.layoutSubtreeIfNeeded()
            window.contentView = nil
        }
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(350))
        try await select("Portals", in: ["Portals", "Applets", "Cards"], host: host)
        XCTAssertEqual(model.selectedDeviceName, "Kitchen phone")
        XCTAssertTrue(model.appletPortals.isEmpty)
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(250))
            try capture(host, name: "empty-linked-pages-" + name)
        }
    }

    @MainActor func testWideSidebarFreshAndTransitionAppearances() async throws {
        func makeWindow(_ appearance: NSAppearance.Name) -> (NSWindow, NSHostingView<WideSidebar>) {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 280, height: 700), styleMask: [.titled], backing: .buffered, defer: false)
            window.appearance = NSAppearance(named: appearance)
            let host = NSHostingView(rootView: WideSidebar(selection: .constant(.activity), inboxBadge: nil))
            window.contentView = host; window.layoutIfNeeded()
            return (window, host)
        }
        let (freshDark, darkHost) = makeWindow(.darkAqua)
        try await Task.sleep(for: .milliseconds(500)); try capture(darkHost, name: "sidebar-fresh-dark")
        let (transition, transitionHost) = makeWindow(.aqua)
        try await Task.sleep(for: .milliseconds(500)); try capture(transitionHost, name: "sidebar-fresh-light")
        transition.appearance = NSAppearance(named: .darkAqua); transition.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(500)); try capture(transitionHost, name: "sidebar-light-to-dark")
        XCTAssertEqual(freshDark.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]), .darkAqua)
        XCTAssertEqual(transition.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]), .darkAqua)
        freshDark.contentView = nil; transition.contentView = nil
    }

    /// Initial selection is seeded, not navigated through the desktop.
    @MainActor func testStandaloneMoreRendersInAppKit() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "HomeRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let reports = FeedbackReports(folder: directory.appendingPathComponent("Feedback"), defaults: defaults)
        XCTAssertTrue(reports.reports.isEmpty)
        let local = LocalAppletModel(directory: directory, settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        try await render(section: "More", name: "more", local: local, reports: reports)
    }

    /// A0137: the Agents page lists this Mac's own control agents.
    @MainActor func testAgentsListsThisMacsAgents() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "HomeRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = LocalAppletModel(directory: directory, settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        let host = try XCTUnwrap(local.host)
        let authority = HostAuthority(store: try XCTUnwrap(local.store), deviceID: "agents-test")
        _ = try await authority.create(name: "Almusca-M2Air", operations: [.interactionsRead, .administrator], applets: [])
        await host.loadSavedAgents()
        XCTAssertEqual(host.agents.map(\.name), ["Almusca-M2Air"])
        try await render(section: "Agents", name: "agents", local: local)
    }

    @MainActor func testStandaloneActivityRendersInAppKit() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "HomeRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = LocalAppletModel(directory: directory, settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        let host = try XCTUnwrap(local.host)
        XCTAssertTrue(host.activity.isEmpty)
        try await render(section: "Activity", name: "activity-empty", local: local)
        await host.prepare(identityOverride: try RelayHostIdentity.ephemeral())
        XCTAssertEqual(host.activity.count, 1)
        try await render(section: "Activity", name: "activity-prepared", local: local)
        let store = try XCTUnwrap(local.store)
        let engine = try XCTUnwrap(local.engine)
        let authority = HostAuthority(store: store, deviceID: "activity-test")
        let (agent, _) = try await authority.create(name: "Fixture Alpha", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func job(_ sequence: Int64, query: [String: Any]) -> HostJob {
            .init(id: UUID().uuidString, sequence: sequence, agentID: agent.id, generation: agent.generation, deviceID: "activity-test", deadline: now + 30_000, createdAt: now, request: .init(method: "GET", path: "/v1/interactions", query: query))
        }
        let success = await dispatcher.dispatch(job(1, query: [:]))
        let failure = await dispatcher.dispatch(job(2, query: ["invalid": "value"]))
        XCTAssertEqual(success.status, 200)
        XCTAssertEqual(failure.status, 422)
        let requests = try await store.hostActivity(kinds: [.request])
        XCTAssertEqual(requests.count, 2)
        let agentEvents = try await store.hostActivity(kinds: [.agent])
        XCTAssertEqual(agentEvents.map(\.agentID), [agent.id])
        try await render(section: "Activity", name: "activity-requests", local: local)
    }

    @MainActor private func render(section: String, name: String, local: LocalAppletModel, reports: FeedbackReports? = nil) async throws {
        let view = MacRootView(initialSection: section, feedbackReports: reports)
            .environmentObject(MacModel(cloud: WatchClient(), loadStored: false))
            .environmentObject(local)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: view)
        window.contentView = host
        defer { window.contentView = nil }
        for (appearanceName, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(350))
            try capture(host, name: name + "-" + appearanceName)
        }
    }

    @MainActor func testNetworkMapUsesExplicitCoreLabelAndAccessibleName() async throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        func map(name: String, label: String?) -> AnyView {
            AnyView(NetworkMapView(snapshot: NetworkMapSnapshot(name: name, coreLabel: label), visible: true, replay: 0, onOpen: { _ in }))
        }
        let host = NSHostingView(rootView: map(name: "Fixture Host", label: "This Mac"))
        window.contentView = host; window.layoutIfNeeded()
        defer { window.contentView = nil }
        var state: [String] = []
        for _ in 0..<100 {
            if let web = findWeb(in: host), let values = try? await web.evaluateJavaScript("[document.getElementById('coresub').textContent, document.getElementById('scene').getAttribute('aria-label')]") as? [String] {
                state = values
                if state.count == 2 && state[0] == "This Mac" && state[1].contains("This Mac") { break }
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(state.first, "This Mac")
        XCTAssertTrue(state.count == 2 && state[1].contains("This Mac"))
        let originalWeb = try XCTUnwrap(findWeb(in: host))
        let linkedName = "Hub \"North\" & Co"
        host.rootView = map(name: linkedName, label: nil)
        for _ in 0..<100 {
            if let values = try? await originalWeb.evaluateJavaScript("[document.getElementById('coresub').textContent, document.getElementById('scene').getAttribute('aria-label')]") as? [String] {
                state = values
                if state.count == 2 && state[1].contains(linkedName) { break }
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(findWeb(in: host) === originalWeb)
        XCTAssertTrue(state.count == 2 && state[1].contains(linkedName))
        XCTAssertEqual(state.first, "Hub \"North\" &…")
    }

    @MainActor func testNetworkMapReadyReplaysThemeAfterStalePageInitialization() async throws {
        let web = WKWebView(frame: NSRect(x: 0, y: 0, width: 400, height: 300))
        let coordinator = NetworkMapView.Coordinator(onOpen: { _ in }, replay: 0)
        coordinator.web = web
        coordinator.theme = "light" // SwiftUI changed while the initial dark URL was loading.
        web.loadHTMLString("<html data-theme='dark'><body>Fixture</body></html>", baseURL: nil)
        var initial: String?
        for _ in 0..<100 {
            initial = try? await web.evaluateJavaScript("document.documentElement.dataset.theme") as? String
            if initial == "dark" { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(initial, "dark")
        coordinator.pageReady()
        var applied: String?
        for _ in 0..<100 {
            applied = try? await web.evaluateJavaScript("document.documentElement.dataset.theme") as? String
            if applied == "light" { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(applied, "light")
    }

    @MainActor func testNetworkMapAppliesLatestThemeAfterReadyAndChangesLive() async throws {
        func map(_ scheme: ColorScheme) -> AnyView {
            AnyView(NetworkMapView(snapshot: NetworkMapSnapshot(name: "Fixture"), visible: true, replay: 0, onOpen: { _ in })
                .environment(\.colorScheme, scheme))
        }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: map(.dark))
        window.contentView = host
        defer { window.contentView = nil }
        window.layoutIfNeeded()
        // Change before the initial page can report ready: URL initialization must not win.
        host.rootView = map(.light)
        func expectTheme(_ expected: String) async throws -> WKWebView {
            for _ in 0..<100 {
                if let web = findWeb(in: host),
                   let state = try? await web.evaluateJavaScript("document.documentElement.dataset.theme") as? String,
                   state == expected { return web }
                try await Task.sleep(for: .milliseconds(100))
            }
            XCTFail("Network map did not apply theme: " + expected)
            throw NSError(domain: "NetworkMapTheme", code: 1)
        }
        let web = try await expectTheme("light")
        try await webSnapshot(web, name: "network-map-theme-light")
        host.rootView = map(.dark)
        let darkWeb = try await expectTheme("dark")
        XCTAssertTrue(darkWeb === web)
        try await webSnapshot(web, name: "network-map-theme-dark")
        host.rootView = map(.light)
        let lightWeb = try await expectTheme("light")
        XCTAssertTrue(lightWeb === web)
    }

    /// Render the actual catalog sheet in both appearances, including its foreground stamp.
    @MainActor func testAbilityCatalogSheetRendersOwnBuildStamp() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "CatalogSheetRender." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = LocalAppletModel(directory: directory, settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        let model = MacModel(cloud: WatchClient(), loadStored: false)
        let sheet = MacAbilityCatalogSheet(model: model, close: {})
            .environmentObject(local)
            .environment(\.buildStampVisible, false)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 700, height: 620), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: sheet)
        window.contentView = host
        defer { window.contentView = nil }
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(350))
            try capture(host, name: "ability-catalog-sheet-" + name)
        }
    }

    /// Inspect actual Cards toolbar affordances in an unshown isolated host.
    @MainActor func testCardsToolbarHostedDiscovery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: directory)
        removeFixtureAfterClosingStore(directory, local: local)
        let view = MacRootView().environmentObject(MacModel(cloud: WatchClient(), loadStored: false)).environmentObject(local)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: view)
        window.contentView = host
        defer { window.contentView = nil }
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(400))
        try await select("Cards", in: ["Portals", "Applets", "Cards"], host: host)
        for source in ["Inbox", "Library"] {
            if source == "Library" { try await select(source, in: ["Inbox", "Library"], host: host) }
            let ax = host.accessibilityChildren() ?? []
            func views(_ v: NSView) -> [NSView] { [v] + v.subviews.flatMap(views) }
            let all = views(host)
            print("CARDS TOOLBAR \(source): host AX children=\(ax.count), native buttons=\(all.compactMap { $0 as? NSButton }.map { $0.title }), popup controls=\(all.compactMap { $0 as? NSPopUpButton }.count)")
            try capture(host, name: "cards-toolbar-" + source.lowercased())
        }
    }

    /// Directly render empty Files and Shared content with a disposable store.
    /// This does not claim the toolbar menu or Done navigation was activated.
    @MainActor func testOwnerFilesContentOffscreen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "HomeRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = LocalAppletModel(directory: directory, settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        for (name, kind) in [("files", TransferKind.files), ("shared", .shares)] {
            let view = OwnerFilesView(kind: kind).environmentObject(local).background(B.surface)
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 600, height: 560), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
            let host = NSHostingView(rootView: view)
            window.contentView = host
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(700))
            try capture(host, name: "owner-" + name)
            window.contentView = nil
        }
    }

    /// Render a contextual report using a synthetic capture and isolated defaults/store.
    @MainActor func testContextualFeedbackRendersOffscreen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "HomeRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: "feedback.uploadOff")
        defer { defaults.removePersistentDomain(forName: suite) }
        let local = LocalAppletModel(directory: directory.appendingPathComponent("applets"), settings: defaults)
        removeFixtureAfterClosingStore(directory, local: local)
        let database = try XCTUnwrap(local.store)
        try await database.setTransferEnabled(.downloads, enabled: true)
        let entries = try await database.hostActivity()
        let picked = try XCTUnwrap(entries.first)
        XCTAssertTrue(picked.isSetting)
        let reports = FeedbackReports(folder: directory.appendingPathComponent("reports"), defaults: defaults)
        let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 120, pixelsHigh: 80, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        NSColor.systemBlue.setFill(); NSRect(x: 0, y: 0, width: 120, height: 80).fill()
        NSGraphicsContext.restoreGraphicsState()
        let image = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
        for (name, appearance, size) in [("light", NSAppearance.Name.aqua, DynamicTypeSize.large), ("dark", .darkAqua, .large), ("large-text", .aqua, .accessibility3)] {
            let view = FeedbackForm(kind: .bug, store: reports, lastScreen: image, sourceScreen: "Activity", activityEntry: picked, activityLog: entries)
                .defaultAppStorage(defaults).environment(\.dynamicTypeSize, size).background(B.surface)
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 620), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
            let host = NSHostingView(rootView: view)
            window.contentView = host; window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(500))
            try capture(host, name: "context-feedback-" + name + "-top")
            if let scroll = allScrollViews(in: host).filter({ ($0.documentView?.bounds.height ?? 0) > $0.contentView.bounds.height + 10 }).max(by: { ($0.documentView?.bounds.width ?? 0) < ($1.documentView?.bounds.width ?? 0) }), let document = scroll.documentView {
                scroll.contentView.scroll(to: NSPoint(x: 0, y: document.isFlipped ? max(0, document.bounds.height - scroll.contentView.bounds.height) : 0))
                scroll.reflectScrolledClipView(scroll.contentView)
                try await Task.sleep(for: .milliseconds(200))
                try capture(host, name: "context-feedback-" + name + "-bottom")
            } else { print("CONTEXT FEEDBACK \(name): no scrollable native view found") }
            window.setContentSize(NSSize(width: 560, height: 900))
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(200))
            try capture(host, name: "context-feedback-" + name + "-tall")
            window.contentView = nil
        }
    }

    /// Legacy bundled-package renderer only. Current Home uses PortableHomeApplets;
    /// these captures do not verify its registry release review or installation flow.
    /// The legacy validator may intentionally reject these maintained packages.
    @MainActor func testLegacyFairytaleReviewRendersOffscreen() async throws {
        try await renderBundledReview("a-sorta-fairytale-about-me", name: "fairytale")
    }

    @MainActor func testLegacyInsuranceReviewRendersOffscreen() async throws {
        try await renderBundledReview("insurance-inventory", name: "insurance")
    }

    @MainActor private func renderBundledReview(_ id: String, name: String) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let local = LocalAppletModel(directory: directory)
        removeFixtureAfterClosingStore(directory, local: local)
        let entry = try XCTUnwrap(BundledApplet.catalog.first { $0.id == id })
        let view = BundledAppletReview(entry: entry).environmentObject(local).environment(\.scenePhase, .active).background(B.surface)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 580), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: view)
        window.contentView = host
        defer { window.contentView = nil }
        window.appearance = NSAppearance(named: .aqua)
        window.layoutIfNeeded()
        try await Task.sleep(for: .seconds(1))
        try capture(host, name: name + "-review-top")
        let scroll = try XCTUnwrap(allScrollViews(in: host).first { ($0.documentView?.bounds.height ?? 0) > $0.contentView.bounds.height + 10 })
        let document = try XCTUnwrap(scroll.documentView)
        let maximum = max(0, document.bounds.height - scroll.contentView.bounds.height)
        scroll.contentView.scroll(to: NSPoint(x: 0, y: maximum / 2))
        scroll.reflectScrolledClipView(scroll.contentView)
        try await Task.sleep(for: .milliseconds(200))
        try capture(host, name: name + "-review-middle")
        scroll.contentView.scroll(to: NSPoint(x: 0, y: document.isFlipped ? maximum : 0))
        scroll.reflectScrolledClipView(scroll.contentView)
        try await Task.sleep(for: .milliseconds(250))
        try capture(host, name: name + "-review-bottom")
    }

    /// Devices uses the shared APK scene instead of the former native local profile.
    @MainActor func testLocalDeviceDiagnosticsRenders() async throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let deviceName = "Bridgit long device name 0123456789-ABCDEFGHIJ-KLMNOPQRST"
        let snapshot = DiagnosticsSnapshot(name: deviceName, animated: false,
            options: ["nodeStates": ["settings": "on"], "agents": [["name": "Fixture Alpha", "called": true]]],
            status: ["applets": []], activityReported: true)
        var opened: String?
        let host = NSHostingView(rootView: DiagnosticsSceneView(snapshot: snapshot, onOpen: { opened = $0 }, onRerun: {}))
        window.contentView = host
        defer { window.contentView = nil }
        window.layoutIfNeeded()
        func findWeb(_ view: NSView) -> WKWebView? {
            (view as? WKWebView) ?? view.subviews.compactMap { findWeb($0) }.first
        }
        var web: WKWebView?
        for _ in 0..<30 {
            web = findWeb(host)
            if let web, (try? await web.evaluateJavaScript("!!window.BridgitScene")) as? Bool == true { break }
            try await Task.sleep(for: .milliseconds(200))
        }
        let scene = try XCTUnwrap(web)
        let hasLog = try await scene.evaluateJavaScript("!!document.getElementById('logBox')") as? Bool
        let hasApplets = try await scene.evaluateJavaScript("!!document.getElementById('appletPanel')") as? Bool
        XCTAssertEqual(hasLog, true)
        XCTAssertEqual(hasApplets, true)
        let nameFits = try await scene.evaluateJavaScript("""
        Array.from(document.querySelectorAll('.portal-hub-name')).length === 2 &&
        Array.from(document.querySelectorAll('.portal-hub-name')).every(t => t.getComputedTextLength() <= 54 && !t.hasAttribute('textLength')) &&
        Array.from(document.querySelectorAll('.portal-hub-name')).some(t => t.textContent.endsWith('…'))
        """) as? Bool
        XCTAssertEqual(nameFits, true, "Long names must truncate without crushing glyphs")
        let accessibleName = try await scene.evaluateJavaScript("document.getElementById('core').getAttribute('aria-label')") as? String
        XCTAssertTrue(accessibleName?.contains(deviceName) == true)
        let layout = try await scene.evaluateJavaScript("""
        (function() {
          var rect = function(id) { return document.getElementById(id).getBoundingClientRect(); };
          var map = rect('stage'), applets = rect('homeStatus'), log = rect('logBox');
          var header = rect('desktopDiagnosticsTitle'), rerun = rect('rerun'), svg = rect('scene');
          return [map.top, map.right, innerWidth, applets.bottom, log.top,
                  applets.right, map.left, Math.abs(header.top - rerun.top),
                  svg.width, map.width, document.body.scrollHeight, innerHeight, map.bottom, svg.height, map.height];
        })()
        """) as? [Double]
        let bounds = try XCTUnwrap(layout)
        XCTAssertLessThanOrEqual(bounds[0], 1, "The animation reaches the top")
        XCTAssertEqual(bounds[1], bounds[2], accuracy: 1, "The map reaches the right edge")
        XCTAssertLessThanOrEqual(bounds[3], bounds[4], "Applets precede logs")
        XCTAssertLessThanOrEqual(bounds[5], bounds[6], "Activity stays to the left of the map")
        XCTAssertLessThan(bounds[7], 16, "Rerun sits beside the diagnostics heading")
        XCTAssertEqual(bounds[8], bounds[9], accuracy: 1, "The map uses the whole pane width")
        XCTAssertLessThanOrEqual(bounds[10], bounds[11] + 1, "The page fits vertically")
        XCTAssertGreaterThan(bounds[12], bounds[11] - 60, "The animation uses the full available height")
        XCTAssertEqual(bounds[13], bounds[14], accuracy: 1)
        _ = try await scene.evaluateJavaScript("document.getElementById('hdr').textContent = 'No Control Agent'; document.getElementById('addAgent').hidden = false")
        try await Task.sleep(for: .milliseconds(100))
        let controls = try await scene.evaluateJavaScript("[document.getElementById('desktopDiagnosticsTitle').textContent, document.getElementById('desktopAgentRow').innerText]") as? [String]
        XCTAssertEqual(controls?.first, "Level 1 Diagnostics")
        XCTAssertEqual(controls?.last?.replacingOccurrences(of: "\n", with: " "), "No Control Agent Add Control Agent")
        _ = try await scene.evaluateJavaScript("document.getElementById('core').dispatchEvent(new MouseEvent('click', {bubbles:true}))")
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(opened, "phones", "The hexagon opens the same device chooser as Android")
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            try await Task.sleep(for: .milliseconds(500))
            try capture(host, name: "local-device-diagnostics-" + name)
            let image = try await scene.takeSnapshot(configuration: nil)
            if let tiff = image.tiffRepresentation,
               let bitmap = NSBitmapImageRep(data: tiff),
               let png = bitmap.representation(using: .png, properties: [:]) {
                try png.write(to: URL(fileURLWithPath: "/tmp/bridgit-diagnostics-layout-" + name + ".png"))
            }
        }
    }
    @MainActor private func allScrollViews(in view: NSView) -> [NSScrollView] {
        ((view as? NSScrollView).map { [$0] } ?? []) + view.subviews.flatMap { allScrollViews(in: $0) }
    }

    /// Read current authorization state only; no Grant or Settings action is invoked.
    @MainActor func testHardwareAccessRendersOffscreen() async throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 700, height: 600), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: HardwareAccessView())
        window.contentView = host
        defer { window.contentView = nil }
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            window.appearance = NSAppearance(named: appearance)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(500))
            try capture(host, name: "hardware-" + name)
        }
    }

    /// Exercise bundled HTML in the production sheet and WebKit bridge offscreen.
    @MainActor func testBundledPreviewChoices() async throws {
        for (id, expectedTitle) in [("html-connection-test", "Reply sent"), ("html-step", "Watch the light"), ("html-wrapup", "Thanks!"), ("html-photo-check", "Thanks!")] {
            let card = try XCTUnwrap(AppletLists.bundledCards.first { $0.id == id })
            if ["html-wrapup", "html-photo-check"].contains(id) {
                XCTAssertTrue(card.html.contains("Your answer reached the agent."))
                XCTAssertTrue(card.previewHTML.contains("Your answer stays on this device."))
            } else { XCTAssertEqual(card.previewHTML, card.html) }
            let choices = ["html-photo-check", "html-connection-test"].contains(id) ? 2 : 3
            for choice in 0..<choices {
                var closed = false
                let host = NSHostingView(rootView: MacLibraryCardSheet(card: card) { closed = true })
                let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 700, height: 700), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
                window.contentView = host
                host.frame = NSRect(x: 0, y: 0, width: 700, height: 700)
                host.layoutSubtreeIfNeeded()
                try await Task.sleep(for: .seconds(2))
                let web = try XCTUnwrap(findWeb(in: host))
                let before = try await web.evaluateJavaScript("JSON.stringify({title:document.querySelector('#title').textContent, buttons:Array.from(document.querySelectorAll('#actions button')).map(x=>({label:x.textContent,disabled:x.disabled}))})") as? String
                print("PREVIEW \(id) \(choice) before \(before ?? "nil")")
                let initial = try XCTUnwrap(before)
                XCTAssertTrue(initial.contains("disabled\":false"))
                try await webSnapshot(web, name: "\(id)-\(choice)-before")
                _ = try await web.evaluateJavaScript("document.querySelectorAll('#actions button')[\(choice)].click()")
                try await Task.sleep(for: .seconds(1))
                let afterValue = try await web.evaluateJavaScript("JSON.stringify({title:document.querySelector('#title').textContent,status:document.querySelector('#status').textContent,buttons:Array.from(document.querySelectorAll('#actions button')).map(x=>({label:x.textContent,disabled:x.disabled}))})")
                let after = try XCTUnwrap(afterValue as? String)
                print("PREVIEW \(id) \(choice) after \(after)")
                XCTAssertTrue(after.contains(expectedTitle))
                if id == "html-wrapup" || id == "html-photo-check" { XCTAssertTrue(after.contains("Your answer stays on this device.")) }
                XCTAssertFalse(after.contains("disabled\":false"))
                if choice == 0 {
                    try await Task.sleep(for: .seconds(9))
                    let delayedValue = try await web.evaluateJavaScript("JSON.stringify({title:document.querySelector('#title').textContent,status:document.querySelector('#status').textContent,disabled:Array.from(document.querySelectorAll('#actions button')).map(x=>x.disabled)})")
                    let delayed = try XCTUnwrap(delayedValue as? String)
                    print("PREVIEW \(id) delayed \(delayed)")
                    if delayed.contains("false") || !delayed.contains(expectedTitle) { throw NSError(domain: "PreviewLockRegressed", code: 1) }
                }
                _ = try await web.evaluateJavaScript("document.querySelectorAll('#actions button')[\((choice + 1) % choices)].click()")
                try await Task.sleep(for: .milliseconds(250))
                let repeatedValue = try await web.evaluateJavaScript("document.querySelector('#title').textContent")
                let repeated = try XCTUnwrap(repeatedValue as? String)
                XCTAssertTrue(repeated.contains(expectedTitle))
                try await webSnapshot(web, name: "\(id)-\(choice)-after")
                XCTAssertFalse(closed)
                window.contentView = nil
            }
        }
    }
    @MainActor func testLocalPortalBrowserHandoffToOwnedReceiver() async throws {
        guard FileManager.default.fileExists(atPath: "/tmp/br-mac-browser-receiver/Receiver.app") else { throw XCTSkip("Owned portal receiver is not configured") }
        setenv("BRIDGIT_FIXTURE_APPLET_PAGE_PORT", "18347", 1)
        defer { unsetenv("BRIDGIT_FIXTURE_APPLET_PAGE_PORT") }
        XCTAssertTrue(AppleFixtureWorkspace.enabled)
        let browser = AppletPageBrowser()
        let receiver = URL(fileURLWithPath: "/tmp/br-mac-browser-receiver/Receiver.app")
        browser.browserOpener = { destination in
            let config = NSWorkspace.OpenConfiguration(); config.activates = false
            NSWorkspace.shared.open([destination], withApplicationAt: receiver, configuration: config) { _, error in
                if let error { print("RECEIVER_ERROR: \(error)") }
            }
        }
        let url = try XCTUnwrap(URL(string: "https://bridgit.cc/myapps/?device=fixture-phone#pkg.alpha-test"))
        let host = NSHostingView(rootView: MacPortalPage(url: url, session: nil, email: nil, browser: browser))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 700, height: 650), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = host; host.frame = NSRect(x: 0, y: 0, width: 700, height: 650); host.layoutSubtreeIfNeeded()
        defer { window.contentView = nil }
        let web = try XCTUnwrap(findWeb(in: host))
        for _ in 0..<50 { if (try? await web.evaluateJavaScript("document.body.innerText") as? String)?.contains("ALPHA ROOT FIXTURE") == true { break }; try await Task.sleep(for: .milliseconds(100)) }
        let rootText = try await web.evaluateJavaScript("document.body.innerText") as? String; XCTAssertTrue(rootText?.contains("ALPHA ROOT FIXTURE") == true)
        try await webSnapshot(web, name: "portal-alpha-root")
        browser.reload(); try await Task.sleep(for: .seconds(1))
        let reloadedRoot = try await web.evaluateJavaScript("document.body.innerText") as? String; XCTAssertTrue(reloadedRoot?.contains("ALPHA ROOT FIXTURE") == true)
        browser.openInBrowser(fallback: url); try await Task.sleep(for: .seconds(1))
        _ = try await web.evaluateJavaScript("document.querySelector('a').click()")
        for _ in 0..<50 { if (try? await web.evaluateJavaScript("document.body.innerText") as? String)?.contains("ALPHA DETAIL FIXTURE") == true { break }; try await Task.sleep(for: .milliseconds(100)) }
        let detailText = try await web.evaluateJavaScript("document.body.innerText") as? String; XCTAssertTrue(detailText?.contains("ALPHA DETAIL FIXTURE") == true)
        try await webSnapshot(web, name: "portal-alpha-detail")
        browser.reload(); try await Task.sleep(for: .seconds(1))
        let reloadedDetail = try await web.evaluateJavaScript("document.body.innerText") as? String; XCTAssertTrue(reloadedDetail?.contains("ALPHA DETAIL FIXTURE") == true)
        browser.openInBrowser(fallback: url); try await Task.sleep(for: .seconds(1))
        let returnedDetail = try await web.evaluateJavaScript("document.body.innerText") as? String; XCTAssertTrue(returnedDetail?.contains("ALPHA DETAIL FIXTURE") == true)
        let received = try String(contentsOfFile: "/tmp/br-mac-browser-receiver/received.txt", encoding: .utf8)
        XCTAssertTrue(received.contains("?device=fixture-phone#pkg.alpha-test"))
        XCTAssertTrue(received.contains("?detail=1#pkg.alpha-test"))
    }

    @MainActor func testInstalledCardBridgeIsSandboxed() async throws {
        let handled = expectation(description: "Installed card request reaches scoped handler")
        let html = """
        <script>window.result = Bridgit.act({path:'/v1/applet/tables/stories/rows'}).then(r => [r.ok,r.body.count,typeof fetch,typeof navigator.geolocation].join(','));</script>
        """
        let host = NSHostingView(rootView: InstalledAppletCardWebView(html: html) { request in
            XCTAssertEqual(request["path"], .string("/v1/applet/tables/stories/rows"))
            handled.fulfill()
            return .object(["count": .number(2)])
        })
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 600, height: 400), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = host; host.frame = NSRect(x: 0, y: 0, width: 600, height: 400); host.layoutSubtreeIfNeeded()
        defer { window.contentView = nil }
        let web = try XCTUnwrap(findWeb(in: host))
        await fulfillment(of: [handled], timeout: 15)
        let result = try await web.callAsyncJavaScript("return await window.result", arguments: [:], in: nil, contentWorld: .page) as? String
        XCTAssertEqual(result, "true,2,undefined,undefined")
    }

    @MainActor func testInstalledFairytaleReadsItsOwnHistoryAndRejectsUnimplementedAction() async throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<3 { root.deleteLastPathComponent() }
        let bytes = try Data(contentsOf: root.appendingPathComponent("applets/a-sorta-fairytale-about-me/applet.json"))
        let definition = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        let entry = try JSONDecoder().decode(CatalogEntry.self, from: JSONSerialization.data(withJSONObject: ["key":"tpl_story", "id":definition["id"]!, "revision":1, "name":"My story", "summary":""]))
        let release = try RegistryPackage(response: JSONSerialization.data(withJSONObject: ["key":"tpl_story", "revision":1, "state":"published", "definition":definition]), selection: entry)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let library = try LocalAppletLibrary(database: directory.appendingPathComponent("library.sqlite"), account: String(repeating:"a", count:64))
        try await library.install(release)
        let installed = try await library.installed("tpl_story")
        let edition = try XCTUnwrap(installed)
        let html = try XCTUnwrap(release.package.cardHTML)
        let host = NSHostingView(rootView: InstalledAppletCardWebView(html: html) { request in
            try await library.cardTableAction(request, in: edition)
        })
        let window = NSWindow(contentRect: NSRect(x:0,y:0,width:700,height:700), styleMask:[.titled], backing:.buffered, defer:false)
        window.contentView = host; host.frame = NSRect(x:0,y:0,width:700,height:700); host.layoutSubtreeIfNeeded()
        defer { window.contentView = nil }
        let web = try XCTUnwrap(findWeb(in: host))
        var history = ""
        for _ in 0..<100 {
            history = (try? await web.evaluateJavaScript("document.querySelector('#history')?.textContent") as? String) ?? ""
            if history.contains("Your stories will appear here") { break }
            try await Task.sleep(for:.milliseconds(100))
        }
        XCTAssertTrue(history.contains("Your stories will appear here"))
        let denied = try await web.callAsyncJavaScript("return await Bridgit.act({method:'POST',path:'/v1/camera/video',body:{}})", arguments:[:], in:nil, contentWorld:.page) as? [String:Any]
        XCTAssertEqual(denied?["ok"] as? Bool, false)
        addTeardownBlock { [weak library] in
            for _ in 0..<50 where library != nil { try await Task.sleep(for: .milliseconds(100)) }
            guard library == nil else { XCTFail("Fairytale fixture still owns its database"); return }
            try FileManager.default.removeItem(at: directory)
        }
    }

    @MainActor private func findWeb(in view: NSView) -> WKWebView? {
        if let web = view as? WKWebView { return web }
        return view.subviews.lazy.compactMap { self.findWeb(in: $0) }.first
    }
    @MainActor private func webSnapshot(_ web: WKWebView, name: String) async throws {
        let image = try await web.takeSnapshot(configuration: nil)
        let tiff = try XCTUnwrap(image.tiffRepresentation)
        let rep = try XCTUnwrap(NSBitmapImageRep(data: tiff))
        let data = try XCTUnwrap(rep.representation(using: .png, properties: [:]))
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    @MainActor private func capture(_ host: NSView, name: String) throws {
        host.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: bitmap)
        let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
        let shot = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
        shot.name = "standalone-" + name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor private func select(_ label: String, in labels: [String], host: NSView) async throws {
        let control = try XCTUnwrap(findPicker(in: host, labels: labels))
        let index = try XCTUnwrap(labels.firstIndex(of: label))
        control.selectedSegment = index
        XCTAssertTrue(control.sendAction(control.action, to: control.target))
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(try XCTUnwrap(findPicker(in: host, labels: labels)).selectedSegment, index)
    }

    @MainActor private func findPicker(in view: NSView, labels: [String]) -> NSSegmentedControl? {
        if let control = view as? NSSegmentedControl, (0..<control.segmentCount).map({ control.label(forSegment: $0) }) == labels { return control }
        return view.subviews.lazy.compactMap { self.findPicker(in: $0, labels: labels) }.first
    }
}

final class FirstRunRenderTests: XCTestCase {
    @MainActor func testFourNativeStepsRenderWithoutEnablingHost() async throws {
        let suite = "FirstRunRenderTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = FirstRunController(defaults: defaults, hasOwnerData: false)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let local = LocalAppletModel(directory: directory, settings: defaults)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 760), styleMask: [.titled], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: FirstRunView(controller: controller) { _ in XCTFail("Rendering must not choose an action") }.environmentObject(local))
        window.contentView = host
        defer { window.contentView = nil }
        for step in 0..<4 {
            window.layoutIfNeeded(); host.layoutSubtreeIfNeeded()
            try await Task.sleep(for: .milliseconds(200))
            let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
            host.cacheDisplay(in: host.bounds, to: bitmap)
            let bytes = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
            let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.png")
            attachment.name = "mac-first-run-step-\(step + 1)"; attachment.lifetime = .keepAlways; add(attachment)
            XCTAssertTrue(controller.isPresented)
            XCTAssertEqual(local.host?.enabledIntent, false)
            XCTAssertTrue(local.host?.agents.isEmpty == true)
            controller.next()
        }
        XCTAssertNil(controller.progress.completedAction)
    }
}
private final class MacReviewRegistry: URLProtocol {
    static var definition: [String: Any] = [:]
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "mac-review.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let id = "a-sorta-fairytale-about-me"
        let entry: [String: Any] = ["key": id, "id": id, "revision": 1, "name": "A Sorta Fairytale About Me", "summary": "A story about your surroundings"]
        let object: [String: Any]
        switch request.url?.path {
        case "/v1/templates/account": object = ["library_account": String(repeating: "d", count: 64)]
        case "/v1/templates/catalog": object = ["format": "bridgit-applet-catalog/1", "scope": "public", "applets": [entry], "next": NSNull()]
        case "/v1/templates/a-sorta-fairytale-about-me/1": object = ["key": id, "revision": 1, "state": "published", "definition": Self.definition]
        default: client?.urlProtocol(self, didFailWithError: URLError(.fileDoesNotExist)); return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: object))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor final class MaintainedReviewRenderTests: XCTestCase {
    func testOffscreenMaintainedFairytaleReviewAndInstalledRendering() async throws {
        let path = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("applets/a-sorta-fairytale-about-me/applet.json")
        MacReviewRegistry.definition = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: path)) as? [String: Any])
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [MacReviewRegistry.self]
        let http = URLSession(configuration: configuration); defer { http.invalidateAndCancel() }
        let cloud = WatchClient(base: URL(string: "https://mac-review.invalid")!, session: "fixture", http: http)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var bindings: [String: String] = [:]
        let controller = PortableLibraryController(cloud: cloud, database: directory.appendingPathComponent("library.sqlite"), bindingRead: { bindings[$0] }, bindingWrite: { bindings[$1] = $0; return true })
        try await controller.catalog()
        let entry = try XCTUnwrap(controller.page?.applets.first)
        let rect = NSRect(x: 0, y: 0, width: 900, height: 750)
        let window = NSWindow(contentRect: rect, styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let host = NSHostingView(rootView: AnyView(NavigationStack { PortableReleaseReview(controller: controller, entry: entry) }))
        window.contentView = host; host.frame = rect; defer { window.contentView = nil }
        window.layoutIfNeeded()
        try await waitForRenderedText("Install on this device", in: host)
        try capture(host, name: "maintained-review")
        let scroll = try XCTUnwrap(descendants(host).compactMap { $0 as? NSScrollView }.first, "Review list must expose a scroll view")
        let document = try XCTUnwrap(scroll.documentView)
        let y = document.isFlipped ? max(0, document.bounds.height - scroll.contentView.bounds.height) : 0
        scroll.contentView.scroll(to: NSPoint(x: 0, y: y))
        scroll.reflectScrolledClipView(scroll.contentView)
        try await waitForRenderedText("image providers", in: host)
        try capture(host, name: "maintained-review-disclosure-end")
        guard case .local(let package) = try await controller.review(entry) else { return XCTFail("Expected maintained package") }
        XCTAssertEqual(package.package.version, "1.3.0")
        let edition = try await controller.install(package)
        XCTAssertEqual(edition.id, entry.key)
        XCTAssertEqual(edition.name, "A Sorta Fairytale About Me")
        let instance = try await controller.open(edition)
        XCTAssertEqual(instance.package.version, "1.3.0")
        host.rootView = AnyView(NavigationStack { PortableInstalledView(controller: controller, edition: edition) })
        window.layoutIfNeeded()
        try await waitForRenderedText("Records", in: host)
        try capture(host, name: "maintained-installed")
    }
    private func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap(descendants) }
    private func waitForRenderedText(_ needle: String, in host: NSView) async throws {
        for _ in 0..<30 {
            host.layoutSubtreeIfNeeded()
            if let image = host.bitmapImageRepForCachingDisplay(in: host.bounds) {
                host.cacheDisplay(in: host.bounds, to: image)
                if let cg = image.cgImage {
                    let request = VNRecognizeTextRequest(); request.recognitionLevel = .fast
                    try VNImageRequestHandler(cgImage: cg).perform([request])
                    let text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
                    if text.localizedCaseInsensitiveContains(needle) { return }
                }
            }
            try await Task.sleep(for: .milliseconds(250))
        }
        XCTFail("Rendered content did not contain \(needle)")
    }
    private func capture(_ host: NSView, name: String) throws {
        host.layoutSubtreeIfNeeded(); let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds)); host.cacheDisplay(in: host.bounds, to: bitmap)
        let shot = XCTAttachment(data: try XCTUnwrap(bitmap.representation(using: .png, properties: [:])), uniformTypeIdentifier: "public.png"); shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
}

/// A0131: Mac dialogs open as movable, resizable windows sized to their content and the screen.
final class DialogWindowTests: XCTestCase {
    func testOpeningSizeFitsContentWithinTheScreen() {
        let screen = NSRect(x: 0, y: 0, width: 1440, height: 900)
        XCTAssertEqual(DialogWindowController.openingSize(content: NSSize(width: 560, height: 440), screen: screen), NSSize(width: 560, height: 440))
        XCTAssertEqual(DialogWindowController.openingSize(content: NSSize(width: 100, height: 50), screen: screen), NSSize(width: 360, height: 240))
        let tall = DialogWindowController.openingSize(content: NSSize(width: 4000, height: CGFloat.infinity), screen: screen)
        XCTAssertEqual(tall.width, 1296, accuracy: 0.5)
        XCTAssertEqual(tall.height, 765, accuracy: 0.5)
    }

    @MainActor func testDialogOpensAsResizableWindowAndClosesWithItsBinding() async throws {
        final class Box: ObservableObject { @Published var shown = true; var dismissed = 0 }
        struct Presenter: View {
            @ObservedObject var box: Box
            var body: some View {
                Color.clear.frame(width: 400, height: 300)
                    .bridgitDialog(isPresented: $box.shown, onDismiss: { box.dismissed += 1 }) {
                        VStack { ForEach(0..<80) { Text("Row \($0)") } }
                    }
            }
        }
        let box = Box()
        let parent = NSWindow(contentRect: NSRect(x: 100, y: 100, width: 800, height: 600), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        parent.isReleasedWhenClosed = false
        parent.contentView = NSHostingView(rootView: Presenter(box: box))
        parent.orderFront(nil)
        defer { parent.close() }
        for _ in 0..<60 where (parent.childWindows ?? []).isEmpty { parent.contentView?.layoutSubtreeIfNeeded(); parent.displayIfNeeded(); try await Task.sleep(for: .milliseconds(50)) }
        let dialog = try XCTUnwrap(parent.childWindows?.first)
        XCTAssertTrue(dialog.styleMask.contains(.resizable))
        XCTAssertTrue(dialog.styleMask.contains(.titled))
        XCTAssertTrue(dialog.isMovable)
        try await Task.sleep(for: .milliseconds(300))
        try shoot(dialog, name: "dialog-opened")
        dialog.setFrameOrigin(NSPoint(x: dialog.frame.minX + 120, y: dialog.frame.minY - 80))
        dialog.setContentSize(NSSize(width: 520, height: 300))
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(dialog.contentLayoutRect.height, 300, accuracy: 1)
        try shoot(dialog, name: "dialog-moved-resized")
        dialog.performClose(nil)
        for _ in 0..<20 where box.shown { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertFalse(box.shown)
        XCTAssertEqual(box.dismissed, 1)
    }
}

extension DialogWindowTests {
    @MainActor func shoot(_ window: NSWindow, name: String) throws {
        let view = try XCTUnwrap(window.contentView)
        view.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        let shot = XCTAttachment(data: try XCTUnwrap(bitmap.representation(using: .png, properties: [:])), uniformTypeIdentifier: "public.png")
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
}

/// A0138: the Mac reopens on the sidebar section the owner last used.
final class LastSectionTests: XCTestCase {
    @MainActor func testRootReopensTheSavedSection() {
        let defaults = AppleFixtureWorkspace.defaults
        let previous = defaults.string(forKey: MacRootView.lastSectionKey)
        defer { defaults.set(previous, forKey: MacRootView.lastSectionKey) }
        defaults.set(WideSection.agents.rawValue, forKey: MacRootView.lastSectionKey)
        XCTAssertEqual(WideSection.restored(defaults.string(forKey: MacRootView.lastSectionKey) ?? "Home"), .agents)
    }
}
