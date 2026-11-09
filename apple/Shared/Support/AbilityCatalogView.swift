import AVFoundation
import BridgitCore
import SwiftUI

/// Reference copy and stable share anchors from the Android 3347 catalog.
/// Local descriptions cover Apple behavior separately from Android options.
struct AbilityCatalogEntry: Decodable, Identifiable {
    let title: String
    let anchor: String
    let technical: Bool
    let stage: String
    let description: String
    let example: String
    let options: [String]
    let reference: String
    var id: String { anchor }
    static let bundled: [Self] = {
        guard let url = Bundle.main.url(forResource: "AbilityCatalog", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Self].self, from: data)) ?? []
    }()

    @MainActor var localDetail: String? {
        switch anchor {
        case "take-a-photo":
            if !AppleCamera.hardware { return "This device has no camera available to Bridgit." }
            return "A host camera request needs camera access, the owner’s camera switch and a camera grant for the control agent."
        case "questions-and-notices-to-you", "rich-html-cards", "inbox":
            return "Control agents with an interaction grant can send cards to the local Inbox. The owner can answer on this device."
        case "videos-and-downloads":
            return "You can choose a file to share with one control agent using Selected Downloads."
        case "files-to-your-phone":
            return "Enable Receive files to let a permitted control agent send files. You can open, export or delete them in Files."
        case "share-to-an-agent":
            return "Choose a file and a permitted recipient in Shared."
        case "app-settings":
            return "The owner and control agents with configuration access can change supported settings."
        case "per-agent-permissions":
            return "The owner or an authorized administrator can manage control agents and their individual access."
        case "add-an-agent-in-a-minute":
            return "Add a control agent, choose its permissions and connect it using the one-time setup information."
        case "one-key-per-agent":
            return "Each control agent has its own key and selected access. Rotate an exposed key in Control agents."
        case "report-a-bug-or-ask-for-a-feature":
            return "Open More to file a bug, request a feature or read your previous reports."
        case "the-api-explains-itself":
            return "The Apple host exposes the operations available to the calling control agent through API discovery."
        case "use-bridgit-over-mcp":
            return "Connect an MCP client to Bridgit with the agent’s own credentials and selected permissions."
        case "reach-bridgit-without-tailscale-relay":
            return "Start the host and grant access to receive requests through the relay."
        default: return nil
        }
    }

    var localSupplement: (options: [String], example: String)? {
        switch anchor {
        case "record-a-short-clip":
            return (["Request a clip from 1 to 120 whole seconds in M4A format.", "Clips are not kept as saved data; returned audio is limited to 1.5 MB.", "On-device transcription can be requested when speech recognition is ready."],
                    "Ask a permitted control agent to record the next 30 seconds and transcribe the clip on the device.")
        case "everything-is-logged":
            return (["Filter by agent, type, endpoint, result, level, time or text.", "Follow new entries, view statistics, or export JSONL or CSV.", "Agents with log access see all entries or only their own, according to their scope."],
                    "A control agent checks yesterday’s Activity to see why a photo request failed.")
        case "agent-notes":
            return (["Agents need log access to write a note.", "Notes appear in the agent channel of Activity."],
                    "A fridge-check control agent adds “milk low” to Activity.")
        case "the-api-explains-itself":
            return (["An authorized agent can read the operation guide and OpenAPI contract.", "Discovery reflects the operations available to that caller."],
                    "A new control agent reads the guide to learn which requests it can use.")
        case "add-an-agent-in-a-minute":
            return (["Choose a connection and the permissions this agent needs.", "Copy the one-time setup information only to the intended agent."],
                    "Add a control agent, copy its one-time setup and use it in that agent to connect to Bridgit.")
        case "one-key-per-agent":
            return (["Each agent receives separate credentials and permissions.", "Rotate the key if it is exposed."],
                    "If a key reaches the wrong person, rotate it and give the new setup to the same control agent.")
        case "use-bridgit-over-mcp":
            return (["For a hosted connector, copy its URL and relay key from agent setup.", "Permitted operations appear as MCP tools; JPEG results can be returned as images."],
                    "Add Bridgit as an MCP connector, then authorize it with the agent’s relay key.")
        case "report-a-bug-or-ask-for-a-feature":
            return (["Choose up to five screenshots and whether to include the recent log.", "For a bug, choose whether to attempt Auto-fix.", "Review saved reports and their status in More.", "When a report is marked fixed, confirm it or say it is still broken."],
                    "Describe a problem, add a screenshot and save or submit the report.")
        default: return nil
        }
    }
}

