import Foundation
import ImageIO
import Vision

enum AppletImageText {
    static let maxBytes = 16 * 1024 * 1024

    static func read(_ data: Data) async throws -> String {
        try Task.checkCancellation()
        guard !data.isEmpty, data.count <= maxBytes,
              let source = CGImageSourceCreateWithData(data as CFData,nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...32000).contains(width), (1...32000).contains(height), width * height <= 80000000,
              let image = CGImageSourceCreateThumbnailAtIndex(source,0,[
                kCGImageSourceCreateThumbnailFromImageAlways:true,
                kCGImageSourceCreateThumbnailWithTransform:true,
                kCGImageSourceThumbnailMaxPixelSize:4096
              ] as CFDictionary) else { throw AppletRuntimeError.invalid("Select an image up to 16 MiB and 80 megapixels") }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        try VNImageRequestHandler(cgImage:image).perform([request])
        try Task.checkCancellation()
        let text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator:"\n")
        guard !text.isEmpty, text.utf8.count <= 65536 else { throw AppletRuntimeError.invalid("No readable text or too much text in the image") }
        return text
    }
}
