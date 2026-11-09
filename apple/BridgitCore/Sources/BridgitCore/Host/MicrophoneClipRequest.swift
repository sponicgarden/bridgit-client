import CoreFoundation
import Foundation

public struct MicrophoneClipRequest {
    public static let maximumSeconds = 120
    public static let longJobTimeoutSeconds = 150
    public let seconds: Int
    public let transcribe: Bool
    public let returnAudio: Bool
    public let language: String?
    // Allow the native recorder to finalize and the relay to start delivery. Offline
    // transcription has its own 20-second bound and uses the same job deadline.
    var minimumJobSeconds: Int { seconds + 3 + (transcribe ? 20 : 0) }

    public init(_ request: HostRequest) throws {
        let body = request.body
        guard Set(request.query.keys).isSubset(of: ["store"]),
              Set(body.keys).isSubset(of: ["duration_seconds", "transcribe", "return_audio", "language", "store"]),
              let duration = body["duration_seconds"] as? NSNumber,
              CFGetTypeID(duration) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: duration.objCType)),
              (1...Self.maximumSeconds).contains(duration.intValue) else {
            throw DeviceAPIError(422, "invalid_parameter", "Supply duration_seconds as a whole number from 1 to 120.")
        }
        seconds = duration.intValue
        func boolean(_ key: String, default fallback: Bool) throws -> Bool {
            guard let input = body[key] else { return fallback }
            guard let value = input as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else {
                throw DeviceAPIError(422, "invalid_parameter", key + " must be a boolean.")
            }
            return value.boolValue
        }
        transcribe = try boolean("transcribe", default: false)
        returnAudio = try boolean("return_audio", default: true)
        guard returnAudio || transcribe else {
            throw DeviceAPIError(422, "invalid_parameter", "return_audio=false requires transcribe=true.")
        }
        if let input = body["language"] {
            guard let value = input as? String, value.utf8.count <= 64 else {
                throw DeviceAPIError(422, "invalid_parameter", "language must be a language tag.")
            }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.isEmpty || trimmed.range(of: "^[A-Za-z0-9]+([-_][A-Za-z0-9]+)*$", options: .regularExpression) != nil else {
                throw DeviceAPIError(422, "invalid_parameter", "language must be a language tag.")
            }
            language = trimmed.isEmpty ? nil : trimmed
        } else {
            language = nil
        }
        for input in [body["store"], request.query["store"]].compactMap({ $0 }) where !(input is NSNull) {
            let destinations: [String]
            if let list = input as? [String] {
                destinations = list
            } else if let value = input as? String {
                destinations = value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            } else {
                throw DeviceAPIError(422, "invalid_parameter", "store must be a list of capture destinations.")
            }
            guard destinations.isEmpty || destinations == ["none"] else {
                throw DeviceAPIError(422, "invalid_parameter", "This host returns microphone clips without keeping or uploading them. Use store: [].")
            }
        }
    }
}

extension HostJob {
    static let ordinaryTimeoutMilliseconds: Int64 = 120_000

    func microphoneClipOptions(for agent: HostAgent) -> MicrophoneClipRequest? {
        guard mode != "direct", agent.allows(.microphoneRecord) else { return nil }
        let clip: HostRequest
        if request.method == "POST", request.path == "/v1/mic/record" {
            clip = request
        } else {
            guard request.method == "POST", request.path == "/v1/mcp", request.query.isEmpty,
                  agent.allows(.deviceRead), request.body["jsonrpc"] as? String == "2.0",
                  request.body["method"] as? String == "tools/call", request.body["id"] != nil,
                  let parameters = request.body["params"] as? [String: Any],
                  parameters["name"] as? String == "mic_record",
                  let arguments = parameters["arguments"] as? [String: Any],
                  let operation = HostAPIContract.operations.first(where: { $0.name == "mic_record" }),
                  let nested = try? operation.request(arguments) else { return nil }
            clip = nested
        }
        return try? MicrophoneClipRequest(clip)
    }

    func timeoutMilliseconds(for agent: HostAgent) -> Int64 {
        guard let options = microphoneClipOptions(for: agent),
              options.minimumJobSeconds * 1_000 >= Self.ordinaryTimeoutMilliseconds else { return Self.ordinaryTimeoutMilliseconds }
        return Int64(MicrophoneClipRequest.longJobTimeoutSeconds) * 1_000
    }
}