/// The selected target is explicit, including an unresolved linked selection.
/// Local listener and relay observations are separate from per-agent authority.
struct AbilityTransportFacts: Equatable {
    var directBound: Bool
    var directStarting: Bool
    var directIntent: Bool
    var directFailed: Bool
    var hasTailnetAddress: Bool
    var relayState: RelayHostConnection.State

    var directLabel: String {
        if directBound { return "Direct listening" }
        if directStarting { return "Direct starting" }
        if directFailed { return "Direct needs attention" }
        if directIntent { return "Direct unavailable" }
        return "Direct off"
    }
    var description: String {
        let direct: String
        if directBound { direct = "Direct Tailscale is listening." }
        else if directStarting { direct = "Direct Tailscale is starting." }
        else if directFailed { direct = "Direct Tailscale needs attention." }
        else if directIntent { direct = "Direct Tailscale is not ready." }
        else if hasTailnetAddress { direct = "Direct Tailscale is off." }
        else { direct = "Direct Tailscale is off; no Tailscale address is detected." }
        let relay: String
        switch relayState {
        case .stopped: relay = "Relay is off."
        case .connecting: relay = "Relay is connecting."
        case .connected: relay = "Relay is connected."
        case .failed: relay = "Relay needs attention."
        }
        return direct + " " + relay + " Control-agent access is separate."
    }
}

struct LocalAbilityFacts: Equatable {
    let cameraPresent: Bool
    let cameraEnabled: Bool
    let cameraPermission: Bool
    let microphonePresent: Bool
    let microphoneEnabled: Bool
    let microphonePermission: Bool
    let transcriptionReady: Bool
    var transport: AbilityTransportFacts? = nil
}

struct AbilityCatalogScope: Equatable {
    var identity: String
    var name: String
    var platform: String?
    var local: Bool
    var switches: [String: ReportedFeatureState] = [:]
    var localFacts: LocalAbilityFacts? = nil

    static let thisDevice = Self(identity: "local", name: "This device", platform: nil, local: true)
}

/// Profiles are observations for one caller and target, never durable authorization.
@MainActor final class AbilityProfileReader: ObservableObject {
    enum State: Equatable { case unknown, loading, loaded, denied }
    @Published private(set) var profile: DeviceProfile?
    @Published private(set) var state: State = .unknown
    @Published private(set) var identity: String?
    private var generation = 0

    func invalidate() {
        generation += 1
        profile = nil
        identity = nil
        state = .unknown
    }

    func refresh(identity: String, _ read: () async throws -> DeviceProfile) async {
        generation += 1
        let request = generation
        profile = nil
        self.identity = identity
        state = .loading
        do {
            let result = try await read()
            guard request == generation, !Task.isCancelled else { return }
            profile = result
            state = .loaded
        } catch {
            guard request == generation, !Task.isCancelled else { return }
            profile = nil
            if case RelayError.notAllowed = error { state = .denied }
            else { state = .unknown }
        }
    }
}

/// Observe owner-controlled device facts without manufacturing an agent or grant.
struct LocalAbilityCatalogView: View {
    @EnvironmentObject private var local: LocalAppletModel
    var body: some View {
        if let host = local.host { LocalAbilityHostView(host: host) }
        else { AbilityCatalogView(scope: .thisDevice) }
    }
}

