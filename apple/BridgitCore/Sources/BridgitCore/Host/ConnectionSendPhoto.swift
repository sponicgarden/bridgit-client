import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Created only after the persisted kept reference passes the caller's existing media scope.
public struct ConnectionSendPhoto: Sendable {
    public let jpeg: Data
    public let applet: String
}

extension OutboundConnectionService {
    static let photoMaxBytes = 1_000_000
    static func sendJPEG(_ bytes: Data) throws -> Data {
        guard !bytes.isEmpty, bytes.count <= 10 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 1280,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw DeviceAPIError(422, "invalid_photo", "The kept photo cannot be read.") }
        for quality in [0.85, 0.70, 0.55, 0.40] {
            let output = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else {
                throw DeviceAPIError(422, "invalid_photo", "The kept photo cannot be encoded.")
            }
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { throw DeviceAPIError(422, "invalid_photo", "The kept photo cannot be encoded.") }
            if output.length <= photoMaxBytes { return output as Data }
        }
        throw DeviceAPIError(413, "photo_too_large", "The photo is still over 1 MB at low quality.")
    }
}

extension AppletStore {
    public func connectionPhoto(ref: String, agent: HostAgent) throws -> ConnectionSendPhoto {
        guard agent.allows(.appletsMediaRead) else { throw DeviceAPIError(403, "agent_not_allowed", "Sending a kept photo requires the owner's existing applet media read grant.") }
        guard ref.range(of: "^kept:[a-z0-9]{1,40}$", options: .regularExpression) != nil else { throw DeviceAPIError(422, "invalid_parameter", "photo must be a kept photo reference.") }
        guard let applet = try connectionPhotoApplet(ref: ref), agent.allowsApplet(applet, operation: .appletsMediaRead) else {
            throw DeviceAPIError(404, "not_found", "No kept photo is available to this sender.")
        }
        let bytes: Data
        do { bytes = try photo(applet: applet, ref: ref) }
        catch { throw DeviceAPIError(404, "not_found", "No kept photo is available to this sender.") }
        return .init(jpeg: try OutboundConnectionService.sendJPEG(bytes), applet: applet)
    }
}
