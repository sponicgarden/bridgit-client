import Foundation

/// Receiver content is bounded independently of request limits. No response headers escape the transport.
public struct ConnectionHTTPResponse: Sendable {
    public let status: Int
    public let body: Data
    public let truncated: Bool
    public init(status: Int, body: Data = Data(), truncated: Bool = false) {
        self.status = status
        self.body = Data(body.prefix(16 * 1024))
        self.truncated = truncated || body.count > 16 * 1024
    }
    func result(for row: OwnerConnection) -> [String: Any] {
        var out: [String: Any] = ["status": status, "queued": false]
        guard !body.isEmpty else { return out }
        let scrubber = ConnectionReplyRedactor(row)
        if !truncated, let json = try? JSONSerialization.jsonObject(with: body), json is [String: Any] || json is [Any] {
            let cleaned = scrubber.clean(json)
            if let encoded = try? JSONSerialization.data(withJSONObject: cleaned), encoded.count <= 16 * 1024 {
                out["reply"] = cleaned
                return out
            }
        }
        // Scrub the entire bounded byte prefix BEFORE truncating, so a cut inside a known secret cannot expose its prefix.
        let text = scrubber.text(String(decoding: body, as: UTF8.self), networkTail: truncated)
        out["reply_text"] = String(text.prefix(2000))
        return out
    }
}

