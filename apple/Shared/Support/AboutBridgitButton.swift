import SwiftUI
import Darwin

struct AboutBridgitButton: View {
    @Environment(\.openURL) private var openURL
    @State private var presented = false

    var body: some View {
        Button { presented = true } label: {
            HStack {
                Label("About", systemImage: "info.circle")
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(B.onSurfaceVariant)
            }.contentShape(Rectangle()).padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .alert("About Bridgit", isPresented: $presented) {
            Button("Privacy policy") { openURL(AppleBuildDetails.privacy) }
            Button("OK", role: .cancel) {}
        } message: { Text(details) }
    }

    private var details: String {
        var lines = ["Version " + AppleBuildDetails.version]
        if let revision = AppleBuildDetails.revision { lines.append("Commit " + revision) }
        lines.append("\nSource code: github.com/sponicgarden/bridgit")
        return lines.joined(separator: "\n")
    }
}

enum AppleBuildDetails {
    static let privacy = URL(string: "https://bridgit.cc/privacy/")!
    static let terms = URL(string: "https://bridgit.cc/terms/")!

    static var configuration: String {
        #if DEBUG
        return "debug"
        #else
        return "release"
        #endif
    }

    static var deviceModel: String? {
        #if os(iOS)
        return DeviceModel.name + " (" + DeviceModel.identifier + ")"
        #else
        var size = 0
        guard sysctlbyname("hw.model", nil, &size, nil, 0) == 0, size > 1 else { return nil }
        var bytes = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.model", &bytes, &size, nil, 0) == 0 else { return nil }
        return String(cString: bytes)
        #endif
    }

    static var shortVersion: String? { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String }
    static var buildNumber: String? { Bundle.main.infoDictionary?["CFBundleVersion"] as? String }

    static var version: String {
        let version = shortVersion ?? "?"
        let build = buildNumber ?? "?"
        let identity = version + " (" + build + ", " + configuration + ")"
        #if os(iOS)
        if let date = AppVersion.buildDate(build) {
            return identity + " · built " + date.formatted(date: .abbreviated, time: .shortened)
        }
        #endif
        return identity
    }

    static var revision: String? {
        guard let url = Bundle.main.url(forResource: "BuildRevision", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let revision = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return revision.isEmpty ? nil : revision
    }
}

#if os(iOS)
struct TestFlightUpdateButton<Label: View>: View {
    @Environment(\.openURL) private var openURL
    @State private var showingHelp = false
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button {
            #if targetEnvironment(simulator)
            showingHelp = true
            #else
            openURL(URL(string: "itms-beta://")!) { opened in showingHelp = !opened }
            #endif
        } label: { label() }
        .alert("Updates", isPresented: $showingHelp) {
            Button("OK", role: .cancel) {}
        } message: {
            #if targetEnvironment(simulator)
            Text("Open TestFlight on your iPhone or iPad to check for Bridgit updates.")
            #else
            Text("Bridgit updates arrive through TestFlight. Install TestFlight from the App Store to get them.")
            #endif
        }
    }
}
#endif