private struct LocalAbilityHostView: View {
    @ObservedObject var host: LocalHostModel
    @EnvironmentObject private var local: LocalAppletModel
    var body: some View {
        let speech = local.speech.readiness()
        let facts = LocalAbilityFacts(cameraPresent: AppleCamera.hardware,
                                      cameraEnabled: host.cameraEnabled, cameraPermission: AppleCamera.permission,
                                      microphonePresent: AVCaptureDevice.default(for: .audio) != nil,
                                      microphoneEnabled: host.microphoneEnabled,
                                      microphonePermission: speech["microphone_allowed"] as? Bool == true,
                                      transcriptionReady: speech["hearing_on_device"] as? Bool == true,
                                      transport: .init(directBound: host.ownerDirectAddress != nil && host.ownerDirectPort != nil,
                                                       directStarting: host.ownerDirectStarting, directIntent: host.ownerDirectIntent,
                                                       directFailed: host.ownerDirectProblem != nil,
                                                       hasTailnetAddress: !host.availableTailnetAddresses.isEmpty,
                                                       relayState: host.state))
        AbilityCatalogView(scope: .init(identity: "local", name: "This device", platform: nil, local: true, localFacts: facts))
    }
}

struct AbilityCatalogView: View {
    var scope: AbilityCatalogScope = .thisDevice
    var readProfile: (() async throws -> DeviceProfile)? = nil
    @StateObject private var reader = AbilityProfileReader()

    private func isAndroid(now: Date) -> Bool {
        guard !scope.local else { return false }
        let reported = reader.identity == scope.identity && reader.profile?.isFresh(at: now) == true ? reader.profile?.device.platform : nil
        return (reported ?? scope.platform) == "android"
    }
    private var scopeKey: String { scope.identity }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { clock in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if AbilityCatalogEntry.bundled.isEmpty {
                        Text("The capability catalog couldn’t be loaded.")
                    } else {
                        Text("For " + scope.name).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                        if !scope.local && reader.identity == scope.identity && reader.state == .denied {
                            Text("This connection can’t read device capabilities.")
                                .font(.footnote).foregroundStyle(B.onSurfaceVariant)
                        }
                        ForEach([false, true], id: \.self) { technical in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(technical ? "How Bridgit works (the technical part)" : "Supported control agent requests")
                                    .font(.title2.bold()).accessibilityAddTraits(.isHeader)
                                Text(technical ? "How control agents connect, and how you stay in control." :
                                     "Each request depends on the device, its settings and the access you grant.")
                                    .foregroundStyle(B.onSurfaceVariant)
                                AccentRule()
                            }.padding().frame(maxWidth: .infinity, alignment: .leading)
                                .background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
                            ForEach(AbilityCatalogEntry.bundled.filter { $0.technical == technical }) { entry in
                                card(entry, now: clock.date)
                            }
                        }
                    }
                }.padding().frame(maxWidth: 760).frame(maxWidth: .infinity)
            }
        }
        .background(B.surface).navigationTitle("What can Bridgit do?")
        .task(id: scopeKey) {
            reader.invalidate()
            if !scope.local, let readProfile { await reader.refresh(identity: scope.identity, readProfile) }
        }
        #if os(iOS)
        .toolbar(.hidden, for: .tabBar)
        #endif
    }

    private func card(_ entry: AbilityCatalogEntry, now: Date) -> some View {
        let title = AbilityAvailability.title(for: entry, scope: scope)
        return VStack(alignment: .leading, spacing: 10) {
            if let url = Bundle.main.url(forResource: "whatcan_" + entry.anchor.replacingOccurrences(of: "-", with: "_"), withExtension: "webp") {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFit().frame(maxHeight: 180)
                } placeholder: { EmptyView() }.accessibilityHidden(true)
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(AbilityAvailability.label(for: entry.anchor, scope: scope, profile: reader.identity == scope.identity ? reader.profile : nil, now: now))
                        .font(.caption.bold()).foregroundStyle(B.onSurface)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .background(B.secondaryContainer, in: RoundedRectangle(cornerRadius: 4))
                    Text(title).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("ability-card-title-" + entry.anchor)
                }
                Spacer()
                ShareLink(item: AbilityAvailability.shareText(title: title, anchor: entry.anchor, scope: scope), subject: Text("Bridgit: " + title)) {
                    Image(systemName: "square.and.arrow.up").frame(minWidth: 44, minHeight: 44)
                }.accessibilityLabel("Share the API details for " + title)
            }
            if !scope.local && entry.anchor == "only-over-tailscale" {
                Text("Connection details have not been reported by this linked device.").accessibilityIdentifier("ability-card-body-" + entry.anchor)
            } else if isAndroid(now: now) {
                Text(entry.description).accessibilityIdentifier("ability-card-body-" + entry.anchor)
                details(options: entry.options, example: entry.example, anchor: entry.anchor)
            } else if scope.local, let detail = entry.localDetail {
                Text(detail).accessibilityIdentifier("ability-card-body-" + entry.anchor)
                if let supplement = entry.localSupplement {
                    details(options: supplement.options, example: supplement.example, anchor: entry.anchor)
                }
            } else if scope.local {
                Text(AbilityAvailability.localDescription(for: entry.anchor, facts: scope.localFacts)).accessibilityIdentifier("ability-card-body-" + entry.anchor)
                if let supplement = entry.localSupplement {
                    details(options: supplement.options, example: supplement.example, anchor: entry.anchor)
                }
            } else {
                Text(AbilityAvailability.description(for: entry.anchor)).accessibilityIdentifier("ability-card-body-" + entry.anchor)
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private func details(options: [String], example: String, anchor: String) -> some View {
        if !options.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Options").font(.subheadline.bold()).foregroundStyle(B.primary)
                ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                    HStack(alignment: .firstTextBaseline) {
                        Text("•").foregroundStyle(B.primary)
                        Text(option).accessibilityIdentifier("ability-card-option-" + anchor + "-" + String(index))
                    }
                }
            }.padding().background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 8))
        }
        Text("For example").font(.subheadline.bold())
        Text(example).accessibilityIdentifier("ability-card-example-" + anchor)
    }
}

