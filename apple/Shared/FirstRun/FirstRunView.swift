import BridgitCore
import SwiftUI

@MainActor
final class FirstRunController: ObservableObject {
    @Published private(set) var progress: FirstRunProgress
    @Published private(set) var handlingIncomingLink = false
    @Published var problem: String?
    let bypassedForFixture: Bool
    var isPresented: Bool { progress.isRequired && !handlingIncomingLink && !bypassedForFixture }

    init(defaults: UserDefaults, hasOwnerData: Bool, bypassedForFixture: Bool = false) {
        self.bypassedForFixture = bypassedForFixture
        progress = FirstRunProgress(defaults: defaults, hasOwnerData: hasOwnerData)
    }
    /// Called in App.init, before either native host creates its initial workspace.
    static func launch() -> FirstRunController {
        #if DEBUG
        let args = CommandLine.arguments
        if let index = args.firstIndex(of: "-firstRunFixture"), args.indices.contains(index + 1),
           let id = UUID(uuidString: args[index + 1]) {
            return FirstRunController(defaults: UserDefaults(suiteName: "cc.bridgit.firstRun.fixture." + id.uuidString)!, hasOwnerData: false)
        }
        if args.contains(where: { $0.hasPrefix("-fixture") || $0 == "-sampleData" }) {
            // Explicit developer launch contracts only; never inspect a test bundle or XCTest environment.
            return FirstRunController(defaults: UserDefaults(suiteName: "cc.bridgit.firstRun.bypass." + UUID().uuidString)!, hasOwnerData: false, bypassedForFixture: true)
        }
        #endif
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let bundle = Bundle.main.bundleIdentifier ?? "cc.bridgit.local"
        let domain = UserDefaults.standard.persistentDomain(forName: bundle) ?? [:]
        let ownerKeys = FirstRunProgress.hasOwnerPreferences(Array(domain.keys))
        let directories = [
            AppletStorageLocation.directory(applicationSupport: support, bundleIdentifier: bundle, development: true),
            AppletStorageLocation.directory(applicationSupport: support, bundleIdentifier: bundle, development: false),
            support.appendingPathComponent(bundle).appendingPathComponent("Feedback")
        ]
        let existingWorkspace = directories.contains { FileManager.default.fileExists(atPath: $0.path) }
        return FirstRunController(defaults: .standard, hasOwnerData: ownerKeys || existingWorkspace || Keychain.get("watch-session") != nil)
    }
    static func inactive() -> FirstRunController {
        FirstRunController(defaults: UserDefaults(suiteName: "cc.bridgit.firstRun.preview." + UUID().uuidString)!, hasOwnerData: true)
    }
    func recognizeSavedAgents(_ agents: [HostAgent]) {
        if !agents.isEmpty { progress.recognizeSavedAgent() }
    }
    func next() { progress.next() }
    func back() { progress.back() }
    func skip() { progress.skip() }
    func finish(_ action: FirstRunProgress.Action) -> Bool { progress.finish(action) }
    func receive(_ url: URL, platform: String) {
        let local = platform == "ios" && url.scheme == "bridgit" && url.host == "device" && url.path.isEmpty && url.user == nil && url.password == nil && url.port == nil
        let signIn = url.scheme == "https" && url.host == SignInAttempt.callbackHost && url.path == SignInAttempt.callbackPath(platform: platform) && url.user == nil && url.password == nil && url.port == nil
        // This only yields presentation. The existing handler still validates/claims/authenticates.
        if local || signIn || Pairing.parse(link: url) != nil { handlingIncomingLink = true }
    }
}

