#if os(macOS)
import AppKit
import SwiftUI

/// Mac builds have no store or download channel: each one is installed beside the older ones as
/// ~/Applications/Bridgit-<build>.app (tools/build-requests.py). Check for updates finds the newest
/// installed copy of this app and opens it, the Mac counterpart of Android's updater and iOS TestFlight.
enum MacUpdates {
    struct Build: Equatable {
        let url: URL
        let version: String
        let build: String
        var label: String { "v" + version + " (" + build + ")" }
    }

    static var current: Build {
        Build(url: Bundle.main.bundleURL,
              version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?",
              build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?")
    }

    static var folders: [URL] {
        [FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true),
         URL(fileURLWithPath: "/Applications", isDirectory: true)]
    }

    /// Build numbers are UTC timestamps (yymmddHHMM); compare them as numbers, then as version strings.
    static func isNewer(_ build: String, than other: String) -> Bool {
        if let a = Int64(build), let b = Int64(other) { return a > b }
        return build.compare(other, options: .numeric) == .orderedDescending
    }

    /// The newest app in `folders` with this bundle identifier and a later build than `current`.
    static func newest(in folders: [URL], bundleID: String?, newerThan current: Build) -> Build? {
        guard let bundleID else { return nil }
        let here = current.url.standardizedFileURL.resolvingSymlinksInPath()
        return folders.flatMap { folder in
            ((try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [])
                .filter { $0.pathExtension == "app" }
        }
        .compactMap { url -> Build? in
            guard url.standardizedFileURL.resolvingSymlinksInPath() != here,
                  let info = NSDictionary(contentsOf: url.appendingPathComponent("Contents/Info.plist")),
                  info["CFBundleIdentifier"] as? String == bundleID,
                  let build = info["CFBundleVersion"] as? String, isNewer(build, than: current.build) else { return nil }
            return Build(url: url, version: info["CFBundleShortVersionString"] as? String ?? "?", build: build)
        }
        .max { isNewer($1.build, than: $0.build) }
    }

    static func check() -> Build? { newest(in: folders, bundleID: Bundle.main.bundleIdentifier, newerThan: current) }

    /// Opens the newer build and quits this one, so only one host runs.
    static func open(_ build: Build) {
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: build.url, configuration: configuration) { app, _ in
            guard app != nil else { return }
            DispatchQueue.main.async { NSApp.terminate(nil) }
        }
    }
}

/// More → Check for updates and Settings → About on the Mac.
struct MacUpdateButton<Label: View>: View {
    @ViewBuilder var label: () -> Label
    @State private var checked = false
    @State private var newer: MacUpdates.Build?

    var body: some View {
        Button {
            newer = MacUpdates.check()
            checked = true
        } label: { label() }
        .alert("Update available", isPresented: Binding(get: { checked && newer != nil }, set: { checked = $0 }), presenting: newer) { build in
            Button("Open " + build.label) { MacUpdates.open(build) }
            Button("Not now", role: .cancel) {}
        } message: { build in
            Text("This Mac has Bridgit " + build.label + " installed. You're on " + MacUpdates.current.label + ".")
        }
        .alert("Bridgit is up to date", isPresented: Binding(get: { checked && newer == nil }, set: { checked = $0 })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(MacUpdates.current.label + " is the newest Bridgit build on this Mac.")
        }
    }
}
#endif