/// Catalog anchors are mapped only where a specific operation is known. A general switch
/// is reported as a switch, never as proof that the calling agent may invoke an operation.
enum AbilityAvailability {
    static func title(for entry: AbilityCatalogEntry, scope: AbilityCatalogScope) -> String {
        scope.local && entry.anchor == "only-over-tailscale" ? "Direct Tailscale access" : entry.title
    }

    static let operations: [String: (String, ProfileOperation)] = [
        "take-a-photo": ("camera", .init(method: "POST", path: "/v1/camera/snapshot")),
        "record-a-short-clip": ("mic", .init(method: "POST", path: "/v1/mic/record")),
        "transcribe-clips-on-the-phone": ("mic", .init(method: "POST", path: "/v1/mic/record")),
        "files-to-your-phone": ("files", .init(method: "POST", path: "/v1/files")),
        "agent-notes": ("log", .init(method: "POST", path: "/v1/log")),
        "questions-and-notices-to-you": ("interactions", .init(method: "POST", path: "/v1/interactions")),
        "rich-html-cards": ("interactions", .init(method: "POST", path: "/v1/interactions")),
        "inbox": ("interactions", .init(method: "GET", path: "/v1/interactions")),
        "events-and-callbacks": ("interactions", .init(method: "GET", path: "/v1/events")),
        "app-settings": ("settings", .init(method: "GET", path: "/v1/settings")),
        "per-agent-permissions": ("settings", .init(method: "GET", path: "/v1/agents/me")),
        "one-key-per-agent": ("settings", .init(method: "GET", path: "/v1/agents/me")),
        "the-api-explains-itself": ("device", .init(method: "GET", path: "/v1/guide")),
        "phone-status-and-context": ("device", .init(method: "GET", path: "/v1/device/profile"))
    ]
    static let switches: [String: String] = ["take-a-photo": "camera", "record-a-short-clip": "mic"]
    // Owner-side actions do not imply an agent may call a host operation.
    static let ownerActions: Set<String> = ["add-an-agent-in-a-minute", "share-to-an-agent", "videos-and-downloads", "report-a-bug-or-ask-for-a-feature", "check-for-updates", "you-stay-in-control", "reach-bridgit-without-tailscale-relay"]
    static let partial: Set<String> = ["everything-is-logged", "events-and-callbacks", "phone-status-and-context", "short-video-clips", "play-an-audio-file", "find-and-interact-with-network-devices"]

