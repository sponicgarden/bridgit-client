import Foundation

public struct SpeechError: LocalizedError {
    public let code: String
    public let message: String
    public var errorDescription: String? { message }
    public init(_ code: String, _ message: String) { self.code = code; self.message = message }
}

public struct SpeechSelection: Equatable {
    public let model: String
    public let cloud: Bool
    public init(_ json: [String: Any]) throws {
        guard let model = json["model"] as? String, !model.isEmpty,
              let processing = json["processing"] as? String, ["device", "cloud"].contains(processing) else { throw SpeechError("speech_malformed", "Invalid speech model selection.") }
        self.model = model; self.cloud = processing == "cloud"
    }
}

@MainActor public protocol SpeechDevice: AnyObject {
    func readiness(language: String) -> [String: Any]
    func record(seconds: Int) async throws -> Data
    func transcribe(wav: Data, language: String) async throws -> String
    func speak(text: String, language: String) async throws
    func play(audio: Data) async throws
    func stop()
}

/// One bounded turn per device. The host supplies signed transport and rechecks owner/agent grants.
/// Construction does not ask for permissions or enroll the device. No automatic retries or cloud fallback.
@MainActor public final class SpeechController {
    public typealias Transport = (_ operation: String, _ payload: [String: Any], _ applet: String) async throws -> [String: Any]
    public typealias Authorize = (_ operation: String, _ applet: String, _ cloud: Bool) throws -> Void
    public static let maxSeconds = 30
    public static let maxCharacters = 600
    private let device: SpeechDevice
    private let transport: Transport
    private let authorize: Authorize
    private var activeCloud = false
    private var activeOperation = "hear"
    private var active: (id: UUID, applet: String, task: Task<[String: Any], Error>)?
    public init(device: SpeechDevice, transport: @escaping Transport, authorize: @escaping Authorize) {
        self.device = device; self.transport = transport; self.authorize = authorize
    }
    public func readiness(language: String = Locale.current.identifier) -> [String: Any] {
        var value = device.readiness(language: language)
        value["mode"] = "turn"; value["max_seconds"] = Self.maxSeconds; value["max_chars"] = Self.maxCharacters
        value["busy"] = active != nil; return value
    }
    public func hear(seconds: Int = 5, language: String = Locale.current.identifier, allowCloud: Bool = false, applet: String,
                     beforeRecord: (@MainActor () async throws -> (@MainActor () -> Void))? = nil) async throws -> [String: Any] {
        guard (1...Self.maxSeconds).contains(seconds) else { throw SpeechError("invalid_parameter", "Hearing turns last 1–30 seconds.") }
        return try await turn(operation: "hear", applet: applet) {
            try self.authorize("hear", applet, false)
            let selected = try await self.selection("hear", applet: applet, allowCloud: allowCloud)
            try Task.checkCancellation(); self.activeCloud = selected.cloud
            try self.authorize("hear", applet, selected.cloud)
            let finishNotice = try await beforeRecord?()
            let wav: Data
            do {
                try Task.checkCancellation()
                try self.authorize("hear", applet, selected.cloud)
                wav = try await self.device.record(seconds: seconds)
            }
            catch { finishNotice?(); throw error }
            finishNotice?()
            try Task.checkCancellation(); try self.authorize("hear", applet, selected.cloud)
            if selected.cloud {
                let result = try await self.transport("hear", ["audio_base64": wav.base64EncodedString(), "language": Self.languageTag(language), "allow_cloud": true, "expected_model": selected.model], applet)
                guard let transcript = result["transcript"] as? String, transcript.count <= 8000 else { throw SpeechError("speech_malformed", "No bounded transcript was returned.") }
                return result
            }
            let text = try await self.device.transcribe(wav: wav, language: language)
            return ["transcript": text, "model": selected.model, "processing": "device", "cost_usd": 0]
        }
    }
    public func speak(text: String, language: String = Locale.current.identifier, allowCloud: Bool = false, applet: String) async throws -> [String: Any] {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= Self.maxCharacters else { throw SpeechError("invalid_parameter", "Speech turns take 1–600 characters.") }
        return try await turn(operation: "speak", applet: applet) {
            try self.authorize("speak", applet, false)
            let selected = try await self.selection("speak", applet: applet, allowCloud: allowCloud)
            try Task.checkCancellation(); self.activeCloud = selected.cloud
            try self.authorize("speak", applet, selected.cloud)
            if selected.cloud {
                var reply = try await self.transport("speak", ["text": text, "language": Self.languageTag(language), "allow_cloud": true, "expected_model": selected.model], applet)
                guard let type = reply["content_type"] as? String, ["audio/mpeg", "audio/wav"].contains(type), let b64 = reply["audio_base64"] as? String, b64.utf8.count <= 2_666_672,
                      let audio = Data(base64Encoded: b64), !audio.isEmpty, audio.count <= 2_000_000 else { throw SpeechError("speech_malformed", "No bounded speech audio was returned.") }
                try Task.checkCancellation(); try self.authorize("speak", applet, true)
                try await self.device.play(audio: audio)
                reply.removeValue(forKey: "audio_base64"); reply["status"] = "completed"; return reply
            }
            try await self.device.speak(text: text, language: language)
            return ["model": selected.model, "processing": "device", "cost_usd": 0, "status": "completed"]
        }
    }
    public func microphoneClip(authorize: @escaping @MainActor () async throws -> Void,
                               capture: @escaping @MainActor () async throws -> [String: Any]) async throws -> [String: Any] {
        try await turn(operation: "clip", applet: "", timeout: 145, reauthorize: authorize) {
            try await authorize()
            let result = try await capture()
            try await authorize()
            return result
        }
    }
    public func stopMicrophoneClip() {
        if activeOperation == "clip" { stop() }
    }
    public func stop(applet: String? = nil) {
        guard let current = active, applet == nil || current.applet == applet else { return }
        current.task.cancel(); device.stop()
        // Keep the slot until cleanup completes; a new turn cannot overlap an old microphone/player.
    }
    private func selection(_ operation: String, applet: String, allowCloud: Bool) async throws -> SpeechSelection {
        let selected = try SpeechSelection(try await transport("resolve", ["operation": operation], applet))
        if selected.cloud && !allowCloud { throw SpeechError("speech_cloud_consent_required", "Cloud speech needs explicit permission. Nothing was recorded or sent.") }
        return selected
    }
    private func turn(operation: String, applet: String, timeout: TimeInterval = 90, reauthorize: (@MainActor () async throws -> Void)? = nil, work: @escaping @MainActor () async throws -> [String: Any]) async throws -> [String: Any] {
        guard active == nil else { throw SpeechError("speech_busy", "Another speech turn is active.") }
        activeOperation = operation; activeCloud = false
        let id = UUID()
        let task = Task { @MainActor in let result = try await work(); try Task.checkCancellation(); return result }
        active = (id, applet, task)
        // Monitor current host permissions, app lifecycle and a total turn deadline while awaiting callbacks.
        let monitor = Task { @MainActor in
            let deadline = Date().addingTimeInterval(timeout)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 200_000_000)
                if Task.isCancelled { return }
                do {
                    if Date() >= deadline { throw SpeechError("speech_timeout", "The speech turn exceeded its deadline.") }
                    if let reauthorize { try await reauthorize() }
                    else { try self.authorize(self.activeOperation, applet, self.activeCloud) }
                } catch { if self.active?.id == id { task.cancel(); self.device.stop() }; return }
            }
        }
        defer { monitor.cancel(); if active?.id == id { active = nil } }
        return try await withTaskCancellationHandler(operation: {
            let reply = try await task.value; try Task.checkCancellation(); return reply
        }, onCancel: { task.cancel(); Task { @MainActor in if self.active?.id == id { self.device.stop() } } })
    }
    public static func languageTag(_ value: String) -> String { value.replacingOccurrences(of: "_", with: "-") }
}
