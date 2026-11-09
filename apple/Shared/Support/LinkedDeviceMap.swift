import SwiftUI

/// A view pairing establishes a hub/satellite relationship. Account visibility alone does not.
struct LinkedDeviceMap<Local: View, Peer: View>: View {
    let devices: [PortalDevice]
    @ViewBuilder var local: () -> Local
    @ViewBuilder var peer: (PortalDevice) -> Peer

    var body: some View {
        let hubs = devices.filter { $0.pairing != nil }
        let accounts = devices.filter { $0.pairing == nil }
        VStack(alignment: .leading, spacing: 8) {
            if hubs.isEmpty { roleLabel("Hub", symbol: "point.3.connected.trianglepath.dotted") }
            else { roleLabel("View satellite", symbol: "antenna.radiowaves.left.and.right") }
            local()
            if !hubs.isEmpty {
                Image(systemName: "arrow.down").foregroundStyle(B.outline).accessibilityHidden(true)
                roleLabel("Hub", symbol: "point.3.connected.trianglepath.dotted")
                ForEach(hubs) { device in peer(device) }
            }
            if !accounts.isEmpty {
                Divider()
                roleLabel("Account view", symbol: "cloud")
                ForEach(accounts) { device in peer(device) }
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(B.surfaceLow, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Map of linked devices")
    }

    private func roleLabel(_ name: LocalizedStringKey, symbol: String) -> some View {
        Label(name, systemImage: symbol).font(.subheadline.weight(.semibold))
            .foregroundStyle(B.primary).accessibilityAddTraits(.isHeader)
    }
}
