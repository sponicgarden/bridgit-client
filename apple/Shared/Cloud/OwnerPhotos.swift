import Foundation
import BridgitCore
#if os(iOS)
import UIKit
#endif

/// Who checks a photo from the Camera tab: an agent on the phone (usually an installed applet such as
/// Kitchen cleanliness) with its photo watches. GET /v1/portal/photo/targets.
struct PhotoTarget: Identifiable, Equatable {
    var id: String
    var name: String
    var watches: [String]
}

/// One applet's answer to a photo: "state: messy · visible: yes", or why it wasn't checked.
struct PhotoResult: Identifiable, Equatable {
    var id: String
    var name: String
    var lines: [String]
    var text: String { "\(name): " + lines.joined(separator: " · ") }
}

enum OwnerPhotos {
    /// The phone resizes again for its checks; this keeps the relay body small (about 200 KB) and leaves faces sharp.
    static let maxEdge: CGFloat = 1280

    static func parseTargets(_ obj: Any) -> (targets: [PhotoTarget], chosen: [String]) {
        let d = obj as? [String: Any] ?? [:]
        let ts = (d["targets"] as? [[String: Any]] ?? []).compactMap { t -> PhotoTarget? in
            guard let id = t["id"] as? String, !id.isEmpty else { return nil }
            let ws = (t["watches"] as? [[String: Any]] ?? []).compactMap { $0["label"] as? String }
            return PhotoTarget(id: id, name: t["name"] as? String ?? "Applet", watches: ws)
        }
        return (ts, d["chosen"] as? [String] ?? [])
    }

    /// POST /v1/portal/photo's reply: one result per applet. A people check (or "spotting is off") only shows
    /// when it is the only one.
    static func parseResults(_ obj: Any) -> [PhotoResult] {
        let d = obj as? [String: Any] ?? [:]
        return (d["targets"] as? [[String: Any]] ?? []).compactMap { t in
            guard let id = t["id"] as? String else { return nil }
            let checks = t["checks"] as? [[String: Any]] ?? []
            let shown = checks.filter { $0["kind"] as? String != "people" && $0["status"] as? String != "people_checked" }
            let lines = (shown.isEmpty ? checks : shown).map { c -> String in
                let s = c["summary"] as? String ?? ""
                return c["status"] as? String == "skipped" ? "Not checked: \(s)" : s
            }
            return PhotoResult(id: id, name: t["name"] as? String ?? "Applet", lines: lines)
        }
    }

    /// The request body: the photo as base64 JPEG, which camera took it, and the chosen applets (empty = all).
    static func body(jpeg: Data, front: Bool?, targets: [String]) -> [String: Any] {
        var b: [String: Any] = ["data_base64": jpeg.base64EncodedString()]
        if let front { b["camera"] = front ? "front" : "rear" }
        if !targets.isEmpty { b["targets"] = targets }
        return b
    }

    static func targets(from relay: RelayClient, client: String, type: String) async throws -> (targets: [PhotoTarget], chosen: [String]) {
        parseTargets(try await relay.getJSON("/v1/portal/photo/targets", query: ["client": client, "client_type": type, "client_platform": DeviceTypeDescription.local.platform ?? ""]))
    }

    static func send(_ jpeg: Data, front: Bool?, targets: [String], to relay: RelayClient, client: String, type: String) async throws -> [PhotoResult] {
        let response = try await relay.call(method: "POST", path: "/v1/portal/photo", query: ["client": client, "client_type": type, "client_platform": DeviceTypeDescription.local.platform ?? ""],
                                            body: body(jpeg: jpeg, front: front, targets: targets), wait: 50)
        return parseResults(try JSONSerialization.jsonObject(with: response.body.isEmpty ? Data("{}".utf8) : response.body))
    }

    #if os(iOS)
    /// Upright, at most [maxEdge] on the long side, JPEG.
    static func shrink(_ image: UIImage, maxEdge: CGFloat = maxEdge, quality: CGFloat = 0.75) -> Data? {
        let size = image.size
        let scale = min(1, maxEdge / max(size.width, size.height, 1))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let out = UIGraphicsImageRenderer(size: target, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        return out.jpegData(compressionQuality: quality)
    }
    #endif
}
