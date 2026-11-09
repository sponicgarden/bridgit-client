import SwiftUI
import BridgitCore
#if os(macOS)
import AppKit
#else
import UIKit
#endif

struct BuildInstructionsView: View {
    // The Android build, copy and share actions all carry this same prompt.
    static let prompt = """
    Help me build a useful Bridgit applet.


    START

    •  Ask what I want it to do.
    •  Read https://bridgit.cc/learn/start.md first: it picks the recipe, the first calls and what done looks like. Details only if needed: https://bridgit.cc/learn/catalog-index.json.
    •  Follow only the selected capability details and the closest recipe at https://bridgit.cc/recipes/.
    •  Full instructions: \(AppletLists.buildGuide.absoluteString)


    HOW TO BUILD IT

    •  Prefer existing APIs, phone-resident watches or owner-approved schedules.
    •  Make an attractive HTML card adapted from the recipe template. Ordinary applets need no native build.
    •  Keep phone work, remote model calls and controller integrations separate.


    BEFORE YOU RUN IT

    •  Confirm my authorization and the installed API, grants, switches, permissions and device prerequisites.


    WHEN YOU REPORT BACK

    •  Show source freshness, errors and unknown coverage honestly.
    •  Verify the answer and the actual pause/stop state; explain retained data.
    •  Report what works, what was tested and any remaining integration.
    •  Do not treat a saved answer as proof that a controller action completed.

    —
    Sent from Bridgit · bridgit.cc
    """
    static func copyPrompt(_ prompt: String) {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(prompt, forType: .string)
        #else
        UIPasteboard.general.string = prompt
        #endif
    }

    @EnvironmentObject private var local: LocalAppletModel
    private struct Receipt: Identifiable {
        let id = UUID()
        let text: String
        let duration: Duration
    }
    @State private var receipt: Receipt?

    private func showReceipt(_ text: String, duration: Duration) {
        receipt = Receipt(text: text, duration: duration)
        AccessibilityNotification.Announcement(text).post()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 0) {
                    if let host = local.host {
                        BuildInstructionDelivery(host: host) { text in showReceipt(text, duration: .milliseconds(3500)) }
                    }
                    Text("Copy or share this prompt into your agent’s chat, then describe the applet you want. Connect the agent in Agents if you haven’t already.")
                        .font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                }
                Card {
                    VStack(alignment: .leading, spacing: 8) {
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 16) { promptActions }
                            VStack(alignment: .leading, spacing: 4) { promptActions }
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(verbatim: Self.prompt)
                            .font(.subheadline).textSelection(.enabled)
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(B.surfaceLowest, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .frame(maxWidth: 600, alignment: .leading)
            .padding()
            .frame(maxWidth: .infinity)
        }
        .background(B.surface)
        .overlay(alignment: .bottom) {
            if let receipt {
                Text(receipt.text).font(.callout)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule()).padding()
                    .allowsHitTesting(false)
            }
        }
        .task(id: receipt?.id) {
            guard let current = receipt else { return }
            do { try await Task.sleep(for: current.duration) } catch { return }
            if receipt?.id == current.id { receipt = nil }
        }
    }

    @ViewBuilder private var promptActions: some View {
        Button {
            Self.copyPrompt(Self.prompt)
            showReceipt("Prompt copied", duration: .seconds(2))
        } label: {
            Text("Copy prompt").fixedSize(horizontal: true, vertical: false).frame(minHeight: 44)
        }
        ShareLink(item: Self.prompt, subject: Text("Build instructions")) {
            Text("Share").fixedSize(horizontal: true, vertical: false).frame(minHeight: 44)
        }
    }
}

