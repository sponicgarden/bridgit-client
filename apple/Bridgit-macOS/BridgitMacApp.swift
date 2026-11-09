import AppKit
import SwiftUI

@main
struct BridgitMacApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var delegate
    @AppStorage("appearance", store: AppleFixtureWorkspace.defaults) private var appearance = BridgitAppearance.system
    @StateObject private var firstRun: FirstRunController
    @StateObject private var local = LocalAppletModel()
    @StateObject private var model = MacModel()
    @StateObject private var status = MacStatusItem()
    init() {
        let introduction = FirstRunController.launch()
        _firstRun = StateObject(wrappedValue: introduction)
        CrashReports.start()
    }
    var body: some Scene {
        Window("Bridgit", id: "main") {
            VStack(spacing: 0) {
                CaptureNoticeBanner()
                MacRootView(firstRun: firstRun).environmentObject(model).environmentObject(local)
            }.frame(minWidth: 850, minHeight: 600)
                .defaultAppStorage(AppleFixtureWorkspace.defaults)
                .background(MacStatusInstaller(status: status, model: model, local: local))
                .toolbar(removing: .title)
                .preferredColorScheme(appearance.colorScheme)
                .onOpenURL { url in firstRun.receive(url, platform: "macos"); Task { await model.handle(url) } }
        }
        .windowToolbarStyle(.unified(showsTitle: false))
        .defaultSize(width: 1100, height: 760)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Close Window") { NSApp.mainWindow?.performClose(nil) }.keyboardShortcut("w", modifiers: .command)
            }
            CommandGroup(after: .appInfo) {
                Picker("Appearance", selection: $appearance) { ForEach(BridgitAppearance.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                Button("Quick controls") { status.showMenu() }
            }
        }
    }
}
