import AppKit
import SwiftUI

/// Menu-bar images must have a small AppKit point size. A SwiftUI .frame does not resize
/// the NSImage bridged by MenuBarExtra; the full 512-point brand asset can consume the bar.
@MainActor final class MacStatusItem: NSObject, ObservableObject, NSMenuDelegate {
    static let iconPoints: CGFloat = 18
    private(set) var item: NSStatusItem?
    private weak var model: MacModel?
    private weak var local: LocalAppletModel?
    private var open: (() -> Void)?
    private var lifecycleObservers: [(NotificationCenter, NSObjectProtocol)] = []
    private let workspaceCenter: NotificationCenter
    private let applicationCenter: NotificationCenter
    init(workspaceCenter: NotificationCenter = NSWorkspace.shared.notificationCenter, applicationCenter: NotificationCenter = .default) {
        self.workspaceCenter = workspaceCenter; self.applicationCenter = applicationCenter
        super.init()
    }
    static func image(from source: NSImage) -> NSImage {
        let image = NSImage(size: NSSize(width: iconPoints, height: iconPoints), flipped: false) { rect in
            source.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
            return true
        }
        // This is the approved full-colour mark, not a monochrome template glyph.
        image.isTemplate = false
        image.accessibilityDescription = "Bridgit"
        return image
    }
    func configure(model: MacModel, local: LocalAppletModel, open: @escaping () -> Void) {
        self.model = model; self.local = local; self.open = open
        guard item == nil else { return } // Additional window appearances cannot add another item.
        Task {
            await local.host?.lifecycle(active: NSApp.isActive)
            await local.host?.resumeConfiguredHost()
        }
        observe(workspaceCenter, NSWorkspace.willSleepNotification) { await $0.sleep() }
        observe(workspaceCenter, NSWorkspace.didWakeNotification) { await $0.wake() }
        observe(applicationCenter, NSApplication.didBecomeActiveNotification) { await $0.lifecycle(active: true) }
        observe(applicationCenter, NSApplication.didResignActiveNotification) { await $0.lifecycle(active: false) }
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.autosaveName = "Bridgit.main"
        if let button = item.button {
            if let mark = NSImage(named: "Mark") { button.image = Self.image(from: mark) }
            else { button.image = NSImage(systemSymbolName: "point.3.connected.trianglepath.dotted", accessibilityDescription: "Bridgit") }
            button.imageScaling = .scaleProportionallyDown
            button.imagePosition = .imageOnly
            button.toolTip = "Bridgit"
            button.setAccessibilityLabel("Bridgit")
        }
        let menu = NSMenu(); menu.delegate = self; item.menu = menu; self.item = item
    }
    private func observe(_ center: NotificationCenter, _ name: Notification.Name, action: @escaping @MainActor (LocalHostModel) async -> Void) {
        let observer = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in if let host = self?.local?.host { await action(host) } }
        }
        lifecycleObservers.append((center, observer))
    }
    func menuWillOpen(_ menu: NSMenu) {
        menu.removeAllItems()
        let state = NSMenuItem(title: "Local preview", action: nil, keyEquivalent: ""); state.isEnabled = false; menu.addItem(state)
        add("Open Bridgit", action: #selector(openBridgit), to: menu)
        add(model?.paused == true ? "Resume device views" : "Pause device views", action: #selector(pauseViews), to: menu)
        if local?.host?.state == .connected || local?.host?.state == .connecting { add("Stop host", action: #selector(stopHost), to: menu) }
        let appearance = NSMenu(title: "Appearance")
        for value in ["System", "Light", "Dark"] {
            let row = NSMenuItem(title: value, action: #selector(changeAppearance(_:)), keyEquivalent: ""); row.target = self; row.representedObject = value
            row.state = (AppleFixtureWorkspace.defaults.string(forKey: "appearance") ?? "System") == value ? .on : .off
            appearance.addItem(row)
        }
        let appearanceItem = NSMenuItem(title: "Appearance", action: nil, keyEquivalent: ""); appearanceItem.submenu = appearance; menu.addItem(appearanceItem)
        menu.addItem(.separator()); add("Quit Bridgit", action: #selector(quit), key: "q", to: menu)
    }
    private func add(_ title: String, action: Selector, key: String = "", to menu: NSMenu) { let row = NSMenuItem(title: title, action: action, keyEquivalent: key); row.target = self; menu.addItem(row) }
    @objc private func openBridgit() { open?(); NSApp.activate(ignoringOtherApps: true) }
    @objc private func pauseViews() { model?.pause() }
    @objc private func stopHost() { Task { await local?.host?.stop() } }
    @objc private func changeAppearance(_ sender: NSMenuItem) { if let value = sender.representedObject as? String { AppleFixtureWorkspace.defaults.set(value, forKey: "appearance") } }
    @objc private func quit() { Task { await local?.host?.stop(); NSApp.terminate(nil) } }
    func showMenu() { item?.button?.performClick(nil) }
    func remove() {
        for (center, observer) in lifecycleObservers { center.removeObserver(observer) }
        lifecycleObservers.removeAll()
        if let item { NSStatusBar.system.removeStatusItem(item) }; item = nil
    }
    /// Read only: lets the owner verify actual runtime sizing without logging image/user content.
    var diagnostics: String {
        guard let item, let button = item.button else { return "Status item not installed" }
        return "image=\(button.image?.size.width ?? 0)×\(button.image?.size.height ?? 0)pt; button=\(button.bounds.width)×\(button.bounds.height)pt; length=\(item.length); template=\(button.image?.isTemplate ?? false); scaling=\(button.imageScaling.rawValue)"
    }
}

struct MacStatusInstaller: View {
    @ObservedObject var status: MacStatusItem
    var model: MacModel
    var local: LocalAppletModel
    @Environment(\.openWindow) private var openWindow
    var body: some View {
        Color.clear.frame(width: 0, height: 0).onAppear {
            status.configure(model: model, local: local, open: { openWindow(id: "main") })
        }
    }
}

@MainActor final class MacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}