struct BuildInstructionDelivery: View {
    @ObservedObject var host: LocalHostModel
    var createApplet = false
    var maintainedLibrary = false
    var onQueued: ((String) -> Void)? = nil
    @EnvironmentObject private var local: LocalAppletModel
    @State private var showSetup = false
    private var title: String { createApplet ? "Create an applet" : "Send to agent" }
    @State private var recipients: [HostAgent] = []
    @State private var choosing = false
    @State private var sending = false
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading) {
            if createApplet || !recipients.isEmpty {
                Group {
                    if createApplet {
                        Button { Task { await chooseRecipient() } } label: {
                            Label(title, systemImage: "plus")
                                #if os(iOS)
                                .frame(minHeight: 48).contentShape(Rectangle())
                                #endif
                        }
                    } else {
                        Button { Task { await chooseRecipient() } } label: {
                            Text(title).frame(maxWidth: .infinity, minHeight: 32)
                        }.buttonStyle(.borderedProminent).padding(.bottom, 8)
                    }
                }
                .disabled(sending)
                .confirmationDialog(title, isPresented: $choosing, titleVisibility: .visible) {
                    ForEach(recipients) { agent in Button(agent.name) { send(agent) } }
                    Button("Cancel", role: .cancel) {}
                }
            }
            if let message { Text(message).foregroundStyle(.secondary) }
        }
        .accessibilityHidden(showSetup)
        .allowsHitTesting(!showSetup)
        .coveringBuildStampSurface(showSetup)
        .bridgitDialog(isPresented: $showSetup) {
            ControlAgentSetup(host: host, packages: local.packages)
        }
        .task(id: host.state) {
            guard !createApplet else { return }
            while !Task.isCancelled {
                recipients = (try? await host.authority?.buildInstructionRecipients()) ?? []
                guard host.state == .connected else { return }
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
            }
        }
    }

    @MainActor private func chooseRecipient() async {
        guard !sending else { return }
        sending = true
        message = nil
        do {
            recipients = try await host.authority?.buildInstructionRecipients() ?? []
            sending = false
            switch recipients.count {
            case 0:
                if createApplet { showSetup = true }
                else { message = "No active agent available" }
            case 1: send(recipients[0])
            default: choosing = true
            }
        } catch {
            sending = false
            message = error.localizedDescription
        }
    }

    static func creationPrompt(device: String, maintainedLibrary: Bool) -> String {
        let target = maintainedLibrary
            ? " Use an owner-authorized registry edition for the maintained applet library. Give me the exact edition key and revision for review on this device. Do not create a legacy local installation. Report any missing runtime support or publishing authority before proceeding."
            : ""
        return "Help me create a Bridgit applet. Device: \(device). Use your existing Bridgit connection to this device.\(target) Instructions: \(AppletLists.buildGuide.absoluteString)"
    }

    private func send(_ agent: HostAgent) {
        guard !sending else { return }
        sending = true
        Task {
            defer { sending = false }
            guard let authority = host.authority else { message = "No active agent available"; return }
            do {
                let prompt = createApplet
                    ? Self.creationPrompt(device: LocalHostModel.deviceName, maintainedLibrary: maintainedLibrary)
                    : BuildInstructionsView.prompt
                try await authority.queueBuildInstructions(for: agent, prompt: prompt,
                                                          instructions: AppletLists.buildGuide)
                if createApplet {
                    BuildInstructionsView.copyPrompt(prompt)
                    message = "Queued for " + agent.name + ". Prompt copied."
                } else {
                    onQueued?("Queued for " + agent.name)
                }
            } catch { message = error.localizedDescription }
        }
    }
}

