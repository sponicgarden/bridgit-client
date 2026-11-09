import AVFoundation
import Foundation
import Speech

/// Native iOS/macOS implementation. The app must declare microphone and speech usage descriptions.
/// All permission prompts are explicit owner actions. Recognition requires an installed offline model.
@MainActor public final class AppleSpeechDevice: NSObject, SpeechDevice, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate, AVAudioRecorderDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var recorder: AVAudioRecorder?
    private var recordingComplete: Bool?
    private var player: AVAudioPlayer?
    private var recognition: SFSpeechRecognitionTask?
    private var currentUtterance: AVSpeechUtterance?
    private var finished = false
    private var failed = false
    public private(set) var volumeCeiling: Float
    public init(volumeCeiling: Float = 0.7) {
        self.volumeCeiling = min(max(volumeCeiling, 0), 1)
        super.init(); synthesizer.delegate = self
    }
    public func setVolumeCeiling(_ requested: Float) {
        let next = min(max(requested, 0), 1)
        if let utterance = currentUtterance, utterance.volume > next, synthesizer.isSpeaking {
            // AVSpeechUtterance volume cannot be reliably changed during synthesis.
            synthesizer.stopSpeaking(at: .immediate)
            failed = true
            finished = true
        }
        volumeCeiling = next
        player?.volume = next
    }
    public static func requestPermissions(hearing: Bool) async -> Bool {
        guard hearing else { return true }
        let microphone = await AVCaptureDevice.requestAccess(for: .audio)
        guard microphone else { return false }
        let status = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
        return status == .authorized
    }
    public func readiness(language: String) -> [String: Any] {
        let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language))
        let microphone = AVCaptureDevice.authorizationStatus(for: .audio)
        let recognition = SFSpeechRecognizer.authorizationStatus()
        let microphoneState: String
        switch microphone {
        case .notDetermined: microphoneState = "not_determined"
        case .restricted: microphoneState = "restricted"
        case .denied: microphoneState = "denied"
        case .authorized: microphoneState = "authorized"
        @unknown default: microphoneState = "unknown"
        }
        let recognitionState: String
        switch recognition {
        case .notDetermined: recognitionState = "not_determined"
        case .restricted: recognitionState = "restricted"
        case .denied: recognitionState = "denied"
        case .authorized: recognitionState = "authorized"
        @unknown default: recognitionState = "unknown"
        }
        return ["microphone_allowed": microphone == .authorized, "recognition_allowed": recognition == .authorized,
                "microphone_authorization": microphoneState, "recognition_authorization": recognitionState,
                "hearing_on_device": microphone == .authorized && recognition == .authorized && recognizer?.isAvailable == true && recognizer?.supportsOnDeviceRecognition == true,
                "speaking_on_device": AVSpeechSynthesisVoice(language: SpeechController.languageTag(language)) != nil]
    }
    private func session(recording: Bool) throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        // Ambient playback respects the hardware silent switch and mixes with other sound.
        // Hearing is foreground-only, authorized by the host before this adapter is called.
        try session.setCategory(recording ? .record : .ambient, mode: recording ? .measurement : .default)
        try session.setActive(true)
        #endif
    }
    private func releaseSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
    public func record(seconds: Int) async throws -> Data {
        try Task.checkCancellation()
        guard (1...30).contains(seconds) else { throw SpeechError("invalid_parameter", "Record 1–30 seconds.") }
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else { throw SpeechError("permission_denied", "The owner must allow the microphone first.") }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-speech-\(UUID()).wav")
        defer { recorder?.stop(); recorder = nil; try? FileManager.default.removeItem(at: file); releaseSession() }
        try session(recording: true)
        let value = try AVAudioRecorder(url: file, settings: [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsFloatKey: false])
        recorder = value
        try Task.checkCancellation()
        guard value.record(forDuration: Double(seconds)) else { throw SpeechError("speech_unavailable", "The microphone could not start.") }
        try await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
        try Task.checkCancellation(); value.stop()
        let audio = try AVAudioFile(forReading: file, commonFormat: .pcmFormatInt16, interleaved: true)
        guard audio.processingFormat.sampleRate == 16000, audio.processingFormat.channelCount == 1,
              audio.length > 0, audio.length <= 30 * 16000,
              let buffer = AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: AVAudioFrameCount(audio.length)) else { throw SpeechError("invalid_audio", "The microphone returned an unsupported audio format.") }
        try audio.read(into: buffer)
        guard let samples = buffer.int16ChannelData?[0] else { throw SpeechError("invalid_audio", "No microphone samples were returned.") }
        return Self.wav(pcm: Data(bytes: samples, count: Int(buffer.frameLength) * 2), sampleRate: 16000)
    }
    public static func wav(pcm: Data, sampleRate: Int) -> Data {
        var result = Data("RIFF".utf8)
        func u32(_ n: Int) { var v = UInt32(n).littleEndian; withUnsafeBytes(of: &v) { result.append(contentsOf: $0) } }
        func u16(_ n: Int) { var v = UInt16(n).littleEndian; withUnsafeBytes(of: &v) { result.append(contentsOf: $0) } }
        u32(pcm.count + 36); result.append(Data("WAVEfmt ".utf8)); u32(16); u16(1); u16(1)
        u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16); result.append(Data("data".utf8)); u32(pcm.count); result.append(pcm)
        return result
    }
    public func transcribe(wav: Data, language: String) async throws -> String {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else { throw SpeechError("permission_denied", "The owner must allow speech recognition first.") }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)), recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            throw SpeechError("speech_unavailable", "No installed offline recognizer supports this language. No cloud fallback was used.")
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-transcribe-\(UUID()).wav")
        try wav.write(to: file, options: [.atomic, .completeFileProtection])
        defer { recognition?.cancel(); recognition = nil; try? FileManager.default.removeItem(at: file) }
        return try await transcribe(file: file, with: recognizer)
    }
    public func captureClip(seconds: Int, language: String?, transcribe: Bool, returnAudio: Bool) async throws -> [String: Any] {
        try Task.checkCancellation()
        guard (1...120).contains(seconds), returnAudio || transcribe else {
            throw SpeechError("invalid_parameter", "Supply 1–120 seconds and request audio or transcription.")
        }
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            throw SpeechError("permission_denied", "The owner must allow the microphone first.")
        }
        var recognizer: SFSpeechRecognizer?
        if transcribe {
            guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
                throw SpeechError("permission_denied", "The owner must allow speech recognition first.")
            }
            guard let selected = SFSpeechRecognizer(locale: Locale(identifier: language ?? Locale.current.identifier)),
                  selected.isAvailable, selected.supportsOnDeviceRecognition else {
                throw SpeechError("not_supported", "No installed offline recognizer supports this language. Nothing was recorded.")
            }
            recognizer = selected
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-clip-" + UUID().uuidString, isDirectory: true)
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o700]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.complete
        #endif
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: attributes)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("clip.m4a")
        let started = try await recordClip(to: file, seconds: seconds)
        let audio = try AVAudioFile(forReading: file)
        let rate = audio.fileFormat.sampleRate
        let duration = Double(audio.length) / rate
        guard rate.isFinite, rate > 0, duration.isFinite, duration >= Double(seconds) - 0.25, duration <= Double(seconds) + 1 else {
            throw SpeechError("capture_failed", "The microphone did not complete the requested recording.")
        }
        let bytes = try Data(contentsOf: file)
        guard !bytes.isEmpty, bytes.count <= 1_500_000 else {
            throw SpeechError("payload_too_large", "The microphone returned an invalid or oversized clip.")
        }
        var result: [String: Any] = ["recorded_at": ISO8601DateFormatter().string(from: started),
            "duration_seconds": Int(duration.rounded()), "format": "m4a", "sample_rate_hz": Int(rate), "size_bytes": bytes.count]
        if returnAudio { result["data_base64"] = bytes.base64EncodedString() }
        if let recognizer {
            defer { recognition?.cancel(); recognition = nil }
            result["on_device"] = true
            do {
                let transcript = try await self.transcribe(file: file, with: recognizer)
                guard transcript.utf8.count <= 32_000 else { throw SpeechError("invalid_transcript", "The offline transcript exceeded its size limit.") }
                result["transcript"] = transcript
            } catch let error as SpeechError where returnAudio {
                try Task.checkCancellation()
                result["transcript"] = NSNull()
                result["transcript_error"] = error.message
            }
        }
        try Task.checkCancellation()
        return result
    }
    private func recordClip(to file: URL, seconds: Int) async throws -> Date {
        defer { recorder?.stop(); recorder = nil; releaseSession() }
        try session(recording: true)
        let value = try AVAudioRecorder(url: file, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16_000, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 48_000])
        value.delegate = self
        recorder = value
        recordingComplete = nil
        try Task.checkCancellation()
        let started = Date()
        guard value.record(forDuration: Double(seconds)) else {
            throw SpeechError("capture_failed", "The microphone could not start.")
        }
        let deadline = ContinuousClock.now + .seconds(seconds + 2)
        while recordingComplete == nil {
            try Task.checkCancellation()
            guard ContinuousClock.now < deadline else { throw SpeechError("capture_failed", "The microphone did not finish recording.") }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        guard recordingComplete == true else { throw SpeechError("capture_failed", "The microphone failed to record the clip.") }
        return started
    }
    private func transcribe(file: URL, with recognizer: SFSpeechRecognizer) async throws -> String {
        let request = SFSpeechURLRecognitionRequest(url: file)
        request.requiresOnDeviceRecognition = true; request.shouldReportPartialResults = false
        var transcript: String?, failure: Error?
        recognition = recognizer.recognitionTask(with: request) { result, error in
            Task { @MainActor in
                if let result, result.isFinal { transcript = result.bestTranscription.formattedString }
                if let error { failure = error }
            }
        }
        let deadline = Date().addingTimeInterval(20)
        while transcript == nil && failure == nil {
            try Task.checkCancellation()
            if Date() >= deadline { throw SpeechError("speech_timeout", "Offline recognition exceeded 20 seconds.") }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        if failure != nil { throw SpeechError("speech_unavailable", "Offline recognition failed; no cloud fallback was used.") }
        return transcript ?? ""
    }
    public func speak(text: String, language: String) async throws {
        try Task.checkCancellation()
        guard !text.isEmpty, text.utf16.count <= 600 else { throw SpeechError("invalid_parameter", "Use 1–600 characters.") }
        guard let voice = AVSpeechSynthesisVoice(language: SpeechController.languageTag(language)) else { throw SpeechError("speech_unavailable", "No installed voice supports this language.") }
        try session(recording: false)
        defer { currentUtterance = nil; synthesizer.stopSpeaking(at: .immediate); releaseSession() }
        finished = false; failed = false
        let utterance = AVSpeechUtterance(string: text); utterance.voice = voice; utterance.volume = volumeCeiling
        currentUtterance = utterance
        try Task.checkCancellation()
        synthesizer.speak(utterance)
        try await waitForPlayback()
    }
    public func play(audio: Data) async throws {
        try Task.checkCancellation()
        guard !audio.isEmpty, audio.count <= 2_000_000 else { throw SpeechError("invalid_audio", "Speech audio is empty or too large.") }
        try session(recording: false)
        defer { player?.stop(); player = nil; releaseSession() }
        let value = try AVAudioPlayer(data: audio); player = value; value.delegate = self; value.volume = volumeCeiling
        guard value.duration.isFinite, value.duration <= 60 else { throw SpeechError("invalid_audio", "Speech output is limited to 60 seconds.") }
        finished = false; failed = false
        try Task.checkCancellation()
        guard value.play() else { throw SpeechError("speech_unavailable", "Speech playback could not start.") }
        try await waitForPlayback()
    }
    private func waitForPlayback() async throws {
        let deadline = Date().addingTimeInterval(65)
        while !finished {
            try Task.checkCancellation()
            if Date() >= deadline { throw SpeechError("speech_timeout", "Speech playback exceeded its deadline.") }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        if failed { throw SpeechError("speech_unavailable", "Speech playback did not complete.") }
    }
    public func stop() {
        currentUtterance = nil
        recorder?.stop(); recognition?.cancel(); synthesizer.stopSpeaking(at: .immediate); player?.stop()
        failed = true; finished = true; releaseSession()
    }
    nonisolated public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { Task { @MainActor in guard self.currentUtterance === utterance else { return }; self.finished = true } }
    nonisolated public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { Task { @MainActor in guard self.currentUtterance === utterance else { return }; self.failed = true; self.finished = true } }
    nonisolated public func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            guard self.recorder === recorder else { return }
            self.recordingComplete = flag
        }
    }
    nonisolated public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in guard self.player === player else { return }; self.failed = !flag; self.finished = true } }
    nonisolated public func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { Task { @MainActor in guard self.player === player else { return }; self.failed = true; self.finished = true } }
}