/// Deliberate Apple behavior: literal configured credentials/addresses are removed from reflected replies.
/// This does not claim to recognize malicious transformations or arbitrary remote encodings.
private struct ConnectionReplyRedactor {
    let secrets: [String]
    let pattern: NSRegularExpression?
    init(_ row: OwnerConnection) {
        // Actual configured credential values must be removed even when they are short.
        var credentials = [row.address, row.signingSecret] + Array(row.headers.values) + Array(row.extra.values)
        if let previous = row.previousSecret { credentials.append(previous) }
        for value in Array(credentials) {
            if value.hasPrefix("whsec_") { credentials.append(String(value.dropFirst(6))) }
            if value.lowercased().hasPrefix("bearer ") || value.lowercased().hasPrefix("basic "), let space = value.firstIndex(of: " ") {
                credentials.append(String(value[value.index(after: space)...]))
            }
        }
        var fragments: [String] = []
        if let u = URLComponents(string: row.address) {
            fragments += [u.host, u.path, u.percentEncodedPath].compactMap { $0 }.filter { !$0.isEmpty && $0 != "/" }
            credentials += (u.queryItems ?? []).compactMap(\.value)
            fragments += u.path.split(separator: "/").map(String.init)
        }
        secrets = Set(credentials.filter { !$0.isEmpty } + fragments.filter { $0.count >= 4 }).sorted { $0.count > $1.count }
        pattern = try? NSRegularExpression(pattern: secrets.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|"))
    }
    func text(_ value: String, networkTail: Bool = false) -> String {
        var source = value
        var cut = false
        if networkTail {
            // Only an actually truncated network prefix can contain a cut credential.
            // KMP computes the longest suffix matching a secret prefix in linear time.
            let input = Array(value.utf16)
            var overlap = 0
            for secret in secrets {
                let key = Array(secret.utf16)
                guard key.count > 1 else { continue }
                let tail = Array(input.suffix(key.count - 1))
                let sequence = key.map(Int.init) + [-1] + tail.map(Int.init)
                var prefix = Array(repeating: 0, count: sequence.count)
                for i in 1..<sequence.count {
                    var j = prefix[i - 1]
                    while j > 0 && sequence[i] != sequence[j] { j = prefix[j - 1] }
                    if sequence[i] == sequence[j] { j += 1 }
                    prefix[i] = j
                }
                overlap = max(overlap, prefix.last ?? 0)
            }
            if overlap > 0 { source = String(decoding: input.dropLast(overlap), as: UTF16.self); cut = true }
        }
        // Replace once, so short secrets cannot recursively expand the redaction marker.
        guard let pattern else { return "[redacted]" }
        let result = pattern.stringByReplacingMatches(in: source, range: NSRange(source.startIndex..., in: source), withTemplate: "[redacted]")
        return result + (cut ? "[redacted]" : "")
    }
    func clean(_ value: Any) -> Any {
        if let value = value as? String { return text(value) }
        if let value = value as? [Any] { return value.map(clean) }
        if let value = value as? [String: Any] {
            var out: [String: Any] = [:]
            for key in value.keys.sorted() { out[text(key)] = clean(value[key]!) }
            return out
        }
        if let value = value as? NSNumber, secrets.contains(value.stringValue) { return "[redacted]" }
        return value
    }
}

/// Incremental HTTP/1 response framing; memory stays bounded even for an unbounded receiver.
struct ConnectionHTTPReplyParser {
    private var buffer = Data(), body = Data()
    private var status: Int?, length: Int?, chunked = false, remaining: Int?, needsCRLF = false
    private var informational = 0, endingChunks = false, trailerBytes = 0
    private let limit = 16 * 1024
    mutating func append(_ data: Data, complete: Bool) throws -> ConnectionHTTPResponse? {
        buffer.append(data)
        while status == nil {
            guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                guard buffer.count <= limit, !complete else { throw invalid() }; return nil
            }
            guard end.lowerBound - buffer.startIndex <= limit else { throw invalid() }
            let lines = String(decoding: buffer[..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
            buffer.removeSubrange(..<end.upperBound)
            let first = lines[0].split(separator: " ")
            guard first.count >= 2, ["HTTP/1.0", "HTTP/1.1"].contains(String(first[0])), first[1].count == 3,
                  let code = Int(first[1]), (100...599).contains(code), code != 101 else { throw invalid() }
            var headers: [String: String] = [:]
            for line in lines.dropFirst() {
                guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { throw invalid() }
                let key = String(line[..<colon]).lowercased(), value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                guard key.range(of: "^[a-z0-9!#$%&'*+.^_`|~-]+$", options: .regularExpression) != nil,
                      !value.unicodeScalars.contains(where: { $0.value < 32 && $0.value != 9 || $0.value == 127 }),
                      headers[key] == nil || !["content-length", "transfer-encoding"].contains(key) else { throw invalid() }
                headers[key] = value
            }
            if code < 200 { informational += 1; guard informational <= 8 else { throw invalid() }; continue }
            status = code
            if code == 204 || code == 304 { return response() }
            if let transfer = headers["transfer-encoding"] {
                guard transfer.lowercased() == "chunked", headers["content-length"] == nil else { throw invalid() }; chunked = true
            }
            if let size = headers["content-length"] {
                guard !size.isEmpty, size.allSatisfy({ $0.isASCII && $0.isNumber }), let n = Int(size), n >= 0 else { throw invalid() }; length = n
            }
        }
        if chunked {
            while true {
                if endingChunks {
                    guard let end = buffer.range(of: Data("\r\n".utf8)) else {
                        guard trailerBytes + buffer.count <= limit, !complete else { throw invalid() }; return nil
                    }
                    let line = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
                    trailerBytes += end.upperBound - buffer.startIndex
                    guard trailerBytes <= limit else { throw invalid() }
                    buffer.removeSubrange(..<end.upperBound)
                    if line.isEmpty { return response() }
                    guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { throw invalid() }
                    let key = String(line[..<colon]).lowercased(), value = line[line.index(after: colon)...]
                    guard key.range(of: "^[a-z0-9!#$%&'*+.^_`|~-]+$", options: .regularExpression) != nil,
                          !["content-length", "transfer-encoding", "host", "connection", "trailer", "upgrade", "content-encoding", "content-range"].contains(key),
                          !value.unicodeScalars.contains(where: { $0.value < 32 && $0.value != 9 || $0.value == 127 }) else { throw invalid() }
                    continue
                }
                if needsCRLF {
                    guard buffer.count >= 2 else { if complete { throw invalid() }; return nil }
                    guard buffer.prefix(2) == Data("\r\n".utf8) else { throw invalid() }
                    buffer.removeFirst(2); needsCRLF = false
                }
                if remaining == nil {
                    guard let end = buffer.range(of: Data("\r\n".utf8)) else { guard buffer.count <= 128, !complete else { throw invalid() }; return nil }
                    guard end.lowerBound - buffer.startIndex <= 128 else { throw invalid() }
                    let line = String(decoding: buffer[..<end.lowerBound], as: UTF8.self).split(separator: ";", omittingEmptySubsequences: false)[0]
                    guard !line.isEmpty, line.count <= 16, line.allSatisfy({ $0.isASCII && $0.isHexDigit }), let n = Int(line, radix: 16), n >= 0 else { throw invalid() }
                    buffer.removeSubrange(..<end.upperBound)
                    if n == 0 { endingChunks = true; continue }; remaining = n
                }
                let count = min(remaining!, buffer.count, limit + 1 - body.count)
                body.append(buffer.prefix(count)); buffer.removeFirst(count); remaining! -= count
                if body.count > limit { return response(truncated: true) }
                if remaining == 0 { remaining = nil; needsCRLF = true; continue }
                if complete { throw invalid() }; return nil
            }
        }
        let count = min(buffer.count, length.map { max(0, $0 - body.count) } ?? buffer.count, limit + 1 - body.count)
        body.append(buffer.prefix(count)); buffer.removeFirst(count)
        if body.count > limit { return response(truncated: true) }
        if let length { if body.count == length { return response() }; if complete { throw invalid() } }
        else if complete { return response() }
        return nil
    }
    private func response(truncated: Bool = false) -> ConnectionHTTPResponse { .init(status: status!, body: body, truncated: truncated) }
    private func invalid() -> DeviceAPIError { .init(502, "connection_response_invalid", "The connection returned an invalid or incomplete HTTP response. The send may have arrived; do not retry automatically.") }
}