enum ControlAgentConnection: String, CaseIterable, Identifiable {
    case direct, relay, claude, chatGPT
    var id: String { rawValue }
    var title: String {
        switch self {
        case .direct: "Yes, use Tailscale"
        case .relay: "No, the control agent cannot use Tailscale"
        case .claude: "Claude Duet or claude.ai"
        case .chatGPT: "ChatGPT Dots"
        }
    }
    var detail: String {
        switch self {
        case .direct: "Connect from the owner’s Tailscale network using a direct key. Review the listener and permissions next."
        case .relay: "Use the Bridgit relay over the internet. Choose encrypted or connector requests when you review permissions."
        case .claude: "Custom connector over the Bridgit relay. You choose its permissions next."
        case .chatGPT: "Custom MCP server over the Bridgit relay. You choose its permissions next."
        }
    }
    var hostedClient: Bool { self == .claude || self == .chatGPT }
    /// A0128: a new control agent uses Tailscale when this device has a Tailscale
    /// address, unless the owner picked a connection or a hosted client.
    static func initial(current: Self, chosen: Bool, tailscaleFound: Bool) -> Self {
        if chosen || current.hostedClient { return current }
        return tailscaleFound ? .direct : .relay
    }
    func detail(tailscaleFound: Bool) -> String {
        self == .direct && !tailscaleFound ? "Tailscale not found on this device." : detail
    }
    var clientName: String { self == .claude ? "Claude" : "ChatGPT" }
}

