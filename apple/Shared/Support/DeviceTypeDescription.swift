import BridgitCore
import SwiftUI

/// The nickname stays separate. Do not infer type from a name, model, version or role.
enum DeviceTypeDescription {
    static var local: ReportedDeviceMetadata {
#if os(macOS)
        .init(platform: "macos", deviceType: "computer")
#else
        {
            if ProcessInfo.processInfo.isiOSAppOnMac { return .init(platform: "macos", deviceType: "computer") }
            switch UIDevice.current.userInterfaceIdiom {
            case .phone: return .init(platform: "ios", deviceType: "phone")
            case .pad: return .init(platform: "ios", deviceType: "tablet")
            case .mac: return .init(platform: "macos", deviceType: "computer")
            default: return .init(platform: "ios", deviceType: nil)
            }
        }()
#endif
    }

    static func label(_ metadata: ReportedDeviceMetadata) -> String {
        switch (metadata.platform, metadata.deviceType) {
        case ("ios", "phone"): return String(localized: "iPhone")
        case ("ios", "tablet"), ("ipados", "tablet"): return String(localized: "iPad")
        case ("macos", "computer"): return String(localized: "Mac")
        default: break
        }
        let type: String
        switch metadata.deviceType {
        case "phone": type = String(localized: "Phone")
        case "tablet": type = String(localized: "Tablet")
        case "computer": type = String(localized: "Computer")
        case "tv": type = String(localized: "TV")
        case "watch": type = String(localized: "Watch")
        case "camera": type = String(localized: "Camera")
        default: type = String(localized: "Device")
        }
        let platform: String?
        switch metadata.platform {
        case "android": platform = "Android"
        case "ios": platform = "iOS"
        case "ipados": platform = "iPadOS"
        case "macos": platform = "macOS"
        case "windows": platform = "Windows"
        case "linux": platform = "Linux"
        case "chromeos": platform = "ChromeOS"
        default: platform = nil
        }
        return platform.map { $0 + " · " + type } ?? type
    }

    static func symbol(_ metadata: ReportedDeviceMetadata) -> String {
        switch metadata.deviceType {
        case "phone": "iphone.gen3"
        case "tablet": "ipad"
        case "computer": "desktopcomputer"
        case "tv": "tv"
        case "watch": "applewatch"
        case "camera": "camera"
        default: "rectangle.dashed"
        }
    }
}

/// Keep the reported nickname and device descriptor separate, with a narrow-width fallback.
struct DeviceNameLabel: View {
    let name: String
    let metadata: ReportedDeviceMetadata
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(name).font(.headline)
                Text(DeviceTypeDescription.label(metadata)).font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(name).font(.headline)
                Text(DeviceTypeDescription.label(metadata)).font(.caption).foregroundStyle(B.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
