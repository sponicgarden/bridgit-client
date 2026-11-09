import Foundation

/// A bounded subset of the existing v1 snapshot options. Unsupported controls fail before
/// capture, rather than silently taking a different picture or uploading/keeping it.
public struct CameraRequestOptions {
    public let lens: String?
    public let maxEdge: Int
    public let quality: Double
    public let raw: Bool
    public init(body: [String: Any], query: [String: Any]) throws {
        guard Set(body.keys).isSubset(of: ["camera", "flash", "zoom", "max_width_px", "store", "size", "quality", "format", "raw"]) else { throw DeviceAPIError(422, "unsupported_parameter", "This Apple snapshot supports camera, size, quality, max_width_px, JPEG and return-only storage.") }
        if let value = body["camera"] { guard let text = value as? String else { throw DeviceAPIError(422, "invalid_parameter", "camera must be a string.") }; lens = text } else { lens = nil }
        if let flash = body["flash"] {
            guard let number = flash as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw DeviceAPIError(422, "invalid_parameter", "flash must be true or false.") }
            guard !number.boolValue else { throw DeviceAPIError(501, "unsupported_parameter", "This build does not support flash for host snapshots.") }
        }
        if let zoom = body["zoom"], !(zoom is NSNull) {
            guard let number = zoom as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue == 1 else { throw DeviceAPIError(501, "unsupported_parameter", "This build supports only zoom 1 for host snapshots.") }
        }
        if let format = body["format"], !(format is String && format as! String == "jpeg") { throw DeviceAPIError(501, "unsupported_parameter", "This build returns JPEG snapshots.") }
        if let store = body["store"] {
            let none = (store as? [Any])?.isEmpty == true || store as? String == "none"
            guard none else { throw DeviceAPIError(501, "unsupported_parameter", "Host snapshots are return-only. Capture storage and uploads are not implemented.") }
        }
        let presets = ["agent": 1568, "thumb": 512, "large": 2560]
        var edge = 2048
        if let size = body["size"] { guard let name = size as? String, let pixels = presets[name] else { throw DeviceAPIError(501, "unsupported_parameter", "Choose agent, thumb or large. Original-size snapshots are unavailable in this build.") }; edge = pixels }
        if let width = body["max_width_px"] { edge = try Self.integer(width, name: "max_width_px", range: 64...4096) }
        let q = try body["quality"].map { try Self.integer($0, name: "quality", range: 40...100) } ?? ((body["size"] as? String == "thumb") ? 75 : (body["size"] as? String == "large") ? 90 : 85)
        maxEdge = edge; quality = Double(q) / 100
        if let value = query["raw"] ?? body["raw"] {
            if let string = value as? String, ["0", "1", "true", "false"].contains(string) { raw = string == "1" || string == "true" }
            else if let number = value as? NSNumber, [0, 1].contains(number.doubleValue) { raw = number.boolValue }
            else { throw DeviceAPIError(422, "invalid_parameter", "raw must be true or false, or 1 or 0.") }
        } else { raw = false }
    }
    private static func integer(_ value: Any, name: String, range: ClosedRange<Int>) throws -> Int {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite, number.doubleValue.rounded() == number.doubleValue,
              number.doubleValue >= Double(range.lowerBound), number.doubleValue <= Double(range.upperBound) else { throw DeviceAPIError(422, "invalid_parameter", "\(name) must be an integer from \(range.lowerBound) to \(range.upperBound).") }
        return number.intValue
    }
}