struct ControlAgentSetup: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @DialogDismiss private var dismiss
    @ObservedObject var host: LocalHostModel
    let packages: [[String: Any]]
    @State private var name = ""
    @State private var nameError: String?
    @State private var configuring = false
    @State private var choosingConnection = false
    @State private var checkingDirect = false
    @State private var connection = ControlAgentConnection.relay
    @State private var connectionChosen = false
    @State private var tailscaleFound = false
    @State private var permissions = HostAgentPermissionDraft()
    @State private var showingSecret = false
    private let suggestions: [(label: String, name: String, mark: String)] = [
        (label: "Grok bot / Grok", name: "Grok Bot", mark: "grok"),
        ("Claude", "Claude", "claude"), ("Claude Duet", "Claude Duet", "claude"),
        ("ChatGPT", "ChatGPT", "openai"), ("ChatGPT Dots", "ChatGPT Dots", "openai"),
        ("Muse", "Muse", "muse"), ("Google Gemini", "Gemini", "gemini"),
        ("OpenCode", "OpenCode", "opencode"), ("Instinct", "Instinct", "instinct"),
        ("OpenRouter.ai", "OpenRouter", "openrouter"), ("Cursor", "Cursor", "cursor")
    ]

    var body: some View {
        Group {
            if showingSecret {
                HostSetupSecret(host: host, connection: connection)
                    .onChange(of: host.oneTimeSetup) { _, setup in if setup == nil { dismiss() } }
            } else if configuring {
                HostAgentForm(host: host, packages: packages, name: name, connection: connection, draft: permissions,
                              onBack: { configuring = false }, onDraftChange: { permissions = $0 }) { showingSecret = true }
            } else if checkingDirect {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Check Tailscale connection").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    ScrollView { OwnerDirectConnectionControls(host: host) }
                    HStack {
                        Button { checkingDirect = false } label: {
                            Text("Back").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                        }
                        Button { dismiss() } label: {
                            Text("Cancel").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                        }
                        Spacer()
                        Button {
                            Task {
                                if host.ownerDirectAddress == nil {
                                    do { try await host.enableConfiguredOwnerDirect() }
                                    catch is CancellationError {}
                                    catch { host.problem = error.localizedDescription }
                                }
                                configuring = true
                            }
                        } label: {
                            Text("Next").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                        }.buttonStyle(.borderedProminent)
                            .disabled(host.ownerDirectStarting)
                    }
                }
                .padding()
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("owner-direct.setup")
            } else if choosingConnection {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Connection for " + name).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Choose how this control agent will connect, then review its permissions. Nothing is started or granted at this step.")
                                .font(.callout).foregroundStyle(B.onSurfaceVariant)
                            ForEach(ControlAgentConnection.allCases) { choice in
                                Button { connection = choice; connectionChosen = true } label: {
                                    HStack(alignment: .top, spacing: 12) {
                                        Image(systemName: connection == choice ? "largecircle.fill.circle" : "circle").accessibilityHidden(true)
                                        VStack(alignment: .leading, spacing: 5) {
                                            Text(choice.title).font(.headline)
                                            Text(choice.detail(tailscaleFound: tailscaleFound)).font(.callout).foregroundStyle(B.onSurfaceVariant)
                                        }
                                        Spacer(minLength: 0)
                                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                        .background(connection == choice ? B.primaryContainer : B.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                                        .contentShape(Rectangle())
                                }.buttonStyle(.plain)
                                    .accessibilityAddTraits(connection == choice ? .isSelected : [])
                                    .accessibilityIdentifier("agent-connection-" + choice.rawValue)
                            }
                            DisclosureGroup("Advanced Tailscale options") {
                                Text("Tailscale on a restricted server is unavailable here. This Apple host has no userspace Tailscale or SOCKS enrollment path.")
                                    .font(.callout).foregroundStyle(B.onSurfaceVariant).padding(.top, 8)
                            }
                        }
                    }
                    HStack {
                        Button("Back") { choosingConnection = false }
                        Button("Cancel") { dismiss() }
                        Spacer()
                        Button("Next") {
                            if connection == .direct { checkingDirect = true }
                            else { configuring = true }
                        }.buttonStyle(.borderedProminent)
                    }
                }.padding()
            } else {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("Add control agent").font(.title2.bold())
                        Spacer()
                        Button("Close") { dismiss() }
                    }
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("What do you call this control agent?").font(.headline)
                            TextField("Name", text: $name).textFieldStyle(.roundedBorder)
                                .onChange(of: name) { _, value in
                                    if value.count > 24 { name = String(value.prefix(24)) }
                                    nameError = nil
                                }
                            if let nameError { Text(nameError).foregroundStyle(B.error) }
                            Text("Letters, numbers, spaces, - and _. Up to 24.").font(.caption)
                            Text("\(name.count)/24").font(.caption)
                            LazyVGrid(columns: textSize.isAccessibilitySize ? [GridItem(.flexible())] : [GridItem(.adaptive(minimum: 128))], alignment: .leading) {
                                ForEach(suggestions, id: \.label) { suggestion in
                                    Button {
                                        name = suggestedName(suggestion.name)
                                        connection = suggestion.name == "Claude Duet" ? .claude : suggestion.name == "ChatGPT Dots" ? .chatGPT : .relay
                                    } label: {
                                        HStack {
                                            Image("agent_brand_" + suggestion.mark)
                                                .resizable().scaledToFit().frame(width: 22, height: 22)
                                                .accessibilityHidden(true)
                                            Text(suggestion.label)
                                        }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                    }.buttonStyle(.bordered)
                                }
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Button("Next", action: next).buttonStyle(.borderedProminent)
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding()
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack {
                Spacer()
                BuildStamp(foreground: B.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
        }
        .buildStampSurface()
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 420)
        #endif
        .onDisappear { if showingSecret { host.oneTimeSetup = nil } }
        .interactiveDismissDisabled(host.creatingAgent)
    }

    private func suggestedName(_ base: String) -> String {
        let used = Set(host.agents.map { $0.name.trimmingCharacters(in: .whitespaces).lowercased() })
        var candidate = base
        var suffix = 2
        while used.contains(candidate.lowercased()) {
            candidate = "\(base) \(suffix)"
            suffix += 1
        }
        return candidate
    }

    private func next() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.range(of: #"\A[A-Za-z0-9][A-Za-z0-9 _-]{0,23}\z"#, options: .regularExpression) == nil {
            nameError = "Use letters, numbers, spaces, - or _ (1 to 24)."
        } else if let match = host.agents.first(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }) {
            nameError = "You already have a control agent called \(match.name)."
        } else {
            name = trimmed
            tailscaleFound = !host.availableTailnetAddresses.isEmpty
            connection = .initial(current: connection, chosen: connectionChosen, tailscaleFound: tailscaleFound)
            choosingConnection = true
        }
    }
}