    static func localDescription(for anchor: String, facts: LocalAbilityFacts? = nil) -> String {
        if anchor == "only-over-tailscale" {
            return facts?.transport?.description ?? "Connection details have not been reported."
        }
        let details: [String: String] = [
            "record-a-short-clip": "A permitted control agent can request a microphone clip when the owner has enabled access.",
            "everything-is-logged": "Activity records supported requests, events and control agent notes.",
            "events-and-callbacks": "Permitted agents can read interaction events. General device callbacks are not available.",
            "transcribe-clips-on-the-phone": "A permitted microphone request may include on-device transcription when speech recognition is ready.",
            "phone-status-and-context": "Device profile and connection status are available to permitted control agents.",
            "short-video-clips": "You can record a video in an installed applet. A control agent cannot request a general video capture.",
            "play-an-audio-file": "Installed applets can use supported speech actions. General audio-file playback is not available.",
            "check-for-updates": "Use the distribution channel for this installation to check for updates.",
            "find-and-interact-with-network-devices": "Linked devices can report network information. General discovery and control are not available here.",
            "you-stay-in-control": "The owner controls the host and can grant or revoke access for each control agent.",
            "agent-notes": "Permitted control agents can add notes to Activity."
        ]
        if let detail = details[anchor] { return detail }
        if operations[anchor] != nil || ownerActions.contains(anchor) { return description(for: anchor) }
        return "Not available on this device."
    }

    @MainActor static func label(for anchor: String, scope: AbilityCatalogScope, profile: DeviceProfile?, now: Date) -> String {
        if scope.local {
            if anchor == "only-over-tailscale" { return scope.localFacts?.transport?.directLabel ?? "Not reported" }
            if anchor == "take-a-photo" {
                guard let facts = scope.localFacts else { return "Not reported" }
                if !facts.cameraPresent { return "Not available" }
                if !facts.cameraEnabled { return "Switch off" }
                if !facts.cameraPermission { return "Needs permission" }
            }
            if anchor == "record-a-short-clip" || anchor == "transcribe-clips-on-the-phone" {
                guard let facts = scope.localFacts else { return "Not reported" }
                if !facts.microphonePresent { return "Not available" }
                if !facts.microphoneEnabled { return "Switch off" }
                if !facts.microphonePermission { return "Needs permission" }
                if anchor == "transcribe-clips-on-the-phone", !facts.transcriptionReady { return "Not ready" }
            }
            if anchor == "use-bridgit-over-mcp" {
                return HostOperationCatalog.contains(method: "POST", path: "/v1/mcp") ? "Supported" : "Not available"
            }
            if partial.contains(anchor) { return "Limited" }
            if let (_, operation) = operations[anchor] {
                return HostOperationCatalog.contains(method: operation.method, path: operation.path) ? "Supported" : "Not available"
            }
            if ownerActions.contains(anchor) { return "On this device" }
            return "Not available"
        }
        if let (id, operation) = operations[anchor], let profile, profile.isFresh(at: now), let cap = profile.capabilities[id] {
            if !cap.implemented || !cap.supported { return "Not available" }
            if !cap.agentAllowed { return "Not granted" }
            if !cap.enabled { return "Off" }
            if anchor == "transcribe-clips-on-the-phone" {
                guard let options = cap.constraints.parameters?["on_device_transcription"] else { return "Not reported" }
                if !options.contains("available_for_default_language") { return "Not ready" }
            }
            if profile.canCall(id, operation: operation, at: now) { return "Available" }
            return "Not available now"
        }
        if let id = switches[anchor], let state = scope.switches[id] {
            switch state {
            case .on: return "Switch last reported on"
            case .off: return "Switch last reported off"
            case .blocked: return "Switch last reported blocked"
            case .unavailable: return "Switch last reported unavailable"
            case .unreported: break
            }
        }
        return "Not reported"
    }

