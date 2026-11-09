import XCTest
@testable import BridgitSpeech

@MainActor final class TestDevice: SpeechDevice {
    var records = 0, spoken = 0, played = 0, stops = 0
    var delay: UInt64 = 0
    func readiness(language: String) -> [String: Any] { ["hearing_on_device": true] }
    func record(seconds: Int) async throws -> Data { records += 1; if delay > 0 { try await Task.sleep(nanoseconds: delay) }; return AppleSpeechDevice.wav(pcm: Data(repeating: 0, count: 32000), sampleRate: 16000) }
    func transcribe(wav: Data, language: String) async throws -> String { "hello" }
    func speak(text: String, language: String) async throws { spoken += 1 }
    func play(audio: Data) async throws { played += 1 }
    func stop() { stops += 1 }
}

final class SpeechControllerTests: XCTestCase {
    @MainActor func testNativeHearingUsesRegistryAndKeepsAudioOnDevice() async throws {
        let device = TestDevice(); var calls = [String]()
        let speech = SpeechController(device: device, transport: { op, _, _ in calls.append(op); return ["model":"device", "processing":"device"] }, authorize: { _,_,_ in })
        let result = try await speech.hear(applet: "test")
        XCTAssertEqual(result["transcript"] as? String,"hello"); XCTAssertEqual(calls,["resolve"]); XCTAssertEqual(device.records,1)
    }
    @MainActor func testCloudConsentBeforeRecordingAndNoImplicitFallback() async throws {
        let device = TestDevice(); var calls = [String]()
        let speech = SpeechController(device:device, transport:{op,_,_ in calls.append(op); return ["model":"whisper", "processing":"cloud"]}, authorize:{_,_,_ in })
        do { _ = try await speech.hear(applet:"test"); XCTFail("consent missing") }
        catch { XCTAssertEqual((error as? SpeechError)?.code,"speech_cloud_consent_required") }
        XCTAssertEqual(device.records,0); XCTAssertEqual(calls,["resolve"])
    }
    @MainActor func testRegistrySwitchChangesSpeakingExecution() async throws {
        let device = TestDevice(); var cloud = false; var received = [String:Any]()
        let speech = SpeechController(device:device, transport:{op,payload,_ in
            if op == "resolve" { return ["model":cloud ? "aura" : "device", "processing":cloud ? "cloud" : "device"] }
            received = payload; return ["audio_base64":Data([1,2,3]).base64EncodedString(),"content_type":"audio/mpeg","model":"aura"]
        }, authorize:{_,_,_ in })
        _ = try await speech.speak(text:"hello",applet:"test"); cloud = true
        let result = try await speech.speak(text:"hello",allowCloud:true,applet:"test")
        XCTAssertEqual(device.spoken,1); XCTAssertEqual(device.played,1); XCTAssertEqual(received["expected_model"] as? String,"aura"); XCTAssertNil(result["audio_base64"])
    }
    @MainActor func testStopCancelsRecordingAndDoesNotStopAnotherApplet() async throws {
        let device=TestDevice(); device.delay=5_000_000_000
        let speech=SpeechController(device:device,transport:{_,_,_ in ["model":"device","processing":"device"]},authorize:{_,_,_ in })
        let task=Task { try await speech.hear(applet:"first") }
        while device.records == 0 { await Task.yield() }
        speech.stop(applet:"second"); XCTAssertEqual(device.stops,0)
        do { _ = try await speech.speak(text:"hello",applet:"second"); XCTFail("overlap allowed") } catch { XCTAssertEqual((error as? SpeechError)?.code,"speech_busy") }
        speech.stop(applet:"first")
        do { _ = try await task.value; XCTFail("cancel ignored") } catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(device.stops,1); XCTAssertEqual(speech.readiness()["busy"] as? Bool,false)
    }
    @MainActor func testHostGrantRevocationCancelsInFlightTurn() async throws {
        let device=TestDevice(); device.delay=5_000_000_000; var granted=true
        let speech=SpeechController(device:device,transport:{_,_,_ in ["model":"device","processing":"device"]},authorize:{_,_,_ in if !granted { throw SpeechError("permission_denied","revoked") } })
        let task=Task { try await speech.hear(applet:"test") }
        while device.records == 0 { await Task.yield() }; granted=false
        do { _ = try await task.value; XCTFail("revocation ignored") } catch {}
        XCTAssertEqual(device.stops,1)
    }
    @MainActor func testMicrophoneClipExcludesSpeechAndCancelsWhenGrantIsRevoked() async throws {
        let device = TestDevice()
        var started = false, granted = true
        let speech = SpeechController(device: device, transport: { _, _, _ in
            XCTFail("Microphone capture must not call registry speech services.")
            return [:]
        }, authorize: { _, _, _ in })
        let clip = Task {
            try await speech.microphoneClip(authorize: {
                guard granted else { throw SpeechError("permission_denied", "Revoked") }
            }, capture: {
                started = true
                try await Task.sleep(nanoseconds: 3_000_000_000)
                return ["format": "m4a"]
            })
        }
        while !started { await Task.yield() }
        do { _ = try await speech.hear(applet: "test"); XCTFail("Overlapping microphone work.") }
        catch { XCTAssertEqual((error as? SpeechError)?.code, "speech_busy") }
        granted = false
        do { _ = try await clip.value; XCTFail("Revoked clip completed.") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(device.stops, 1)
        XCTAssertEqual(speech.readiness()["busy"] as? Bool, false)
    }

    @MainActor func testBoundsAndCanonicalWav() async throws {
        let device=TestDevice(); let speech=SpeechController(device:device,transport:{_,_,_ in XCTFail("invalid input reached backend"); return [:]},authorize:{_,_,_ in })
        do { _ = try await speech.hear(seconds:31,applet:"test"); XCTFail() } catch {}
        do { _ = try await speech.speak(text:String(repeating:"x",count:601),applet:"test"); XCTFail() } catch {}
        let wav=AppleSpeechDevice.wav(pcm:Data(repeating:0,count:32000),sampleRate:16000)
        XCTAssertEqual(wav.count,32044); XCTAssertEqual(String(data:wav.prefix(4),encoding:.utf8),"RIFF")
        XCTAssertEqual(SpeechController.languageTag("en_US"),"en-US")
    }
    @MainActor func testStopDuringUncooperativeResolutionCannotStartMicrophone() async throws {
        let device = TestDevice()
        var continuation: CheckedContinuation<[String: Any], Never>?
        let speech = SpeechController(device: device, transport: { _,_,_ in
            await withCheckedContinuation { continuation = $0 }
        }, authorize: { _,_,_ in })
        let task = Task { try await speech.hear(applet: "test") }
        while continuation == nil { await Task.yield() }
        speech.stop(applet: "test")
        continuation?.resume(returning: ["model":"device", "processing":"device"])
        do { _ = try await task.value; XCTFail("Stop was ignored") } catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(device.records, 0)
    }

    @MainActor func testHearingNoticeWrapsOnlyActualRecording() async throws {
        let device = TestDevice()
        var started = 0, finished = 0
        let speech = SpeechController(device: device, transport: { _, _, _ in
            ["model": "device", "processing": "device"]
        }, authorize: { _, _, _ in })
        _ = try await speech.hear(applet: "test", beforeRecord: {
            started += 1
            return { finished += 1 }
        })
        XCTAssertEqual(device.records, 1)
        XCTAssertEqual(started, 1)
        XCTAssertEqual(finished, 1)
        do {
            _ = try await speech.hear(applet: "test", beforeRecord: { throw CancellationError() })
            XCTFail("Failed notice must prevent recording")
        } catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(device.records, 1)
    }

}