struct FirstRunView: View {
    @ObservedObject var controller: FirstRunController
    let choose: (FirstRunProgress.Action) -> Void
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var example = 0
    @State private var hardware = false
    @State private var connection = false
    private static let providers: [(asset: String, name: String)] = [
        ("grok", "Grok"), ("claude", "Claude"), ("openai", "ChatGPT"), ("muse", "Muse"),
        ("gemini", "Google Gemini"), ("opencode", "OpenCode"), ("instinct", "Instinct"),
        ("openrouter", "OpenRouter.ai")
    ]
    private let examples = [("camera", "Ask about a photo"), ("waveform", "Talk with your agent"), ("tray.full", "Keep records in applets"), ("point.3.connected.trianglepath.dotted", "Connect your own services")]
    private var device: String {
        #if os(macOS)
        "Mac"
        #else
        ProcessInfo.processInfo.isiOSAppOnMac ? "Mac" : (UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
        #endif
    }
    var body: some View {
        VStack(spacing: 0) {
            WideBrandHeader()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding().opacity(hardware || connection ? 0 : 1)
                .accessibilityHidden(hardware || connection).background(B.surface)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    content
                    if let problem = controller.problem { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(B.error) }
                }
                .frame(maxWidth: 700, alignment: .leading).frame(maxWidth: .infinity)
                .padding(24)
            }
            controls.padding().frame(maxWidth: .infinity).background(B.surface)
        }
        .tint(B.primary).background(B.surface.ignoresSafeArea())
        .bridgitDialog(isPresented: $hardware) {
            NavigationStack {
                HardwareAccessView()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { HStack { BuildStamp(); Button("Done") { hardware = false } } } }
            }
        }
        .bridgitDialog(isPresented: $connection) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(local.host?.enabledIntent == true ? "Relay host is enabled" : "Relay host is stopped").font(.headline)
                        Text("This Apple host uses an outbound Bridgit relay connection. Add a control agent and review its grants before starting. Tailscale listening is available for satellite links only. SOCKS enrollment is unavailable here.")
                        Text("Starting a host does not enable cloud models or grant camera or microphone access.")
                    }.padding()
                }
                    .navigationTitle("Connection")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { HStack { BuildStamp(); Button("Done") { connection = false } } } }
            }
        }
        .task(id: controller.progress.step) {
            guard controller.progress.step == .introduction, !reduceMotion else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(4)) } catch { return }
                withAnimation { example = (example + 1) % examples.count }
            }
        }
    }
    @ViewBuilder private var content: some View {
        switch controller.progress.step {
        case .introduction:
            heading("Bridgit enables super-human utilization of your device.")
            Text("Unlock your device. Unleash agentic applications.").font(.headline)
            Text("Your AI control agents connect to this \(device) through the Bridgit relay. You choose what each agent can access.")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 64))], spacing: 18) {
                ForEach(Self.providers, id: \.asset) { provider in
                    Image("agent_brand_" + provider.asset).resizable().scaledToFit().frame(width: 42, height: 42)
                        .accessibilityLabel(Text(verbatim: provider.name))
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Control agents and providers")
            VStack(alignment: .leading, spacing: 14) {
                Text("Things to try").font(.headline).accessibilityAddTraits(.isHeader)
                ForEach(examples.indices, id: \.self) { index in
                    Label(examples[index].1, systemImage: examples[index].0)
                        .foregroundStyle(example == index ? B.primary : B.onSurfaceVariant)
                }
            }.padding().frame(maxWidth: .infinity, alignment: .leading).background(B.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        case .capabilities:
            heading("What Bridgit can do")
            capability("camera", "Camera and microphone", "Capture photos and use speech after you allow access and enable the feature. Availability depends on this device.")
            capability("square.stack.3d.up", "Applets and records", "Install applets, review their requests and keep their records on this device.")
            capability("person.crop.circle.badge.checkmark", "Control agents", "Choose an agent, connection and permissions. You can pause or revoke its access.")
            #if os(macOS)
            capability("desktopcomputer", "Desktop hosting", "Keep the Mac host running while connected. Background activity depends on your Mac’s power and network settings.")
            #else
            capability("iphone", "Foreground hosting", "Keep Bridgit open for active hosting. iOS limits background execution; optional wake delivery does not make it an always-on host.")
            #endif
            capability("questionmark.bubble", "Ask you", "Questions, notices and cards you answer with a tap.")
            Button("Review hardware access", systemImage: "camera.badge.ellipsis") { hardware = true }.accessibilityIdentifier("firstRun.hardware")
        case .privacy:
            heading("Private by design.")
            Text("Applet records are stored on this device. Relay requests pass through Bridgit. Account links can store activity snapshots, and approved cloud processing sends selected data to model providers.")
            Text("Camera and microphone use Apple’s permission prompts when you request access. Viewing these steps starts no connection or permission request.")
            Button("Review connection", systemImage: "network") { connection = true }.accessibilityIdentifier("firstRun.connection")
            privacyLink
        case .warning:
            heading("Before you add a control agent")
            Label("An access key is a key to your device.", systemImage: "key.fill").font(.title2.bold())
            Text("Only give it to an agent you trust. Maximum Agentic Control can manage app-owned settings and access you authorize. Apple permissions and security checks still apply.")
            Text("Hosted AI services may receive requests and data you share. Review the agent’s connection and permissions. Pause or revoke access if you no longer trust it.")
            privacyLink
        }
    }
    private func heading(_ text: String) -> some View { Text(text).font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader).accessibilityIdentifier("firstRun.heading") }
    private func capability(_ icon: String, _ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 6) { Label(title, systemImage: icon).font(.headline); Text(detail).foregroundStyle(B.onSurfaceVariant) }
    }
    private var privacyLink: some View {
        Link("Privacy policy", destination: URL(string: "https://bridgit.cc/privacy/")!).accessibilityIdentifier("firstRun.privacy")
    }
    private func actionLabel(_ title: String, icon: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if !textSize.isAccessibilitySize { Image(systemName: icon).accessibilityHidden(true) }
            Text(title).lineLimit(nil).fixedSize(horizontal: false, vertical: true)
        }.multilineTextAlignment(.center).frame(maxWidth: .infinity)
    }
    private var controls: some View {
        VStack(spacing: 12) {
            Text("Step \(controller.progress.step.rawValue + 1) of 4")
                .font(.caption).dynamicTypeSize(...DynamicTypeSize.accessibility1)
                .foregroundStyle(B.onSurfaceVariant).accessibilityIdentifier("firstRun.progress")
            if controller.progress.step == .warning {
                Button { choose(.diagnostic) } label: { actionLabel("Run diagnostic", icon: "stethoscope") }
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel("Run diagnostic").accessibilityIdentifier("firstRun.diagnostic")
                Button { choose(.addControlAgent) } label: { actionLabel("Add Control Agent", icon: "person.badge.plus") }
                    .buttonStyle(.bordered).accessibilityLabel("Add Control Agent").accessibilityIdentifier("firstRun.addAgent")
            }
            if textSize.isAccessibilitySize && controller.progress.step != .warning {
                HStack { back; Spacer(); skip }
                next
            } else {
                HStack { back; Spacer(); skip; next }
            }
        }.frame(maxWidth: 700)
    }
    @ViewBuilder private var back: some View {
        if controller.progress.step != .introduction {
            Button("Back") { controller.back() }.accessibilityIdentifier("firstRun.back")
        }
    }
    @ViewBuilder private var skip: some View {
        if controller.progress.step != .warning {
            Button("Skip") { controller.skip() }.accessibilityIdentifier("firstRun.skip")
        }
    }
    @ViewBuilder private var next: some View {
        if controller.progress.step != .warning {
            Button("Next") { controller.next() }.buttonStyle(.borderedProminent)
                .accessibilityIdentifier("firstRun.next")
        }
    }
}