    static func shareText(title: String, anchor: String, scope: AbilityCatalogScope) -> String {
        let operation = anchor == "use-bridgit-over-mcp" ? "POST /v1/mcp. " :
            (operations[anchor].map { "\($0.1.method) \($0.1.path). " } ?? "")
        return "Bridgit: " + title + "\nFor " + scope.name + ". " + operation +
            "Ask a connected control agent to read GET /v1/guide and GET /v1/openapi.json for its permitted operations."
    }

    static func description(for anchor: String) -> String {
        let copy: [String: String] = [
            "find-and-interact-with-network-devices": "Discover and interact with devices on a local network.",
            "add-an-agent-in-a-minute": "Connect a control agent with its own access.",
            "one-key-per-agent": "Give each control agent its own key and access.",
            "take-a-photo": "Request a photo from the device camera.",
            "record-a-short-clip": "Request a short microphone recording.",
            "your-latest-screenshot-or-photo": "Get the latest screenshot or camera photo.",
            "find-photos-in-your-gallery": "Find and retrieve photos from the device gallery.",
            "everything-is-logged": "Review device activity and control agent requests.",
            "agent-notes": "Let a control agent add a note to Activity.",
            "the-api-explains-itself": "Read the device’s live operation guide and API description.",
            "agents-learn-what-s-new": "Find out when the device API has changed.",
            "report-a-bug-or-ask-for-a-feature": "Send a bug report or feature request.",
            "check-for-updates": "Check for a newer version of Bridgit.",
            "you-stay-in-control": "Control device access and control agent permissions.",
            "only-over-tailscale": "Limit connections to the private Tailscale network.",
            "questions-and-notices-to-you": "Receive notices and questions from a control agent and send answers.",
            "rich-html-cards": "View interactive HTML cards sent by a control agent.",
            "live-text-panel": "Follow a live stream of text on the device.",
            "use-bridgit-over-mcp": "Connect an MCP control agent to Bridgit.",
            "inbox": "Review questions, notices, files and shares.",
            "send-a-screenshot": "Capture the current device screen for a control agent.",
            "events-and-callbacks": "Read events or receive callbacks when activity occurs.",
            "open-a-link-or-app": "Open a suggested link or app with the owner’s participation.",
            "quick-settings-tile": "Pause or resume Bridgit from system controls.",
            "phone-status-and-context": "Read device status and context.",
            "location-details": "Request the device’s current location.",
            "weather": "Get weather for a location.",
            "flash-the-light": "Turn the device light on or off.",
            "phone-controls": "Adjust device sound, display or other system controls.",
            "app-settings": "Read or change permitted Bridgit settings.",
            "city-tour": "Use location and camera context for a guided tour.",
            "find-my-phone": "Locate the device by making it ring or vibrate.",
            "sensors": "Read device sensor measurements.",
            "videos-and-downloads": "Access selected videos or downloaded files.",
            "files-to-your-phone": "Receive a file from a control agent.",
            "share-to-an-agent": "Choose content to share with a control agent.",
            "listen-for-any-sounds": "Listen for sound events.",
            "wake-words": "Use a chosen wake word for a control agent.",
            "short-video-clips": "Record a short video clip.",
            "read-text-on-the-phone": "Recognize text on the device.",
            "transcribe-clips-on-the-phone": "Transcribe a microphone clip on the device.",
            "screen-sharing-as-a-fallback": "Share the device screen with an approved viewer.",
            "app-usage": "Read app usage summaries.",
            "health-connect": "Read permitted health records.",
            "read-notifications": "Read notifications received by the device.",
            "copy-to-clipboard": "Put approved text on the device clipboard.",
            "per-agent-permissions": "Choose what each control agent may use.",
            "reach-bridgit-without-tailscale-relay": "Connect through the relay when a control agent cannot join the private network.",
            "play-an-audio-file": "Play audio or speak text on the device.",
            "scheduled-actions": "Schedule device actions for later.",
            "messages-contacts-calendar-calls": "Work with messages, contacts, calendar or calls.",
            "location-history-and-places": "Keep location history or recognize places."
        ]
        return copy[anchor] ?? "Availability has not been reported."
    }
}
