import UIKit

/// Pushes contain no work. Normal signed relay admission remains the only request path.
@MainActor final class AppleApplicationDelegate: NSObject, UIApplicationDelegate {
    private var host: LocalHostModel? { AppleHostBootstrap.local.host }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { await host?.receivedAPNsToken(deviceToken) }
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        host?.failedAPNsRegistration(error)
    }
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        guard userInfo["type"] as? String == "relay_wake", let host else { completionHandler(.noData); return }
        Task { let changed = await host.backgroundWake(); completionHandler(changed ? .newData : .noData) }
    }
}

/// The app and OS callbacks share one runtime even when no window is being shown.
@MainActor enum AppleHostBootstrap {
    static let local = LocalAppletModel()
}
