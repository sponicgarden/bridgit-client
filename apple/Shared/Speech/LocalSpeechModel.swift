import AVFoundation
import BridgitCore
import BridgitSpeech
import Combine
import Foundation

/// Owner grants are durable per applet. Runtime suspension stops work without revoking them.
@MainActor final class LocalSpeechModel: ObservableObject {
    @Published private(set) var enabled: Set<String> = []
    @Published private(set) var cloudAllowed = Set<String>()
    @Published private(set) var speakerVolumeCeiling: Int
    @Published private(set) var preparing = false
    @Published var problem: String?
    var foreground = true { didSet { if !foreground { suspend() } } }
    typealias Registration = () async throws -> InstallClient
    private let registration: Registration
    var captureNotices = CaptureNotices.shared
    var packageForBudget: ((String) async throws -> [String: Any])?
    private var client: InstallClient?
    private let settings: UserDefaults?
    private var generation = UUID()
    private let device: AppleSpeechDevice
    private lazy var controller = SpeechController(device: device, transport: { [weak self] op, payload, applet in
        guard let self else { throw CancellationError() }
        let run = self.generation
        guard self.foreground, self.enabled.contains(applet) else { throw SpeechError("permission_denied", "Speech is unavailable in this state.") }
        if self.client == nil { self.client = try await self.registration() }
        try Task.checkCancellation()
        guard self.foreground, run == self.generation, self.enabled.contains(applet), op == "resolve" || self.cloudAllowed.contains(applet), let client = self.client else { throw SpeechError("permission_denied", "Speech access changed.") }
        guard let packageForBudget = self.packageForBudget else { throw DeviceAPIError(409, "budget_unconfirmed", "The applet store is unavailable.") }
        return try await client.speech(operation: op, payload: payload, applet: applet, package: packageForBudget(applet)) {
            guard self.foreground, run == self.generation, self.enabled.contains(applet), op == "resolve" || self.cloudAllowed.contains(applet) else {
                throw SpeechError("permission_denied", "Speech access changed.")
            }
        }
    }, authorize: { [weak self] _, applet, cloud in
        guard let self, self.foreground, self.enabled.contains(applet) else { throw SpeechError("permission_denied", "Speech requires this applet's owner permission and a foreground app.") }
        if cloud && !self.cloudAllowed.contains(applet) { throw SpeechError("speech_cloud_consent_required", "Allow cloud speech separately before sending text or audio.") }
    })
    init(registration: Registration? = nil, settings: UserDefaults? = nil) {
        let saved = settings ?? (AppleFixtureWorkspace.enabled ? nil : AppleFixtureWorkspace.defaults)
        self.settings = saved
        let storedCeiling = saved?.integer(forKey: "speaker.volume.ceiling") ?? 0
        let ceiling = HostSettingChange.speakerVolumeRange.contains(storedCeiling) ? storedCeiling : HostSettingChange.speakerVolumeDefault
        speakerVolumeCeiling = ceiling
        device = AppleSpeechDevice(volumeCeiling: Float(ceiling) / 100)
        let grants = Set((saved?.stringArray(forKey: "speech.enabled") ?? []).filter { $0.range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil })
        enabled = grants
        cloudAllowed = Set(saved?.stringArray(forKey: "speech.cloud") ?? []).intersection(grants)
        self.registration = registration ?? {
            let signer = try DeviceSigner.loadOrCreate(tag: (Bundle.main.bundleIdentifier ?? "cc.bridgit") + ".install-signing.v1")
            let client = InstallClient(signer: signer, outboundAccess: DeviceOutboundPolicy.access)
            _ = try await client.register(appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "local")
            return client
        }
    }
    func setSpeakerVolumeCeiling(_ value: Int) {
        guard HostSettingChange.speakerVolumeRange.contains(value), value != speakerVolumeCeiling else { return }
        device.setVolumeCeiling(Float(value) / 100)
        settings?.set(value, forKey: "speaker.volume.ceiling")
        speakerVolumeCeiling = value
    }
    func enable(applet: String, hearing: Bool, authorize: (() async throws -> Void)? = nil, commit: (() async throws -> Void)? = nil) async {
        guard !preparing else { return }; preparing = true; defer { preparing = false }; problem = nil
        let run = generation
        do {
            if hearing {
                let permitted = await AppleSpeechDevice.requestPermissions(hearing: true)
                guard permitted else { throw SpeechError("permission_denied", "Microphone and speech-recognition permissions are required.") }
            }
            try await authorize?()
            guard foreground, run == generation else { throw CancellationError() }
            if client == nil {
                client = try await registration()
            }
            try await authorize?()
            guard foreground, run == generation else { throw CancellationError() }
            try await commit?()
            guard foreground, run == generation else { throw CancellationError() }
            enabled.insert(applet); persist()
        } catch { problem = error.localizedDescription }
    }
    func disable(applet: String) { generation = UUID(); enabled.remove(applet); cloudAllowed.remove(applet); persist(); controller.stop(applet: applet) }
    func setCloud(applet: String, allowed: Bool) {
        if allowed, enabled.contains(applet) { cloudAllowed.insert(applet) }
        else { cloudAllowed.remove(applet); controller.stop(applet: applet) }
        persist()
    }
    func stop(applet: String? = nil) { controller.stop(applet: applet) }
    func suspend() { generation = UUID(); controller.stop() }
    private func persist() {
        settings?.set(enabled.sorted(), forKey: "speech.enabled")
        settings?.set(cloudAllowed.sorted(), forKey: "speech.cloud")
    }
    func stopMicrophoneClip() { controller.stopMicrophoneClip() }
    func recordMicrophoneClip(_ options: MicrophoneClipRequest,
                              authorize: @escaping @MainActor () async throws -> Void) async throws -> [String: Any] {
        do {
            return try await controller.microphoneClip(authorize: authorize) {
                try await self.device.captureClip(seconds: options.seconds, language: options.language,
                                                  transcribe: options.transcribe, returnAudio: options.returnAudio)
            }
        } catch let error as SpeechError {
            let status: Int
            switch error.code {
            case "permission_denied": status = 403
            case "payload_too_large": status = 413
            default: status = 409
            }
            throw DeviceAPIError(status, error.code, error.message)
        }
    }
    func readiness(language: String = Locale.current.identifier) -> [String: Any] { controller.readiness(language: language) }
    func run(operation: String, step: [String: Any], applet: String) async throws -> [String: Any] {
        do { return try await perform(operation: operation, step: step, applet: applet) }
        catch let error as SpeechError { throw DeviceAPIError(["permission_denied", "speech_cloud_consent_required"].contains(error.code) ? 403 : 409, error.code, error.message) }
    }
    private func perform(operation: String, step: [String: Any], applet: String) async throws -> [String: Any] {
        let cloud = step["allow_cloud"] as? Bool ?? false
        let language = step["language"] as? String ?? Locale.current.identifier
        if operation == "hear" {
            let seconds = step["duration_seconds"] as? Int ?? 5
            return try await controller.hear(seconds: seconds, language: language, allowCloud: cloud, applet: applet) {
                let token = try await self.captureNotices.begin("Applet " + applet + " is listening")
                let monitor = self.captureNotices.monitor {
                    self.controller.stop(applet: applet)
                }
                return { monitor.cancel(); self.captureNotices.end(token) }
            }
        }
        let text: String
        if let string = step["text"] as? String { text = string }
        else if let pieces = step["text"] as? [String] { text = pieces.joined() }
        else { throw SpeechError("invalid_parameter", "A speak step needs text.") }
        return try await controller.speak(text: text, language: language, allowCloud: cloud, applet: applet)
    }
}
