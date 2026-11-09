import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// The local counterpart of the APK's About this phone, separate from settings and device selection.
struct LocalDeviceInfoView: View {
    @AppStorage(DeviceNamePreference.key, store: AppleFixtureWorkspace.defaults) private var nameOverride = ""
    static var title: String {
        #if os(macOS)
        "About this Mac"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "About this iPad" : "About this phone"
        #endif
    }
    var body: some View {
        Form {
            LabeledContent("Name", value: LocalHostModel.effectiveDeviceName(override: nameOverride))
            LabeledContent("Device type", value: DeviceTypeDescription.label(DeviceTypeDescription.local))
            #if os(macOS)
            LabeledContent("System", value: ProcessInfo.processInfo.operatingSystemVersionString)
            #else
            LabeledContent("Model", value: DeviceModel.name)
            LabeledContent("iOS", value: UIDevice.current.systemVersion)
            #endif
        }.navigationTitle(Self.title)
    }
}
