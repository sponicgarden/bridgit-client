import Foundation

extension AppletLists {
    /// The relay's list includes Pet monitor even when web sharing is off. Use the enabled web pages for this install.
    static func portals(device: PortalDevice?, accountApplets: [AppletSummary]) -> [Portal] {
        guard let device else { return [] }
        return portals(install: device.cloud?.phone ?? device.pairing?.install, account: accountApplets)
    }
}
