import SwiftUI

/// The bar across the top of every screen: which device the portal is showing (owner, 5 Nov 2026). Home, Applets,
/// Camera, Agents, the Devices map and More all follow the one chosen phone, so this names it everywhere, as the
/// Android app's hub bar does on a satellite switched to its hub ("Showing <name> · your hub").
/// With more than one phone it is also the switch.
struct DeviceBar: View {
    var device: PortalDevice
    var devices: [PortalDevice]
    var select: (String) -> Void
    @Environment(\.dynamicTypeSize) private var sizeClass

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: device.isLive ? "dot.radiowaves.left.and.right" : "cloud")
                .font(.system(size: 18, weight: .semibold))
                .accessibilityHidden(true)
            Text("Showing \(device.name)")
                .frostText(.supporting, weight: .semibold)
                .lineLimit(sizeClass.isAccessibilitySize ? 2 : 1).truncationMode(.middle)
            Spacer(minLength: 8)
            Text(device.isLive ? "Live" : "Snapshot")
                .frostText(.supporting)
            if devices.count > 1 {
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 14, weight: .semibold))
                    .accessibilityHidden(true)
            }
        }
        .foregroundStyle(B.onPrimaryContainer)
        .padding(.horizontal, 16).padding(.vertical, 8)
        .frame(minHeight: 48)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(B.primaryContainer.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) { Rectangle().fill(B.outlineVariant).frame(height: 0.5) }
        .contentShape(Rectangle())
        .overlay { if devices.count > 1 { switcher } }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Showing \(device.name). \(device.isLive ? "Live" : "Snapshot")." + (devices.count > 1 ? " Switch phone." : ""))
        .accessibilityAddTraits(devices.count > 1 ? .isButton : [])
    }

    private var switcher: some View {
        Menu {
            ForEach(devices) { d in
                Button { select(d.id) } label: {
                    Label(d.name, systemImage: d.id == device.id ? "checkmark" : (d.isLive ? "dot.radiowaves.left.and.right" : "cloud"))
                }
            }
        } label: { Color.clear }
    }
}

extension View {
    /// Puts the device bar above this screen, outside its navigation bar so pushed screens keep it.
    func deviceBar(_ device: PortalDevice?, devices: [PortalDevice], select: @escaping (String) -> Void) -> some View {
        VStack(spacing: 0) {
            if let device { DeviceBar(device: device, devices: devices, select: select) }
            self
        }
    }
}
