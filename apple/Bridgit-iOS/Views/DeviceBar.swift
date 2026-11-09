import SwiftUI

/// Switch between device views and return directly to this phone or tablet.
struct DeviceBar: View {
    var device: PortalDevice?
    var status: String
    var devices: [PortalDevice]
    var showsBuildStamp = true
    var select: (String) -> Void
    @Environment(\.dynamicTypeSize) private var sizeClass

    private var name: String { device?.name ?? DeviceModel.name }
    private var canSwitch: Bool { !devices.isEmpty }
    var body: some View {
        Group {
            if sizeClass.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 0) {
                    chooser
                    HStack(alignment: .center) {
                        localButton
                        Spacer(minLength: 8)
                        if showsBuildStamp { BuildStamp(foreground: B.onPrimaryContainer) }
                    }
                }
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { chooser; localButton }
                    VStack(alignment: .leading, spacing: 0) { chooser; localButton }
                }
            }
        }
        .foregroundStyle(B.onPrimaryContainer)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(B.primaryContainer.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 0.5) }
    }

    @ViewBuilder private var chooser: some View {
        if canSwitch {
            switcher
        } else {
            deviceLabel.accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder private var localButton: some View {
        if device != nil {
            Button { select(PortalDevice.localID) } label: {
                Text(UIDevice.current.userInterfaceIdiom == .pad ? "Local tablet" : "Local phone")
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(B.surfaceHigh, in: Capsule())
                    .foregroundStyle(B.onSurface)
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
    }

    @ViewBuilder private var deviceLabel: some View {
        if sizeClass.isAccessibilitySize {
            HStack(spacing: 8) {
                Text(name + " · " + DeviceTypeDescription.label(device?.reportedMetadata ?? DeviceTypeDescription.local))
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                    .layoutPriority(1)
                Spacer(minLength: 0)
                if canSwitch {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 20, weight: .semibold))
                        .accessibilityHidden(true)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .contentShape(Rectangle())
        } else {
            HStack(spacing: 8) {
                Image(systemName: device != nil ? "eye" : (UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"))
                    .accessibilityHidden(true)
                Text(name + " · " + DeviceTypeDescription.label(device?.reportedMetadata ?? DeviceTypeDescription.local))
                    .font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                if showsBuildStamp { BuildStamp(foreground: B.onPrimaryContainer) }
                if canSwitch {
                    Image(systemName: "chevron.up.chevron.down").font(.caption.weight(.semibold))
                        .accessibilityHidden(true)
                }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
    }

    private var switcher: some View {
        Menu {
            Button { select(PortalDevice.localID) } label: {
                Label("This device", systemImage: device == nil ? "checkmark" : (UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"))
            }
            ForEach(devices) { d in
                Button { select(d.id) } label: {
                    Label(d.name + " · " + DeviceTypeDescription.label(d.reportedMetadata), systemImage: d.id == device?.id ? "checkmark" : (d.isLive ? "dot.radiowaves.left.and.right" : "cloud"))
                }
            }
        } label: { deviceLabel }
        .buttonStyle(.plain)
        .accessibilityLabel("\(name). \(status). Switch device.")
    }
}

private struct LinkedDeviceBar: View {
    @ObservedObject var live: PhoneLive
    var devices: [PortalDevice]
    var showsBuildStamp = true
    var select: (String) -> Void

    private var status: String {
        switch live.source {
        case .live: "Live"
        case .stale: "Last known"
        case .snapshot: "Snapshot"
        case .sample: "Sample"
        case .none: live.liveError == nil ? "Waiting" : "Unavailable"
        }
    }

    var body: some View {
        DeviceBar(device: live.device, status: status, devices: devices, showsBuildStamp: showsBuildStamp, select: select)
    }
}

/// A local-only destination can opt out of the linked-device selector while it is displayed.
struct LocalDestinationPreference: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

struct ActivityToolbarStampPreference: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

private struct DeviceBarModifier: ViewModifier {
    let live: PhoneLive?
    let devices: [PortalDevice]
    let cameraBackground: Bool
    var hidden = false
    var showsBuildStamp = true
    var rootStampInContent = false
    let select: (String) -> Void
    @State private var localDestination = false
    @State private var activityToolbarStamp = false
    @Environment(\.buildStampVisible) private var activeSurface

    func body(content: Content) -> some View {
        VStack(spacing: 0) {
            if activeSurface && !hidden && !localDestination {
                if let live {
                    LinkedDeviceBar(live: live, devices: devices, showsBuildStamp: showsBuildStamp, select: select)
                }
            }
            content.overlay(alignment: .topTrailing) {
                if activeSurface && showsBuildStamp && !hidden && (localDestination || (live == nil && !rootStampInContent)) {
                    Group {
                        if cameraBackground { BuildStamp().padding(2).background(.black.opacity(0.7)).environment(\.colorScheme, .dark) }
                        else { BuildStamp() }
                    }.padding(.top, 2).padding(.trailing, activityToolbarStamp ? 68 : 16)
                }
            }
        }
        .onPreferenceChange(LocalDestinationPreference.self) { localDestination = $0 }
        .onPreferenceChange(ActivityToolbarStampPreference.self) { activityToolbarStamp = $0 }
    }
}

extension View {
    func deviceBar(_ live: PhoneLive?, devices: [PortalDevice], cameraBackground: Bool, hidden: Bool = false, showsBuildStamp: Bool = true, rootStampInContent: Bool = false, select: @escaping (String) -> Void) -> some View {
        modifier(DeviceBarModifier(live: live, devices: devices, cameraBackground: cameraBackground, hidden: hidden, showsBuildStamp: showsBuildStamp, rootStampInContent: rootStampInContent, select: select))
    }
}
